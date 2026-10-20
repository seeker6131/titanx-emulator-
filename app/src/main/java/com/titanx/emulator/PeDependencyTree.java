
package com.titanx.emulator;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PeDependencyTree {

    private static final int MAX_FILES = 20000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_TREE_DEPTH = 20;
    private static final int MAX_REPORT = 100000;
    private static final int MAX_SECTIONS = 96;
    private static final int MAX_IMPORTS = 256;

    private static final Set<String> SYSTEM_DLLS =
            new HashSet<>(Arrays.asList(
                    "kernel32.dll", "kernelbase.dll",
                    "ntdll.dll", "user32.dll",
                    "gdi32.dll", "advapi32.dll",
                    "shell32.dll", "ole32.dll",
                    "oleaut32.dll", "comdlg32.dll",
                    "comctl32.dll", "ws2_32.dll",
                    "winmm.dll", "bcrypt.dll",
                    "rpcrt4.dll", "sechost.dll",
                    "ucrtbase.dll", "msvcrt.dll",
                    "shlwapi.dll", "imm32.dll",
                    "version.dll", "setupapi.dll",
                    "crypt32.dll"
            ));

    private PeDependencyTree() {}

    private static final class Section {
        long va;
        long virtualSize;
        long raw;
        long rawSize;
    }

    private static final class Image {
        String path;
        String arch = "Unknown";
        String type = "Unknown";
        boolean valid;
        boolean complete = true;
        String error = "";
        final List<String> imports = new ArrayList<>();
    }

    private static final class Stats {
        int found;
        int missing;
        int system;
        int mismatch;
        int ambiguous;
        int unverified;
        int cycles;
        int depthLimits;
    }

    private static final class Reader implements AutoCloseable {
        final RandomAccessFile file;
        final long length;

        Reader(File source) throws IOException {
            file = new RandomAccessFile(source, "r");
            length = file.length();
        }

        void check(long offset, long count)
                throws IOException {
            if (offset < 0 || count < 0
                    || offset > length
                    || count > length - offset) {
                throw new IOException("Invalid PE offset");
            }
        }

        int u8(long offset) throws IOException {
            check(offset, 1);
            file.seek(offset);
            return file.readUnsignedByte();
        }

        int u16(long offset) throws IOException {
            return u8(offset) | (u8(offset + 1) << 8);
        }

        long u32(long offset) throws IOException {
            return (u16(offset) & 65535L)
                    | ((long) u16(offset + 2) << 16);
        }

        String ascii(long offset, int max)
                throws IOException {
            StringBuilder result = new StringBuilder();

            for (int i = 0; i < max; i++) {
                int value = u8(offset + i);

                if (value == 0) {
                    if (result.length() == 0) {
                        throw new IOException("Empty DLL name");
                    }
                    return result.toString();
                }

                if (value < 32 || value > 126) {
                    throw new IOException("Invalid DLL name");
                }

                result.append((char) value);
            }

            throw new IOException("DLL name too long");
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    private static String architecture(int machine) {
        switch (machine) {
            case 0x014c: return "x86";
            case 0x8664: return "x86-64";
            case 0xaa64: return "ARM64";
            case 0x01c0:
            case 0x01c4: return "ARM32";
            default: return "Unknown";
        }
    }

    private static String filename(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1)
                .toLowerCase(Locale.ROOT);
    }

    private static String folder(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? ""
                : path.substring(0, slash + 1);
    }

    private static long rvaOffset(
            long rva,
            List<Section> sections,
            long headerSize,
            long length) throws IOException {

        if (rva < headerSize && rva < length) {
            return rva;
        }

        for (Section section : sections) {
            long extent = Math.max(
                    section.virtualSize,
                    section.rawSize);

            if (rva >= section.va
                    && rva - section.va < extent) {

                long delta = rva - section.va;

                if (delta >= section.rawSize) {
                    throw new IOException(
                            "RVA not backed by file");
                }

                long offset = section.raw + delta;

                if (offset >= length) {
                    throw new IOException(
                            "RVA outside file");
                }

                return offset;
            }
        }

        throw new IOException("Unmapped RVA");
    }

    private static Image parse(File file, String path) {
        Image image = new Image();
        image.path = path;

        try (Reader reader = new Reader(file)) {

            if (reader.length < 64
                    || reader.u16(0) != 0x5a4d) {
                return image;
            }

            long pe = reader.u32(0x3c);
            reader.check(pe, 24);

            if (reader.u32(pe) != 0x4550) {
                return image;
            }

            int machine = reader.u16(pe + 4);
            int sectionCount = reader.u16(pe + 6);
            int optionalSize = reader.u16(pe + 20);
            int characteristics = reader.u16(pe + 22);

            image.arch = architecture(machine);
            image.type = (characteristics & 0x2000) != 0
                    ? "DLL" : "EXE / PE image";

            long optional = pe + 24;
            reader.check(optional, optionalSize);

            int magic = reader.u16(optional);
            boolean pe64;

            if (magic == 0x20b) {
                pe64 = true;
            } else if (magic == 0x10b) {
                pe64 = false;
            } else {
                throw new IOException(
                        "Unsupported PE optional header");
            }

            int directoryOffset = pe64 ? 112 : 96;
            int countOffset = pe64 ? 108 : 92;

            if (optionalSize < directoryOffset) {
                throw new IOException(
                        "Truncated optional header");
            }

            if (sectionCount > MAX_SECTIONS) {
                throw new IOException(
                        "Too many PE sections");
            }

            long headerSize = reader.u32(optional + 60);
            long directoryCount =
                    reader.u32(optional + countOffset);

            long sectionTable = optional + optionalSize;
            reader.check(sectionTable, sectionCount * 40L);

            List<Section> sections = new ArrayList<>();

            for (int i = 0; i < sectionCount; i++) {
                long offset = sectionTable + i * 40L;

                Section section = new Section();
                section.virtualSize = reader.u32(offset + 8);
                section.va = reader.u32(offset + 12);
                section.rawSize = reader.u32(offset + 16);
                section.raw = reader.u32(offset + 20);

                sections.add(section);
            }

            image.valid = true;

            if (directoryCount < 2
                    || optionalSize < directoryOffset + 16) {
                return image;
            }

            long importRva =
                    reader.u32(optional + directoryOffset + 8);

            long importSize =
                    reader.u32(optional + directoryOffset + 12);

            if (importRva == 0 || importSize == 0) {
                return image;
            }

            long table = rvaOffset(
                    importRva, sections,
                    headerSize, reader.length);

            long limit = Math.min(
                    MAX_IMPORTS, importSize / 20L);

            boolean terminated = false;

            for (long i = 0; i < limit; i++) {
                long entry = table + i * 20L;
                reader.check(entry, 20);

                long a = reader.u32(entry);
                long b = reader.u32(entry + 4);
                long c = reader.u32(entry + 8);
                long name = reader.u32(entry + 12);
                long d = reader.u32(entry + 16);

                if ((a | b | c | name | d) == 0) {
                    terminated = true;
                    break;
                }

                if (name == 0) {
                    throw new IOException(
                            "Missing import name");
                }

                long nameOffset = rvaOffset(
                        name, sections,
                        headerSize, reader.length);

                image.imports.add(
                        reader.ascii(nameOffset, 260));
            }

            if (!terminated) {
                image.complete = false;
                image.error = "Import table incomplete";
            }

        } catch (Exception exception) {
            image.complete = false;
            image.error = exception.getMessage() == null
                    ? exception.toString()
                    : exception.getMessage();
        }

        return image;
    }

    private static void collect(
            File root,
            File directory,
            int depth,
            List<File> files) throws IOException {

        if (depth > MAX_DEPTH) {
            throw new IOException(
                    "Directory depth limit exceeded");
        }

        File[] children = directory.listFiles();

        if (children == null) {
            throw new IOException(
                    "Cannot list runtime directory");
        }

        Arrays.sort(children,
                Comparator.comparing(File::getName));

        String base = root.getCanonicalPath()
                + File.separator;

        for (File child : children) {
            if (!child.getCanonicalPath()
                    .startsWith(base)) {
                throw new IOException(
                        "Path outside runtime");
            }

            if (child.isDirectory()) {
                collect(root, child, depth + 1, files);
            } else if (child.isFile()) {
                if (files.size() >= MAX_FILES) {
                    throw new IOException(
                            "File count limit exceeded");
                }

                files.add(child);
            }
        }
    }

    private static void append(
            StringBuilder output,
            String line) {

        if (output.length() < MAX_REPORT) {
            output.append(line).append('\n');
        }
    }

    private static String indent(int depth) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < depth; i++) {
            result.append("  ");
        }

        return result.toString();
    }

    private static boolean systemDll(String name) {
        return SYSTEM_DLLS.contains(name)
                || name.startsWith("api-ms-win-")
                || name.startsWith("ext-ms-win-");
    }

    private static void traverse(
            Image source,
            Map<String, List<Image>> index,
            Set<String> active,
            int depth,
            StringBuilder output,
            Stats stats) {

        if (output.length() >= MAX_REPORT) {
            return;
        }

        if (depth >= MAX_TREE_DEPTH) {
            stats.depthLimits++;
            append(output, indent(depth)
                    + "[DEPTH LIMIT]");
            return;
        }

        for (String dependency : source.imports) {
            if (output.length() >= MAX_REPORT) {
                return;
            }

            String prefix = indent(depth + 1);
            String key = dependency
                    .toLowerCase(Locale.ROOT);

            List<Image> candidates = index.get(key);

            if (candidates == null
                    || candidates.isEmpty()) {

                if (systemDll(key)) {
                    stats.system++;
                    append(output, prefix
                            + "[SYSTEM CANDIDATE] "
                            + dependency);
                } else {
                    stats.missing++;
                    append(output, prefix
                            + "[MISSING] "
                            + dependency);
                }

                continue;
            }

            List<Image> local = new ArrayList<>();

            for (Image candidate : candidates) {
                if (folder(candidate.path)
                        .equalsIgnoreCase(
                                folder(source.path))) {
                    local.add(candidate);
                }
            }

            if (!local.isEmpty()) {
                candidates = local;
            }

            List<Image> compatible = new ArrayList<>();
            List<Image> unknown = new ArrayList<>();

            for (Image candidate : candidates) {
                if (!candidate.valid
                        || !candidate.complete
                        || candidate.arch.equals("Unknown")
                        || source.arch.equals("Unknown")) {
                    unknown.add(candidate);
                } else if (candidate.arch.equals(
                        source.arch)) {
                    compatible.add(candidate);
                }
            }

            if (compatible.size() > 1) {
                stats.ambiguous++;
                append(output, prefix
                        + "[AMBIGUOUS] "
                        + dependency + " ("
                        + compatible.size()
                        + " candidates)");
                continue;
            }

            if (compatible.isEmpty()) {
                if (!unknown.isEmpty()) {
                    stats.unverified++;
                    append(output, prefix
                            + "[UNVERIFIED] "
                            + dependency);
                } else {
                    stats.mismatch++;
                    append(output, prefix
                            + "[ARCH MISMATCH] "
                            + dependency
                            + " expected "
                            + source.arch);
                }

                continue;
            }

            Image target = compatible.get(0);
            stats.found++;

            append(output, prefix
                    + "[FOUND] " + dependency
                    + " -> " + target.path);

            if (active.contains(target.path)) {
                stats.cycles++;
                append(output, prefix
                        + "  [CYCLE DETECTED]");
                continue;
            }

            if (!target.complete) {
                stats.unverified++;
                append(output, prefix
                        + "  [INCOMPLETE METADATA]");
                continue;
            }

            active.add(target.path);

            traverse(target, index, active,
                    depth + 1, output, stats);

            active.remove(target.path);
        }
    }

    public static String analyze(File runtime)
            throws IOException {

        if (runtime == null || !runtime.isDirectory()) {
            return "No runtime imported";
        }

        List<File> files = new ArrayList<>();
        collect(runtime, runtime, 0, files);

        List<Image> images = new ArrayList<>();
        Map<String, List<Image>> index = new HashMap<>();

        String base = runtime.getCanonicalPath()
                + File.separator;

        for (File file : files) {
            if (file.length() < 64) {
                continue;
            }

            try (RandomAccessFile reader =
                         new RandomAccessFile(file, "r")) {

                if (reader.readUnsignedByte() != 'M'
                        || reader.readUnsignedByte() != 'Z') {
                    continue;
                }
            }

            String path = file.getCanonicalPath()
                    .substring(base.length())
                    .replace(File.separatorChar, '/');

            Image image = parse(file, path);

            if (!image.valid) {
                continue;
            }

            images.add(image);

            if ("DLL".equals(image.type)) {
                index.computeIfAbsent(
                        filename(path),
                        k -> new ArrayList<>())
                        .add(image);
            }
        }

        images.sort(
                Comparator.comparing(image -> image.path));

        StringBuilder output = new StringBuilder();
        Stats stats = new Stats();

        append(output,
                "TITANX WINDOWS DEPENDENCY TREE v1.8");
        append(output, "");
        append(output, "Files scanned: " + files.size());
        append(output, "PE images: " + images.size());
        append(output, "DLL names indexed: "
                + index.size());
        append(output, "");

        for (Image image : images) {
            if (output.length() >= MAX_REPORT) {
                break;
            }

            append(output,
                    "----------------------------");
            append(output, "[ROOT] " + image.path);
            append(output, "Type: " + image.type);
            append(output,
                    "Architecture: " + image.arch);

            if (!image.complete) {
                stats.unverified++;
                append(output,
                        "[INCOMPLETE PE] " + image.error);
            }

            if (image.imports.isEmpty()) {
                append(output, "Imports: None");
            } else {
                Set<String> active = new HashSet<>();
                active.add(image.path);

                traverse(image, index, active,
                        0, output, stats);
            }

            append(output, "");
        }

        append(output,
                "============================");
        append(output, "SUMMARY");
        append(output, "FOUND edges: " + stats.found);
        append(output,
                "MISSING edges: " + stats.missing);
        append(output,
                "SYSTEM candidates: " + stats.system);
        append(output,
                "ARCH mismatches: " + stats.mismatch);
        append(output,
                "AMBIGUOUS edges: " + stats.ambiguous);
        append(output,
                "UNVERIFIED: " + stats.unverified);
        append(output,
                "CYCLES detected: " + stats.cycles);
        append(output,
                "DEPTH limits: " + stats.depthLimits);
        append(output, "");

        append(output,
                "Static normal-import analysis only.");
        append(output,
                "DLL search order is approximated.");
        append(output,
                "Delay-load imports are not analyzed.");
        append(output,
                "Exports and Windows loader behavior "
                + "are not verified.");
        append(output,
                "No Windows binaries were executed.");
        append(output,
                "Wine/FEX/DXVK not integrated.");

        if (output.length() >= MAX_REPORT) {
            return output.substring(0, MAX_REPORT)
                    + "\n[REPORT TRUNCATED]";
        }

        return output.toString();
    }

    public static String selfTest() {
        boolean system = systemDll("kernel32.dll");
        boolean api = systemDll(
                "api-ms-win-core-file-l1-1-0.dll");
        boolean x64 = architecture(0x8664)
                .equals("x86-64");
        boolean arm64 = architecture(0xaa64)
                .equals("ARM64");
        boolean names = filename("LIBS/TEST.DLL")
                .equals("test.dll");

        Set<String> active = new HashSet<>();
        active.add("a.dll");
        active.add("b.dll");

        boolean cycle = active.contains("a.dll");

        int passed = 0;

        if (system) passed++;
        if (api) passed++;
        if (x64) passed++;
        if (arm64) passed++;
        if (names) passed++;
        if (cycle) passed++;

        return "TITANX DEPENDENCY TREE SELF-TEST v1.8\n\n"
                + "System DLL recognition: "
                + status(system)
                + "\nAPI-set recognition: "
                + status(api)
                + "\nx64 architecture: "
                + status(x64)
                + "\nARM64 architecture: "
                + status(arm64)
                + "\nFilename normalization: "
                + status(names)
                + "\nCycle membership: "
                + status(cycle)
                + "\n\n"
                + passed + "/6 BASIC TESTS PASSED"
                + "\n\nThese are logic checks, "
                + "not end-to-end PE fixture tests."
                + "\nNo binaries executed.";
    }

    private static String status(boolean value) {
        return value ? "PASS" : "FAIL";
    }
}
