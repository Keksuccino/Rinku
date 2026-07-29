#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence, Tuple, Union
from xml.sax.saxutils import escape as xml_escape


DEFAULT_REPO_URL = "https://github.com/Keksuccino/keksuccino.github.io.git"
REQUIRED_PROPERTIES = ("minecraft_version", "mod_version", "mod_id")
HASH_ALGORITHMS = ("sha512", "sha256", "sha1", "md5")
SUPPORTED_LOADER_MODULES = ("fabric", "forge", "neoforge")
LOADER_DISPLAY_NAMES = {
    "fabric": "Fabric",
    "forge": "Forge",
    "neoforge": "NeoForge",
}
INCLUDE_STATEMENT_PATTERN = re.compile(
    r"\binclude\b\s*(?:\((?P<paren>[^)]*)\)|(?P<noparen>[^\r\n]+))"
)
QUOTED_VALUE_PATTERN = re.compile(r"""["']([^"']+)["']""")


class PublishError(Exception):
    pass


@dataclass(frozen=True)
class ProjectProperties:
    project_version: str
    group: str
    minecraft_version: str
    mod_version: str
    mod_id: str
    java_version: int

    @property
    def publish_version(self) -> str:
        return f"{self.mod_version}-{self.minecraft_version}"

    @property
    def group_path(self) -> Path:
        return Path(*self.group.split("."))

    @property
    def group_git_path(self) -> str:
        return self.group.replace(".", "/")


@dataclass(frozen=True)
class Artifact:
    module: str
    loader: str
    artifact_id: str
    filename_base: str
    jar_source: Path
    sources_source: Path


def default_project_dir() -> Path:
    return Path(__file__).resolve().parent


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Publish built Fabric and Forge jars to the GitHub Pages "
            "Maven repository used by publish_to_maven.bat."
        )
    )
    parser.add_argument(
        "--project-dir",
        type=Path,
        default=default_project_dir(),
        help="Project root containing gradle.properties. Defaults to this script's directory.",
    )
    parser.add_argument(
        "--repo-url",
        default=DEFAULT_REPO_URL,
        help=f"Maven repository Git URL. Defaults to {DEFAULT_REPO_URL}.",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=None,
        help=(
            "Directory used for the temporary Maven repository clone. "
            "Defaults to build/maven-publish."
        ),
    )
    return parser.parse_args(argv)


def read_gradle_properties(path: Path) -> Mapping[str, str]:
    if not path.exists():
        raise PublishError(f"Gradle properties file not found: {path}")

    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in raw_line:
            continue

        key, value = raw_line.split("=", 1)
        key = key.strip()
        if not key or key.startswith("#"):
            continue

        properties[key] = value.strip()

    return properties


def load_project_properties(project_dir: Path) -> ProjectProperties:
    properties = read_gradle_properties(project_dir / "gradle.properties")
    missing = [key for key in REQUIRED_PROPERTIES if not properties.get(key)]
    if missing:
        raise PublishError(f"Missing required gradle.properties value(s): {', '.join(missing)}")

    java_version_text = properties.get("java_version") or "17"
    try:
        java_version = int(java_version_text)
    except ValueError as exc:
        raise PublishError(f"java_version must be a number, got: {java_version_text}") from exc

    return ProjectProperties(
        project_version=properties.get("version") or "1.0.0",
        group=properties.get("group") or "de.keksuccino",
        minecraft_version=properties["minecraft_version"],
        mod_version=properties["mod_version"],
        mod_id=properties["mod_id"],
        java_version=java_version,
    )


