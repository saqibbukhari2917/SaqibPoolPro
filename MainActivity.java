
package com.saqib.poolpro;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.graphics.*;

public class MainActivity extends Activity {

    WindowManager wm;
    View bubble;
    TextView bubbleText, powerText;
    SeekBar powerBar;
    int power = 50;

    public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(1);
        layout.setPadding(24, 35, 24, 24);

        TextView title = new TextView(this);
        title.setText("Saqib Pool Pro");
        title.setTextSize(26);
        layout.addView(title);

        TextView info = new TextView(this);
        info.setText("Shot Power / Speed Setting");
        info.setTextSize(18);
        layout.addView(info);

        powerText = new TextView(this);
        powerText.setText("Power: 50%");
        powerText.setTextSize(22);
        layout.addView(powerText);

        powerBar = new SeekBar(this);
        powerBar.setMax(100);
        powerBar.setProgress(50);
        layout.addView(powerBar);

        powerBar.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(
                    SeekBar s, int value, boolean user) {
                    power = value;
                    powerText.setText("Power: " + power + "%");
                    updateBubble();
                }
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) {}
            }
        );

        Button show = new Button(this);
        show.setText("Show Floating Button");
        show.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent i = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
                );
                startActivity(i);
            } else {
                showBubble();
            }
        });

        layout.addView(show);
        setContentView(layout);
    }

    void showBubble() {
        if (bubble != null) return;

        wm = (WindowManager)getSystemService(WINDOW_SERVICE);

        bubbleText = new TextView(this);
        bubbleText.setTextColor(Color.WHITE);
        bubbleText.setGravity(Gravity.CENTER);
        bubbleText.setPadding(18, 14, 18, 14);
        bubbleText.setBackgroundColor(Color.rgb(20, 110, 70));

        updateBubble();

        int type = Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p =
            new WindowManager.LayoutParams(
                -2, -2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            );

        p.gravity = Gravity.TOP | Gravity.END;
        p.x = 25;
        p.y = 200;

        bubble = bubbleText;
        wm.addView(bubble, p);
    }

    void updateBubble() {
        if (bubbleText != null) {
            bubbleText.setText(
                "POOL PRO\nPower: " + power + "%"
            );
        }
    }

    protected void onDestroy() {
        super.onDestroy();
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception e) {}
            bubble = null;
        }
    }
}
