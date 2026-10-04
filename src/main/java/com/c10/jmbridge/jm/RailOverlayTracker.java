package com.c10.jmbridge.jm;

import com.c10.jmbridge.JMEntityBridgeMod;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import journeymap.client.api.IClientAPI;
import journeymap.client.api.display.Context;
import journeymap.client.api.display.DisplayType;
import journeymap.client.api.display.ImageOverlay;
import journeymap.client.api.model.MapImage;
import journeymap.client.texture.DynamicTextureImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.mtr.core.data.Rail;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.mod.client.MinecraftClientData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Only reached when MTR and JourneyMap are installed. Owns map data, never MTR's live data. */
final class RailOverlayTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("jmentitybridge");
    private static final Gson GSON = new Gson();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jm-rail-cache");
        thread.setDaemon(true);
        return thread;
    });
    private static final int SCAN_INTERVAL = 2; // Shared 20-tick entry: scan every 40 ticks.
    private static final int SAVE_INTERVAL = 10; // Save changed data at most once per 200 ticks.
    private static final int RAILS_PER_UPDATE = 8;
    private static final int TILES_PER_UPDATE = 8;

    private final IClientAPI api;
    private final Map<String, String> cached = new HashMap<>();
    private final Map<String, Rail> observed = new HashMap<>();
    private final Map<String, Set<RailTileRenderer.Tile>> railTiles = new HashMap<>();
    private final Map<RailTileRenderer.Tile, Map<String, List<RailTileRenderer.Segment>>> geometry = new HashMap<>();
    private final Map<RailTileRenderer.Tile, TileOverlay> overlays = new HashMap<>();
    private final Set<RailTileRenderer.Tile> dirtyTiles = new LinkedHashSet<>();
    private final Map<String, Rail> pending = new LinkedHashMap<>();
    private final Deque<String> cachedToRender = new ArrayDeque<>();
    private CompletableFuture<List<RenderedTile>> rendering;
    private CompletableFuture<List<PreparedRail>> preparing;
    private ClientLevel level;
    private Path cacheFile;
    private CompletableFuture<Map<String, String>> loading;
    private int scanCounter;
    private int saveCounter;
    private boolean dirty;
    private boolean warnedShowFailure;

    RailOverlayTracker(IClientAPI api) {
        this.api = api;
        JMEntityBridgeMod.railDeletionListener = id -> Minecraft.getInstance().execute(() -> delete(id));
    }

    void update(ClientLevel currentLevel) {
        File dataPath = api.getDataPath(JMEntityBridgeMod.MODID);
        if (dataPath == null) {
            return; // JourneyMap has not selected a world yet.
        }
        var dimension = currentLevel.dimension().location();
        Path currentFile = dataPath.toPath().resolve("rails").resolve(dimension.getNamespace())
                .resolve(dimension.getPath() + ".json");
        if (level != currentLevel || !currentFile.equals(cacheFile)) {
            clear();
            level = currentLevel;
            cacheFile = currentFile;
            loading = CompletableFuture.supplyAsync(() -> readCache(currentFile), IO);
            scanCounter = SCAN_INTERVAL;
        }
        if (loading != null) {
            if (!loading.isDone()) {
                return;
            }
            cached.putAll(loading.join());
            loading = null;
            cachedToRender.addAll(cached.keySet()); // Parse/sampling is spread across shared ticks.
        }
        if (preparing != null && preparing.isDone()) {
            for (PreparedRail prepared : preparing.join()) {
                apply(prepared.id(), prepared.rail(), prepared.tiles());
            }
            preparing = null;
        }
        if (++scanCounter >= SCAN_INTERVAL) {
            scanCounter = 0;
            for (var wrapper : MinecraftClientData.getInstance().railWrapperList.values()) {
                Rail rail = wrapper.getRail();
                String id = rail.getHexId();
                if (observed.put(id, rail) != rail) {
                    String json = Utilities.getJsonObjectFromData(rail).toString();
                    String old = cached.put(id, json);
                    boolean changed = !json.equals(old);
                    dirty |= changed;
                    if (changed || !railTiles.containsKey(id)) {
                        pending.put(id, rail);
                    }
                }
            }
            // Forget object references outside the live set, but retain disk/map geometry.
            observed.keySet().retainAll(MinecraftClientData.getInstance().railWrapperList.keySet());
        }
        if (api.playerAccepts(JMEntityBridgeMod.MODID, DisplayType.Image)) {
            prepareRails();
            updateTiles();
        }
        if (++saveCounter >= SAVE_INTERVAL) {
            saveCounter = 0;
            save();
        }
    }

    private void prepareRails() {
        if (preparing != null) {
            return;
        }
        List<PendingRail> batch = new ArrayList<>(RAILS_PER_UPDATE);
        Iterator<Map.Entry<String, Rail>> iterator = pending.entrySet().iterator();
        while (batch.size() < RAILS_PER_UPDATE && iterator.hasNext()) {
            var entry = iterator.next();
            batch.add(new PendingRail(entry.getKey(), entry.getValue()));
            iterator.remove();
        }
        while (batch.size() < RAILS_PER_UPDATE && pending.isEmpty() && !cachedToRender.isEmpty()) {
            String id = cachedToRender.remove();
            String json = cached.get(id);
            if (json == null || railTiles.containsKey(id)) {
                continue;
            }
            try {
                batch.add(new PendingRail(id, new Rail(JsonReader.parse(json))));
            } catch (RuntimeException e) {
                LOGGER.warn("Ignoring invalid cached MTR rail {}", id, e);
            }
        }
        if (!batch.isEmpty()) {
            preparing = CompletableFuture.supplyAsync(() -> batch.stream()
                    .map(entry -> new PreparedRail(entry.id(), entry.rail(),
                            RailTileRenderer.tiles(RailTileRenderer.sample(entry.rail()))))
                    .toList(), IO);
        }
    }

    private void apply(String id, Rail rail, Map<RailTileRenderer.Tile, List<RailTileRenderer.Segment>> tiles) {
        removeOverlays(id);
        railTiles.put(id, tiles.keySet());
        for (var entry : tiles.entrySet()) {
            geometry.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>()).put(id, entry.getValue());
            dirtyTiles.add(entry.getKey());
        }
    }

    private record PendingRail(String id, Rail rail) {}
    private record PreparedRail(String id, Rail rail,
                                Map<RailTileRenderer.Tile, List<RailTileRenderer.Segment>> tiles) {}

    static int colorForHeight(double y, int minY, int maxY) {
        double t = Math.max(0, Math.min(1, (y - minY) / Math.max(1, maxY - (double) minY)));
        int red = (int) Math.round(80 + 175 * t);
        int green = (int) Math.round(4 + 172 * t);
        int blue = (int) Math.round(10 + 182 * t);
        return red << 16 | green << 8 | blue; // #50040A -> #FFB0C0
    }

    private void delete(String id) {
        // Never apply a late packet to the previous world/ dimension.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || level != mc.level) {
            return;
        }
        if (loading != null) {
            loading = loading.thenApply(data -> {
                data.remove(id);
                return data;
            });
            dirty = true;
        }
        dirty |= cached.remove(id) != null;
        observed.remove(id);
        pending.remove(id);
        removeOverlays(id);
    }

    private void removeOverlays(String id) {
        Set<RailTileRenderer.Tile> previous = railTiles.remove(id);
        if (previous != null) {
            for (var tile : previous) {
                var rails = geometry.get(tile);
                rails.remove(id);
                if (rails.isEmpty()) {
                    geometry.remove(tile);
                }
                dirtyTiles.add(tile);
            }
        }
    }

    private record TileOverlay(ImageOverlay overlay, ResourceLocation location, DynamicTextureImpl texture, int scale) {}
    private record RenderedTile(RailTileRenderer.Tile tile, BufferedImage image, int scale) {}

    private Map<RailTileRenderer.Tile, Integer> visibleScales() {
        Map<RailTileRenderer.Tile, Integer> visible = new HashMap<>();
        for (Context.UI ui : List.of(Context.UI.Minimap, Context.UI.Fullscreen, Context.UI.Webmap)) {
            var state = api.getUIState(ui);
            if (state == null || !state.active || !level.dimension().equals(state.dimension)
                    || state.blockBounds == null) {
                continue;
            }
            int scale = Math.max(1, Math.min(4, (int) Math.ceil(state.blockSize)));
            var bounds = state.blockBounds;
            // A small viewport margin avoids gaps during normal scrolling.
            for (var tile : geometry.keySet()) {
                if (tile.minX() <= bounds.maxX + 32 && tile.minX() + RailTileRenderer.TILE_SIZE >= bounds.minX - 32
                        && tile.minZ() <= bounds.maxZ + 32 && tile.minZ() + RailTileRenderer.TILE_SIZE >= bounds.minZ - 32) {
                    visible.merge(tile, scale, Math::max);
                }
            }
        }
        return visible;
    }

    private void updateTiles() {
        var visible = visibleScales();
        var iterator = overlays.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!visible.containsKey(entry.getKey())) {
                release(entry.getValue());
                iterator.remove();
            }
        }
        if (rendering != null && rendering.isDone()) {
            for (var tile : rendering.join()) {
                if (visible.getOrDefault(tile.tile(), 0) == tile.scale() && !dirtyTiles.contains(tile.tile())) {
                    showTile(tile);
                }
            }
            rendering = null;
        }
        if (rendering != null) {
            return;
        }
        for (var entry : visible.entrySet()) {
            var shown = overlays.get(entry.getKey());
            if (shown == null || shown.scale() != entry.getValue()) {
                dirtyTiles.add(entry.getKey());
            }
        }
        List<RailTileRenderer.Tile> tiles = new ArrayList<>();
        Map<RailTileRenderer.Tile, List<RailTileRenderer.Segment>> snapshots = new HashMap<>();
        var dirtyIterator = dirtyTiles.iterator();
        while (dirtyIterator.hasNext() && tiles.size() < TILES_PER_UPDATE) {
            var tile = dirtyIterator.next();
            if (!geometry.containsKey(tile)) {
                dirtyIterator.remove();
            } else if (visible.containsKey(tile)) {
                tiles.add(tile);
                List<RailTileRenderer.Segment> segments = new ArrayList<>();
                geometry.get(tile).values().forEach(segments::addAll);
                snapshots.put(tile, segments);
                dirtyIterator.remove();
            }
        }
        if (!tiles.isEmpty()) {
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();
            rendering = CompletableFuture.supplyAsync(() -> {
                List<RenderedTile> result = new ArrayList<>();
                for (var tile : tiles) {
                    int scale = visible.get(tile);
                    result.add(new RenderedTile(tile,
                            RailTileRenderer.render(tile, snapshots.get(tile), scale, minY, maxY), scale));
                }
                return result;
            }, IO);
        }
    }

    private void showTile(RenderedTile rendered) {
        var tile = rendered.tile();
        BufferedImage raster = rendered.image();
        NativeImage image = new NativeImage(raster.getWidth(), raster.getHeight(), false);
        int[] pixels = raster.getRGB(0, 0, raster.getWidth(), raster.getHeight(), null, 0, raster.getWidth());
        for (int index = 0; index < pixels.length; index++) {
            int argb = pixels[index];
            int abgr = argb & 0xFF00FF00 | (argb & 0xFF) << 16 | (argb >>> 16 & 0xFF);
            image.setPixelRGBA(index % raster.getWidth(), index / raster.getWidth(), abgr);
        }
        TileOverlay previous = overlays.get(tile);
        if (previous != null) {
            // Preserve both identities: replacing the overlay starts JourneyMap's asynchronous
            // texture lookup again and briefly leaves an otherwise valid rail tile invisible.
            previous.texture().setNativeImage(image);
            if (previous.scale() != rendered.scale()) {
                TextureUtil.prepareImage(previous.texture().getTextureId(), raster.getWidth(), raster.getHeight());
            }
            previous.texture().upload();
            previous.texture().setFilter(false, false);
            previous.overlay().getImage().setDisplayWidth(raster.getWidth()).setDisplayHeight(raster.getHeight());
            overlays.put(tile, new TileOverlay(previous.overlay(), previous.location(), previous.texture(), rendered.scale()));
            return;
        }
        ResourceLocation location = new ResourceLocation(JMEntityBridgeMod.MODID,
                "rail_tiles/" + tile.x() + "_" + tile.z());
        // JourneyMap recognizes this owning texture, avoiding a second wrapper sharing its native image.
        DynamicTextureImpl texture = new DynamicTextureImpl(image, location);
        texture.setFilter(false, false); // Keep the raster's AA coverage; do not blur it again on the GPU.
        Minecraft.getInstance().getTextureManager().register(location, texture);
        ImageOverlay overlay = new ImageOverlay(JMEntityBridgeMod.MODID,
                "rail-tile-" + tile.x() + "-" + tile.z(),
                new BlockPos(tile.minX(), 0, tile.minZ()),
                new BlockPos(tile.minX() + RailTileRenderer.TILE_SIZE, 0, tile.minZ() + RailTileRenderer.TILE_SIZE),
                new MapImage(location, raster.getWidth(), raster.getHeight()));
        overlay.setDimension(level.dimension()).setDisplayOrder(-1).setMinZoom(0).setMaxZoom(8)
                .setActiveUIs(EnumSet.of(Context.UI.Minimap, Context.UI.Fullscreen, Context.UI.Webmap))
                .setActiveMapTypes(EnumSet.of(Context.MapType.Any));
        try {
            api.show(overlay);
            overlays.put(tile, new TileOverlay(overlay, location, texture, rendered.scale()));
        } catch (Exception e) {
            Minecraft.getInstance().getTextureManager().release(location);
            dirtyTiles.add(tile);
            if (!warnedShowFailure) {
                warnedShowFailure = true;
                LOGGER.warn("Failed to show JourneyMap rail tile", e);
            }
        }
    }

    private void release(TileOverlay tile) {
        api.remove(tile.overlay());
        Minecraft.getInstance().getTextureManager().release(tile.location());
    }

    private void save() {
        if (!dirty || cacheFile == null || loading != null) {
            return;
        }
        Path file = cacheFile;
        Map<String, String> snapshot = new HashMap<>(cached);
        dirty = false;
        CompletableFuture.runAsync(() -> {
            try {
                writeCache(file, snapshot);
            } catch (IOException e) {
                LOGGER.warn("Failed to save MTR rail cache {}", file, e);
                Minecraft.getInstance().execute(() -> {
                    if (file.equals(cacheFile)) {
                        dirty = true;
                    }
                });
            }
        }, IO);
    }

    static Map<String, String> readCache(Path file) {
        if (!Files.exists(file)) {
            return new HashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, String> data = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
            return data == null ? new HashMap<>() : data;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to read MTR rail cache {}", file, e);
            return new HashMap<>();
        }
    }

    static void writeCache(Path file, Map<String, String> data) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary)) {
            GSON.toJson(data, writer);
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    void clear() {
        save();
        overlays.values().forEach(this::release);
        overlays.clear();
        railTiles.clear();
        geometry.clear();
        dirtyTiles.clear();
        rendering = null;
        preparing = null;
        pending.clear();
        cachedToRender.clear();
        observed.clear();
        cached.clear();
        level = null;
        cacheFile = null;
        loading = null;
        dirty = false;
        saveCounter = 0;
    }
}
