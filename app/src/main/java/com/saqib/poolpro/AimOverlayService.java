
package com.saqib.poolpro;

import android.app.Service;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public class AimOverlayService extends Service {

    private WindowManager windowManager;
    private View emptyView;

    @Override
    public void onCreate() {
        super.onCreate();

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        emptyView = new View(this);
        emptyView.setBackgroundColor(
                android.graphics.Color.TRANSPARENT
        );

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(emptyView, params);
    }

    @Override
    public int onStartCommand(
            android.content.Intent intent,
            int flags,
            int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (windowManager != null && emptyView != null) {
            try {
                windowManager.removeView(emptyView);
            } catch (Exception ignored) {
            }
            emptyView = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(android.content.Intent intent) {
        return null;
    }
}
