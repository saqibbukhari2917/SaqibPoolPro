
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

        int step = 2;

        int gw = width / step;
        int gh = height / step;

        // Approximate table boundaries
        int left = (int) (gw * 0.10f);
        int right = (int) (gw * 0.90f);
        int top = (int) (gh * 0.12f);
        int bottom = (int) (gh * 0.92f);

        int angleCount = 180;

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

        int whiteCount = 0;

        // Detect bright neutral pixels
        for (int y = top; y < bottom; y++) {

            for (int x = left; x < right; x++) {

                int px = x * step;
                int py = y * step;

                int pos =
                        py * rowStride + px * pixelStride;

                if (pos < 0 || pos + 2 >= buffer.limit()) {
                    continue;
                }

                int r = buffer.get(pos) & 255;
                int g = buffer.get(pos + 1) & 255;
                int b = buffer.get(pos + 2) & 255;

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));

                if (min < 175 || max - min > 55) {
                    continue;
                }

                whiteCount++;

                for (int a = 0; a < angleCount; a++) {

                    int rho = (int) Math.round(
                            x * cos[a] + y * sin[a]
                    );

                    rho += diagonal;

                    if (rho >= 0 && rho < rhoSize) {
                        accumulator[a][rho]++;
                    }
                }
            }
        }

        if (whiteCount < 25) {
            sendLines(null, width, height);
            return;
        }

        // Find first line
        int bestAngle = -1;
        int bestRho = -1;
        int bestVotes = 0;

        for (int a = 0; a < angleCount; a++) {

            for (int r = 0; r < rhoSize; r++) {

                int votes = accumulator[a][r];

                if (votes > bestVotes) {
                    bestVotes = votes;
                    bestAngle = a;
                    bestRho = r;
                }
            }
        }

        if (bestAngle < 0 || bestVotes < 18) {
            sendLines(null, width, height);
            return;
        }

        // Find second line, excluding nearby first-line peaks
        int secondAngle = -1;
        int secondRho = -1;
        int secondVotes = 0;

        for (int a = 0; a < angleCount; a++) {

            for (int r = 0; r < rhoSize; r++) {

                if (Math.abs(a - bestAngle) <= 6
                        && Math.abs(r - bestRho) <= 12) {
                    continue;
                }

                int votes = accumulator[a][r];

                if (votes > secondVotes) {
                    secondVotes = votes;
                    secondAngle = a;
                    secondRho = r;
                }
            }
        }

        float[] first = lineToBounds(
                bestAngle,
                bestRho,
                diagonal,
                cos,
                sin,
                left,
                top,
                right,
                bottom
        );

        float[] second = null;

        if (secondAngle >= 0
                && secondVotes >= Math.max(12, bestVotes / 3)) {

            second = lineToBounds(
                    secondAngle,
                    secondRho,
                    diagonal,
                    cos,
                    sin,
                    left,
                    top,
                    right,
                    bottom
            );
        }

        if (first == null) {
            sendLines(null, width, height);
            return;
        }

        float[] result;

        if (second != null) {

            result = new float[] {
                    first[0] * step,
                    first[1] * step,
                    first[2] * step,
                    first[3] * step,

                    second[0] * step,
                    second[1] * step,
                    second[2] * step,
                    second[3] * step
            };

        } else {

            result = new float[] {
                    first[0] * step,
                    first[1] * step,
                    first[2] * step,
                    first[3] * step
            };
        }

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

        if (points.size() < 2) {
            return null;
        }

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

        if (first == null || second == null) {
            return null;
        }

        return new float[] {
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

        points.add(new float[] {
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
            
