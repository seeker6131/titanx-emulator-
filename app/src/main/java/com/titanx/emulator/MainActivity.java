
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
import java.text.SimpleDateFormat;
import java.util.zip.*;

public class MainActivity extends Activity {

    private static final int PICK_ZIP = 101;
    private static final long MAX_BYTES = 1073741824L;
    private static final int MAX_ENTRIES = 10000;

    private final int BG = Color.rgb(10, 15, 26);
    private final int PANEL = Color.rgb(24, 33, 49);
    private final int ACCENT = Color.rgb(55, 205, 175);
    private final int WHITE = Color.WHITE;
    private final int MUTED = Color.rgb(165, 180, 200);

    private LinearLayout root, content, nav;
    private TextView subtitle;
    private SharedPreferences prefs;
    private File runtime;
    private volatile boolean busy = false;
    private final StringBuilder logs = new StringBuilder();

    private int dp(int value) {
        return (int) (value *
            getResources().getDisplayMetrics().density);
    }

    private GradientDrawable bg(int color) {
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
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(gap);
        parent.addView(child, lp);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(bg(PANEL));
        add(content, c, 14);
        return c;
    }

    private void title(String value) {
        TextView t = text(value, 19, WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        add(content, t, 12);
    }

    private void field(LinearLayout parent,
                       String name, String value) {
        add(parent, text(name, 12, MUTED), 3);
        add(parent, text(value, 15, WHITE), 12);
    }

    private void note(LinearLayout parent, String value) {
        add(parent, text(value, 13, MUTED), 10);
    }

    private void button(LinearLayout parent,
                        String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(bg(ACCENT));
        add(parent, b, 10);
        b.setOnClickListener(v -> action.run());
    }

    private String gb(long bytes) {
        return String.format(Locale.US,
            "%.2f GB", bytes / 1073741824.0);
    }

    private void log(String value) {
        String time = new SimpleDateFormat(
            "HH:mm:ss", Locale.US).format(new Date());

        logs.append(time).append(" ")
            .append(value).append("\n");

        if (logs.length() > 16000) {
            logs.delete(0, logs.length() - 12000);
        }
    }

    private void copy(String value) {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(
                CLIPBOARD_SERVICE);

        clipboard.setPrimaryClip(
            ClipData.newPlainText("TitanX", value));

        Toast.makeText(this, "Copied",
            Toast.LENGTH_SHORT).show();
    }

    private void showReport(String heading, String report) {
        new AlertDialog.Builder(this)
            .setTitle(heading)
            .setMessage(report)
            .setPositiveButton("OK", null)
            .setNeutralButton("COPY",
                (dialog, which) -> copy(report))
            .show();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView()
            .setSystemUiVisibility(0);

        prefs = getSharedPreferences(
            "titanx", MODE_PRIVATE);

        runtime = new File(getFilesDir(), "runtime");

        log("TitanX v0.9 started");
        log("Native dependency resolver available");
        log("Windows execution not integrated");

        buildLayout();
        page("Dashboard");
    }

    private void buildLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(
            dp(18), dp(22), dp(18), dp(14));

        TextView brand = text(
            "TITANX / EMULATOR", 21, WHITE);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        add(header, brand, 4);

        subtitle = text("", 12, ACCENT);
        add(header, subtitle, 0);

        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
            dp(16), dp(12), dp(16), dp(20));

        scroll.addView(content);

        root.addView(scroll,
            new LinearLayout.LayoutParams(-1, 0, 1));

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(PANEL);
        nav.setPadding(dp(4), dp(7), dp(4), dp(7));

