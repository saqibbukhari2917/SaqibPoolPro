
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

        windowManager = (WindowManager)
                getSystemService(WINDOW_SERVICE);

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

        registerBallReceiver();
    }

    private void registerBallReceiver() {

        receiver = new BroadcastReceiver() {

            @Override
            public void onReceive(
                    Context context,
                    Intent intent) {

                if (!AimCaptureService.ACTION_BALL.equals(
                        intent.getAction())) {
                    return;
                }

                float x = intent.getFloatExtra("x", -1);
                float y = intent.getFloatExtra("y", -1);

                int width = intent.getIntExtra("width", 0);
                int height = intent.getIntExtra("height", 0);

                if (aimView != null &&
                        width > 0 && height > 0 &&
                        x >= 0 && y >= 0) {

                    aimView.setBallPosition(
                            x, y, width, height);
                }
            }
        };

        IntentFilter filter = new IntentFilter(
                AimCaptureService.ACTION_BALL);

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

        private final Paint circlePaint;
        private final Paint centerPaint;

        private float ballX = -1;
        private float ballY = -1;

        private int sourceWidth = 1;
        private int sourceHeight = 1;

        AimView(Context context) {
            super(context);

            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            circlePaint.setColor(Color.RED);
            circlePaint.setStyle(Paint.Style.STROKE);
            circlePaint.setStrokeWidth(
                    3.5f * getResources()
                            .getDisplayMetrics().density
            );

            centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            centerPaint.setColor(Color.RED);
            centerPaint.setStyle(Paint.Style.FILL);
        }

        void setBallPosition(
                float x,
                float y,
                int width,
                int height) {

            ballX = x;
            ballY = y;

            sourceWidth = width;
            sourceHeight = height;

            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (ballX < 0 || ballY < 0) {
                return;
            }

            float centerX =
                    ballX * getWidth() / sourceWidth;

            float centerY =
                    ballY * getHeight() / sourceHeight;

            float radius = 18f *
                    getResources().getDisplayMetrics().density;

            canvas.drawCircle(
                    centerX,
                    centerY,
                    radius,
                    circlePaint
            );

            canvas.drawCircle(
                    centerX,
                    centerY,
                    2.5f *
                            getResources().getDisplayMetrics().density,
                    centerPaint
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
    public int onStartCommand(
            Intent intent, int flags, int startId) {

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
          }
