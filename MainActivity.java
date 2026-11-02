package com.saqib.poolpro;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.provider.Settings;
import android.net.Uri;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
    WindowManager wm;
    View aimLine;

    public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(1);
        layout.setPadding(24, 40, 24, 24);

        TextView title = new TextView(this);
        title.setText("Saqib Pool Pro - Aim Test");
        title.setTextSize(24);
        layout.addView(title);

        TextView powerText = new TextView(this);
        powerText.setText("Power: 50%");
        layout.addView(powerText);

        SeekBar power = new SeekBar(this);
        power.setMax(100);
        power.setProgress(50);
        power.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                powerText.setText("Power: " + p + "%");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        layout.addView(power);

        Button show = new Button(this);
        show.setText("Show Aim Line");
        show.setOnClickListener(v -> showAimLine());
        layout.addView(show);

        Button hide = new Button(this);
        hide.setText("Hide Aim Line");
        hide.setOnClickListener(v -> hideAimLine());
        layout.addView(hide);

        setContentView(layout);
    }

    void showAimLine() {
        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        }

        if (aimLine != null) return;

        wm = (WindowManager)getSystemService(WINDOW_SERVICE);

        aimLine = new View(this) {
            Paint paint = new Paint(3);

            protected void onDraw(Canvas c) {
                super.onDraw(c);
                paint.setColor(Color.GREEN);
                paint.setStrokeWidth(6);
                paint.setStyle(Paint.Style.STROKE);
                paint.setPathEffect(new DashPathEffect(
                    new float[]{18, 12}, 0));

                float x = getWidth() / 2f;
                float y1 = getHeight() * 0.88f;
                float y2 = getHeight() * 0.18f;

                c.drawLine(x, y1, x, y2, paint);

                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y2, 9, paint);
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
                PixelFormat.TRANSLUCENT
            );

        wm.addView(aimLine, p);
    }

    void hideAimLine() {
        if (aimLine != null && wm != null) {
            wm.removeView(aimLine);
            aimLine = null;
        }
    }

    protected void onDestroy() {
        hideAimLine();
        super.onDestroy();
    }
}