def strip_gradle_comments(text: str) -> str:
    result = []
    index = 0
    in_single_quote = False
    in_double_quote = False
    in_line_comment = False
    in_block_comment = False
    escaped = False

    while index < len(text):
        char = text[index]
        next_char = text[index + 1] if index + 1 < len(text) else ""

        if in_line_comment:
            if char in "\r\n":
                in_line_comment = False
                result.append(char)
            index += 1
            continue

        if in_block_comment:
            if char == "*" and next_char == "/":
                in_block_comment = False
                index += 2
                continue
            if char in "\r\n":
                result.append(char)
            index += 1
            continue

        if in_single_quote or in_double_quote:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif in_single_quote and char == "'":
                in_single_quote = False
            elif in_double_quote and char == '"':
                in_double_quote = False
            index += 1
            continue

        if char == "/" and next_char == "/":
            in_line_comment = True
            index += 2
            continue
        if char == "/" and next_char == "*":
            in_block_comment = True
            index += 2
            continue

        if char == "'":
            in_single_quote = True
        elif char == '"':
            in_double_quote = True

        result.append(char)
        index += 1

    return "".join(result)


def parse_included_modules(settings_path: Path) -> set[str]:
    if not settings_path.exists():
        return set()

    modules: set[str] = set()
    settings_text = strip_gradle_comments(settings_path.read_text(encoding="utf-8"))
    for include_match in INCLUDE_STATEMENT_PATTERN.finditer(settings_text):
        values = include_match.group("paren") or include_match.group("noparen") or ""
        for value_match in QUOTED_VALUE_PATTERN.finditer(values):
            module_path = value_match.group(1).strip()
            module_name = module_path.strip(":").split(":")[-1]
            if module_name:
                modules.add(module_name)

    return modules


def discover_loader_modules(project_dir: Path) -> Tuple[str, ...]:
    included_modules = set()
    for settings_name in ("settings.gradle", "settings.gradle.kts"):
        included_modules.update(parse_included_modules(project_dir / settings_name))

    if included_modules:
        loader_modules = [
            module
            for module in SUPPORTED_LOADER_MODULES
            if module in included_modules and (project_dir / module).is_dir()
        ]
    else:
        loader_modules = [
            module
            for module in SUPPORTED_LOADER_MODULES
            if (project_dir / module / "build.gradle").exists()
            or (project_dir / module / "build.gradle.kts").exists()
        ]

    if not loader_modules:
        supported = ", ".join(SUPPORTED_LOADER_MODULES)
        raise PublishError(f"No supported loader modules found. Supported modules: {supported}")

    return tuple(loader_modules)


def select_main_jar(project_dir: Path, properties: ProjectProperties, module: str) -> Path:
    libs_dir = project_dir / module / "build" / "libs"
    base_name = f"{properties.mod_id}-{properties.project_version}"
    candidates = (
        [libs_dir / f"{base_name}.jar"]
        if module == "fabric"
        else [
            libs_dir / f"{base_name}-all.jar",
            libs_dir / f"{base_name}.jar",
        ]
    )

    for candidate in candidates:
        if candidate.exists():
            return candidate

    return candidates[0]


def build_artifacts(project_dir: Path, properties: ProjectProperties) -> Tuple[Artifact, ...]:
    loader_modules = discover_loader_modules(project_dir)
    artifacts = []
    for module in loader_modules:
        artifact_id = f"{properties.mod_id}-{module}"
        filename_base = f"{artifact_id}-{properties.publish_version}"
        libs_dir = project_dir / module / "build" / "libs"
        artifacts.append(
            Artifact(
                module=module,
                loader=LOADER_DISPLAY_NAMES[module],
                artifact_id=artifact_id,
                filename_base=filename_base,
                jar_source=select_main_jar(project_dir, properties, module),
                sources_source=(
                    libs_dir / f"{properties.mod_id}-{properties.project_version}-sources.jar"
                ),
            )
        )

    return tuple(artifacts)


def ensure_build_artifacts_exist(artifacts: Sequence[Artifact]) -> None:
    missing = [
        path
        for artifact in artifacts
        for path in (artifact.jar_source, artifact.sources_source)
        if not path.exists()
    ]
    if not missing:
        return

    missing_list = "\n".join(f"  - {path}" for path in missing)
    raise PublishError(
        "Missing build artifact(s):\n"
        f"{missing_list}\n"
        "Build the latest loader jars first, then rerun this script."
    )


