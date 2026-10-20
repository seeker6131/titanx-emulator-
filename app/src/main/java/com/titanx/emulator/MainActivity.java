
package com.titanx.emulator;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

public class MainActivity extends Activity {

    private static final int PICK_ZIP = 101;
    private static final long MAX_ZIP = 1073741824L;
    private static final int MAX_ENTRIES = 10000;

    private final int BG = Color.rgb(10, 15, 26);
    private final int PANEL = Color.rgb(24, 33, 49);
    private final int ACCENT = Color.rgb(55, 205, 175);
    private final int WHITE = Color.WHITE;
    private final int MUTED = Color.rgb(165, 180, 200);

    private LinearLayout root, content, nav;
    private TextView subtitle;
    private File runtime;
    private SharedPreferences prefs;
    private volatile boolean busy;
    private final StringBuilder logs = new StringBuilder();

    private int dp(int v) {
        return (int) (
            v * getResources().getDisplayMetrics().density
        );
    }

    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }

    private TextView text(
        String value, int size, int color
    ) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private void add(
        LinearLayout parent, View view, int gap
    ) {
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(-1, -2);

        lp.bottomMargin = dp(gap);
        parent.addView(view, lp);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(
            dp(16), dp(16), dp(16), dp(16)
        );
        c.setBackground(background(PANEL));
        add(content, c, 14);
        return c;
    }

    private void heading(String value) {
        TextView t = text(value, 19, WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        add(content, t, 12);
    }

    private void field(
        LinearLayout parent,
        String key,
        String value
    ) {
        add(parent, text(key, 12, MUTED), 3);
        add(parent, text(value, 15, WHITE), 12);
    }

    private void note(
        LinearLayout parent, String value
    ) {
        add(parent, text(value, 13, MUTED), 10);
    }

    private void button(
        LinearLayout parent,
        String label,
        Runnable action
    ) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(background(ACCENT));

        add(parent, b, 10);
        b.setOnClickListener(v -> action.run());
    }

    private String size(long bytes) {
        return String.format(
            Locale.US,
            "%.2f GB",
            bytes / 1073741824.0
        );
    }

    private void log(String value) {
        logs.append(value).append('\n');

        if (logs.length() > 12000) {
            logs.delete(0, logs.length() - 8000);
        }
    }

    private void copy(String value) {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(
                CLIPBOARD_SERVICE
            );

        clipboard.setPrimaryClip(
            ClipData.newPlainText("TitanX", value)
        );

        Toast.makeText(
            this, "Copied", Toast.LENGTH_SHORT
        ).show();
    }

    private void report(
        String title, String value
    ) {
        ScrollView scroll = new ScrollView(this);

        TextView message = text(value, 14, WHITE);
        message.setTextIsSelectable(true);
        message.setPadding(
            dp(20), dp(12), dp(20), dp(12)
        );

        scroll.addView(message);

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNeutralButton(
                "COPY",
                (dialog, which) -> copy(value)
            )
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        prefs = getSharedPreferences(
            "titanx", MODE_PRIVATE
        );

        runtime = new File(
            getFilesDir(), "runtime"
        );

        log("TitanX v1.1 started");
        log("Compatibility test suite available");

        buildLayout();
        page("Dashboard");
    }

    private void buildLayout() {
        root = new LinearLayout(this);
        root.setOrientation(
            LinearLayout.VERTICAL
        );
        root.setBackgroundColor(BG);

        LinearLayout header =
            new LinearLayout(this);

        header.setOrientation(
            LinearLayout.VERTICAL
        );

        header.setPadding(
            dp(18), dp(20), dp(18), dp(14)
        );

        TextView brand = text(
            "TITANX / EMULATOR", 21, WHITE
        );

        brand.setTypeface(
            Typeface.DEFAULT_BOLD
        );

        add(header, brand, 5);

        subtitle = text("", 12, ACCENT);
        add(header, subtitle, 0);

        root.addView(header);

        ScrollView scroll =
            new ScrollView(this);

        content = new LinearLayout(this);
        content.setOrientation(
            LinearLayout.VERTICAL
        );

        content.setPadding(
            dp(16), dp(12), dp(16), dp(20)
        );

        scroll.addView(content);

        root.addView(
            scroll,
            new LinearLayout.LayoutParams(
                -1, 0, 1
            )
        );

        nav = new LinearLayout(this);
        nav.setOrientation(
            LinearLayout.HORIZONTAL
        );
        nav.setBackgroundColor(PANEL);

        root.addView(nav);
        setContentView(root);
    }

    private void page(String selected) {
        content.removeAllViews();
        nav.removeAllViews();

        subtitle.setText(
            "v1.1 • " + Build.SUPPORTED_ABIS[0]
            + " • "
            + (runtime.isDirectory()
                ? "Runtime files present"
                : "Runtime offline")
        );

        String[] pages = {
            "Dashboard",
            "Runtime",
            "Games",
            "Settings"
        };

        for (String name : pages) {
            TextView t = text(
                name,
                12,
                name.equals(selected)
                    ? ACCENT : MUTED
            );

            t.setGravity(Gravity.CENTER);
            t.setPadding(
                0, dp(17), 0, dp(17)
            );

            nav.addView(
                t,
                new LinearLayout.LayoutParams(
                    0, -2, 1
                )
            );

            t.setOnClickListener(
                v -> page(name)
            );
        }

        switch (selected) {
            case "Dashboard":
                dashboard();
                break;

            case "Runtime":
                runtimePage();
                break;

            case "Games":
                games();
                break;

            case "Settings":
                settings();
                break;
        }
    }

    private void dashboard() {
        heading("DEVICE DASHBOARD");

        LinearLayout c = card();

        ActivityManager am =
            (ActivityManager) getSystemService(
                ACTIVITY_SERVICE
            );

        ActivityManager.MemoryInfo mi =
            new ActivityManager.MemoryInfo();

        am.getMemoryInfo(mi);

        field(
            c, "DEVICE",
            Build.MANUFACTURER + " " + Build.MODEL
        );

        field(
            c, "ANDROID",
            Build.VERSION.RELEASE
        );

        field(
            c, "CPU ARCHITECTURES",
            Arrays.toString(
                Build.SUPPORTED_ABIS
            )
        );

        field(
            c, "AVAILABLE RAM",
            size(mi.availMem)
        );

        field(
            c, "TOTAL RAM",
            size(mi.totalMem)
        );

        boolean vulkan = getPackageManager()
            .hasSystemFeature(
                PackageManager
                    .FEATURE_VULKAN_HARDWARE_VERSION
            );

        field(
            c, "VULKAN",
            vulkan
                ? "Android reports support"
                : "Not advertised"
        );

        heading("ENGINE STATUS");

        LinearLayout e = card();

        field(
            e, "NATIVE ENGINE",
            "JNI C++ integrated"
        );

        field(
            e, "ELF INSPECTOR",
            "Available"
        );

        field(
            e, "SONAME ANALYZER",
            "Available"
        );

        field(
            e, "DEPENDENCY RESOLVER",
            "Available"
        );

        field(
            e, "COMPATIBILITY TEST SUITE",
            "v1.1 synthetic ELF tests"
        );

        field(e, "WINE", "Not integrated");
        field(e, "FEX", "Not integrated");
        field(e, "DXVK", "Not integrated");

        field(
            e, "WINDOWS EXECUTION",
            "Not implemented"
        );

        button(
            e,
            "OPEN RUNTIME",
            () -> page("Runtime")
        );
    }

    private void runtimePage() {
        heading("NATIVE RUNTIME MANAGER");

        LinearLayout c = card();

        field(
            c,
            "STATUS",
            runtime.isDirectory()
                ? "Runtime files present"
                : "No runtime imported"
        );

        note(
            c,
            "Imported files are inspected, "
            + "not executed."
        );

        if (runtime.isDirectory()) {
            button(
                c,
                "SCAN RUNTIME FILES",
                this::scanAsync
            );

            button(
                c,
                "NATIVE ELF INSPECTION",
                this::nativeScanAsync
            );

            button(
                c,
                "CHECK RUNTIME COMPATIBILITY",
                this::compatibilityAsync
            );
        }

        button(
            c,
            "RUN v1.1 COMPATIBILITY TESTS",
            this::testSuiteAsync
        );

        button(
            c,
            "IMPORT RUNTIME ZIP",
            this::pickZip
        );

        button(
            c,
            "TEST NATIVE ENGINE",
            () -> report(
                "TitanX Native Engine v1.1",
                NativeBridge.report()
            )
        );

        button(
            c,
            "REMOVE RUNTIME",
            this::removeRuntime
        );

        heading("COMPATIBILITY CAPABILITIES");

        LinearLayout info = card();

        field(
            info,
            "ELF ARCHITECTURE",
            "ARM64 / ARM32 / x86 / x86-64"
        );

        field(
            info,
            "DT_NEEDED",
            "Read library dependencies"
        );

        field(
            info,
            "DT_SONAME",
            "Read library identity"
        );

        field(
            info,
            "DEPENDENCY RESULTS",
            "FOUND / MISSING / "
            + "INCOMPATIBLE / UNVERIFIED"
        );

        field(
            info,
            "TEST SUITE",
            "Generates synthetic ELF files "
            + "inside private app storage"
        );

        field(
            info,
            "ZIP LIMIT",
            "1 GB / 10,000 entries"
        );

        field(
            info,
            "ENGINE EXECUTION",
            "Not implemented"
        );

        note(
            info,
            "The test suite does not execute "
            + "ELF files or Windows games."
        );
    }

    private void asyncReport(
        String heading,
        String startLog,
        java.util.concurrent.Callable<String> work
    ) {
        if (busy) {
            Toast.makeText(
                this,
                "Operation in progress",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        busy = true;
        log(startLog);

        new Thread(() -> {
            String result;

            try {
                result = work.call();
            } catch (Exception e) {
                result = "Operation failed:\n" + e;
            }

            final String value = result;

            runOnUiThread(() -> {
                busy = false;
                log("Completed: " + heading);
                report(heading, value);
            });
        }).start();
    }

    private void testSuiteAsync() {
        asyncReport(
            "TitanX v1.1 Test Results",
            "Compatibility tests started",
            () -> CompatibilityFixtures.run(
                getFilesDir()
            )
        );
    }

    private void compatibilityAsync() {
        asyncReport(
            "TitanX Compatibility Report v1.1",
            "Compatibility scan started",
            () -> DependencyResolver.analyze(
                runtime
            )
        );
    }

    private static class Scan {
        int files;
        int elf;
        int arm64;
        int arm32;
        int x86;
        int x64;
        int other;
        long bytes;
    }

    private String architecture(File file) {
        byte[] h = new byte[20];

        try (FileInputStream in =
                 new FileInputStream(file)) {

            int n = 0;

            while (n < h.length) {
                int k = in.read(
                    h, n, h.length - n
                );

                if (k < 0) return "Unknown";
                n += k;
            }

            if ((h[0] & 255) != 127
                || h[1] != 'E'
                || h[2] != 'L'
                || h[3] != 'F') {
                return "Not ELF";
            }

            int machine;

            if (h[5] == 1) {
                machine = (h[18] & 255)
                    | ((h[19] & 255) << 8);
            } else if (h[5] == 2) {
                machine = ((h[18] & 255) << 8)
                    | (h[19] & 255);
            } else {
                return "Unknown";
            }

            switch (machine) {
                case 183: return "ARM64";
                case 40: return "ARM32";
                case 3: return "x86";
                case 62: return "x86-64";
                default: return "Other";
            }

        } catch (IOException e) {
            return "Unknown";
        }
    }

    private void scanFiles(
        File dir,
        Scan scan,
        int depth
    ) {
        if (depth > 32 || scan.files >= 20000)
            return;

        File[] children = dir.listFiles();
        if (children == null) return;

        for (File file : children) {
            if (scan.files >= 20000)
                break;

            if (file.isDirectory()) {
                scanFiles(
                    file, scan, depth + 1
                );
                continue;
            }

            scan.files++;
            scan.bytes += file.length();

            String arch = architecture(file);

            if (!arch.equals("Not ELF")) {
                scan.elf++;

                switch (arch) {
                    case "ARM64":
                        scan.arm64++;
                        break;

                    case "ARM32":
                        scan.arm32++;
                        break;

                    case "x86":
                        scan.x86++;
                        break;

                    case "x86-64":
                        scan.x64++;
                        break;

                    default:
                        scan.other++;
                        break;
                }
            }
        }
    }

    private void scanAsync() {
        asyncReport(
            "Runtime File Scan",
            "Runtime scan started",
            () -> {
                Scan s = new Scan();

                scanFiles(runtime, s, 0);

                return "Files: " + s.files
                    + "\nSize: " + size(s.bytes)
                    + "\nELF: " + s.elf
                    + "\nARM64: " + s.arm64
                    + "\nARM32: " + s.arm32
                    + "\nx86: " + s.x86
                    + "\nx86-64: " + s.x64
                    + "\nOther: " + s.other;
            }
        );
    }

    private void nativeScanAsync() {
        asyncReport(
            "Native ELF Report",
            "Native scan started",
            () -> {
                StringBuilder out =
                    new StringBuilder();

                File[] files =
                    runtime.listFiles();

                int count = 0;

                if (files != null) {
                    for (File file : files) {
                        if (!file.isFile())
                            continue;

                        out.append(
                            file.getName()
                        ).append('\n');

                        out.append(
                            NativeBridge.inspect(file)
                        ).append("\n\n");

                        if (++count >= 20)
                            break;
                    }
                }

                if (count == 0) {
                    out.append(
                        "No root-level files.\n"
                        + "Use compatibility scan "
                        + "for nested directories."
                    );
                }

                return out.toString();
            }
        );
    }

    private void games() {
        heading("GAME LIBRARY");

        LinearLayout c = card();

        field(
            c, "GAME LAUNCHER",
            "Not implemented"
        );

        field(c, "WINE", "Not integrated");
        field(c, "FEX", "Not integrated");
        field(c, "DXVK", "Not integrated");

        note(
            c,
            "TitanX v1.1 tests runtime "
            + "compatibility. Windows game "
            + "execution is not available."
        );

        button(
            c,
            "OPEN RUNTIME MANAGER",
            () -> page("Runtime")
        );
    }

    private void settings() {
        heading("SETTINGS");

        LinearLayout c = card();

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
            Math.max(
                0,
                Math.min(
                    2,
                    prefs.getInt("profile", 1)
                )
            )
        );

        add(c, spinner, 10);

        button(
            c,
            "SAVE PROFILE",
            () -> {
                prefs.edit()
                    .putInt(
                        "profile",
                        spinner.getSelectedItemPosition()
                    )
                    .apply();

                Toast.makeText(
                    this,
                    "Saved",
                    Toast.LENGTH_SHORT
                ).show();
            }
        );

        note(
            c,
            "Performance profiles are "
            + "placeholders until the "
            + "execution engine is integrated."
        );

        heading("SESSION LOG");

        LinearLayout l = card();

        add(
            l,
            text(logs.toString(), 12, WHITE),
            12
        );

        button(
            l,
            "COPY LOG",
            () -> copy(logs.toString())
        );

        button(
            l,
            "CLEAR LOG",
            () -> {
                logs.setLength(0);
                log("Log cleared");
                page("Settings");
            }
        );
    }

    private void pickZip() {
        if (busy) return;

        Intent intent = new Intent(
            Intent.ACTION_OPEN_DOCUMENT
        );

        intent.setType("*/*");

        intent.addCategory(
            Intent.CATEGORY_OPENABLE
        );

        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        startActivityForResult(
            intent, PICK_ZIP
        );
    }

    @Override
    protected void onActivityResult(
        int request,
        int result,
        Intent data
    ) {
        super.onActivityResult(
            request, result, data
        );

        if (request != PICK_ZIP
            || result != RESULT_OK
            || data == null
            || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        new AlertDialog.Builder(this)
            .setTitle("Import Runtime ZIP")
            .setMessage(
                "Import this ZIP into private "
                + "app storage?\n\n"
                + "Use trusted files only."
            )
            .setNegativeButton(
                "CANCEL", null
            )
            .setPositiveButton(
                "IMPORT",
                (dialog, which) -> importZip(uri)
            )
            .show();
    }

    private void importZip(Uri uri) {
        if (busy) return;
        busy = true;

        new Thread(() -> {
            File stage = new File(
                getFilesDir(),
                "runtime_staging"
            );

            File backup = new File(
                getFilesDir(),
                "runtime_backup"
            );

            String result;

            try {
                if (!deleteTree(stage)
                    || !deleteTree(backup)
                    || !stage.mkdirs()) {
                    throw new IOException(
                        "Cannot prepare storage"
                    );
                }

                extract(uri, stage);

                boolean old = runtime.exists();

                if (old
                    && !runtime.renameTo(backup)) {
                    throw new IOException(
                        "Cannot back up runtime"
                    );
                }

                if (!stage.renameTo(runtime)) {
                    if (old) {
                        backup.renameTo(runtime);
                    }

                    throw new IOException(
                        "Cannot activate runtime"
                    );
                }

                deleteTree(backup);

                result =
                    "Runtime imported successfully";

            } catch (Exception e) {
                deleteTree(stage);

                result =
                    "Import failed: "
                    + e.getMessage();
            }

            final String message = result;

            runOnUiThread(() -> {
                busy = false;
                log(message);

                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
                ).show();

                page("Runtime");
            });
        }).start();
    }

    private void extract(
        Uri uri, File destination
    ) throws IOException {

        String base =
            destination.getCanonicalPath()
            + File.separator;

        InputStream input =
            getContentResolver()
                .openInputStream(uri);

        if (input == null) {
            throw new IOException(
                "Cannot open ZIP"
            );
        }

        int entries = 0;
        long bytes = 0;

        try (ZipInputStream zip =
                 new ZipInputStream(
                     new BufferedInputStream(input)
                 )) {

            ZipEntry entry;
            byte[] buffer = new byte[32768];

            while ((entry = zip.getNextEntry())
                    != null) {

                if (++entries > MAX_ENTRIES) {
                    throw new IOException(
                        "Too many entries"
                    );
                }

                String name = entry.getName();

                if (name.isEmpty()
                    || name.startsWith("/")
                    || name.startsWith("\\")
                    || name.contains("\\")
                    || name.indexOf('\0') >= 0) {
                    throw new IOException(
                        "Unsafe ZIP path"
                    );
                }

                File target = new File(
                    destination, name
                );

                if (!target.getCanonicalPath()
                    .startsWith(base)) {
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
                    File parent =
                        target.getParentFile();

                    if (!parent.isDirectory()
                        && !parent.mkdirs()) {
                        throw new IOException(
                            "Cannot create parent"
                        );
                    }

                    if (target.exists()) {
                        throw new IOException(
                            "Duplicate ZIP entry"
                        );
                    }

                    try (OutputStream out =
                             new BufferedOutputStream(
                                 new FileOutputStream(
                                     target
                                 )
                             )) {

                        int n;

                        while ((n = zip.read(buffer))
                                != -1) {

                            bytes += n;

                            if (bytes > MAX_ZIP) {
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

        if (entries == 0) {
            throw new IOException(
                "Empty ZIP"
            );
        }
    }

    private boolean deleteTree(File file) {
        if (!file.exists())
            return true;

        if (file.isDirectory()) {
            File[] children =
                file.listFiles();

            if (children == null)
                return false;

            for (File child : children) {
                if (!deleteTree(child))
                    return false;
            }
        }

        return file.delete();
    }

    private void removeRuntime() {
        if (busy) return;

        new AlertDialog.Builder(this)
            .setTitle("Remove Runtime")
            .setMessage(
                "Delete imported runtime files?"
            )
            .setNegativeButton(
                "CANCEL", null
            )
            .setPositiveButton(
                "DELETE",
                (dialog, which) -> {
                    busy = true;

                    new Thread(() -> {
                        boolean ok =
                            deleteTree(runtime);

                        runOnUiThread(() -> {
                            busy = false;

                            log(
                                ok
                                    ? "Runtime removed"
                                    : "Runtime removal failed"
                            );

                            page("Runtime");
                        });
                    }).start();
                }
            )
            .show();
    }
}
