
package com.titanx.emulator;

import java.io.*;
import java.util.*;

public final class PeInspector {
    private PeInspector() {}

    private static final int MAX_FILES = 20000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_IMPORTS = 256;
    private static final int MAX_SECTIONS = 96;

    private static final class Section {
        long virtualAddress, virtualSize, rawAddress, rawSize;
    }

    private static final class Reader implements Closeable {
        final RandomAccessFile f;
        final long length;

        Reader(File file) throws IOException {
            f = new RandomAccessFile(file, "r");
            length = f.length();
        }

        void check(long pos, long count) throws IOException {
            if (pos < 0 || count < 0 || pos > length
                    || count > length - pos) {
                throw new IOException("Invalid PE offset");
            }
        }

        int u8(long pos) throws IOException {
            check(pos, 1);
            f.seek(pos);
            return f.readUnsignedByte();
        }

        int u16(long pos) throws IOException {
            return u8(pos) | (u8(pos + 1) << 8);
        }

        long u32(long pos) throws IOException {
            return (u16(pos) & 0xffffL)
                    | ((long) u16(pos + 2) << 16);
        }

        String ascii(long pos, int limit) throws IOException {
            check(pos, 1);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < limit && pos + i < length; i++) {
                int b = u8(pos + i);
                if (b == 0) return s.toString();
                if (b < 32 || b > 126) {
                    throw new IOException("Invalid import name");
                }
                s.append((char) b);
            }
            throw new IOException("Unterminated import name");
        }

