
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

    private static final String TAG = "PoolProDiagnostic";
    private static final String CHANNEL_ID = "pool_capture";
    private static final int NOTIFICATION_ID = 7;
    private static final int SAMPLE_STEP = 2;

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
                buildNotification("White-line detector starting"));
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

            workerThread = new HandlerThread("PoolDiagnosticWorker");
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

                    // Diagnostic mode: overlay clear rahega.
                    sendClear(width, height);

                    if (now - lastNotificationTime >= 1500) {
                        lastNotificationTime = now;
                        updateNotification(makeReport(
                                lines, points.size(), width, height));
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Diagnostic error", e);
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
                    "SaqibPoolDiagnostic",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    worker
            );

            updateNotification("White-pixel diagnostic running");

        } catch (Exception e) {
            Log.e(TAG, "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    /*
     * White pixels RAM mein read hote hain.
     * Screenshot ya image file save nahi hoti.
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

                if (min >= 205 && max - min <= 35) {
                    points.add(new Point(x, y));
                }
            }
        }

        return points;
    }

    /*
     * 8-neighbour connected components.
     * Har connected white group ko alag rakhta hai.
     */
    private List<Line> findConnectedLines(
            List<Point> points, int width, int height) {

        List<Line> lines = new ArrayList<>();

        int gridW = width / SAMPLE_STEP + 2;
        int gridH = height / SAMPLE_STEP + 2;

        boolean[][] occupied = new boolean[gridH][gridW];
        boolean[][] visited = new boolean[gridH][gridW];

        for (Point p : points) {
            int gx = p.x / SAMPLE_STEP;
            int gy = p.y / SAMPLE_STEP;

            if (gx >= 0 && gx < gridW &&
                    gy >= 0 && gy < gridH) {
                occupied[gy][gx] = true;
            }
        }

        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};

        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {

                if (!occupied[gy][gx] || visited[gy][gx]) {
                    continue;
                }

                List<Point> component = new ArrayList<>();
                ArrayDeque<int[]> queue = new ArrayDeque<>();

                queue.add(new int[]{gx, gy});
                visited[gy][gx] = true;

                while (!queue.isEmpty()) {

                    int[] current = queue.removeFirst();
                    int cx = current[0];
                    int cy = current[1];

                    component.add(new Point(
                            cx * SAMPLE_STEP,
                            cy * SAMPLE_STEP));

                    for (int k = 0; k < 8; k++) {

                        int nx = cx + dx[k];
                        int ny = cy + dy[k];

                        if (nx < 0 || ny < 0 ||
                                nx >= gridW || ny >= gridH) {
                            continue;
                        }

                        if (occupied[ny][nx] &&
                                !visited[ny][nx]) {

                            visited[ny][nx] = true;
                            queue.addLast(new int[]{nx, ny});
                        }
                    }
                }

                if (component.size() < 4) continue;

                Line line = fitLine(component);

                if (line != null &&
                        line.length() >= 10 &&
                        line.error <= 4.0f) {
                    lines.add(line);
                }
            }
        }

        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Float.compare(b.length(), a.length());
            }
        });

        if (lines.size() > 12) {
            return new ArrayList<>(lines.subList(0, 12));
        }

        return lines;
    }

    /*
     * PCA se component ki main direction aur endpoints.
     */
    private Line fitLine(List<Point> points) {

        if (points.size() < 4) return null;

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

            minProjection = Math.min(
                    minProjection, projection);
            maxProjection = Math.max(
                    maxProjection, projection);

            double perpendicular =
                    -dx * uy + dy * ux;

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

    private String makeReport(
            List<Line> lines,
            int whiteCount,
            int screenWidth,
            int screenHeight) {

        StringBuilder report = new StringBuilder();

        report.append("Frames: ").append(frameCount)
                .append(" | White: ").append(whiteCount)
                .append(" | Components: ").append(lines.size());

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
                    .setContentTitle("Saqib Pool Pro - Diagnostic")
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle()
                            .bigText(message))
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
        }

        return new Notification.Builder(this)
                .setContentTitle("Saqib Pool Pro - Diagnostic")
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
    }
