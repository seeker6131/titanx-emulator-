
package com.titanx.emulator;

import java.io.*;
import java.util.*;

public final class PeExportChecker {
    private PeExportChecker() {}

    private static final int MAX_FILES = 20000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ITEMS = 4096;
    private static final int MAX_REPORT = 100000;

    private static final Set<String> SYSTEM = new HashSet<>(
        Arrays.asList("kernel32.dll","kernelbase.dll",
        "ntdll.dll","user32.dll","gdi32.dll",
        "advapi32.dll","shell32.dll","ole32.dll",
        "oleaut32.dll","ws2_32.dll","winmm.dll",
        "bcrypt.dll","rpcrt4.dll","ucrtbase.dll",
        "msvcrt.dll","comctl32.dll","comdlg32.dll",
        "shlwapi.dll","version.dll","crypt32.dll",
        "sechost.dll","imm32.dll","setupapi.dll")
    );

    private static class Section {
        long va, size, raw, rawSize;
    }

    private static class Dependency {
        String dll;
        final List<String> symbols = new ArrayList<>();
    }

    private static class PE {
        String path, arch, type;
        boolean complete = true;
        String error = "";
        String exportName = "";
        long ordinalBase;
        long functionCount;
        final Set<String> exports = new HashSet<>();
        final Set<Long> ordinals = new HashSet<>();
        final List<Dependency> imports = new ArrayList<>();
    }

    private static class Reader implements AutoCloseable {
        final RandomAccessFile f;
        final long length;

        Reader(File file) throws IOException {
            f = new RandomAccessFile(file, "r");
            length = f.length();
        }

        void check(long pos, long size) throws IOException {
            if (pos < 0 || size < 0 ||
                pos > length || size > length - pos)
                throw new IOException("Invalid file offset");
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
            return (u16(p) & 65535L)
                | ((long)u16(p + 2) << 16);
        }

        long u64(long p) throws IOException {
            return u32(p) | (u32(p + 4) << 32);
        }

        String str(long p) throws IOException {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 512; i++) {
                int c = u8(p + i);
                if (c == 0) return b.toString();
                if (c < 32 || c > 126)
                    throw new IOException("Invalid PE string");
                b.append((char)c);
            }
            throw new IOException("String limit");
        }