        @Override
        public void close() throws IOException {
            f.close();
        }
    }

    private static final class Result {
        boolean pe;
        String architecture = "Unknown";
        String type = "Unknown";
        String format = "Unknown";
        String error = "";
        final List<String> imports = new ArrayList<>();
    }

    private static String architecture(int machine) {
        switch (machine) {
            case 0x014c: return "x86";
            case 0x8664: return "x86-64";
            case 0xaa64: return "ARM64";
            case 0x01c0:
            case 0x01c4: return "ARM32";
            default:
                return String.format(Locale.US,
                        "Unknown (0x%04X)", machine);
        }
    }

    private static long rvaToOffset(
            long rva, List<Section> sections,
            long headersSize, long length) throws IOException {

        if (rva < headersSize && rva < length) {
            return rva;
        }

        for (Section s : sections) {
            long extent = Math.max(s.virtualSize, s.rawSize);
            if (rva >= s.virtualAddress
                    && rva - s.virtualAddress < extent) {
                long delta = rva - s.virtualAddress;
                if (delta >= s.rawSize) {
                    throw new IOException("RVA is not file-backed");
                }
                long offset = s.rawAddress + delta;
                if (offset >= length) {
                    throw new IOException("RVA outside file");
                }
                return offset;
            }
        }

        throw new IOException("Unmapped RVA");
    }

    private static Result inspect(File file) {
        Result result = new Result();

        try (Reader r = new Reader(file)) {
            if (r.length < 64 || r.u16(0) != 0x5a4d) {
                result.error = "Not a PE file (MZ missing)";
                return result;
            }

            long pe = r.u32(0x3c);
            r.check(pe, 24);

            if (r.u32(pe) != 0x00004550L) {
                result.error = "Not a PE file (PE signature missing)";
                return result;
            }

            result.pe = true;

            int machine = r.u16(pe + 4);
            int sectionCount = r.u16(pe + 6);
            int optionalSize = r.u16(pe + 20);
            int characteristics = r.u16(pe + 22);

            result.architecture = architecture(machine);
            result.type = (characteristics & 0x2000) != 0
                    ? "DLL" : "EXE / other PE image";

            long opt = pe + 24;
            r.check(opt, optionalSize);

            int magic = r.u16(opt);
            boolean pe32plus;

            if (magic == 0x20b) {
                pe32plus = true;
                result.format = "PE32+ (64-bit)";
            } else if (magic == 0x10b) {
                pe32plus = false;
                result.format = "PE32 (32-bit)";
            } else {
                result.error = "Unsupported optional header";
                return result;
            }

            int dataDirectoryOffset = pe32plus ? 112 : 96;
            int directoryCountOffset = pe32plus ? 108 : 92;

            if (optionalSize < dataDirectoryOffset) {
                result.error = "Truncated optional header";
                return result;
            }

            long headersSize = r.u32(opt + 60);
            long directories = r.u32(opt + directoryCountOffset);

            if (sectionCount > MAX_SECTIONS) {
                result.error = "Too many sections";
                return result;
            }

            long sectionTable = opt + optionalSize;
            r.check(sectionTable, sectionCount * 40L);

            List<Section> sections = new ArrayList<>();

            for (int i = 0; i < sectionCount; i++) {
                long at = sectionTable + i * 40L;
                Section s = new Section();
                s.virtualSize = r.u32(at + 8);
                s.virtualAddress = r.u32(at + 12);
                s.rawSize = r.u32(at + 16);
                s.rawAddress = r.u32(at + 20);
                sections.add(s);
            }

            if (directories < 2
                    || optionalSize < dataDirectoryOffset + 16) {
                return result;
            }

            long importRva = r.u32(opt + dataDirectoryOffset + 8);
            long importSize = r.u32(opt + dataDirectoryOffset + 12);

            if (importRva == 0 || importSize == 0) {
                return result;
            }

            long table = rvaToOffset(
                    importRva, sections, headersSize, r.length);

            long maxDescriptors = Math.min(
                    MAX_IMPORTS, importSize / 20L);

            boolean terminated = false;

            for (long i = 0; i < maxDescriptors; i++) {
                long at = table + i * 20L;
                r.check(at, 20);

                long originalThunk = r.u32(at);
                long timestamp = r.u32(at + 4);
                long forwarder = r.u32(at + 8);
                long nameRva = r.u32(at + 12);
                long firstThunk = r.u32(at + 16);

                if (originalThunk == 0 && timestamp == 0
                        && forwarder == 0 && nameRva == 0
                        && firstThunk == 0) {
                    terminated = true;
                    break;
                }

                if (nameRva == 0) {
                    throw new IOException("Import name RVA missing");
                }

                long nameOffset = rvaToOffset(
                        nameRva, sections, headersSize, r.length);

                String name = r.ascii(nameOffset, 260);
                result.imports.add(name);
            }

            if (!terminated) {
                result.error = "Import table not fully terminated "
                        + "within declared size/limit";
            }

        } catch (Exception e) {
            result.error = e.getMessage() == null
                    ? e.toString() : e.getMessage();
        }

        return result;
    }

    private static void walk(
            File root, File directory, int depth,
            List<File> files, int[] count) throws IOException {

        if (depth > MAX_DEPTH) {
            throw new IOException("Directory depth limit exceeded");
        }

        File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("Cannot read directory");
        }

        Arrays.sort(children, Comparator.comparing(File::getName));
        String base = root.getCanonicalPath() + File.separator;

        for (File child : children) {
            String path = child.getCanonicalPath();

            if (!path.startsWith(base)) {
                throw new IOException("Path outside runtime");
            }

            if (child.isDirectory()) {
                walk(root, child, depth + 1, files, count);
            } else if (child.isFile()) {
                if (++count[0] > MAX_FILES) {
                    throw new IOException("File limit exceeded");
                }

                // Inspect signatures, not only extensions.
                if (child.length() >= 64) {
                    try (RandomAccessFile r =
                                 new RandomAccessFile(child, "r")) {
                        if (r.readUnsignedByte() == 'M'
                                && r.readUnsignedByte() == 'Z') {
                            files.add(child);
                        }
                    }
                }
            }
        }
    }

    public static String analyze(File runtime) throws IOException {
        if (runtime == null || !runtime.isDirectory()) {
            return "No runtime imported";
        }

        List<File> candidates = new ArrayList<>();
        int[] scanned = {0};

        walk(runtime, runtime, 0, candidates, scanned);

        StringBuilder out = new StringBuilder();
        out.append("TITANX WINDOWS PE INSPECTOR v1.6\n\n");
        out.append("Files scanned: ").append(scanned[0]).append('\n');
        out.append("MZ candidates: ").append(candidates.size())
                .append("\n\n");

        int valid = 0;
        int errors = 0;

        String base = runtime.getCanonicalPath() + File.separator;

        for (File file : candidates) {
            Result p = inspect(file);

            String relative = file.getCanonicalPath()
                    .substring(base.length())
                    .replace(File.separatorChar, '/');

            out.append("------------------------\n");
            out.append(relative).append('\n');

            if (!p.pe) {
                out.append("NOT PE: ").append(p.error)
                        .append("\n\n");
                errors++;
                continue;
            }

            valid++;
            out.append("Format: ").append(p.format).append('\n');
            out.append("Architecture: ")
                    .append(p.architecture).append('\n');
            out.append("Type: ").append(p.type).append('\n');

            out.append("Imported DLLs: ")
                    .append(p.imports.size()).append('\n');

            if (p.imports.isEmpty()) {
                out.append("  None found in normal Import Directory\n");
            } else {
                for (String name : p.imports) {
                    out.append("  - ").append(name).append('\n');
                }
            }

            if (!p.error.isEmpty()) {
                errors++;
                out.append("WARNING: ").append(p.error)
                        .append('\n');
            }

            out.append('\n');
        }

        out.append("========================\n");
        out.append("SUMMARY\n");
        out.append("PE files detected: ").append(valid).append('\n');
        out.append("Parsing warnings/errors: ")
                .append(errors).append("\n\n");
        out.append("No imported binaries were executed.\n");
        out.append("Only the normal PE Import Directory is read.\n");
        out.append("Delay-load imports and runtime-loaded DLLs "
                + "are not included.\n");
        out.append("PE metadata does not prove compatibility.");

        return out.toString();
    }

    public static String selfTest() {
        boolean mz = (('M' & 255) | (('Z' & 255) << 8))
                == 0x5a4d;
        boolean x86 = architecture(0x014c).equals("x86");
        boolean x64 = architecture(0x8664).equals("x86-64");
        boolean arm64 = architecture(0xaa64).equals("ARM64");
        boolean dllFlag = (0x2000 & 0x2000) != 0;

        int passed = 0;
        if (mz) passed++;
        if (x86) passed++;
        if (x64) passed++;
        if (arm64) passed++;
        if (dllFlag) passed++;

        return "TITANX PE SELF-TEST v1.6\n\n"
                + "MZ signature: " + status(mz)
                + "\nx86 machine: " + status(x86)
                + "\nx64 machine: " + status(x64)
                + "\nARM64 machine: " + status(arm64)
                + "\nDLL flag: " + status(dllFlag)
                + "\n\n" + passed + "/5 BASIC TESTS PASSED"
                + "\n\nThis does not test complete PE parsing."
                + "\nNo binaries executed.";
    }

    private static String status(boolean ok) {
        return ok ? "PASS" : "FAIL";
    }
}
