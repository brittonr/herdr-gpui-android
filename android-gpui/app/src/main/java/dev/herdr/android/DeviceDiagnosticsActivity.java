package dev.herdr.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.TextView;
import org.json.JSONException;

/** Explicit, foreground-only diagnostics preview. No remote endpoint is created. */
public final class DeviceDiagnosticsActivity extends Activity {
    private TextView output;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(32, 32, 32, 32);
        column.setBackgroundColor(Color.rgb(16, 18, 22));
        TextView description = new TextView(this);
        description.setText("Local diagnostics only. No remote access, root, or background monitoring. Tap below to read device information.");
        description.setTextColor(Color.WHITE);
        column.addView(description);
        CheckBox enabled = new CheckBox(this);
        enabled.setText("Enable local diagnostics for this screen");
        enabled.setTextColor(Color.WHITE);
        column.addView(enabled);
        Button read = new Button(this);
        read.setText("Read diagnostics");
        read.setEnabled(false);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            read.setEnabled(DiagnosticsPolicy.canRead(checked));
            if (!checked) output.setText("");
        });
        column.addView(read);
        output = new TextView(this);
        output.setTextColor(Color.WHITE);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        column.addView(scroll);
        setContentView(column);
        read.setOnClickListener(v -> {
            if (!DiagnosticsPolicy.canRead(enabled.isChecked())) return;
            try {
                output.setText(DeviceDiagnostics.snapshot(this).toString(2));
            } catch (JSONException | RuntimeException failure) {
                output.setText("Unable to read device diagnostics.");
            }
        });
    }

    @Override protected void onStop() {
        output.setText("");
        super.onStop();
    }
}
