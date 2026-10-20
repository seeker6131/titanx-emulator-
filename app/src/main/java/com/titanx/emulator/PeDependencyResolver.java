
package com.titanx.emulator;

import java.io.*;
import java.util.*;

public final class PeDependencyResolver {

    private PeDependencyResolver() {}

    private static final int MAX_FILES = 20000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_SECTIONS = 96;
    private static final int MAX_IMPORTS = 256;

    private static final Set<String> SYSTEM_DLLS =
            new HashSet<>(Arrays.asList(
                    "kernel32.dll",
                    "kernelbase.dll",
                    "ntdll.dll",
                    "user32.dll",
                    "gdi32.dll",
                    "advapi32.dll",
                    "shell32.dll",
                    "ole32.dll",
                    "oleaut32.dll",
                    "comdlg32.dll",
                    "comctl32.dll",
                    "ws2_32.dll",
                    "winmm.dll",
                    "bcrypt.dll",
                    "rpcrt4.dll",
                    "sechost.dll",
                    "ucrtbase.dll",
                    "msvcrt.dll",
                    "shlwapi.dll",
                    "imm32.dll",
                    "version.dll",
                    "setupapi.dll",
                    "crypt32.dll"
            ));

    private static class Section {
        long va, vs, raw, size;
    }

    private static class Pe {
        File file;
        String path;
        String arch = "Unknown";
        String type = "Unknown";
        boolean valid;
        boolean complete = true;
        String error = "";
        final List<String> imports = new ArrayList<>();
    }

    private static class Reader implements Closeable {
        final RandomAccessFile f;
        final long length;

        Reader(File file) throws IOException {
            f = new RandomAccessFile(file, "r");
            length = f.length();
        }

        void check(long p, long n) throws IOException {
            if (p < 0 || n < 0 || p > length
                    || n > length - p) {
                throw new IOException("Invalid file offset");
            }
        }

        int u8(long p) throws IOException {
            check(p, 1);
            f.seek(p);
            return f.readUnsignedByte();
        }

        int u16(long p) throws IOException {
            return u8(p) | (u8(p + 1) << 8);
        }

        long u32(long p) throws IOException {
            return (u16(p) & 0xffffL)
                    | ((long) u16(p + 2) << 16);
        }

        String string(long p, int max)
                throws IOException {
            StringBuilder b = new StringBuilder();

            for (int i = 0; i < max; i++) {
                int c = u8(p + i);
                if (c == 0) return b.toString();

                if (c < 32 || c > 126) {
                    throw new IOException(
                            "Invalid DLL name");
                }

                b.append((char) c);
            }

            throw new IOException(
                    "Unterminated DLL name");
        }

        @Override
        public void close() throws IOException {
            f.close();
        }
    }

    private static String arch(int machine) {
        switch (machine) {
            case 0x014c: return "x86";
            case 0x8664: return "x86-64";
            case 0xaa64: return "ARM64";
            case 0x01c0:
            case 0x01c4: return "ARM32";
            default: return "Unknown";
        }
    }

    private static long offset(
            long rva,
            List<Section> sections,
            long headers,
            long length) throws IOException {

        if (rva < headers && rva < length) {
            return rva;
        }

        for (Section s : sections) {
            long extent = Math.max(s.vs, s.size);

            if (rva >= s.va
                    && rva - s.va < extent) {

                long delta = rva - s.va;

                if (delta >= s.size) {
                    throw new IOException(
                            "RVA not file-backed");
                }

                long p = s.raw + delta;

                if (p >= length) {
                    throw new IOException(
                            "RVA outside file");
                }

                return p;
            }
        }

        throw new IOException("Unmapped RVA");
    }

