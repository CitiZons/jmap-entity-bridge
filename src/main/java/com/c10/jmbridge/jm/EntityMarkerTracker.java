package com.c10.jmbridge.jm;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
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
            new ResourceLocation("mts", "builder_existing"),  new TrackedType("Vehicle (MTS)", COLOR_VEHICLE));

    /**
     * MTS/Immersive Vehicles uses the single MC entity type "mts:builder_existing"
     * (mcinterface1201.BuilderEntityExisting) as a wrapper for ALL of its internal
     * entities, not just vehicles. In particular, WrapperWorld.onIVWorldTick spawns
     * one wrapped EntityPlayerGun for EVERY player, and EntityPlayerGun.update()
     * pins its position to player.getPosition() each tick. That per-player gun
     * wrapper is what shows up as a marker glued to each player, so it must be
     * filtered out. The wrapped internal entity is stored in the protected field
     * "entity" of BuilderEntityExisting; we read it via reflection because this
     * mod does not compile against MTS.
     */
    private static final String WRAPPED_ENTITY_FIELD_NAME = "entity";
    private static final String PLAYER_GUN_CLASS_NAME = "EntityPlayerGun";
    private static final double FALLBACK_PLAYER_EXCLUSION_DIST_SQ = 2.0 * 2.0;

    private final IClientAPI api;
    private final Map<UUID, MarkerOverlay> markers = new HashMap<>();
    private final Map<Class<?>, Optional<Field>> wrappedFieldCache = new HashMap<>();

    private boolean mappingActive;
    private int tickCounter;
    private boolean warnedShowFailure;
    private boolean warnedReflectionFailure;

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
            if (isPlayerBoundWrapper(entity, level)) {
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

    /**
     * Returns true when this builder wrapper must not get a marker because it is
     * one of the per-player entities MTS glues to each player (EntityPlayerGun).
     *
     * Decision order:
     * 1. Reflectively read BuilderEntityExisting#entity.
     *    - Wrapped entity is an EntityPlayerGun -> skip.
     *    - Wrapped entity not yet synced (null) -> skip for now; the type is
     *      unknown and it may be a gun. Real vehicles get their data within a
     *      few ticks and will be marked on a later pass.
     * 2. If reflection is unavailable (MTS internals changed), fall back to
     *    hiding wrappers within 2 blocks of any player. This also hides a
     *    vehicle the player is riding, which is acceptable for a fallback.
     */
    private boolean isPlayerBoundWrapper(Entity entity, ClientLevel level) {
        Field field = this.wrappedFieldCache
                .computeIfAbsent(entity.getClass(), EntityMarkerTracker::resolveWrappedField)
                .orElse(null);
        if (field != null) {
            try {
                Object wrapped = field.get(entity);
                if (wrapped == null) {
                    return true;
                }
                return PLAYER_GUN_CLASS_NAME.equals(wrapped.getClass().getSimpleName());
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (!this.warnedReflectionFailure) {
                    this.warnedReflectionFailure = true;
                    LOGGER.warn("Failed to read wrapped MTS entity; falling back to distance filter", e);
                }
            }
        } else if (!this.warnedReflectionFailure) {
            this.warnedReflectionFailure = true;
            LOGGER.warn("No '{}' field found on {}; falling back to distance filter",
                    WRAPPED_ENTITY_FIELD_NAME, entity.getClass().getName());
        }
        return level.players().stream()
                .anyMatch(p -> p.distanceToSqr(entity) < FALLBACK_PLAYER_EXCLUSION_DIST_SQ);
    }

    private static Optional<Field> resolveWrappedField(Class<?> builderClass) {
        for (Class<?> c = builderClass; c != null && c != Entity.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(WRAPPED_ENTITY_FIELD_NAME);
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException | RuntimeException ignored) {
                // keep walking up the hierarchy
            }
        }
        return Optional.empty();
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
