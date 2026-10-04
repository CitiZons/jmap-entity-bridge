package com.c10.jmbridge.mixin;

import journeymap.client.model.BlockFlag;
import journeymap.client.model.BlockMD;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nodes are editing helpers, not terrain pixels. Does not change their world rendering. */
@Pseudo
@Mixin(targets = "journeymap.client.mod.ModBlockDelegate", remap = false)
public abstract class JourneyMapRailNodeMixin {
    @Inject(method = "initialize(Ljourneymap/client/model/BlockMD;)V", at = @At("TAIL"), remap = false)
    private void ignoreRailNode(BlockMD block, CallbackInfo ci) {
        if (isRailNode(block.getBlock().getClass())) {
            block.removeFlags(BlockFlag.Force);
            block.addFlags(BlockFlag.Ignore, BlockFlag.NoShadow);
        }
    }

    // No hard MTR class reference: JourneyMap can also be installed without MTR.
    private static boolean isRailNode(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals("org.mtr.mod.block.BlockNode")) {
                return true;
            }
        }
        return false;
    }
}
