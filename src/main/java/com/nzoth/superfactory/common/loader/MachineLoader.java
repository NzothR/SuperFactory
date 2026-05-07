package com.nzoth.superfactory.common.loader;

import net.minecraft.item.ItemStack;

import com.nzoth.superfactory.Config;
import com.nzoth.superfactory.SuperFactory;
import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;
import com.nzoth.superfactory.common.mte.MTESuperProxyFactory;

import gregtech.api.GregTechAPI;

public final class MachineLoader {

    public static final int SUPER_PROXY_FACTORY_ID_OFFSET = 1;
    public static final int SUPER_INTEGRATED_FACTORY_ID_OFFSET = 2;

    private static ItemStack superProxyFactoryController;
    private static ItemStack superIntegratedFactoryController;

    private MachineLoader() {}

    public static void load() {
        loadSuperProxyFactory();
        loadSuperIntegratedFactory();
    }

    public static ItemStack getSuperProxyFactoryController() {
        return superProxyFactoryController == null ? null : superProxyFactoryController.copy();
    }

    public static ItemStack getSuperIntegratedFactoryController() {
        return superIntegratedFactoryController == null ? null : superIntegratedFactoryController.copy();
    }

    private static void loadSuperProxyFactory() {
        if (!Config.enableSuperProxyFactory) {
            SuperFactory.LOG.info("Super Proxy Factory is disabled in config.");
            return;
        }

        int id = Config.mteIdOffset + SUPER_PROXY_FACTORY_ID_OFFSET;
        ensureIdAvailable(id);
        superProxyFactoryController = new MTESuperProxyFactory(
            id,
            "superfactory.machine.super_proxy_factory",
            "Super Proxy Factory").getStackForm(1);
    }

    private static void loadSuperIntegratedFactory() {
        if (!Config.enableSuperIntegratedFactory) {
            SuperFactory.LOG.info("Super Integrated Factory is disabled in config.");
            return;
        }

        int id = Config.mteIdOffset + SUPER_INTEGRATED_FACTORY_ID_OFFSET;
        ensureIdAvailable(id);
        superIntegratedFactoryController = new MTESuperIntegratedFactory(
            id,
            "superfactory.machine.super_integrated_factory",
            "Super Integrated Factory").getStackForm(1);
    }

    private static void ensureIdAvailable(int id) {
        if (GregTechAPI.METATILEENTITIES[id] != null) {
            throw new IllegalStateException(
                "SuperFactory MTE id " + id
                    + " is already occupied by "
                    + GregTechAPI.METATILEENTITIES[id].getClass()
                        .getName());
        }
    }
}
