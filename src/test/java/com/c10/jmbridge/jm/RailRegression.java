package com.c10.jmbridge.jm;

import com.c10.jmbridge.JMEntityBridgeMod;
import com.c10.jmbridge.mixin.MtrRailDeletionMixin;
import com.c10.jmbridge.mixin.JourneyMapRailNodeMixin;
import org.mtr.mod.block.BlockNode;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Runs against the actual MTR 4.0.3 geometry/serializer, without launching a game. */
public final class RailRegression {
    public static void main(String[] args) throws Exception {
        Rail straight = rail(133, 64, 64, 0, Angle.W);
        var segments = RailTileRenderer.sample(straight);
        var start = straight.railMath.getPosition(0, false);
        var end = straight.railMath.getPosition(straight.railMath.getLength(), false);
        require(segments.get(0).start().equals(RailTileRenderer.Point.from(start))
                        && segments.get(segments.size() - 1).end().equals(RailTileRenderer.Point.from(end)),
                "Sampling must preserve exact fractional endpoints at negative coordinates");
        require(segments.stream().allMatch(s -> s.height() == 64), "Horizontal track height must be retained");
        for (int i = 1; i < segments.size(); i++) {
            require(segments.get(i - 1).end().equals(segments.get(i).start()), "Curve must remain continuous");
        }

        var slope = RailTileRenderer.sample(rail(128, 0, 96, 0, Angle.W));
        require(slope.size() > 1, "Slope must use more than one height/color layer");
        require(slope.get(0).height() < slope.get(slope.size() - 1).height(), "Slope height order reversed");
        Rail curvedRail = rail(64, 20, 20, 64, Angle.N);
        var curve = RailTileRenderer.sample(curvedRail);
        require(curve.stream().anyMatch(s -> Math.abs(s.start().x() - Math.rint(s.start().x())) > 0.01),
                "Curve precision must not be rounded to whole blocks");
        // Dense oracle: verify against MTR's exact curve, not the sampler's own midpoint rule.
        for (double d = 0; d < curvedRail.railMath.getLength(); d += 0.25) {
            var point = curvedRail.railMath.getPosition(d, false);
            double nearest = curve.stream().mapToDouble(s -> java.awt.geom.Line2D.ptSegDist(
                    s.start().x(), s.start().z(), s.end().x(), s.end().z(), point.x, point.z)).min().orElseThrow();
            require(nearest <= 0.025, "Reconstructed curve deviates from MTR geometry");
        }

        var low = new RailTileRenderer.Segment(new RailTileRenderer.Point(16, 0, 64.125),
                new RailTileRenderer.Point(112, 0, 64.125));
        var high = new RailTileRenderer.Segment(new RailTileRenderer.Point(64.125, 96, 16),
                new RailTileRenderer.Point(64.125, 96, 112));
        var tile = new RailTileRenderer.Tile(0, 0);
        BufferedImage crossing = RailTileRenderer.render(tile, List.of(high, low), 4, -64, 320);
        int crossingPixel = crossing.getRGB(256, 256);
        require((crossingPixel & 0xFFFFFF) == RailOverlayTracker.colorForHeight(96, -64, 320),
                "Higher/lighter rail must cover lower rail regardless of input order");
        int coverage = 0;
        boolean antialiased = false;
        for (int row = 0; row < crossing.getHeight(); row++) {
            int alpha = crossing.getRGB(128, row) >>> 24;
            if (alpha > 0) { coverage++; }
            if (alpha > 0 && alpha < 255) { antialiased = true; }
        }
        require(coverage <= 4, "Stroke must remain thin at four pixels per block");
        require(antialiased, "Fractional curve edges must have antialiasing coverage");
        for (int y = 0; y < crossing.getHeight(); y++) {
            for (int x = 0; x < crossing.getWidth(); x++) {
                int pixel = crossing.getRGB(x, y);
                if ((pixel >>> 24) > 0) {
                    require((pixel & 0xFFFFFF) != 0xFFFFFF, "Rail raster must not contain white node/outline pixels");
                    require((pixel >> 16 & 255) > (pixel >> 8 & 255), "AA edge must retain the rail's red hue");
                }
            }
        }
        var seam = new RailTileRenderer.Segment(new RailTileRenderer.Point(120.375, 64, 60.125),
                new RailTileRenderer.Point(140.125, 64, 60.125));
        var indexed = RailTileRenderer.tiles(List.of(seam));
        BufferedImage leftTile = RailTileRenderer.render(tile, indexed.get(tile), 4, -64, 320);
        var rightKey = new RailTileRenderer.Tile(1, 0);
        BufferedImage rightTile = RailTileRenderer.render(rightKey, indexed.get(rightKey), 4, -64, 320);
        for (int row = 0; row < leftTile.getHeight(); row++) {
            require(leftTile.getRGB(511, row) == rightTile.getRGB(0, row), "Tile border must have no gap or alpha seam");
        }
        writePreview();

        var nodeFilter = JourneyMapRailNodeMixin.class.getDeclaredMethod("isRailNode", Class.class);
        require(java.lang.reflect.Modifier.isPrivate(nodeFilter.getModifiers()),
                "Mixin static helper must be private or Mixin rejects application");
        nodeFilter.setAccessible(true);
        require((boolean) nodeFilter.invoke(null, BlockNode.class), "MTR rail node must be hidden from terrain mapping");
        require((boolean) nodeFilter.invoke(null, BlockNode.BlockContinuousMovementNode.class),
                "MTR node subclasses must also be hidden");
        require(!(boolean) nodeFilter.invoke(null, Object.class) && !(boolean) nodeFilter.invoke(null, String.class),
                "Node filter must not hide unrelated blocks/classes");

        int previous = RailOverlayTracker.colorForHeight(-64, -64, 320);
        for (int y = -63; y <= 320; y++) {
            int color = RailOverlayTracker.colorForHeight(y, -64, 320);
            for (int shift : new int[]{0, 8, 16}) {
                require((color >> shift & 255) >= (previous >> shift & 255), "Higher Y must never darken");
            }
            require((color >> 16 & 255) > (color >> 8 & 255), "Palette must remain red");
            previous = color;
        }

        String id = straight.getHexId();
        String json = Utilities.getJsonObjectFromData(straight).toString();
        Path overworld = Path.of("world-a", "minecraft", "overworld.json");
        Path nether = Path.of("world-a", "minecraft", "the_nether.json");
        Map<String, String> cache = new HashMap<>(Map.of(id, json));
        RailOverlayTracker.writeCache(overworld, cache);
        RailOverlayTracker.writeCache(nether, Map.of());
        var restored = RailOverlayTracker.readCache(overworld);
        Rail fromDisk = new Rail(JsonReader.parse(restored.get(id)));
        require(RailTileRenderer.sample(fromDisk).equals(segments), "Disk round-trip changed geometry/Y");
        require(RailOverlayTracker.readCache(nether).isEmpty(), "Dimensions must not share rail data");

        List<String> deleted = new ArrayList<>();
        JMEntityBridgeMod.railDeletionListener = deleted::add;
        var hook = MtrRailDeletionMixin.class.getDeclaredMethod("onRailDeletion", JsonReader.class, CallbackInfo.class);
        hook.setAccessible(true);
        var mixin = new MtrRailDeletionMixin() {};
        hook.invoke(mixin, JsonReader.parse("{\"railsToKeep\":[]}"), new CallbackInfo("test", false));
        require(deleted.isEmpty(), "Range eviction must not erase cached rails");
        hook.invoke(mixin, JsonReader.parse("{\"railIds\":[\"" + id + "\"]}"), new CallbackInfo("test", false));
        require(deleted.equals(List.of(id)), "Explicit rail deletion packet must reach the cache listener");
        deleted.forEach(cache::remove);
        RailOverlayTracker.writeCache(overworld, cache);
        require(RailOverlayTracker.readCache(overworld).isEmpty(), "Deleted rail must remain deleted on reload");
        JMEntityBridgeMod.railDeletionListener = null;
        hook.invoke(mixin, JsonReader.parse("{\"railIds\":[\"unused\"]}"), new CallbackInfo("test", false));
        System.out.println("Rail regressions passed: exact geometry, red-only strokes, node filter, height layering, tile seams, cache, deletion hook.");
    }

