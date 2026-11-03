
package com.saqib.poolpro;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public class AimOverlayService extends Service {

    private WindowManager windowManager;
    private AimView aimView;
    private BroadcastReceiver receiver;

    @Override
    public void onCreate() {
        super.onCreate();

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        aimView = new AimView(this);

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(aimView, params);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                if (!AimCaptureService.ACTION_AIM.equals(
                        intent.getAction())) {
                    return;
                }

                float x = intent.getFloatExtra("x", -1);
                float y = intent.getFloatExtra("y", -1);
                float angle = intent.getFloatExtra("angle", 0);
                float score = intent.getFloatExtra("score", 0);

                int width = intent.getIntExtra("width", 0);
                int height = intent.getIntExtra("height", 0);

                if (aimView != null &&
                        x >= 0 && y >= 0 &&
                        width > 0 && height > 0) {

                    aimView.setGuide(
                            x, y, angle, width, height, score
                    );
                }
            }
        };

        IntentFilter filter =
                new IntentFilter(AimCaptureService.ACTION_AIM);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    receiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private class AimView extends View {

        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float x = -1;
        private float y = -1;
        private float angle;
        private float score;

        private int sourceWidth = 1;
        private int sourceHeight = 1;

        AimView(Context context) {
            super(context);

            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            outline.setColor(Color.BLACK);
            outline.setStrokeWidth(
                    5 * getResources().getDisplayMetrics().density
            );
            outline.setStrokeCap(Paint.Cap.ROUND);

            line.setColor(Color.WHITE);
            line.setStrokeWidth(
                    2.2f * getResources().getDisplayMetrics().density
            );
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        void setGuide(
                float px,
                float py,
                float a,
                int w,
                int h,
                float s) {

            x = px;
            y = py;
            angle = a;
            sourceWidth = w;
            sourceHeight = h;
            score = s;

            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (x < 0 || y < 0 || score < 16) return;

            float sx = getWidth() / (float) sourceWidth;
            float sy = getHeight() / (float) sourceHeight;

            float startX = x * sx;
            float startY = y * sy;

            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);

            float t = Float.POSITIVE_INFINITY;

            if (dx > 0) {
                t = Math.min(t, (getWidth() - startX) / dx);
            } else if (dx < 0) {
                t = Math.min(t, -startX / dx);
            }

            if (dy > 0) {
                t = Math.min(t, (getHeight() - startY) / dy);
            } else if (dy < 0) {
                t = Math.min(t, -startY / dy);
            }

            if (t <= 0 || Float.isInfinite(t)) return;

            float endX = startX + dx * t;
            float endY = startY + dy * t;

            // Original-style white line with a dark outline.
            canvas.drawLine(
                    startX, startY, endX, endY, outline
            );

            canvas.drawLine(
                    startX, startY, endX, endY, line
            );
        }
    }

    @Override
    public void onDestroy() {

        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }

        if (windowManager != null && aimView != null) {
            try {
                windowManager.removeView(aimView);
            } catch (Exception ignored) {
            }
            aimView = null;
        }

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
