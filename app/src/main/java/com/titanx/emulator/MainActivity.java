
package com.titanx.emulator;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.widget.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final int PICK_ZIP = 1001;
    private static final long MAX_BYTES =
        1024L * 1024L * 1024L;
    private static final int MAX_FILES = 10000;

    private TextView details;
    private TextView runtimeStatus;
    private TextView logView;

    private SharedPreferences prefs;
    private File runtimeDir;

    private final StringBuilder logs =
        new StringBuilder();

    private volatile boolean busy = false;

    private String gb(long bytes) {
        return String.format(
            Locale.US,
            "%.2f GB",
            bytes / 1073741824.0
        );
    }

    private void log(String message) {
        String time = new SimpleDateFormat(
            "HH:mm:ss",
            Locale.US
        ).format(new Date());

        logs.append(time)
            .append("  ")
            .append(message)
            .append("\n");

        if (logs.length() > 12000) {
            logs.delete(
                0,
                logs.length() - 10000
            );
        }

        if (logView != null) {
            logView.setText(logs.toString());
        }
    }

    private TextView label(
        String text,
        int size
    ) {
        TextView view = new TextView(this);

        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setPadding(12, 12, 12, 12);

        return view;
    }

    private Button button(
        String text,
        LinearLayout root
    ) {
        Button b = new Button(this);
        b.setText(text);
        root.addView(b);
        return b;
    }

    private String diagnostics() {

        ActivityManager manager =
            (ActivityManager)
            getSystemService(
                Context.ACTIVITY_SERVICE
            );

        ActivityManager.MemoryInfo memory =
            new ActivityManager.MemoryInfo();

        manager.getMemoryInfo(memory);

        StatFs storage = new StatFs(
            Environment.getDataDirectory()
                .getAbsolutePath()
        );

        boolean vulkan =
            getPackageManager().hasSystemFeature(
                PackageManager
                    .FEATURE_VULKAN_HARDWARE_VERSION
            );

        return
            "Device: "
            + Build.MANUFACTURER
            + " "
            + Build.MODEL

            + "\nAndroid: "
            + Build.VERSION.RELEASE

            + "\nAPI: "
            + Build.VERSION.SDK_INT

            + "\nCPU ABIs: "
            + Arrays.toString(
                Build.SUPPORTED_ABIS
            )

            + "\nRAM Total: "
            + gb(memory.totalMem)

            + "\nRAM Available: "
            + gb(memory.availMem)

            + "\nLow Memory: "
            + memory.lowMemory

            + "\nStorage Free: "
            + gb(storage.getAvailableBytes())

            + "\nStorage Total: "
            + gb(storage.getTotalBytes())

            + "\nVulkan Feature: "
            + (vulkan
                ? "Advertised"
                : "Not Advertised");
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        prefs = getSharedPreferences(
            "titanx",
            MODE_PRIVATE
        );

        runtimeDir = new File(
            getFilesDir(),
            "runtime"
        );

        ScrollView scroll =
            new ScrollView(this);

        scroll.setFillViewport(true);

        LinearLayout root =
            new LinearLayout(this);

        root.setOrientation(
            LinearLayout.VERTICAL
        );

        root.setPadding(
            24, 24, 24, 24
        );

        root.setBackgroundColor(
            Color.rgb(14, 20, 32)
        );

        scroll.addView(root);

        TextView title = label(
            "TITANX EMULATOR v0.3.1",
            24
        );

        title.setGravity(
            Gravity.CENTER
        );

        root.addView(title);

        root.addView(label(
            "Device Diagnostics & Runtime Manager",
            14
        ));

        root.addView(label(
            "PERFORMANCE PROFILE",
            18
        ));

        String[] profiles = {
            "Balanced",
            "Low RAM 8GB",
            "Performance"
        };

        Spinner spinner =
            new Spinner(this);

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
                    prefs.getInt(
                        "profile", 1
                    )
                )
            )
        );

        root.addView(spinner);

        button(
            "SAVE PROFILE",
            root
        ).setOnClickListener(v -> {

            int selected =
                spinner.getSelectedItemPosition();

            prefs.edit()
                .putInt(
                    "profile",
                    selected
                )
                .apply();

            log(
                "Profile saved: "
                + profiles[selected]
            );
        });

        root.addView(label(
            "DEVICE INFORMATION",
            18
        ));

        details = label("", 15);
        root.addView(details);

        button(
            "REFRESH DIAGNOSTICS",
            root
        ).setOnClickListener(
            v -> refresh()
        );

        button(
            "COPY DIAGNOSTICS",
            root
        ).setOnClickListener(v -> {

            copyText(
                "TitanX Diagnostics",
                details.getText().toString()
            );

            log("Diagnostics copied");
        });

        root.addView(label(
            "RUNTIME MANAGER",
            20
        ));

        runtimeStatus = label("", 15);
        root.addView(runtimeStatus);

        button(
            "IMPORT RUNTIME ZIP",
            root
        ).setOnClickListener(
            v -> pickZip()
        );

        button(
            "REFRESH RUNTIME STATUS",
            root
        ).setOnClickListener(
            v -> updateRuntime()
        );

        button(
            "REMOVE INSTALLED RUNTIME",
            root
        ).setOnClickListener(
            v -> confirmRemove()
        );

        root.addView(label(
            "SESSION LOG",
            18
        ));

        logView = label("", 13);
        root.addView(logView);

        button(
            "COPY SESSION LOG",
            root
        ).setOnClickListener(v -> {

            copyText(
                "TitanX Session Log",
                logs.toString()
            );

            Toast.makeText(
                this,
                "Log copied",
                Toast.LENGTH_SHORT
            ).show();
        });

        button(
            "CLEAR LOG",
            root
        ).setOnClickListener(v -> {

            logs.setLength(0);
            log("Log cleared");
        });

        setContentView(scroll);

        log("TitanX v0.3.1 started");
        log("Native Windows runtime not integrated");

        refresh();
        updateRuntime();
    }

    private void copyText(
        String title,
        String value
    ) {
        android.content.ClipboardManager cb =
            (android.content.ClipboardManager)
            getSystemService(
                CLIPBOARD_SERVICE
            );

        cb.setPrimaryClip(
            android.content.ClipData
                .newPlainText(
                    title,
                    value
                )
        );
    }

    private void refresh() {
        details.setText(
            diagnostics()
        );

        log("Diagnostics refreshed");
    }

    private void updateRuntime() {

        if (!runtimeDir.exists()) {

            runtimeStatus.setText(
                "Runtime: NOT INSTALLED"
                + "\nWine: NOT CONFIGURED"
                + "\nFEX / Box64: NOT CONFIGURED"
                + "\nDXVK: NOT CONFIGURED"
            );

            return;
        }

        long[] stats = scan(runtimeDir);

        runtimeStatus.setText(
            "Runtime files: PRESENT"
            + "\nFiles: " + stats[0]
            + "\nSize: " + gb(stats[1])
            + "\nStorage: Private App Storage"
            + "\nExecution: NOT INTEGRATED"
        );
    }

    private long[] scan(File dir) {

        long count = 0;
        long size = 0;

        File[] files = dir.listFiles();

        if (files == null) {
            return new long[]{0, 0};
        }

        for (File f : files) {

            if (f.isDirectory()) {

                long[] child = scan(f);

                count += child[0];
                size += child[1];

            } else {

                count++;
                size += f.length();
            }
        }

        return new long[]{count, size};
    }

    private void pickZip() {

        if (busy) {
            Toast.makeText(
                this,
                "Runtime operation in progress",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

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
            intent,
            PICK_ZIP
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

        if (
            requestCode != PICK_ZIP
            || resultCode != RESULT_OK
            || data == null
        ) {
            return;
        }

        Uri uri = data.getData();

        if (uri == null) {
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Import Runtime ZIP")
            .setMessage(
                "Extract this ZIP into TitanX "
                + "private storage?\n\n"
                + "Existing runtime files will "
                + "be replaced after extraction.\n\n"
                + "Only import trusted files."
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "IMPORT",
                (dialog, which) ->
                    importZip(uri)
            )
            .show();
    }

    private void importZip(Uri uri) {

        if (busy) {
            log("Another operation is running");
            return;
        }

        busy = true;

        log("Starting runtime ZIP import");

        File staging = new File(
            getFilesDir(),
            "runtime_staging"
        );

        new Thread(() -> {

            String result;

            try {

                if (!deleteTree(staging)) {
                    throw new IOException(
                        "Cannot clear staging"
                    );
                }

                if (!staging.mkdirs()) {
                    throw new IOException(
                        "Cannot create staging"
                    );
                }

                extractZip(
                    uri,
                    staging
                );

                if (!deleteTree(runtimeDir)) {
                    throw new IOException(
                        "Cannot remove old runtime"
                    );
                }

                if (!staging.renameTo(runtimeDir)) {
                    throw new IOException(
                        "Cannot activate runtime"
                    );
                }

                result =
                    "Runtime ZIP imported successfully";

            } catch (Exception e) {

                deleteTree(staging);

                result =
                    "Import failed: "
                    + e.getMessage();
            }

            final String message = result;

            runOnUiThread(() -> {

                busy = false;

                log(message);
                updateRuntime();

                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
                ).show();
            });

        }).start();
    }

    private void extractZip(
        Uri uri,
        File destination
    ) throws IOException {

        String base =
            destination.getCanonicalPath()
            + File.separator;

        long totalBytes = 0;
        int count = 0;

        InputStream input =
            getContentResolver()
                .openInputStream(uri);

        if (input == null) {
            throw new IOException(
                "Cannot open selected file"
            );
        }

        try (
            InputStream source = input;
            ZipInputStream zip =
                new ZipInputStream(
                    new BufferedInputStream(source)
                )
        ) {

            ZipEntry entry;

            byte[] buffer =
                new byte[32768];

            while (
                (entry = zip.getNextEntry()) != null
            ) {

                count++;

                if (count > MAX_FILES) {
                    throw new IOException(
                        "Too many ZIP entries"
                    );
                }

                String name =
                    entry.getName();

                if (
                    name.startsWith("/")
                    || name.startsWith("\\")
                    || name.contains("\\")
                    || name.indexOf('\0') >= 0
                ) {
                    throw new IOException(
                        "Unsafe ZIP path"
                    );
                }

                File target = new File(
                    destination,
                    name
                );

                String canonical =
                    target.getCanonicalPath();

                if (
                    !canonical.startsWith(base)
                ) {
                    throw new IOException(
                        "ZIP path traversal blocked"
                    );
                }

                if (entry.isDirectory()) {

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

                    try (
                        OutputStream output =
                            new BufferedOutputStream(
                                new FileOutputStream(target)
                            )
                    ) {

                        int read;

                        while (
                            (read = zip.read(buffer)) != -1
                        ) {

                            totalBytes += read;

                            if (
                                totalBytes > MAX_BYTES
                            ) {
                                throw new IOException(
                                    "ZIP exceeds 1 GB limit"
                                );
                            }

                            output.write(
                                buffer,
                                0,
                                read
                            );
                        }
                    }
                }

                zip.closeEntry();
            }
        }

        if (count == 0) {
            throw new IOException(
                "ZIP empty or invalid"
            );
        }
    }

    private boolean deleteTree(File file) {

        if (!file.exists()) {
            return true;
        }

        if (file.isDirectory()) {

            File[] children =
                file.listFiles();

            if (children == null) {
                return false;
            }

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
                this,
                "Runtime operation in progress",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Remove Runtime")
            .setMessage(
                "Delete all imported runtime files?"
            )
            .setNegativeButton(
                "CANCEL",
                null
            )
            .setPositiveButton(
                "DELETE",
                (dialog, which) -> {

                    busy = true;

                    new Thread(() -> {

                        boolean success =
                            deleteTree(runtimeDir);

                        runOnUiThread(() -> {

                            busy = false;

                            log(
                                success
                                ? "Runtime removed"
                                : "Runtime removal failed"
                            );

                            updateRuntime();
                        });

                    }).start();
                }
            )
            .show();
    }
}
