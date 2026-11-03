
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

import java.nio.ByteBuffer;

public class AimCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "projectionData";
    public static final String ACTION_AIM =
            "com.saqib.poolpro.AIM_DIRECTION";

    private static final String CHANNEL_ID = "pool_capture";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;

    private long lastFrame;
    private long lastBroadcast;

    private int screenW;
    private int screenH;

    private int tableLeft;
    private int tableTop;
    private int tableRight;
    private int tableBottom;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Pool capture",
                    NotificationManager.IMPORTANCE_LOW
            );

            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);

            startForeground(7, new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Original aim guide detection")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .build());
        } else {
            startForeground(7, new Notification.Builder(this)
                    .setContentTitle("Saqib Pool Pro")
                    .setContentText("Original aim guide detection")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            stopSelf();
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

            screenW = metrics.widthPixels;
            screenH = metrics.heightPixels;

            thread = new HandlerThread("PoolAimDetector");
            thread.start();
            handler = new Handler(thread.getLooper());

            imageReader = ImageReader.newInstance(
                    screenW,
                    screenH,
                    PixelFormat.RGBA_8888,
                    2
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;

                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;

                    long now = System.currentTimeMillis();
                    if (now - lastFrame < 150) return;
                    lastFrame = now;

                    detectGuide(image);

                } catch (Exception e) {
                    android.util.Log.e("PoolPro", "Detection error", e);
                } finally {
                    if (image != null) image.close();
                }
            }, handler);

            projection.registerCallback(
                    new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            stopSelf();
                        }
                    },
                    handler
            );

            virtualDisplay = projection.createVirtualDisplay(
                    "PoolAimCapture",
                    screenW,
                    screenH,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler
            );

        } catch (Exception e) {
            android.util.Log.e("PoolPro", "Capture start failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private int pixel(
            ByteBuffer b,
            int row,
            int stride,
            int x,
            int y,
            int w,
            int h) {

        if (x < 0 || y < 0 || x >= w || y >= h) return -1;

        int p = y * row + x * stride;
        if (p < 0 || p + 2 >= b.limit()) return -1;

        int r = b.get(p) & 255;
        int g = b.get(p + 1) & 255;
        int bl = b.get(p + 2) & 255;

        return (r << 16) | (g << 8) | bl;
    }

    private boolean white(
            ByteBuffer b, int row, int stride,
            int x, int y, int w, int h) {

        int c = pixel(b, row, stride, x, y, w, h);
        if (c < 0) return false;

        int r = (c >> 16) & 255;
        int g = (c >> 8) & 255;
        int bl = c & 255;

        return r > 185 && g > 185 && bl > 175
                && Math.max(r, Math.max(g, bl))
                - Math.min(r, Math.min(g, bl)) < 65;
    }

    private boolean dark(
            ByteBuffer b, int row, int stride,
            int x, int y, int w, int h) {

        int c = pixel(b, row, stride, x, y, w, h);
        if (c < 0) return false;

        int r = (c >> 16) & 255;
        int g = (c >> 8) & 255;
        int bl = c & 255;

        return r < 100 && g < 120 && bl < 145;
    }

    private void findTableBounds(
            ByteBuffer b, int row, int stride, int w, int h) {

        int left = w;
        int right = 0;
        int top = h;
        int bottom = 0;

        int xStart = (int) (w * 0.08f);
        int xEnd = (int) (w * 0.92f);
        int yStart = (int) (h * 0.08f);
        int yEnd = (int) (h * 0.92f);

        for (int y = yStart; y < yEnd; y += 5) {
            for (int x = xStart; x < xEnd; x += 5) {

                int c = pixel(b, row, stride, x, y, w, h);
                if (c < 0) continue;

                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int bl = c & 255;

                // Blue/turquoise table felt.
                if (r < 115 && g > 105 && bl > 120
                        && g > r * 1.25f) {

                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }

        if (right - left > w * 0.35f &&
                bottom - top > h * 0.25f) {

            tableLeft = left;
            tableRight = right;
            tableTop = top;
            tableBottom = bottom;
        }
    }

    private double lineScore(
            ByteBuffer b,
            int row,
            int stride,
            int w,
            int h,
            int cx,
            int cy,
            double angle) {

        double dx = Math.cos(angle);
        double dy = Math.sin(angle);

        int hits = 0;
        int pairs = 0;
        int run = 0;
        int longest = 0;

        // The game guide has a dark center and bright edges.
        for (int d = 24; d < 650; d += 4) {

            int x = (int) Math.round(cx + dx * d);
            int y = (int) Math.round(cy + dy * d);

            if (x <= tableLeft || x >= tableRight ||
                    y <= tableTop || y >= tableBottom) {
                break;
            }

            int ox = (int) Math.round(-dy * 3);
            int oy = (int) Math.round(dx * 3);

            boolean centerDark = dark(
                    b, row, stride, x, y, w, h
            );

            boolean edge1 = white(
                    b, row, stride, x + ox, y + oy, w, h
            );

            boolean edge2 = white(
                    b, row, stride, x - ox, y - oy, w, h
            );

            if (centerDark && (edge1 || edge2)) {
                hits++;
                run++;
                pairs++;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }

        return hits + longest * 1.5 + pairs * 0.5;
    }

    private void detectGuide(Image image) {

        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return;

        ByteBuffer b = planes[0].getBuffer().duplicate();

        int w = image.getWidth();
        int h = image.getHeight();

        int row = planes[0].getRowStride();
        int stride = planes[0].getPixelStride();

        findTableBounds(b, row, stride, w, h);

        if (tableRight <= tableLeft || tableBottom <= tableTop) {
            return;
        }

        int step = 3;
        int bestX = -1;
        int bestY = -1;
        double bestAngle = 0;
        double bestScore = 0;

        int radius = Math.max(9, Math.round(Math.min(w, h) * 0.022f));

        // Search for a bright cue-ball-like circle only on the table.
        for (int cy = tableTop + radius;
             cy < tableBottom - radius;
             cy += 5) {

            for (int cx = tableLeft + radius;
                 cx < tableRight - radius;
                 cx += 5) {

                int whiteCount = 0;
                int count = 0;

                for (int yy = -radius; yy <= radius; yy += step) {
                    for (int xx = -radius; xx <= radius; xx += step) {

                        if (xx * xx + yy * yy > radius * radius) {
                            continue;
                        }

                        count++;

                        if (white(b, row, stride,
                                cx + xx, cy + yy, w, h)) {
                            whiteCount++;
                        }
                    }
                }

                if (count == 0) continue;

                float ratio = whiteCount / (float) count;

                if (ratio < 0.52f) continue;

                // Test each possible direction from the ball.
                for (int deg = 0; deg < 360; deg += 3) {

                    double angle = Math.toRadians(deg);

                    double score = lineScore(
                            b, row, stride, w, h,
                            cx, cy, angle
                    );

                    if (score > bestScore) {
                        bestScore = score;
                        bestX = cx;
                        bestY = cy;
                        bestAngle = angle;
                    }
                }
            }
        }

        // Reject weak matches instead of drawing a random line.
        if (bestX < 0 || bestScore < 22) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBroadcast < 100) return;
        lastBroadcast = now;

        Intent update = new Intent(ACTION_AIM);
        update.setPackage(getPackageName());

        update.putExtra("x", (float) bestX);
        update.putExtra("y", (float) bestY);
        update.putExtra("angle", (float) bestAngle);
        update.putExtra("width", w);
        update.putExtra("height", h);
        update.putExtra("left", tableLeft);
        update.putExtra("top", tableTop);
        update.putExtra("right", tableRight);
        update.putExtra("bottom", tableBottom);
        update.putExtra("score", (float) bestScore);

        sendBroadcast(update);
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

        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
            }
