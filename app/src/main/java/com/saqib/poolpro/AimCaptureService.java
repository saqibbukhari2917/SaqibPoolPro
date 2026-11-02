
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
import java.util.ArrayDeque;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_BALL =
            "com.saqib.poolpro.BALL_POSITION";

    private static final String CHANNEL_ID = "pool_capture";
    private static final int NOTIFICATION_ID = 7;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;
    private long lastFrameTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
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
            int density = metrics.densityDpi;

            workerThread = new HandlerThread("PoolBallDetector");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());

            imageReader = ImageReader.newInstance(
                    width, height, PixelFormat.RGBA_8888, 2);

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;

                    long now = System.currentTimeMillis();
                    if (now - lastFrameTime < 120) return;
                    lastFrameTime = now;

                    detectBall(image);

                } catch (Exception e) {
                    android.util.Log.e("PoolPro", "Frame error", e);
                } finally {
                    if (image != null) image.close();
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
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    worker
            );

        } catch (Exception e) {
            android.util.Log.e("PoolPro", "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void detectBall(Image image) {

        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return;

        ByteBuffer buffer = planes[0].getBuffer().duplicate();

        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();

        int width = image.getWidth();
        int height = image.getHeight();

        // Har doosra pixel check hoga.
        final int step = 2;
        int gw = (width + step - 1) / step;
        int gh = (height + step - 1) / step;

        boolean[] white = new boolean[gw * gh];

        // Pool table ke andar search.
        int minX = (int)(width * 0.18f);
        int maxX = (int)(width * 0.83f);
        int minY = (int)(height * 0.20f);
        int maxY = (int)(height * 0.94f);

        int gx1 = minX / step;
        int gx2 = Math.min(gw - 1, maxX / step);
        int gy1 = minY / step;
        int gy2 = Math.min(gh - 1, maxY / step);

        for (int gy = gy1; gy <= gy2; gy++) {
            int y = gy * step;

            for (int gx = gx1; gx <= gx2; gx++) {
                int x = gx * step;
                int pos = y * rowStride + x * pixelStride;

                if (pos < 0 || pos + 2 >= buffer.limit()) continue;

                int r = buffer.get(pos) & 255;
                int g = buffer.get(pos + 1) & 255;
                int b = buffer.get(pos + 2) & 255;

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));

                // White / light-grey ball pixels.
                white[gy * gw + gx] =
                        r >= 185 && g >= 180 && b >= 165
                        && max - min <= 65;
            }
        }

        boolean[] visited = new boolean[gw * gh];
        int[] queue = new int[gw * gh];

        int bestX = -1;
        int bestY = -1;
        double bestScore = -999999;

        // Connected white components ko check karo.
        for (int gy = gy1; gy <= gy2; gy++) {
            for (int gx = gx1; gx <= gx2; gx++) {

                int start = gy * gw + gx;

                if (!white[start] || visited[start]) continue;

                int head = 0;
                int tail = 0;

                queue[tail++] = start;
                visited[start] = true;

                int count = 0;
                int left = gx, right = gx;
                int top = gy, bottom = gy;
                long sumX = 0, sumY = 0;

                while (head < tail) {
                    int index = queue[head++];

                    int cx = index % gw;
                    int cy = index / gw;

                    count++;
                    sumX += cx;
                    sumY += cy;

                    left = Math.min(left, cx);
                    right = Math.max(right, cx);
                    top = Math.min(top, cy);
                    bottom = Math.max(bottom, cy);

                    int[] neighbors = {
                            index - 1,
                            index + 1,
                            index - gw,
                            index + gw
                    };

                    for (int n : neighbors) {
                        if (n < 0 || n >= white.length) continue;

                        int nx = n % gw;
                        int ny = n / gw;

                        if (Math.abs(nx - cx) + Math.abs(ny - cy) != 1)
                            continue;

                        if (nx < gx1 || nx > gx2 ||
                                ny < gy1 || ny > gy2) continue;

                        if (white[n] && !visited[n]) {
                            visited[n] = true;
                            queue[tail++] = n;
                        }
                    }
                }

                int boxW = right - left + 1;
                int boxH = bottom - top + 1;

                // Cue ball ka expected diameter screen width ka 2% hai.
                float expected = width * 0.020f;
                float diameter = (boxW + boxH) * step / 2f;

                float ratio = boxW / (float)Math.max(1, boxH);
                float fill = count /
                        (float)Math.max(1, boxW * boxH);

                if (boxW < 4 || boxH < 4) continue;
                if (ratio < 0.65f || ratio > 1.35f) continue;
                if (fill < 0.30f) continue;
                if (diameter < expected * 0.55f ||
                        diameter > expected * 1.65f) continue;

                float centerPixelX =
                        (sumX / (float)count) * step;
                float centerPixelY =
                        (sumY / (float)count) * step;

                // Gol shape aur expected size ko score do.
                double sizeError =
                        Math.abs(diameter - expected) / expected;

                double shapeError = Math.abs(1f - ratio);

                double score =
                        count * 0.01
                        - sizeError * 12
                        - shapeError * 8
                        + fill * 5;

                if (score > bestScore) {
                    bestScore = score;
                    bestX = Math.round(centerPixelX);
                    bestY = Math.round(centerPixelY);
                }
            }
        }

        if (bestX >= 0 && bestY >= 0) {

            Intent update = new Intent(ACTION_BALL);
            update.setPackage(getPackageName());

            update.putExtra("x", (float)bestX);
            update.putExtra("y", (float)bestY);
            update.putExtra("width", width);
            update.putExtra("height", height);

            sendBroadcast(update);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Pool ball detection",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager nm =
                    getSystemService(NotificationManager.class);

            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Cue ball detection is running")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle("Saqib Pool Pro")
                .setContentText("Cue ball detection is running")
                .setSmallIcon(android.R.drawable.ic_menu_search)
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
            