        root.addView(nav);
        setContentView(root);
    }

    private void page(String name) {
        content.removeAllViews();
        nav.removeAllViews();

        subtitle.setText(
            "v0.9 • " + Build.SUPPORTED_ABIS[0]
            + " • " + (runtime.isDirectory()
                ? "Runtime files present"
                : "Runtime offline"));

        String[] pages = {
            "Dashboard", "Runtime", "Games", "Settings"
        };

        for (String item : pages) {
            TextView t = text(item, 12,
                item.equals(name) ? ACCENT : MUTED);

            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(15), 0, dp(15));

            nav.addView(t,
                new LinearLayout.LayoutParams(0, -2, 1));

            t.setOnClickListener(v -> page(item));
        }

        switch (name) {
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
        title("DEVICE DASHBOARD");

        LinearLayout c = card();

        ActivityManager am = (ActivityManager)
            getSystemService(ACTIVITY_SERVICE);

        ActivityManager.MemoryInfo mi =
            new ActivityManager.MemoryInfo();

        am.getMemoryInfo(mi);

        StatFs storage = new StatFs(
            Environment.getDataDirectory()
                .getAbsolutePath());

        field(c, "DEVICE",
            Build.MANUFACTURER + " " + Build.MODEL);

        field(c, "ANDROID",
            Build.VERSION.RELEASE + " / API "
                + Build.VERSION.SDK_INT);

        field(c, "ARCHITECTURES",
            Arrays.toString(Build.SUPPORTED_ABIS));

        field(c, "RAM",
            gb(mi.availMem) + " available / "
                + gb(mi.totalMem) + " total");

        field(c, "STORAGE FREE",
            gb(storage.getAvailableBytes()));

        boolean vulkan = getPackageManager()
            .hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);

        field(c, "VULKAN",
            vulkan ? "Advertised by Android"
                : "Not advertised");

        title("ENGINE READINESS");

        LinearLayout e = card();

        field(e, "NATIVE C++ ENGINE",
            "JNI core integrated");

        field(e, "ELF INSPECTOR",
            "Native dependency parsing");

        field(e, "DEPENDENCY RESOLVER",
            "Runtime filename matching");

        field(e, "WINE",
            "Not integrated");

        field(e, "FEX / BOX64",
            "Not integrated");

        field(e, "DXVK / TURNIP",
            "Not integrated");

        field(e, "WINDOWS EXECUTION",
            "Unavailable");

        button(e, "OPEN RUNTIME",
            () -> page("Runtime"));
    }

    private static class Scan {
        int files;
        int elf;
        int arm64;
        int arm32;
        int x86;
        int x64;
        int unknown;
        int libraries;
        int wineNames;
        int fexNames;
        int boxNames;

        long bytes;

        ArrayList<String> samples =
            new ArrayList<>();
    }

    private String elfArch(File file) {
        byte[] header = new byte[20];

        try (FileInputStream in =
                 new FileInputStream(file)) {

            int offset = 0;

            while (offset < header.length) {
                int n = in.read(header,
                    offset, header.length - offset);

                if (n < 0) return "Unknown";

                offset += n;
            }

            if ((header[0] & 255) != 127
                || header[1] != 'E'
                || header[2] != 'L'
                || header[3] != 'F') {
                return "Not ELF";
            }

            int machine;

            if (header[5] == 1) {
                machine = (header[18] & 255)
                    | ((header[19] & 255) << 8);
            } else if (header[5] == 2) {
                machine = ((header[18] & 255) << 8)
                    | (header[19] & 255);
            } else {
                return "Unknown";
            }

            if (machine == 183 && header[4] == 2)
                return "ARM64";

            if (machine == 40 && header[4] == 1)
                return "ARM32";

            if (machine == 3 && header[4] == 1)
                return "x86";

            if (machine == 62 && header[4] == 2)
                return "x86-64";

            return "Other ELF";

        } catch (IOException e) {
            return "Unreadable";
        }
    }

    private void inspect(File dir, Scan scan, int depth) {
        if (depth > 32 || scan.files >= 20000)
            return;

        File[] children = dir.listFiles();
        if (children == null) return;

        for (File file : children) {
            if (scan.files >= 20000) break;

            if (file.isDirectory()) {
                inspect(file, scan, depth + 1);
                continue;
            }

            scan.files++;
            scan.bytes += file.length();

            String name = file.getName()
                .toLowerCase(Locale.US);

            if (name.endsWith(".so"))
                scan.libraries++;

            if (name.equals("wine")
                || name.equals("wine64")
                || name.equals("wineserver"))
                scan.wineNames++;

            if (name.startsWith("fex"))
                scan.fexNames++;

            if (name.startsWith("box64")
                || name.startsWith("box86"))
                scan.boxNames++;

            String arch = elfArch(file);

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
                        scan.unknown++;
                        break;
                }

                if (scan.samples.size() < 12) {
                    scan.samples.add(
                        file.getName() + " : " + arch);
                }
            }
        }
    }

    private void runtimePage() {
        title("NATIVE RUNTIME MANAGER");

        LinearLayout c = card();

        if (!runtime.isDirectory()) {
            field(c, "STATUS",
                "No runtime imported");

            note(c,
                "Import a trusted ZIP to inspect "
                + "its runtime files.");

        } else {
            field(c, "STATUS",
                "Runtime files present");

            note(c,
                "Files are inspected, not executed.");

            button(c, "SCAN RUNTIME FILES",
                this::scanAsync);

            button(c, "NATIVE ELF INSPECTION",
                this::nativeScanAsync);

            button(c, "CHECK RUNTIME DEPENDENCIES",
                this::dependencyScanAsync);
        }

        button(c, "IMPORT RUNTIME ZIP",
            this::pickZip);

        button(c, "TEST NATIVE ENGINE", () -> {
            String report = NativeBridge.report();

            log("Native engine test requested");

            showReport(
                "TitanX Native Engine v0.9",
                report
            );
        });

        button(c, "REMOVE RUNTIME",
            this::removeRuntime);

        title("INSPECTION CAPABILITIES");

        LinearLayout info = card();

        field(info, "JAVA ELF SCANNER",
            "Recursive runtime file scan");

        field(info, "NATIVE ELF INSPECTOR",
            "C++ architecture and DT_NEEDED");

        field(info, "DEPENDENCY RESOLVER",
            "Find matching library filenames");

        field(info, "SUPPORTED ARCHITECTURES",
            "ARM64 / ARM32 / x86 / x86-64");

        field(info, "ZIP EXTRACTION LIMIT",
            "1 GB / 10,000 entries");

        field(info, "ENGINE EXECUTION",
            "Not implemented");

        note(info,
            "A matching library name does not "
            + "prove ABI compatibility or "
            + "that a program can run.");
    }

    private void scanAsync() {
        if (busy) return;
        busy = true;

        new Thread(() -> {
            String report;

            try {
                Scan scan = new Scan();
                inspect(runtime, scan, 0);

                StringBuilder sb = new StringBuilder();

                sb.append("Files: ")
                    .append(scan.files);

                sb.append("\nSize: ")
                    .append(gb(scan.bytes));

                sb.append("\nELF files: ")
                    .append(scan.elf);

                sb.append("\nARM64: ")
                    .append(scan.arm64);

                sb.append("\nARM32: ")
                    .append(scan.arm32);

                sb.append("\nx86: ")
                    .append(scan.x86);

                sb.append("\nx86-64: ")
                    .append(scan.x64);

                sb.append("\nOther/unknown: ")
                    .append(scan.unknown);

                sb.append("\n.so filenames: ")
                    .append(scan.libraries);

                sb.append("\nWine filenames: ")
                    .append(scan.wineNames);

                sb.append("\nFEX filenames: ")
                    .append(scan.fexNames);

                sb.append("\nBox filenames: ")
                    .append(scan.boxNames);

                sb.append("\n\nELF samples:\n");

                for (String sample : scan.samples) {
                    sb.append(sample).append("\n");
                }

                report = sb.toString();

            } catch (Exception e) {
                report = "Scan failed:\n" + e;
            }

            final String result = report;

            runOnUiThread(() -> {
                busy = false;
                log("Runtime scan completed");

                showReport(
                    "Runtime Scan Report",
                    result
                );
            });
        }).start();
    }

    private void nativeScanAsync() {
        if (busy) return;
        busy = true;

        new Thread(() -> {
            String report;

            try {
                StringBuilder sb =
                    new StringBuilder();

                File[] files = runtime.listFiles();
                int checked = 0;

                if (files != null) {
                    for (File file : files) {
                        if (!file.isFile()) continue;

                        sb.append(file.getName())
                            .append("\n");

                        sb.append(
                            NativeBridge.inspect(file)
                        ).append("\n\n");

                        checked++;

                        if (checked >= 20) break;
                    }
                }

                if (checked == 0) {
                    sb.append(
                        "No regular files at runtime root.\n"
                        + "Use Dependency Resolver "
                        + "to scan nested folders."
                    );
                }

                report = sb.toString();

            } catch (Exception e) {
                report = "Native scan failed:\n" + e;
            }

            final String result = report;

            runOnUiThread(() -> {
                busy = false;
                log("Native ELF scan completed");

                showReport(
                    "Native ELF Report",
                    result
                );
            });
        }).start();
    }

    private void dependencyScanAsync() {
        if (busy) return;
        busy = true;

        log("Dependency resolver started");

        new Thread(() -> {
            String report;

            try {
                report =
                    DependencyResolver.analyze(runtime);

            } catch (Exception e) {
                report =
                    "Dependency resolver error:\n" + e;
            }

            final String result = report;

            runOnUiThread(() -> {
                busy = false;

                log("Dependency resolver completed");

                showReport(
                    "TitanX Dependency Report v0.9",
                    result
                );
            });
        }).start();
    }

    private void games() {
        title("GAME LIBRARY");

        LinearLayout c = card();

        field(c, "WINDOWS EXECUTION",
            "Not available");

        field(c, "WINE / FEX",
            "Not integrated");

        field(c, "DXVK",
            "Not integrated");

        note(c,
            "TitanX v0.9 inspects runtime "
            + "files and dependencies. "
            + "It cannot launch Windows games yet.");

        button(c, "OPEN RUNTIME MANAGER",
            () -> page("Runtime"));
    }

    private void settings() {
        title("SETTINGS");

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
            Math.max(0, Math.min(2,
                prefs.getInt("profile", 1)))
        );

        add(c, spinner, 10);

        button(c, "SAVE PROFILE", () -> {
            int index =
                spinner.getSelectedItemPosition();

            prefs.edit()
                .putInt("profile", index)
                .apply();

            log("Profile saved: "
                + profiles[index]);

            Toast.makeText(
                this, "Saved",
                Toast.LENGTH_SHORT
            ).show();
        });

        note(c,
            "Profiles are preferences only. "
            + "Performance tuning is not "
            + "implemented yet.");

        title("SESSION LOG");

        LinearLayout l = card();

        add(l, text(logs.toString(), 12, WHITE), 12);

        button(l, "COPY LOG",
            () -> copy(logs.toString()));

        button(l, "CLEAR LOG", () -> {
            logs.setLength(0);
            log("Log cleared");
            page("Settings");
        });
    }

    private void pickZip() {
        if (busy) {
            Toast.makeText(
                this,
                "Operation in progress",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Intent intent = new Intent(
            Intent.ACTION_OPEN_DOCUMENT);

        intent.setType("*/*");
        intent.addCategory(
            Intent.CATEGORY_OPENABLE);

        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivityForResult(intent, PICK_ZIP);
    }

    @Override
    protected void onActivityResult(
        int request,
        int result,
        Intent data
    ) {
        super.onActivityResult(
            request, result, data);

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
                "Extract this ZIP into private "
                + "app storage?\n\n"
                + "Only import trusted files."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("IMPORT",
                (dialog, which) -> importZip(uri))
            .show();
    }

    private void importZip(Uri uri) {
        if (busy) return;
        busy = true;

        log("ZIP import started");

        new Thread(() -> {
            File stage = new File(
                getFilesDir(), "runtime_staging");

            File backup = new File(
                getFilesDir(), "runtime_backup");

            String message;

            try {
                if (!deleteTree(stage)
                    || !deleteTree(backup)
                    || !stage.mkdirs()) {
                    throw new IOException(
                        "Cannot prepare directories");
                }

                extract(uri, stage);

                boolean hadOld = runtime.exists();

                if (hadOld
                    && !runtime.renameTo(backup)) {
                    throw new IOException(
                        "Cannot back up runtime");
                }

                if (!stage.renameTo(runtime)) {
                    if (hadOld) {
                        backup.renameTo(runtime);
                    }

                    throw new IOException(
                        "Cannot activate runtime");
                }

                deleteTree(backup);

                message =
                    "Runtime imported successfully";

            } catch (Exception e) {
                deleteTree(stage);

                message =
                    "Import failed: " + e.getMessage();
            }

            final String result = message;

            runOnUiThread(() -> {
                busy = false;

                log(result);

                Toast.makeText(
                    this,
                    result,
                    Toast.LENGTH_LONG
                ).show();

                page("Runtime");
            });
        }).start();
    }

    private void extract(Uri uri, File dest)
        throws IOException {

        String base = dest.getCanonicalPath()
            + File.separator;

        int entries = 0;
        long bytes = 0;

        InputStream input =
            getContentResolver().openInputStream(uri);

        if (input == null) {
            throw new IOException(
                "Cannot open ZIP");
        }

        try (ZipInputStream zip =
                 new ZipInputStream(
                     new BufferedInputStream(input))) {

            ZipEntry entry;
            byte[] buffer = new byte[32768];

            while ((entry = zip.getNextEntry())
                    != null) {

                entries++;

                if (entries > MAX_ENTRIES) {
                    throw new IOException(
                        "Too many ZIP entries");
                }

                String name = entry.getName();

                if (name.isEmpty()
                    || name.startsWith("/")
                    || name.startsWith("\\")
                    || name.contains("\\")
                    || name.indexOf('\0') >= 0) {
                    throw new IOException(
                        "Unsafe ZIP entry");
                }

                File target = new File(dest, name);

                if (!target.getCanonicalPath()
                    .startsWith(base)) {
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

                    if (!parent.isDirectory()
                        && !parent.mkdirs()) {
                        throw new IOException(
                            "Cannot create parent");
                    }

                    if (target.exists()) {
                        throw new IOException(
                            "Duplicate ZIP entry");
                    }

                    try (OutputStream out =
                             new BufferedOutputStream(
                                 new FileOutputStream(
                                     target))) {

                        int n;

                        while ((n = zip.read(buffer))
                                != -1) {

                            bytes += n;

                            if (bytes > MAX_BYTES) {
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

        if (entries == 0) {
            throw new IOException(
                "Empty or invalid ZIP");
        }
    }

    private boolean deleteTree(File file) {
        if (!file.exists()) return true;

        if (file.isDirectory()) {
            File[] children = file.listFiles();

            if (children == null) return false;

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
                "Delete imported runtime files?")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE",
                (dialog, which) -> {
                    busy = true;

                    new Thread(() -> {
                        boolean success =
                            deleteTree(runtime);

                        runOnUiThread(() -> {
                            busy = false;

                            log(success
                                ? "Runtime removed"
                                : "Runtime removal failed");

                            page("Runtime");
                        });
                    }).start();
                })
            .show();
    }
}