def ensure_git_available() -> None:
    if shutil.which("git") is None:
        raise PublishError("Git is required but was not found on PATH.")


def run_command(command: Sequence[Union[str, Path]], cwd: Path) -> None:
    printable_command = [str(part) for part in command]
    print(f"+ {shlex.join(printable_command)}", flush=True)
    try:
        subprocess.run(printable_command, cwd=cwd, check=True)
    except FileNotFoundError as exc:
        raise PublishError(f"Command not found: {printable_command[0]}") from exc
    except subprocess.CalledProcessError as exc:
        raise PublishError(
            f"Command failed with exit code {exc.returncode}: {shlex.join(printable_command)}"
        ) from exc


def run_command_for_status(command: Sequence[Union[str, Path]], cwd: Path) -> int:
    printable_command = [str(part) for part in command]
    print(f"+ {shlex.join(printable_command)}", flush=True)
    try:
        return subprocess.run(printable_command, cwd=cwd, check=False).returncode
    except FileNotFoundError as exc:
        raise PublishError(f"Command not found: {printable_command[0]}") from exc


def sync_maven_repository(work_dir: Path, repo_dir: Path, repo_url: str) -> None:
    work_dir.mkdir(parents=True, exist_ok=True)

    if (repo_dir / ".git").exists():
        run_command(("git", "fetch", "origin", "main"), repo_dir)
        run_command(("git", "checkout", "main"), repo_dir)
        run_command(("git", "pull", "--ff-only"), repo_dir)
        return

    if repo_dir.exists():
        raise PublishError(f"Repository directory exists but is not a Git repository: {repo_dir}")

    run_command(("git", "clone", "--branch", "main", repo_url, repo_dir), work_dir)


def copy_artifact(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


def write_pom(path: Path, group: str, artifact_id: str, version: str) -> None:
    path.write_text(
        "\n".join(
            (
                '<?xml version="1.0" encoding="UTF-8"?>',
                '<project xmlns="http://maven.apache.org/POM/4.0.0" '
                'xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 '
                'https://maven.apache.org/xsd/maven-4.0.0.xsd" '
                'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">',
                "  <!-- This module was also published with a richer model, Gradle metadata,  -->",
                "  <!-- which should be used instead. Do not delete the following line which  -->",
                "  <!-- is to indicate to Gradle or any Gradle module metadata file consumer  -->",
                "  <!-- that they should prefer consuming it instead. -->",
                "  <!-- do_not_remove: published-with-gradle-metadata -->",
                "  <modelVersion>4.0.0</modelVersion>",
                f"  <groupId>{xml_escape(group)}</groupId>",
                f"  <artifactId>{xml_escape(artifact_id)}</artifactId>",
                f"  <version>{xml_escape(version)}</version>",
                "</project>",
                "",
            )
        ),
        encoding="utf-8",
    )


def compute_hashes(path: Path) -> Mapping[str, str]:
    hashers = {algorithm: hashlib.new(algorithm) for algorithm in HASH_ALGORITHMS}
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            for hasher in hashers.values():
                hasher.update(chunk)
    return {algorithm: hashers[algorithm].hexdigest() for algorithm in HASH_ALGORITHMS}


def artifact_metadata(path: Path) -> Mapping[str, Union[str, int]]:
    hashes = compute_hashes(path)
    return {
        "name": path.name,
        "url": path.name,
        "size": path.stat().st_size,
        "sha512": hashes["sha512"],
        "sha256": hashes["sha256"],
        "sha1": hashes["sha1"],
        "md5": hashes["md5"],
    }


def write_module_metadata(
    path: Path,
    properties: ProjectProperties,
    artifact_id: str,
    main_file: Path,
    sources_file: Path,
) -> None:
    main_metadata = artifact_metadata(main_file)
    sources_metadata = artifact_metadata(sources_file)

    metadata = {
        "formatVersion": "1.1",
        "component": {
            "group": properties.group,
            "module": artifact_id,
            "version": properties.publish_version,
            "attributes": {
                "org.gradle.status": "release",
            },
        },
        "variants": [
            {
                "name": "apiElements",
                "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.dependency.bundling": "external",
                    "org.gradle.jvm.version": properties.java_version,
                    "org.gradle.libraryelements": "jar",
                    "org.gradle.usage": "java-api",
                },
                "files": [
                    main_metadata,
                ],
            },
            {
                "name": "runtimeElements",
                "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.dependency.bundling": "external",
                    "org.gradle.jvm.version": properties.java_version,
                    "org.gradle.libraryelements": "jar",
                    "org.gradle.usage": "java-runtime",
                },
                "files": [
                    main_metadata,
                ],
            },
            {
                "name": "sourcesElements",
                "attributes": {
                    "org.gradle.category": "documentation",
                    "org.gradle.dependency.bundling": "external",
                    "org.gradle.docstype": "sources",
                    "org.gradle.usage": "java-runtime",
                },
                "files": [
                    sources_metadata,
                ],
            },
        ],
    }

    path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


