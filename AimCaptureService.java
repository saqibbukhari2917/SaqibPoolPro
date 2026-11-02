package com.saqib.poolpro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Notification notification;

        if (Build.VERSION.SDK_INT >= 26) {
            notification = new Notification.Builder(this, "pool_capture")
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Screen capture is active")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Screen capture is active")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    7,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(7, notification);
        }

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);

        Intent permissionData;

        if (Build.VERSION.SDK_INT >= 33) {
            permissionData = intent.getParcelableExtra(
                    EXTRA_DATA, Intent.class
            );
        } else {
            permissionData = intent.getParcelableExtra(EXTRA_DATA);
        }

        if (permissionData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(
                        MEDIA_PROJECTION_SERVICE
                );

        projection = manager.getMediaProjection(
                resultCode, permissionData
        );

        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        projection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        releaseCapture(false);
                        stopSelf();
                    }
                },
                handler
        );

        startCapture();

        return START_NOT_STICKY;
    }

    private void startCapture() {

        WindowManager windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        DisplayMetrics metrics = new DisplayMetrics();
        Display display = windowManager.getDefaultDisplay();
        display.getRealMetrics(metrics);

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
        );

        imageReader.setOnImageAvailableListener(reader -> {

            Image image = reader.acquireLatestImage();

            if (image != null) {
                // Screen frame received.
                // Ball detection will be added later.
                image.close();
            }

        }, handler);

        virtualDisplay = projection.createVirtualDisplay(
                "SaqibPoolProCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
        );
    }

    private void releaseCapture(boolean stopProjection) {

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }

        if (projection != null) {
            MediaProjection oldProjection = projection;
            projection = null;

            if (stopProjection) {
                oldProjection.stop();
            }
        }
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel = new NotificationChannel(
                    "pool_capture",
                    "Pool Pro Screen Capture",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        releaseCapture(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
