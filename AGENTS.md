# Repository Guidelines

## Project Structure & Module Organization
- MCEF is a Minecraft Java 26.2 mod (the version number is not a typo) that uses the MultiLoader layout with shared logic under `common` and loader-specific wrappers under `fabric` and `neoforge`.
- Place shared Java sources in `common/src/main/java` and assets such as menu JSON, translations, or textures in `common/src/main/resources` so they ship with every loader build.
- Loader-only hooks belong inside each module's `src/main/java` tree; keep local run directories like `run_client` and `run_server` for iterative testing but never depend on them for assets.

## Environment
- You are operating on macOS 27 Beta.

## Coding Style & Naming Conventions
- Target Java 25 with 4-space indentation and UTF-8 encoding (WITHOUT BOM), matching the Gradle toolchain configuration.
- Name resources with the `mcef` prefix (e.g., `mcef.mixins.json`, `mcef.accesswidener`) so Gradle and the loaders resolve them consistently.
- Prefer explicit nullability annotations from `jsr305`.
- Keep Mixin classes lightweight.

## Mixin Structurization
- Place shared mixins under `common/src/main/java/com/cinemamod/mcef/mixins`.
- Declare `@Mixin` classes (and accessor interfaces) with imports grouped at the top, list `@Unique` members before any `@Shadow` declarations, and extend or implement the vanilla type when necessary; supply a suppressed dummy constructor when subclasses require it.
- Suffix every unique field or helper with `_MCEF`. Static finals use all caps with `_MCEF`, and injected method names follow the `before/after/on/wrap/cancel_<VanillaMethod>_MCEF` pattern. Accessor/invoker methods also end in `_MCEF`.
- Cluster related injections together (for example, all `setScreen` hooks in `MixinMinecraft`) and keep helper wrappers private unless a wider contract is required.
- Use short `//` comments for quick reminders and `/** @reason ... */` blocks ahead of injections that change vanilla behavior, matching the authoring tone in existing files.
- MCEF has access to Mixin Extras.
- Prefer using features from Mixin Extras instead of using normal Mixin redirects or overrides.
- When leveraging Mixin Extras (`WrapOperation`, `WrapWithCondition`, etc.), name helpers after the intent (`wrap_..._MCEF`, `cancel_..._MCEF`) and call the provided `Operation` when returning to vanilla flow.

## Minecraft Sources
- You have access to the full Minecraft 26.2 sources in `/Volumes/STUFF/CODING/WORKSPACES/Java/Minecraft Mods/.MINECRAFT_SOURCES/26.2/minecraft/fabric/` and `/Volumes/STUFF/CODING/WORKSPACES/Java/Minecraft Mods/.MINECRAFT_SOURCES/26.2/minecraft/neoforge/`.
- Sources for some libraries used by Minecraft 26.2 are in `/Volumes/STUFF/CODING/WORKSPACES/Java/Minecraft Mods/.MINECRAFT_SOURCES/26.2/libraries/`.
- You have access to the full Minecraft 26.1.1 sources in `/Volumes/STUFF/CODING/WORKSPACES/Java/Minecraft Mods/.MINECRAFT_SOURCES/26.1.1/minecraft/fabric/` and `/Volumes/STUFF/CODING/WORKSPACES/Java/Minecraft Mods/.MINECRAFT_SOURCES/26.1.1/minecraft/neoforge/`.
- Sources for some libraries used by Minecraft 26.1.1 are in `/Volumes/STUFF/CODING/WORKSPACES/Java/Minecraft Mods/.MINECRAFT_SOURCES/26.1.1/libraries/`.
- Use the Minecraft sources for research when working with Minecraft-related code.
- Always prefer the sources provided in the `/<mc_version>/libraries/` folder instead of trying to unpack source JARs yourself. Only do that when the provided sources don't contain what you need.
- Minecraft 26.1.1 is the version before Minecraft 26.2.

## Testing
- Use "Computer Use" and terminal commands to test your changes.
- For simple "does it compile" checks, build the `fabric` and `neoforge` submodules. Never the `common` submodule!
- At the end, also do visual testing via Computer Use, so check if everything still works.
- There is IntelliJ IDE open with the project active, so for visual testing you should run the project via the "Run" button in the top-right of IntelliJ, via Computer Use, instead of trying to manually run it via terminal. Never manually run the project via terminal, always use IntelliJ for launching!
