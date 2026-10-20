
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
    private static final long MAX_BYTES =
        1024L * 1024L * 1024L;
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

    private final StringBuilder logs =
        new StringBuilder();

    private int dp(int v) {
        return (int) (
            v * getResources()
                .getDisplayMetrics().density
        );
    }

    private GradientDrawable bg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }

    private TextView text(
        String s, int size, int color
    ) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private void add(
        LinearLayout p, View v, int gap
    ) {
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(-1, -2);

        lp.bottomMargin = dp(gap);
        p.addView(v, lp);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(
            LinearLayout.VERTICAL
        );
        c.setPadding(
            dp(16), dp(16), dp(16), dp(16)
        );
        c.setBackground(bg(PANEL));
        add(content, c, 14);
        return c;
    }

    private void title(String s) {
        TextView t = text(s, 19, WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        add(content, t, 12);
    }

    private void field(
        LinearLayout p, String k, String v
    ) {
        add(p, text(k, 12, MUTED), 3);
        add(p, text(v, 15, WHITE), 12);
    }

    private void note(
        LinearLayout p, String s
    ) {
        add(p, text(s, 13, MUTED), 10);
    }

    private void button(
        LinearLayout p,
        String s,
        Runnable r
    ) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(bg(ACCENT));

        add(p, b, 10);
        b.setOnClickListener(v -> r.run());
    }

    private String gb(long v) {
        return String.format(
            Locale.US,
            "%.2f GB",
            v / 1073741824.0
        );
    }

    private void log(String s) {
        String tm = new SimpleDateFormat(
            "HH:mm:ss",
            Locale.US
        ).format(new Date());

        logs.append(tm)
            .append(" ")
            .append(s)
            .append("\n");

        if (logs.length() > 16000) {
            logs.delete(
                0,
                logs.length() - 12000
            );
        }
    }

    private void copy(String s) {
        ClipboardManager c =
            (ClipboardManager)
            getSystemService(CLIPBOARD_SERVICE);

        c.setPrimaryClip(
            ClipData.newPlainText(
                "TitanX", s
            )
        );

        Toast.makeText(
            this,
            "Copied",
            Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView()
            .setSystemUiVisibility(0);

        prefs = getSharedPreferences(
            "titanx",
            MODE_PRIVATE
        );

        runtime = new File(
            getFilesDir(),
            "runtime"
        );

        log("TitanX v0.7 started");
        log("Native ELF inspection available");
        log("Windows execution not integrated");

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
            dp(18), dp(22),
            dp(18), dp(14)
        );

        TextView brand = text(
            "TITANX / EMULATOR",
            21, WHITE
        );

        brand.setTypeface(
            Typeface.DEFAULT_BOLD
        );

        add(header, brand, 4);

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
            dp(16), dp(12),
            dp(16), dp(20)
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
        nav.setPadding(
            dp(4), dp(7),
            dp(4), dp(7)
        );

        root.addView(nav);
        setContentView(root);
    }

    private void page(String name) {
        content.removeAllViews();
        nav.removeAllViews();

        subtitle.setText(
            "v0.7 • "
            + Build.SUPPORTED_ABIS[0]
            + " • "
            + (
                runtime.isDirectory()
                ? "Runtime files present"
                : "Runtime offline"
            )
        );

        String[] pages = {
            "Dashboard",
            "Runtime",
            "Games",
            "Settings"
        };

        for (String item : pages) {
            TextView t = text(
                item,
                12,
                item.equals(name)
                    ? ACCENT
                    : MUTED
            );

            t.setGravity(Gravity.CENTER);
            t.setPadding(
                0, dp(15),
                0, dp(15)
            );

            nav.addView(
                t,
                new LinearLayout.LayoutParams(
                    0, -2, 1
                )
            );

            t.setOnClickListener(
                v -> page(item)
            );
        }

        if (name.equals("Dashboard")) {
            dashboard();
        } else if (name.equals("Runtime")) {
            runtimePage();
        } else if (name.equals("Games")) {
            games();
        } else {
            settings();
        }
    }

    private void dashboard() {
        title("DEVICE DASHBOARD");

        LinearLayout c = card();

        ActivityManager am =
            (ActivityManager)
            getSystemService(
                ACTIVITY_SERVICE
            );

        ActivityManager.MemoryInfo mi =
            new ActivityManager.MemoryInfo();

        am.getMemoryInfo(mi);

        StatFs storage = new StatFs(
            Environment.getDataDirectory()
                .getAbsolutePath()
        );

        field(
            c,
            "DEVICE",
            Build.MANUFACTURER
            + " "
            + Build.MODEL
        );

        field(
            c,
            "ANDROID",
            Build.VERSION.RELEASE
            + " / API "
            + Build.VERSION.SDK_INT
        );

        field(
            c,
            "ARCHITECTURES",
            Arrays.toString(
                Build.SUPPORTED_ABIS
            )
        );

        field(
            c,
            "RAM",
            gb(mi.availMem)
            + " available / "
            + gb(mi.totalMem)
            + " total"
        );

        field(
            c,
            "STORAGE FREE",
            gb(storage.getAvailableBytes())
        );

        boolean vulkan =
            getPackageManager().hasSystemFeature(
                PackageManager
                    .FEATURE_VULKAN_HARDWARE_VERSION
            );

        field(
            c,
            "VULKAN",
            vulkan
                ? "Advertised by Android"
                : "Not advertised"
        );

        title("ENGINE READINESS");

        LinearLayout e = card();

        field(
            e,
            "TITANX NATIVE CORE",
            "JNI bridge available for testing"
        );

        field(
            e,
            "WINE",
            "Not integrated"
        );

        field(
            e,
            "FEX / BOX64",
            "Not integrated"
        );

        field(
            e,
            "DXVK / TURNIP",
            "Not integrated"
        );

        field(
            e,
            "WINDOWS EXECUTION",
            "Unavailable"
        );

        button(
            e,
            "INSPECT RUNTIME",
            () -> page("Runtime")
        );
    }

    private static class Scan {
        int files = 0;
        int elf = 0;
        int arm64 = 0;
        int arm32 = 0;
        int x86 = 0;
        int x64 = 0;
        int unknown = 0;
        int libraries = 0;
        int wineNames = 0;
        int fexNames = 0;
        int boxNames = 0;

        long bytes = 0;

        ArrayList<String> samples =
            new ArrayList<>();
    }

    private String elfArch(File f) {
        byte[] h = new byte[20];

        try (
            FileInputStream in =
                new FileInputStream(f)
        ) {
            int offset = 0;

            while (offset < h.length) {
                int n = in.read(
                    h,
                    offset,
                    h.length - offset
                );

                if (n < 0) {
                    return "Unknown";
                }

                offset += n;
            }

            if (
                (h[0] & 255) != 127
                || h[1] != 'E'
                || h[2] != 'L'
                || h[3] != 'F'
            ) {
                return "Not ELF";
            }

            int machine;

            if (h[5] == 1) {
                machine =
                    (h[18] & 255)
                    | ((h[19] & 255) << 8);
            } else if (h[5] == 2) {
                machine =
                    ((h[18] & 255) << 8)
                    | (h[19] & 255);
            } else {
                return "Unknown";
            }

            if (
                machine == 183
                && h[4] == 2
            ) {
                return "ARM64";
            }

            if (
                machine == 40
                && h[4] == 1
            ) {
                return "ARM32";
            }

            if (
                machine == 3
                && h[4] == 1
            ) {
                return "x86";
            }

            if (
                machine == 62
                && h[4] == 2
            ) {
                return "x86-64";
            }

            return "Other ELF";

        } catch (IOException ex) {
            return "Unreadable";
        }
    }

    private void inspect(
        File dir,
        Scan s,
        int depth
    ) {
        if (
            depth > 32
            || s.files >= 20000
        ) {
            return;
        }

        File[] children =
            dir.listFiles();

        if (children == null) {
            return;
        }

        for (File f : children) {
            if (s.files >= 20000) {
                break;
            }

            if (f.isDirectory()) {
                inspect(
                    f,
                    s,
                    depth + 1
                );
                continue;
            }

            s.files++;
            s.bytes += f.length();

            String n = f.getName()
                .toLowerCase(Locale.US);

            if (n.endsWith(".so")) {
                s.libraries++;
            }

            if (
                n.equals("wine")
                || n.equals("wine64")
                || n.equals("wineserver")
            ) {
                s.wineNames++;
            }

            if (n.startsWith("fex")) {
                s.fexNames++;
            }

            if (
                n.startsWith("box64")
                || n.startsWith("box86")
            ) {
                s.boxNames++;
            }

            String arch = elfArch(f);

            if (!arch.equals("Not ELF")) {
                s.elf++;

                if (arch.equals("ARM64")) {
                    s.arm64++;
                } else if (
                    arch.equals("ARM32")
                ) {
                    s.arm32++;
                } else if (
                    arch.equals("x86")
                ) {
                    s.x86++;
                } else if (
                    arch.equals("x86-64")
                ) {
                    s.x64++;
                } else {
                    s.unknown++;
                }

                if (s.samples.size() < 12) {
                    s.samples.add(
                        f.getName()
                        + " : "
                        + arch
                    );
                }
            }
        }
    }

    private void runtimePage() {
        title("NATIVE RUNTIME INSPECTOR");

        LinearLayout c = card();

        if (!runtime.isDirectory()) {
            field(
                c,
                "STATUS",
                "No runtime imported"
            );
        } else {
            field(
                c,
                "STATUS",
                "Imported files present"
            );

            note(
                c,
                "Runtime files are inspected, "
                + "not executed."
            );

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
        }

        button(
            c,
            "IMPORT RUNTIME ZIP",
            this::pickZip
        );

        button(
            c,
            "TEST NATIVE ENGINE",
            () -> {
                String report =
                    NativeBridge.report();

                log(
                    "Native engine test requested"
                );

                showReport(
                    "TitanX Native Engine v0.7",
                    report
                );
            }
        );

        button(
            c,
            "REMOVE RUNTIME",
            this::removeRuntime
        );

        LinearLayout v = card();

        field(
            v,
            "JAVA ELF SCANNER",
            "Scans nested runtime files"
        );

        field(
            v,
            "NATIVE ELF INSPECTOR",
            "C++ ELF header inspection"
        );

        field(
            v,
            "SUPPORTED ARCHITECTURES",
            "ARM64 / ARM32 / x86 / x86-64"
        );

        field(
            v,
            "ZIP EXTRACTION LIMIT",
            "1 GB"
        );

        field(
            v,
            "ENGINE EXECUTION",
            "Not implemented"
        );

        note(
            v,
            "ELF inspection does not verify "
            + "dependencies, compatibility, "
            + "or file trustworthiness."
        );
    }

    private void showReport(
        String heading,
        String report
    ) {
        new AlertDialog.Builder(this)
            .setTitle(heading)
            .setMessage(report)
            .setPositiveButton(
                "OK",
                null
            )
            .setNeutralButton(
                "COPY",
                (d, w) -> copy(report)
            )
            .show();
    }

    private void scanAsync() {
        if (busy) {
            return;
        }

        busy = true;

        new Thread(() -> {
            Scan s = new Scan();

            inspect(
                runtime,
                s,
                0
            );

            StringBuilder result =
                new StringBuilder();

            result.append("Files: ")
                .append(s.files);

            result.append("\nSize: ")
                .append(gb(s.bytes));

            result.append("\nELF files: ")
                .append(s.elf);

            result.append("\nARM64: ")
                .append(s.arm64);

            result.append("\nARM32: ")
                .append(s.arm32);

            result.append("\nx86: ")
                .append(s.x86);

            result.append("\nx86-64: ")
                .append(s.x64);

            result.append("\nOther/unknown: ")
                .append(s.unknown);

            result.append("\n.so filenames: ")
                .append(s.libraries);

            result.append("\nWine filenames: ")
                .append(s.wineNames);

            result.append("\nFEX filenames: ")
                .append(s.fexNames);

            result.append("\nBox filenames: ")
                .append(s.boxNames);

            result.append("\n\nELF samples:\n");

            for (String x : s.samples) {
                result.append(x)
                    .append("\n");
            }

            String report =
                result.toString();

            runOnUiThread(() -> {
                busy = false;

                log(
                    "Java runtime scan completed"
                );

                showReport(
                    "Runtime Scan Report",
                    report
                );
            });
        }).start();
    }

    private void nativeScanAsync() {
        if (busy) {
            return;
        }

        busy = true;

        new Thread(() -> {
            StringBuilder report =
                new StringBuilder();

            File[] files =
                runtime.listFiles();

            int checked = 0;

            if (files != null) {
                for (File f : files) {
                    if (!f.isFile()) {
                        continue;
                    }

                    report.append(
                        f.getName()
                    );

                    report.append("\n");

                    report.append(
                        NativeBridge.inspect(f)
                    );

                    report.append("\n\n");

                    checked++;

                    if (checked >= 20) {
                        break;
                    }
                }
            }

            if (checked == 0) {
                report.append(
                    "No regular files in runtime root.\n"
                    + "Nested folders are not scanned "
                    + "by this native inspection mode."
                );
            }

            String result =
                report.toString();

            runOnUiThread(() -> {
                busy = false;

                log(
                    "Native ELF inspection completed"
                );

                showReport(
                    "Native ELF Report",
                    result
                );
            });
        }).start();
    }

    private void games() {
        title("GAME LIBRARY");

        LinearLayout c = card();

        field(
            c,
            "WINDOWS EXECUTION",
            "Not available"
        );

        field(
            c,
            "WINE / FEX",
            "Not integrated"
        );

        note(
            c,
            "TitanX v0.7 can inspect "
            + "runtime files but cannot "
            + "launch Windows games yet."
        );
    }

    private void settings() {
        title("SETTINGS");

        LinearLayout c = card();

        String[] profiles = {
            "Balanced",
            "Low RAM 8GB",
            "Performance"
        };

        Spinner sp = new Spinner(this);

        ArrayAdapter<String> adapter =
            new ArrayAdapter<>(
                this,
                android.R.layout
                    .simple_spinner_dropdown_item,
                profiles
            );

        sp.setAdapter(adapter);

        sp.setSelection(
            Math.max(
                0,
                Math.min(
                    2,
                    prefs.getInt(
                        "profile",
                        1
                    )
                )
            )
        );

        add(c, sp, 10);

        button(
            c,
            "SAVE PROFILE",
            () -> {
                int n =
                    sp.getSelectedItemPosition();

                prefs.edit()
                    .putInt(
                        "profile",
                        n
                    )
                    .apply();

                log(
                    "Profile saved: "
                    + profiles[n]
                );

                Toast.makeText(
                    this,
                    "Saved",
                    Toast.LENGTH_SHORT
                ).show();
            }
        );

        note(
            c,
            "Profiles are saved preferences only. "
            + "They do not change CPU or GPU "
            + "performance yet."
        );

        title("SESSION LOG");

        LinearLayout l = card();

        add(
            l,
            text(
                logs.toString(),
                12,
                WHITE
            ),
            12
        );

        button(
            l,
            "COPY LOG",
            () -> copy(
                logs.toString()
            )
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
        if (busy) {
            Toast.makeText(
                this,
                "Operation in progress",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        Intent i = new Intent(
            Intent.ACTION_OPEN_DOCUMENT
        );

        i.setType("*/*");

        i.addCategory(
            Intent.CATEGORY_OPENABLE
        );

        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        startActivityForResult(
            i,
            PICK_ZIP
        );
    }

    @Override
    protected void onActivityResult(
        int request,
        int result,
        Intent data
    ) {
        super.onActivityResult(
            request,
            result,
            data
        );

        if (
            request != PICK_ZIP
            || result != RESULT_OK
            || data == null
            || data.getData() == null
        ) {
            return;
        }

        Uri uri = data.getData();

        new AlertDialog.Builder(this)
            .setTitle(
                "Import Runtime ZIP"
            )
            .setMessage(
                "Extract the selected ZIP into "
                + "private app storage?\n\n"
                + "Only use files from "
                + "trusted sources."
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "IMPORT",
                (d, w) -> importZip(uri)
            )
            .show();
    }

    private void importZip(Uri uri) {
        if (busy) {
            return;
        }

        busy = true;
        log("ZIP import started");

        new Thread(() -> {
            File stage = new File(
                getFilesDir(),
                "runtime_staging"
            );

            File backup = new File(
                getFilesDir(),
                "runtime_backup"
            );

            String message;

            try {
                if (
                    !deleteTree(stage)
                    || !deleteTree(backup)
                    || !stage.mkdirs()
                ) {
                    throw new IOException(
                        "Cannot prepare directories"
                    );
                }

                extract(uri, stage);

                boolean old =
                    runtime.exists();

                if (
                    old
                    && !runtime.renameTo(backup)
                ) {
                    throw new IOException(
                        "Cannot back up runtime"
                    );
                }

                if (
                    !stage.renameTo(runtime)
                ) {
                    if (old) {
                        backup.renameTo(runtime);
                    }

                    throw new IOException(
                        "Cannot activate runtime"
                    );
                }

                deleteTree(backup);

                message =
                    "Runtime imported successfully";

            } catch (Exception ex) {
                deleteTree(stage);

                message =
                    "Import failed: "
                    + ex.getMessage();
            }

            final String m = message;

            runOnUiThread(() -> {
                busy = false;

                log(m);

                Toast.makeText(
                    this,
                    m,
                    Toast.LENGTH_LONG
                ).show();

                page("Runtime");
            });
        }).start();
    }

    private void extract(
        Uri uri,
        File dest
    ) throws IOException {

        String base =
            dest.getCanonicalPath()
            + File.separator;

        int entries = 0;
        long bytes = 0;

        InputStream input =
            getContentResolver()
                .openInputStream(uri);

        if (input == null) {
            throw new IOException(
                "Cannot open ZIP"
            );
        }

        try (
            ZipInputStream zip =
                new ZipInputStream(
                    new BufferedInputStream(input)
                )
        ) {
            ZipEntry e;

            byte[] buffer =
                new byte[32768];

            while (
                (e = zip.getNextEntry()) != null
            ) {
                entries++;

                if (entries > MAX_ENTRIES) {
                    throw new IOException(
                        "Too many ZIP entries"
                    );
                }

                String name =
                    e.getName();

                if (
                    name.isEmpty()
                    || name.startsWith("/")
                    || name.startsWith("\\")
                    || name.contains("\\")
                    || name.indexOf('\0') >= 0
                ) {
                    throw new IOException(
                        "Unsafe ZIP entry"
                    );
                }

                File target =
                    new File(dest, name);

                if (
                    !target.getCanonicalPath()
                        .startsWith(base)
                ) {
                    throw new IOException(
                        "ZIP path traversal blocked"
                    );
                }

                if (e.isDirectory()) {
                    if (
                        !target.isDirectory()
                        && !target.mkdirs()
                    ) {
                        throw new IOException(
                            "Cannot create directory"
                        );
                    }
                } else {
                    File parent =
                        target.getParentFile();

                    if (
                        !parent.isDirectory()
                        && !parent.mkdirs()
                    ) {
                        throw new IOException(
                            "Cannot create parent"
                        );
                    }

                    if (target.exists()) {
                        throw new IOException(
                            "Duplicate ZIP entry"
                        );
                    }

                    try (
                        OutputStream out =
                            new BufferedOutputStream(
                                new FileOutputStream(
                                    target
                                )
                            )
                    ) {
                        int n;

                        while (
                            (n = zip.read(buffer)) != -1
                        ) {
                            bytes += n;

                            if (
                                bytes > MAX_BYTES
                            ) {
                                throw new IOException(
                                    "ZIP exceeds 1 GB"
                                );
                            }

                            out.write(
                                buffer,
                                0,
                                n
                            );
                        }
                    }
                }

                zip.closeEntry();
            }
        }

        if (entries == 0) {
            throw new IOException(
                "Empty or invalid ZIP"
            );
        }
    }

    private boolean deleteTree(File f) {
        if (!f.exists()) {
            return true;
        }

        if (f.isDirectory()) {
            File[] children =
                f.listFiles();

            if (children == null) {
                return false;
            }

            for (File child : children) {
                if (!deleteTree(child)) {
                    return false;
                }
            }
        }

        return f.delete();
    }

    private void removeRuntime() {
        if (busy) {
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle(
                "Remove Runtime"
            )
            .setMessage(
                "Delete imported runtime files?"
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "DELETE",
                (d, w) -> {
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
