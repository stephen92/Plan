/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.settings.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.*;

class ConfigPersistenceTest {
    @TempDir
    Path directory;

    private Config defaults(String text) {
        try (ConfigReader reader = new ConfigReader(new Scanner(text))) {
            return reader.read();
        }
    }

    @Test
    void fillsMissingFieldsWithoutReplacingNullListsCommentsOrUnknownKeys() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "# operator note\nSettings:\n  Disabled:\n  Selected:\n    - custom\n  Unknown: keep\n# trailing note\n");
        Config schema = defaults("Settings:\n  Disabled: true\n  Selected:\n    - example\n  New: 42\n");
        Config config = new Config(file.toFile(), schema);
        assertNull(config.getString("Settings.Disabled"));
        assertEquals(java.util.List.of("custom"), config.getStringList("Settings.Selected"));
        assertEquals("keep", config.getString("Settings.Unknown"));
        assertEquals(42, config.getInteger("Settings.New"));
        String updated = Files.readString(file);
        assertTrue(updated.contains("operator note"));
        assertTrue(updated.contains("trailing note"));
        assertFalse(updated.contains("example"));
        FileTime time = FileTime.fromMillis(1000);
        Files.setLastModifiedTime(file, time);
        new Config(file.toFile(), schema);
        assertEquals(updated, Files.readString(file));
        assertEquals(time, Files.getLastModifiedTime(file));
    }

    @Test
    void readingDoesNotRewriteOperatorFile() throws IOException {
        Path file = directory.resolve("locale.yml");
        String text = "# custom formatting\nmessage: 'My message' # explanation\n";
        Files.writeString(file, text);
        new Config(file.toFile());
        assertEquals(text, Files.readString(file));
    }

    @Test
    void refusesToOverwriteConcurrentEditsAndRetainsDecodedSettings() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "Port: 8804\n");
        Config config = new Config(file.toFile());
        config.set("New", true);
        Files.writeString(file, "Port: 9911\n");
        assertThrows(IOException.class, config::save);
        assertEquals(8804, config.getInteger("Port"));
        assertEquals("Port: 9911\n", Files.readString(file));
    }

    @Test
    void malformedInputRemainsIntact() throws IOException {
        Path file = directory.resolve("config.yml");
        String text = "Port: 'unfinished\n";
        Files.writeString(file, text);
        assertThrows(IllegalStateException.class, () -> new Config(file.toFile(), defaults("Port: 8804\n")));
        assertEquals(text, Files.readString(file));
    }

    @Test
    void quotesInInlineCommentsDoNotBecomePartOfOperatorValues() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "Message: 'hello' # operator's note\nList:\n  - \"hello\" # say \"hi\"\n");
        Config config = new Config(file.toFile(), defaults("New: true\n"));
        assertEquals("hello", config.getString("Message"));
        assertEquals(java.util.List.of("hello"), config.getStringList("List"));
    }

    @Test
    void namespacedWorldAliasesSurviveStartupAndRepeatLoads() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "World_aliases:\n  List:\n    minecraft:overworld: 'Main world'\n    minecraft:the_nether: 'Nether world'\n");
        Config config = new Config(file.toFile(), defaults("New: true\n"));
        assertEquals("Main world", config.getString("World_aliases.List.minecraft:overworld"));
        assertEquals("Nether world", config.getString("World_aliases.List.minecraft:the_nether"));
        config.read();
        assertEquals("Main world", config.getString("World_aliases.List.minecraft:overworld"));
    }

    @Test
    void quotedUnknownKeysKeepTheirMeaningWhenDefaultsArePersisted() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "'Custom: label': value\n'#hidden': keep\n");
        Config config = new Config(file.toFile(), defaults("New: true\n"));
        config.read();
        assertEquals("value", config.getString("Custom: label"));
        assertEquals("keep", config.getString("#hidden"));
    }

    @Test
    void malformedBareLinesAndIndentationAreNotRewritten() throws IOException {
        for (String text : java.util.List.of("Port: 8804\nthis is not a config line\n", "Parent:\n   Port: 8804\n", "Port: 8804\n  Child: value\n")) {
            Path file = directory.resolve("config.yml");
            Files.writeString(file, text);
            assertThrows(IllegalStateException.class, () -> new Config(file.toFile(), defaults("New: true\n")));
            assertEquals(text, Files.readString(file));
        }
    }

    @Test
    void reloadDropsDeletedKeysBeforeAddingMissingDefaults() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "Port: 8804\nRemoved: custom\n");
        Config config = new Config(file.toFile());
        Files.writeString(file, "Port: 9911\n");
        config.read();
        assertFalse(config.contains("Removed"));
        assertEquals(9911, config.getInteger("Port"));
    }

    @Test
    void packagedSchemaDoesNotRestoreRemovedExamplesOrShadowLegacyValues() throws IOException {
        Path file = directory.resolve("config.yml");
        try (var resource = getClass().getResourceAsStream("/assets/plan/config.yml")) {
            Files.copy(resource, file);
        }
        PlanConfig operator = new PlanConfig(file.toFile(), null, null, new utilities.TestPluginLogger());
        operator.removeNode("World_aliases.List.world");
        operator.removeNode("World_aliases.Regex");
        operator.removeNode("Plugins.Factions");
        operator.removeNode("Data_gathering.Join_addresses.Filter_out_from_data");
        operator.removeNode("Webserver.Port");
        operator.set("WebServer.Port", 9911);
        operator.set("Custom", "operator value");
        operator.save();
        new com.djrapitops.plan.settings.config.changes.ConfigUpdater(new utilities.TestPluginLogger(),
                org.mockito.Mockito.mock(com.djrapitops.plan.utilities.logging.ErrorLogger.class)).applyConfigUpdate(operator);
        try (ConfigReader reader = new ConfigReader(getClass().getResourceAsStream("/assets/plan/config.yml"))) {
            operator.copyMissing(reader.read());
        }
        operator.save();
        assertFalse(operator.contains("World_aliases.List.world"));
        assertFalse(operator.contains("Plugins.Factions"));
        assertTrue(operator.getStringList("World_aliases.Regex").isEmpty());
        assertTrue(operator.getStringList("Data_gathering.Join_addresses.Filter_out_from_data").isEmpty());
        assertEquals(9911, operator.getInteger("Webserver.Port"));
        assertFalse(operator.contains("WebServer.Port"));
        assertEquals("operator value", operator.getString("Custom"));
    }

    @Test
    void failedDefaultPersistenceRetainsDecodedSettings() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "Port: 9911\n");
        Config config = new Config(file.toFile(), defaults("Port: 8804\nAdded: true\n")) {
            @Override
            public void save() throws IOException {
                throw new IOException("Simulated denied write");
            }
        };
        assertEquals(9911, config.getInteger("Port"));
        assertTrue(config.getBoolean("Added"));
        assertEquals("Port: 9911\n", Files.readString(file));
    }
}
