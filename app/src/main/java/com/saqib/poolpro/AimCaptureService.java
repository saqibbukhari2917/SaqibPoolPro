package com.saqib.poolpro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;

import java.nio.ByteBuffer;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_LINE = "com.saqib.poolpro.AIM_LINE";

    private static final String TAG = "PoolProCapture";
    private static final String CHANNEL_ID = "pool_capture";
    private static final int NOTIFICATION_ID = 7;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;

    private long lastFrameTime;
    private long lastNotificationTime;
    private int frameCount;
    private int whitePixelCount;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID,
                buildNotification("Live capture ready"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null || projection != null) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data;

        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_DATA);
        }

        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE);

            projection = manager.getMediaProjection(resultCode, data);

            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            DisplayMetrics metrics = new DisplayMetrics();

            android.view.WindowManager wm =
                    (android.view.WindowManager)
                            getSystemService(WINDOW_SERVICE);

            wm.getDefaultDisplay().getRealMetrics(metrics);

            final int width = metrics.widthPixels;
            final int height = metrics.heightPixels;

            workerThread = new HandlerThread("PoolLiveFrameWorker");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());

            imageReader = ImageReader.newInstance(
                    width, height, PixelFormat.RGBA_8888, 3);

            imageReader.setOnImageAvailableListener(reader -> {

                Image image = null;

                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;

                    long now = System.currentTimeMillis();

                    if (now - lastFrameTime < 300) return;
                    lastFrameTime = now;

                    frameCount++;

                    // Sirf RAM mein pixels ka diagnostic count.
                    // Koi screenshot ya image file save nahi hoti.
                    whitePixelCount = countBrightNeutralPixels(image);

                    if (now - lastNotificationTime >= 1500) {
                        lastNotificationTime = now;

                        updateNotification(
                                "Live frames: " + frameCount
                                        + " | White pixels: "
                                        + whitePixelCount
                        );
                    }

                    // Original guide lines verify nahi hui hain.
                    // Is liye abhi koi line draw nahi karni.
                    sendLines(null, width, height);

                } catch (Exception e) {
                    Log.e(TAG, "Frame processing error", e);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }

            }, worker);

            projection.registerCallback(
                    new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            stopSelf();
                        }
                    }, worker);

            virtualDisplay = projection.createVirtualDisplay(
                    "SaqibPoolProCapture",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    worker
            );

            updateNotification("Live capture started");

        } catch (Exception e) {
            Log.e(TAG, "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private int countBrightNeutralPixels(Image image) {

        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return 0;

        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();

        int width = image.getWidth();
        int height = image.getHeight();

        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        int left = width / 10;
        int right = width * 9 / 10;
        int top = height * 12 / 100;
        int bottom = height * 92 / 100;

        int count = 0;

        // Har 4 pixels par sample; processing halka rakha gaya hai.
        for (int y = top; y < bottom; y += 4) {
            for (int x = left; x < right; x += 4) {

                int pos = y * rowStride + x * pixelStride;

                if (pos + 2 >= buffer.limit()) continue;

                int r = buffer.get(pos) & 255;
                int g = buffer.get(pos + 1) & 255;
                int b = buffer.get(pos + 2) & 255;

                int min = Math.min(r, Math.min(g, b));
                int max = Math.max(r, Math.max(g, b));

                if (min >= 190 && max - min <= 42) {
                    count++;
                }
            }
        }

        return count;
    }

    private void sendLines(float[] lines, int width, int height) {

        Intent update = new Intent(ACTION_LINE);
        update.setPackage(getPackageName());

        if (lines != null) {
            update.putExtra("lines", lines);
        }

        update.putExtra("width", width);
        update.putExtra("height", height);

        sendBroadcast(update);
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Pool live capture",
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager nm =
                    getSystemService(NotificationManager.class);

            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {

        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle("Saqib Pool Pro")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void updateNotification(String message) {

        NotificationManager nm =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        nm.notify(NOTIFICATION_ID, buildNotification(message));
    }

    @Override
    public void onDestroy() {

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

        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
                        }
