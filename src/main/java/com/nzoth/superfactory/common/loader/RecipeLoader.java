package com.nzoth.superfactory.common.loader;

import net.minecraft.item.ItemStack;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTModHandler;

public final class RecipeLoader {

    private RecipeLoader() {}

    public static void load() {
        ItemStack controller = MachineLoader.getSuperProxyFactoryController();
        if (controller == null) {
            return;
        }

        ItemStack advancedAssemblyLine = GTModHandler.getModItem("gregtech", "gt.blockmachines", 64L, 13532);
        ItemStack uhv64aWirelessEnergyHatch = GTModHandler.getModItem("gregtech", "gt.blockmachines", 16L, 15082);
        if (advancedAssemblyLine == null || uhv64aWirelessEnergyHatch == null) {
            return;
        }

        GTValues.RA.stdBuilder()
            .itemInputs(
                advancedAssemblyLine,
                ItemList.Circuit_Wetwaremainframe.get(64L),
                ItemList.Circuit_Wetwaremainframe.get(64L),
                ItemList.Robot_Arm_UHV.get(64L),
                ItemList.Emitter_UHV.get(64L),
                ItemList.Sensor_UHV.get(64L),
                ItemList.Field_Generator_UHV.get(32L),
                uhv64aWirelessEnergyHatch,
                ItemList.BatteryHull_UV_Full.get(16L),
                ItemList.Tool_DataOrb.get(64L))
            .fluidInputs(Materials.SolderingAlloy.getMolten(73728L), Materials.Naquadria.getMolten(36864L))
            .itemOutputs(controller)
            .eut(GTValues.VP[9])
            .duration(20 * 300)
            .addTo(RecipeMaps.assemblylineVisualRecipes);
    }
}
