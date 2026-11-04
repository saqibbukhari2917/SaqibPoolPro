package com.saqib.poolpro;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int OVERLAY_REQUEST = 1002;
    private static final int CAPTURE_REQUEST = 1001;

    private MediaProjectionManager projectionManager;
    private boolean waitingForOverlay = false;

    private static final String GAME_PACKAGE =
            "com.miniclip.eightballpool";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        showMainScreen();
    }

    private void showMainScreen() {

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(30, 30, 30, 30);

        TextView title = new TextView(this);
        title.setText("Saqib Pool Pro");
        title.setTextSize(27);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);

        layout.addView(title);

        TextView info = new TextView(this);
        info.setText("Live 8 Ball Pool Aim Detection");
        info.setTextSize(16);
        info.setGravity(Gravity.CENTER);

        layout.addView(info);

        Button start = new Button(this);
        start.setText("Start Live Aim");
        start.setOnClickListener(v -> startAim());

        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop Aim");
        stop.setOnClickListener(v -> stopAll());

        layout.addView(stop);

        setContentView(layout);
    }

    private void startAim() {

        if (!Settings.canDrawOverlays(this)) {

            waitingForOverlay = true;

            Intent permission = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));

            startActivityForResult(
                    permission, OVERLAY_REQUEST);

            Toast.makeText(this,
                    "Allow Display over other apps",
                    Toast.LENGTH_LONG).show();

            return;
        }

        requestScreenCapture();
    }

    private void requestScreenCapture() {

        startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                CAPTURE_REQUEST);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OVERLAY_REQUEST) {

            if (Settings.canDrawOverlays(this)) {
                waitingForOverlay = false;
                requestScreenCapture();
            } else {
                Toast.makeText(this,
                        "Overlay permission is required",
                        Toast.LENGTH_LONG).show();
            }

            return;
        }

        if (requestCode != CAPTURE_REQUEST) return;

        if (resultCode != RESULT_OK || data == null) {

            Toast.makeText(this,
                    "Screen capture permission denied",
                    Toast.LENGTH_LONG).show();

            return;
        }

        try {

            Intent overlay = new Intent(
                    this, AimOverlayService.class);

            startService(overlay);

            Intent capture = new Intent(
                    this, AimCaptureService.class);

            capture.putExtra(
                    AimCaptureService.EXTRA_RESULT_CODE,
                    resultCode);

            capture.putExtra(
                    AimCaptureService.EXTRA_DATA,
                    data);

            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(capture);
            } else {
                startService(capture);
            }

            Toast.makeText(this,
                    "Aim started. Opening 8 Ball Pool...",
                    Toast.LENGTH_LONG).show();

            new Handler(Looper.getMainLooper())
                    .postDelayed(() -> {

                Intent game = getPackageManager()
                        .getLaunchIntentForPackage(GAME_PACKAGE);

                if (game != null) {
                    game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(game);
                } else {
                    Toast.makeText(this,
                            "8 Ball Pool is not installed",
                            Toast.LENGTH_LONG).show();
                }

            }, 1200);

        } catch (Exception e) {

            Toast.makeText(this,
                    "Start error: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopAll() {

        stopService(new Intent(
                this, AimCaptureService.class));

        stopService(new Intent(
                this, AimOverlayService.class));

        Toast.makeText(this,
                "Aim stopped",
                Toast.LENGTH_SHORT).show();
    }
                         }
