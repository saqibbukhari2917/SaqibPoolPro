
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
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

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

            workerThread = new HandlerThread("PoolAimDetector");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());

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

                    if (now - lastFrameTime < 180) return;

                    lastFrameTime = now;
                    detectAimLines(image);

                } catch (Exception e) {
                    Log.e("PoolPro", "Aim detection error", e);
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
            Log.e("PoolPro", "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void detectAimLines(Image image) {

        int width = image.getWidth();
        int height = image.getHeight();

        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return;

        ByteBuffer buffer = planes[0].getBuffer().duplicate();

        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();

        final int step = 2;

        int gw = width / step;
        int gh = height / step;

        // Approximate game table area
        int left = (int) (gw * 0.10f);
        int right = (int) (gw * 0.90f);
        int top = (int) (gh * 0.12f);
        int bottom = (int) (gh * 0.92f);

        final int angleCount = 180;

        int diagonal =
                (int) Math.sqrt(gw * gw + gh * gh);

        int rhoSize = diagonal * 2 + 1;

        int[][] accumulator =
                new int[angleCount][rhoSize];

        double[] cos = new double[angleCount];
        double[] sin = new double[angleCount];

        for (int a = 0; a < angleCount; a++) {
            double theta = Math.PI * a / angleCount;
            cos[a] = Math.cos(theta);
            sin[a] = Math.sin(theta);
        }

        ArrayList<int[]> whitePixels = new ArrayList<>();

        // Find bright neutral pixels
        for (int y = top; y < bottom; y++) {

            for (int x = left; x < right; x++) {

                int pos =
                        (y * step) * rowStride
                                + (x * step) * pixelStride;

                if (pos < 0 || pos + 2 >= buffer.limit()) {
                    continue;
                }

                int r = buffer.get(pos) & 255;
                int g = buffer.get(pos + 1) & 255;
                int b = buffer.get(pos + 2) & 255;

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));

                if (min < 190 || max - min > 42) {
                    continue;
                }

                whitePixels.add(new int[]{x, y});

                for (int a = 0; a < angleCount; a++) {

                    int rho = (int) Math.round(
                            x * cos[a] + y * sin[a]
                    ) + diagonal;

                    if (rho >= 0 && rho < rhoSize) {
                        accumulator[a][rho]++;
                    }
                }
            }
        }

        if (whitePixels.size() < 40) {
            sendLines(null, width, height);
            return;
        }

        ArrayList<float[]> acceptedLines = new ArrayList<>();

        // Search for two separate, continuous line candidates
        for (int attempt = 0;
             attempt < 30 && acceptedLines.size() < 2;
             attempt++) {

            int bestA = -1;
            int bestR = -1;
            int bestVotes = 0;

            for (int a = 0; a < angleCount; a++) {

                for (int r = 0; r < rhoSize; r++) {

                    int votes = accumulator[a][r];

                    if (votes > bestVotes) {
                        bestVotes = votes;
                        bestA = a;
                        bestR = r;
                    }
                }
            }

            if (bestA < 0 || bestVotes < 18) {
                break;
            }

            double nx = cos[bestA];
            double ny = sin[bestA];

            double rho = bestR - diagonal;

            double dx = -ny;
            double dy = nx;

            int[] bins = new int[20];

            int inliers = 0;

            double minT = Double.POSITIVE_INFINITY;
            double maxT = Double.NEGATIVE_INFINITY;

            for (int[] p : whitePixels) {

                double distance = Math.abs(
                        p[0] * nx + p[1] * ny - rho
                );

                if (distance > 1.8) continue;

                double t = p[0] * dx + p[1] * dy;

                minT = Math.min(minT, t);
                maxT = Math.max(maxT, t);

                inliers++;
            }

            if (inliers > 0 && maxT > minT) {

                double span = maxT - minT;

                for (int[] p : whitePixels) {

                    double distance = Math.abs(
                            p[0] * nx + p[1] * ny - rho
                    );

                    if (distance > 1.8) continue;

                    double t = p[0] * dx + p[1] * dy;

                    int bin = (int)
                            ((t - minT) / span * 19.0);

                    bin = Math.max(0, Math.min(19, bin));

                    bins[bin]++;
                }
            }

            int occupiedBins = 0;

            for (int count : bins) {
                if (count > 0) occupiedBins++;
            }

            float[] bounds = lineToBounds(
                    bestA,
                    bestR,
                    diagonal,
                    cos,
                    sin,
                    left,
                    top,
                    right,
                    bottom
            );

            boolean longEnough =
                    maxT - minT >= Math.min(gw, gh) * 0.18;

            boolean continuous =
                    occupiedBins >= 10 && inliers >= 24;

            boolean distinct = true;

            if (bounds != null) {

                for (float[] prior : acceptedLines) {

                    double angleDifference =
                            Math.abs(bestA - prior[4]);

                    angleDifference =
                            Math.min(
                                    angleDifference,
                                    180 - angleDifference
                            );

                    if (angleDifference < 7
                            && Math.abs(rho - prior[5]) < 14) {

                        distinct = false;
                        break;
                    }
                }
            }

            if (bounds != null
                    && longEnough
                    && continuous
                    && distinct) {

                acceptedLines.add(new float[]{
                        bounds[0],
                        bounds[1],
                        bounds[2],
                        bounds[3],
                        bestA,
                        (float) rho
                });
            }

            // Remove this peak and nearby duplicate peaks
            for (int a = Math.max(0, bestA - 5);
                 a <= Math.min(179, bestA + 5);
                 a++) {

                for (int r = Math.max(0, bestR - 10);
                     r <= Math.min(rhoSize - 1, bestR + 10);
                     r++) {

                    accumulator[a][r] = 0;
                }
            }
        }

        // Never draw random lines when two candidates are not found
        if (acceptedLines.size() != 2) {
            sendLines(null, width, height);
            return;
        }

        float[] result = new float[]{
                acceptedLines.get(0)[0] * step,
                acceptedLines.get(0)[1] * step,
                acceptedLines.get(0)[2] * step,
                acceptedLines.get(0)[3] * step,

                acceptedLines.get(1)[0] * step,
                acceptedLines.get(1)[1] * step,
                acceptedLines.get(1)[2] * step,
                acceptedLines.get(1)[3] * step
        };

        sendLines(result, width, height);
    }

    private float[] lineToBounds(
            int angle,
            int rhoIndex,
            int diagonal,
            double[] cos,
            double[] sin,
            int left,
            int top,
            int right,
            int bottom) {

        double nx = cos[angle];
        double ny = sin[angle];

        double rho = rhoIndex - diagonal;

        List<float[]> points = new ArrayList<>();

        if (Math.abs(ny) > 0.0001) {

            addPoint(
                    points,
                    left,
                    (rho - left * nx) / ny,
                    left, top, right, bottom
            );

            addPoint(
                    points,
                    right,
                    (rho - right * nx) / ny,
                    left, top, right, bottom
            );
        }

        if (Math.abs(nx) > 0.0001) {

            addPoint(
                    points,
                    (rho - top * ny) / nx,
                    top,
                    left, top, right, bottom
            );

            addPoint(
                    points,
                    (rho - bottom * ny) / nx,
                    bottom,
                    left, top, right, bottom
            );
        }

        if (points.size() < 2) return null;

        float[] first = null;
        float[] second = null;

        double maxDistance = 0;

        for (int i = 0; i < points.size(); i++) {

            for (int j = i + 1; j < points.size(); j++) {

                float[] p1 = points.get(i);
                float[] p2 = points.get(j);

                double dx = p1[0] - p2[0];
                double dy = p1[1] - p2[1];

                double distance = dx * dx + dy * dy;

                if (distance > maxDistance) {

                    maxDistance = distance;

                    first = p1;
                    second = p2;
                }
            }
        }

        if (first == null || second == null) return null;

        return new float[]{
                first[0], first[1],
                second[0], second[1]
        };
    }

    private void addPoint(
            List<float[]> points,
            double x,
            double y,
            int left,
            int top,
            int right,
            int bottom) {

        if (x < left || x > right || y < top || y > bottom) {
            return;
        }

        for (float[] point : points) {

            if (Math.abs(point[0] - x) < 1
                    && Math.abs(point[1] - y) < 1) {
                return;
            }
        }

        points.add(new float[]{
                (float) x,
                (float) y
        });
    }

    private void sendLines(
            float[] lines,
            int width,
            int height) {

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

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Pool aim detection",
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
                    .setContentText("Aim detection is running")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle("Saqib Pool Pro")
                .setContentText("Aim detection is running")
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
