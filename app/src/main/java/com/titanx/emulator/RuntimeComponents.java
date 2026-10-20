package com.titanx.emulator;

import java.io.*;
import java.util.*;

public final class RuntimeComponents {

    private RuntimeComponents() {}

    private static final int MAX_FILES = 20000;
    private static final int MAX_DEPTH = 32;

    private static class Item {
        String path;
        String name;
        String arch;
        String component;

        Item(String path, String arch, String component) {
            this.path = path;
            this.name = path.substring(path.lastIndexOf('/') + 1);
            this.arch = arch;
            this.component = component;
        }
    }

    private static String classify(String name) {
        String n = name.toLowerCase(Locale.ROOT);

        if (n.equals("wine")
                || n.equals("wine64")
                || n.equals("wineserver")
                || n.equals("wineboot.exe")
                || n.equals("wine64-preloader")
                || n.equals("wine-preloader")
                || n.equals("ntdll.dll")
                || n.equals("ntdll.so")
                || n.equals("libwine.so")) {
            return "WINE";
        }

        if (n.equals("fexinterpreter")
                || n.equals("fexbash")
                || n.equals("fexloader")
                || n.equals("fexrootfs")) {
            return "FEX";
        }

        if (n.equals("box64")
                || n.equals("box86")) {
            return "BOX";
        }

        if (n.equals("d3d9.dll")
                || n.equals("d3d10core.dll")
                || n.equals("d3d11.dll")
                || n.equals("dxgi.dll")) {
            return "DXVK_CANDIDATE";
        }

        return "";
    }

    private static String arch(File f) {
        byte[] h = new byte[20];

        try (InputStream in = new FileInputStream(f)) {
            int offset = 0;

            while (offset < h.length) {
                int n = in.read(h, offset, h.length - offset);
                if (n < 0) return "UNVERIFIED";
                offset += n;
            }

            if ((h[0] & 255) != 127
                    || h[1] != 'E'
                    || h[2] != 'L'
                    || h[3] != 'F') {
                return "NON_ELF";
            }

            int machine;

            if (h[5] == 1) {
                machine = (h[18] & 255)
                        | ((h[19] & 255) << 8);
            } else if (h[5] == 2) {
                machine = ((h[18] & 255) << 8)
                        | (h[19] & 255);
            } else {
                return "UNVERIFIED";
            }

            switch (machine) {
                case 183: return "ARM64";
                case 40: return "ARM32";
                case 62: return "X86_64";
                case 3: return "X86";
                default: return "UNKNOWN_ELF";
            }

        } catch (IOException e) {
            return "UNVERIFIED";
        }
    }

    private static void walk(
            File root,
            File dir,
            int depth,
            List<Item> items,
            int[] total
    ) throws IOException {

        if (depth > MAX_DEPTH) {
            throw new IOException("Directory depth exceeded");
        }

        File[] children = dir.listFiles();

        if (children == null) {
            throw new IOException("Cannot list directory");
        }

        Arrays.sort(children, Comparator.comparing(File::getName));

        String base = root.getCanonicalPath() + File.separator;

        for (File f : children) {
            String canonical = f.getCanonicalPath();

            if (!canonical.startsWith(base)) {
                throw new IOException("Path escapes runtime");
            }

            if (f.isDirectory()) {
                walk(root, f, depth + 1, items, total);
                continue;
            }

            if (!f.isFile()) continue;

            total[0]++;

            if (total[0] > MAX_FILES) {
                throw new IOException("File limit exceeded");
            }

            String type = classify(f.getName());

            if (type.isEmpty()) continue;

            String relative = canonical.substring(base.length())
                    .replace(File.separatorChar, '/');

            items.add(new Item(relative, arch(f), type));
        }
    }

