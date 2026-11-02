
package com.saqib.poolpro;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.content.pm.ServiceInfo;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Notification notification =
            new Notification.Builder(this, "pool_capture")
                .setContentTitle("Saqib Pool Pro")
                .setContentText("Screen capture is active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();

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
        android.os.Parcelable parcel =
            intent.getParcelableExtra(EXTRA_DATA);

        if (!(parcel instanceof Intent)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Intent permissionData = (Intent) parcel;

        MediaProjectionManager manager =
            (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);

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
                    stopCapture();
                    stopSelf();
                }
            },
            new Handler(Looper.getMainLooper())
        );

        startCapture();
        return START_NOT_STICKY;
    }

    private void startCapture() {
        DisplayMetrics metrics = new DisplayMetrics();

        WindowManager windowManager =
            (WindowManager) getSystemService(WINDOW_SERVICE);

        if (Build.VERSION.SDK_INT >= 30) {
            android.view.Display display =
                windowManager.getDefaultDisplay();
            display.getRealMetrics(metrics);
        } else {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        }

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2
        );

        imageReader.setOnImageAvailableListener(reader -> {
            android.media.Image image = reader.acquireLatestImage();

            if (image != null) {
                // Screen frame received.
                // Ball detection will be added in the next step.
                image.close();
            }
        }, new Handler(Looper.getMainLooper()));

        virtualDisplay = projection.createVirtualDisplay(
            "SaqibPoolProCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            null
        );
    }

    private void stopCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel =
                new NotificationChannel(
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
        stopCapture();
        super.onDestroy();
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}