    private static void writePreview() throws Exception {
        List<RailTileRenderer.Segment> segments = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            int z = 20 + index * 8;
            int y = 32 + index * 32;
            segments.addAll(RailTileRenderer.sample(railAt(new Position(8, y, z), new Position(104, y, z), Angle.W)));
            segments.addAll(RailTileRenderer.sample(railAt(new Position(104, y, z), new Position(232, y, z + 40), Angle.W)));
        }
        segments.addAll(RailTileRenderer.sample(railAt(new Position(8, 192, 104), new Position(232, 192, 16), Angle.W)));
        var tiles = RailTileRenderer.tiles(segments);
        BufferedImage preview = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_RGB);
        var graphics = preview.createGraphics();
        graphics.setColor(new Color(0x5A7437));
        graphics.fillRect(0, 0, preview.getWidth(), preview.getHeight());
        graphics.setColor(new Color(0x78935A));
        for (int x = 0; x < 1024; x += 64) { graphics.drawLine(x, 0, x, 512); }
        for (int z = 0; z < 512; z += 64) { graphics.drawLine(0, z, 1024, z); }
        for (int x = 0; x < 2; x++) {
            var tile = new RailTileRenderer.Tile(x, 0);
            graphics.drawImage(RailTileRenderer.render(tile, tiles.get(tile), 4, -64, 320), x * 512, 0, null);
        }
        graphics.dispose();
        ImageIO.write(preview, "png", Path.of("rail-preview.png").toFile());
    }

    private static Rail rail(long lengthX, long y1, long y2, long lengthZ, Angle endAngle) {
        return railAt(new Position(-32, y1, -8), new Position(-32 + lengthX, y2, -8 + lengthZ), endAngle);
    }

    private static Rail railAt(Position start, Position end, Angle endAngle) {
        Rail rail = Rail.newRail(start, Angle.E, end, endAngle,
                Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), 80, 80,
                false, false, true, false, true, TransportMode.TRAIN);
        require(rail.isValid(), "Invalid MTR fixture");
        return rail;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
