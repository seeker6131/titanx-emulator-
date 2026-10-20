
package com.titanx.emulator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class RuntimeProcessActivity extends Activity {

    private static final int PICK_ZIP = 220;
    private static final int BG = 0xff0a0f1a;
    private static final int PANEL = 0xff182131;
    private static final int GREEN = 0xff36ceb1;
    private static final long MAX_ZIP_BYTES = 1073741824L;
    private static final int MAX_FILES = 10000;
    private static final String TEST_ASSET =
        "runtime/titanx_x64_test";
    private static final String EXPECTED =
        "CPU TEST PASS";

    private LinearLayout content;
    private TextView status;
    private TextView output;
    private File runtimeDir;
    private volatile boolean busy = false;

    private int dp(int value) {
        return (int) (value *
            getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }

    private TextView label(
        String value, int size, int color
    ) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(5), 0, dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.setBackground(shape(PANEL));

        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(16);
        content.addView(c, p);
        return c;
    }

    private void line(
        LinearLayout parent, String value
    ) {
        parent.addView(
            label(value, 14, Color.WHITE)
        );
    }

    private void button(
        LinearLayout parent,
        String title,
        Runnable action
    ) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(shape(GREEN));

        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(-1, dp(54));
        p.bottomMargin = dp(12);
        parent.addView(b, p);

        b.setOnClickListener(v -> action.run());
    }

    private void dialog(
        String title, String message
    ) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        runtimeDir = new File(
            getFilesDir(), "runtime_v22"
        );

        LinearLayout root =
            new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            dp(16), dp(24), dp(16), dp(24)
        );

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);

        buildPage();
    }

    private void buildPage() {
        content.removeAllViews();

        content.addView(label(
            "TITANX / EMULATOR",
            24, Color.WHITE
        ));

        content.addView(label(
            "v2.5.1 • X86-64 CPU Execution",
            13, GREEN
        ));

        LinearLayout device = card();
        line(device, "DEVICE: " + Build.MODEL);
        line(device, "ABI: " +
            Build.SUPPORTED_ABIS[0]);
        line(device, "ANDROID: " +
            Build.VERSION.RELEASE);

        LinearLayout engine = card();
        line(engine, "NATIVE PROCESS ENGINE");

        status = label(
            "STATUS: READY", 15, GREEN
        );
        engine.addView(status);

        button(
            engine,
            "RUN NATIVE PROCESS TEST",
            () -> runCommand(
                "ANDROID NATIVE TEST",
                new String[]{"/system/bin/id"},
                false,
                false
            )
        );

        button(
            engine,
            "CHECK ANDROID ENVIRONMENT",
            () -> runCommand(
                "ANDROID ENVIRONMENT",
                new String[]{
                    "/system/bin/getprop",
                    "ro.product.cpu.abi"
                },
                false,
                false
            )
        );

        button(
            engine,
            "TEST JNI ENGINE",
            () -> dialog(
                "TitanX JNI",
                NativeBridge.report()
            )
        );

        LinearLayout box = card();

        line(box, "BOX64 EXECUTION ENGINE");
        line(box, "Source: APK native libraries");

        File binary = getBox64File();

        line(box, "Box64: " +
            (binary.isFile()
                ? "FOUND"
                : "NOT FOUND"));

        line(box, "Size: " +
            (binary.isFile()
                ? binary.length() : 0)
            + " bytes");

        button(
            box,
            "CHECK EMBEDDED BOX64",
            this::inspectBox64
        );

        button(
            box,
            "RUN BOX64 VERSION TEST",
            this::runBox64
        );

        button(
            box,
            "SHOW BOX64 FILE PATH",
            () -> dialog(
                "Box64 Location",
                getBox64File().getAbsolutePath()
            )
        );

        LinearLayout cpu = card();

        line(cpu, "X86-64 CPU TEST");
        line(cpu, "Architecture: Linux x86-64");
        line(cpu, "Execution: Box64 Dynarec");
        line(cpu, "Expected: 1111111110");

        button(
            cpu,
            "CHECK X86-64 TEST PROGRAM",
            this::inspectX64Test
        );

        button(
            cpu,
            "RUN X86-64 CPU TEST",
            this::runX64Test
        );

        LinearLayout runtime = card();

        line(runtime, "RUNTIME PACKAGE MANAGER");
        line(runtime, "Storage:");
        line(
            runtime,
            runtimeDir.getAbsolutePath()
        );

        line(
            runtime,
            runtimeDir.isDirectory()
                ? "PACKAGE: IMPORTED"
                : "PACKAGE: NOT IMPORTED"
        );

        button(
            runtime,
            "IMPORT RUNTIME ZIP",
            this::pickZip
        );

        button(
            runtime,
            "SCAN INSTALLED COMPONENTS",
            this::scanRuntime
        );

        button(
            runtime,
            "CHECK RUNTIME READINESS",
            this::checkReadiness
        );

        button(
            runtime,
            "REMOVE RUNTIME PACKAGE",
            this::confirmRemove
        );

        LinearLayout game = card();

        line(game, "GTA V LITE 44GB");
        line(game, "Box64: CPU TEST AVAILABLE");
        line(game, "Wine: NOT INTEGRATED");
        line(game, "X11: NOT INTEGRATED");
        line(game, "Turnip/DXVK: NOT INTEGRATED");
        line(game, "GTA V launch: NOT AVAILABLE");

        LinearLayout logs = card();
        line(logs, "RUNTIME LOG");

        output = label(
            "Press RUN X86-64 CPU TEST.",
            13, Color.WHITE
        );

        output.setTextIsSelectable(true);
        logs.addView(output);

        LinearLayout previous = card();

        button(
            previous,
            "OPEN v1.9 INSPECTORS",
            () -> startActivity(
                new Intent(
                    this, MainActivity.class
                )
            )
        );
    }

    private File getBox64File() {
        return new File(
            getApplicationInfo().nativeLibraryDir,
            "libbox64.so"
        );
    }

    private void showResult(
        String report,
        boolean passed,
        boolean popup
    ) {
        status.setText(
            passed
                ? "STATUS: TEST PASS"
                : "STATUS: TEST FAILED / CHECK"
        );

        output.setText(report);

        if (popup) {
            dialog("TitanX Test Result", report);
        }
    }

    private void inspectBox64() {
        File f = getBox64File();

        StringBuilder r = new StringBuilder();

        r.append("BOX64 APK INSPECTION\n\n");
        r.append("Path:\n")
            .append(f.getAbsolutePath());
        r.append("\n\nExists: ")
            .append(f.isFile());
        r.append("\nSize: ")
            .append(f.length());
        r.append("\nCan read: ")
            .append(f.canRead());
        r.append("\nCan execute: ")
            .append(f.canExecute());

        if (f.isFile()) {
            try (FileInputStream in =
                new FileInputStream(f)) {

                byte[] h = new byte[20];
                int n = in.read(h);

                boolean elf = n >= 20
                    && (h[0] & 255) == 127
                    && h[1] == 'E'
                    && h[2] == 'L'
                    && h[3] == 'F';

                boolean arm64 = elf
                    && (h[18] & 255) == 183
                    && (h[19] & 255) == 0;

                r.append("\nELF header: ")
                    .append(elf);
                r.append("\nAArch64 ELF: ")
                    .append(arm64);

            } catch (Exception e) {
                r.append("\nRead error: ")
                    .append(e);
            }
        }

        r.append(
            "\n\nInspection is not execution."
        );

        showResult(
            r.toString(), f.isFile(), true
        );
    }

    private void runBox64() {
        File binary = getBox64File();

        if (!binary.isFile()) {
            showResult(
                "BOX64 NOT FOUND\n\n" +
                binary.getAbsolutePath(),
                false,
                true
            );
            return;
        }

        runCommand(
            "BOX64 VERSION TEST",
            new String[]{
                binary.getAbsolutePath(),
                "--version"
            },
            true,
            false
        );
    }

    private File getX64TestFile() {
        return new File(
            getFilesDir(),
            "titanx_x64_test"
        );
    }

    private File extractX64Test()
        throws IOException {

        File destination = getX64TestFile();
        File temp = new File(
            getFilesDir(),
            "titanx_x64_test.tmp"
        );

        if (temp.exists() && !temp.delete()) {
            throw new IOException(
                "Cannot clear temporary test file"
            );
        }

        long total = 0;

        try (
            InputStream in =
                getAssets().open(TEST_ASSET);
            FileOutputStream out =
                new FileOutputStream(temp)
        ) {
            byte[] buffer = new byte[8192];
            int n;

            while ((n = in.read(buffer)) != -1) {
                total += n;

                if (total > 20 * 1024 * 1024) {
                    throw new IOException(
                        "Test binary too large"
                    );
                }

                out.write(buffer, 0, n);
            }

            out.getFD().sync();
        } catch (IOException e) {
            temp.delete();
            throw e;
        }

        if (total < 64) {
            temp.delete();
            throw new IOException(
                "Test binary is empty or invalid"
            );
        }

        if (destination.exists()
            && !destination.delete()) {
            temp.delete();
            throw new IOException(
                "Cannot replace old test binary"
            );
        }

        if (!temp.renameTo(destination)) {
            temp.delete();
            throw new IOException(
                "Cannot activate test binary"
            );
        }

        return destination;
    }

    private boolean isX64Elf(File file)
        throws IOException {

        try (FileInputStream in =
            new FileInputStream(file)) {

            byte[] h = new byte[20];
            int offset = 0;

            while (offset < h.length) {
                int n = in.read(
                    h,
                    offset,
                    h.length - offset
                );

                if (n < 0) return false;
                offset += n;
            }

            return (h[0] & 255) == 127
                && h[1] == 'E'
                && h[2] == 'L'
                && h[3] == 'F'
                && (h[4] & 255) == 2
                && (h[5] & 255) == 1
                && (h[18] & 255) == 62
                && (h[19] & 255) == 0;
        }
    }

    private void inspectX64Test() {
        if (busy) return;

        busy = true;
        status.setText("STATUS: INSPECTING");

        new Thread(() -> {
            String report;
            boolean passed = false;

            try {
                File test = extractX64Test();
                passed = isX64Elf(test);

                report =
                    "X86-64 TEST INSPECTION\n\n" +
                    "Asset: " + TEST_ASSET +
                    "\nPath: " +
                    test.getAbsolutePath() +
                    "\nSize: " + test.length() +
                    "\nELF64 x86-64: " + passed +
                    "\n\nInspection only." +
                    "\nNo CPU emulation executed.";

            } catch (Exception e) {
                report =
                    "X86-64 TEST INSPECTION FAILED\n\n"
                    + e;
            }

            final String result = report;
            final boolean ok = passed;

            runOnUiThread(() -> {
                busy = false;
                showResult(result, ok, true);
            });
        }).start();
    }

    private void runX64Test() {
        if (busy) return;

        File box64 = getBox64File();

        if (!box64.isFile()) {
            showResult(
                "BOX64 NOT FOUND\n\n" +
                box64.getAbsolutePath(),
                false,
                true
            );
            return;
        }

        busy = true;
        status.setText("STATUS: RUNNING X86-64 TEST");
        output.setText(
            "Extracting x86-64 test program..."
        );

        new Thread(() -> {
            String report;
            boolean passed = false;

            try {
                File test = extractX64Test();

                if (!isX64Elf(test)) {
                    throw new IOException(
                        "Asset is not an ELF64 x86-64 program"
                    );
                }

                String[] command = {
                    box64.getAbsolutePath(),
                    test.getAbsolutePath()
                };

                CommandResult result =
                    executeCommand(command, true);

                passed =
                    result.exitCode == 0
                    && !result.timedOut
                    && result.output.contains(
                        "TITANX X86_64 TEST"
                    )
                    && result.output.contains(
                        "RESULT: 1111111110"
                    )
                    && result.output.contains(
                        EXPECTED
                    );

                report =
                    "TITANX X86-64 CPU TEST\n\n" +
                    "BOX64:\n" +
                    box64.getAbsolutePath() +
                    "\n\nX86-64 PROGRAM:\n" +
                    test.getAbsolutePath() +
                    "\n\nEXIT CODE: " +
                    result.exitCode +
                    "\nTIMEOUT: " +
                    result.timedOut +
                    "\nCPU RESULT: " +
                    (passed ? "PASS" : "FAIL") +
                    "\n\nOUTPUT:\n" +
                    result.output +
                    "\n\n" +
                    (passed
                        ? "Box64 executed the x86-64 test program successfully."
                        : "The expected CPU test output was not verified.");

            } catch (Exception e) {
                report =
                    "X86-64 CPU TEST FAILED\n\n" +
                    e.toString() +
                    "\n\nCheck the runtime log.";
            }

            final String finalReport = report;
            final boolean finalPassed = passed;

            runOnUiThread(() -> {
                busy = false;
                showResult(
                    finalReport,
                    finalPassed,
                    true
                );
            });
        }).start();
    }

    private static class CommandResult {
        int exitCode = -1;
        boolean timedOut;
        String output = "";
    }

    private CommandResult executeCommand(
        String[] command,
        boolean box64Test
    ) throws Exception {

        CommandResult result =
            new CommandResult();

        ProcessBuilder builder =
            new ProcessBuilder(command);

        builder.redirectErrorStream(true);
        builder.directory(getFilesDir());

        builder.environment().put(
            "HOME",
            getFilesDir().getAbsolutePath()
        );

        builder.environment().put(
            "TMPDIR",
            getCacheDir().getAbsolutePath()
        );

        if (box64Test) {
            builder.environment().put(
                "BOX64_LOG", "1"
            );
        }

        Process process = builder.start();

        ByteArrayOutputStream bytes =
            new ByteArrayOutputStream();

        Thread reader = new Thread(() -> {
            try (InputStream in =
                process.getInputStream()) {

                byte[] buffer = new byte[4096];
                int n;

                while ((n = in.read(buffer)) != -1) {
                    synchronized (bytes) {
                        int remaining =
                            65536 - bytes.size();

                        if (remaining > 0) {
                            bytes.write(
                                buffer,
                                0,
                                Math.min(n, remaining)
                            );
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        });

        reader.start();

        try {
            boolean finished = process.waitFor(
                20, TimeUnit.SECONDS
            );

            if (!finished) {
                result.timedOut = true;
                process.destroyForcibly();
                process.waitFor(
                    3, TimeUnit.SECONDS
                );
            } else {
                result.exitCode =
                    process.exitValue();
            }

            reader.join(3000);

            synchronized (bytes) {
                result.output = new String(
                    bytes.toByteArray(),
                    StandardCharsets.UTF_8
                );
            }

            if (result.timedOut) {
                result.output +=
                    "\nPROCESS TIMEOUT (20 seconds)";
            }

        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        return result;
    }

    private void runCommand(
        String title,
        String[] command,
        boolean box64Test,
        boolean strictOutput
    ) {
        if (busy) return;

        busy = true;
        status.setText("STATUS: RUNNING");
        output.setText("Running " + title + "...");

        new Thread(() -> {
            String report;
            boolean passed = false;

            try {
                CommandResult result =
                    executeCommand(
                        command,
                        box64Test
                    );

                passed =
                    result.exitCode == 0
                    && !result.timedOut;

                if (strictOutput) {
                    passed = passed &&
                        result.output.contains(EXPECTED);
                }

                report =
                    title +
                    "\n\nCOMMAND:\n" +
                    Arrays.toString(command) +
                    "\n\nEXIT CODE: " +
                    result.exitCode +
                    "\nTIMEOUT: " +
                    result.timedOut +
                    "\nRESULT: " +
                    (passed ? "PASS" : "FAIL") +
                    "\n\nOUTPUT:\n" +
                    result.output;

            } catch (Exception e) {
                report =
                    title +
                    "\n\nEXECUTION ERROR:\n" +
                    e.toString();
            }

            final String finalReport = report;
            final boolean finalPassed = passed;

            runOnUiThread(() -> {
                busy = false;
                showResult(
                    finalReport,
                    finalPassed,
                    true
                );
            });
        }).start();
    }

    private void pickZip() {
        if (busy) return;

        Intent intent = new Intent(
            Intent.ACTION_OPEN_DOCUMENT
        );

        intent.addCategory(
            Intent.CATEGORY_OPENABLE
        );

        intent.setType("application/zip");

        startActivityForResult(
            intent, PICK_ZIP
        );
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        );

        if (requestCode == PICK_ZIP
            && resultCode == RESULT_OK
            && data != null
            && data.getData() != null) {

            importZip(data.getData());
        }
    }

    private void importZip(Uri uri) {
        if (busy) return;

        busy = true;
        status.setText("STATUS: IMPORTING");

        new Thread(() -> {
            File stage = new File(
                getFilesDir(),
                "runtime_v22_staging"
            );

            File backup = new File(
                getFilesDir(),
                "runtime_v22_backup"
            );

            String result;
            boolean success = false;

            try {
                if (stage.exists()
                    && !deleteTree(stage)) {
                    throw new IOException(
                        "Cannot clear staging directory"
                    );
                }

                if (!stage.mkdirs()) {
                    throw new IOException(
                        "Cannot create staging directory"
                    );
                }

                String root =
                    stage.getCanonicalPath()
                    + File.separator;

                long total = 0;
                int count = 0;

                try (InputStream raw =
                    getContentResolver()
                        .openInputStream(uri)) {

                    if (raw == null) {
                        throw new IOException(
                            "Cannot open ZIP"
                        );
                    }

                    try (ZipInputStream zip =
                        new ZipInputStream(
                            new BufferedInputStream(raw)
                        )) {

                        ZipEntry entry;
                        byte[] buffer =
                            new byte[65536];

                        while ((entry =
                            zip.getNextEntry()) != null) {

                            count++;

                            if (count > MAX_FILES) {
                                throw new IOException(
                                    "Too many ZIP entries"
                                );
                            }

                            String name =
                                entry.getName();

                            if (name.indexOf('\0') >= 0
                                || name.startsWith("/")
                                || name.contains("\\")) {
                                throw new IOException(
                                    "Unsafe ZIP path"
                                );
                            }

                            File target =
                                new File(stage, name);

                            String path =
                                target.getCanonicalPath();

                            if (!path.startsWith(root)) {
                                throw new IOException(
                                    "ZIP traversal blocked"
                                );
                            }

                            if (entry.isDirectory()) {
                                if (!target.isDirectory()
                                    && !target.mkdirs()) {
                                    throw new IOException(
                                        "Cannot create directory"
                                    );
                                }
                            } else {
                                File parent =
                                    target.getParentFile();

                                if (parent == null
                                    || (!parent.isDirectory()
                                    && !parent.mkdirs())) {
                                    throw new IOException(
                                        "Cannot create parent"
                                    );
                                }

                                if (target.exists()) {
                                    throw new IOException(
                                        "Duplicate ZIP path"
                                    );
                                }

                                try (FileOutputStream out =
                                    new FileOutputStream(
                                        target
                                    )) {

                                    int n;

                                    while ((n =
                                        zip.read(buffer)) != -1) {

                                        total += n;

                                        if (total >
                                            MAX_ZIP_BYTES) {
                                            throw new IOException(
                                                "ZIP exceeds 1 GB"
                                            );
                                        }

                                        out.write(
                                            buffer, 0, n
                                        );
                                    }
                                }
                            }

                            zip.closeEntry();
                        }
                    }
                }

                if (count == 0) {
                    throw new IOException(
                        "Empty ZIP"
                    );
                }

                if (backup.exists()
                    && !deleteTree(backup)) {
                    throw new IOException(
                        "Cannot clear backup"
                    );
                }

                if (runtimeDir.exists()
                    && !runtimeDir.renameTo(backup)) {
                    throw new IOException(
                        "Cannot back up runtime"
                    );
                }

                if (!stage.renameTo(runtimeDir)) {
                    if (backup.exists()) {
                        backup.renameTo(runtimeDir);
                    }

                    throw new IOException(
                        "Cannot activate runtime"
                    );
                }

                if (backup.exists()) {
                    deleteTree(backup);
                }

                result =
                    "ZIP IMPORT COMPLETE" +
                    "\nEntries: " + count +
                    "\nBytes: " + total +
                    "\n\nImported files are not executed.";

                success = true;

            } catch (Exception e) {
                result =
                    "IMPORT FAILED\n\n" + e;
            } finally {
                if (stage.exists()) {
                    deleteTree(stage);
                }
            }

            final String report = result;
            final boolean ok = success;

            runOnUiThread(() -> {
                busy = false;
                buildPage();
                showResult(report, ok, true);
            });
        }).start();
    }

    private boolean deleteTree(File root) {
        if (!root.exists()) return true;

        File[] files = root.listFiles();

        if (files != null) {
            for (File file : files) {
                if (!deleteTree(file)) {
                    return false;
                }
            }
        }

        return root.delete();
    }

    private static class Inventory {
        int files;
        int box64;
        int wine;
        int dxvk;
        int elf;
        long bytes;
    }

    private void scanDir(
        File dir,
        Inventory inv,
        int depth
    ) {
        if (depth > 24
            || inv.files >= MAX_FILES) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanDir(
                    f, inv, depth + 1
                );
                continue;
            }

            if (!f.isFile()) continue;

            inv.files++;
            inv.bytes += f.length();

            String name =
                f.getName().toLowerCase(
                    Locale.ROOT
                );

            if (name.equals("box64")) {
                inv.box64++;
            }

            if (name.equals("wine")
                || name.equals("wine64")
                || name.equals("wine-preloader")) {
                inv.wine++;
            }

            if (name.equals("d3d11.dll")
                || name.equals("dxgi.dll")
                || name.equals("d3d9.dll")) {
                inv.dxvk++;
            }

            try (FileInputStream in =
                new FileInputStream(f)) {

                byte[] h = new byte[4];

                if (in.read(h) == 4
                    && (h[0] & 255) == 127
                    && h[1] == 'E'
                    && h[2] == 'L'
                    && h[3] == 'F') {
                    inv.elf++;
                }
            } catch (IOException ignored) {
            }
        }
    }

    private Inventory inventory() {
        Inventory inv = new Inventory();

        if (runtimeDir.isDirectory()) {
            scanDir(
                runtimeDir, inv, 0
            );
        }

        return inv;
    }

    private void scanRuntime() {
        if (busy) return;

        busy = true;

        new Thread(() -> {
            Inventory inv = inventory();

            String report =
                "RUNTIME COMPONENT SCAN v2.5.1\n\n" +
                "Imported files: " + inv.files +
                "\nBytes: " + inv.bytes +
                "\nELF: " + inv.elf +
                "\nBox64 ZIP candidates: " +
                inv.box64 +
                "\nWine candidates: " +
                inv.wine +
                "\nDXVK DLL candidates: " +
                inv.dxvk +
                "\n\nEmbedded Box64: " +
                (getBox64File().isFile()
                    ? "FOUND"
                    : "NOT FOUND") +
                "\n\nZIP files are not executed.";

            runOnUiThread(() -> {
                busy = false;
                showResult(
                    report, true, true
                );
            });
        }).start();
    }

    private void checkReadiness() {
        if (busy) return;

        busy = true;

        new Thread(() -> {
            Inventory inv = inventory();
            File box64 = getBox64File();

            boolean assetPresent;

            try (InputStream in =
                getAssets().open(TEST_ASSET)) {
                assetPresent =
                    in.read() != -1;
            } catch (IOException e) {
                assetPresent = false;
            }

            String report =
                "TITANX READINESS v2.5.1\n\n" +
                "Box64 embedded: " +
                box64.isFile() +
                "\nBox64 executable: " +
                box64.canExecute() +
                "\nX86-64 test asset: " +
                assetPresent +
                "\nImported files: " +
                inv.files +
                "\nWine candidates: " +
                inv.wine +
                "\nDXVK DLL candidates: " +
                inv.dxvk +
                "\n\nCPU execution: TEST SEPARATELY" +
                "\nWine: NOT INTEGRATED" +
                "\nX11: NOT INTEGRATED" +
                "\nTurnip: NOT INTEGRATED" +
                "\nGTA V: NOT READY";

            runOnUiThread(() -> {
                busy = false;
                showResult(
                    report, true, true
                );
            });
        }).start();
    }

    private void confirmRemove() {
        if (busy) return;

        new AlertDialog.Builder(this)
            .setTitle(
                "Remove imported Runtime?"
            )
            .setMessage(
                "Only imported ZIP files will be deleted. " +
                "Embedded Box64 and the CPU test remain."
            )
            .setNegativeButton(
                "CANCEL", null
            )
            .setPositiveButton(
                "REMOVE",
                (d, w) -> {
                    busy = true;

                    new Thread(() -> {
                        boolean ok =
                            deleteTree(runtimeDir);

                        runOnUiThread(() -> {
                            busy = false;
                            buildPage();

                            showResult(
                                ok
                                    ? "Imported runtime removed."
                                    : "Runtime removal failed.",
                                ok,
                                true
                            );
                        });
                    }).start();
                }
            )
            .show();
    }
}