    private static Pe parse(File file, String path) {
        Pe pe = new Pe();
        pe.file = file;
        pe.path = path;

        try (Reader r = new Reader(file)) {

            if (r.length < 64 || r.u16(0) != 0x5a4d) {
                pe.error = "Not MZ";
                return pe;
            }

            long header = r.u32(0x3c);
            r.check(header, 24);

            if (r.u32(header) != 0x00004550L) {
                pe.error = "Invalid PE signature";
                return pe;
            }

            int machine = r.u16(header + 4);
            int sectionsCount = r.u16(header + 6);
            int optionalSize = r.u16(header + 20);
            int flags = r.u16(header + 22);

            pe.arch = arch(machine);
            pe.type = (flags & 0x2000) != 0
                    ? "DLL" : "EXE / PE image";

            long opt = header + 24;
            r.check(opt, optionalSize);

            int magic = r.u16(opt);
            boolean plus;

            if (magic == 0x20b) {
                plus = true;
            } else if (magic == 0x10b) {
                plus = false;
            } else {
                throw new IOException(
                        "Unsupported PE optional header");
            }

            int directoryOffset = plus ? 112 : 96;
            int countOffset = plus ? 108 : 92;

            if (optionalSize < directoryOffset) {
                throw new IOException(
                        "Truncated optional header");
            }

            if (sectionsCount > MAX_SECTIONS) {
                throw new IOException(
                        "Section limit exceeded");
            }

            long headers = r.u32(opt + 60);
            long directories = r.u32(opt + countOffset);

            long sectionTable = opt + optionalSize;
            r.check(sectionTable, sectionsCount * 40L);

            List<Section> sections = new ArrayList<>();

            for (int i = 0; i < sectionsCount; i++) {
                long p = sectionTable + i * 40L;

                Section s = new Section();
                s.vs = r.u32(p + 8);
                s.va = r.u32(p + 12);
                s.size = r.u32(p + 16);
                s.raw = r.u32(p + 20);

                sections.add(s);
            }

            pe.valid = true;

            if (directories < 2
                    || optionalSize < directoryOffset + 16) {
                return pe;
            }

            long importRva =
                    r.u32(opt + directoryOffset + 8);

            long importSize =
                    r.u32(opt + directoryOffset + 12);

            if (importRva == 0 || importSize == 0) {
                return pe;
            }

            long table = offset(
                    importRva, sections, headers, r.length);

            long limit = Math.min(
                    MAX_IMPORTS, importSize / 20L);

            boolean terminated = false;

            for (long i = 0; i < limit; i++) {
                long p = table + i * 20L;
                r.check(p, 20);

                long a = r.u32(p);
                long b = r.u32(p + 4);
                long c = r.u32(p + 8);
                long name = r.u32(p + 12);
                long d = r.u32(p + 16);

                if ((a | b | c | name | d) == 0) {
                    terminated = true;
                    break;
                }

                if (name == 0) {
                    throw new IOException(
                            "Missing import name RVA");
                }

                long nameOffset = offset(
                        name, sections, headers, r.length);

                pe.imports.add(
                        r.string(nameOffset, 260));
            }

            if (!terminated) {
                pe.complete = false;
                pe.error = "Import table incomplete";
            }

        } catch (Exception e) {
            pe.complete = false;
            pe.error = e.getMessage() == null
                    ? e.toString() : e.getMessage();
        }

        return pe;
    }

    private static void collect(
            File root,
            File dir,
            int depth,
            List<File> output) throws IOException {

        if (depth > MAX_DEPTH) {
            throw new IOException(
                    "Directory depth exceeded");
        }

        File[] children = dir.listFiles();

        if (children == null) {
            throw new IOException(
                    "Cannot list directory");
        }

        Arrays.sort(
                children,
                Comparator.comparing(File::getName));

        String base = root.getCanonicalPath()
                + File.separator;

        for (File f : children) {
            if (!f.getCanonicalPath().startsWith(base)) {
                throw new IOException(
                        "Path outside runtime");
            }

            if (f.isDirectory()) {
                collect(root, f, depth + 1, output);
            } else if (f.isFile()) {
                if (output.size() >= MAX_FILES) {
                    throw new IOException(
                            "File limit exceeded");
                }

                output.add(f);
            }
        }
    }

