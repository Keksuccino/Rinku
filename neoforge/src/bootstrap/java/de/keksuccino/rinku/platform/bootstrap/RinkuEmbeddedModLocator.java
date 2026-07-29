package de.keksuccino.rinku.platform.bootstrap;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.niofs.union.UnionFileSystem;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;

/**
 * Exposes the normal Rinku mod nested inside the early-service wrapper to FML 4's mod pipeline.
 *
 * <p>FML 4 moves every early-service JAR into the SERVICE layer and consequently marks the physical
 * wrapper as already located before ordinary mod discovery. Unlike newer FML releases, it does not
 * pass that wrapper to the standard Jar-in-Jar dependency locator. This provider deliberately reuses
 * FML's own locator so its metadata parsing, version selection, and in-place {@code jij:} filesystem
 * behavior remain canonical.</p>
 *
 * <p>This class must remain public, top-level, concrete, and publicly zero-constructible. FML discovers
 * it through {@link java.util.ServiceLoader}; breaking that constructor contract would silently remove
 * the only route from the wrapper to the nested mod.</p>
 */
public final class RinkuEmbeddedModLocator implements IModFileCandidateLocator {

    private static final String JAR_IN_JAR_METADATA = "META-INF/jarjar/metadata.json";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            Path wrapperPath = resolveWrapperPath();
            if (!Files.isRegularFile(wrapperPath)) return;

            // The nested jij: filesystem reads its package path through this SecureJar's UnionFS. FML keeps
            // discovered mod files open for the process lifetime, and the nested ModFile retains this wrapper as
            // its discovery parent. Closing the temporary parent here would invalidate later class/resource reads.
            SecureJar wrapperJar = SecureJar.from(wrapperPath);
            IModFile wrapper = IModFile.create(wrapperJar, JarModsDotTomlModFileReader::manifestParser, IModFile.Type.LIBRARY, ModFileDiscoveryAttributes.DEFAULT);
            if (!Files.isRegularFile(wrapper.findResource(JAR_IN_JAR_METADATA))) throw new IllegalStateException("Rinku's early-service wrapper is missing " + JAR_IN_JAR_METADATA);
            new JarInJarDependencyLocator().scanMods(List.of(wrapper), pipeline);
        } catch (ModLoadingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.technical_error", "Rinku failed to expose its nested NeoForge mod").withCause(exception));
        }
    }

    @Override
    public String toString() {
        return "Rinku embedded-mod locator";
    }

    private static Path resolveWrapperPath() throws Exception {
        CodeSource codeSource = RinkuEmbeddedModLocator.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) throw new IllegalStateException("Rinku's early-service wrapper has no code source");
        Path location = Path.of(codeSource.getLocation().toURI());
        if (location.getFileSystem() instanceof UnionFileSystem unionFileSystem && Files.isRegularFile(unionFileSystem.getPrimaryPath())) return unionFileSystem.getPrimaryPath();
        return location;
    }

}
