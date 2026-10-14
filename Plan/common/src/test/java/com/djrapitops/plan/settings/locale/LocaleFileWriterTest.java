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
package com.djrapitops.plan.settings.locale;

import com.djrapitops.plan.storage.file.FileResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for {@link LocaleFileWriter}.
 *
 * @author AuroraLS3
 */
class LocaleFileWriterTest {

    @Test
    void preservesOperatorNullUnknownKeysAndCommentsOnRepeatedUpdates(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("locale.yml");
        String key = LocaleSystem.getKeys().keySet().iterator().next();
        StringBuilder operatorFile = new StringBuilder("# operator locale\n");
        String[] parts = key.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            operatorFile.append("  ".repeat(i)).append(parts[i]).append(":\n");
        }
        Files.writeString(file, operatorFile + "unknown_key: custom\n");
        new LocaleFileWriter(new Locale()).writeToFile(file.toFile());
        var config = new com.djrapitops.plan.settings.config.Config(file.toFile());
        assertEquals(null, config.getString(key));
        assertEquals("custom", config.getString("unknown_key"));
        assertTrue(Files.readString(file).contains("operator locale"));
        FileTime time = FileTime.fromMillis(1000);
        Files.setLastModifiedTime(file, time);
        new LocaleFileWriter(new Locale()).writeToFile(file.toFile());
        assertEquals(time, Files.getLastModifiedTime(file));
    }

    @Test
    void writesAllKeys(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("localeFile.yml").toFile();
        new LocaleFileWriter(new Locale()).writeToFile(file);

        long expected = LocaleSystem.getKeys().size();
        long result = FileResource.lines(file).stream().filter(line -> !line.endsWith(":")).count();
        assertEquals(expected, result);
    }

}