    private static String name(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1)
                .toLowerCase(Locale.ROOT);
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? ""
                : path.substring(0, slash + 1);
    }

    private static int priority(Pe source, Pe candidate) {
        String a = parent(source.path);
        String b = parent(candidate.path);

        if (a.equalsIgnoreCase(b)) return 0;
        return 1;
    }

    private static String resolve(
            Pe source,
            String dependency,
            Map<String, List<Pe>> index) {

        String key = dependency
                .toLowerCase(Locale.ROOT);

        List<Pe> matches = index.get(key);

        if (matches == null || matches.isEmpty()) {
            if (SYSTEM_DLLS.contains(key)
                    || key.startsWith("api-ms-win-")
                    || key.startsWith("ext-ms-win-")) {
                return "[SYSTEM DLL CANDIDATE] "
                        + dependency
                        + "\n  Not bundled; Wine/system "
                        + "availability not checked";
            }

            return "[MISSING IN ZIP] " + dependency;
        }

        int best = Integer.MAX_VALUE;

        for (Pe candidate : matches) {
            best = Math.min(
                    best, priority(source, candidate));
        }

        List<Pe> preferred = new ArrayList<>();

        for (Pe candidate : matches) {
            if (priority(source, candidate) == best) {
                preferred.add(candidate);
            }
        }

        List<Pe> compatible = new ArrayList<>();
        List<Pe> unknown = new ArrayList<>();

        for (Pe candidate : preferred) {
            if (!candidate.valid
                    || !candidate.complete
                    || candidate.arch.equals("Unknown")) {
                unknown.add(candidate);
            } else if (candidate.arch.equals(source.arch)) {
                compatible.add(candidate);
            }
        }

        if (compatible.size() == 1) {
            return "[FOUND IN ZIP] " + dependency
                    + "\n  -> " + compatible.get(0).path
                    + "\n  Architecture: "
                    + compatible.get(0).arch;
        }

        if (compatible.size() > 1) {
            return "[AMBIGUOUS] " + dependency
                    + "\n  Multiple matching candidates: "
                    + compatible.size();
        }

        if (!unknown.isEmpty()
                || source.arch.equals("Unknown")) {
            return "[UNVERIFIED] " + dependency
                    + "\n  Candidate metadata incomplete";
        }

        StringBuilder b = new StringBuilder();
        b.append("[ARCHITECTURE MISMATCH] ")
                .append(dependency)
                .append("\n  Expected: ")
                .append(source.arch);

        for (Pe candidate : preferred) {
            b.append("\n  Candidate: ")
                    .append(candidate.path)
                    .append(" (")
                    .append(candidate.arch)
                    .append(")");
        }

        return b.toString();
    }

    public static String analyze(File runtime)
            throws IOException {

        if (runtime == null || !runtime.isDirectory()) {
            return "No runtime imported";
        }

        List<File> files = new ArrayList<>();
        collect(runtime, runtime, 0, files);

        List<Pe> images = new ArrayList<>();
        Map<String, List<Pe>> index = new HashMap<>();

        String base = runtime.getCanonicalPath()
                + File.separator;

        for (File f : files) {
            if (f.length() < 64) continue;

            try (RandomAccessFile r =
                         new RandomAccessFile(f, "r")) {

                if (r.readUnsignedByte() != 'M'
                        || r.readUnsignedByte() != 'Z') {
                    continue;
                }
            }

            String path = f.getCanonicalPath()
                    .substring(base.length())
                    .replace(File.separatorChar, '/');

            Pe pe = parse(f, path);

            if (!pe.valid) continue;

            images.add(pe);

            if (pe.type.equals("DLL")) {
                String key = name(path);

                index.computeIfAbsent(
                        key, k -> new ArrayList<>()).add(pe);
            }
        }

        int found = 0;
        int missing = 0;
        int system = 0;
        int mismatch = 0;
        int ambiguous = 0;
        int unverified = 0;

        StringBuilder report = new StringBuilder();

        report.append(
                "TITANX WINDOWS DLL RESOLVER v1.7\n\n");

        report.append("Files scanned: ")
                .append(files.size()).append('\n');

        report.append("PE images: ")
                .append(images.size()).append('\n');

        report.append("DLL candidates: ")
                .append(index.size()).append("\n\n");

        for (Pe pe : images) {
            report.append("------------------------\n");
            report.append(pe.path).append('\n');
            report.append("Architecture: ")
                    .append(pe.arch).append('\n');
            report.append("Type: ")
                    .append(pe.type).append('\n');

            if (!pe.complete) {
                report.append("[UNVERIFIED PE] ")
                        .append(pe.error).append('\n');
                unverified++;
            }

            if (pe.imports.isEmpty()) {
                report.append(
                        "Normal DLL imports: None\n");
            }

            for (String dependency : pe.imports) {
                String result = resolve(
                        pe, dependency, index);

                report.append(result).append('\n');

                if (result.startsWith("[FOUND")) found++;
                else if (result.startsWith("[MISSING")) missing++;
                else if (result.startsWith("[SYSTEM")) system++;
                else if (result.startsWith("[ARCHITECTURE")) mismatch++;
                else if (result.startsWith("[AMBIGUOUS")) ambiguous++;
                else unverified++;
            }

            report.append('\n');
        }

        report.append("========================\n");
        report.append("SUMMARY\n");
        report.append("FOUND IN ZIP: ").append(found).append('\n');
        report.append("MISSING IN ZIP: ").append(missing).append('\n');
        report.append("SYSTEM DLL CANDIDATE: ")
                .append(system).append('\n');
        report.append("ARCHITECTURE MISMATCH: ")
                .append(mismatch).append('\n');
        report.append("AMBIGUOUS: ")
                .append(ambiguous).append('\n');
        report.append("UNVERIFIED: ")
                .append(unverified).append("\n\n");

        report.append(
                "This is a static ZIP-level analysis.\n");
        report.append(
                "DLL search order is approximated.\n");
        report.append(
                "Import names do not verify exported functions.\n");
        report.append(
                "Delay-load and runtime imports are not checked.\n");
        report.append(
                "No Windows binaries were executed.\n");
        report.append(
                "Wine/FEX/DXVK are not integrated.");

        return report.toString();
    }

    public static String selfTest() {
        boolean system =
                SYSTEM_DLLS.contains("kernel32.dll");

        boolean caseInsensitive =
                "USER32.DLL".toLowerCase(Locale.ROOT)
                        .equals("user32.dll");

        boolean x64 = arch(0x8664).equals("x86-64");
        boolean arm64 = arch(0xaa64).equals("ARM64");

        boolean path =
                name("folder/TEST.DLL").equals("test.dll");

        int passed = 0;
        if (system) passed++;
        if (caseInsensitive) passed++;
        if (x64) passed++;
        if (arm64) passed++;
        if (path) passed++;

        return "TITANX DLL RESOLVER SELF-TEST v1.7\n\n"
                + "System DLL names: " + status(system)
                + "\nCase insensitive names: "
                + status(caseInsensitive)
                + "\nx64 machine: " + status(x64)
                + "\nARM64 machine: " + status(arm64)
                + "\nDLL filename normalization: "
                + status(path)
                + "\n\n" + passed + "/5 BASIC TESTS PASSED"
                + "\n\nNo binaries executed.";
    }

    private static String status(boolean ok) {
        return ok ? "PASS" : "FAIL";
    }
}
