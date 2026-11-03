package com.saqib.poolpro;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_LINES = "com.saqib.poolpro.LINES";

    private static final String CHANNEL = "pool_capture";
    private static final String TAG = "PoolPro";

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;

    private int screenW, screenH;
    private long lastFrame;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Pool Pro Capture",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);

            startForeground(7, new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Live guide detection")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build());
        } else {
            startForeground(7, new Notification.Builder(this)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Live guide detection")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || projection != null) return START_NOT_STICKY;

        int result = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
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
                            MEDIA_PROJECTION_SERVICE);

            projection = manager.getMediaProjection(result, data);

            DisplayMetrics dm = new DisplayMetrics();
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);

            screenW = dm.widthPixels;
            screenH = dm.heightPixels;

            thread = new HandlerThread("PoolLiveDetector");
            thread.start();
            handler = new Handler(thread.getLooper());

            reader = ImageReader.newInstance(
                    screenW, screenH, PixelFormat.RGBA_8888, 3);

            reader.setOnImageAvailableListener(r -> {
                Image image = null;
                try {
                    image = r.acquireLatestImage();
                    if (image == null) return;

                    long now = System.currentTimeMillis();
                    if (now - lastFrame < 250) return;
                    lastFrame = now;

                    detectLines(image);

                } catch (Exception e) {
                    Log.e(TAG, "Detection error", e);
                } finally {
                    if (image != null) image.close();
                }
            }, handler);

            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            }, handler);

            display = projection.createVirtualDisplay(
                    "PoolLiveCapture",
                    screenW, screenH, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);

            Log.d(TAG, "Live capture started " + screenW + "x" + screenH);

        } catch (Exception e) {
            Log.e(TAG, "Could not start capture", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    /*
     * Hough-style white-line detector.
     * It searches for long, bright, nearly-neutral lines in the
     * central gameplay area. It does not save screenshots.
     */
    private void detectLines(Image image) {

        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return;

        ByteBuffer buffer = planes[0].getBuffer().duplicate();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();

        int gw = 320;
        int gh = Math.max(180, (int) (320f * screenH / screenW));

        int angleCount = 60; // 3-degree increments
        int diag = (int) Math.sqrt(gw * gw + gh * gh);
        int rhoCount = diag * 2 + 1;
        int[][] acc = new int[angleCount][rhoCount];

        double[] cos = new double[angleCount];
        double[] sin = new double[angleCount];

        for (int a = 0; a < angleCount; a++) {
            double rad = Math.toRadians(a * 3);
            cos[a] = Math.cos(rad);
            sin[a] = Math.sin(rad);
        }

        int xStart = gw / 12;
        int xEnd = gw - xStart;
        int yStart = gh / 8;
        int yEnd = gh - gh / 8;

        for (int gy = yStart; gy < yEnd; gy += 1) {
            int sy = gy * screenH / gh;

            for (int gx = xStart; gx < xEnd; gx += 1) {
                int sx = gx * screenW / gw;
                int index = sy * rowStride + sx * pixelStride;

                if (index + 2 >= buffer.limit()) continue;

                int r = buffer.get(index) & 255;
                int g = buffer.get(index + 1) & 255;
                int b = buffer.get(index + 2) & 255;

                boolean white = r > 205 && g > 205 && b > 205
                        && Math.abs(r - g) < 38
                        && Math.abs(g - b) < 38;

                if (!white) continue;

                for (int a = 0; a < angleCount; a++) {
                    int rho = (int) Math.round(
                            gx * cos[a] + gy * sin[a]) + diag;

                    if (rho >= 0 && rho < rhoCount) {
                        acc[a][rho]++;
                    }
                }
            }
        }

        ArrayList<Line> found = new ArrayList<>();

        for (int n = 0; n < 2; n++) {
            int bestA = -1, bestR = -1, bestVotes = 0;

            for (int a = 0; a < angleCount; a++) {
                for (int r = 0; r < rhoCount; r++) {
                    if (acc[a][r] > bestVotes) {
                        bestVotes = acc[a][r];
                        bestA = a;
                        bestR = r;
                    }
                }
            }

            // Ignore short or weak white features.
            if (bestA < 0 || bestVotes < 18) break;

            double theta = Math.toRadians(bestA * 3);
            double rho = bestR - diag;

            float[] p = clipLine(
                    (float) Math.cos(theta),
                    (float) Math.sin(theta),
                    (float) rho,
                    screenW * 0.08f,
                    screenH * 0.12f,
                    screenW * 0.92f,
                    screenH * 0.88f
            );

            if (p != null) {
                found.add(new Line(p[0], p[1], p[2], p[3]));
            }

            // Suppress nearby peaks so the same line is not selected twice.
            for (int a = 0; a < angleCount; a++) {
                int angleDiff = Math.abs(a - bestA);
                angleDiff = Math.min(angleDiff, angleCount - angleDiff);

                if (angleDiff <= 3) {
                    for (int rr = 0; rr < rhoCount; rr++) {
                        if (Math.abs(rr - bestR) < 22) {
                            acc[a][rr] = 0;
                        }
                    }
                }
            }
        }

        Intent out = new Intent(ACTION_LINES);
        out.setPackage(getPackageName());

        if (found.size() > 0) {
            float[] values = new float[found.size() * 4];

            for (int i = 0; i < found.size(); i++) {
                Line line = found.get(i);
                values[i * 4] = line.x1;
                values[i * 4 + 1] = line.y1;
                values[i * 4 + 2] = line.x2;
                values[i * 4 + 3] = line.y2;
            }

            out.putExtra("lines", values);
        } else {
            out.putExtra("lines", new float[0]);
        }

        sendBroadcast(out);
    }

    // Clip an infinite Hough line to the gameplay rectangle.
    private float[] clipLine(float a, float b, float rho,
                             float left, float top,
                             float right, float bottom) {

        ArrayList<float[]> pts = new ArrayList<>();

        if (Math.abs(b) > 0.0001f) {
            addPoint(pts, left, (rho - a * left) / b,
                    left, top, right, bottom);
            addPoint(pts, right, (rho - a * right) / b,
                    left, top, right, bottom);
        }

        if (Math.abs(a) > 0.0001f) {
            addPoint(pts, (rho - b * top) / a, top,
                    left, top, right, bottom);
            addPoint(pts, (rho - b * bottom) / a, bottom,
                    left, top, right, bottom);
        }

        if (pts.size() < 2) return null;

        float[] p1 = pts.get(0);
        float[] p2 = pts.get(1);

        return new float[]{p1[0], p1[1], p2[0], p2[1]};
    }

    private void addPoint(ArrayList<float[]> pts,
                          float x, float y,
                          float left, float top,
                          float right, float bottom) {
        if (x < left || x > right || y < top || y > bottom) return;

        for (float[] p : pts) {
            if (Math.abs(p[0] - x) < 2 && Math.abs(p[1] - y) < 2) return;
        }
        pts.add(new float[]{x, y});
    }

    private static class Line {
        float x1, y1, x2, y2;
        Line(float a, float b, float c, float d) {
            x1 = a; y1 = b; x2 = c; y2 = d;
        }
    }

    @Override
    public void onDestroy() {
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (thread != null) thread.quitSafely();

        display = null;
        reader = null;
        projection = null;
        thread = null;

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
