package com.saqib.poolpro;

public final class TableGeometry {

    private TableGeometry() {}

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

    // Dono lines ke alag origins aur directions.
    public static float[] extendTwoLinesFromOrigins(
            float cueX, float cueY,
            float cueDx, float cueDy,
            float objectX, float objectY,
            float objectDx, float objectDy,
            float left, float top,
            float right, float bottom) {

        float[] cueLine = extendRay(
                cueX, cueY, cueDx, cueDy,
                left, top, right, bottom);

        float[] objectLine = extendRay(
                objectX, objectY, objectDx, objectDy,
                left, top, right, bottom);

        // Ek bhi line valid na ho to dono draw nahi hongi.
        if (cueLine == null || objectLine == null) {
            return null;
        }

        return new float[] {
                cueLine[0], cueLine[1],
                cueLine[2], cueLine[3],

                objectLine[0], objectLine[1],
                objectLine[2], objectLine[3]
        };
    }
}
