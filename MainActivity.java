
package com.saqib.poolpro;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.media.projection.MediaProjectionManager;
import android.provider.Settings;
import android.net.Uri;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {

    WindowManager wm;
    TextView bubble;
    MediaProjectionManager projectionManager;

    int powerValue = 50;
    boolean aimVisible = false;

    static final int CAPTURE_REQUEST = 1001;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        projectionManager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(1);
        layout.setPadding(24, 35, 24, 24);

        TextView title = new TextView(this);
        title.setText("Saqib Pool Pro");
        title.setTextSize(26);
        layout.addView(title);

        TextView powerText = new TextView(this);
        powerText.setText("Power: 50%");
        layout.addView(powerText);

        SeekBar power = new SeekBar(this);
        power.setMax(100);
        power.setProgress(50);

        power.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(
                    SeekBar s, int value, boolean user) {

                powerValue = value;
                powerText.setText("Power: " + value + "%");
                updateBubble();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });

        layout.addView(power);

        Button capture = new Button(this);
        capture.setText("Start Ball Detection");

        capture.setOnClickListener(v -> startCapturePermission());

        layout.addView(capture);

        Button floating = new Button(this);
        floating.setText("Show Floating Button");

        floating.setOnClickListener(v -> showBubble());

        layout.addView(floating);

        Button showAim = new Button(this);
        showAim.setText("Show Circle Aim");

        showAim.setOnClickListener(v -> showAimLine());

        layout.addView(showAim);

        Button hideAim = new Button(this);
        hideAim.setText("Hide Circle Aim");

        hideAim.setOnClickListener(v -> hideAimLine());

        layout.addView(hideAim);

        Button stop = new Button(this);
        stop.setText("Stop Detection");

        stop.setOnClickListener(v -> stopDetection());

        layout.addView(stop);

        setContentView(layout);
    }

    private void startCapturePermission() {

        if (!Settings.canDrawOverlays(this)) {

            Intent permission = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );

            startActivity(permission);

            Toast.makeText(this,
                    "Allow Display Over Other Apps, then tap Start again",
                    Toast.LENGTH_LONG).show();

            return;
        }

        Intent intent =
                projectionManager.createScreenCaptureIntent();

        startActivityForResult(
                intent, CAPTURE_REQUEST);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode, resultCode, data);

        if (requestCode != CAPTURE_REQUEST) return;

        if (resultCode != RESULT_OK || data == null) {

            Toast.makeText(this,
                    "Screen capture permission denied",
                    Toast.LENGTH_LONG).show();

            return;
        }

        try {

            // Start circle overlay service
            showAimLine();

            // Start screen capture service
            Intent serviceIntent =
                    new Intent(this, AimCaptureService.class);

            serviceIntent.putExtra(
                    AimCaptureService.EXTRA_RESULT_CODE,
                    resultCode);

            serviceIntent.putExtra(
                    AimCaptureService.EXTRA_DATA,
                    data);

            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            Toast.makeText(this,
                    "Ball detection started",
                    Toast.LENGTH_LONG).show();

            new Handler(Looper.getMainLooper())
                    .postDelayed(() -> {

                Intent game = getPackageManager()
                        .getLaunchIntentForPackage(
                                "com.miniclip.eightballpool");

                if (game != null) {
                    startActivity(game);
                } else {
                    Toast.makeText(this,
                            "8 Ball Pool not found",
                            Toast.LENGTH_LONG).show();
                }

            }, 1200);

        } catch (Exception e) {

            Toast.makeText(this,
                    "Error: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    void showBubble() {

        if (!Settings.canDrawOverlays(this)) {

            startOverlayPermission();

            return;
        }

        if (bubble != null) return;

        wm = (WindowManager)
                getSystemService(WINDOW_SERVICE);

        bubble = new TextView(this);

        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(14);
        bubble.setGravity(Gravity.CENTER);
        bubble.setPadding(18, 14, 18, 14);

        bubble.setBackgroundColor(
                Color.rgb(20, 110, 70));

        updateBubble();

        bubble.setOnClickListener(v -> {

            if (aimVisible) {
                hideAimLine();
            } else {
                showAimLine();
            }
        });

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                );

        p.gravity = Gravity.TOP | Gravity.END;
        p.x = 25;
        p.y = 200;

        wm.addView(bubble, p);
    }

    void updateBubble() {

        if (bubble != null) {
            bubble.setText(
                    "POOL PRO\nPower " + powerValue + "%");
        }
    }

    void showAimLine() {

        if (!Settings.canDrawOverlays(this)) {
            startOverlayPermission();
            return;
        }

        if (aimVisible) return;

        try {

            Intent intent = new Intent(
                    this, AimOverlayService.class);

            startService(intent);

            aimVisible = true;

        } catch (Exception e) {

            Toast.makeText(this,
                    "Overlay error: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    void hideAimLine() {

        Intent intent = new Intent(
                this, AimOverlayService.class);

        stopService(intent);

        aimVisible = false;
    }

    private void startOverlayPermission() {

        Intent permission = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );

        startActivity(permission);
    }

    void stopDetection() {

        stopService(new Intent(
                this, AimCaptureService.class));

        hideAimLine();

        Toast.makeText(this,
                "Detection stopped",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {

        if (bubble != null && wm != null) {

            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {}

            bubble = null;
        }

        // Do not stop capture here:
        // It should continue when the game opens.

        super.onDestroy();
    }
}
