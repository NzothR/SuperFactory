package com.nzoth.superfactory;

import java.io.File;

import com.nzoth.superfactory.common.loader.MachineLoader;
import com.nzoth.superfactory.common.loader.RecipeLoader;
import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;
import com.nzoth.superfactory.common.network.NetworkLoader;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        File configFile = new File(
            new File(event.getModConfigurationDirectory(), SuperFactory.MODID),
            "superfactory.cfg");
        Config.synchronizeConfiguration(configFile);

        SuperFactory.LOG.info(Config.greeting);
        SuperFactory.LOG.info("I am MyMod at version " + Tags.VERSION);
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {
        NetworkLoader.init();
        MachineLoader.load();
        RecipeLoader.load();
    }

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {}

    public void openIntegratedFactoryProcessGui(MTESuperIntegratedFactory factory) {}

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {}
}
