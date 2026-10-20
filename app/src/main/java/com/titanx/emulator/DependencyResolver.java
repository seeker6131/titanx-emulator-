
package com.titanx.emulator;

import java.io.*;
import java.util.*;

public final class DependencyResolver {

    private static final int MAX_FILES = 20000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ELF = 200;
    private static final int MAX_REPORT = 100000;

    private DependencyResolver() {}

    private static class Item {
        File file;
        String path;
        String name;
        String arch = "Unknown";
        String soname = "";
        boolean elf;
        boolean valid;
        boolean inspected;
        List<String> needed = new ArrayList<>();

        Item(File f, String p) {
            file = f;
            path = p;
            name = f.getName();
        }
    }

    private static void collect(
        File root, File dir, int depth, List<Item> out
    ) throws IOException {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES)
            return;

        File[] files = dir.listFiles();
        if (files == null) return;

        Arrays.sort(files, Comparator.comparing(File::getName));

        String base = root.getCanonicalPath() + File.separator;

        for (File f : files) {
            if (out.size() >= MAX_FILES) return;
            String path = f.getCanonicalPath();
            if (!path.startsWith(base)) continue;

            if (f.isDirectory()) {
                collect(root, f, depth + 1, out);
            } else if (f.isFile()) {
                out.add(new Item(f, path.substring(base.length())));
            }
        }
    }

    private static boolean isElf(File f) {
        if (f.length() < 20) return false;

        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            if (in.read(h) != 4) return false;

            return (h[0] & 255) == 127 &&
                h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    private static void parse(Item item) {
        item.inspected = true;
        String report = NativeBridge.inspect(item.file);

        if (!report.startsWith("ELF detected\n") ||
            report.contains("\nError:")) {
            return;
        }

        boolean inNeeded = false;
        boolean recognized = false;

        for (String line : report.split("\n")) {
            String s = line.trim();

            if (s.startsWith("Architecture: ")) {
                item.arch = s.substring(14).trim();
            }

            if (s.startsWith("DT_SONAME: ")) {
                String value = s.substring(11).trim();
                if (!value.equals("None")) item.soname = value;
            }

            if (s.startsWith("DT_NEEDED (")) {
                inNeeded = true;
                recognized = true;
                continue;
            }

            if (s.equals("DT_NEEDED: None")) {
                recognized = true;
                inNeeded = false;
            }

            if (inNeeded) {
                if (s.startsWith("- ")) {
                    String dep = s.substring(2).trim();
                    if (!dep.isEmpty() &&
                        !item.needed.contains(dep)) {
                        item.needed.add(dep);
                    }
                } else {
                    inNeeded = false;
                }
            }
        }

        item.valid = recognized &&
            !item.arch.equals("Unknown");
    }

    private static void line(StringBuilder out, String text) {
        if (out.length() + text.length() <= MAX_REPORT)
            out.append(text).append('\n');
    }

    public static String analyze(File runtime) throws IOException {
        if (runtime == null || !runtime.isDirectory())
            return "Runtime directory not found";

        List<Item> files = new ArrayList<>();
        collect(runtime, runtime, 0, files);

        List<Item> elfFiles = new ArrayList<>();
        int skipped = 0;

        for (Item item : files) {
            if (!isElf(item.file)) continue;
            item.elf = true;

            if (elfFiles.size() >= MAX_ELF) {
                skipped++;
                continue;
            }

            parse(item);
            elfFiles.add(item);
        }

        Map<String, List<Item>> names = new HashMap<>();

        for (Item item : files) {
            names.computeIfAbsent(item.name,
                k -> new ArrayList<>()).add(item);

            if (item.valid && !item.soname.isEmpty()) {
                names.computeIfAbsent(item.soname,
                    k -> new ArrayList<>()).add(item);
            }
        }

        int found = 0, missing = 0;
        int incompatible = 0, unverified = 0;
        int invalidElf = 0;

        StringBuilder out = new StringBuilder();
        line(out, "TITANX COMPATIBILITY ANALYZER v1.0");
        line(out, "");
        line(out, "Runtime files: " + files.size());
        line(out, "ELF selected: " + elfFiles.size());
        line(out, "ELF skipped by limit: " + skipped);
        line(out, "");

        for (Item item : elfFiles) {
            line(out, "--------------------------");
            line(out, item.path);

            if (!item.valid) {
                invalidElf++;
                line(out, "[UNVERIFIED] ELF metadata unavailable");
                continue;
            }

            line(out, "Architecture: " + item.arch);
            line(out, "SONAME: " +
                (item.soname.isEmpty() ? "None" : item.soname));

            if (item.needed.isEmpty()) {
                line(out, "DT_NEEDED: None");
                continue;
            }

            for (String dep : item.needed) {
                List<Item> matches = names.get(dep);

                if (matches == null || matches.isEmpty()) {
                    missing++;
                    line(out, "[MISSING] " + dep);
                    continue;
                }

                boolean good = false;
                boolean unknown = false;
                boolean wrong = false;
                String chosen = "";

                Set<Item> unique = new LinkedHashSet<>(matches);

                for (Item candidate : unique) {
                    if (!candidate.elf ||
                        !candidate.inspected ||
                        !candidate.valid) {
                        unknown = true;
                        continue;
                    }

                    if (candidate.arch.equals(item.arch)) {
                        good = true;
                        chosen = candidate.path;
                        break;
                    } else {
                        wrong = true;
                        chosen = candidate.path;
                    }
                }

                if (good) {
                    found++;
                    line(out, "[FOUND] " + dep);
                    line(out, "  -> " + chosen);
                } else if (unknown) {
                    unverified++;
                    line(out, "[UNVERIFIED] " + dep);
                    line(out, "  Candidate exists, metadata incomplete");
                } else if (wrong) {
                    incompatible++;
                    line(out, "[INCOMPATIBLE] " + dep);
                    line(out, "  Expected: " + item.arch);
                    line(out, "  Candidate: " + chosen);
                } else {
                    unverified++;
                    line(out, "[UNVERIFIED] " + dep);
                }
            }
        }

        line(out, "");
        line(out, "==========================");
        line(out, "SUMMARY");
        line(out, "FOUND: " + found);
        line(out, "MISSING: " + missing);
        line(out, "INCOMPATIBLE: " + incompatible);
        line(out, "UNVERIFIED references: " + unverified);
        line(out, "UNVERIFIED ELF files: " + invalidElf);
        line(out, "ELF skipped: " + skipped);
        line(out, "");
        line(out, "FOUND = matching name/SONAME and architecture.");
        line(out, "MISSING = not found inside imported ZIP.");
        line(out, "INCOMPATIBLE = architecture mismatch.");
        line(out, "UNVERIFIED = insufficient metadata.");
        line(out, "");
        line(out, "No binaries executed.");
        line(out, "Dynamic linker behavior not simulated.");
        line(out, "Android/Wine compatibility not guaranteed.");

        return out.toString();
    }
}
