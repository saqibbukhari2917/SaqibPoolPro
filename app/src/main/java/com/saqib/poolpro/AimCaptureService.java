
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
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.OutputStream;
import java.nio.ByteBuffer;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_AIM =
            "com.saqib.poolpro.AIM_DIRECTION";

    private static final String CHANNEL_ID = "pool_capture";
    private static final String TAG = "PoolPro";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;

    private boolean frameSaved = false;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Pool capture",
                    NotificationManager.IMPORTANCE_LOW
            );

            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);

            startForeground(7, new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Saving diagnostic screen")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build());
        } else {
            startForeground(7, new Notification.Builder(this)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Saving diagnostic screen")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            stopSelf();
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
            Log.e(TAG, "Projection data is missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);

            projection = manager.getMediaProjection(resultCode, data);

            if (projection == null) {
                Log.e(TAG, "MediaProjection is null");
                stopSelf();
                return START_NOT_STICKY;
            }

            DisplayMetrics metrics = new DisplayMetrics();

            android.view.WindowManager wm =
                    (android.view.WindowManager)
                    getSystemService(WINDOW_SERVICE);

            wm.getDefaultDisplay().getRealMetrics(metrics);

            int screenW = metrics.widthPixels;
            int screenH = metrics.heightPixels;

            thread = new HandlerThread("PoolDebugCapture");
            thread.start();
            handler = new Handler(thread.getLooper());

            imageReader = ImageReader.newInstance(
                    screenW,
                    screenH,
                    PixelFormat.RGBA_8888,
                    2
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;

                try {
                    image = reader.acquireLatestImage();

                    if (image == null || frameSaved) {
                        return;
                    }

                    frameSaved = true;
                    saveDebugFrame(image);

                } catch (Exception e) {
                    frameSaved = false;
                    Log.e(TAG, "Frame capture failed", e);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }, handler);

            projection.registerCallback(
                    new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            stopSelf();
                        }
                    },
                    handler
            );

            virtualDisplay = projection.createVirtualDisplay(
                    "PoolDebugCapture",
                    screenW,
                    screenH,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler
            );

            Log.d(TAG, "Screen capture started");

        } catch (Exception e) {
            Log.e(TAG, "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void saveDebugFrame(Image image) {

        Image.Plane[] planes = image.getPlanes();

        if (planes == null || planes.length == 0) {
            Log.e(TAG, "No image planes");
            frameSaved = false;
            return;
        }

        int width = image.getWidth();
        int height = image.getHeight();

        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();

        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                int index = y * rowStride + x * pixelStride;

                if (index + 3 >= buffer.limit()) {
                    continue;
                }

                int r = buffer.get(index) & 255;
                int g = buffer.get(index + 1) & 255;
                int b = buffer.get(index + 2) & 255;
                int a = buffer.get(index + 3) & 255;

                pixels[y * width + x] =
                        (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
        );

        bitmap.setPixels(
                pixels, 0, width,
                0, 0, width, height
        );

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "PoolPro_Debug_Frame.png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");

            if (Build.VERSION.SDK_INT >= 29) {
                values.put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES
                                + "/SaqibPoolPro"
                );

                values.put(
                        MediaStore.Images.Media.IS_PENDING,
                        1
                );
            }

            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            );

            if (uri == null) {
                throw new Exception("Could not create image file");
            }

            OutputStream output =
                    getContentResolver().openOutputStream(uri);

            if (output == null) {
                throw new Exception("Could not open image output");
            }

            boolean compressed;
            try (OutputStream stream = output) {
                compressed = bitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        stream
                );
                stream.flush();
            }

            if (!compressed) {
                throw new Exception("PNG compression failed");
            }

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);

                getContentResolver().update(uri, done, null, null);
            }

            Log.d(TAG, "DEBUG FRAME SAVED: " + uri);

        } catch (Exception e) {
            frameSaved = false;
            Log.e(TAG, "Could not save debug frame", e);
        } finally {
            bitmap.recycle();
        }
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

        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
                    }
