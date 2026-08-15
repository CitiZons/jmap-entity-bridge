package com.c10.jmbridge.jm;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.c10.jmbridge.JMEntityBridgeMod;

import journeymap.client.api.IClientAPI;
import journeymap.client.api.display.Context;
import journeymap.client.api.display.DisplayType;
import journeymap.client.api.display.MarkerOverlay;
import journeymap.client.api.model.MapImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

public class EntityMarkerTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("jmentitybridge");

    private static final int UPDATE_INTERVAL_TICKS = 20;

    private static final ResourceLocation DOT_TEXTURE =
            new ResourceLocation(JMEntityBridgeMod.MODID, "textures/marker_dot.png");

    private static final int COLOR_VEHICLE = 0x2979FF; // blue
    private static final int COLOR_TRAIN   = 0xE53935; // red
    private static final int COLOR_RIDE    = 0x43A047; // green

    private record TrackedType(String title, int color) {
    }

    private static final Map<ResourceLocation, TrackedType> TRACKED = Map.of(
            new ResourceLocation("mts", "builder_existing"),  new TrackedType("Vehicle (MTS)", COLOR_VEHICLE),
            new ResourceLocation("mts", "builder_seat"),      new TrackedType("Vehicle Seat (MTS)", COLOR_VEHICLE));

    private final IClientAPI api;
    private final Map<UUID, MarkerOverlay> markers = new HashMap<>();

    private boolean mappingActive;
    private int tickCounter;
    private boolean warnedShowFailure;

    public EntityMarkerTracker(IClientAPI api) {
        this.api = api;
    }

    public void setMappingActive(boolean active) {
        this.mappingActive = active;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.tickCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }
        this.tickCounter = 0;

        if (!this.mappingActive) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            clearAll();
            return;
        }
        if (!this.api.playerAccepts(JMEntityBridgeMod.MODID, DisplayType.Marker)) {
            return;
        }

        Set<UUID> seen = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.isAlive()) {
                continue;
            }
            ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            TrackedType type = typeId == null ? null : TRACKED.get(typeId);
            if (type == null) {
                continue;
            }
            seen.add(entity.getUUID());

            BlockPos pos = entity.blockPosition();
            MarkerOverlay overlay = this.markers.get(entity.getUUID());
            if (overlay != null && !level.dimension().equals(overlay.getDimension())) {
                this.api.remove(overlay);
                this.markers.remove(entity.getUUID());
                overlay = null;
            }
            if (overlay == null) {
                createMarker(entity, type, pos, level);
            } else {
                overlay.setPoint(pos);
                overlay.flagForRerender();
            }
        }

        this.markers.entrySet().removeIf(entry -> {
            if (!seen.contains(entry.getKey())) {
                this.api.remove(entry.getValue());
                return true;
            }
            return false;
        });
    }

    private void createMarker(Entity entity, TrackedType type, BlockPos pos, ClientLevel level) {
        MapImage icon = new MapImage(DOT_TEXTURE, 16, 16)
                .setColor(type.color())
                .centerAnchors();

        MarkerOverlay overlay = new MarkerOverlay(
                JMEntityBridgeMod.MODID,
                "entity-" + entity.getUUID(),
                pos,
                icon);
        overlay.setDimension(level.dimension())
                .setTitle(type.title())
                .setMinZoom(0)
                .setMaxZoom(8)
                .setActiveUIs(EnumSet.of(Context.UI.Minimap, Context.UI.Fullscreen, Context.UI.Webmap))
                .setActiveMapTypes(EnumSet.of(Context.MapType.Any));

        try {
            this.api.show(overlay);
            this.markers.put(entity.getUUID(), overlay);
        } catch (Exception e) {
            if (!this.warnedShowFailure) {
                this.warnedShowFailure = true;
                LOGGER.warn("Failed to show JourneyMap marker for {}", entity, e);
            }
        }
    }

    public void clearAll() {
        for (MarkerOverlay overlay : this.markers.values()) {
            this.api.remove(overlay);
        }
        this.markers.clear();
    }
}
