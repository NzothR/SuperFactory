package com.nzoth.superfactory.common.network;

import com.nzoth.superfactory.SuperFactory;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class NetworkLoader {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(SuperFactory.MODID);

    private static int nextId;

    private NetworkLoader() {}

    public static void init() {
        INSTANCE.registerMessage(
            MessageSetProcessNodeRecipe.Handler.class,
            MessageSetProcessNodeRecipe.class,
            nextId++,
            Side.SERVER);
        INSTANCE.registerMessage(
            MessageUpdateProcessGraph.Handler.class,
            MessageUpdateProcessGraph.class,
            nextId++,
            Side.SERVER);
    }
}
