
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
    View aimLine;
    MediaProjectionManager projectionManager;

    int powerValue = 50;
    boolean aimVisible = false;

    float ballX = -1;
    float ballY = -1;
    int screenW = 1;
    int screenH = 1;

    static final int CAPTURE_REQUEST = 1001;

    private final BroadcastReceiver ballReceiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ballX = intent.getFloatExtra("x", -1);
            ballY = intent.getFloatExtra("y", -1);
            screenW = intent.getIntExtra("width", 1);
            screenH = intent.getIntExtra("height", 1);

            if (aimLine != null) {
                aimLine.invalidate();
            }
        }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        projectionManager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);

        IntentFilter filter =
                new IntentFilter(AimCaptureService.ACTION_BALL);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ballReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ballReceiver, filter);
        }

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
            public void onProgressChanged(
                    SeekBar s, int value, boolean user) {
                powerValue = value;
                powerText.setText("Power: " + value + "%");
                updateBubble();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        layout.addView(power);

        Button capture = new Button(this);
        capture.setText("Allow Screen Capture");
        capture.setOnClickListener(v -> {
            Intent intent =
                    projectionManager.createScreenCaptureIntent();
            startActivityForResult(intent, CAPTURE_REQUEST);
        });
        layout.addView(capture);

        Button floating = new Button(this);
        floating.setText("Show Floating Button");
        floating.setOnClickListener(v -> showBubble());
        layout.addView(floating);

        Button showAim = new Button(this);
        showAim.setText("Show Aim Line");
        showAim.setOnClickListener(v -> showAimLine());
        layout.addView(showAim);

        Button hideAim = new Button(this);
        hideAim.setText("Hide Aim Line");
        hideAim.setOnClickListener(v -> hideAimLine());
        layout.addView(hideAim);

        setContentView(layout);
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != CAPTURE_REQUEST) return;

        if (resultCode == RESULT_OK && data != null) {

            Intent serviceIntent =
                    new Intent(this, AimCaptureService.class);

            serviceIntent.putExtra(
                    AimCaptureService.EXTRA_RESULT_CODE, resultCode);

            serviceIntent.putExtra(
                    AimCaptureService.EXTRA_DATA, data);

            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                Toast.makeText(this,
                        "Screen capture service started",
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
                }, 1000);

            } catch (Exception e) {
                Toast.makeText(this,
                        "Capture error: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }

        } else {
            Toast.makeText(this,
                    "Screen capture permission denied",
                    Toast.LENGTH_LONG).show();
        }
    }

    void showBubble() {

        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        }

        if (bubble != null) return;

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        bubble = new TextView(this);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(14);
        bubble.setGravity(Gravity.CENTER);
        bubble.setPadding(18, 14, 18, 14);
        bubble.setBackgroundColor(Color.rgb(20, 110, 70));

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
                        PixelFormat.TRANSLUCENT);

        p.gravity = Gravity.TOP | Gravity.END;
        p.x = 25;
        p.y = 200;

        wm.addView(bubble, p);
    }

    void updateBubble() {
        if (bubble != null) {
            bubble.setText("POOL PRO\nPower " + powerValue + "%");
        }
    }

    void showAimLine() {

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Allow overlay permission first",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (aimLine != null) return;

        if (wm == null) {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        aimLine = new View(this) {
            Paint paint = new Paint(3);

            @Override
            protected void onDraw(Canvas c) {
                super.onDraw(c);

                if (ballX < 0 || ballY < 0) {
                    paint.setColor(Color.YELLOW);
                    paint.setTextSize(30);
                    c.drawText(
                            "Searching Cue Ball...",
                            40, 60, paint);
                    return;
                }

                float x = ballX * getWidth() / screenW;
                float y = ballY * getHeight() / screenH;

                paint.setColor(Color.GREEN);
                paint.setStrokeWidth(6);
                paint.setStyle(Paint.Style.STROKE);
                paint.setPathEffect(
                        new DashPathEffect(
                                new float[]{18, 12}, 0));

                c.drawLine(x, y, x, 20, paint);

                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5);
                c.drawCircle(x, y, 17, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(25);
                c.drawText("Cue Ball", x + 22, y - 15, paint);
            }
        };

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);

        wm.addView(aimLine, p);
        aimVisible = true;
    }

    void hideAimLine() {

        if (aimLine != null && wm != null) {
            try {
                wm.removeView(aimLine);
            } catch (Exception e) {}

            aimLine = null;
        }

        aimVisible = false;
    }

    @Override
    protected void onDestroy() {

        hideAimLine();

        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception e) {}
            bubble = null;
        }

        try {
            unregisterReceiver(ballReceiver);
        } catch (Exception e) {}

        super.onDestroy();
    }
}
