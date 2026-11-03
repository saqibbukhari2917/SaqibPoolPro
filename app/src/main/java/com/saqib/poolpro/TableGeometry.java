package com.saqib.poolpro;

public final class TableGeometry {

    private TableGeometry() {}

    // Ray ko nearest cushion tak extend karta hai.
    public static float[] extendRay(
            float x, float y,
            float dx, float dy,
            float left, float top,
            float right, float bottom) {

        if (right <= left || bottom <= top) return null;
        if (x < left || x > right || y < top || y > bottom) return null;
        if (Math.abs(dx) < 0.000001f &&
            Math.abs(dy) < 0.000001f) return null;

        float tMin = Float.POSITIVE_INFINITY;

        if (dx > 0) {
            float t = (right - x) / dx;
            float hitY = y + t * dy;
            if (t > 0 && hitY >= top && hitY <= bottom)
                tMin = Math.min(tMin, t);
        } else if (dx < 0) {
            float t = (left - x) / dx;
            float hitY = y + t * dy;
            if (t > 0 && hitY >= top && hitY <= bottom)
                tMin = Math.min(tMin, t);
        }

        if (dy > 0) {
            float t = (bottom - y) / dy;
            float hitX = x + t * dx;
            if (t > 0 && hitX >= left && hitX <= right)
                tMin = Math.min(tMin, t);
        } else if (dy < 0) {
            float t = (top - y) / dy;
            float hitX = x + t * dx;
            if (t > 0 && hitX >= left && hitX <= right)
                tMin = Math.min(tMin, t);
        }

        if (!Float.isFinite(tMin)) return null;

        return new float[] {
            x, y,
            x + tMin * dx,
            y + tMin * dy
        };
    }

    // Contact point se dono guide lines cushion tak.
    public static float[] extendTwoLines(
            float x, float y,
            float dx1, float dy1,
            float dx2, float dy2,
            float left, float top,
            float right, float bottom) {

        float[] a = extendRay(
            x, y, dx1, dy1, left, top, right, bottom);

        float[] b = extendRay(
            x, y, dx2, dy2, left, top, right, bottom);

        if (a == null || b == null) return null;

        return new float[] {
            a[0], a[1], a[2], a[3],
            b[0], b[1], b[2], b[3]
        };
    }
}
