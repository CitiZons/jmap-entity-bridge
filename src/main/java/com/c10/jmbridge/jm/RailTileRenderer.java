package com.c10.jmbridge.jm;

import org.mtr.core.data.Rail;
import org.mtr.core.tool.Vector;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Samples MTR's exact curve in double precision; no block-coordinate rounding. */
final class RailTileRenderer {
    static final int TILE_SIZE = 128;
    static final float LINE_WIDTH = 0.5F; // World blocks, one quarter of the previous filled strip.
    private static final double MAX_ERROR = 0.05;
    private static final double MAX_STEP = 8;

    record Point(double x, double y, double z) {
        static Point from(Vector vector) { return new Point(vector.x, vector.y, vector.z); }
    }
    record Segment(Point start, Point end) {
        double height() { return (start.y + end.y) / 2; }
    }
    record Tile(int x, int z) {
        int minX() { return x * TILE_SIZE; }
        int minZ() { return z * TILE_SIZE; }
    }

    static List<Segment> sample(Rail rail) {
        List<Segment> result = new ArrayList<>();
        if (rail.isValid()) {
            double length = rail.railMath.getLength();
            subdivide(rail, 0, Point.from(rail.railMath.getPosition(0, false)), length,
                    Point.from(rail.railMath.getPosition(length, false)), result);
        }
        return result;
    }

    private static void subdivide(Rail rail, double d1, Point start, double d2, Point end,
                                  List<Segment> result) {
        double middleDistance = (d1 + d2) / 2;
        Point middle = Point.from(rail.railMath.getPosition(middleDistance, false));
        double error = Math.hypot(middle.x - (start.x + end.x) / 2,
                middle.z - (start.z + end.z) / 2);
        if (d2 - d1 > MAX_STEP || error > MAX_ERROR || Math.abs(end.y - start.y) > 1) {
            subdivide(rail, d1, start, middleDistance, middle, result);
            subdivide(rail, middleDistance, middle, d2, end, result);
        } else {
            result.add(new Segment(start, end));
        }
    }

    static Map<Tile, List<Segment>> tiles(List<Segment> segments) {
        Map<Tile, List<Segment>> result = new HashMap<>();
        // Include stroke/AA on both sides of tile borders. The raster origin stays exact.
        double padding = LINE_WIDTH / 2 + 1;
        for (Segment segment : segments) {
            int minX = tileIndex(Math.min(segment.start.x, segment.end.x) - padding);
            int maxX = tileIndex(Math.max(segment.start.x, segment.end.x) + padding);
            int minZ = tileIndex(Math.min(segment.start.z, segment.end.z) - padding);
            int maxZ = tileIndex(Math.max(segment.start.z, segment.end.z) + padding);
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    result.computeIfAbsent(new Tile(x, z), ignored -> new ArrayList<>()).add(segment);
                }
            }
        }
        return result;
    }

    private static int tileIndex(double coordinate) {
        return (int) Math.floor(coordinate / TILE_SIZE);
    }

    static BufferedImage render(Tile tile, List<Segment> segments, int scale, int minY, int maxY) {
        BufferedImage image = new BufferedImage(TILE_SIZE * scale, TILE_SIZE * scale, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.scale(scale, scale);
            graphics.translate(-tile.minX(), -tile.minZ());
            graphics.setStroke(new BasicStroke(LINE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            List<Segment> ordered = new ArrayList<>(segments);
            ordered.sort(Comparator.comparingDouble(Segment::height)); // Higher/lighter rails paint last.
            Path2D.Double path = new Path2D.Double();
            Point previous = null;
            int previousColor = -1;
            for (Segment segment : ordered) {
                int color = RailOverlayTracker.colorForHeight(segment.height(), minY, maxY);
                if (color != previousColor || !segment.start.equals(previous)) {
                    if (previous != null) {
                        graphics.draw(path);
                    }
                    graphics.setColor(new Color(color));
                    path.reset();
                    path.moveTo(segment.start.x, segment.start.z);
                }
                path.lineTo(segment.end.x, segment.end.z);
                previous = segment.end;
                previousColor = color;
            }
            if (previous != null) {
                graphics.draw(path);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
