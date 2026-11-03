
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
                    width, height, PixelFormat.RGBA_8888, 2);

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;

                try {
                    image = reader.acquireLatestImage();

                    if (image == null) return;

                    long now = System.currentTimeMillis();

                    if (now - lastFrameTime < 180) return;

                    lastFrameTime = now;

                    detectAimLine(image);

                } catch (Exception e) {
                    Log.e("PoolPro", "Aim detection error", e);
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

    private void detectAimLine(Image image) {

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

        // Approximate playing-area crop.
        // This is a starting estimate, not exact cushion detection.
        int left = (int)(gw * 0.10f);
        int right = (int)(gw * 0.90f);
        int top = (int)(gh * 0.12f);
        int bottom = (int)(gh * 0.92f);

        int angleCount = 180;
        int diagonal = (int)Math.sqrt(gw * gw + gh * gh);
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

        // Find bright, nearly neutral pixels.
        for (int y = top; y < bottom; y++) {

            for (int x = left; x < right; x++) {

                int px = x * step;
                int py = y * step;

                int pos = py * rowStride + px * pixelStride;

                if (pos < 0 || pos + 2 >= buffer.limit()) continue;

                int r = buffer.get(pos) & 255;
                int g = buffer.get(pos + 1) & 255;
                int b = buffer.get(pos + 2) & 255;

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));

                // Bright neutral pixels only.
                if (min < 175 || max - min > 55) continue;

                whiteCount++;

                for (int a = 0; a < angleCount; a++) {

                    int rho = (int)Math.round(
                            x * cos[a] + y * sin[a]);

                    rho += diagonal;

                    if (rho >= 0 && rho < rhoSize) {
                        accumulator[a][rho]++;
                    }
                }
            }
        }

        int bestAngle = -1;
        int bestRho = -1;
        int bestVotes = 0;

        // Find strongest straight-line candidate.
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

        // Avoid displaying random short highlights.
        if (bestAngle < 0 || bestVotes < 18 || whiteCount < 25) {
            sendLine(null, width, height);
            return;
        }

        double nx = cos[bestAngle];
        double ny = sin[bestAngle];

        double rho = bestRho - diagonal;

        // Line equation: x*cos + y*sin = rho
        // Find intersections with estimated playing-area bounds.
        double x1 = Double.NaN;
        double y1 = Double.NaN;
        double x2 = Double.NaN;
        double y2 = Double.NaN;

        double[] p = new double[2];

        if (Math.abs(ny) > 0.0001) {

            double y = (rho - left * nx) / ny;
            if (y >= top && y <= bottom) {
                x1 = left;
                y1 = y;
            }

            y = (rho - right * nx) / ny;
            if (y >= top && y <= bottom) {
                if (Double.isNaN(x1)) {
                    x1 = right;
                    y1 = y;
                } else {
                    x2 = right;
                    y2 = y;
                }
            }
        }

        if (Math.abs(nx) > 0.0001) {

            double x = (rho - top * ny) / nx;
            if (x >= left && x <= right) {
                if (Double.isNaN(x1)) {
                    x1 = x;
                    y1 = top;
                } else if (Double.isNaN(x2)) {
                    x2 = x;
                    y2 = top;
                }
            }

            x = (rho - bottom * ny) / nx;
            if (x >= left && x <= right) {
                if (Double.isNaN(x1)) {
                    x1 = x;
                    y1 = bottom;
                } else if (Double.isNaN(x2)) {
                    x2 = x;
                    y2 = bottom;
                }
            }
        }

        if (Double.isNaN(x1) || Double.isNaN(x2)) {
            sendLine(null, width, height);
            return;
        }

        float[] line = new float[] {
                (float)(x1 * step),
                (float)(y1 * step),
                (float)(x2 * step),
                (float)(y2 * step)
        };

        sendLine(line, width, height);
    }

    private void sendLine(float[] line, int width, int height) {

        Intent update = new Intent(ACTION_LINE);
        update.setPackage(getPackageName());

        if (line != null) {
            update.putExtra("line", line);
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
