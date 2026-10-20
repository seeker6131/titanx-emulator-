
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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.TimeUnit;

public class RuntimeProcessActivity extends Activity {

    private static final int PICK_RUNTIME = 220;
    private static final int BG = 0xff0a0f1a;
    private static final int PANEL = 0xff182131;
    private static final int GREEN = 0xff36ceb1;
    private static final int MUTED = 0xffa5b4c8;

    private static final long MAX_BYTES =
            1024L * 1024 * 1024;
    private static final int MAX_FILES = 10000;

    private LinearLayout content;
    private TextView status;
    private TextView output;
    private File runtimeDir;
    private boolean busy = false;

    private int dp(int n) {
        return (int)(n *
                getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT);
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
        p.bottomMargin = dp(15);
        content.addView(c, p);
        return c;
    }

    private void button(
            LinearLayout parent,
            String title,
            Runnable action) {

        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(shape(GREEN));

        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(-1, dp(54));
        p.bottomMargin = dp(10);

        parent.addView(b, p);
        b.setOnClickListener(v -> action.run());
    }

    private void line(LinearLayout parent, String value) {
        parent.addView(text(value, 14, Color.WHITE));
    }

    private void dialog(String title, String value) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(value)
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        runtimeDir = new File(getFilesDir(), "runtime_v22");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                dp(16), dp(24), dp(16), dp(24));

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);

        buildPage();
    }

    private void buildPage() {
        content.removeAllViews();

        content.addView(text(
                "TITANX / EMULATOR",
                24, Color.WHITE));

        content.addView(text(
                "v2.2 • Runtime Package Installer",
                13, GREEN));

        LinearLayout device = card();

        line(device, "DEVICE: " + Build.MODEL);
        line(device, "ABI: " + Build.SUPPORTED_ABIS[0]);
        line(device, "ANDROID: " + Build.VERSION.RELEASE);

        LinearLayout engine = card();

        line(engine, "NATIVE PROCESS ENGINE");

        status = text("STATUS: READY", 15, GREEN);
        engine.addView(status);

        button(engine, "RUN NATIVE PROCESS TEST",
                () -> runProcess(
                        "/system/bin/id"));

        button(engine, "CHECK ANDROID ENVIRONMENT",
                () -> runProcess(
                        "/system/bin/getprop",
                        "ro.product.cpu.abi"));

        button(engine, "TEST JNI ENGINE",
                () -> dialog(
                        "TitanX JNI",
                        NativeBridge.report()));

        LinearLayout runtime = card();

        line(runtime, "RUNTIME PACKAGE MANAGER");

        line(runtime, "Runtime storage:");
        line(runtime, runtimeDir.getAbsolutePath());

        line(runtime, runtimeDir.isDirectory()
                ? "PACKAGE: INSTALLED FILES PRESENT"
                : "PACKAGE: NOT IMPORTED");

        button(runtime, "IMPORT RUNTIME ZIP",
                this::pickRuntime);

        button(runtime, "SCAN INSTALLED COMPONENTS",
                this::scanAsync);

        button(runtime, "CHECK RUNTIME READINESS",
                this::readinessAsync);

        button(runtime, "REMOVE RUNTIME PACKAGE",
                this::confirmRemove);

        LinearLayout game = card();

        line(game, "GTA V LITE 44GB");

        line(game, "Box64: requires integration");
        line(game, "Wine: requires integration");
        line(game, "X11: requires integration");
        line(game, "Turnip/DXVK: requires integration");
        line(game, "GTA V launch: NOT AVAILABLE");

        LinearLayout logs = card();

        line(logs, "RUNTIME LOG");

        output = text(
                "Import a runtime ZIP or run a test.",
                13, Color.WHITE);
        output.setTextIsSelectable(true);
        logs.addView(output);

        LinearLayout old = card();

        button(old, "OPEN v1.9 INSPECTORS",
                () -> startActivity(
                        new Intent(this, MainActivity.class)));
    }

    private void setOutput(String value, boolean success) {
        status.setText(success
                ? "STATUS: OPERATION COMPLETE"
                : "STATUS: CHECK REPORT");
        output.setText(value);
    }

    private void runProcess(String... command) {
        if (busy) return;

        busy = true;
        status.setText("STATUS: RUNNING");

        new Thread(() -> {
            String result;
            Process process = null;

            try {
                process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();

                boolean finished =
                        process.waitFor(8, TimeUnit.SECONDS);

                if (!finished) {
                    process.destroyForcibly();
                    result = "PROCESS TIMEOUT";
                } else {
                    int code = process.exitValue();

                    StringBuilder log = new StringBuilder();

                    try (InputStream in =
                                 process.getInputStream()) {

                        byte[] buffer = new byte[4096];
                        int n;

                        while ((n = in.read(buffer)) != -1) {
                            if (log.length() >= 16384) break;
                            log.append(new String(
                                    buffer, 0, n,
                                    java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }

                    result = "EXIT CODE: " + code
                            + "\n" + log;
                }

            } catch (Exception e) {
                result = "PROCESS ERROR: " + e;
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            final String report = result;

            runOnUiThread(() -> {
                busy = false;
                setOutput(report,
                        report.startsWith("EXIT CODE: 0"));
            });
        }).start();
    }

    private void pickRuntime() {
        if (busy) return;

        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");

        startActivityForResult(i, PICK_RUNTIME);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode, resultCode, data);

        if (requestCode == PICK_RUNTIME
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            importRuntime(data.getData());
        }
    }

    private void importRuntime(Uri uri) {
        if (busy) return;

        busy = true;
        status.setText("STATUS: IMPORTING");

        new Thread(() -> {
            String result;
            boolean success = false;

            File staging = new File(
                    getFilesDir(), "runtime_v22_staging");

            File backup = new File(
                    getFilesDir(), "runtime_v22_backup");

            try {
                if (staging.exists()) deleteTree(staging);
                if (!staging.mkdirs()) {
                    throw new IOException(
                            "Cannot create staging directory");
                }

                String rootPath =
                        staging.getCanonicalPath() + File.separator;

                long total = 0;
                int count = 0;

                try (InputStream raw =
                             getContentResolver().openInputStream(uri)) {

                    if (raw == null) {
                        throw new IOException("Cannot open ZIP");
                    }

                    try (ZipInputStream zip =
                                 new ZipInputStream(
                                         new BufferedInputStream(raw))) {

                        ZipEntry entry;
                        byte[] buffer = new byte[65536];

                        while ((entry = zip.getNextEntry()) != null) {
                            count++;

                            if (count > MAX_FILES) {
                                throw new IOException(
                                        "Too many ZIP entries");
                            }

                            String name = entry.getName();

                            if (name.indexOf('\0') >= 0
                                    || name.startsWith("/")
                                    || name.startsWith("\\")
                                    || name.contains("\\")) {
                                throw new IOException(
                                        "Unsafe ZIP entry");
                            }

                            File target = new File(staging, name);
                            String path = target.getCanonicalPath();

                            if (!path.startsWith(rootPath)) {
                                throw new IOException(
                                        "ZIP path traversal blocked");
                            }

                            if (entry.isDirectory()) {
                                if (!target.isDirectory()
                                        && !target.mkdirs()) {
                                    throw new IOException(
                                            "Cannot create directory");
                                }
                            } else {
                                File parent = target.getParentFile();

                                if (parent == null
                                        || (!parent.isDirectory()
                                        && !parent.mkdirs())) {
                                    throw new IOException(
                                            "Cannot create parent");
                                }

                                if (target.exists()) {
                                    throw new IOException(
                                            "Duplicate file path");
                                }

                                try (FileOutputStream out =
                                             new FileOutputStream(target)) {

                                    int n;

                                    while ((n = zip.read(buffer)) != -1) {
                                        total += n;

                                        if (total > MAX_BYTES) {
                                            throw new IOException(
                                                    "ZIP exceeds 1 GB");
                                        }

                                        out.write(buffer, 0, n);
                                    }
                                }
                            }

                            zip.closeEntry();
                        }
                    }
                }

                if (count == 0) {
                    throw new IOException("Empty ZIP");
                }

                if (backup.exists()) deleteTree(backup);

                if (runtimeDir.exists()
                        && !runtimeDir.renameTo(backup)) {
                    throw new IOException(
                            "Cannot back up old runtime");
                }

                if (!staging.renameTo(runtimeDir)) {
                    if (backup.exists()) {
                        backup.renameTo(runtimeDir);
                    }
                    throw new IOException(
                            "Cannot activate imported package");
                }

                if (backup.exists()) deleteTree(backup);

                result = "RUNTIME ZIP IMPORTED\n"
                        + "Entries: " + count
                        + "\nExtracted bytes: " + total
                        + "\n\nFiles installed for inspection."
                        + "\nNo binaries executed.";

                success = true;

            } catch (Exception e) {
                result = "IMPORT FAILED\n" + e;
            } finally {
                if (staging.exists()) deleteTree(staging);
            }

            final String report = result;
            final boolean ok = success;

            runOnUiThread(() -> {
                busy = false;
                buildPage();
                setOutput(report, ok);
                dialog("Runtime Package", report);
            });
        }).start();
    }

    private boolean deleteTree(File root) {
        if (!root.exists()) return true;

        File[] children = root.listFiles();

        if (children != null) {
            for (File child : children) {
                if (!deleteTree(child)) return false;
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
        final ArrayList<String> candidates =
                new ArrayList<>();
    }

    private void scanDirectory(
            File dir, Inventory inventory, int depth) {

        if (depth > 24 || inventory.files >= MAX_FILES) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectory(f, inventory, depth + 1);
                continue;
            }

            if (!f.isFile()) continue;

            inventory.files++;
            inventory.bytes += f.length();

            String name = f.getName().toLowerCase(Locale.ROOT);

            if (name.equals("box64")) {
                inventory.box64++;
                inventory.candidates.add(f.getAbsolutePath());
            }

            if (name.equals("wine")
                    || name.equals("wine64")
                    || name.equals("wine-preloader")) {
                inventory.wine++;
                inventory.candidates.add(f.getAbsolutePath());
            }

            if (name.equals("d3d11.dll")
                    || name.equals("dxgi.dll")
                    || name.equals("d3d9.dll")) {
                inventory.dxvk++;
            }

            try (FileInputStream in = new FileInputStream(f)) {
                byte[] h = new byte[4];

                if (in.read(h) == 4
                        && (h[0] & 255) == 127
                        && h[1] == 'E'
                        && h[2] == 'L'
                        && h[3] == 'F') {
                    inventory.elf++;
                }
            } catch (IOException ignored) {
            }
        }
    }

    private Inventory inventory() {
        Inventory inv = new Inventory();

        if (runtimeDir.isDirectory()) {
            scanDirectory(runtimeDir, inv, 0);
        }

        return inv;
    }

    private void scanAsync() {
        if (busy) return;
        busy = true;

        new Thread(() -> {
            Inventory inv = inventory();

            StringBuilder report = new StringBuilder();

            report.append("TITANX COMPONENT SCAN v2.2\n\n");
            report.append("Files: ").append(inv.files).append('\n');
            report.append("Bytes: ").append(inv.bytes).append('\n');
            report.append("ELF files: ").append(inv.elf).append('\n');
            report.append("Box64 filenames: ").append(inv.box64).append('\n');
            report.append("Wine filenames: ").append(inv.wine).append('\n');
            report.append("DXVK DLL filenames: ").append(inv.dxvk).append('\n');

            report.append("\nCANDIDATES:\n");

            for (String path : inv.candidates) {
                report.append(path).append('\n');
            }

            report.append("\nFilename detection only.");
            report.append("\nNo runtime executed.");

            runOnUiThread(() -> {
                busy = false;
                setOutput(report.toString(), true);
            });
        }).start();
    }

    private void readinessAsync() {
        if (busy) return;
        busy = true;

        new Thread(() -> {
            Inventory inv = inventory();

            String report =
                    "TITANX RUNTIME READINESS v2.2\n\n"
                    + "Package files: " + inv.files
                    + "\nBox64 candidates: " + inv.box64
                    + "\nWine candidates: " + inv.wine
                    + "\nDXVK DLL candidates: " + inv.dxvk
                    + "\n\nBox64 execution: NOT VERIFIED"
                    + "\nWine execution: NOT VERIFIED"
                    + "\nX11 display: NOT INTEGRATED"
                    + "\nTurnip driver: NOT INTEGRATED"
                    + "\nGTA V Lite: NOT READY"
                    + "\n\nNext: integrate compatible "
                    + "ARM64 runtime binaries and display.";

            runOnUiThread(() -> {
                busy = false;
                setOutput(report, true);
            });
        }).start();
    }

    private void confirmRemove() {
        if (busy) return;

        new AlertDialog.Builder(this)
                .setTitle("Remove Runtime?")
                .setMessage(
                        "Delete imported runtime files? "
                        + "The v1.9 tools will remain.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("REMOVE", (d, w) -> {
                    busy = true;

                    new Thread(() -> {
                        boolean ok = deleteTree(runtimeDir);

                        runOnUiThread(() -> {
                            busy = false;
                            buildPage();
                            dialog("Runtime Removal",
                                    ok ? "Removed"
                                       : "Some files could not be removed");
                        });
                    }).start();
                })
                .show();
    }
}
