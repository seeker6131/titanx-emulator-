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
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** TitanX v3.0: isolate ELF loader, GLIBC and Box64 execution failures. */
public class GlibcTestActivity extends Activity {
    private static final String VERSION = "3.0";
    private static final String ASSETS = "linux_x64";
    private static final String[] GUEST_FILES = {
        "ld-linux-x86-64.so.2", "libc.so.6", "titanx_glibc_test"
    };
    private static final int MAX_OUTPUT = 45000;
    private File runtime;
    private TextView status;
    private TextView output;
    private volatile boolean busy = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        runtime = new File(getFilesDir(), "titanx_glibc_v27");
        LinearLayout root = new LinearLayout(this);
        root.setPadding(24, 32, 24, 24);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0a0f1a);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        label(root, "TITANX / EMULATOR v" + VERSION, 23, 0xff36ceb1);
        label(root, "ELF / GLIBC / BOX64 DIAGNOSTIC\nWine, graphics and GTA V: NOT INTEGRATED", 14, Color.WHITE);
        status = label(root, "STATUS: READY", 16, 0xff36ceb1);
        button(root, "1. INSTALL GLIBC TEST FILES", this::install);
        button(root, "2. INSPECT ELF AND INTERPRETER", this::inspect);
        button(root, "3. RUN GLIBC DIAGNOSTIC v3.0", this::runMatrix);
        button(root, "OPEN BOX64 CPU TEST", () -> startActivity(
                new Intent(this, RuntimeProcessActivity.class)));
        output = label(root, "Ready. Install files, inspect, then run diagnostic.", 13, Color.WHITE);
        output.setTextIsSelectable(true);
    }

    private TextView label(LinearLayout root, String content, int size, int color) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(4, 12, 4, 16);
        root.addView(t);
        return t;
    }

    private void button(LinearLayout root, String title, Runnable task) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        root.addView(b);
        b.setOnClickListener(v -> task.run());
    }

    private File box() {
        return new File(getApplicationInfo().nativeLibraryDir, "libbox64.so");
    }

    private File guest(String name) { return new File(runtime, name); }

    private boolean begin(String text) {
        if (busy) return false;
        busy = true;
        status.setText("STATUS: WORKING");
        output.setText(text);
        return true;
    }

    private void done(String text, boolean ok) {
        runOnUiThread(() -> {
            busy = false;
            status.setText(ok ? "STATUS: PASS" : "STATUS: SEE DIAGNOSTIC");
            output.setText(text);
            new AlertDialog.Builder(this).setTitle("TitanX v" + VERSION)
                    .setMessage(text).setPositiveButton("OK", null).show();
        });
    }

    private static int u16(RandomAccessFile f, long offset) throws Exception {
        f.seek(offset);
        int a = f.readUnsignedByte();
        int b = f.readUnsignedByte();
        return a | (b << 8);
    }

    private static long u32(RandomAccessFile f, long offset) throws Exception {
        f.seek(offset);
        long a = f.readUnsignedByte();
        long b = f.readUnsignedByte();
        long c = f.readUnsignedByte();
        long d = f.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    private static long u64(RandomAccessFile f, long offset) throws Exception {
        long lo = u32(f, offset);
        long hi = u32(f, offset + 4);
        if (hi > 0x7fffffffL) throw new Exception("ELF offset too large");
        return (hi << 32) | lo;
    }

    private String elfInfo(File file, int expectedMachine) throws Exception {
        if (!file.isFile()) throw new Exception("MISSING: " + file);
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            if (f.length() < 64 || f.readUnsignedByte() != 127 || f.readByte() != 'E'
                    || f.readByte() != 'L' || f.readByte() != 'F')
                throw new Exception("Not an ELF: " + file.getName());
            f.seek(4);
            if (f.readUnsignedByte() != 2 || f.readUnsignedByte() != 1)
                throw new Exception("Not ELF64 little-endian: " + file.getName());
            int type = u16(f, 16);
            int machine = u16(f, 18);
            if (machine != expectedMachine)
                throw new Exception("Wrong architecture: " + file.getName() + " machine=" + machine);
            long entry = u64(f, 24);
            long phoff = u64(f, 32);
            int phentsize = u16(f, 54);
            int phnum = u16(f, 56);
            String interpreter = "(none: static ELF or interpreter not present)";
            if (phnum > 0 && phentsize >= 56 && phoff <= f.length()
                    && (long) phnum * phentsize <= f.length() - phoff) {
                for (int i = 0; i < phnum; i++) {
                    long p = phoff + (long) i * phentsize;
                    if (u32(f, p) != 3L) continue; // PT_INTERP
                    long pos = u64(f, p + 8);
                    long size = u64(f, p + 32);
                    if (size <= 0 || size > 4096 || pos > f.length() - size)
                        throw new Exception("Invalid PT_INTERP");
                    byte[] data = new byte[(int) size];
                    f.seek(pos);
                    f.readFully(data);
                    int n = 0;
                    while (n < data.length && data[n] != 0) n++;
                    interpreter = new String(data, 0, n, StandardCharsets.UTF_8);
                    break;
                }
            }
            return file.getName() + "\n  bytes: " + file.length()
                    + "\n  ELF type: " + (type == 3 ? "ET_DYN (PIE/shared)" : type == 2 ? "ET_EXEC" : type)
                    + "\n  machine: " + (machine == 62 ? "x86-64" : "ARM64")
                    + "\n  entry: 0x" + Long.toHexString(entry)
                    + "\n  interpreter: " + interpreter
                    + "\n  readable: " + file.canRead()
                    + "\n  executable bit: " + file.canExecute() + "\n";
        }
    }

    private void requireFiles() throws Exception {
        elfInfo(box(), 183);
        for (String name : GUEST_FILES) elfInfo(guest(name), 62);
    }

    private void install() {
        if (!begin("Installing GLIBC assets...")) return;
        new Thread(() -> {
            try {
                if (!runtime.isDirectory() && !runtime.mkdirs())
                    throw new Exception("Cannot create runtime directory");
                StringBuilder report = new StringBuilder("ASSET INSTALLATION v3.0\n\n");
                for (String name : GUEST_FILES) {
                    File dest = guest(name);
                    File temp = guest(name + ".tmp");
                    try (InputStream in = getAssets().open(ASSETS + "/" + name);
                         FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) != -1) out.write(b, 0, n);
                        out.getFD().sync();
                    }
                    elfInfo(temp, 62);
                    temp.setReadable(true, true);
                    temp.setExecutable(true, true);
                    if (dest.exists() && !dest.delete()) throw new Exception("Cannot replace " + name);
                    if (!temp.renameTo(dest)) throw new Exception("Cannot rename " + name);
                    report.append(name).append(": ").append(dest.length())
                            .append(" bytes, executable=").append(dest.canExecute()).append('\n');
                }
                requireFiles();
                done(report + "\nInstalled. ELF inspection is separate from successful execution.", true);
            } catch (Exception e) { done("INSTALL FAILED\n" + e, false); }
        }).start();
    }

    private void inspect() {
        if (!begin("Inspecting ELF headers...")) return;
        new Thread(() -> {
            try {
                StringBuilder s = new StringBuilder("ELF / PT_INTERP INSPECTION v3.0\n\n");
                s.append(elfInfo(box(), 183));
                for (String name : GUEST_FILES) s.append('\n').append(elfInfo(guest(name), 62));
                s.append("\nNOTE: Ubuntu x86-64 glibc bundled into an Android/Bionic application is not a complete Linux runtime.\n");
                done(s.toString(), true);
            } catch (Exception e) { done("INSPECTION FAILED\n" + e + "\nPress INSTALL first.", false); }
        }).start();
    }

    private static class Run {
        int code = -1;
        boolean timeout;
        String log = "";
        boolean pass;
    }

    private Run execute(String mode, Map<String, String> overrides) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(box().getAbsolutePath(),
                guest("titanx_glibc_test").getAbsolutePath());
        pb.directory(getFilesDir());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("BOX64_LOG", "1");
        env.put("BOX64_LD_LIBRARY_PATH", runtime.getAbsolutePath());
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        // Do not overwrite host LD_LIBRARY_PATH: it can break native Android libraries.
        env.remove("LD_LIBRARY_PATH");
        env.putAll(overrides);
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    synchronized (out) {
                        int left = MAX_OUTPUT - out.size();
                        if (left > 0) out.write(buf, 0, Math.min(left, n));
                    }
                }
            } catch (Exception ignored) { }
        });
        reader.start();
        Run r = new Run();
        try {
            boolean completed = p.waitFor(18, TimeUnit.SECONDS);
            r.timeout = !completed;
            if (completed) r.code = p.exitValue();
            else {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
            reader.join(2000);
            synchronized (out) { r.log = new String(out.toByteArray(), StandardCharsets.UTF_8); }
            r.pass = !r.timeout && r.code == 0
                    && r.log.contains("TITANX GLIBC TEST")
                    && r.log.contains("DYNAMIC LIBRARY TEST PASS");
            return r;
        } finally {
            if (p.isAlive()) p.destroyForcibly();
        }
    }

    private void runMatrix() {
        if (!begin("Testing Box64 GLIBC modes...")) return;
        new Thread(() -> {
            try {
                requireFiles();
                StringBuilder s = new StringBuilder("TITANX GLIBC DIAGNOSTIC v3.0\n\n");
                s.append("Guest interpreter:\n")
                        .append(elfInfo(guest("titanx_glibc_test"), 62))
                        .append("\n");
                Map<String, String> emulated = new LinkedHashMap<>();
                emulated.put("BOX64_PREFER_EMULATED", "1");
                emulated.put("BOX64_EMULATED_LIBS", "libc.so.6");
                emulated.put("BOX64_DYNAREC", "0");
                Run first = execute("interpreter", emulated);
                s.append("MODE A: emulated libc + interpreter (DYNAREC=0)\n")
                        .append("Exit code: ").append(first.code)
                        .append(" / Timeout: ").append(first.timeout)
                        .append(" / ").append(first.pass ? "PASS" : "FAIL")
                        .append("\n").append(first.log).append("\n\n");
                if (first.pass) {
                    done(s + "GLIBC TEST PASS (mode A)", true);
                    return;
                }
                Map<String, String> nativeMode = new LinkedHashMap<>();
                nativeMode.put("BOX64_DYNAREC", "0");
                Run second = execute("interpreter", nativeMode);
                s.append("MODE B: default library selection (DYNAREC=0)\n")
                        .append("Exit code: ").append(second.code)
                        .append(" / Timeout: ").append(second.timeout)
                        .append(" / ").append(second.pass ? "PASS" : "FAIL")
                        .append("\n").append(second.log).append("\n\n");
                if (second.pass) {
                    done(s + "GLIBC TEST PASS (mode B)", true);
                } else {
                    s.append("DIAGNOSIS: Both modes failed. This is not a chmod issue. ")
                            .append("A compatible Linux/glibc execution environment or more extensive Box64 integration is needed. ")
                            .append("The CPU-only Box64 test does not establish glibc or Windows-game compatibility.");
                    done(s.toString(), false);
                }
            } catch (Exception e) { done("GLIBC DIAGNOSTIC ERROR\n" + e, false); }
        }).start();
    }
}
