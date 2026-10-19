
package com.titanx.emulator;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView details;
    private TextView logView;
    private SharedPreferences prefs;
    private final StringBuilder logs = new StringBuilder();

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

        if (logView != null) {
            logView.setText(logs.toString());
        }
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setPadding(12, 12, 12, 12);
        return view;
    }

    private Button button(String text, LinearLayout root) {
        Button b = new Button(this);
        b.setText(text);
        root.addView(b);
        return b;
    }

    private String diagnostics() {
        ActivityManager manager =
            (ActivityManager) getSystemService(
                Context.ACTIVITY_SERVICE
            );

        ActivityManager.MemoryInfo memory =
            new ActivityManager.MemoryInfo();

        manager.getMemoryInfo(memory);

        StatFs storage = new StatFs(
            Environment.getDataDirectory().getAbsolutePath()
        );

        boolean vulkan = getPackageManager().hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
        );

        return "Device: " + Build.MANUFACTURER + " " + Build.MODEL
            + "\nAndroid: " + Build.VERSION.RELEASE
            + " (API " + Build.VERSION.SDK_INT + ")"
            + "\nABIs: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS)
            + "\nRAM total: " + gb(memory.totalMem)
            + "\nRAM available: " + gb(memory.availMem)
            + "\nRAM low: " + memory.lowMemory
            + "\nInternal storage: "
            + gb(storage.getAvailableBytes())
            + " free / " + gb(storage.getTotalBytes())
            + "\nVulkan hardware feature: "
            + (vulkan ? "Advertised" : "Not advertised")
            + "\nWine: NOT INSTALLED"
            + "\nFEX / Box64: NOT INSTALLED"
            + "\nTurnip / DXVK: NOT INSTALLED";
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        prefs = getSharedPreferences("titanx", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.rgb(14, 20, 32));

        scroll.addView(root);

        TextView title = label("TITANX EMULATOR v0.2", 24);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        root.addView(label(
            "Device Diagnostics & Runtime Readiness", 14
        ));

        String[] profiles = {
            "Balanced",
            "Low RAM 8GB",
            "Performance"
        };

        Spinner spinner = new Spinner(this);

        ArrayAdapter<String> adapter =
            new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                profiles
            );

        spinner.setAdapter(adapter);

        spinner.setSelection(
            Math.max(0, Math.min(2, prefs.getInt("profile", 1)))
        );

        root.addView(spinner);

        button("SAVE PROFILE", root).setOnClickListener(v -> {
            prefs.edit()
                .putInt("profile", spinner.getSelectedItemPosition())
                .apply();

            log("Saved profile: "
                + profiles[spinner.getSelectedItemPosition()]);
        });

        details = label("", 15);
        root.addView(details);

        button("REFRESH DEVICE DIAGNOSTICS", root)
            .setOnClickListener(v -> refresh());

        button("COPY DIAGNOSTICS", root)
            .setOnClickListener(v -> {
                android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);

                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "TitanX diagnostics",
                        details.getText()
                    )
                );

                log("Diagnostics copied to clipboard");
            });

        root.addView(label("SESSION LOG", 18));

        logView = label("", 13);
        root.addView(logView);

        button("CLEAR LOG", root).setOnClickListener(v -> {
            logs.setLength(0);
            log("Log cleared");
        });

        setContentView(scroll);

        log("TitanX v0.2 started.");
        log("Windows runtime is not integrated.");

        refresh();
    }

    private void refresh() {
        details.setText(diagnostics());
        log("Device diagnostics refreshed");
    }
}
