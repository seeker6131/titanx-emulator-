
package com.titanx.emulator;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import android.content.SharedPreferences;

public class MainActivity extends Activity {

    SharedPreferences prefs;
    TextView status;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        prefs = getSharedPreferences("titanx", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 24);
        root.setBackgroundColor(Color.rgb(15, 19, 29));

        TextView title = new TextView(this);
        title.setText("TITANX EMULATOR");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView device = new TextView(this);
        device.setText("ARM64 / Android / GTA V Lite Profile");
        device.setTextColor(Color.LTGRAY);
        device.setGravity(Gravity.CENTER);
        root.addView(device);

        String[] profiles = {
            "Balanced",
            "Low RAM 8GB",
            "Performance"
        };

        Spinner selector = new Spinner(this);
        ArrayAdapter<String> adapter =
            new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                profiles);
        selector.setAdapter(adapter);
        selector.setSelection(prefs.getInt("profile", 1));
        root.addView(selector);

        Button save = new Button(this);
        save.setText("SAVE PROFILE");
        root.addView(save);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(0, 32, 0, 20);
        root.addView(status);

        Button launch = new Button(this);
        launch.setText("CHECK EMULATOR RUNTIME");
        root.addView(launch);

        save.setOnClickListener(v -> {
            int selected = selector.getSelectedItemPosition();
            prefs.edit().putInt("profile", selected).apply();
            status.setText("Profile saved: " + profiles[selected]);
        });

        launch.setOnClickListener(v -> {
            status.setText(
                "Wine: Not installed\n" +
                "FEX / Box64: Not installed\n" +
                "DXVK / Turnip: Not installed\n\n" +
                "Runtime integration is required."
            );
        });

        status.setText("TitanX v0.1 - Launcher prototype");
        setContentView(root);
    }
}
