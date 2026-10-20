package com.titanx.emulator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class CompatibilityFixtures {

    private CompatibilityFixtures() {}

    private static final int ELF_SIZE = 1024;
    private static final long BASE = 0x400000L;

    private static void dynamic(
        ByteBuffer b, int offset, long tag, long value
    ) {
        b.putLong(offset, tag);
        b.putLong(offset + 8, value);
    }

    private static void program(
        ByteBuffer b, int offset,
        int type, long fileOffset,
        long address, long fileSize
    ) {
        b.putInt(offset, type);
        b.putInt(offset + 4, 4);
        b.putLong(offset + 8, fileOffset);
        b.putLong(offset + 16, address);
        b.putLong(offset + 24, address);
        b.putLong(offset + 32, fileSize);
        b.putLong(offset + 40, fileSize);
        b.putLong(offset + 48, 8);
    }

    private static void writeElf(
        File target,
        int machine,
        String soname,
        String[] dependencies
    ) throws IOException {

        ByteBuffer b = ByteBuffer.allocate(ELF_SIZE);
        b.order(ByteOrder.LITTLE_ENDIAN);

        b.put(0, (byte) 0x7f);
        b.put(1, (byte) 'E');
        b.put(2, (byte) 'L');
        b.put(3, (byte) 'F');
        b.put(4, (byte) 2);
        b.put(5, (byte) 1);
        b.put(6, (byte) 1);

        b.putShort(16, (short) 3);
        b.putShort(18, (short) machine);
        b.putInt(20, 1);
        b.putLong(32, 64);
        b.putShort(52, (short) 64);
        b.putShort(54, (short) 56);
        b.putShort(56, (short) 2);

        program(b, 64, 1, 0, BASE, ELF_SIZE);
        program(b, 120, 2, 256, BASE + 256, 128);

        int stringPos = 513;
        int[] indexes = new int[dependencies.length];

        for (int i = 0; i < dependencies.length; i++) {
            byte[] name = dependencies[i].getBytes(
                StandardCharsets.UTF_8
            );

            if (stringPos + name.length + 1 >= 768) {
                throw new IOException("String table full");
            }

            indexes[i] = stringPos - 512;

            for (byte value : name) {
                b.put(stringPos++, value);
            }

            b.put(stringPos++, (byte) 0);
        }

        int sonameIndex = -1;

        if (soname != null && !soname.isEmpty()) {
            byte[] name = soname.getBytes(
                StandardCharsets.UTF_8
            );

            if (stringPos + name.length + 1 >= 768) {
                throw new IOException("String table full");
            }

            sonameIndex = stringPos - 512;

            for (byte value : name) {
                b.put(stringPos++, value);
            }

            b.put(stringPos++, (byte) 0);
        }

        int d = 256;

        dynamic(b, d, 5, BASE + 512);
        d += 16;

        dynamic(b, d, 10, 256);
        d += 16;

        for (int index : indexes) {
            dynamic(b, d, 1, index);
            d += 16;
        }

        if (sonameIndex >= 0) {
            dynamic(b, d, 14, sonameIndex);
            d += 16;
        }

        if (d + 16 > 384) {
            throw new IOException("Dynamic table full");
        }

        dynamic(b, d, 0, 0);

        try (FileOutputStream out =
                 new FileOutputStream(target)) {
            out.write(b.array());
        }
    }

    private static void writeText(
        File target, String text
    ) throws IOException {
        try (FileOutputStream out =
                 new FileOutputStream(target)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
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

    private static void assertContains(
        String report, String expected
    ) throws IOException {
        if (!report.contains(expected)) {
            throw new IOException(
                "Expected result missing: " + expected
            );
        }
    }

    public static String run(File appFiles)
        throws IOException {

        File testDir = new File(
            appFiles, "titanx_v11_test"
        );

        if (!deleteTree(testDir)) {
            throw new IOException(
                "Cannot clean previous test"
            );
        }

        if (!testDir.mkdirs()) {
            throw new IOException(
                "Cannot create test directory"
            );
        }

        try {
            writeElf(
                new File(testDir, "test_program"),
                62,
                null,
                new String[] {
                    "libfound.so",
                    "libwrong.so",
                    "libunknown.so",
                    "libmissing.so"
                }
            );

            writeElf(
                new File(testDir, "renamed_library.bin"),
                62,
                "libfound.so",
                new String[0]
            );

            writeElf(
                new File(testDir, "libwrong.so"),
                183,
                "libwrong.so",
                new String[0]
            );

            writeText(
                new File(testDir, "libunknown.so"),
                "TitanX synthetic non-ELF test"
            );

            String nativeReport = NativeBridge.inspect(
                new File(testDir, "renamed_library.bin")
            );

            assertContains(
                nativeReport,
                "DT_SONAME: libfound.so"
            );

            String report =
                DependencyResolver.analyze(testDir);

            assertContains(report, "[FOUND] libfound.so");
            assertContains(
                report,
                "[INCOMPATIBLE] libwrong.so"
            );
            assertContains(
                report,
                "[UNVERIFIED] libunknown.so"
            );
            assertContains(
                report,
                "[MISSING] libmissing.so"
            );

            assertContains(report, "FOUND: 1");
            assertContains(report, "MISSING: 1");
            assertContains(report, "INCOMPATIBLE: 1");
            assertContains(
                report,
                "UNVERIFIED references: 1"
            );

            return "TITANX v1.1 COMPATIBILITY TEST\n\n"
                + "SONAME: PASS\n"
                + "FOUND: PASS\n"
                + "MISSING: PASS\n"
                + "INCOMPATIBLE: PASS\n"
                + "UNVERIFIED: PASS\n\n"
                + "ALL 5 TESTS PASSED\n\n"
                + report;

        } finally {
            deleteTree(testDir);
        }
    }
}
