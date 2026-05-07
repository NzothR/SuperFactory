package com.nzoth.superfactory;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;

import com.nzoth.superfactory.client.nei.SuperIntegratedFactoryNEIHandler;
import com.nzoth.superfactory.client.ui.GuiSuperIntegratedFactoryProcess;
import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;

import codechicken.nei.api.API;
import codechicken.nei.guihook.GuiContainerManager;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        MinecraftForge.EVENT_BUS.register(SuperIntegratedFactoryNEIHandler.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(SuperIntegratedFactoryNEIHandler.INSTANCE);
        API.registerNEIGuiHandler(SuperIntegratedFactoryNEIHandler.INSTANCE);
        GuiContainerManager.addDrawHandler(SuperIntegratedFactoryNEIHandler.INSTANCE);
    }

    @Override
    public void openIntegratedFactoryProcessGui(MTESuperIntegratedFactory factory) {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiSuperIntegratedFactoryProcess(factory));
    }
}
