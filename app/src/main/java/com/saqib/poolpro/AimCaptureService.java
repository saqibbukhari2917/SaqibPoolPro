
private void detectBall(Image image) {

    Image.Plane[] planes = image.getPlanes();
    if (planes.length == 0) return;

    ByteBuffer buffer = planes[0].getBuffer();

    int rowStride = planes[0].getRowStride();
    int pixelStride = planes[0].getPixelStride();

    int width = image.getWidth();
    int height = image.getHeight();

    // Pool table search area
    int minX = (int)(width * 0.17f);
    int maxX = (int)(width * 0.84f);
    int minY = (int)(height * 0.17f);
    int maxY = (int)(height * 0.97f);

    int radius = Math.max(8, (int)(width * 0.009f));

    int bestX = -1;
    int bestY = -1;
    int bestScore = -1;

    // Search for the small red center marker
    for (int y = minY + radius; y < maxY - radius; y += 2) {

        for (int x = minX + radius; x < maxX - radius; x += 2) {

            if (!isRedMarker(buffer, x, y,
                    rowStride, pixelStride)) {
                continue;
            }

            int whiteSamples = 0;
            int totalSamples = 0;

            // Check the surrounding area of the red dot
            for (int angle = 0; angle < 360; angle += 30) {

                double rad = Math.toRadians(angle);

                int sx = x + (int)(Math.cos(rad) * radius);
                int sy = y + (int)(Math.sin(rad) * radius);

                totalSamples++;

                if (isBallWhite(buffer, sx, sy,
                        rowStride, pixelStride)) {
                    whiteSamples++;
                }
            }

            // Most surrounding samples should be white
            if (whiteSamples >= 8) {

                int score = whiteSamples;

                if (score > bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestY = y;
                }
            }
        }
    }

    if (bestX >= 0 && bestY >= 0) {

        Intent update = new Intent(ACTION_BALL);
        update.setPackage(getPackageName());

        update.putExtra("x", (float)bestX);
        update.putExtra("y", (float)bestY);
        update.putExtra("width", width);
        update.putExtra("height", height);

        sendBroadcast(update);
    }
}

private boolean isRedMarker(
        ByteBuffer buffer,
        int x,
        int y,
        int rowStride,
        int pixelStride) {

    int pos = y * rowStride + x * pixelStride;

    if (pos < 0 || pos + 2 >= buffer.limit()) {
        return false;
    }

    int r = buffer.get(pos) & 255;
    int g = buffer.get(pos + 1) & 255;
    int b = buffer.get(pos + 2) & 255;

    return r > 150 &&
            r > g * 1.4 &&
            r > b * 1.3;
}

private boolean isBallWhite(
        ByteBuffer buffer,
        int x,
        int y,
        int rowStride,
        int pixelStride) {

    int pos = y * rowStride + x * pixelStride;

    if (pos < 0 || pos + 2 >= buffer.limit()) {
        return false;
    }

    int r = buffer.get(pos) & 255;
    int g = buffer.get(pos + 1) & 255;
    int b = buffer.get(pos + 2) & 255;

    int max = Math.max(r, Math.max(g, b));
    int min = Math.min(r, Math.min(g, b));

    return r > 155 &&
            g > 145 &&
            b > 130 &&
            max - min < 75;
}