    private static String status(Item item) {
        if (item.arch.equals("UNVERIFIED")
                || item.arch.equals("UNKNOWN_ELF")) {
            return "UNVERIFIED";
        }

        if (item.component.equals("FEX")
                || item.component.equals("BOX")) {

            if (item.arch.equals("ARM64")) {
                return "DETECTED";
            }

            if (item.arch.equals("NON_ELF")) {
                return "UNVERIFIED";
            }

            return "WRONG ARCH";
        }

        if (item.component.equals("WINE")) {
            if (item.arch.equals("X86_64")
                    || item.arch.equals("X86")
                    || item.arch.equals("ARM64")) {
                return "DETECTED";
            }

            return item.arch.equals("NON_ELF")
                    ? "UNVERIFIED"
                    : "WRONG ARCH";
        }

        if (item.component.equals("DXVK_CANDIDATE")) {
            if (item.arch.equals("NON_ELF")) {
                return "UNVERIFIED";
            }

            return "UNVERIFIED";
        }

        return "UNVERIFIED";
    }

    private static void section(
            StringBuilder out,
            String title,
            String component,
            List<Item> items
    ) {
        out.append("\n").append(title).append("\n");

        int found = 0;

        for (Item item : items) {
            if (!item.component.equals(component)) continue;

            found++;

            out.append("[")
                    .append(status(item))
                    .append("] ")
                    .append(item.path)
                    .append("\n  Architecture: ")
                    .append(item.arch)
                    .append("\n");
        }

        if (found == 0) {
            out.append("[MISSING] No matching filenames\n");
        }

        out.append("Candidates: ").append(found).append("\n");
    }

    public static String analyze(File runtime) throws Exception {
        if (runtime == null || !runtime.isDirectory()) {
            return "TITANX COMPONENT MANAGER v1.5\n\n"
                    + "No runtime imported.";
        }

        List<Item> items = new ArrayList<>();
        int[] total = {0};

        walk(runtime, runtime, 0, items, total);

        StringBuilder out = new StringBuilder();

        out.append("TITANX RUNTIME COMPONENT MANAGER v1.5\n\n");
        out.append("Files scanned: ").append(total[0]).append("\n");
        out.append("Named component candidates: ")
                .append(items.size()).append("\n");

        section(out, "WINE COMPONENTS", "WINE", items);
        section(out, "FEX COMPONENTS", "FEX", items);
        section(out, "BOX64 / BOX86", "BOX", items);
        section(out, "DXVK DLL CANDIDATES",
                "DXVK_CANDIDATE", items);

        out.append("\nREADINESS\n");
        out.append("Native execution integration: NOT READY\n");
        out.append("Wine launch: NOT AVAILABLE\n");
        out.append("FEX translation: NOT AVAILABLE\n");
        out.append("DXVK rendering: NOT AVAILABLE\n\n");

        out.append("IMPORTANT\n");
        out.append("Filename matches are only indicators.\n");
        out.append("ELF machine type does not prove compatibility.\n");
        out.append("Windows DLL files may use PE format, not ELF.\n");
        out.append("No imported files were executed.");

        return out.toString();
    }

    public static String selfTest() {
        boolean wine = classify("wine64").equals("WINE");
        boolean fex = classify("FEXInterpreter").equals("FEX");
        boolean box = classify("box64").equals("BOX");
        boolean dxvk = classify("d3d11.dll")
                .equals("DXVK_CANDIDATE");
        boolean unknown = classify("README.txt").isEmpty();

        int passed = 0;

        if (wine) passed++;
        if (fex) passed++;
        if (box) passed++;
        if (dxvk) passed++;
        if (unknown) passed++;

        return "TITANX COMPONENT SELF-TEST v1.5\n\n"
                + "Wine filename: " + result(wine)
                + "\nFEX filename: " + result(fex)
                + "\nBox64 filename: " + result(box)
                + "\nDXVK DLL filename: " + result(dxvk)
                + "\nUnknown file: " + result(unknown)
                + "\n\n"
                + passed + "/5 TESTS PASSED\n\n"
                + "Filename classification tests only."
                + "\nNo binaries were executed.";
    }

    private static String result(boolean ok) {
        return ok ? "PASS" : "FAIL";
    }
}
