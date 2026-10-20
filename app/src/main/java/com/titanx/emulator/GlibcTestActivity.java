
package com.titanx.emulator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

    private static final int BG = 0xff0a0f1a;
    private static final int PANEL = 0xff182131;
    private static final int GREEN = 0xff36ceb1;

    private static final String ASSET_DIR = "linux_x64";

    private static final String[] FILES = {
        "ld-linux-x86-64.so.2",
        "libc.so.6",
        "titanx_glibc_test"
    };

    private TextView status;
    private TextView log;
    private File runtimeDir;
    private volatile boolean busy;

    private int dp(int n) {
        return (int) (
            n * getResources()
                .getDisplayMetrics().density
        );
    }

    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(16));
        return d;
    }

    private TextView text(
        String value,
        int size,
        int color
    ) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(7), 0, dp(9));
        return t;
    }

    private void button(
        LinearLayout parent,
        String name,
        Runnable action
    ) {
        Button b = new Button(this);

        b.setText(name);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(background(GREEN));

        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(
                -1,
                dp(58)
            );

        p.bottomMargin = dp(12);
        parent.addView(b, p);

        b.setOnClickListener(v -> action.run());
    }

    private LinearLayout card(
        LinearLayout parent
    ) {
        LinearLayout c = new LinearLayout(this);

        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(
            dp(18),
            dp(18),
            dp(18),
            dp(18)
        );

        c.setBackground(background(PANEL));

        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(
                -1,
                -2
            );

        p.bottomMargin = dp(16);
        parent.addView(c, p);

        return c;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        // Reuse the files installed by v2.7.
        runtimeDir = new File(
            getFilesDir(),
            "titanx_glibc_v27"
        );

        LinearLayout root =
            new LinearLayout(this);

        root.setOrientation(
            LinearLayout.VERTICAL
        );

        root.setPadding(
            dp(16),
            dp(22),
            dp(16),
            dp(20)
        );

        root.setBackgroundColor(BG);

        ScrollView scroll =
            new ScrollView(this);

        scroll.addView(root);
        setContentView(scroll);

        root.addView(text(
            "TITANX / EMULATOR",
            25,
            Color.WHITE
        ));

        root.addView(text(
            "v2.8 - Box64 GLIBC Execution Fix",
            14,
            GREEN
        ));

        LinearLayout info = card(root);

        info.addView(text(
            "BOX64 + GLIBC RUNTIME",
            18,
            Color.WHITE
        ));

        info.addView(text(
            "Box64: embedded ARM64\n" +
            "GLIBC: x86-64 runtime\n" +
            "Execution: direct test binary\n" +
            "Wine: not integrated\n" +
            "GTA V: not available",
            14,
            Color.LTGRAY
        ));

        status = text(
            "STATUS: READY",
            16,
            GREEN
        );

        info.addView(status);

        button(
            info,
            "CHECK GLIBC ASSETS",
            this::inspectAssets
        );

        button(
            info,
            "INSTALL GLIBC TEST FILES",
            this::installAssets
        );

        button(
            info,
            "RUN GLIBC TEST v2.8",
            this::runGlibcTest
        );

        button(
            info,
            "OPEN BOX64 CPU TEST",
            () -> startActivity(
                new Intent(
                    this,
                    RuntimeProcessActivity.class
                )
            )
        );

        LinearLayout outputCard = card(root);

        outputCard.addView(text(
            "RUNTIME OUTPUT",
            18,
            Color.WHITE
        ));

        log = text(
            "Ready. Run GLIBC test.",
            13,
            Color.WHITE
        );

        log.setTextIsSelectable(true);
        outputCard.addView(log);

        outputCard.addView(text(
            "Runtime directory:\n" +
            runtimeDir.getAbsolutePath(),
            12,
            Color.LTGRAY
        ));
    }

    private File box64() {
        return new File(
            getApplicationInfo().nativeLibraryDir,
            "libbox64.so"
        );
    }

    private File installed(String name) {
        return new File(runtimeDir, name);
    }

    private boolean validElf(
        File file,
        int machine
    ) throws Exception {

        if (!file.isFile()) {
            return false;
        }

        byte[] h = new byte[20];

        try (
            FileInputStream in =
                new FileInputStream(file)
        ) {
            int offset = 0;

            while (offset < h.length) {
                int n = in.read(
                    h,
                    offset,
                    h.length - offset
                );

                if (n < 0) {
                    return false;
                }

                offset += n;
            }
        }

        int actual =
            (h[18] & 255) |
            ((h[19] & 255) << 8);

        return h[0] == 127
            && h[1] == 'E'
            && h[2] == 'L'
            && h[3] == 'F'
            && (h[4] & 255) == 2
            && (h[5] & 255) == 1
            && actual == machine;
    }

    private void result(
        String title,
        String message,
        boolean pass
    ) {
        runOnUiThread(() -> {
            busy = false;

            status.setText(
                pass
                    ? "STATUS: PASS"
                    : "STATUS: FAILED / CHECK LOG"
            );

            String report =
                title + "\n\n" + message;

            log.setText(report);

            new AlertDialog.Builder(this)
                .setTitle("TitanX v2.8")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show();
        });
    }

    private boolean begin(String message) {
        if (busy) {
            return false;
        }

        busy = true;

        status.setText("STATUS: WORKING");
        log.setText(message);

        return true;
    }

    private void inspectAssets() {
        if (!begin("Inspecting assets...")) {
            return;
        }

        new Thread(() -> {
            StringBuilder report =
                new StringBuilder();

            boolean pass = true;

            try {
                AssetManager assets = getAssets();

                for (String name : FILES) {
                    long count = 0;

                    try (
                        InputStream in =
                            assets.open(
                                ASSET_DIR + "/" + name
                            )
                    ) {
                        byte[] buffer =
                            new byte[8192];

                        int n;

                        while (
                            (n = in.read(buffer)) != -1
                        ) {
                            count += n;
                        }
                    }

                    report.append(name)
                        .append(": ")
                        .append(count)
                        .append(" bytes\n");

                    if (count == 0) {
                        pass = false;
                    }
                }

                File box = box64();

                report.append(
                    "\nBox64 exists: "
                ).append(box.isFile());

                boolean boxValid =
                    validElf(box, 183);

                report.append(
                    "\nBox64 ARM64 ELF: "
                ).append(boxValid);

                if (!boxValid) {
                    pass = false;
                }

            } catch (Exception e) {
                pass = false;

                report.append("\nERROR:\n")
                    .append(e);
            }

            result(
                "GLIBC ASSET INSPECTION",
                report.toString(),
                pass
            );
        }).start();
    }

    private void installAssets() {
        if (!begin("Installing GLIBC files...")) {
            return;
        }

        new Thread(() -> {
            StringBuilder report =
                new StringBuilder();

            boolean pass = true;

            try {
                if (
                    !runtimeDir.exists()
                    && !runtimeDir.mkdirs()
                ) {
                    throw new Exception(
                        "Cannot create runtime directory"
                    );
                }

                for (String name : FILES) {
                    File destination =
                        installed(name);

                    File temp = new File(
                        runtimeDir,
                        name + ".tmp"
                    );

                    try (
                        InputStream in =
                            getAssets().open(
                                ASSET_DIR + "/" + name
                            );

                        FileOutputStream out =
                            new FileOutputStream(temp)
                    ) {
                        byte[] buffer =
                            new byte[8192];

                        int n;

                        while (
                            (n = in.read(buffer)) != -1
                        ) {
                            out.write(
                                buffer,
                                0,
                                n
                            );
                        }

                        out.getFD().sync();
                    }

                    if (
                        destination.exists()
                        && !destination.delete()
                    ) {
                        throw new Exception(
                            "Cannot replace " + name
                        );
                    }

                    if (
                        !temp.renameTo(destination)
                    ) {
                        throw new Exception(
                            "Cannot install " + name
                        );
                    }

                    report.append(name)
                        .append(": ")
                        .append(
                            destination.length()
                        )
                        .append(" bytes\n");
                }

                for (String name : FILES) {
                    if (
                        !validElf(
                            installed(name),
                            62
                        )
                    ) {
                        throw new Exception(
                            "Invalid x86-64 ELF: "
                                + name
                        );
                    }
                }

                report.append(
                    "\nAll files valid x86-64 ELF."
                );

                report.append(
                    "\nInstallation complete."
                );

            } catch (Exception e) {
                pass = false;

                report.append("\nERROR:\n")
                    .append(e);
            }

            result(
                "GLIBC INSTALLATION",
                report.toString(),
                pass
            );
        }).start();
    }

    private void runGlibcTest() {
        if (!begin("Running GLIBC test v2.8...")) {
            return;
        }

        new Thread(() -> {
            StringBuilder report =
                new StringBuilder();

            boolean pass = false;
            Process process = null;

            try {
                File box = box64();

                File loader = installed(
                    "ld-linux-x86-64.so.2"
                );

                File libc = installed(
                    "libc.so.6"
                );

                File test = installed(
                    "titanx_glibc_test"
                );

                if (!validElf(box, 183)) {
                    throw new Exception(
                        "Box64 ARM64 ELF missing or invalid"
                    );
                }

                if (
                    !validElf(loader, 62)
                    || !validElf(libc, 62)
                    || !validElf(test, 62)
                ) {
                    throw new Exception(
                        "GLIBC files missing. " +
                        "Press INSTALL GLIBC TEST FILES."
                    );
                }

                /*
                 * v2.8 FIX:
                 *
                 * Execute the x86-64 test program
                 * directly through Box64.
                 *
                 * Do not pass ld-linux-x86-64.so.2
                 * as the primary executable.
                 */

                ProcessBuilder pb =
                    new ProcessBuilder(
                        box.getAbsolutePath(),
                        test.getAbsolutePath()
                    );

                pb.directory(getFilesDir());
                pb.redirectErrorStream(true);

                Map<String, String> env =
                    pb.environment();

                env.put("BOX64_LOG", "1");

                env.put(
                    "BOX64_LD_LIBRARY_PATH",
                    runtimeDir.getAbsolutePath()
                );

                env.put(
                    "LD_LIBRARY_PATH",
                    runtimeDir.getAbsolutePath()
                );

                env.put(
                    "HOME",
                    getFilesDir().getAbsolutePath()
                );

                env.put(
                    "TMPDIR",
                    getCacheDir().getAbsolutePath()
                );

                report.append("BOX64:\n")
                    .append(
                        box.getAbsolutePath()
                    );

                report.append("\n\nLOADER:\n")
                    .append(
                        loader.getAbsolutePath()
                    );

                report.append("\n\nLIBC:\n")
                    .append(
                        libc.getAbsolutePath()
                    );

                report.append("\n\nTEST:\n")
                    .append(
                        test.getAbsolutePath()
                    );

                report.append(
                    "\n\nCOMMAND MODE: DIRECT X86-64"
                );

                process = pb.start();

                final Process running = process;

                ByteArrayOutputStream bytes =
                    new ByteArrayOutputStream();

                Thread reader = new Thread(() -> {
                    try (
                        InputStream in =
                            running.getInputStream()
                    ) {
                        byte[] buffer =
                            new byte[4096];

                        int n;

                        while (
                            (n = in.read(buffer)) != -1
                        ) {
                            synchronized (bytes) {
                                int remaining =
                                    131072
                                    - bytes.size();

                                if (remaining > 0) {
                                    bytes.write(
                                        buffer,
                                        0,
                                        Math.min(
                                            n,
                                            remaining
                                        )
                                    );
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });

                reader.start();

                boolean finished =
                    process.waitFor(
                        25,
                        TimeUnit.SECONDS
                    );

                int exitCode = -1;

                if (finished) {
                    exitCode =
                        process.exitValue();
                } else {
                    process.destroyForcibly();

                    process.waitFor(
                        3,
                        TimeUnit.SECONDS
                    );
                }

                reader.join(3000);

                String output;

                synchronized (bytes) {
                    output = new String(
                        bytes.toByteArray(),
                        StandardCharsets.UTF_8
                    );
                }

                pass =
                    finished
                    && exitCode == 0
                    && output.contains(
                        "TITANX GLIBC TEST"
                    )
                    && output.contains(
                        "DYNAMIC LIBRARY TEST PASS"
                    );

                report.append(
                    "\n\nEXIT CODE: "
                ).append(exitCode);

                report.append(
                    "\nTIMEOUT: "
                ).append(!finished);

                report.append(
                    "\nGLIBC RESULT: "
                ).append(
                    pass ? "PASS" : "FAIL"
                );

                report.append(
                    "\n\nOUTPUT:\n"
                ).append(output);

                if (!pass) {
                    report.append(
                        "\n\nDIAGNOSTIC:\n"
                    );

                    if (!finished) {
                        report.append(
                            "Process timed out."
                        );
                    } else if (
                        output.contains(
                            "not found"
                        ) || output.contains(
                            "No such file"
                        )
                    ) {
                        report.append(
                            "A runtime dependency " +
                            "or loader may be missing."
                        );
                    } else if (
                        output.contains(
                            "Error loading"
                        )
                    ) {
                        report.append(
                            "Box64 could not load " +
                            "the x86-64 program."
                        );
                    } else {
                        report.append(
                            "Check Box64 output above."
                        );
                    }
                }

            } catch (Exception e) {
                report.append(
                    "\n\nERROR:\n"
                ).append(e);

            } finally {
                if (
                    process != null
                    && process.isAlive()
                ) {
                    process.destroyForcibly();
                }
            }

            result(
                "TITANX GLIBC TEST v2.8",
                report.toString(),
                pass
            );
        }).start();
    }
}
