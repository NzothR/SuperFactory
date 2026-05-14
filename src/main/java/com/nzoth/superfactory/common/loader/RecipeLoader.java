package com.nzoth.superfactory.common.loader;

import net.minecraft.item.ItemStack;

import com.nzoth.superfactory.Config;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTUtility;

public final class RecipeLoader {

    private RecipeLoader() {}

    public static void load() {
        loadSuperProxyFactoryRecipes();
        loadSuperIntegratedFactoryRecipes();
    }

    private static void loadSuperProxyFactoryRecipes() {
        ItemStack controller = MachineLoader.getSuperProxyFactoryController();
        if (controller == null) {
            return;
        }

        GTValues.RA.stdBuilder()
            .itemInputs(
                ItemList.Hull_UXV.get(1L),
                ItemList.Circuit_CosmicMainframe.get(16L),
                ItemList.Circuit_TranscendentMainframe.get(4L),
                ItemList.Robot_Arm_UXV.get(8L),
                ItemList.Emitter_UXV.get(8L),
                ItemList.Sensor_UXV.get(8L),
                ItemList.Field_Generator_UXV.get(4L),
                GTUtility.getIntegratedCircuit(24),
                ItemList.Tool_DataOrb.get(64L))
            .fluidInputs(Materials.Naquadria.getMolten(144L * 512L))
            .itemOutputs(controller)
            .eut(GTValues.VP[13])
            .duration(20 * 600)
            .addTo(RecipeMaps.assemblerRecipes);

        if (!Config.enableCheapSuperProxyFactoryRecipe) {
            return;
        }

        GTValues.RA.stdBuilder()
            .itemInputs(
                Materials.Steel.getPlates(16),
                ItemList.Hull_LV.get(1L),
                ItemList.Circuit_Board_Basic.get(4L),
                ItemList.Circuit_Basic.get(4L),
                ItemList.Robot_Arm_LV.get(2L),
                ItemList.Emitter_LV.get(1L),
                ItemList.Sensor_LV.get(1L),
                GTUtility.getIntegratedCircuit(24))
            .fluidInputs(Materials.SolderingAlloy.getMolten(144L * 4L))
            .itemOutputs(controller)
            .eut(GTValues.VP[1])
            .duration(20 * 30)
            .addTo(RecipeMaps.assemblerRecipes);
    }

    private static void loadSuperIntegratedFactoryRecipes() {
        ItemStack controller = MachineLoader.getSuperIntegratedFactoryController();
        if (controller == null) {
            return;
        }

        GTValues.RA.stdBuilder()
            .itemInputs(
                GTUtility.getIntegratedCircuit(24),
                Materials.Neutronium.getPlates(32),
                Materials.Tritanium.getPlates(32),
                Materials.Duranium.getPlates(32),
                Materials.Quantium.getPlates(16),
                ItemList.Hull_UXV.get(2L),
                ItemList.Circuit_TranscendentMainframe.get(16L),
                ItemList.Field_Generator_UXV.get(8L),
                ItemList.Tool_DataOrb.get(64L))
            .fluidInputs(Materials.Naquadria.getMolten(144L * 1024L), Materials.SolderingAlloy.getMolten(144L * 2048L))
            .itemOutputs(controller)
            .eut(GTValues.VP[13])
            .duration(20 * 900)
            .addTo(RecipeMaps.assemblerRecipes);

        if (!Config.enableCheapSuperIntegratedFactoryRecipe) {
            return;
        }

        GTValues.RA.stdBuilder()
            .itemInputs(
                Materials.Steel.getPlates(32),
                ItemList.Hull_LV.get(2L),
                ItemList.Circuit_Board_Basic.get(8L),
                ItemList.Circuit_Basic.get(8L),
                ItemList.Robot_Arm_LV.get(4L),
                ItemList.Emitter_LV.get(2L),
                ItemList.Sensor_LV.get(2L),
                GTUtility.getIntegratedCircuit(24))
            .fluidInputs(Materials.SolderingAlloy.getMolten(144L * 8L))
            .itemOutputs(controller)
            .eut(GTValues.VP[1])
            .duration(20 * 45)
            .addTo(RecipeMaps.assemblerRecipes);
    }
}
