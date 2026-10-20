
package com.titanx.emulator;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final int PICK_ZIP = 101;
    private static final long MAX_ZIP_BYTES =
        1024L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 10000;

    private final int BG = Color.rgb(10, 15, 26);
    private final int PANEL = Color.rgb(24, 33, 49);
    private final int ACCENT = Color.rgb(55, 205, 175);
    private final int WHITE = Color.WHITE;
    private final int MUTED = Color.rgb(165, 180, 200);

    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout navigation;
    private TextView headerStatus;

    private SharedPreferences prefs;
    private File runtimeDir;

    private final StringBuilder logs = new StringBuilder();

    private volatile boolean busy = false;
    private String currentPage = "Dashboard";

    private int dp(int n) {
        return (int) (n *
            getResources().getDisplayMetrics().density);
    }

    private GradientDrawable background(
        int color, int radius
    ) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView text(
        String value, int size, int color
    ) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(true);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private void margin(View v, int bottom) {
        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(
                -1, -2
            );
        p.bottomMargin = dp(bottom);
        v.setLayoutParams(p);
    }

    private TextView heading(String value) {
        TextView t = text(value, 19, WHITE);
        t.setTypeface(null, Typeface.BOLD);
        margin(t, 12);
        return t;
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(
            dp(16), dp(16), dp(16), dp(16)
        );
        p.setBackground(background(PANEL, 15));
        margin(p, 14);
        content.addView(p);
        return p;
    }

    private void line(
        LinearLayout parent,
        String label,
        String value
    ) {
        TextView a = text(label, 12, MUTED);
        parent.addView(a);

        TextView b = text(value, 15, WHITE);
        b.setPadding(0, dp(3), 0, dp(12));
        parent.addView(b);
    }

    private Button button(
        LinearLayout parent,
        String title,
        Runnable action
    ) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextColor(BG);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(background(ACCENT, 12));
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        margin(b, 10);
        parent.addView(b);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void info(
        LinearLayout parent,
        String message
    ) {
        TextView t = text(message, 13, MUTED);
        t.setPadding(0, dp(5), 0, dp(10));
        parent.addView(t);
    }

    private String gb(long n) {
        return String.format(
            Locale.US, "%.2f GB",
            n / 1073741824.0
        );
    }

    private void log(String s) {
        String time = new SimpleDateFormat(
            "HH:mm:ss", Locale.US
        ).format(new Date());

        logs.append(time).append(" ")
            .append(s).append("\n");

        if (logs.length() > 16000) {
            logs.delete(0, logs.length() - 12000);
        }
    }

    private void copy(String value) {
        ClipboardManager c =
            (ClipboardManager)
            getSystemService(CLIPBOARD_SERVICE);

        c.setPrimaryClip(
            ClipData.newPlainText("TitanX", value)
        );

        Toast.makeText(
            this, "Copied",
            Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        prefs = getSharedPreferences(
            "titanx", MODE_PRIVATE
        );

        runtimeDir = new File(
            getFilesDir(), "runtime"
        );

        log("TitanX v0.4 started");
        log("Windows execution engine not installed");

        createLayout();
        showPage("Dashboard");
    }

    private void createLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(
            dp(18), dp(18), dp(18), dp(14)
        );

        TextView title = text(
            "TITANX  /  EMULATOR",
            22, WHITE
        );
        title.setTypeface(null, Typeface.BOLD);
        top.addView(title);

        headerStatus = text(
            "v0.4  •  ARM64  •  Runtime offline",
            12, ACCENT
        );
        top.addView(headerStatus);
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            dp(16), dp(12), dp(16), dp(20)
        );

        scroll.addView(content);

        root.addView(
            scroll,
            new LinearLayout.LayoutParams(
                -1, 0, 1f
            )
        );

        navigation = new LinearLayout(this);
        navigation.setOrientation(
            LinearLayout.HORIZONTAL
        );
        navigation.setPadding(
            dp(6), dp(8), dp(6), dp(8)
        );
        navigation.setBackgroundColor(PANEL);

        root.addView(navigation);
        setContentView(root);
    }

    private void showPage(String name) {
        currentPage = name;
        content.removeAllViews();
        navigation.removeAllViews();

        String[] pages = {
            "Dashboard", "Runtime",
            "Games", "Settings"
        };

        for (String page : pages) {
            TextView tab = text(
                page, 12,
                page.equals(name) ? ACCENT : MUTED
            );
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(
                dp(2), dp(14), dp(2), dp(14)
            );
            if (page.equals(name)) {
                tab.setTypeface(null, Typeface.BOLD);
            }
            navigation.addView(
                tab,
                new LinearLayout.LayoutParams(
                    0, -2, 1f
                )
            );
            tab.setOnClickListener(v -> showPage(page));
        }

        updateHeader();

        if (name.equals("Dashboard")) {
            dashboard();
        } else if (name.equals("Runtime")) {
            runtimePage();
        } else if (name.equals("Games")) {
            gamesPage();
        } else {
            settingsPage();
        }
    }

    private void updateHeader() {
        headerStatus.setText(
            "v0.4  •  "
            + Build.SUPPORTED_ABIS[0]
            + "  •  "
            + (runtimeDir.isDirectory()
                ? "Files imported"
                : "Runtime offline")
        );
    }

    private void dashboard() {
        content.addView(heading("DEVICE DASHBOARD"));

        ActivityManager am =
            (ActivityManager)
            getSystemService(
                Context.ACTIVITY_SERVICE
            );

        ActivityManager.MemoryInfo mem =
            new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);

        StatFs fs = new StatFs(
            Environment.getDataDirectory()
                .getAbsolutePath()
        );

        boolean vulkan =
            getPackageManager().hasSystemFeature(
                PackageManager
                    .FEATURE_VULKAN_HARDWARE_VERSION
            );

        LinearLayout device = panel();

        line(device, "DEVICE",
            Build.MANUFACTURER + " " + Build.MODEL);

        line(device, "ANDROID",
            Build.VERSION.RELEASE
            + " / API " + Build.VERSION.SDK_INT);

        line(device, "CPU ARCHITECTURE",
            Arrays.toString(Build.SUPPORTED_ABIS));

        line(device, "RAM",
            gb(mem.availMem) + " available / "
            + gb(mem.totalMem) + " total");

        line(device, "STORAGE",
            gb(fs.getAvailableBytes()) + " free");

        line(device, "VULKAN",
            vulkan
                ? "Advertised by Android"
                : "Not advertised");

        content.addView(heading("ENGINE STATUS"));

        LinearLayout engine = panel();

        line(engine, "WINE", "Not integrated");
        line(engine, "FEX / BOX64", "Not integrated");
        line(engine, "DXVK / TURNIP", "Not integrated");
        line(engine, "GAME LAUNCHER", "Not available");

        button(engine, "OPEN RUNTIME MANAGER",
            () -> showPage("Runtime"));

        info(content,
            "TitanX currently manages runtime files "
            + "and device diagnostics. It cannot "
            + "execute Windows programs yet.");
    }

    private long[] scan(File dir) {
        long files = 0;
        long bytes = 0;

        File[] children = dir.listFiles();
        if (children == null) {
            return new long[]{0, 0};
        }

        for (File f : children) {
            if (f.isDirectory()) {
                long[] s = scan(f);
                files += s[0];
                bytes += s[1];
            } else {
                files++;
                bytes += f.length();
            }
        }

        return new long[]{files, bytes};
    }

    private void runtimePage() {
        content.addView(heading("RUNTIME MANAGER"));

        LinearLayout p = panel();

        if (runtimeDir.isDirectory()) {
            long[] s = scan(runtimeDir);

            line(p, "FILES IMPORTED",
                String.valueOf(s[0]));
            line(p, "TOTAL SIZE", gb(s[1]));
            line(p, "VALIDATION",
                "ZIP extraction completed");
            line(p, "EXECUTION",
                "Not integrated");

            info(p,
                "Imported files are not proof that "
                + "Wine or FEX is compatible.");
        } else {
            line(p, "STATUS", "No runtime imported");
        }

        button(p, "IMPORT RUNTIME ZIP",
            this::pickZip);

        button(p, "REFRESH STATUS",
            () -> showPage("Runtime"));

        button(p, "REMOVE RUNTIME",
            this::confirmRemove);

        LinearLayout validation = panel();
        line(validation, "ZIP SECURITY",
            "Path traversal protection enabled");
        line(validation, "EXTRACTED DATA LIMIT",
            "1 GB");
        line(validation, "ENTRY LIMIT",
            "10,000");
        line(validation, "EXECUTABLE VALIDATION",
            "Not implemented");

        info(content,
            "Import ZIP files only from sources "
            + "you trust. No Windows executable "
            + "will be launched by this version.");
    }

    private void gamesPage() {
        content.addView(heading("GAME LIBRARY"));

        LinearLayout p = panel();

        line(p, "LIBRARY STATUS",
            "Windows game launching unavailable");

        info(p,
            "This page is reserved for a future "
            + "game library. Runtime execution, "
            + "Windows filesystem integration, "
            + "and graphics translation must "
            + "be implemented first.");

        line(p, "ENGINE",
            "Not integrated");

        line(p, "COMPATIBILITY",
            "Not tested");
    }

    private void settingsPage() {
        content.addView(heading("SETTINGS"));

        LinearLayout p = panel();

        line(p, "PERFORMANCE PROFILE",
            "Stored preference only");

        String[] profiles = {
            "Balanced",
            "Low RAM 8GB",
            "Performance"
        };

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter =
            new ArrayAdapter<>(
                this,
                android.R.layout
                    .simple_spinner_dropdown_item,
                profiles
            );
        spinner.setAdapter(adapter);
        spinner.setSelection(
            Math.max(0, Math.min(
                2, prefs.getInt("profile", 1)
            ))
        );
        p.addView(spinner);

        button(p, "SAVE PROFILE", () -> {
            int selected =
                spinner.getSelectedItemPosition();

            prefs.edit()
                .putInt("profile", selected)
                .apply();

            log("Profile: " + profiles[selected]);

            Toast.makeText(
                this, "Profile saved",
                Toast.LENGTH_SHORT
            ).show();
        });

        info(p,
            "Profiles do not change CPU, GPU, "
            + "RAM limits, or runtime settings yet.");

        content.addView(heading("SESSION LOG"));

        LinearLayout logPanel = panel();
        TextView logText = text(
            logs.toString(), 12, WHITE
        );
        logPanel.addView(logText);

        button(logPanel, "COPY LOG",
            () -> copy(logs.toString()));

        button(logPanel, "CLEAR LOG", () -> {
            logs.setLength(0);
            log("Log cleared");
            showPage("Settings");
        });

        button(logPanel, "COPY DIAGNOSTICS", () -> {
            String d = Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE
                + "\nABIs: "
                + Arrays.toString(Build.SUPPORTED_ABIS);
            copy(d);
        });
    }

    private void pickZip() {
        if (busy) {
            Toast.makeText(
                this, "Operation in progress",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Intent i = new Intent(
            Intent.ACTION_OPEN_DOCUMENT
        );
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        );
        startActivityForResult(i, PICK_ZIP);
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {
        super.onActivityResult(
            requestCode, resultCode, data
        );

        if (requestCode != PICK_ZIP
            || resultCode != RESULT_OK
            || data == null
            || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        new AlertDialog.Builder(this)
            .setTitle("Import Runtime ZIP")
            .setMessage(
                "Extract the selected ZIP into "
                + "TitanX private storage?\n\n"
                + "Existing files will be replaced "
                + "only after extraction succeeds."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("IMPORT",
                (d, w) -> importZip(uri))
            .show();
    }

    private void importZip(Uri uri) {
        if (busy) return;
        busy = true;
        log("Import started");

        new Thread(() -> {
            File stage = new File(
                getFilesDir(), "runtime_staging"
            );
            File backup = new File(
                getFilesDir(), "runtime_backup"
            );

            String result;

            try {
                if (!deleteTree(stage)
                    || !deleteTree(backup)
                    || !stage.mkdirs()) {
                    throw new IOException(
                        "Cannot prepare directories"
                    );
                }

                extractZip(uri, stage);

                boolean hadOld = runtimeDir.exists();

                if (hadOld
                    && !runtimeDir.renameTo(backup)) {
                    throw new IOException(
                        "Cannot back up old runtime"
                    );
                }

                if (!stage.renameTo(runtimeDir)) {
                    if (hadOld) {
                        backup.renameTo(runtimeDir);
                    }
                    throw new IOException(
                        "Cannot activate imported files"
                    );
                }

                deleteTree(backup);
                result = "Runtime files imported";

            } catch (Exception e) {
                deleteTree(stage);
                result = "Import failed: "
                    + e.getMessage();
            }

            final String message = result;

            runOnUiThread(() -> {
                busy = false;
                log(message);
                Toast.makeText(
                    this, message,
                    Toast.LENGTH_LONG
                ).show();
                showPage("Runtime");
            });
        }).start();
    }

    private void extractZip(
        Uri uri, File destination
    ) throws IOException {

        String base =
            destination.getCanonicalPath()
            + File.separator;

        int count = 0;
        long total = 0;

        InputStream source =
            getContentResolver().openInputStream(uri);

        if (source == null) {
            throw new IOException(
                "Cannot open selected file"
            );
        }

        try (
            InputStream input = source;
            ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(input)
            )
        ) {
            ZipEntry entry;
            byte[] buffer = new byte[32768];

            while ((entry = zip.getNextEntry())
                    != null) {
                count++;

                if (count > MAX_ENTRIES) {
                    throw new IOException(
                        "Too many ZIP entries"
                    );
                }

                String name = entry.getName();

                if (name.isEmpty()
                    || name.startsWith("/")
                    || name.startsWith("\\")
                    || name.contains("\\")
                    || name.indexOf('\0') >= 0) {
                    throw new IOException(
                        "Unsafe ZIP entry"
                    );
                }

                File target = new File(
                    destination, name
                );

                String path = target.getCanonicalPath();

                if (!path.startsWith(base)) {
                    throw new IOException(
                        "ZIP path traversal blocked"
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
                    File parent = target.getParentFile();

                    if (!parent.isDirectory()
                        && !parent.mkdirs()) {
                        throw new IOException(
                            "Cannot create parent"
                        );
                    }

                    if (target.exists()) {
                        throw new IOException(
                            "Duplicate ZIP path"
                        );
                    }

                    try (OutputStream out =
                        new BufferedOutputStream(
                            new FileOutputStream(target)
                        )) {
                        int n;

                        while ((n = zip.read(buffer)) != -1) {
                            total += n;

                            if (total > MAX_ZIP_BYTES) {
                                throw new IOException(
                                    "ZIP exceeds 1 GB"
                                );
                            }

                            out.write(buffer, 0, n);
                        }
                    }
                }

                zip.closeEntry();
            }
        }

        if (count == 0) {
            throw new IOException(
                "Empty or invalid ZIP"
            );
        }
    }

    private boolean deleteTree(File file) {
        if (!file.exists()) return true;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;

            for (File child : children) {
                if (!deleteTree(child)) {
                    return false;
                }
            }
        }

        return file.delete();
    }

    private void confirmRemove() {
        if (busy) {
            Toast.makeText(
                this, "Operation in progress",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Remove Runtime")
            .setMessage(
                "Delete imported runtime files?"
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE", (d, w) -> {
                busy = true;

                new Thread(() -> {
                    boolean ok = deleteTree(runtimeDir);

                    runOnUiThread(() -> {
                        busy = false;
                        log(ok
                            ? "Runtime removed"
                            : "Runtime removal failed");
                        showPage("Runtime");
                    });
                }).start();
            })
            .show();
    }
}
