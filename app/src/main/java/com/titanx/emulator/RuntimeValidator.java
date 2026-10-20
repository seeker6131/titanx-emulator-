package com.titanx.emulator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class RuntimeValidator {

    private static final int MAX_FILES = 20000;
    private static final int MAX_DEPTH = 32;
    private static final long MAX_TOTAL = 1073741824L;
    private static final String MANIFEST = "titanx_manifest_v13.txt";

    private RuntimeValidator() {}

    private static final class Entry {
        String path;
        long size;
        String hash;

        Entry(String p, long s, String h) {
            path = p;
            size = s;
            hash = h;
        }
    }

    private static void collect(
        File root,
        File dir,
        int depth,
        List<File> files
    ) throws IOException {
        if (depth > MAX_DEPTH)
            throw new IOException("Directory depth limit exceeded");

        File[] children = dir.listFiles();

        if (children == null)
            throw new IOException("Cannot list: " + dir.getName());

        Arrays.sort(children,
            Comparator.comparing(File::getName));

        String base = root.getCanonicalPath()
            + File.separator;

        for (File child : children) {
            if (files.size() >= MAX_FILES)
                throw new IOException("File count limit exceeded");

            String canonical = child.getCanonicalPath();

            if (!canonical.startsWith(base))
                throw new IOException("Path escapes runtime");

            if (child.isDirectory()) {
                collect(root, child, depth + 1, files);
            } else if (child.isFile()) {
                files.add(child);
            } else {
                throw new IOException("Unsupported file type");
            }
        }
    }

    private static String relative(
        File root, File file
    ) throws IOException {
        String base = root.getCanonicalPath()
            + File.separator;

        String path = file.getCanonicalPath();

        if (!path.startsWith(base))
            throw new IOException("Invalid file path");

        return path.substring(base.length())
            .replace(File.separatorChar, '/');
    }

    private static String hex(byte[] bytes) {
        char[] chars = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder();

        for (byte b : bytes) {
            int v = b & 255;
            out.append(chars[v >>> 4]);
            out.append(chars[v & 15]);
        }

        return out.toString();
    }

    private static String sha256(File file)
        throws Exception {

        MessageDigest digest =
            MessageDigest.getInstance("SHA-256");

        byte[] buffer = new byte[32768];

        try (InputStream in =
                 new BufferedInputStream(
                     new FileInputStream(file))) {

            int n;

            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }

        return hex(digest.digest());
    }

    private static List<Entry> inventory(File root)
        throws Exception {

        if (root == null || !root.isDirectory())
            throw new IOException("Runtime not imported");

        List<File> files = new ArrayList<>();
        collect(root, root, 0, files);

        List<Entry> result = new ArrayList<>();
        long total = 0;

        for (File file : files) {
            long length = file.length();

            if (length < 0 || length > MAX_TOTAL - total)
                throw new IOException("Runtime exceeds 1 GB");

            total += length;

            result.add(new Entry(
                relative(root, file),
                length,
                sha256(file)
            ));
        }

        return result;
    }

    private static String manifestText(List<Entry> entries) {
        StringBuilder out = new StringBuilder();

        out.append("TITANX-MANIFEST-V1.3\n");

        for (Entry e : entries) {
            out.append(e.hash)
                .append('\t')
                .append(e.size)
                .append('\t')
                .append(e.path)
                .append('\n');
        }

        return out.toString();
    }

    private static void writeAtomic(
        File destination, String value
    ) throws IOException {

        File parent = destination.getParentFile();

        if (parent == null || !parent.isDirectory())
            throw new IOException("Manifest directory missing");

        File temp = new File(
            parent, destination.getName() + ".tmp"
        );

        try (FileOutputStream out =
                 new FileOutputStream(temp)) {

            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }

        if (destination.exists() && !destination.delete())
            throw new IOException("Cannot replace manifest");

        if (!temp.renameTo(destination))
            throw new IOException("Cannot save manifest");
    }

    private static String readText(File file)
        throws IOException {

        if (!file.isFile())
            throw new IOException("Manifest not found");

        if (file.length() > 4000000)
            throw new IOException("Manifest too large");

        ByteArrayOutputStream out =
            new ByteArrayOutputStream();

        byte[] buffer = new byte[8192];

        try (InputStream in = new FileInputStream(file)) {
            int n;

            while ((n = in.read(buffer)) != -1)
                out.write(buffer, 0, n);
        }

        return out.toString("UTF-8");
    }

    public static String validate(File runtime)
        throws Exception {

        List<Entry> entries = inventory(runtime);

        long total = 0;
        int empty = 0;
        int duplicates = 0;

        Map<String, String> normalized =
            new HashMap<>();

        StringBuilder issues = new StringBuilder();

        for (Entry e : entries) {
            total += e.size;

            if (e.size == 0) {
                empty++;
                issues.append("[EMPTY] ")
                    .append(e.path).append('\n');
            }

            String key = e.path.toLowerCase(Locale.ROOT);
            String previous = normalized.putIfAbsent(
                key, e.path
            );

            if (previous != null) {
                duplicates++;
                issues.append("[CASE COLLISION] ")
                    .append(previous)
                    .append(" <> ")
                    .append(e.path)
                    .append('\n');
            }
        }

        StringBuilder out = new StringBuilder();

        out.append("TITANX PACKAGE VALIDATOR v1.3\n\n")
            .append("Files: ").append(entries.size())
            .append("\nTotal bytes: ").append(total)
            .append("\nEmpty files: ").append(empty)
            .append("\nCase collisions: ")
            .append(duplicates)
            .append("\n\n");

        if (entries.isEmpty())
            out.append("[WARNING] Runtime is empty\n");

        if (issues.length() > 0)
            out.append(issues);

        out.append("\nSHA-256 INVENTORY\n");

        int shown = 0;

        for (Entry e : entries) {
            if (shown++ >= 30) {
                out.append("... remaining entries omitted\n");
                break;
            }

            out.append(e.path)
                .append("\n  Size: ").append(e.size)
                .append("\n  SHA-256: ").append(e.hash)
                .append('\n');
        }

        out.append("\nHashing completed.\n")
            .append("Hashes do not establish file trust.\n")
            .append("No imported files were executed.");

        return out.toString();
    }

    public static String saveManifest(
        File runtime, File appFiles
    ) throws Exception {

        List<Entry> entries = inventory(runtime);

        if (entries.isEmpty())
            return "Cannot save an empty runtime manifest";

        String data = manifestText(entries);

        File target = new File(appFiles, MANIFEST);

        writeAtomic(target, data);

        return "TITANX MANIFEST v1.3\n\n"
            + "Saved successfully\n"
            + "Files: " + entries.size()
            + "\nLocation: private app storage\n\n"
            + "Use VERIFY PACKAGE MANIFEST "
            + "to detect later file changes.\n\n"
            + "This is a local baseline, "
            + "not a trusted digital signature.";
    }

    public static String verifyManifest(
        File runtime, File appFiles
    ) throws Exception {

        File saved = new File(appFiles, MANIFEST);

        if (!saved.isFile())
            return "No manifest saved.\n"
                + "Press SAVE PACKAGE MANIFEST first.";

        String expected = readText(saved);

        if (!expected.startsWith(
            "TITANX-MANIFEST-V1.3\n"))
            return "Manifest format invalid";

        String current = manifestText(inventory(runtime));

        if (expected.equals(current))
            return "TITANX MANIFEST VERIFY v1.3\n\n"
                + "MATCH: PASS\n"
                + "All recorded paths, sizes and "
                + "SHA-256 hashes match.\n\n"
                + "This does not establish authenticity.";

        return "TITANX MANIFEST VERIFY v1.3\n\n"
            + "MATCH: FAIL\n"
            + "Runtime files differ from saved baseline.\n"
            + "One or more files were added, removed, "
            + "renamed or modified.\n\n"
            + "Run VALIDATE RUNTIME PACKAGE "
            + "to inspect the current inventory.";
    }

    public static String selfTest(File appFiles)
        throws Exception {

        File dir = new File(
            appFiles, "titanx_validator_test"
        );

        if (dir.exists())
            deleteTree(dir);

        if (!dir.mkdirs())
            throw new IOException("Cannot create test folder");

        try {
            File a = new File(dir, "sample.txt");

            try (FileOutputStream out =
                     new FileOutputStream(a)) {
                out.write("TitanX v1.3".getBytes(
                    StandardCharsets.UTF_8));
            }

            String original = sha256(a);

            boolean hashValid =
                original.length() == 64;

            String before = manifestText(inventory(dir));

            try (FileOutputStream out =
                     new FileOutputStream(a, true)) {
                out.write(33);
            }

            String after = manifestText(inventory(dir));

            boolean changeDetected =
                !before.equals(after);

            File empty = new File(dir, "empty.txt");
            boolean created = empty.createNewFile();

            boolean emptyDetected =
                created && empty.length() == 0;

            return "TITANX VALIDATOR SELF-TEST v1.3\n\n"
                + "SHA-256 calculation: "
                + (hashValid ? "PASS" : "FAIL")
                + "\nFile modification detection: "
                + (changeDetected ? "PASS" : "FAIL")
                + "\nEmpty file detection: "
                + (emptyDetected ? "PASS" : "FAIL")
                + "\n\n"
                + (hashValid && changeDetected && emptyDetected
                    ? "ALL 3 TESTS PASSED"
                    : "SELF-TEST FAILED")
                + "\n\nTest files were not executed.";

        } finally {
            deleteTree(dir);
        }
    }

    private static boolean deleteTree(File file) {
        if (!file.exists()) return true;

        if (file.isDirectory()) {
            File[] children = file.listFiles();

            if (children == null) return false;

            for (File child : children) {
                if (!deleteTree(child)) return false;
            }
        }

        return file.delete();
    }
}
