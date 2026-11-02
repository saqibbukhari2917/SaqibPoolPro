
package com.saqib.poolpro;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import android.media.projection.*;

public class MainActivity extends Activity {

    WindowManager wm;
    View bubble;
    TextView bubbleText, powerText;
    SeekBar powerBar;
    int power = 50;

    MediaProjectionManager projectionManager;
    MediaProjection mediaProjection;

    static final int SCREEN_CAPTURE_REQUEST = 1001;

    public void onCreate(Bundle b) {
        super.onCreate(b);

        projectionManager =
            (MediaProjectionManager)getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            );

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(1);
        layout.setPadding(24, 35, 24, 24);

        TextView title = new TextView(this);
        title.setText("Saqib Pool Pro");
        title.setTextSize(26);
        layout.addView(title);

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

        Button capture = new Button(this);
        capture.setText("Allow Screen Capture");
        capture.setOnClickListener(v -> {
            Intent intent =
                projectionManager.createScreenCaptureIntent();
            startActivityForResult(
                intent, SCREEN_CAPTURE_REQUEST
            );
        });
        layout.addView(capture);

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

    protected void onActivityResult(
        int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjection =
                    projectionManager.getMediaProjection(
                        resultCode, data
                    );

                Toast.makeText(
                    this,
                    "Screen capture permission granted!",
                    Toast.LENGTH_LONG
                ).show();
            } else {
                Toast.makeText(
                    this,
                    "Screen capture permission denied",
                    Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    void showBubble() {
        if (bubble != null) return;

        wm = (WindowManager)getSystemService(WINDOW_SERVICE);

        bubbleText = new TextView(this);
        bubbleText.setTextColor(Color.WHITE);
        bubbleText.setGravity(Gravity.CENTER);
        bubbleText.setPadding(18, 14, 18, 14);
        bubbleText.setBackgroundColor(Color.rgb(20,110,70));

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

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception e) {}
            bubble = null;
        }
    }
    }
