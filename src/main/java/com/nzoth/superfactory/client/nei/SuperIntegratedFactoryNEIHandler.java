package com.nzoth.superfactory.client.nei;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.nzoth.superfactory.client.ui.GuiSuperIntegratedFactoryProcess;
import com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory;

import codechicken.nei.ItemPanels;
import codechicken.nei.VisiblityData;
import codechicken.nei.api.INEIGuiHandler;
import codechicken.nei.api.TaggedInventoryArea;
import codechicken.nei.guihook.IContainerDrawHandler;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.GuiRecipeButton;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.nei.GTNEIDefaultHandler;

public final class SuperIntegratedFactoryNEIHandler implements INEIGuiHandler, IContainerDrawHandler {

    public static final SuperIntegratedFactoryNEIHandler INSTANCE = new SuperIntegratedFactoryNEIHandler();

    private SuperIntegratedFactoryNEIHandler() {}

    @SubscribeEvent
    public void onUpdateRecipeButtons(GuiRecipeButton.UpdateRecipeButtonsEvent.Post event) {
        if (event.gui instanceof GuiRecipe<?>gui && isGuiEligible(gui)) {
            SuperIntegratedFactoryOverlayButton.updateRecipeButtons(gui, event.buttonList);
        }
    }

    private boolean isGuiEligible(GuiRecipe<?> gui) {
        MTESuperIntegratedFactory factory = MTESuperIntegratedFactory.getClientEditingFactory();
        if (factory == null) {
            return false;
        }
        return factory.getSelectedProcessNodeId() > 0 && gui.getHandler() instanceof GTNEIDefaultHandler;
    }

    @Override
    public VisiblityData modifyVisiblity(GuiContainer gui, VisiblityData currentVisibility) {
        return currentVisibility;
    }

    @Override
    public Iterable<Integer> getItemSpawnSlots(GuiContainer gui, ItemStack item) {
        return Collections.emptyList();
    }

    @Override
    public List<TaggedInventoryArea> getInventoryAreas(GuiContainer gui) {
        return Collections.emptyList();
    }

    @Override
    public boolean handleDragNDrop(GuiContainer gui, int mousex, int mousey, ItemStack draggedStack, int button) {
        if (gui instanceof GuiSuperIntegratedFactoryProcess processGui
            && processGui.acceptDraggedStack(mousex, mousey, draggedStack)) {
            ItemPanels.itemPanel.draggedStack = null;
            ItemPanels.bookmarkPanel.draggedStack = null;
            return true;
        }
        return false;
    }

    @Override
    public boolean hideItemPanelSlot(GuiContainer gui, int x, int y, int w, int h) {
        if (!(gui instanceof GuiSuperIntegratedFactoryProcess processGui)) {
            return false;
        }
        return processGui.intersectsCanvas(x, y, w, h);
    }

    @Override
    public void onPreDraw(GuiContainer gui) {}

    @Override
    public void renderObjects(GuiContainer gui, int mousex, int mousey) {}

    @Override
    public void postRenderObjects(GuiContainer gui, int mousex, int mousey) {
        if (gui instanceof GuiSuperIntegratedFactoryProcess processGui) {
            processGui.drawCanvasAfterNei(mousex, mousey, 0.0F);
        }
    }

    @Override
    public void renderSlotUnderlay(GuiContainer gui, Slot slot) {}

    @Override
    public void renderSlotOverlay(GuiContainer gui, Slot slot) {}
}
