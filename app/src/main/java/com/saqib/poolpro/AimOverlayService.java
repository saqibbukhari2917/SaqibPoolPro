package com.saqib.poolpro;

import android.app.Service;
import android.content.*;
import android.graphics.*;
import android.os.Build;
import android.os.IBinder;
import android.view.*;
import android.provider.Settings;

public class AimOverlayService extends Service {

    private WindowManager wm;
    private AimView aimView;
    private BroadcastReceiver receiver;

    @Override
    public void onCreate() {
        super.onCreate();

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        aimView = new AimView(this);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        wm.addView(aimView, params);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                float[] lines = intent.getFloatArrayExtra("lines");
                if (aimView != null) aimView.setLines(lines);
            }
        };

        IntentFilter filter = new IntentFilter(
                AimCaptureService.ACTION_LINES);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private static class AimView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] lines = new float[0];

        AimView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setLines(float[] values) {
            lines = values == null ? new float[0] : values.clone();
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setStrokeCap(Paint.Cap.ROUND);

            for (int i = 0; i + 3 < lines.length; i += 4) {
                if (i == 0) {
                    paint.setColor(Color.CYAN);
                } else {
                    paint.setColor(Color.YELLOW);
                }

                paint.setAlpha(220);
                canvas.drawLine(
                        lines[i], lines[i + 1],
                        lines[i + 2], lines[i + 3],
                        paint);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) { }
            receiver = null;
        }

        if (wm != null && aimView != null) {
            try {
                wm.removeView(aimView);
            } catch (Exception ignored) { }
        }

        aimView = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
