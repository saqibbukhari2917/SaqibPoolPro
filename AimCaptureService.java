
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

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        worker = new HandlerThread("BallDetector");
        worker.start();
        workerHandler = new Handler(worker.getLooper());
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
            startForeground(7, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(7, notification);
        }

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode =
                intent.getIntExtra(EXTRA_RESULT_CODE, 0);

        Intent permissionData;

        if (Build.VERSION.SDK_INT >= 33) {
            permissionData =
                    intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        } else {
            permissionData =
                    intent.getParcelableExtra(EXTRA_DATA);
        }

        if (permissionData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);

        projection =
                manager.getMediaProjection(resultCode, permissionData);

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

        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, 2);

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();

            if (image != null) {
                try {
                    detectBall(image);
                } finally {
                    image.close();
                }
            }
        }, workerHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "SaqibPoolProCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, workerHandler);
    }

    private void detectBall(Image image) {

        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return;

        ByteBuffer buffer = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();

        int width = image.getWidth();
        int height = image.getHeight();

        int step = 2;
        int gw = width / step;
        int gh = height / step;

        if (gw <= 0 || gh <= 0) return;

        boolean[] visited = new boolean[gw * gh];
        int[] queue = new int[gw * gh];

        int bestX = -1;
        int bestY = -1;
        double bestScore = -1;

        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {

                int index = gy * gw + gx;

                if (visited[index] ||
                    !isWhite(buffer, gx * step, gy * step,
                             rowStride, pixelStride)) {
                    continue;
                }

                int head = 0;
                int tail = 0;

                queue[tail++] = index;
                visited[index] = true;

                int minX = gx, maxX = gx;
                int minY = gy, maxY = gy;
                int count = 0;
                long sumX = 0, sumY = 0;

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

                    int[] nx = {x - 1, x + 1, x, x};
                    int[] ny = {y, y, y - 1, y + 1};

                    for (int k = 0; k < 4; k++) {
                        int xx = nx[k];
                        int yy = ny[k];

                        if (xx < 0 || yy < 0 ||
                            xx >= gw || yy >= gh) continue;

                        int ni = yy * gw + xx;

                        if (!visited[ni] &&
                            isWhite(buffer, xx * step, yy * step,
                                    rowStride, pixelStride)) {
                            visited[ni] = true;
                            queue[tail++] = ni;
                        }
                    }
                }

                int bw = maxX - minX + 1;
                int bh = maxY - minY + 1;

                if (count < 40 || count > 2200 ||
                    bw < 8 || bh < 8 ||
                    bw > 90 || bh > 90) continue;

                float ratio = (float) bw / (float) bh;

                if (ratio < 0.65f || ratio > 1.4f) continue;

                float fill =
                        (float) count / (float) (bw * bh);

                if (fill < 0.30f) continue;

                int cx = (int) (sumX / count) * step;
                int cy = (int) (sumY / count) * step;

                double score = count;

                if (lastX >= 0 && lastY >= 0) {
                    double distance = Math.hypot(
                            cx - lastX, cy - lastY);

                    score = count / (1.0 + distance / 150.0);
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
            update.putExtra("x", (float) bestX);
            update.putExtra("y", (float) bestY);
            update.putExtra("width", width);
            update.putExtra("height", height);

            sendBroadcast(update);
        }
    }

    private boolean isWhite(
            ByteBuffer buffer, int x, int y,
            int rowStride, int pixelStride) {

        int pos = y * rowStride + x * pixelStride;

        if (pos < 0 || pos + 2 >= buffer.limit()) return false;

        int r = buffer.get(pos) & 255;
        int g = buffer.get(pos + 1) & 255;
        int b = buffer.get(pos + 2) & 255;

        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));

        return r > 205 && g > 205 && b > 205
                && max - min < 45;
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

            if (stopProjection) old.stop();
        }
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel =
                    new NotificationChannel(
                            "pool_capture",
                            "Pool Pro Screen Capture",
                            NotificationManager.IMPORTANCE_LOW);

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
