package com.c10.jmbridge;

import net.minecraftforge.fml.common.Mod;

@Mod(JMEntityBridgeMod.MODID)
public class JMEntityBridgeMod {

    public static final String MODID = "jmentitybridge";

    // Optional integration: the packet hook must also load without JourneyMap.
    public static java.util.function.Consumer<String> railDeletionListener;

    public JMEntityBridgeMod() {
    }
}
