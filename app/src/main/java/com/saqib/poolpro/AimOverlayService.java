
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

                int left = intent.getIntExtra("left", 0);
                int top = intent.getIntExtra("top", 0);
                int right = intent.getIntExtra("right", 0);
                int bottom = intent.getIntExtra("bottom", 0);

                if (aimView != null &&
                        x >= 0 && y >= 0 &&
                        width > 0 && height > 0 &&
                        right > left && bottom > top) {

                    aimView.setGuide(
                            x, y, angle, width, height,
                            left, top, right, bottom, score
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

        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float x = -1;
        private float y = -1;
        private float angle;

        private int sourceWidth = 1;
        private int sourceHeight = 1;

        private int tableLeft;
        private int tableTop;
        private int tableRight;
        private int tableBottom;

        private float score;

        AimView(Context context) {
            super(context);

            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            border.setColor(Color.BLACK);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(
                    5 * getResources().getDisplayMetrics().density
            );
            border.setStrokeCap(Paint.Cap.ROUND);

            guide.setColor(Color.WHITE);
            guide.setStyle(Paint.Style.STROKE);
            guide.setStrokeWidth(
                    2 * getResources().getDisplayMetrics().density
            );
            guide.setStrokeCap(Paint.Cap.ROUND);
        }

        void setGuide(
                float px,
                float py,
                float a,
                int w,
                int h,
                int left,
                int top,
                int right,
                int bottom,
                float s) {

            x = px;
            y = py;
            angle = a;

            sourceWidth = w;
            sourceHeight = h;

            tableLeft = left;
            tableTop = top;
            tableRight = right;
            tableBottom = bottom;

            score = s;

            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (x < 0 || y < 0 || score < 22) return;

            float sx = getWidth() / (float) sourceWidth;
            float sy = getHeight() / (float) sourceHeight;

            float startX = x * sx;
            float startY = y * sy;

            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);

            float left = tableLeft * sx;
            float right = tableRight * sx;
            float top = tableTop * sy;
            float bottom = tableBottom * sy;

            float t = Float.POSITIVE_INFINITY;

            if (dx > 0) {
                t = Math.min(t, (right - startX) / dx);
            } else if (dx < 0) {
                t = Math.min(t, (left - startX) / dx);
            }

            if (dy > 0) {
                t = Math.min(t, (bottom - startY) / dy);
            } else if (dy < 0) {
                t = Math.min(t, (top - startY) / dy);
            }

            if (t <= 0 || Float.isInfinite(t)) return;

            float endX = startX + dx * t;
            float endY = startY + dy * t;

            // No ball marker or red circle.
            canvas.drawLine(
                    startX, startY, endX, endY, border
            );

            canvas.drawLine(
                    startX, startY, endX, endY, guide
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
