
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

            int[] orientedSize = getOrientedSize(
                    wm.getDefaultDisplay(), metrics);
            final int width = orientedSize[0];
            final int height = orientedSize[1];
            captureRotation = orientedSize[2];

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
    private int captureRotation = -1;

    /*
     * Some devices report the natural portrait metrics to a Service even
     * while the foreground game is landscape. Normalize dimensions using
     * the real display rotation before creating/resizing the virtual display.
     */
    private int[] getOrientedSize(
            android.view.Display display, DisplayMetrics metrics) {
        int rotation = display.getRotation();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        boolean landscape =
                rotation == android.view.Surface.ROTATION_90 ||
                rotation == android.view.Surface.ROTATION_270;

        if (landscape && height > width) {
            int temp = width;
            width = height;
            height = temp;
        } else if (!landscape && width > height) {
            int temp = width;
            width = height;
            height = temp;
        }

        return new int[] { width, height, rotation };
    }
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
        int[] orientedSize = getOrientedSize(wm.getDefaultDisplay(), m);
        int newWidth = orientedSize[0];
        int newHeight = orientedSize[1];
        int newRotation = orientedSize[2];
        if (newWidth == captureWidth &&
                newHeight == captureHeight &&
                newRotation == captureRotation) return;
        captureWidth = newWidth;
        captureHeight = newHeight;
        captureRotation = newRotation;
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
                int rawWhiteCount = points.size();

                // A near-100% white mask means the captured buffer is invalid
                // or its pixel layout is being interpreted incorrectly.
                // Never run Hough/extension on such a frame.
                int roiArea =
                        (imageWidth * 11 / 12 - imageWidth / 12) *
                        (imageHeight * 9 / 10 - imageHeight / 10);
                boolean saturatedMask =
                        roiArea > 0 && rawWhiteCount > roiArea * 0.20;

                if (saturatedMask) {
                    points.clear();
                }

                List<Line> lines = saturatedMask
                        ? new ArrayList<>()
                        : findConnectedLines(points, imageWidth, imageHeight);

                float[] extendedGuide = saturatedMask
                        ? null
                        : buildExtendedGuide(
                                lines, points, imageWidth, imageHeight);
                sendLines(extendedGuide, imageWidth, imageHeight);

                if (now - lastNotificationTime >= 1500) {
                    lastNotificationTime = now;

                    String report = makeReport(
                            lines,
                            rawWhiteCount,
                            imageWidth,
                            imageHeight,
                            imageWidth,
                            imageHeight
                    );
                    report += "\\nRotation: " + captureRotation
                            + " | Mask: "
                            + (saturatedMask ? "INVALID" : "OK")
                            + " | Stride: "
                            + image.getPlanes()[0].getRowStride()
                            + "/" + image.getPlanes()[0].getPixelStride()
                            + " | Pixel: " + samplePixel(image, imageWidth / 2,
                                    imageHeight / 2);
                    updateNotification(report);
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

    private String samplePixel(Image image, int x, int y) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer().duplicate();
            int pos = y * plane.getRowStride() + x * plane.getPixelStride();
            if (pos < 0 || pos + 3 >= buffer.limit()) return "out";
            return (buffer.get(pos) & 255) + ","
                    + (buffer.get(pos + 1) & 255) + ","
                    + (buffer.get(pos + 2) & 255) + ","
                    + (buffer.get(pos + 3) & 255);
        } catch (Exception e) {
            return "err";
        }
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

        int peakLimit = Math.min(peaks.size(), 240);

        for (int pi = 0;
             pi < peakLimit && result.size() < 160;
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

        // Show short candidates first: these are the post-contact guides
        // the extension logic is expected to select, not the long cue line.
        List<Line> diagnostic = new ArrayList<>();
        for (Line candidate : lines) {
            if (candidate.length() >= 10f && candidate.length() <= 900f) {
                diagnostic.add(candidate);
            }
        }
        Collections.sort(diagnostic,
                (a, b) -> Float.compare(b.votes, a.votes));
        int shown = Math.min(diagnostic.size(), 6);

        for (int i = 0; i < shown; i++) {

            Line line = diagnostic.get(i);

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

    private boolean linePassesNearPoint(
            Line line, float px, float py, float tolerance) {
        float dx = line.x2 - line.x1;
        float dy = line.y2 - line.y1;
        float lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1f) return false;
        float t = ((px - line.x1) * dx + (py - line.y1) * dy)
                / lengthSquared;
        // Permit a modest extrapolation because Hough fragments can stop
        // just short of the visible reticle intersection.
        if (t < -0.45f || t > 1.45f) return false;
        float qx = line.x1 + t * dx;
        float qy = line.y1 + t * dy;
        return Math.hypot(px - qx, py - qy) <= tolerance;
    }

    private float[] buildExtendedGuide(
            List<Line> lines, List<Point> points,
            int width, int height) {

        if (lines == null || lines.size() < 2 || points == null) return null;

        /*
         * Only short post-contact guides are eligible. The long incoming
         * cue-ball guide is deliberately excluded. A valid pair must:
         *  - have a shared endpoint close to the contact marker,
         *  - point in clearly different directions,
         *  - have visible white-pixel support around the shared marker.
         */
        Line bestA = null;
        Line bestB = null;
        float bestScore = Float.POSITIVE_INFINITY;
        float bestOriginX = 0f;
        float bestOriginY = 0f;
        float bestAOuterX = 0f, bestAOuterY = 0f;
        float bestBOuterX = 0f, bestBOuterY = 0f;

        // Reject only segments that terminate at a true multi-direction
        // reticle hub. A simple shared-endpoint test was too aggressive and
        // removed valid short guides. Here, two other differently-directed
        // detected lines must geometrically pass through the same endpoint.
        List<Line> eligible = new ArrayList<>();
        for (Line candidate : lines) {
            boolean reticleSpoke = false;
            float[][] ends = {
                    {candidate.x1, candidate.y1},
                    {candidate.x2, candidate.y2}
            };
            // Only suppress short spokes at the reticle. Long rays
            // that continue from the contact point to a cushion must remain
            // available as post-contact candidates.
            if (candidate.length() <= 190f) {
            for (float[] end : ends) {
                List<Float> hubAngles = new ArrayList<>();
                for (Line other : lines) {
                    if (other == candidate) continue;
                    if (linePassesNearPoint(other, end[0], end[1], 10f)) {
                        float angle = other.angle();
                        boolean distinct = true;
                        for (Float known : hubAngles) {
                            if (angleDifference(known, angle) < 18f) {
                                distinct = false;
                                break;
                            }
                        }
                        if (distinct) hubAngles.add(angle);
                    }
                }
                if (hubAngles.size() >= 2) {
                    reticleSpoke = true;
                    break;
                }
            }
            }
            if (!reticleSpoke) eligible.add(candidate);
        }

        for (int i = 0; i < eligible.size(); i++) {
            Line a = eligible.get(i);
            float lenA = a.length();
            if (lenA < 80f || lenA > 900f) continue;

            for (int j = i + 1; j < eligible.size(); j++) {
                Line b = eligible.get(j);
                float lenB = b.length();
                if (lenB < 80f || lenB > 900f) continue;

                float angle = angleDifference(a.angle(), b.angle());
                if (angle < 32f) continue;

                float[][] ae = {{a.x1, a.y1}, {a.x2, a.y2}};
                float[][] be = {{b.x1, b.y1}, {b.x2, b.y2}};

                for (int ai = 0; ai < 2; ai++) {
                    for (int bi = 0; bi < 2; bi++) {
                        float ax = ae[ai][0], ay = ae[ai][1];
                        float bx = be[bi][0], by = be[bi][1];
                        float gap = (float) Math.hypot(ax - bx, ay - by);

                        // The two guide segments usually stop at opposite
                        // edges of the colored contact ball, not at one
                        // identical pixel. Allow a ball-sized gap; this is
                        // independent of screen position and shot direction.
                        if (gap > 92f) continue;

                        float ox = (ax + bx) * 0.5f;
                        float oy = (ay + by) * 0.5f;

                        int ao = 1 - ai;
                        int bo = 1 - bi;
                        float aOuterX = ae[ao][0], aOuterY = ae[ao][1];
                        float bOuterX = be[bo][0], bOuterY = be[bo][1];

                        float tableLeft = width * 0.195f;
                        float tableRight = width * 0.805f;
                        float tableTop = height * 0.237f;
                        float tableBottom = height * 0.914f;
                        float aCushionDistance = Math.min(
                                Math.min(Math.abs(aOuterX - tableLeft),
                                        Math.abs(aOuterX - tableRight)),
                                Math.min(Math.abs(aOuterY - tableTop),
                                        Math.abs(aOuterY - tableBottom)));
                        float bCushionDistance = Math.min(
                                Math.min(Math.abs(bOuterX - tableLeft),
                                        Math.abs(bOuterX - tableRight)),
                                Math.min(Math.abs(bOuterY - tableTop),
                                        Math.abs(bOuterY - tableBottom)));

                        // Both outgoing prediction rays should terminate at
                        // a table cushion. The incoming cue guide ends at the
                        // cue ball inside the table and is rejected here.
                        if (aCushionDistance > 65f || bCushionDistance > 65f) {
                            continue;
                        }

                        // Prefer endpoints whose midpoint is surrounded by
                        // visible guide/ball-edge pixels, but don't require
                        // a white center: the ball itself is colored.
                        int markerSupport = whitePointsNear(points, ox, oy);

                        float score = Math.abs(gap - 56f) * 1.2f
                                - markerSupport * 0.15f
                                - Math.min(a.votes, b.votes) * 0.015f
                                + Math.abs(lenA - lenB) * 0.02f;

                        if (score < bestScore) {
                            bestScore = score;
                            bestA = a;
                            bestB = b;
                            bestOriginX = ox;
                            bestOriginY = oy;

                            bestAOuterX = aOuterX;
                            bestAOuterY = aOuterY;
                            bestBOuterX = bOuterX;
                            bestBOuterY = bOuterY;
                        }
                    }
                }
            }
        }

        if (bestA == null || bestB == null) return null;

        // Reject nearly parallel/duplicate segments even if Hough produced
        // slightly different endpoints for the same physical guide.
        if (angleDifference(bestA.angle(), bestB.angle()) < 32f) return null;

        float left = width * 0.195f;
        float right = width * 0.805f;
        float top = height * 0.237f;
        float bottom = height * 0.914f;

        // Extend each detected short guide from the shared contact marker
        // along its own detected direction, never from the cue-ball line.
        return TableGeometry.extendTwoLinesFromOrigins(
                bestOriginX, bestOriginY,
                bestAOuterX - bestOriginX, bestAOuterY - bestOriginY,
                bestOriginX, bestOriginY,
                bestBOuterX - bestOriginX, bestBOuterY - bestOriginY,
                left, top, right, bottom);
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