        @Override public void close() throws IOException {
            f.close();
        }
    }

    private static String arch(int m) {
        switch (m) {
            case 0x8664: return "x86-64";
            case 0x014c: return "x86";
            case 0xaa64: return "ARM64";
            case 0x01c0:
            case 0x01c4: return "ARM32";
            default: return "Unknown";
        }
    }

    private static long offset(long rva,
        List<Section> sections, long headers,
        long fileLength) throws IOException {

        if (rva < headers && rva < fileLength)
            return rva;

        for (Section s : sections) {
            if (rva >= s.va &&
                rva - s.va < Math.max(s.size, s.rawSize)) {
                long delta = rva - s.va;
                if (delta >= s.rawSize)
                    throw new IOException("Unbacked RVA");
                long result = s.raw + delta;
                if (result >= fileLength)
                    throw new IOException("RVA outside file");
                return result;
            }
        }
        throw new IOException("Unmapped RVA");
    }

    private static PE parse(File file, String path)
        throws IOException {

        PE pe = new PE();
        pe.path = path;

        try (Reader r = new Reader(file)) {
            if (r.length < 64 || r.u16(0) != 0x5a4d)
                throw new IOException("Not MZ");

            long nt = r.u32(0x3c);
            r.check(nt, 24);
            if (r.u32(nt) != 0x4550)
                throw new IOException("Invalid PE signature");

            pe.arch = arch(r.u16(nt + 4));
            int sectionsCount = r.u16(nt + 6);
            int optSize = r.u16(nt + 20);
            int characteristics = r.u16(nt + 22);
            pe.type = (characteristics & 0x2000) != 0
                ? "DLL" : "EXE";

            long opt = nt + 24;
            r.check(opt, optSize);
            int magic = r.u16(opt);
            boolean is64 = magic == 0x20b;
            if (!is64 && magic != 0x10b)
                throw new IOException("Unsupported PE format");

            int dirOffset = is64 ? 112 : 96;
            int countOffset = is64 ? 108 : 92;
            if (optSize < dirOffset)
                throw new IOException("Short optional header");

            long dirCount = r.u32(opt + countOffset);
            long headers = r.u32(opt + 60);

            if (sectionsCount > 96)
                throw new IOException("Section limit");

            List<Section> sections = new ArrayList<>();
            long table = opt + optSize;
            r.check(table, sectionsCount * 40L);

            for (int i = 0; i < sectionsCount; i++) {
                long p = table + i * 40L;
                Section s = new Section();
                s.size = r.u32(p + 8);
                s.va = r.u32(p + 12);
                s.rawSize = r.u32(p + 16);
                s.raw = r.u32(p + 20);
                sections.add(s);
            }

            if (dirCount > 0 && optSize >= dirOffset + 8) {
                long expRva = r.u32(opt + dirOffset);
                long expSize = r.u32(opt + dirOffset + 4);
                if (expRva != 0 && expSize >= 40) {
                    try {
                        readExports(r, pe, sections,
                            headers, expRva, expSize);
                    } catch (IOException ex) {
                        pe.complete = false;
                        pe.error += "Export: " + ex.getMessage() + "; ";
                    }
                }
            }

            if (dirCount > 1 && optSize >= dirOffset + 16) {
                long impRva = r.u32(opt + dirOffset + 8);
                long impSize = r.u32(opt + dirOffset + 12);
                if (impRva != 0 && impSize > 0) {
                    try {
                        readImports(r, pe, sections,
                            headers, impRva, impSize, is64);
                    } catch (IOException ex) {
                        pe.complete = false;
                        pe.error += "Import: " + ex.getMessage() + "; ";
                    }
                }
            }
        }
        return pe;
    }

    private static void readExports(Reader r, PE pe,
        List<Section> sections, long headers,
        long rva, long size) throws IOException {

        long p = offset(rva, sections, headers, r.length);
        r.check(p, 40);

        long nameRva = r.u32(p + 12);
        pe.ordinalBase = r.u32(p + 16);
        pe.functionCount = r.u32(p + 20);
        long nameCount = r.u32(p + 24);
        long funcsRva = r.u32(p + 28);
        long namesRva = r.u32(p + 32);
        long ordsRva = r.u32(p + 36);

        if (pe.functionCount > MAX_ITEMS ||
            nameCount > MAX_ITEMS)
            throw new IOException("Export count limit");

        if (nameRva != 0)
            pe.exportName = r.str(offset(nameRva,
                sections, headers, r.length));

        if (pe.functionCount == 0) return;

        long funcs = offset(funcsRva,
            sections, headers, r.length);
        r.check(funcs, pe.functionCount * 4L);

        for (int i = 0; i < pe.functionCount; i++) {
            long funcRva = r.u32(funcs + i * 4L);
            if (funcRva != 0) {
                pe.ordinals.add(pe.ordinalBase + i);
            }
        }

        if (nameCount == 0) return;

        long names = offset(namesRva,
            sections, headers, r.length);
        long ords = offset(ordsRva,
            sections, headers, r.length);
        r.check(names, nameCount * 4L);
        r.check(ords, nameCount * 2L);

        for (int i = 0; i < nameCount; i++) {
            long name = r.u32(names + i * 4L);
            int index = r.u16(ords + i * 2L);
            if (index >= pe.functionCount)
                throw new IOException("Bad export ordinal");
            if (r.u32(funcs + index * 4L) == 0)
                continue;
            pe.exports.add(r.str(offset(name,
                sections, headers, r.length)));
        }
    }

    private static void readImports(Reader r, PE pe,
        List<Section> sections, long headers,
        long rva, long size, boolean is64)
        throws IOException {

        long table = offset(rva, sections,
            headers, r.length);

        long limit = Math.min(MAX_ITEMS, size / 20);
        boolean ended = false;

        for (long i = 0; i < limit; i++) {
            long p = table + i * 20;
            r.check(p, 20);

            long lookup = r.u32(p);
            long time = r.u32(p + 4);
            long chain = r.u32(p + 8);
            long name = r.u32(p + 12);
            long address = r.u32(p + 16);

            if ((lookup | time | chain |
                name | address) == 0) {
                ended = true;
                break;
            }

            if (name == 0)
                throw new IOException("Missing import DLL name");

            Dependency d = new Dependency();
            d.dll = r.str(offset(name, sections,
                headers, r.length));

            long thunkRva = lookup != 0 ? lookup : address;
            if (thunkRva == 0)
                throw new IOException("Missing import thunk");

            long thunk = offset(thunkRva, sections,
                headers, r.length);
            int step = is64 ? 8 : 4;
            boolean thunkEnded = false;

            for (int j = 0; j < MAX_ITEMS; j++) {
                long item = thunk + (long)j * step;
                long value = is64 ? r.u64(item) : r.u32(item);
                if (value == 0) {
                    thunkEnded = true;
                    break;
                }

                boolean ordinal = is64
                    ? (value & Long.MIN_VALUE) != 0
                    : (value & 0x80000000L) != 0;

                if (ordinal) {
                    d.symbols.add("#" + (value & 0xffff));
                } else {
                    long mask = is64
                        ? 0x7fffffffffffffffL : 0x7fffffffL;
                    long nameOffset = offset(value & mask,
                        sections, headers, r.length);
                    d.symbols.add(r.str(nameOffset + 2));
                }
            }

            if (!thunkEnded)
                throw new IOException("Import thunk limit");

            pe.imports.add(d);
        }

        if (!ended)
            throw new IOException("Import descriptor limit");
    }

    private static void collect(File root, File dir,
        int depth, List<File> files) throws IOException {

        if (depth > MAX_DEPTH)
            throw new IOException("Directory depth limit");

        File[] children = dir.listFiles();
        if (children == null)
            throw new IOException("Cannot list directory");

        String base = root.getCanonicalPath()
            + File.separator;

        Arrays.sort(children,
            Comparator.comparing(File::getName));

        for (File f : children) {
            if (!f.getCanonicalPath().startsWith(base))
                throw new IOException("Unsafe file path");
            if (f.isDirectory()) {
                collect(root, f, depth + 1, files);
            } else if (f.isFile()) {
                if (files.size() >= MAX_FILES)
                    throw new IOException("File limit");
                files.add(f);
            }
        }
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return path.substring(i + 1)
            .toLowerCase(Locale.ROOT);
    }

    private static boolean system(String name) {
        return SYSTEM.contains(name)
            || name.startsWith("api-ms-win-")
            || name.startsWith("ext-ms-win-");
    }

    private static void line(StringBuilder out, String s) {
        if (out.length() < MAX_REPORT)
            out.append(s).append('\n');
    }

    public static String analyze(File runtime)
        throws IOException {

        if (runtime == null || !runtime.isDirectory())
            return "No runtime imported";

        List<File> files = new ArrayList<>();
        collect(runtime, runtime, 0, files);

        String root = runtime.getCanonicalPath()
            + File.separator;

        List<PE> images = new ArrayList<>();
        Map<String,List<PE>> dlls = new HashMap<>();
        int parseErrors = 0;

        for (File file : files) {
            if (file.length() < 64) continue;

            try (Reader r = new Reader(file)) {
                if (r.u16(0) != 0x5a4d) continue;
            }

            String path = file.getCanonicalPath()
                .substring(root.length())
                .replace(File.separatorChar, '/');

            try {
                PE pe = parse(file, path);
                images.add(pe);
                if ("DLL".equals(pe.type)) {
                    dlls.computeIfAbsent(baseName(path),
                        k -> new ArrayList<>()).add(pe);
                    if (!pe.exportName.isEmpty()) {
                        String key = pe.exportName
                            .toLowerCase(Locale.ROOT);
                        List<PE> group = dlls.computeIfAbsent(
                            key, k -> new ArrayList<>());
                        if (!group.contains(pe)) group.add(pe);
                    }
                }
            } catch (Exception ex) {
                parseErrors++;
            }
        }

        StringBuilder out = new StringBuilder();
        line(out, "TITANX PE EXPORT CHECKER v1.9");
        line(out, "");
        line(out, "Files scanned: " + files.size());
        line(out, "PE images: " + images.size());
        line(out, "PE parse errors: " + parseErrors);
        line(out, "");

        int found = 0, missing = 0, system = 0;
        int unresolved = 0, mismatch = 0, ambiguous = 0;
        int exportCount = 0;

        for (PE pe : images) {
            exportCount += pe.exports.size();

            line(out, "-------------------------");
            line(out, pe.path);
            line(out, "Type: " + pe.type);
            line(out, "Architecture: " + pe.arch);
            line(out, "Export DLL name: "
                + (pe.exportName.isEmpty()
                    ? "None" : pe.exportName));
            line(out, "Named exports: "
                + pe.exports.size());
            line(out, "Ordinal exports: "
                + pe.ordinals.size());

            if (!pe.complete)
                line(out, "[PARTIAL] " + pe.error);

            if (pe.imports.isEmpty())
                line(out, "Imports: None");

            for (Dependency dep : pe.imports) {
                String key = dep.dll
                    .toLowerCase(Locale.ROOT);

                List<PE> candidates = dlls.get(key);
                if (candidates == null)
                    candidates = Collections.emptyList();

                List<PE> matching = new ArrayList<>();
                for (PE candidate : candidates) {
                    if (candidate.arch.equals(pe.arch))
                        matching.add(candidate);
                }

                line(out, "IMPORT DLL: " + dep.dll);

                if (matching.size() > 1) {
                    ambiguous += dep.symbols.size();
                    line(out, "  [AMBIGUOUS DLL]");
                    continue;
                }

                if (matching.isEmpty()) {
                    if (!candidates.isEmpty()) {
                        mismatch += dep.symbols.size();
                        line(out, "  [ARCH MISMATCH]");
                    } else if (system(key)) {
                        system += dep.symbols.size();
                        line(out, "  [SYSTEM CANDIDATE]");
                    } else {
                        missing += dep.symbols.size();
                        line(out, "  [DLL MISSING]");
                    }
                    continue;
                }

                PE target = matching.get(0);
                line(out, "  Candidate: " + target.path);

                for (String symbol : dep.symbols) {
                    if (!target.complete) {
                        unresolved++;
                        line(out, "  [UNVERIFIED] " + symbol);
                        continue;
                    }

                    boolean exists;
                    if (symbol.startsWith("#")) {
                        try {
                            long ordinal = Long.parseLong(
                                symbol.substring(1));
                            exists = target.ordinals
                                .contains(ordinal);
                        } catch (NumberFormatException ex) {
                            exists = false;
                        }
                    } else {
                        exists = target.exports.contains(symbol);
                    }

                    if (exists) {
                        found++;
                        line(out, "  [FOUND] " + symbol);
                    } else {
                        missing++;
                        line(out, "  [MISSING EXPORT] " + symbol);
                    }
                }
            }
            line(out, "");
        }

        line(out, "=========================");
        line(out, "SUMMARY");
        line(out, "Named exports: " + exportCount);
        line(out, "FOUND symbols: " + found);
        line(out, "MISSING symbols/DLL: " + missing);
        line(out, "SYSTEM candidates: " + system);
        line(out, "ARCH mismatches: " + mismatch);
        line(out, "AMBIGUOUS: " + ambiguous);
        line(out, "UNVERIFIED: " + unresolved);
        line(out, "");
        line(out, "Static PE metadata analysis only.");
        line(out, "Forwarded exports are not resolved.");
        line(out, "Delay imports are not analyzed.");
        line(out, "DLL search order is approximated.");
        line(out, "No Windows binaries were executed.");
        line(out, "Wine/FEX/DXVK not integrated.");

        if (out.length() >= MAX_REPORT)
            return out.substring(0, MAX_REPORT)
                + "\n[REPORT TRUNCATED]";
        return out.toString();
    }

    public static String selfTest() {
        boolean a = arch(0x8664).equals("x86-64");
        boolean b = arch(0xaa64).equals("ARM64");
        boolean c = system("kernel32.dll");
        boolean d = system("api-ms-win-core-test.dll");
        boolean e = baseName("LIBS/Test.DLL")
            .equals("test.dll");
        int count = 0;
        for (boolean pass : new boolean[]{a,b,c,d,e})
            if (pass) count++;

        return "TITANX PE EXPORT SELF-TEST v1.9\n\n"
            + "x64 machine: " + status(a)
            + "\nARM64 machine: " + status(b)
            + "\nSystem DLL: " + status(c)
            + "\nAPI-set name: " + status(d)
            + "\nDLL filename: " + status(e)
            + "\n\n" + count + "/5 BASIC TESTS PASSED"
            + "\n\nThese are basic logic tests only."
            + "\nReal PE export/import fixtures still needed.";
    }

    private static String status(boolean ok) {
        return ok ? "PASS" : "FAIL";
    }
}
