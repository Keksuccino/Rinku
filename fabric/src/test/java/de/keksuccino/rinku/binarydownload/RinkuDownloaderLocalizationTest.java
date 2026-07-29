package de.keksuccino.rinku.binarydownload;

import net.minecraft.locale.Language;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guards the complete localized text contract rendered by the downloader screen. */
class RinkuDownloaderLocalizationTest {

    @Test
    void englishLocaleDefinesEveryDownloaderScreenTranslation() throws IOException {
        Map<String, String> translations = new HashMap<>();
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream("/assets/rinku/lang/en_us.json"), "Rinku en_us.json must be present")) {
            Language.loadFromJson(input, translations::put);
        }

        Map<String, String> expected = new HashMap<>();
        expected.put("rinku.downloader.title", "Rinku is downloading required libraries...");
        expected.put("rinku.downloader.progress", "%s%%");
        expected.put("rinku.downloader.task.preparing", "Preparing download");
        expected.put("rinku.downloader.task.downloading_framework", "Downloading Chromium Embedded Framework");
        expected.put("rinku.downloader.task.downloading_checksum", "Downloading checksum");
        expected.put("rinku.downloader.task.extracting", "Extracting");
        expected.put("rinku.downloader.task.failed_library_paths", "Failed to prepare Rinku library paths");
        expected.put("rinku.downloader.task.failed_initialization", "Failed to initialize JCEF downloader");
        expected.put("rinku.downloader.task.failed_configuration", "JCEF downloader failed due to an invalid configuration");

        for (Map.Entry<String, String> entry : expected.entrySet()) assertEquals(entry.getValue(), translations.get(entry.getKey()), entry.getKey());
        long downloaderTranslationCount = translations.keySet().stream().filter(key -> key.startsWith("rinku.downloader.")).count();
        assertEquals(expected.size(), downloaderTranslationCount, "Unexpected downloader translation key count");
    }
}
