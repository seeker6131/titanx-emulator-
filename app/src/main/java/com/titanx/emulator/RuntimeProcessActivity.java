package com.titanx.emulator;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class RuntimeProcessActivity extends Activity {

    private static final int BG = 0xff0a0f1a;
    private static final int PANEL = 0xff182131;
    private static final int GREEN = 0xff36ceb1;
    private static final int MUTED = 0xffa5b4c8;

    private LinearLayout content;
    private TextView status;
    private TextView output;
    private boolean running = false;

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

    private TextView label(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(Typeface.DEFAULT);
        t.setPadding(0, dp(5), 0, dp(7));
        return t;
    }

    private void add(LinearLayout parent, TextView view) {
        parent.addView(view);
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

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

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

        add(content, label(
            "TITANX / EMULATOR",
            24, Color.WHITE));

        add(content, label(
            "v2.1 • Native Process Foundation",
            13, GREEN));

        LinearLayout device = card();

        add(device, label(
            "DEVICE: " + Build.MODEL,
            15, Color.WHITE));

        add(device, label(
            "ABI: " + Build.SUPPORTED_ABIS[0],
            14, MUTED));

        add(device, label(
            "ANDROID: " + Build.VERSION.RELEASE,
            14, MUTED));

        LinearLayout engine = card();

        add(engine, label(
            "PROCESS ENGINE",
            19, Color.WHITE));

        status = label(
            "STATUS: NOT TESTED",
            15, GREEN);
        add(engine, status);

        button(engine,
            "RUN NATIVE PROCESS TEST",
            () -> runCommand(
                "NATIVE PROCESS",
                new String[]{"/system/bin/id"}));

        button(engine,
            "CHECK ANDROID ENVIRONMENT",
            () -> runCommand(
                "ANDROID ENVIRONMENT",
                new String[]{
                    "/system/bin/getprop",
                    "ro.product.cpu.abi"
                }));

        button(engine,
            "TEST JNI ENGINE",
            () -> showDialog(
                "JNI ENGINE",
                NativeBridge.report()));

        LinearLayout runtime = card();

        add(runtime, label(
            "GTA V RUNTIME STATUS",
            19, Color.WHITE));

        add(runtime, label(
            "Android process launcher: TEST AVAILABLE",
            14, Color.WHITE));

        add(runtime, label(
            "Box64: NOT INTEGRATED",
            14, MUTED));

        add(runtime, label(
            "Wine: NOT INTEGRATED",
            14, MUTED));

        add(runtime, label(
            "Turnip / DXVK: NOT INTEGRATED",
            14, MUTED));

        add(runtime, label(
            "X11 game display: NOT INTEGRATED",
            14, MUTED));

        add(runtime, label(
            "GTA V execution: NOT AVAILABLE",
            14, MUTED));

        LinearLayout logs = card();

        add(logs, label(
            "PROCESS OUTPUT",
            19, Color.WHITE));

        output = label(
            "Run a process test to view output.",
            13, Color.WHITE);
        output.setTextIsSelectable(true);
        add(logs, output);

        LinearLayout previous = card();

        button(previous,
            "OPEN TITANX v1.9 TOOLS",
            () -> startActivity(
                new android.content.Intent(
                    this, MainActivity.class)));

        add(previous, label(
            "The existing PE and DLL inspectors remain available.",
            13, MUTED));
    }

    private void runCommand(
        String title,
        String[] command
    ) {
        if (running) return;

        running = true;
        status.setText("STATUS: RUNNING");
        output.setText("Starting " + title + "...");

        new Thread(() -> {
            String result;
            Process process = null;

            try {
                ProcessBuilder builder =
                    new ProcessBuilder(command);

                builder.redirectErrorStream(true);
                builder.directory(getFilesDir());

                process = builder.start();

                boolean finished =
                    process.waitFor(
                        8, TimeUnit.SECONDS);

                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor(
                        2, TimeUnit.SECONDS);

                    result =
                        "PROCESS TIMEOUT\n"
                        + "Command: " + command[0]
                        + "\nLimit: 8 seconds";
                } else {
                    int exitCode = process.exitValue();

                    ByteArrayOutputStream bytes =
                        new ByteArrayOutputStream();

                    try (InputStream in =
                             process.getInputStream()) {

                        byte[] buffer = new byte[4096];
                        int n;

                        while ((n = in.read(buffer)) != -1) {
                            if (bytes.size() + n > 65536) {
                                break;
                            }
                            bytes.write(buffer, 0, n);
                        }
                    }

                    String stdout = new String(
                        bytes.toByteArray(),
                        StandardCharsets.UTF_8);

                    result =
                        "COMMAND: " + command[0]
                        + "\nEXIT CODE: " + exitCode
                        + "\nRESULT: "
                        + (exitCode == 0
                            ? "PASS" : "FAIL")
                        + "\n\nOUTPUT:\n"
                        + stdout;
                }

            } catch (Exception e) {
                result = "PROCESS ERROR:\n"
                    + e.toString();

            } finally {
                if (process != null
                    && process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            final String report = result;

            runOnUiThread(() -> {
                running = false;

                boolean passed =
                    report.contains("EXIT CODE: 0");

                status.setText(
                    passed
                    ? "STATUS: PROCESS PASS"
                    : "STATUS: PROCESS FAILED");

                output.setText(report);
            });

        }).start();
    }

    private void showDialog(
        String title, String message
    ) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }
}
