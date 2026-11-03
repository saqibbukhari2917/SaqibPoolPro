
package com.saqib.poolpro;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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

import java.nio.ByteBuffer;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";

    public static final String ACTION_AIM =
            "com.saqib.poolpro.AIM_DIRECTION";

    private static final String CHANNEL_ID = "pool_capture";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;

    private long lastFrame;

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
                    .setContentText("Aim guide detection running")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .build());
        } else {
            startForeground(7, new Notification.Builder(this)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Aim guide detection running")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
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

            thread = new HandlerThread("PoolAimDetector");
            thread.start();
            handler = new Handler(thread.getLooper());

            imageReader = ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    2
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;

                try {
                    image = reader.acquireLatestImage();

                    if (image == null) return;

                    long now = System.currentTimeMillis();

                    if (now - lastFrame < 100) return;
                    lastFrame = now;

                    detectGuide(image);

                } catch (Exception e) {
                    android.util.Log.e("PoolPro", "Detection error", e);
                } finally {
                    if (image != null) image.close();
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
                    "PoolAimCapture",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler
            );

        } catch (Exception e) {
            android.util.Log.e("PoolPro", "Start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private boolean isGuideWhite(
            ByteBuffer buffer,
            int rowStride,
            int pixelStride,
            int x,
            int y,
            int width,
            int height) {

        if (x < 0 || y < 0 || x >= width || y >= height) {
            return false;
        }

        int pos = y * rowStride + x * pixelStride;

        if (pos + 2 >= buffer.limit()) return false;

        int r = buffer.get(pos) & 255;
        int g = buffer.get(pos + 1) & 255;
        int b = buffer.get(pos + 2) & 255;

        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));

        return r > 175 && g > 175 && b > 165
                && max - min < 65;
    }

    private void detectGuide(Image image) {

        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return;

        ByteBuffer buffer = planes[0].getBuffer().duplicate();

        int width = image.getWidth();
        int height = image.getHeight();

        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();

        /*
         * White circular candidates are searched.
         * The candidate must also have a long white guide
         * extending away from it.
         */

        int step = 2;

        int bestBallX = -1;
        int bestBallY = -1;
        double bestLineScore = 0;
        double bestAngle = 0;

        int minDim = Math.min(width, height);
        int expectedRadius = Math.max(8, Math.round(minDim * 0.020f));

        for (int cy = expectedRadius + 5;
             cy < height - expectedRadius - 5;
             cy += 4) {

            for (int cx = expectedRadius + 5;
                 cx < width - expectedRadius - 5;
                 cx += 4) {

                int whiteCount = 0;
                int total = 0;

                for (int yy = -expectedRadius;
                     yy <= expectedRadius;
                     yy += step) {

                    for (int xx = -expectedRadius;
                         xx <= expectedRadius;
                         xx += step) {

                        if (xx * xx + yy * yy >
                                expectedRadius * expectedRadius) {
                            continue;
                        }

                        total++;

                        if (isGuideWhite(
                                buffer, rowStride, pixelStride,
                                cx + xx, cy + yy, width, height)) {
                            whiteCount++;
                        }
                    }
                }

                if (total == 0) continue;

                float whiteRatio = whiteCount / (float) total;

                // Plain white cue ball should have a relatively
                // high white-pixel ratio.
                if (whiteRatio < 0.48f) continue;

                for (int degree = 0; degree < 360; degree += 2) {

                    double angle = Math.toRadians(degree);

                    double dx = Math.cos(angle);
                    double dy = Math.sin(angle);

                    int hits = 0;
                    int longest = 0;
                    int run = 0;

                    int start = expectedRadius + 5;
                    int end = Math.min(minDim / 2, 650);

                    for (int d = start; d < end; d += 3) {

                        int px = (int) Math.round(cx + dx * d);
                        int py = (int) Math.round(cy + dy * d);

                        if (px < 0 || py < 0 ||
                                px >= width || py >= height) {
                            break;
                        }

                        boolean hit = false;

                        // Search across a narrow strip around the ray.
                        for (int offset = -2; offset <= 2; offset++) {

                            int sx = (int) Math.round(px - dy * offset);
                            int sy = (int) Math.round(py + dx * offset);

                            if (isGuideWhite(
                                    buffer, rowStride, pixelStride,
                                    sx, sy, width, height)) {
                                hit = true;
                                break;
                            }
                        }

                        if (hit) {
                            hits++;
                            run++;
                            longest = Math.max(longest, run);
                        } else {
                            run = 0;
                        }
                    }

                    double score = hits + longest * 2.0;

                    if (score > bestLineScore) {
                        bestLineScore = score;
                        bestBallX = cx;
                        bestBallY = cy;
                        bestAngle = angle;
                    }
                }
            }
        }

        // Do not draw anything if the line match is weak.
        if (bestBallX < 0 || bestLineScore < 16) {
            return;
        }

        Intent update = new Intent(ACTION_AIM);
        update.setPackage(getPackageName());

        update.putExtra("x", (float) bestBallX);
        update.putExtra("y", (float) bestBallY);
        update.putExtra("angle", (float) bestAngle);
        update.putExtra("width", width);
        update.putExtra("height", height);
        update.putExtra("score", (float) bestLineScore);

        sendBroadcast(update);
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
                    
