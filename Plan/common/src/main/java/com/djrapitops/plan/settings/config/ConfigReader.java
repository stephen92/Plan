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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Reader for parsing {@link Config} out of file-lines.
 * <p>
 * ConfigReader can read a single file at a time, so it is NOT thread safe.
 *
 * @author AuroraLS3
 */
public class ConfigReader implements Closeable {

    private final Scanner scanner;
    private final List<String> unboundComment = new ArrayList<>();
    private boolean closed;
    private ConfigNode previousNode;
    private ConfigNode parent;
    // Indent mode assumes the number of spaces used to indent the file.
    // If the first found indent is smaller, that will be used instead.
    private int indentMode = 4;
    private int lastDepth = -1;
    private String lineDanglingComment;

    /**
     * Create a new ConfigReader for a Path.
     *
     * @param filePath Path to a config file.
     * @throws IOException If the path can not be read.
     */
    public ConfigReader(Path filePath) throws IOException {
        this(Files.newInputStream(filePath));
    }

    /**
     * Create a new ConfigReader for an InputStream.
     *
     * @param in InputStream of a resource that is a config.
     */
    public ConfigReader(InputStream in) {
        this(new Scanner(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    /**
     * Create a new ConfigReader for a BufferedReader.
     *
     * @param bufferedReader BufferedReader of a resource that is a config.
     */
    public ConfigReader(BufferedReader bufferedReader) {
        this(new Scanner(bufferedReader));
    }

    /**
     * Create a new ConfigReader for a Scanner.
     *
     * @param scanner Scanner of a resource that is a config.
     */
    public ConfigReader(Scanner scanner) {
        this.scanner = scanner;
        closed = false;
    }

    /**
     * Read the resource into a {@link Config}.
     *
     * @return Config parsed from the lines found in the given resource.
     * @throws IllegalStateException If the configReader is closed by calling {@link ConfigReader#close()}
     */
    public Config read() {
        if (closed) throw new IllegalStateException("ConfigReader has been closed.");

        Config config = new Config();

        previousNode = config;
        parent = config;

        while (scanner.hasNextLine()) {
            String line = readNewLine();
            String trimmed = line.trim();
            handleLine(line, trimmed);
        }

        config.setComment(new ArrayList<>(unboundComment));
        return config;
    }

    private void handleLine(String line, String trimmed) {
        if (trimmed.isEmpty()) {
            // Add an empty row comment in case user wants spacers
            handleCommentLine(" ");
        } else if (trimmed.startsWith("#")) {
            handleCommentLine(trimmed);
        } else {
            // Determine where the node belongs

            int currentDepth = findCurrentDepth(line);
            parent = findParent(lastDepth, currentDepth);
            if (parent == null) throw new IllegalStateException("Could not determine parent on line: \"" + line + "\"");
            // Historical Plan configs stored DatePattern beneath a boolean setting.
            String parentPath = parent.getKey(true);
            boolean legacyDateSetting = "Customization.Formatting.Dates.RecentDays".equals(parentPath)
                    || "Formatting.Dates.Show_recent_day_names".equals(parentPath);
            if (!trimmed.startsWith("- ") && parent.value != null && !legacyDateSetting) {
                throw new IllegalStateException("A scalar config value cannot contain child settings: " + line + ". Fix its indentation before reloading.");
            }

            // Get the node the line belongs to
            previousNode = parseNode(trimmed);
            if (previousNode == null) throw new IllegalStateException("Could not parse node on line: \"" + line + "\"");

            lastDepth = currentDepth;
            handleUnboundComments();
        }
    }

    private String readNewLine() {
        String line = scanner.nextLine();
        lineDanglingComment = null;
        if (line.stripLeading().startsWith("#")) return line;
        int scalarStart = line.stripLeading().startsWith("- ") ? line.indexOf('-') + 2 : findKeySeparator(line) + 1;
        while (scalarStart < line.length() && Character.isWhitespace(line.charAt(scalarStart))) scalarStart++;
        int commentStart = scalarStart;
        if (scalarStart < line.length() && (line.charAt(scalarStart) == '\'' || line.charAt(scalarStart) == '"')) {
            commentStart = findClosingQuote(line, scalarStart) + 1;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t' && line.substring(0, i).isBlank()) {
                throw new IllegalStateException("Tabs are not supported for config indentation: " + line);
            }
            if (i >= commentStart && c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                lineDanglingComment = line.substring(i);
                line = line.substring(0, i);
                break;
            }
        }
        return line;
    }

    private int findClosingQuote(String line, int start) {
        char quote = line.charAt(start);
        for (int i = start + 1; i < line.length(); i++) {
            if (line.charAt(i) != quote) continue;
            if (quote == '"') {
                int escapes = 0;
                for (int j = i - 1; j > start && line.charAt(j) == '\\'; j--) escapes++;
                if (escapes % 2 != 0) continue;
            } else if (i + 1 < line.length() && line.charAt(i + 1) == quote) {
                i++;
                continue;
            }
            String remaining = line.substring(i + 1).stripLeading();
            if (remaining.isEmpty() || remaining.startsWith("#")) return i;
        }
        throw new IllegalStateException("Unclosed quote in config line: " + line + ". Close the quote before reloading.");
    }

    private ConfigNode parseNode(String line) {
        // Parse a node "Key: value"
        int separator = findKeySeparator(line);
        if (separator < 0 || line.startsWith("- ")) {
            return handleMultiline(line);
        }
        String serializedKey = line.substring(0, separator).trim();
        String key = new ConfigValueParser.StringParser().compose(serializedKey);
        String value = line.substring(separator + 1).trim();
        ConfigNode node = handleNewNode(key, !value.isEmpty() ? value : null);
        node.serializedKey = serializedKey;
        return node;
    }

    private int findKeySeparator(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '\'' || c == '"') && (i == 0 || line.substring(0, i).isBlank() || quote == c)) {
                quote = quote == 0 ? c : 0;
            }
            // Colons in identifiers such as minecraft:overworld are part of the key.
            if (quote == 0 && c == ':' && (i + 1 == line.length() || Character.isWhitespace(line.charAt(i + 1)))) return i;
        }
        return -1;
    }

    private ConfigNode handleMultiline(String line) {
        if (line.startsWith("- ")) {
            return handleListCase(line);
        } else {
            throw new IllegalStateException("Expected 'Key: value' or '- list item' in config line: " + line + ". Put messages on one quoted line.");
        }
    }

    private void handleCommentLine(String line) {
        unboundComment.add(line.substring(1).trim());
    }

    private void handleUnboundComments() {
        if (lineDanglingComment != null) {
            unboundComment.add(lineDanglingComment.substring(1).trim());
        }
        if (!unboundComment.isEmpty()) {
            List<String> currentComment = previousNode.getComment();
            if (currentComment == null || currentComment.isEmpty()) {
                previousNode.setComment(new ArrayList<>(unboundComment));
            } else {
                currentComment.addAll(unboundComment);
            }
            unboundComment.clear();
        }
    }

    private ConfigNode handleNewNode(String key, String value) {
        if (key.isEmpty() || parent.childNodes.containsKey(key)) {
            throw new IllegalStateException("Empty or duplicate config key: " + key);
        }
        ConfigNode newNode = new ConfigNode(key, parent, value);
        newNode.explicitNull = value == null;
        return parent.addChild(newNode);
    }

    private ConfigNode handleListCase(String line) {
        // Ensure we are not appending to null value
        if (previousNode.value == null) {
            previousNode.value = "";
        }
        // Append list item to the value.
        previousNode.value += "\n- " + line.substring(2).trim();
        return previousNode;
    }

    private ConfigNode findParent(int previousDepth, int currentDepth) {
        if (previousDepth < currentDepth) {
            return previousNode;
        } else if (previousDepth > currentDepth) {
            // Prevents incorrect indent in the case:
            // 1:
            //   2:
            //     3:
            // 1:
            int helperDepth = previousDepth;
            ConfigNode foundParent = previousNode;
            while (helperDepth > currentDepth) {
                helperDepth = foundParent.getNodeDepth();
                foundParent = foundParent.parent; // Moves one level up the tree
            }
            return foundParent;
        } else {
            return parent;
        }
    }

    private int findCurrentDepth(String line) {
        int indent = readIndent(line);

        // Re-define indent mode if indent is 2 for configs indented with 2 spaces.
        if (indent == 2 && indentMode == 4) {
            indentMode = indent;
        }

        int depth;
        if (indent % indentMode == 0) {
            depth = indent / indentMode;
        } else {
            if (line.stripLeading().startsWith("- ") && indent % indentMode == 2) {
                depth = indent / indentMode + 1;
            } else {
                throw new IllegalStateException("Invalid config indentation: " + line + ". Use consistent two- or four-space indentation.");
            }
        }
        return depth;
    }

    private int readIndent(String line) {
        int indentation = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                indentation++;
            } else {
                break;
            }
        }
        return indentation;
    }

    @Override
    public void close() {
        scanner.close();
        closed = true;
    }
}
