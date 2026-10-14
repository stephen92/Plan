/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021 AuroraLS3
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.djrapitops.plan.settings.config;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.logging.Logger;

/**
 * Configuration utility for storing settings in a .yml file.
 *
 * @author AuroraLS3
 */
public class Config extends ConfigNode {

    private final Path configFilePath;
    private byte[] loadedBytes;
    private List<String> savedLines;

    public Config(File configFile) {
        super("", null, null);
        this.configFilePath = configFile.toPath();
        Path dir = configFilePath.getParent();

        try {
            if (!Files.isSymbolicLink(dir)) Files.createDirectories(dir);
            if (!Files.exists(configFilePath)) Files.createFile(configFilePath);
            read();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read config " + configFilePath + ": " + e.getMessage(), e);
        }
    }

    public Config(File configFile, ConfigNode defaults) {
        this(configFile);
        copyMissing(defaults);
        try {
            save();
        } catch (IOException e) {
            Logger.getLogger(Config.class.getName()).warning("Could not persist missing defaults to " + configFilePath + ": " + e.getMessage() + ". Decoded settings remain in use.");
        }
    }

    Config() {
        super("", null, null);
        configFilePath = null;
    }

    public boolean fileExists() {
        return Files.exists(configFilePath);
    }

    public synchronized void read() throws IOException {
        byte[] bytes = Files.readAllBytes(configFilePath);
        try (ConfigReader reader = new ConfigReader(new ByteArrayInputStream(bytes))) {
            Config parsed = reader.read();
            childNodes.clear();
            nodeOrder.clear();
            copyAll(parsed);
            loadedBytes = bytes;
            savedLines = new ConfigWriter().createLines(this);
        }
    }

    @Override
    public synchronized void save() throws IOException {
        List<String> lines = new ConfigWriter().createLines(this);
        if (lines.equals(savedLines)) return;
        loadedBytes = new ConfigWriter(configFilePath).writeExpected(this, loadedBytes);
        savedLines = lines;
    }

    /** Add schema fields, never replace an existing value (including explicit null). */
    @Override
    public synchronized void copyMissing(ConfigNode defaults) {
        mergeDefaults(this, defaults, "");
    }

    private void mergeDefaults(ConfigNode target, ConfigNode defaults, String prefix) {
        for (String key : defaults.nodeOrder) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (this instanceof PlanConfig && ("World_aliases.List".equals(path) || "Plugins".equals(path))) {
                // Keep the fixed container available without restoring named example records.
                if (!target.childNodes.containsKey(key)) target.addNode(key);
                continue;
            }
            ConfigNode source = defaults.childNodes.get(key);
            ConfigNode existing = target.childNodes.get(key);
            if (existing == null) {
                ConfigNode added = target.addNode(key);
                added.setComment(new java.util.ArrayList<>(source.comment));
                added.serializedKey = source.serializedKey;
                // Lists contain operator-selected entries, not fixed schema fields.
                added.value = source.value != null && source.value.startsWith("\n- ") ? "" : source.value;
                mergeDefaults(added, source, path);
            } else if (existing.value == null && (!existing.explicitNull || !existing.isLeafNode())) {
                mergeDefaults(existing, source, path);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), configFilePath);
    }

    public Path getConfigFilePath() {
        return configFilePath;
    }
}
