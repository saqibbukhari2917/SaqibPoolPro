
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

        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
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

        registerReceiverEvents();
    }

    private void registerReceiverEvents() {

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                if (AimCaptureService.ACTION_BALL.equals(intent.getAction())) {

                    float x = intent.getFloatExtra("x", -1);
                    float y = intent.getFloatExtra("y", -1);
                    int width = intent.getIntExtra("width", 0);
                    int height = intent.getIntExtra("height", 0);

                    if (aimView != null && x >= 0 && y >= 0 &&
                            width > 0 && height > 0) {
                        aimView.setBallPosition(x, y, width, height);
                    }
                }

                if (AimCaptureService.ACTION_AIM.equals(intent.getAction())) {

                    float x = intent.getFloatExtra("x", -1);
                    float y = intent.getFloatExtra("y", -1);
                    float angle = intent.getFloatExtra("angle", 0);
                    int width = intent.getIntExtra("width", 0);
                    int height = intent.getIntExtra("height", 0);
                    float score = intent.getFloatExtra("score", 0);

                    if (aimView != null && x >= 0 && y >= 0 &&
                            width > 0 && height > 0) {
                        aimView.setAim(x, y, angle, width, height, score);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(AimCaptureService.ACTION_BALL);
        filter.addAction(AimCaptureService.ACTION_AIM);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    receiver, filter, Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private class AimView extends View {

        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint aimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float ballX = -1;
        private float ballY = -1;

        private float aimX = -1;
        private float aimY = -1;
        private float aimAngle = 0;
        private float aimScore = 0;

        private int sourceWidth = 1;
        private int sourceHeight = 1;

        AimView(Context context) {
            super(context);

            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            float density =
                    getResources().getDisplayMetrics().density;

            circlePaint.setColor(Color.RED);
            circlePaint.setStyle(Paint.Style.STROKE);
            circlePaint.setStrokeWidth(3.5f * density);

            centerPaint.setColor(Color.RED);
            centerPaint.setStyle(Paint.Style.FILL);

            aimPaint.setColor(Color.YELLOW);
            aimPaint.setStyle(Paint.Style.STROKE);
            aimPaint.setStrokeWidth(2.5f * density);
            aimPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setBallPosition(float x, float y, int width, int height) {
            ballX = x;
            ballY = y;
            sourceWidth = width;
            sourceHeight = height;
            postInvalidate();
        }

        void setAim(
                float x, float y, float angle,
                int width, int height, float score) {

            aimX = x;
            aimY = y;
            aimAngle = angle;
            aimScore = score;
            sourceWidth = width;
            sourceHeight = height;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float sx = getWidth() / (float)sourceWidth;
            float sy = getHeight() / (float)sourceHeight;

            float density =
                    getResources().getDisplayMetrics().density;

            if (aimX >= 0 && aimY >= 0 && aimScore >= 13) {

                float startX = aimX * sx;
                float startY = aimY * sy;

                float dx = (float)Math.cos(aimAngle);
                float dy = (float)Math.sin(aimAngle);

                float endX = startX + dx * getWidth() * 2f;
                float endY = startY + dy * getHeight() * 2f;

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

                if (t > 0 && t < Float.POSITIVE_INFINITY) {
                    endX = startX + dx * t;
                    endY = startY + dy * t;

                    canvas.drawLine(
                            startX, startY, endX, endY, aimPaint
                    );
                }
            }

            if (ballX >= 0 && ballY >= 0) {

                float centerX = ballX * sx;
                float centerY = ballY * sy;

                float radius = 18f * density;

                canvas.drawCircle(
                        centerX, centerY, radius, circlePaint
                );

                canvas.drawCircle(
                        centerX, centerY, 2.5f * density, centerPaint
                );
            }
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
