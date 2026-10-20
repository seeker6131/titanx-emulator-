package com.titanx.emulator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DependencyResolver {

    private static final int MAX_FILES = 20000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ELF = 200;
    private static final int MAX_REPORT = 120000;

    private DependencyResolver() {}

    private static final class Entry {
        final File file;
        final String relative;

        Entry(File file, String relative) {
            this.file = file;
            this.relative = relative;
        }
    }

    private static void collect(
        File root,
        File dir,
        int depth,
        ArrayList<Entry> result
    ) throws IOException {

        if (depth > MAX_DEPTH
            || result.size() >= MAX_FILES) {
            return;
        }

        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (result.size() >= MAX_FILES) break;

            String base = root.getCanonicalPath()
                + File.separator;

            String path = child.getCanonicalPath();

            if (!path.startsWith(base)) continue;

            if (child.isDirectory()) {
                collect(root, child, depth + 1, result);
            } else if (child.isFile()) {
                result.add(new Entry(
                    child,
                    path.substring(base.length())
                ));
            }
        }
    }

    private static boolean isElf(File file) {
        if (file.length() < 20) return false;

        try (java.io.FileInputStream in =
                 new java.io.FileInputStream(file)) {

            byte[] h = new byte[4];
            if (in.read(h) != 4) return false;

            return (h[0] & 255) == 127
                && h[1] == 'E'
                && h[2] == 'L'
                && h[3] == 'F';

        } catch (IOException e) {
            return false;
        }
    }

    private static ArrayList<String> needed(String report) {
        ArrayList<String> names = new ArrayList<>();
        boolean reading = false;

        for (String line : report.split("\n")) {
            String s = line.trim();

            if (s.startsWith("DT_NEEDED (")) {
                reading = true;
                continue;
            }

            if (!reading) continue;

            if (!s.startsWith("- ")) break;

            String name = s.substring(2).trim();

            if (name.isEmpty()
                || name.startsWith("[")) {
                continue;
            }

            if (!names.contains(name)) {
                names.add(name);
            }
        }

        return names;
    }

    private static String architecture(String report) {
        for (String line : report.split("\n")) {
            if (line.startsWith("Architecture: ")) {
                return line.substring(
                    "Architecture: ".length()
                ).trim();
            }
        }
        return "Unknown";
    }

    private static boolean validReport(String report) {
        return report.startsWith("ELF detected\n")
            && !report.contains("\nError:");
    }

    private static void append(
        StringBuilder sb,
        String value
    ) {
        if (sb.length() + value.length() < MAX_REPORT) {
            sb.append(value);
        }
    }

    public static String analyze(File runtime)
        throws IOException {

        if (runtime == null
            || !runtime.isDirectory()) {
            return "Runtime directory not found";
        }

        ArrayList<Entry> entries = new ArrayList<>();
        collect(runtime, runtime, 0, entries);

        Map<String, ArrayList<Entry>> byName =
            new HashMap<>();

        for (Entry entry : entries) {
            String name = entry.file.getName();

            ArrayList<Entry> matches = byName.get(name);

            if (matches == null) {
                matches = new ArrayList<>();
                byName.put(name, matches);
            }

            matches.add(entry);
        }

        StringBuilder result = new StringBuilder();

        append(result,
            "TITANX DEPENDENCY RESOLVER v0.9\n\n");

        append(result,
            "Runtime files indexed: "
            + entries.size() + "\n");

        int elfCount = 0;
        int present = 0;
        int missing = 0;
        int unchecked = 0;

        Set<String> missingNames = new HashSet<>();

        for (Entry entry : entries) {
            if (!isElf(entry.file)) continue;

            if (elfCount >= MAX_ELF) {
                append(result,
                    "\nELF inspection limit reached\n");
                break;
            }

            elfCount++;

            String report =
                NativeBridge.inspect(entry.file);

            append(result,
                "\n--------------------\n"
                + entry.relative + "\n");

            if (!validReport(report)) {
                unchecked++;
                append(result,
                    "UNVERIFIED: Native inspection failed "
                    + "or unsupported\n"
                    + report + "\n");
                continue;
            }

            String arch = architecture(report);

            append(result,
                "Architecture: " + arch + "\n");

            ArrayList<String> dependencies =
                needed(report);

            if (dependencies.isEmpty()) {
                append(result,
                    "No DT_NEEDED entries reported\n");
                continue;
            }

            for (String dep : dependencies) {
                ArrayList<Entry> matches =
                    byName.get(dep);

                if (matches == null
                    || matches.isEmpty()) {

                    missing++;
                    missingNames.add(dep);

                    append(result,
                        "[MISSING IN ZIP] "
                        + dep + "\n");
                    continue;
                }

                present++;

                append(result,
                    "[FOUND BY NAME] "
                    + dep + "\n");

                int shown = 0;

                for (Entry match : matches) {
                    append(result,
                        "  -> " + match.relative + "\n");

                    if (++shown >= 3) break;
                }
            }
        }

        append(result,
            "\n====================\n"
            + "SUMMARY\n"
            + "ELF inspected: " + elfCount + "\n"
            + "Dependency references found by name: "
            + present + "\n"
            + "Dependency references missing in ZIP: "
            + missing + "\n"
            + "ELF inspections unverified: "
            + unchecked + "\n"
            + "Unique missing names: "
            + missingNames.size() + "\n\n"
            + "IMPORTANT:\n"
            + "Name matches do not prove ABI compatibility.\n"
            + "Missing in ZIP does not mean missing on device.\n"
            + "No binaries were executed.\n");

        return result.toString();
    }
}
