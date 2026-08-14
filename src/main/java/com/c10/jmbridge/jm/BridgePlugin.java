package com.c10.jmbridge.jm;

import java.util.EnumSet;

import com.c10.jmbridge.JMEntityBridgeMod;

import journeymap.client.api.ClientPlugin;
import journeymap.client.api.IClientAPI;
import journeymap.client.api.IClientPlugin;
import journeymap.client.api.event.ClientEvent;
import net.minecraftforge.common.MinecraftForge;

@ClientPlugin
public class BridgePlugin implements IClientPlugin {

    private EntityMarkerTracker tracker;

    public BridgePlugin() {
    }

    @Override
    public void initialize(IClientAPI api) {
        this.tracker = new EntityMarkerTracker(api);
        api.subscribe(getModId(), EnumSet.of(
                ClientEvent.Type.MAPPING_STARTED,
                ClientEvent.Type.MAPPING_STOPPED));
        MinecraftForge.EVENT_BUS.register(this.tracker);
    }

    @Override
    public String getModId() {
        return JMEntityBridgeMod.MODID;
    }

    @Override
    public void onEvent(ClientEvent event) {
        if (this.tracker == null) {
            return;
        }
        switch (event.type) {
            case MAPPING_STARTED -> this.tracker.setMappingActive(true);
            case MAPPING_STOPPED -> {
                this.tracker.setMappingActive(false);
                this.tracker.clearAll();
            }
            default -> {
            }
        }
    }
}
