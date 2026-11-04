
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_LINE = "com.saqib.poolpro.AIM_LINE";

    private static final String TAG = "PoolProDiagnosticV2";
    private static final String CHANNEL_ID = "pool_capture";
    private static final int NOTIFICATION_ID = 7;

    // V2: har screen pixel sample hoga.
    private static final int SAMPLE_STEP = 1;

    // White aur anti-aliased white pixels.
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
                buildNotification("White-line Diagnostic V2 starting"));
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
                    (android.view.WindowManager)
                            getSystemService(WINDOW_SERVICE);

            wm.getDefaultDisplay().getRealMetrics(metrics);

            final int width = metrics.widthPixels;
            final int height = metrics.heightPixels;

            workerThread = new HandlerThread("PoolDiagnosticV2Worker");
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

                    if (now - lastFrameTime < 250) return;
                    lastFrameTime = now;
                    frameCount++;

                    List<Point> points = getWhitePoints(image);

                    List<Line> lines =
                            findConnectedLines(points, width, height);

                    // Diagnostic mode: overlay abhi clear rahega.
                    sendClear(width, height);

                    if (now - lastNotificationTime >= 1500) {
                        lastNotificationTime = now;
                        updateNotification(makeReport(
                                lines, points.size(), width, height));
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Diagnostic V2 error", e);
                    sendClear(width, height);
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
                    "SaqibPoolDiagnosticV2",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    worker
            );

            updateNotification("Diagnostic V2 running - no overlay lines");

        } catch (Exception e) {
            Log.e(TAG, "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    /*
     * Pixels sirf RAM mein read hote hain.
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

        int left = width / 12;
        int right = width * 11 / 12;
        int top = height / 10;
        int bottom = height * 9 / 10;

        for (int y = top; y < bottom; y += SAMPLE_STEP) {
            for (int x = left; x < right; x += SAMPLE_STEP) {

                int pos = y * rowStride + x * pixelStride;
                if (pos + 2 >= buffer.limit()) continue;

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
     * White pixels ke connected groups nikalta hai.
     * V2 mein sampling step 1 hai, is liye patli lines
     * ke pixels miss hone ka chance kam hoga.
     */
    private List<Line> findConnectedLines(
            List<Point> points, int width, int height) {

        List<Line> lines = new ArrayList<>();

        boolean[][] occupied = new boolean[height][width];
        boolean[][] visited = new boolean[height][width];

        for (Point p : points) {
            if (p.x >= 0 && p.x < width &&
                    p.y >= 0 && p.y < height) {
                occupied[p.y][p.x] = true;
            }
        }

        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};

        for (Point start : points) {

            int sx = start.x;
            int sy = start.y;

            if (visited[sy][sx]) continue;

            List<Point> component = new ArrayList<>();
            ArrayDeque<Point> queue = new ArrayDeque<>();

            queue.add(new Point(sx, sy));
            visited[sy][sx] = true;

            while (!queue.isEmpty()) {

                Point current = queue.removeFirst();
                component.add(current);

                for (int k = 0; k < 8; k++) {

                    int nx = current.x + dx[k];
                    int ny = current.y + dy[k];

                    if (nx < 0 || ny < 0 ||
                            nx >= width || ny >= height) {
                        continue;
                    }

                    if (occupied[ny][nx] && !visited[ny][nx]) {
                        visited[ny][nx] = true;
                        queue.addLast(new Point(nx, ny));
                    }
                }
            }

            if (component.size() < 5) continue;

            Line line = fitLine(component);

            if (line != null &&
                    line.length() >= 7 &&
                    line.error <= 5.5f) {
                lines.add(line);
            }
        }

        // Ek hi seedhi line ke tootay huay tukray jorna.
        mergeCollinearSegments(lines);

        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Float.compare(b.length(), a.length());
            }
        });

        if (lines.size() > 18) {
            return new ArrayList<>(lines.subList(0, 18));
        }

        return lines;
    }

    /*
     * PCA se component ki direction aur endpoints.
     */
    private Line fitLine(List<Point> points) {

        if (points.size() < 5) return null;

        double meanX = 0;
        double meanY = 0;

        for (Point p : points) {
            meanX += p.x;
            meanY += p.y;
        }

        meanX /= points.size();
        meanY /= points.size();

        double xx = 0;
        double yy = 0;
        double xy = 0;

        for (Point p : points) {
            double dx = p.x - meanX;
            double dy = p.y - meanY;

            xx += dx * dx;
            yy += dy * dy;
            xy += dx * dy;
        }

        double angle = 0.5 * Math.atan2(
                2 * xy, xx - yy);

        double ux = Math.cos(angle);
        double uy = Math.sin(angle);

        double minProjection = Double.MAX_VALUE;
        double maxProjection = -Double.MAX_VALUE;
        double errorSum = 0;

        for (Point p : points) {

            double dx = p.x - meanX;
            double dy = p.y - meanY;

            double projection = dx * ux + dy * uy;

            minProjection = Math.min(minProjection, projection);
            maxProjection = Math.max(maxProjection, projection);

            double perpendicular = -dx * uy + dy * ux;
            errorSum += perpendicular * perpendicular;
        }

        float error = (float) Math.sqrt(
                errorSum / points.size());

        float x1 = (float) (meanX + minProjection * ux);
        float y1 = (float) (meanY + minProjection * uy);
        float x2 = (float) (meanX + maxProjection * ux);
        float y2 = (float) (meanY + maxProjection * uy);

        return new Line(
                x1, y1, x2, y2, points.size(), error);
    }

    /*
     * Collinear components ko combine karta hai.
     * Is se ek lambi aiming line ke tukray ek candidate ban sakte hain.
     */
    private void mergeCollinearSegments(List<Line> lines) {

        boolean changed = true;
        int safety = 0;

        while (changed && safety++ < 80) {

            changed = false;

            outer:
            for (int i = 0; i < lines.size(); i++) {
                for (int j = i + 1; j < lines.size(); j++) {

                    Line a = lines.get(i);
                    Line b = lines.get(j);

                    if (canMerge(a, b)) {
                        Line merged = mergePair(a, b);
                        lines.set(i, merged);
                        lines.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
    }

    private boolean canMerge(Line a, Line b) {

        float angleDiff = angleDifference(a.angle(), b.angle());
        if (angleDiff > 7.0f) return false;

        double radians = Math.toRadians(a.angle());
        float ux = (float) Math.cos(radians);
        float uy = (float) Math.sin(radians);

        float centerDx = b.centerX() - a.centerX();
        float centerDy = b.centerY() - a.centerY();

        float perpendicular = Math.abs(
                -centerDx * uy + centerDy * ux);

        if (perpendicular > 7.0f) return false;

        float a1 = a.x1 * ux + a.y1 * uy;
        float a2 = a.x2 * ux + a.y2 * uy;
        float b1 = b.x1 * ux + b.y1 * uy;
        float b2 = b.x2 * ux + b.y2 * uy;

        float aMin = Math.min(a1, a2);
        float aMax = Math.max(a1, a2);
        float bMin = Math.min(b1, b2);
        float bMax = Math.max(b1, b2);

        float gap = Math.max(
                0f, Math.max(aMin, bMin) - Math.min(aMax, bMax));

        return gap <= 65.0f;
    }

    private float angleDifference(float a, float b) {

        float diff = Math.abs(a - b) % 180f;
        if (diff > 90f) diff = 180f - diff;
        return diff;
    }

    private Line mergePair(Line a, Line b) {

        float radiansA = (float) Math.toRadians(a.angle());
        float radiansB = (float) Math.toRadians(b.angle());

        float ux = (float) (
                Math.cos(radiansA) * a.votes +
                Math.cos(radiansB) * b.votes);

        float uy = (float) (
                Math.sin(radiansA) * a.votes +
                Math.sin(radiansB) * b.votes);

        float norm = (float) Math.hypot(ux, uy);
        if (norm < 0.001f) return a;

        ux /= norm;
        uy /= norm;

        float totalVotes = a.votes + b.votes;

        float cx = (
                a.centerX() * a.votes +
                b.centerX() * b.votes) / totalVotes;

        float cy = (
                a.centerY() * a.votes +
                b.centerY() * b.votes) / totalVotes;

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;

        float[] xs = {a.x1, a.x2, b.x1, b.x2};
        float[] ys = {a.y1, a.y2, b.y1, b.y2};

        for (int i = 0; i < 4; i++) {
            float projection = (xs[i] - cx) * ux +
                    (ys[i] - cy) * uy;

            min = Math.min(min, projection);
            max = Math.max(max, projection);
        }

        float x1 = cx + min * ux;
        float y1 = cy + min * uy;
        float x2 = cx + max * ux;
        float y2 = cy + max * uy;

        float error = (
                a.error * a.votes + b.error * b.votes) /
                totalVotes;

        return new Line(
                x1, y1, x2, y2,
                a.votes + b.votes, error);
    }

    private String makeReport(
            List<Line> lines,
            int whiteCount,
            int screenWidth,
            int screenHeight) {

        StringBuilder report = new StringBuilder();

        report.append("V2 Frames: ").append(frameCount)
                .append(" | White: ").append(whiteCount)
                .append(" | Lines: ").append(lines.size());

        for (int i = 0; i < lines.size(); i++) {

            Line line = lines.get(i);

            report.append("\nL").append(i + 1)
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
                            Locale.US, "%.0f", line.angle()))
                    .append(" Len:")
                    .append(Math.round(line.length()))
                    .append(" N:")
                    .append(line.votes)
                    .append(" E:")
                    .append(String.format(
                            Locale.US, "%.1f", line.error));
        }

        report.append("\nScreen: ")
                .append(screenWidth)
                .append("x")
                .append(screenHeight);

        return report.toString();
    }

    private void sendClear(int width, int height) {

        Intent update = new Intent(ACTION_LINE);
        update.setPackage(getPackageName());
        update.putExtra("width", width);
        update.putExtra("height", height);
        sendBroadcast(update);
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Pool live diagnostic",
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager nm =
                    getSystemService(NotificationManager.class);

            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {

        if (Build.VERSION.SDK_INT >= 26) {

            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Saqib Pool Pro - Diagnostic V2")
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle()
                            .bigText(message))
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle("Saqib Pool Pro - Diagnostic V2")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(message))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void updateNotification(String message) {

        NotificationManager nm =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

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
