
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
import java.util.Locale;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_LINE =
            "com.saqib.poolpro.AIM_LINE";

    private static final String TAG = "PoolProDiagnosticV3";
    private static final String CHANNEL_ID = "pool_capture";
    private static final int NOTIFICATION_ID = 7;

    private static final int SAMPLE_STEP = 1;
    private static final int WHITE_MIN = 185;
    private static final int WHITE_SPREAD = 60;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;

    private long lastFrameTime;
    private long lastNotificationTime;
    private int frameCount;

    private static class Point {
        int x, y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class Line {
        float x1, y1, x2, y2;
        int votes;
        float error;

        Line(float x1, float y1, float x2, float y2,
             int votes, float error) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.votes = votes;
            this.error = error;
        }

        float length() {
            return (float) Math.hypot(x2 - x1, y2 - y1);
        }

        float angle() {
            return (float) Math.toDegrees(
                    Math.atan2(y2 - y1, x2 - x1));
        }

        float centerX() {
            return (x1 + x2) / 2f;
        }

        float centerY() {
            return (y1 + y2) / 2f;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(
                NOTIFICATION_ID,
                buildNotification("White-line detector starting")
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null || projection != null) {
            return START_NOT_STICKY;
        }

        int resultCode =
                intent.getIntExtra(EXTRA_RESULT_CODE, 0);

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

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE);

            projection = manager.getMediaProjection(
                    resultCode, data);

            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            DisplayMetrics metrics = new DisplayMetrics();

            android.view.WindowManager wm =
                    (android.view.WindowManager)
                            getSystemService(WINDOW_SERVICE);

            wm.getDefaultDisplay().getRealMetrics(metrics);

            final int width = metrics.widthPixels;
            final int height = metrics.heightPixels;

            workerThread =
                    new HandlerThread("PoolDetectorWorker");
            workerThread.start();

            worker = new Handler(workerThread.getLooper());

            captureWidth = width;
            captureHeight = height;
            captureDensityDpi = metrics.densityDpi;
            imageReader = createCaptureReader(width, height);
            displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            displayManager.registerDisplayListener(displayListener, worker);


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
                    "SaqibPoolDetector",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    worker
            );

            updateNotification("White-line detector running");

        } catch (Exception e) {
            Log.e(TAG, "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }


    private int captureWidth;
    private int captureHeight;
    private int captureDensityDpi;
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY && worker != null) {
                worker.post(() -> refreshCaptureSize());
            }
        }
    };

    private void refreshCaptureSize() {
        if (virtualDisplay == null || imageReader == null) return;
        DisplayMetrics m = new DisplayMetrics();
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(m);
        if (m.widthPixels == captureWidth && m.heightPixels == captureHeight) return;
        captureWidth = m.widthPixels;
        captureHeight = m.heightPixels;
        captureDensityDpi = m.densityDpi;
        ImageReader oldReader = imageReader;
        imageReader = null;
        ImageReader newReader = createCaptureReader(captureWidth, captureHeight);
        virtualDisplay.resize(captureWidth, captureHeight, captureDensityDpi);
        virtualDisplay.setSurface(newReader.getSurface());
        oldReader.close();
        updateNotification("Capture resized: " + captureWidth + "x" + captureHeight);
    }

    private ImageReader createCaptureReader(int width, int height) {
        imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                3
        );

        imageReader.setOnImageAvailableListener(reader -> {

            Image image = null;

            try {
                image = reader.acquireLatestImage();

                if (image == null) return;

                long now = System.currentTimeMillis();

                if (now - lastFrameTime < 250) return;

                lastFrameTime = now;
                frameCount++;

                int imageWidth = image.getWidth();
                int imageHeight = image.getHeight();

                List<Point> points = getWhitePoints(image);

                List<Line> lines = findConnectedLines(
                        points,
                        imageWidth,
                        imageHeight
                );

                // Extend only the strongest long white guide from the cue-ball
                // end to the estimated inner cushion boundary.
                float[] extendedGuide = buildExtendedGuide(
                        lines, points, imageWidth, imageHeight);
                sendLines(extendedGuide, imageWidth, imageHeight);

                if (now - lastNotificationTime >= 1500) {
                    lastNotificationTime = now;

                    updateNotification(makeReport(
                            lines,
                            points.size(),
                            width,
                            height,
                            imageWidth,
                            imageHeight
                    ));
                }

            } catch (Exception e) {
                Log.e(TAG, "Detector error", e);
                sendClear(width, height);

            } finally {
                if (image != null) {
                    image.close();
                }
            }

        }, worker);
        return imageReader;
    }

    // Screen pixels are processed in RAM. No screenshot is saved.
    private List<Point> getWhitePoints(Image image) {

        List<Point> points = new ArrayList<>();

        Image.Plane[] planes = image.getPlanes();

        if (planes == null || planes.length == 0) {
            return points;
        }

        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();

        int width = image.getWidth();
        int height = image.getHeight();

        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        int left = width / 12;
        int right = width * 11 / 12;
        int top = height / 10;
        int bottom = height * 9 / 10;

        for (int y = top; y < bottom; y += SAMPLE_STEP) {
            for (int x = left; x < right; x += SAMPLE_STEP) {

                int pos = y * rowStride + x * pixelStride;

                if (pos + 2 >= buffer.limit()) {
                    continue;
                }

                int r = buffer.get(pos) & 255;
                int g = buffer.get(pos + 1) & 255;
                int b = buffer.get(pos + 2) & 255;

                int min = Math.min(r, Math.min(g, b));
                int max = Math.max(r, Math.max(g, b));

                if (min >= WHITE_MIN &&
                        max - min <= WHITE_SPREAD) {
                    points.add(new Point(x, y));
                }
            }
        }

        return points;
    }

    /*
     * Hough detector.
     * These are diagnostic candidates, not confirmed game guides.
     */
    private List<Line> findConnectedLines(
            List<Point> points,
            int width,
            int height) {

        List<Line> result = new ArrayList<>();

        if (points == null || points.size() < 8) {
            return result;
        }

        final int thetaCount = 180;

        final int diagonal =
                (int) Math.ceil(Math.hypot(width, height));

        final int rhoCount = diagonal * 2 + 1;

        int[][] accumulator =
                new int[thetaCount][rhoCount];

        float[] cos = new float[thetaCount];
        float[] sin = new float[thetaCount];

        for (int t = 0; t < thetaCount; t++) {
            double angle = Math.toRadians(t);

            cos[t] = (float) Math.cos(angle);
            sin[t] = (float) Math.sin(angle);
        }

        for (Point p : points) {
            for (int t = 0; t < thetaCount; t++) {

                int rho = Math.round(
                        p.x * cos[t] + p.y * sin[t]
                ) + diagonal;

                if (rho >= 0 && rho < rhoCount) {
                    accumulator[t][rho]++;
                }
            }
        }

        class Peak {
            int theta, rho, votes;

            Peak(int theta, int rho, int votes) {
                this.theta = theta;
                this.rho = rho;
                this.votes = votes;
            }
        }

        List<Peak> peaks = new ArrayList<>();

        for (int t = 0; t < thetaCount; t++) {
            for (int r = 0; r < rhoCount; r++) {

                int votes = accumulator[t][r];

                if (votes < 9) continue;

                boolean maximum = true;

                for (int dt = -2; dt <= 2 && maximum; dt++) {
                    for (int dr = -3; dr <= 3; dr++) {

                        if (dt == 0 && dr == 0) continue;

                        int nt =
                                (t + dt + thetaCount) % thetaCount;

                        int nr = r + dr;

                        if (nr >= 0 && nr < rhoCount &&
                                accumulator[nt][nr] > votes) {
                            maximum = false;
                            break;
                        }
                    }
                }

                if (maximum) {
                    peaks.add(new Peak(t, r, votes));
                }
            }
        }

        Collections.sort(
                peaks,
                (a, b) -> Integer.compare(b.votes, a.votes)
        );

        int peakLimit = Math.min(peaks.size(), 100);

        for (int pi = 0;
             pi < peakLimit && result.size() < 80;
             pi++) {

            Peak peak = peaks.get(pi);

            float nx = cos[peak.theta];
            float ny = sin[peak.theta];

            float ux = -ny;
            float uy = nx;

            float targetRho = peak.rho - diagonal;

            List<Float> projections = new ArrayList<>();

            for (Point p : points) {

                float distance = Math.abs(
                        p.x * nx + p.y * ny - targetRho
                );

                if (distance <= 1.8f) {
                    projections.add(p.x * ux + p.y * uy);
                }
            }

            if (projections.size() < 8) continue;

            Collections.sort(projections);

            float segmentStart = projections.get(0);
            float previous = segmentStart;

            int count = 1;

            for (int i = 1; i <= projections.size(); i++) {

                boolean split = i == projections.size();

                if (!split) {
                    split =
                            projections.get(i) - previous > 10.0f;
                }

                if (split) {

                    float segmentEnd = previous;
                    float segmentLength =
                            segmentEnd - segmentStart;

                    if (segmentLength >= 10.0f && count >= 7) {

                        float mx = nx * targetRho;
                        float my = ny * targetRho;

                        Line candidate = new Line(
                                mx + ux * segmentStart,
                                my + uy * segmentStart,
                                mx + ux * segmentEnd,
                                my + uy * segmentEnd,
                                count,
                                1.0f
                        );

                        boolean duplicate = false;

                        for (Line old : result) {

                            float angleDiff = angleDifference(
                                    old.angle(),
                                    candidate.angle()
                            );

                            if (angleDiff >= 3.0f) continue;

                            float dx =
                                    candidate.centerX() - old.centerX();

                            float dy =
                                    candidate.centerY() - old.centerY();

                            float angle =
                                    (float) Math.toRadians(old.angle());

                            float perpendicular = Math.abs(
                                    -dx * (float) Math.sin(angle)
                                    + dy * (float) Math.cos(angle)
                            );

                            if (perpendicular < 4.0f &&
                                    Math.abs(
                                            old.length()
                                            - candidate.length()
                                    ) < 18.0f) {
                                duplicate = true;
                                break;
                            }
                        }

                        if (!duplicate) {
                            result.add(candidate);
                        }
                    }

                    if (i < projections.size()) {
                        segmentStart = projections.get(i);
                        previous = segmentStart;
                        count = 1;
                    }

                } else {
                    previous = projections.get(i);
                    count++;
                }
            }
        }

        Collections.sort(
                result,
                (a, b) -> Float.compare(b.length(), a.length())
        );

        // Keep short contact-direction candidates. Truncating to the
        // longest 18 lines discarded the two short post-contact guides.
        return result;
    }

    private float angleDifference(float a, float b) {

        float diff = Math.abs(a - b) % 180f;

        if (diff > 90f) {
            diff = 180f - diff;
        }

        return diff;
    }

    // Resolution is deliberately placed first to avoid notification truncation.
    private String makeReport(
            List<Line> lines,
            int whiteCount,
            int displayWidth,
            int displayHeight,
            int imageWidth,
            int imageHeight) {

        StringBuilder report = new StringBuilder();

        report.append("Display: ")
                .append(displayWidth)
                .append("x")
                .append(displayHeight)
                .append(" | Image: ")
                .append(imageWidth)
                .append("x")
                .append(imageHeight)
                .append("\nV3 Frames: ")
                .append(frameCount)
                .append(" | White: ")
                .append(whiteCount)
                .append(" | Lines: ")
                .append(lines.size());

        // Only six strongest candidates are shown.
        int shown = Math.min(lines.size(), 6);

        for (int i = 0; i < shown; i++) {

            Line line = lines.get(i);

            report.append("\nL")
                    .append(i + 1)
                    .append(" P1:")
                    .append(Math.round(line.x1))
                    .append(",")
                    .append(Math.round(line.y1))
                    .append(" P2:")
                    .append(Math.round(line.x2))
                    .append(",")
                    .append(Math.round(line.y2))
                    .append(" A:")
                    .append(String.format(
                            Locale.US,
                            "%.0f",
                            line.angle()
                    ))
                    .append(" Len:")
                    .append(Math.round(line.length()))
                    .append(" N:")
                    .append(line.votes);
        }

        return report.toString();
    }

    private int whitePointsNear(List<Point> points, float x, float y) {
        int count = 0;
        for (Point p : points) {
            float dx = p.x - x;
            float dy = p.y - y;
            if (dx * dx + dy * dy <= 22f * 22f) count++;
        }
        return count;
    }

    private float[] buildExtendedGuide(
            List<Line> lines, List<Point> points,
            int width, int height) {

        if (lines == null || lines.size() < 2) return null;

        // The required sources are the TWO short post-contact guide lines.
        // Never select the long cue-ball incoming guide.
        Line first = null;
        Line second = null;
        float bestScore = Float.POSITIVE_INFINITY;

        for (int i = 0; i < lines.size(); i++) {
            Line a = lines.get(i);
            float lenA = a.length();
            if (lenA < 12f || lenA > 240f) continue;

            for (int j = i + 1; j < lines.size(); j++) {
                Line b = lines.get(j);
                float lenB = b.length();
                if (lenB < 12f || lenB > 240f) continue;

                float angle = angleDifference(a.angle(), b.angle());
                if (angle < 18f || angle > 88f) continue;

                // Find the closest pair of endpoints. The two short guide
                // segments should meet at the collision/contact marker.
                float[][] ea = {{a.x1, a.y1}, {a.x2, a.y2}};
                float[][] eb = {{b.x1, b.y1}, {b.x2, b.y2}};
                float gap = Float.MAX_VALUE;

                for (float[] pa : ea) {
                    for (float[] pb : eb) {
                        float d = (float) Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
                        if (d < gap) gap = d;
                    }
                }

                if (gap > 26f) continue;

                // Prefer a tight shared endpoint and two clearly visible
                // short segments, rather than unrelated white table details.
                float score = gap * 5f
                        + Math.abs(lenA - lenB) * 0.12f
                        - Math.min(a.votes, b.votes) * 0.002f;

                if (score < bestScore) {
                    bestScore = score;
                    first = a;
                    second = b;
                }
            }
        }

        if (first == null || second == null) return null;

        // Determine the shared endpoint pair and use its midpoint as the
        // common collision origin. Each ray follows its OWN detected line.
        float bestGap = Float.MAX_VALUE;
        float originX = 0f, originY = 0f;
        float firstOuterX = 0f, firstOuterY = 0f;
        float secondOuterX = 0f, secondOuterY = 0f;

        float[][] fEnds = {{first.x1, first.y1}, {first.x2, first.y2}};
        float[][] sEnds = {{second.x1, second.y1}, {second.x2, second.y2}};

        for (int fi = 0; fi < 2; fi++) {
            for (int si = 0; si < 2; si++) {
                float fx = fEnds[fi][0], fy = fEnds[fi][1];
                float sx = sEnds[si][0], sy = sEnds[si][1];
                float gap = (float) Math.hypot(fx - sx, fy - sy);

                if (gap < bestGap) {
                    bestGap = gap;
                    originX = (fx + sx) * 0.5f;
                    originY = (fy + sy) * 0.5f;

                    int fo = 1 - fi;
                    int so = 1 - si;
                    firstOuterX = fEnds[fo][0];
                    firstOuterY = fEnds[fo][1];
                    secondOuterX = sEnds[so][0];
                    secondOuterY = sEnds[so][1];
                }
            }
        }

        if (bestGap > 26f) return null;

        float left = width * 0.195f;
        float right = width * 0.805f;
        float top = height * 0.237f;
        float bottom = height * 0.914f;

        // Extend BOTH short post-contact rays from their common marker.
        float[] pair = TableGeometry.extendTwoLinesFromOrigins(
                originX, originY,
                firstOuterX - originX, firstOuterY - originY,
                originX, originY,
                secondOuterX - originX, secondOuterY - originY,
                left, top, right, bottom);

        return pair;
    }

    private void sendLines(float[] lines, int width, int height) {
        Intent update = new Intent(ACTION_LINE);
        update.setPackage(getPackageName());
        update.putExtra("width", width);
        update.putExtra("height", height);
        update.putExtra("lines", lines);
        sendBroadcast(update);
    }

    private void sendClear(int width, int height) {
        sendLines(null, width, height);
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Pool live diagnostic",
                            NotificationManager.IMPORTANCE_LOW
                    );

            NotificationManager nm =
                    getSystemService(NotificationManager.class);

            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {

        if (Build.VERSION.SDK_INT >= 26) {

            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(
                            "Saqib Pool Pro - Detector V3")
                    .setContentText(message)
                    .setStyle(
                            new Notification.BigTextStyle()
                                    .bigText(message)
                    )
                    .setSmallIcon(
                            android.R.drawable.ic_menu_camera)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle(
                        "Saqib Pool Pro - Detector V3")
                .setContentText(message)
                .setStyle(
                        new Notification.BigTextStyle()
                                .bigText(message)
                )
                .setSmallIcon(
                        android.R.drawable.ic_menu_camera)
                .build();
    }

    private void updateNotification(String message) {

        NotificationManager nm =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        nm.notify(
                NOTIFICATION_ID,
                buildNotification(message)
        );
    }

    @Override
    public void onDestroy() {

        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayManager = null;
        }

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
            worker = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
