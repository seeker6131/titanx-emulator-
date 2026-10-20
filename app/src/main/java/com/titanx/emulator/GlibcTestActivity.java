package com.titanx.emulator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GlibcTestActivity extends Activity {
    private static final String VERSION = "2.9";
    private static final String ASSET_DIR = "linux_x64";
    private static final String[] FILES = {
        "ld-linux-x86-64.so.2", "libc.so.6", "titanx_glibc_test"
    };
    private TextView status, output;
    private File runtimeDir;
    private volatile boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        runtimeDir = new File(getFilesDir(), "titanx_glibc_v27");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 30, 24, 30);
        root.setBackgroundColor(0xff0a0f1a);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        addText(root, "TITANX / EMULATOR v" + VERSION, 24, 0xff36ceb1);
        addText(root, "Box64 + x86-64 GLIBC diagnostic\nWine / DXVK / GTA V: NOT INTEGRATED", 14, Color.WHITE);
        status = addText(root, "STATUS: READY", 17, 0xff36ceb1);
        addButton(root, "CHECK ELF FILES", this::inspect);
        addButton(root, "INSTALL GLIBC TEST FILES", this::install);
        addButton(root, "RUN GLIBC TEST v2.9", this::runTest);
        addButton(root, "OPEN BOX64 CPU TEST", () -> startActivity(new Intent(this, RuntimeProcessActivity.class)));
        output = addText(root, "Ready", 13, Color.WHITE);
        output.setTextIsSelectable(true);
    }

    private TextView addText(LinearLayout parent, String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(4, 12, 4, 16);
        parent.addView(t);
        return t;
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        parent.addView(b);
        b.setOnClickListener(v -> action.run());
    }

    private File box64() {
        return new File(getApplicationInfo().nativeLibraryDir, "libbox64.so");
    }

    private File file(String name) { return new File(runtimeDir, name); }

    private boolean elf(File f, int machine) throws Exception {
        if (!f.isFile()) return false;
        byte[] h = new byte[20];
        try (FileInputStream in = new FileInputStream(f)) {
            int pos = 0;
            while (pos < h.length) {
                int n = in.read(h, pos, h.length - pos);
                if (n < 0) return false;
                pos += n;
            }
        }
        int actual = (h[18] & 255) | ((h[19] & 255) << 8);
        return (h[0] & 255) == 127 && h[1] == 'E' && h[2] == 'L'
                && h[3] == 'F' && (h[4] & 255) == 2
                && (h[5] & 255) == 1 && actual == machine;
    }

    private boolean begin(String msg) {
        if (busy) return false;
        busy = true;
        status.setText("STATUS: RUNNING");
        output.setText(msg);
        return true;
    }

    private void finish(String report, boolean pass) {
        runOnUiThread(() -> {
            busy = false;
            status.setText(pass ? "STATUS: PASS" : "STATUS: FAILED / SEE LOG");
            output.setText(report);
            new AlertDialog.Builder(this).setTitle("TitanX v" + VERSION)
                    .setMessage(report).setPositiveButton("OK", null).show();
        });
    }

    private String describe(File f, int machine) throws Exception {
        return f.getName() + "\n  path: " + f.getAbsolutePath()
                + "\n  size: " + f.length()
                + "\n  ELF valid: " + elf(f, machine)
                + "\n  readable: " + f.canRead()
                + "\n  executable: " + f.canExecute() + "\n";
    }

    private void inspect() {
        if (!begin("Checking ELF files...")) return;
        new Thread(() -> {
            try {
                StringBuilder s = new StringBuilder("ELF INSPECTION v2.9\n\n");
                s.append(describe(box64(), 183));
                for (String name : FILES) s.append(describe(file(name), 62));
                finish(s.toString(), elf(box64(), 183) && elf(file(FILES[2]), 62));
            } catch (Exception e) { finish("INSPECTION ERROR\n" + e, false); }
        }).start();
    }

    private void install() {
        if (!begin("Installing files...")) return;
        new Thread(() -> {
            try {
                if (!runtimeDir.isDirectory() && !runtimeDir.mkdirs())
                    throw new Exception("Cannot create " + runtimeDir);
                StringBuilder s = new StringBuilder("INSTALL v2.9\n\n");
                for (String name : FILES) {
                    File dest = file(name);
                    File temp = file(name + ".tmp");
                    try (InputStream in = getAssets().open(ASSET_DIR + "/" + name);
                         FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        out.getFD().sync();
                    }
                    if (!elf(temp, 62)) throw new Exception("Invalid ELF asset: " + name);
                    if (!temp.setReadable(true, true)) throw new Exception("Cannot set read permission: " + name);
                    boolean exec = temp.setExecutable(true, true);
                    if (dest.exists() && !dest.delete()) throw new Exception("Cannot replace " + name);
                    if (!temp.renameTo(dest)) throw new Exception("Cannot install " + name);
                    s.append(name).append(": ").append(dest.length())
                            .append(" bytes, chmod +x=").append(exec)
                            .append(", canExecute=").append(dest.canExecute()).append('\n');
                }
                s.append("\nFiles installed. Android may restrict executing files from app data.");
                finish(s.toString(), true);
            } catch (Exception e) { finish("INSTALL FAILED\n" + e, false); }
        }).start();
    }

    private void runTest() {
        if (!begin("Starting Box64...")) return;
        new Thread(() -> {
            Process process = null;
            try {
                File box = box64();
                File guest = file("titanx_glibc_test");
                if (!elf(box, 183)) throw new Exception("Box64 ARM64 ELF missing or invalid");
                for (String name : FILES)
                    if (!elf(file(name), 62)) throw new Exception("Missing or invalid " + name + ". Press INSTALL first.");
                boolean changed = false;
                if (!guest.canExecute()) changed = guest.setExecutable(true, true);
                StringBuilder report = new StringBuilder("TITANX GLIBC TEST v2.9\n");
                report.append("Guest: ").append(guest).append('\n');
                report.append("Guest chmod +x attempted: ").append(changed).append('\n');
                report.append("Guest executable: ").append(guest.canExecute()).append('\n');
                report.append("Box64 executable: ").append(box.canExecute()).append('\n');
                if (!guest.canExecute()) {
                    finish(report + "\nFAIL: guest ELF lacks executable permission. Android app-data execution restrictions may apply.", false);
                    return;
                }
                ProcessBuilder pb = new ProcessBuilder(box.getAbsolutePath(), guest.getAbsolutePath());
                pb.directory(getFilesDir());
                pb.redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.put("BOX64_LOG", "2");
                env.put("BOX64_LD_LIBRARY_PATH", runtimeDir.getAbsolutePath());
                env.put("HOME", getFilesDir().getAbsolutePath());
                env.put("TMPDIR", getCacheDir().getAbsolutePath());
                process = pb.start();
                final Process running = process;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                Thread reader = new Thread(() -> {
                    try (InputStream in = running.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            synchronized (bytes) {
                                int left = 131072 - bytes.size();
                                if (left > 0) bytes.write(buf, 0, Math.min(left, n));
                            }
                        }
                    } catch (Exception ignored) { }
                });
                reader.start();
                boolean finished = process.waitFor(25, TimeUnit.SECONDS);
                int code = -1;
                if (finished) code = process.exitValue();
                else process.destroyForcibly();
                reader.join(3000);
                String logs;
                synchronized (bytes) { logs = new String(bytes.toByteArray(), StandardCharsets.UTF_8); }
                boolean pass = finished && code == 0
                        && logs.contains("TITANX GLIBC TEST")
                        && logs.contains("DYNAMIC LIBRARY TEST PASS");
                report.append("Exit code: ").append(code).append("\nTimeout: ").append(!finished)
                        .append("\nResult: ").append(pass ? "PASS" : "FAIL")
                        .append("\n\nBOX64 OUTPUT:\n").append(logs);
                if (!pass) report.append("\n\nDiagnostic: Check executable permissions, ELF interpreter, and x86-64 library dependencies.");
                finish(report.toString(), pass);
            } catch (Exception e) { finish("GLIBC EXECUTION FAILED\n" + e, false); }
            finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
        }).start();
    }
    }
