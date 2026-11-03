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

    private MediaProjectionManager projectionManager;
    private static final int CAPTURE_REQUEST = 1001;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        projectionManager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 35, 24, 24);

        TextView title = new TextView(this);
        title.setText("Saqib Pool Pro");
        title.setTextSize(26);
        layout.addView(title);

        TextView info = new TextView(this);
        info.setText("Live white guide-line extension");
        layout.addView(info);

        Button start = new Button(this);
        start.setText("Start Live Aim");
        start.setOnClickListener(v -> requestCapture());
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop Aim");
        stop.setOnClickListener(v -> stopAll());
        layout.addView(stop);

        setContentView(layout);
    }

    private void requestCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Intent permission = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(permission);

            Toast.makeText(this,
                    "Allow display over other apps, then tap Start Live Aim",
                    Toast.LENGTH_LONG).show();
            return;
        }

        startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                CAPTURE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode,
                                    int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != CAPTURE_REQUEST) return;

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture permission denied",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Intent overlay = new Intent(this, AimOverlayService.class);
            startService(overlay);

            Intent capture = new Intent(this, AimCaptureService.class);
            capture.putExtra(AimCaptureService.EXTRA_RESULT_CODE, resultCode);
            capture.putExtra(AimCaptureService.EXTRA_DATA, data);

            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(capture);
            } else {
                startService(capture);
            }

            Toast.makeText(this,
                    "Live detection started. Opening 8 Ball Pool...",
                    Toast.LENGTH_LONG).show();

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Intent game = getPackageManager()
                        .getLaunchIntentForPackage(
                                "com.miniclip.eightballpool");

                if (game != null) {
                    startActivity(game);
                } else {
                    Toast.makeText(this, "8 Ball Pool not found",
                            Toast.LENGTH_LONG).show();
                }
            }, 1200);

        } catch (Exception e) {
            Toast.makeText(this, "Start error: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopAll() {
        stopService(new Intent(this, AimCaptureService.class));
        stopService(new Intent(this, AimOverlayService.class));

        Toast.makeText(this, "Aim stopped", Toast.LENGTH_SHORT).show();
    }
                                   }
