
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

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        aimView = new AimView(this);

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(aimView, params);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                if (!AimCaptureService.ACTION_LINE.equals(intent.getAction())) {
                    return;
                }

                float[] lines = intent.getFloatArrayExtra("lines");

                int width = intent.getIntExtra("width", 0);
                int height = intent.getIntExtra("height", 0);

                if (aimView != null) {
                    aimView.setLines(lines, width, height);
                }
            }
        };

        IntentFilter filter =
                new IntentFilter(AimCaptureService.ACTION_LINE);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private class AimView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float[] lines;
        private int sourceWidth = 1;
        private int sourceHeight = 1;

        AimView(Context context) {
            super(context);

            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(
                    2.5f * getResources().getDisplayMetrics().density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
        }

        void setLines(float[] points, int width, int height) {

            if (points == null || points.length < 4 || points.length % 4 != 0) {
                lines = null;
            } else {
                lines = points.clone();
            }

            if (width > 0) sourceWidth = width;
            if (height > 0) sourceHeight = height;

            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (lines == null || lines.length < 4) return;

            float sx = getWidth() / (float) sourceWidth;
            float sy = getHeight() / (float) sourceHeight;

            for (int i = 0; i + 3 < lines.length; i += 4) {
                canvas.drawLine(
                        lines[i] * sx, lines[i + 1] * sy,
                        lines[i + 2] * sx, lines[i + 3] * sy, paint);
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
