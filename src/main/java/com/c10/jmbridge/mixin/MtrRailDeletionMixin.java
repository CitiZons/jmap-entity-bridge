package com.c10.jmbridge.mixin;

import com.c10.jmbridge.JMEntityBridgeMod;
import org.mtr.core.serializer.JsonReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Range eviction is not deletion. Only explicit server deletion removes cached rails. */
@Pseudo
@Mixin(targets = "org.mtr.mod.packet.PacketDeleteData", remap = false)
public abstract class MtrRailDeletionMixin {
    @Inject(method = "runClientInbound", at = @At("TAIL"), remap = false)
    private void onRailDeletion(JsonReader reader, CallbackInfo ci) {
        if (JMEntityBridgeMod.railDeletionListener != null) {
            reader.iterateStringArray("railIds", () -> {}, JMEntityBridgeMod.railDeletionListener);
        }
    }
}
