
package com.saqib.poolpro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import java.util.Collections;
import java.util.List;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_LINE = "com.saqib.poolpro.AIM_LINE";

    private static final String TAG = "PoolProCapture";
    private static final String CHANNEL_ID = "pool_capture";
    private static final int NOTIFICATION_ID = 7;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;

    private long lastFrameTime;
    private long lastNotificationTime;
    private int frameCount;

    private static class Point {
        float x, y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class Line {
        float x1, y1, x2, y2;
        int votes;

        Line(float x1, float y1, float x2, float y2, int votes) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.votes = votes;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID,
                buildNotification("Live detection ready"));
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
                    (MediaProjectionManager) getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE);

            projection = manager.getMediaProjection(resultCode, data);

            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            DisplayMetrics metrics = new DisplayMetrics();
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);

            wm.getDefaultDisplay().getRealMetrics(metrics);

            final int width = metrics.widthPixels;
            final int height = metrics.heightPixels;

            workerThread = new HandlerThread("PoolWhiteDetector");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());

            imageReader = ImageReader.newInstance(
                    width, height, PixelFormat.RGBA_8888, 3);

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;

                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;

                    long now = System.currentTimeMillis();

                    if (now - lastFrameTime < 180) return;
                    lastFrameTime = now;
                    frameCount++;

                    // Ab moving pixels nahi, tamam visible white pixels check honge.
                    List<Point> whitePoints = getWhitePoints(image);

                    List<Line> candidates =
                            findCandidates(whitePoints, width, height);

                    Line[] pair = findConnectedPair(candidates);

                    if (pair != null) {
                        sendLines(new float[]{
                                pair[0].x1, pair[0].y1,
                                pair[0].x2, pair[0].y2,
                                pair[1].x1, pair[1].y1,
                                pair[1].x2, pair[1].y2
                        }, width, height);
                    } else {
                        sendLines(null, width, height);
                    }

                    if (now - lastNotificationTime >= 1200) {
                        lastNotificationTime = now;

                        String info = "Frames: " + frameCount
                                + " | White pixels: " + whitePoints.size()
                                + " | Candidates: " + candidates.size()
                                + " | Pair: " + (pair == null ? "0" : "2");

                        updateNotification(info);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Detection error", e);
                    sendLines(null, width, height);
                } finally {
                    if (image != null) image.close();
                }
            }, worker);

            projection.registerCallback(new MediaProjection.Callback() {
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

            updateNotification("Stationary white-line detection running");

        } catch (Exception e) {
            Log.e(TAG, "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    /*
     * Har frame ke visible white pixels RAM mein read karta hai.
     * Koi screenshot ya image file save nahi hoti.
     */
    private List<Point> getWhitePoints(Image image) {

        List<Point> points = new ArrayList<>();

        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return points;

        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();

        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        int step = 2;

        int left = width / 12;
        int right = width * 11 / 12;
        int top = height / 10;
        int bottom = height * 9 / 10;

        for (int y = top; y < bottom; y += step) {
            for (int x = left; x < right; x += step) {

                int pos = y * rowStride + x * pixelStride;

                if (pos + 2 >= buffer.limit()) continue;

                int r = buffer.get(pos) & 255;
                int g = buffer.get(pos + 1) & 255;
                int b = buffer.get(pos + 2) & 255;

                int min = Math.min(r, Math.min(g, b));
                int max = Math.max(r, Math.max(g, b));

                // White pixels only.
                boolean white = min >= 205 && max - min <= 35;

                if (white) {
                    points.add(new Point(x, y));
                }
            }
        }

        return points;
    }

    private List<Line> findCandidates(
            List<Point> points, int width, int height) {

        List<Line> result = new ArrayList<>();

        if (points.size() < 8) return result;

        List<Point> remaining = new ArrayList<>(points);

        for (int attempt = 0; attempt < 5; attempt++) {

            Line line = findOneLine(remaining, width, height);

            if (line == null) break;

            result.add(line);

            List<Point> filtered = new ArrayList<>();

            for (Point p : remaining) {
                if (distanceFromLine(p, line) > 5) {
                    filtered.add(p);
                }
            }

            remaining = filtered;
        }

        return result;
    }

    private Line findOneLine(
            List<Point> points, int width, int height) {

        if (points.size() < 6) return null;

        double diagonal = Math.hypot(width, height);
        int rhoStep = 2;
        int rhoCount = (int) (2 * diagonal / rhoStep) + 2;

        int[][] accumulator = new int[180][rhoCount];

        double[] cos = new double[180];
        double[] sin = new double[180];

        for (int a = 0; a < 180; a++) {
            double rad = Math.toRadians(a);
            cos[a] = Math.cos(rad);
            sin[a] = Math.sin(rad);
        }

        for (Point p : points) {
            for (int a = 0; a < 180; a++) {

                double rho = p.x * cos[a] + p.y * sin[a];
                int index = (int) Math.round(
                        (rho + diagonal) / rhoStep);

                if (index >= 0 && index < rhoCount) {
                    accumulator[a][index]++;
                }
            }
        }

        int bestAngle = -1;
        int bestRho = -1;
        int bestVotes = 0;

        for (int a = 0; a < 180; a++) {
            for (int r = 0; r < rhoCount; r++) {

                if (accumulator[a][r] > bestVotes) {
                    bestVotes = accumulator[a][r];
                    bestAngle = a;
                    bestRho = r;
                }
            }
        }

        if (bestAngle < 0 || bestVotes < 5) return null;

        double c = cos[bestAngle];
        double s = sin[bestAngle];
        double rho = bestRho * rhoStep - diagonal;

        List<Double> projections = new ArrayList<>();

        for (Point p : points) {

            double distance = Math.abs(
                    p.x * c + p.y * s - rho);

            if (distance <= 4) {
                projections.add(-p.x * s + p.y * c);
            }
        }

        if (projections.size() < 5) return null;

        Collections.sort(projections);

        double start = projections.get(0);
        double previous = start;
        double bestStart = start;
        double bestEnd = start;

        for (int i = 1; i < projections.size(); i++) {

            double value = projections.get(i);

            if (value - previous > 14) {

                if (previous - start > bestEnd - bestStart) {
                    bestStart = start;
                    bestEnd = previous;
                }

                start = value;
            }

            previous = value;
        }

        if (previous - start > bestEnd - bestStart) {
            bestStart = start;
            bestEnd = previous;
        }

        double length = bestEnd - bestStart;

        // Chhoti aur lambi dono lines allow hain.
        if (length < 10) return null;

        float x1 = (float) (rho * c - bestStart * s);
        float y1 = (float) (rho * s + bestStart * c);
        float x2 = (float) (rho * c - bestEnd * s);
        float y2 = (float) (rho * s + bestEnd * c);

        return new Line(x1, y1, x2, y2, bestVotes);
    }

    private Line[] findConnectedPair(List<Line> lines) {

        Line first = null;
        Line second = null;
        double bestDistance = Double.MAX_VALUE;

        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {

                Line a = lines.get(i);
                Line b = lines.get(j);

                double angleA = Math.atan2(
                        a.y2 - a.y1, a.x2 - a.x1);

                double angleB = Math.atan2(
                        b.y2 - b.y1, b.x2 - b.x1);

                double angleDiff = Math.abs(angleA - angleB);
                angleDiff = Math.min(angleDiff, Math.PI - angleDiff);

                if (angleDiff < Math.toRadians(12)) continue;

                double d = Math.min(
                        Math.min(
                                pointDistance(a.x1, a.y1, b.x1, b.y1),
                                pointDistance(a.x1, a.y1, b.x2, b.y2)),
                        Math.min(
                                pointDistance(a.x2, a.y2, b.x1, b.y1),
                                pointDistance(a.x2, a.y2, b.x2, b.y2))
                );

                if (d < bestDistance && d <= 28) {
                    bestDistance = d;
                    first = a;
                    second = b;
                }
            }
        }

        if (first == null || second == null) return null;

        return new Line[]{first, second};
    }

    private double pointDistance(
            double x1, double y1, double x2, double y2) {

        return Math.hypot(x1 - x2, y1 - y2);
    }

    private double distanceFromLine(Point p, Line line) {

        double dx = line.x2 - line.x1;
        double dy = line.y2 - line.y1;
        double length = Math.hypot(dx, dy);

        if (length < 1) return Double.MAX_VALUE;

        return Math.abs(
                dx * (line.y1 - p.y)
                        - (line.x1 - p.x) * dy
        ) / length;
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

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Pool live capture",
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager nm =
                    getSystemService(NotificationManager.class);

            nm.createNotificationChannel(channel);
        }
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

    private void updateNotification(String message) {

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        nm.notify(NOTIFICATION_ID, buildNotification(message));
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