def publish_artifact(repo_dir: Path, properties: ProjectProperties, artifact: Artifact) -> str:
    target_dir = (
        repo_dir
        / "maven"
        / properties.group_path
        / artifact.artifact_id
        / properties.publish_version
    )
    main_target = target_dir / f"{artifact.filename_base}.jar"
    sources_target = target_dir / f"{artifact.filename_base}-sources.jar"

    copy_artifact(artifact.jar_source, main_target)
    copy_artifact(artifact.sources_source, sources_target)
    write_pom(
        target_dir / f"{artifact.filename_base}.pom",
        properties.group,
        artifact.artifact_id,
        properties.publish_version,
    )
    write_module_metadata(
        target_dir / f"{artifact.filename_base}.module",
        properties,
        artifact.artifact_id,
        main_target,
        sources_target,
    )

    return f"maven/{properties.group_git_path}/{artifact.artifact_id}/{properties.publish_version}"


def publish(project_dir: Path, repo_url: str, work_dir: Path) -> None:
    ensure_git_available()

    properties = load_project_properties(project_dir)
    artifacts = build_artifacts(project_dir, properties)
    ensure_build_artifacts_exist(artifacts)

    repo_dir = work_dir / "keksuccino.github.io"

    print(f"Publishing {properties.mod_id} {properties.publish_version}")
    print(f"Project: {project_dir}")
    print(f"Maven clone: {repo_dir}")

    sync_maven_repository(work_dir, repo_dir, repo_url)

    staged_paths = []
    for artifact in artifacts:
        print(f"Staging {artifact.loader} artifact: {artifact.filename_base}")
        staged_paths.append(publish_artifact(repo_dir, properties, artifact))

    run_command(("git", "add", *staged_paths), repo_dir)
    diff_status = run_command_for_status(("git", "diff", "--cached", "--quiet", "--"), repo_dir)
    if diff_status == 0:
        print("No changes to publish.")
        return
    if diff_status != 1:
        raise PublishError(f"Git diff failed with exit code {diff_status}.")

    run_command(
        ("git", "commit", "-m", f"Publish {properties.mod_id} {properties.publish_version}"),
        repo_dir,
    )
    run_command(("git", "push", "origin", "main"), repo_dir)
    print(f"Artifacts published to {repo_url}")


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    project_dir = args.project_dir.resolve()
    work_dir = (args.work_dir or (project_dir / "build" / "maven-publish")).resolve()

    try:
        publish(project_dir, args.repo_url, work_dir)
    except PublishError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Interrupted.", file=sys.stderr)
        return 130

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
