
package com.saqib.poolpro;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_LINE =
            "com.saqib.poolpro.AIM_LINE";

    private static final String CHANNEL_ID = "pool_capture";
    private static final int NOTIFICATION_ID = 7;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;

    private long lastFrameTime = 0;
    private long lastSaveTime = 0;
    private int savedFrames = 0;

    private static final int MAX_SAVED_FRAMES = 3;
    private static final long SAVE_INTERVAL = 8000;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
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
                    (MediaProjectionManager)
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE);

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

            int width = metrics.widthPixels;
            int height = metrics.heightPixels;

            workerThread = new HandlerThread("PoolFrameSaver");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());

            imageReader = ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    3
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;

                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;

                    long now = System.currentTimeMillis();

                    if (now - lastFrameTime < 500) return;
                    lastFrameTime = now;

                    if (savedFrames < MAX_SAVED_FRAMES
                            && now - lastSaveTime >= SAVE_INTERVAL) {

                        boolean saved = saveFrame(image);

                        if (saved) {
                            savedFrames++;
                            lastSaveTime = now;

                            Log.i("PoolProFrame",
                                    "Saved frame " + savedFrames
                                            + " of " + MAX_SAVED_FRAMES);

                            updateNotification(
                                    "Game frames saved: " + savedFrames
                                            + " of " + MAX_SAVED_FRAMES
                            );
                        }
                    }

                    // Diagnostic mode: do not draw unverified lines.
                    sendLines(null, width, height);

                } catch (Exception e) {
                    Log.e("PoolProFrame", "Frame processing error", e);
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
                    },
                    worker
            );

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

        } catch (Exception e) {
            Log.e("PoolProFrame", "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private boolean saveFrame(Image image) {

        Bitmap bitmap = null;

        try {
            int width = image.getWidth();
            int height = image.getHeight();

            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) return false;

            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer().duplicate();

            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();

            int[] pixels = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {

                    int pos = y * rowStride + x * pixelStride;

                    if (pos + 3 >= buffer.limit()) {
                        continue;
                    }

                    int r = buffer.get(pos) & 255;
                    int g = buffer.get(pos + 1) & 255;
                    int b = buffer.get(pos + 2) & 255;
                    int a = buffer.get(pos + 3) & 255;

                    pixels[y * width + x] =
                            (a << 24) | (r << 16) | (g << 8) | b;
                }
            }

            bitmap = Bitmap.createBitmap(
                    pixels, width, height, Bitmap.Config.ARGB_8888
            );

            String fileName =
                    "PoolFrame_" + System.currentTimeMillis() + ".jpg";

            OutputStream output = null;

            if (Build.VERSION.SDK_INT >= 29) {

                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/SaqibPoolPro"
                );
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                android.net.Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values
                );

                if (uri == null) return false;

                output = getContentResolver().openOutputStream(uri);

                if (output == null) {
                    getContentResolver().delete(uri, null, null);
                    return false;
                }

                boolean compressed = bitmap.compress(
                        Bitmap.CompressFormat.JPEG, 92, output
                );

                output.flush();
                output.close();
                output = null;

                if (!compressed) {
                    getContentResolver().delete(uri, null, null);
                    return false;
                }

                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, ready, null, null);

                Log.i("PoolProFrame", "Image saved: " + uri);
                return true;

            } else {

                File folder = new File(
                        getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        "SaqibPoolPro"
                );

                if (!folder.exists() && !folder.mkdirs()) {
                    return false;
                }

                File file = new File(folder, fileName);
                output = new FileOutputStream(file);

                boolean compressed = bitmap.compress(
                        Bitmap.CompressFormat.JPEG, 92, output
                );

                output.flush();
                output.close();
                output = null;

                Log.i("PoolProFrame",
                        "Image saved: " + file.getAbsolutePath());

                return compressed;
            }

        } catch (Exception e) {
            Log.e("PoolProFrame", "Image save failed", e);
            return false;

        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
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

    private void updateNotification(String message) {

        NotificationManager nm =
                (NotificationManager)
                        getSystemService(Context.NOTIFICATION_SERVICE);

        nm.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Pool frame capture",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager nm =
                    getSystemService(NotificationManager.class);

            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return buildNotification("Live frame capture is running");
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
