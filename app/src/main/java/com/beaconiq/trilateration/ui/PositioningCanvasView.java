package com.beaconiq.trilateration.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.beaconiq.trilateration.positioning.phase1.BeaconSample;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PositioningCanvasView extends View {

    private static final int MAX_TRAIL_SIZE = 10;
    private static final float BEACON_RADIUS = 18f;
    private static final float CLOSEST_BEACON_RADIUS = 24f;
    private static final float GLOW_RADIUS = 36f;
    private static final float POSITION_RADIUS = 12f;
    private static final float RING_RADIUS = 28f;
    private static final float PAD = 48f;

    private Map<String, BeaconPos> beaconMap = new HashMap<>();
    private double[] estimatedPosition;
    private String closestBeaconKey;
    private final Deque<double[]> positionTrail = new ArrayDeque<>();

    private float compassAzimuth = 0f;
    private float tiltPitch = 0f;
    private float tiltRoll = 0f;

    private Set<String> cachedBeaconKeys;
    private double cachedMinX, cachedMaxX, cachedMinY, cachedMaxY;

    private final Paint beaconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closestBeaconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint positionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint messagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dottedLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint compassBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint compassNeedlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint compassLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tiltBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tiltDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PositioningCanvasView(Context context) {
        super(context);
        init();
    }

    public PositioningCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PositioningCanvasView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        beaconPaint.setColor(0xFF00BCD4);
        beaconPaint.setStyle(Paint.Style.FILL);

        closestBeaconPaint.setColor(0xFFFFC107);
        closestBeaconPaint.setStyle(Paint.Style.FILL);

        glowPaint.setColor(0x4DFFC107);
        glowPaint.setStyle(Paint.Style.FILL);

        positionPaint.setColor(0xFFB61F23);
        positionPaint.setStyle(Paint.Style.FILL);

        ringPaint.setColor(0x44B61F23);
        ringPaint.setStyle(Paint.Style.FILL);

        labelPaint.setColor(0xFF000000);
        labelPaint.setTextSize(24f);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        messagePaint.setColor(0xFF9E9E9E);
        messagePaint.setTextSize(32f);
        messagePaint.setTextAlign(Paint.Align.CENTER);

        borderPaint.setColor(0xFFBDBDBD);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);

        dottedLinePaint.setColor(0xFF9E9E9E);
        dottedLinePaint.setStyle(Paint.Style.STROKE);
        dottedLinePaint.setStrokeWidth(2f);
        dottedLinePaint.setPathEffect(new DashPathEffect(new float[]{10f, 10f}, 0f));

        trailPaint.setColor(0xFFB61F23);
        trailPaint.setStyle(Paint.Style.FILL);

        badgeBgPaint.setColor(0xE6FFFFFF);
        badgeBgPaint.setStyle(Paint.Style.FILL);

        badgeTextPaint.setColor(0xFF424242);
        badgeTextPaint.setTextSize(20f);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);

        compassBgPaint.setColor(0x33BDBDBD);
        compassBgPaint.setStyle(Paint.Style.FILL);

        compassNeedlePaint.setColor(0xFFD32F2F);
        compassNeedlePaint.setStyle(Paint.Style.FILL);

        compassLabelPaint.setColor(0xFF424242);
        compassLabelPaint.setTextSize(18f);
        compassLabelPaint.setTextAlign(Paint.Align.CENTER);

        tiltBgPaint.setColor(0x33BDBDBD);
        tiltBgPaint.setStyle(Paint.Style.STROKE);
        tiltBgPaint.setStrokeWidth(2f);

        tiltDotPaint.setColor(0xFF00BCD4);
        tiltDotPaint.setStyle(Paint.Style.FILL);
    }

    // Phase I entry point
    public void update(Map<String, BeaconSample> beacons, double[] position) {
        update(beacons, position, null);
    }

    // Phase I entry point with closest beacon
    public void update(Map<String, BeaconSample> beacons, double[] position, String closestKey) {
        Map<String, BeaconPos> converted = new HashMap<>();
        for (Map.Entry<String, BeaconSample> e : beacons.entrySet()) {
            BeaconSample b = e.getValue();
            converted.put(e.getKey(), new BeaconPos(b.getX(), b.getY(), b.getFilteredDistance()));
        }
        updateInternal(converted, position, closestKey);
    }

    // Phase II entry point
    public void updateP2(Map<String, com.beaconiq.trilateration.positioning.phase2.BeaconSample> beacons,
                         double[] position) {
        updateP2(beacons, position, null);
    }

    // Phase II entry point with closest beacon
    public void updateP2(Map<String, com.beaconiq.trilateration.positioning.phase2.BeaconSample> beacons,
                         double[] position, String closestKey) {
        Map<String, BeaconPos> converted = new HashMap<>();
        for (Map.Entry<String, com.beaconiq.trilateration.positioning.phase2.BeaconSample> e : beacons.entrySet()) {
            com.beaconiq.trilateration.positioning.phase2.BeaconSample b = e.getValue();
            converted.put(e.getKey(), new BeaconPos(b.getX(), b.getY(), b.getFilteredDistance()));
        }
        updateInternal(converted, position, closestKey);
    }

    private void updateInternal(Map<String, BeaconPos> beacons, double[] position, String closestKey) {
        boolean beaconSetChanged = !beacons.keySet().equals(
                this.beaconMap != null ? this.beaconMap.keySet() : Collections.emptySet());
        this.beaconMap = beacons;
        this.closestBeaconKey = closestKey;

        if (position != null) {
            positionTrail.addLast(new double[]{position[0], position[1]});
            while (positionTrail.size() > MAX_TRAIL_SIZE) positionTrail.removeFirst();
        }
        this.estimatedPosition = position;

        if (beaconSetChanged) {
            recalculateBounds();
        }
        invalidate();
    }

    public void updateOrientation(float azimuth, float pitch, float roll) {
        this.compassAzimuth = azimuth;
        this.tiltPitch = pitch;
        this.tiltRoll = roll;
        invalidate();
    }

    public void clear() {
        beaconMap.clear();
        estimatedPosition = null;
        closestBeaconKey = null;
        positionTrail.clear();
        cachedBeaconKeys = null;
        invalidate();
    }

    private void recalculateBounds() {
        if (beaconMap.isEmpty()) return;
        cachedBeaconKeys = new HashSet<>(beaconMap.keySet());
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (BeaconPos b : beaconMap.values()) {
            minX = Math.min(minX, b.x);
            maxX = Math.max(maxX, b.x);
            minY = Math.min(minY, b.y);
            maxY = Math.max(maxY, b.y);
        }
        double rangeX = maxX - minX;
        double rangeY = maxY - minY;
        if (rangeX < 1) { minX -= 5; maxX += 5; rangeX = 10; }
        if (rangeY < 1) { minY -= 5; maxY += 5; rangeY = 10; }
        minX -= rangeX * 0.25;
        maxX += rangeX * 0.25;
        minY -= rangeY * 0.25;
        maxY += rangeY * 0.25;
        cachedMinX = minX;
        cachedMaxX = maxX;
        cachedMinY = minY;
        cachedMaxY = maxY;
    }

    private int countActive() {
        int count = 0;
        for (BeaconPos b : beaconMap.values()) {
            if (b.filteredDistance != null) count++;
        }
        return count;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFFF4F4F4);

        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, borderPaint);

        if (beaconMap.isEmpty()) {
            canvas.drawText("Waiting for beacons...",
                    w / 2f, h / 2f, messagePaint);
            drawCompass(canvas, w);
            drawTiltIndicator(canvas);
            return;
        }

        int active = countActive();
        if (active < 3) {
            canvas.drawText("Need 3+ beacons (" + active + " active)",
                    w / 2f, h / 2f, messagePaint);
        }

        if (cachedBeaconKeys == null) {
            recalculateBounds();
        }

        double drawW = w - 2 * PAD;
        double drawH = h - 2 * PAD;
        double scaleX = drawW / (cachedMaxX - cachedMinX);
        double scaleY = drawH / (cachedMaxY - cachedMinY);
        double scale = Math.min(scaleX, scaleY);
        double offX = PAD + (drawW - (cachedMaxX - cachedMinX) * scale) / 2;
        double offY = PAD + (drawH - (cachedMaxY - cachedMinY) * scale) / 2;

        drawBeacons(canvas, scale, offX, offY);
        drawTrail(canvas, scale, offX, offY);
        drawDottedLineToClosest(canvas, scale, offX, offY, active);
        drawPosition(canvas, scale, offX, offY, active);
        drawDistanceBadge(canvas, scale, offX, offY, active);
        drawCompass(canvas, w);
        drawTiltIndicator(canvas);
    }

    private float toScreenX(double worldX, double scale, double offX) {
        return (float) (offX + (worldX - cachedMinX) * scale);
    }

    private float toScreenY(double worldY, double scale, double offY) {
        return (float) (offY + (cachedMaxY - worldY) * scale);
    }

    private void drawBeacons(Canvas canvas, double scale, double offX, double offY) {
        for (Map.Entry<String, BeaconPos> entry : beaconMap.entrySet()) {
            BeaconPos b = entry.getValue();
            float cx = toScreenX(b.x, scale, offX);
            float cy = toScreenY(b.y, scale, offY);

            boolean isClosest = entry.getKey().equals(closestBeaconKey);

            if (isClosest) {
                canvas.drawCircle(cx, cy, GLOW_RADIUS, glowPaint);
                canvas.drawCircle(cx, cy, CLOSEST_BEACON_RADIUS, closestBeaconPaint);
            } else {
                canvas.drawCircle(cx, cy, BEACON_RADIUS, beaconPaint);
            }

            String label = entry.getKey();
            String[] parts = label.split(":");
            if (parts.length >= 3) label = parts[1] + "," + parts[2];
            canvas.drawText(label, cx, cy - (isClosest ? 30f : 24f), labelPaint);
        }
    }

    private void drawTrail(Canvas canvas, double scale, double offX, double offY) {
        if (positionTrail.isEmpty()) return;
        int i = 0;
        int size = positionTrail.size();
        for (double[] p : positionTrail) {
            float px = toScreenX(p[0], scale, offX);
            float py = toScreenY(p[1], scale, offY);
            int alpha = 30 + (150 * i / Math.max(size - 1, 1));
            trailPaint.setAlpha(alpha);
            canvas.drawCircle(px, py, 6f, trailPaint);
            i++;
        }
    }

    private void drawDottedLineToClosest(Canvas canvas, double scale, double offX, double offY,
                                          int active) {
        if (estimatedPosition == null || active < 3 || closestBeaconKey == null) return;
        BeaconPos closest = beaconMap.get(closestBeaconKey);
        if (closest == null) return;

        float px = toScreenX(estimatedPosition[0], scale, offX);
        float py = toScreenY(estimatedPosition[1], scale, offY);
        float bx = toScreenX(closest.x, scale, offX);
        float by = toScreenY(closest.y, scale, offY);

        canvas.drawLine(px, py, bx, by, dottedLinePaint);
    }

    private void drawPosition(Canvas canvas, double scale, double offX, double offY, int active) {
        if (estimatedPosition == null || active < 3) return;
        float px = toScreenX(estimatedPosition[0], scale, offX);
        float py = toScreenY(estimatedPosition[1], scale, offY);
        canvas.drawCircle(px, py, RING_RADIUS, ringPaint);
        canvas.drawCircle(px, py, POSITION_RADIUS, positionPaint);
    }

    private void drawDistanceBadge(Canvas canvas, double scale, double offX, double offY,
                                    int active) {
        if (estimatedPosition == null || active < 3 || closestBeaconKey == null) return;
        BeaconPos closest = beaconMap.get(closestBeaconKey);
        if (closest == null) return;

        double dx = estimatedPosition[0] - closest.x;
        double dy = estimatedPosition[1] - closest.y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        String text = String.format(Locale.US, "%.1fm", dist);

        float px = toScreenX(estimatedPosition[0], scale, offX);
        float py = toScreenY(estimatedPosition[1], scale, offY);
        float bx = toScreenX(closest.x, scale, offX);
        float by = toScreenY(closest.y, scale, offY);
        float midX = (px + bx) / 2f;
        float midY = (py + by) / 2f;

        float textWidth = badgeTextPaint.measureText(text);
        float padH = 8f, padV = 4f;
        RectF rect = new RectF(
                midX - textWidth / 2f - padH,
                midY - 12f - padV,
                midX + textWidth / 2f + padH,
                midY + 4f + padV);
        canvas.drawRoundRect(rect, 6f, 6f, badgeBgPaint);
        canvas.drawText(text, midX, midY, badgeTextPaint);
    }

    private void drawCompass(Canvas canvas, int viewWidth) {
        float cx = viewWidth - 44f;
        float cy = 44f;
        float radius = 22f;

        canvas.drawCircle(cx, cy, radius, compassBgPaint);

        canvas.save();
        canvas.rotate(-compassAzimuth, cx, cy);

        Path needle = new Path();
        needle.moveTo(cx, cy - radius + 4f);
        needle.lineTo(cx - 5f, cy);
        needle.lineTo(cx + 5f, cy);
        needle.close();
        canvas.drawPath(needle, compassNeedlePaint);

        canvas.drawText("N", cx, cy - radius + 16f, compassLabelPaint);
        canvas.restore();
    }

    private void drawTiltIndicator(Canvas canvas) {
        float cx = 44f;
        float cy = 44f;
        float radius = 18f;

        canvas.drawCircle(cx, cy, radius, tiltBgPaint);

        float dotOffX = Math.max(-radius + 4, Math.min(radius - 4, tiltRoll / 90f * radius));
        float dotOffY = Math.max(-radius + 4, Math.min(radius - 4, tiltPitch / 90f * radius));

        canvas.drawCircle(cx + dotOffX, cy - dotOffY, 5f, tiltDotPaint);
    }

    private static class BeaconPos {
        final double x, y;
        final Double filteredDistance;
        BeaconPos(double x, double y, Double d) { this.x = x; this.y = y; this.filteredDistance = d; }
    }
}
