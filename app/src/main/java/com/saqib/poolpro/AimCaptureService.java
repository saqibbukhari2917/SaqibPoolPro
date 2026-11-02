
package com.saqib.poolpro;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import java.nio.ByteBuffer;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_BALL =
            "com.saqib.poolpro.BALL_POSITION";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private HandlerThread worker;
    private Handler workerHandler;

    private int lastX = -1;
    private int lastY = -1;
    private long lastProcessTime = 0;

    private static final int STEP = 2;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        worker = new HandlerThread("BallDetector");
        worker.start();

        workerHandler = new Handler(worker.getLooper());
    }

    @Override
    public int onStartCommand(
            Intent intent, int flags, int startId) {

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification;

        if (Build.VERSION.SDK_INT >= 26) {
            notification = new Notification.Builder(
                    this, "pool_capture")
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("White ball detection active")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Ball detection active")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(7, notification);
        }

        int resultCode = intent.getIntExtra(
                EXTRA_RESULT_CODE, 0);

        Intent data;

        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(
                    EXTRA_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_DATA);
        }

        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);

        projection = manager.getMediaProjection(
                resultCode, data);

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
                }, workerHandler);

        startCapture();

        return START_NOT_STICKY;
    }

    private void startCapture() {

        WindowManager wm =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);

        int width = dm.widthPixels;
        int height = dm.heightPixels;

        imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
        );

        imageReader.setOnImageAvailableListener(
                reader -> {

                    Image image = reader.acquireLatestImage();

                    if (image == null) return;

                    try {
                        long now = System.currentTimeMillis();

                        if (now - lastProcessTime >= 100) {
                            lastProcessTime = now;
                            detectBall(image);
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                    }

                }, workerHandler
        );

        virtualDisplay = projection.createVirtualDisplay(
                "SaqibPoolProCapture",
                width,
                height,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                workerHandler
        );
    }

    private void detectBall(Image image) {

        Image.Plane[] planes = image.getPlanes();

        if (planes.length == 0) return;

        ByteBuffer buffer = planes[0].getBuffer();

        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();

        int width = image.getWidth();
        int height = image.getHeight();

        int gw = width / STEP;
        int gh = height / STEP;

        boolean[] visited = new boolean[gw * gh];
        int[] queue = new int[gw * gh];

        int minGX = (int)(gw * 0.10f);
        int maxGX = (int)(gw * 0.92f);

        int minGY = (int)(gh * 0.10f);
        int maxGY = (int)(gh * 0.96f);

        int bestX = -1;
        int bestY = -1;

        double bestScore = -1;

        int minDiameter = Math.max(5, (int)(gw * 0.012f));
        int maxDiameter = Math.max(20, (int)(gw * 0.075f));

        for (int gy = minGY; gy < maxGY; gy++) {

            for (int gx = minGX; gx < maxGX; gx++) {

                int index = gy * gw + gx;

                if (visited[index]) continue;

                if (!isWhite(buffer,
                        gx * STEP,
                        gy * STEP,
                        rowStride,
                        pixelStride)) {
                    continue;
                }

                int head = 0;
                int tail = 0;

                queue[tail++] = index;
                visited[index] = true;

                int minX = gx;
                int maxX = gx;
                int minY = gy;
                int maxY = gy;

                long sumX = 0;
                long sumY = 0;

                int count = 0;

                while (head < tail) {

                    int current = queue[head++];

                    int x = current % gw;
                    int y = current / gw;

                    count++;

                    sumX += x;
                    sumY += y;

                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);

                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);

                    for (int yy = y - 1; yy <= y + 1; yy++) {

                        for (int xx = x - 1; xx <= x + 1; xx++) {

                            if (xx < minGX || xx >= maxGX ||
                                    yy < minGY || yy >= maxGY) {
                                continue;
                            }

                            int ni = yy * gw + xx;

                            if (!visited[ni] &&
                                    isWhite(buffer,
                                            xx * STEP,
                                            yy * STEP,
                                            rowStride,
                                            pixelStride)) {

                                visited[ni] = true;
                                queue[tail++] = ni;
                            }
                        }
                    }
                }

                int bw = maxX - minX + 1;
                int bh = maxY - minY + 1;

                if (bw < minDiameter || bh < minDiameter ||
                        bw > maxDiameter || bh > maxDiameter) {
                    continue;
                }

                float ratio = (float)bw / bh;

                if (ratio < 0.78f || ratio > 1.28f) {
                    continue;
                }

                float fill = (float)count / (bw * bh);

                if (fill < 0.48f) continue;

                int cx = (int)(sumX / count) * STEP;
                int cy = (int)(sumY / count) * STEP;

                double roundScore =
                        1.0 - Math.abs(1.0 - ratio);

                double score = count * fill * roundScore;

                if (lastX >= 0 && lastY >= 0) {

                    double distance = Math.hypot(
                            cx - lastX,
                            cy - lastY);

                    score /= (1.0 + distance / (width * 0.30));
                }

                if (score > bestScore) {

                    bestScore = score;

                    bestX = cx;
                    bestY = cy;
                }
            }
        }

        if (bestX >= 0 && bestY >= 0) {

            lastX = bestX;
            lastY = bestY;

            Intent update = new Intent(ACTION_BALL);

            update.setPackage(getPackageName());

            update.putExtra("x", (float)bestX);
            update.putExtra("y", (float)bestY);

            update.putExtra("width", width);
            update.putExtra("height", height);

            sendBroadcast(update);
        }
    }

    private boolean isWhite(
            ByteBuffer buffer,
            int x,
            int y,
            int rowStride,
            int pixelStride) {

        int pos = y * rowStride + x * pixelStride;

        if (pos < 0 || pos + 2 >= buffer.limit()) {
            return false;
        }

        int r = buffer.get(pos) & 255;
        int g = buffer.get(pos + 1) & 255;
        int b = buffer.get(pos + 2) & 255;

        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));

        return r > 205 &&
                g > 205 &&
                b > 205 &&
                max - min < 40;
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

            MediaProjection old = projection;
            projection = null;

            if (stopProjection) {
                old.stop();
            }
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

            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {

        releaseCapture(true);

        if (worker != null) {
            worker.quitSafely();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
                    }
