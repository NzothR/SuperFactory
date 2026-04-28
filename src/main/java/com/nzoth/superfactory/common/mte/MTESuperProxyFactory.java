package com.nzoth.superfactory.common.mte;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.isAir;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.onElementPass;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.transpose;
import static gregtech.api.enums.HatchElement.Energy;
import static gregtech.api.enums.HatchElement.ExoticEnergy;
import static gregtech.api.enums.HatchElement.InputBus;
import static gregtech.api.enums.HatchElement.InputHatch;
import static gregtech.api.enums.HatchElement.Maintenance;
import static gregtech.api.enums.HatchElement.OutputBus;
import static gregtech.api.enums.HatchElement.OutputHatch;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizons.modularui.api.math.Color;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.common.widget.ButtonWidget;
import com.gtnewhorizons.modularui.common.widget.DynamicPositionedColumn;
import com.gtnewhorizons.modularui.common.widget.FakeSyncWidget;
import com.gtnewhorizons.modularui.common.widget.SlotWidget;
import com.gtnewhorizons.modularui.common.widget.TextWidget;
import com.nzoth.superfactory.SuperFactory;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.Textures;
import gregtech.api.gui.modularui.GTUITextures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.logic.ProcessingLogic;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.common.blocks.ItemMachines;
import tectech.thing.gui.TecTechUITextures;
import tectech.thing.metaTileEntity.multi.base.LedStatus;
import tectech.thing.metaTileEntity.multi.base.TTMultiblockBase;

public class MTESuperProxyFactory extends TTMultiblockBase implements ISurvivalConstructable {

    private static final String STRUCTURE_PIECE_MAIN = "main";
    private static final int OFFSET_H = 1;
    private static final int OFFSET_V = 1;
    private static final int OFFSET_D = 0;
    private static final int CASING_META = 0;
    private static final int CASING_INDEX = GTUtility.getCasingTextureIndex(GregTechAPI.sBlockCasings2, CASING_META);
    private static final String[][] STRUCTURE = new String[][] { { "CCC", "CCC", "CCC" }, { "C~C", "CBC", "CCC" },
        { "CCC", "CCC", "CCC" } };
    private static final IStructureDefinition<MTESuperProxyFactory> STRUCTURE_DEFINITION = StructureDefinition
        .<MTESuperProxyFactory>builder()
        .addShape(STRUCTURE_PIECE_MAIN, transpose(STRUCTURE))
        .addElement('B', isAir())
        .addElement(
            'C',
            buildHatchAdder(MTESuperProxyFactory.class)
                .atLeast(InputBus, InputHatch, OutputBus, OutputHatch, Maintenance, Energy.or(ExoticEnergy))
                .casingIndex(CASING_INDEX)
                .dot(1)
                .buildAndChain(
                    onElementPass(
                        MTESuperProxyFactory::onCasingAdded,
                        ofBlock(GregTechAPI.sBlockCasings2, CASING_META))))
        .build();

    private static final int INDEX_BATCH = 0;
    private static final int INDEX_LOCK = 10;
    private static final int INDEX_SMART = 1;
    private static final int INDEX_MAX_TIME = 11;
    private static final int INDEX_MIN_TIME = 2;
    private static final int INDEX_PARALLEL = 12;
    private static final int INDEX_INPUT_SEPARATION = 3;
    private static final int INDEX_POWER_DISPLAY_MODE = 13;

    private static final int INDEX_ITEM_MULTIPLIER = 3;
    private static final int INDEX_FLUID_MULTIPLIER = 13;
    private static final int INDEX_ITEM_MIN = 4;
    private static final int INDEX_FLUID_MIN = 14;
    private static final int INDEX_ITEM_MAX = 5;
    private static final int INDEX_FLUID_MAX = 15;

    private static final int[] OUTPUT_DISPLAY_INDICES = new int[] { 0, 10, 1, 11, 2, 12 };
    private int casingCount;
    private List<String> cachedRecipeMapNames = new ArrayList<>();
    private int cachedRecipeMapIndex;
    private int cachedMachineMeta = -1;
    private String cachedMachineName = "";
    private int cachedMachineCount;
    private int cachedParallelLimit = 1;
    private String lastCacheMessage = "No cached machine";
    private int lastLoggedProgress = -1;

    public MTESuperProxyFactory(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTESuperProxyFactory(String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTESuperProxyFactory(this.mName);
    }

    @Override
    public IStructureDefinition<MTESuperProxyFactory> getStructure_EM() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public IStructureDefinition<TTMultiblockBase> getStructureDefinition() {
        return (IStructureDefinition) STRUCTURE_DEFINITION;
    }

    @Override
    public void construct(ItemStack stackSize, boolean hintsOnly) {
        structureBuild_EM(STRUCTURE_PIECE_MAIN, OFFSET_H, OFFSET_V, OFFSET_D, stackSize, hintsOnly);
    }

    @Override
    public int survivalConstruct(ItemStack stackSize, int elementBudget, ISurvivalBuildEnvironment env) {
        if (mMachine) {
            return -1;
        }
        return survivalBuildPiece(
            STRUCTURE_PIECE_MAIN,
            stackSize,
            OFFSET_H,
            OFFSET_V,
            OFFSET_D,
            elementBudget,
            env,
            false,
            true);
    }

    @Override
    protected void parametersInstantiation_EM() {
        var group0 = parametrization.getGroup(0, true);
        group0.makeInParameter(
            0,
            1,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.batch"),
            (base, parameter) -> boolStatus(parameter.get()));
        group0.makeOutParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.item_multiplier"),
            (base, parameter) -> displayStatus(parameter.get()));
        group0.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.lock"),
            (base, parameter) -> boolStatus(parameter.get()));
        group0.makeOutParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.fluid_multiplier"),
            (base, parameter) -> displayStatus(parameter.get()));

        var group1 = parametrization.getGroup(1, true);
        group1.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.smart_cleanup"),
            (base, parameter) -> featureStatus(parameter.get(), false));
        group1.makeOutParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.item_min_output"),
            (base, parameter) -> displayStatus(parameter.get()));
        group1.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.max_runtime"),
            (base, parameter) -> timeStatus(parameter.get(), true));
        group1.makeOutParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.fluid_min_output"),
            (base, parameter) -> displayStatus(parameter.get()));

        var group2 = parametrization.getGroup(2, true);
        group2.makeInParameter(
            0,
            1,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.min_runtime"),
            (base, parameter) -> timeStatus(parameter.get(), false));
        group2.makeOutParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.item_max_output"),
            (base, parameter) -> displayStatus(parameter.get()));
        group2.makeInParameter(
            1,
            1,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.parallel"),
            (base, parameter) -> parallelStatus(parameter.get()));
        group2.makeOutParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.fluid_max_output"),
            (base, parameter) -> displayStatus(parameter.get()));

        var group3 = parametrization.getGroup(3, true);
        group3.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.input_separation"),
            (base, parameter) -> boolStatus(parameter.get()));
        group3.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.power_display_mode"),
            (base, parameter) -> boolStatus(parameter.get()));
    }

    @Override
    protected void parametersStatusesWrite_EM(boolean busy) {
        Arrays.fill(inputStatuses(), LedStatus.STATUS_UNUSED);
        Arrays.fill(outputStatuses(), LedStatus.STATUS_UNUSED);

        inputStatuses()[INDEX_BATCH] = boolStatus(inputValues()[INDEX_BATCH]);
        inputStatuses()[INDEX_LOCK] = boolStatus(inputValues()[INDEX_LOCK]);
        inputStatuses()[INDEX_SMART] = featureStatus(inputValues()[INDEX_SMART], false);
        inputStatuses()[INDEX_MAX_TIME] = timeStatus(inputValues()[INDEX_MAX_TIME], true);
        inputStatuses()[INDEX_MIN_TIME] = timeStatus(inputValues()[INDEX_MIN_TIME], false);
        inputStatuses()[INDEX_PARALLEL] = parallelStatus(inputValues()[INDEX_PARALLEL]);
        inputStatuses()[INDEX_INPUT_SEPARATION] = boolStatus(inputValues()[INDEX_INPUT_SEPARATION]);
        inputStatuses()[INDEX_POWER_DISPLAY_MODE] = boolStatus(inputValues()[INDEX_POWER_DISPLAY_MODE]);

        outputStatuses()[OUTPUT_DISPLAY_INDICES[0]] = displayStatus(outputValues()[OUTPUT_DISPLAY_INDICES[0]]);
        outputStatuses()[OUTPUT_DISPLAY_INDICES[1]] = displayStatus(outputValues()[OUTPUT_DISPLAY_INDICES[1]]);
        outputStatuses()[OUTPUT_DISPLAY_INDICES[2]] = displayStatus(outputValues()[OUTPUT_DISPLAY_INDICES[2]]);
        outputStatuses()[OUTPUT_DISPLAY_INDICES[3]] = displayStatus(outputValues()[OUTPUT_DISPLAY_INDICES[3]]);
        outputStatuses()[OUTPUT_DISPLAY_INDICES[4]] = displayStatus(outputValues()[OUTPUT_DISPLAY_INDICES[4]]);
        outputStatuses()[OUTPUT_DISPLAY_INDICES[5]] = displayStatus(outputValues()[OUTPUT_DISPLAY_INDICES[5]]);
    }

    @Override
    protected boolean checkMachine_EM(IGregTechTileEntity aBaseMetaTileEntity, ItemStack aStack) {
        casingCount = 0;
        hasMaintenanceChecks = false;
        mWrench = true;
        mScrewdriver = true;
        mSoftMallet = true;
        mHardHammer = true;
        mSolderingTool = true;
        mCrowbar = true;
        return structureCheck_EM(STRUCTURE_PIECE_MAIN, OFFSET_H, OFFSET_V, OFFSET_D) && casingCount >= 7;
    }

    @Override
    protected CheckRecipeResult checkProcessing_EM() {
        setInputSeparation(isInputSeparationEnabled());
        RecipeMap<?> recipeMap = getRecipeMap();
        if (recipeMap == null) {
            logDebug("未缓存配方表，无法开始配方检查");
            return CheckRecipeResultRegistry.NO_RECIPE;
        }
        logDebug(
            "开始检查配方: 主机=" + cachedMachineName
                + ", 配方表="
                + getCurrentRecipeMapDisplayName()
                + ", 并行上限="
                + getEffectiveParallelLimit()
                + ", 输入总线="
                + mInputBusses.size()
                + ", 输入仓="
                + mInputHatches.size()
                + ", 输出总线="
                + mOutputBusses.size()
                + ", 输出仓="
                + mOutputHatches.size());
        CheckRecipeResult result = super.checkProcessing_EM();
        logDebug(
            "配方检查结果: " + result.getID()
                + ", 成功="
                + result.wasSuccessful()
                + ", 耗时="
                + mMaxProgresstime
                + ", 功耗="
                + Math.abs(lEUt)
                + " EU/t, 物品输出="
                + describeItemOutputs(mOutputItems)
                + ", 流体输出="
                + describeFluidOutputs(mOutputFluids));
        if (result.wasSuccessful()) {
            lastLoggedProgress = -1;
        }
        return result;
    }

    @Override
    protected ProcessingLogic createProcessingLogic() {
        return new ProcessingLogic() {

            @Override
            public CheckRecipeResult process() {
                CheckRecipeResult result = super.process();
                if (!result.wasSuccessful()) {
                    return result;
                }
                outputItems = transformItems(outputItems);
                outputFluids = transformFluids(outputFluids);
                duration = transformDuration(duration);
                return result;
            }
        };
    }

    @Override
    protected void setupProcessingLogic(ProcessingLogic logic) {
        super.setupProcessingLogic(logic);
        logic.setVoidProtection(protectsExcessItem(), protectsExcessFluid());
        logic.setBatchSize(isBatchEnabled() ? getMaxBatchSize() : 1);
        logic.setRecipeLocking(this, isRecipeLockEnabled());
        logic.setMaxParallelSupplier(this::getEffectiveParallelLimit);
    }

    @Override
    public RecipeMap<?> getRecipeMap() {
        if (cachedRecipeMapNames.isEmpty()) {
            return null;
        }
        if (cachedRecipeMapIndex < 0 || cachedRecipeMapIndex >= cachedRecipeMapNames.size()) {
            cachedRecipeMapIndex = 0;
        }
        return RecipeMap.ALL_RECIPE_MAPS.get(cachedRecipeMapNames.get(cachedRecipeMapIndex));
    }

    @Override
    public boolean canUseControllerSlotForRecipe() {
        return false;
    }

    @Override
    public void onScrewdriverRightClick(ForgeDirection side, EntityPlayer aPlayer, float aX, float aY, float aZ,
        ItemStack aTool) {
        super.onScrewdriverRightClick(side, aPlayer, aX, aY, aZ, aTool);
        if (cachedRecipeMapNames.isEmpty()) {
            GTUtility.sendChatToPlayer(
                aPlayer,
                EnumChatFormatting.RED + tr("superfactory.machine.super_proxy_factory.chat.no_modes"));
            return;
        }
        cachedRecipeMapIndex = (cachedRecipeMapIndex + 1) % cachedRecipeMapNames.size();
        GTUtility.sendChatToPlayer(
            aPlayer,
            EnumChatFormatting.AQUA + tr("superfactory.machine.super_proxy_factory.chat.mode_prefix")
                + getCurrentRecipeMapDisplayName());
    }

    @Override
    public boolean supportsInputSeparation() {
        return false;
    }

    @Override
    public boolean supportsBatchMode() {
        return false;
    }

    @Override
    public boolean supportsSingleRecipeLocking() {
        return false;
    }

    @Override
    public boolean supportsVoidProtection() {
        return false;
    }

    @Override
    public boolean protectsExcessItem() {
        return true;
    }

    @Override
    public boolean protectsExcessFluid() {
        return true;
    }

    @Override
    public boolean getDefaultHasMaintenanceChecks() {
        return false;
    }

    @Override
    public boolean showRecipeTextInGUI() {
        return true;
    }

    @Override
    public int getMaxEfficiency(ItemStack aStack) {
        return 10000;
    }

    @Override
    public int getDamageToComponent(ItemStack aStack) {
        return 0;
    }

    @Override
    public boolean doRandomMaintenanceDamage() {
        return true;
    }

    @Override
    public boolean onRunningTick(ItemStack aStack) {
        boolean running = super.onRunningTick(aStack);
        if (running && mMaxProgresstime > 0 && mProgresstime != lastLoggedProgress) {
            lastLoggedProgress = mProgresstime;
            if (mProgresstime == 0 || mProgresstime == 1
                || mProgresstime == mMaxProgresstime / 2
                || mProgresstime + 1 >= mMaxProgresstime) {
                logDebug(
                    "运行推进: 进度=" + mProgresstime
                        + "/"
                        + mMaxProgresstime
                        + ", 实际耗能="
                        + getActualEnergyUsage()
                        + " EU/t");
            }
        }
        return running;
    }

    @Override
    protected void afterRecipeCheckFailed() {
        super.afterRecipeCheckFailed();
        lEUt = 0;
    }

    public void outputAfterRecipe_EM() {
        logDebug("配方完成，准备输出: 物品=" + describeItemOutputs(mOutputItems) + ", 流体=" + describeFluidOutputs(mOutputFluids));
        super.outputAfterRecipe_EM();
    }

    @Override
    public void stopMachine(gregtech.api.util.shutdown.ShutDownReason reason) {
        logDebug(
            "机器停机: 原因=" + reason.getID()
                + ", 当前进度="
                + mProgresstime
                + "/"
                + mMaxProgresstime
                + ", 当前输出物品="
                + describeItemOutputs(mOutputItems)
                + ", 当前输出流体="
                + describeFluidOutputs(mOutputFluids));
        super.stopMachine(reason);
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity baseMetaTileEntity, ForgeDirection side, ForgeDirection facing,
        int colorIndex, boolean active, boolean redstoneLevel) {
        if (side != facing) {
            return new ITexture[] { Textures.BlockIcons.getCasingTextureForId(CASING_INDEX) };
        }
        if (active) {
            return new ITexture[] { Textures.BlockIcons.getCasingTextureForId(CASING_INDEX), TextureFactory.builder()
                .addIcon(Textures.BlockIcons.OVERLAY_FRONT_ASSEMBLY_LINE_ACTIVE)
                .extFacing()
                .build(),
                TextureFactory.builder()
                    .addIcon(Textures.BlockIcons.OVERLAY_FRONT_ASSEMBLY_LINE_ACTIVE_GLOW)
                    .extFacing()
                    .glow()
                    .build() };
        }
        return new ITexture[] { Textures.BlockIcons.getCasingTextureForId(CASING_INDEX), TextureFactory.builder()
            .addIcon(Textures.BlockIcons.OVERLAY_FRONT_ASSEMBLY_LINE)
            .extFacing()
            .build(),
            TextureFactory.builder()
                .addIcon(Textures.BlockIcons.OVERLAY_FRONT_ASSEMBLY_LINE_GLOW)
                .extFacing()
                .glow()
                .build() };
    }

    @Override
    public MultiblockTooltipBuilder createTooltip() {
        return new MultiblockTooltipBuilder()
            .addMachineType(tr("superfactory.machine.super_proxy_factory.tooltip.type"))
            .addInfo(tr("superfactory.machine.super_proxy_factory.tooltip.1"))
            .addInfo(tr("superfactory.machine.super_proxy_factory.tooltip.2"))
            .addInfo(tr("superfactory.machine.super_proxy_factory.tooltip.3"))
            .addInfo(tr("superfactory.machine.super_proxy_factory.tooltip.4"))
            .beginStructureBlock(3, 3, 3, false)
            .addController(tr("superfactory.machine.super_proxy_factory.tooltip.controller"))
            .addInputBus(tr("superfactory.machine.super_proxy_factory.tooltip.any_casing"), 1)
            .addInputHatch(tr("superfactory.machine.super_proxy_factory.tooltip.any_casing"), 1)
            .addOutputBus(tr("superfactory.machine.super_proxy_factory.tooltip.any_casing"), 1)
            .addOutputHatch(tr("superfactory.machine.super_proxy_factory.tooltip.any_casing"), 1)
            .addEnergyHatch(tr("superfactory.machine.super_proxy_factory.tooltip.any_casing"), 1)
            .toolTipFinisher();
    }

    @Override
    protected void drawTexts(DynamicPositionedColumn screenElements, SlotWidget inventorySlot) {
        super.drawTexts(screenElements, inventorySlot);
        screenElements.widget(
            new TextWidget(tr("superfactory.machine.super_proxy_factory.name")).setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget
                .dynamicString(
                    () -> tr("superfactory.machine.super_proxy_factory.gui.cached_host") + ": "
                        + (cachedMachineName.isEmpty() ? EnumChatFormatting.RED + tr("superfactory.common.none")
                            : EnumChatFormatting.AQUA + cachedMachineName))
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget
                .dynamicString(
                    () -> tr("superfactory.machine.super_proxy_factory.gui.recipe_mode") + ": "
                        + (cachedRecipeMapNames.isEmpty() ? EnumChatFormatting.RED + tr("superfactory.common.none")
                            : EnumChatFormatting.GREEN + getCurrentRecipeMapDisplayName()))
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget
                .dynamicString(
                    () -> tr("superfactory.machine.super_proxy_factory.gui.parallel") + ": "
                        + EnumChatFormatting.AQUA
                        + getEffectiveParallelLimit()
                        + EnumChatFormatting.GRAY
                        + " / "
                        + cachedParallelLimit)
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget
                .dynamicString(
                    () -> tr("superfactory.machine.super_proxy_factory.gui.cache_state") + ": "
                        + EnumChatFormatting.GRAY
                        + lastCacheMessage)
                .setDefaultColor(Color.WHITE.normal));
        screenElements
            .widget(
                TextWidget
                    .dynamicString(
                        () -> tr("superfactory.machine.super_proxy_factory.gui.smart_cleanup") + ": "
                            + (isSmartCleanupEnabled()
                                ? EnumChatFormatting.YELLOW
                                    + tr("superfactory.machine.super_proxy_factory.gui.placeholder")
                                : EnumChatFormatting.DARK_GRAY + tr("superfactory.common.off")))
                    .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget
                .dynamicString(
                    () -> tr("superfactory.machine.super_proxy_factory.gui.progress") + ": "
                        + EnumChatFormatting.AQUA
                        + mProgresstime
                        + EnumChatFormatting.GRAY
                        + " / "
                        + mMaxProgresstime)
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget
                .dynamicString(
                    () -> tr("superfactory.machine.super_proxy_factory.gui.eu") + ": "
                        + EnumChatFormatting.RED
                        + formatPowerUsageDisplay())
                .setDefaultColor(Color.WHITE.normal));
    }

    @Override
    public void addUIWidgets(ModularWindow.Builder builder, UIBuildContext buildContext) {
        super.addUIWidgets(builder, buildContext);
        addGuiSyncers(builder);
    }

    @Override
    protected ButtonWidget createPowerPassButton() {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (!widget.isClient()) {
                refreshRecipeCache();
            }
        })
            .setPlayClickSound(true)
            .setBackground(TecTechUITextures.BUTTON_STANDARD_16x16, GTUITextures.OVERLAY_BUTTON_ARROW_GREEN_UP)
            .setPos(174, 116)
            .setSize(16, 16);
        button.addTooltip(tr("superfactory.machine.super_proxy_factory.gui.tooltip.update_cache"))
            .setTooltipShowUpDelay(5);
        return button;
    }

    @Override
    protected ButtonWidget createSafeVoidButton() {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (!widget.isClient()) {
                mStructureChanged = true;
                checkMachine(getBaseMetaTileEntity(), getControllerSlot());
            }
        })
            .setPlayClickSound(true)
            .setBackground(TecTechUITextures.BUTTON_STANDARD_16x16, GTUITextures.OVERLAY_BUTTON_CHECKMARK)
            .setPos(174, 132)
            .setSize(16, 16);
        button.addTooltip(tr("superfactory.machine.super_proxy_factory.gui.tooltip.check_structure"))
            .setTooltipShowUpDelay(5);
        return button;
    }

    @Override
    protected ButtonWidget createPowerSwitchButton() {
        return super.createPowerSwitchButton();
    }

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);
        aNBT.setInteger("CachedRecipeMapIndex", cachedRecipeMapIndex);
        aNBT.setInteger("CachedMachineMeta", cachedMachineMeta);
        aNBT.setString("CachedMachineName", cachedMachineName);
        aNBT.setInteger("CachedMachineCount", cachedMachineCount);
        aNBT.setInteger("CachedParallelLimit", cachedParallelLimit);
        aNBT.setString("LastCacheMessage", lastCacheMessage);

        NBTTagList recipeList = new NBTTagList();
        for (String recipeMapName : cachedRecipeMapNames) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("Name", recipeMapName);
            recipeList.appendTag(tag);
        }
        aNBT.setTag("CachedRecipeMaps", recipeList);
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
        cachedRecipeMapIndex = aNBT.getInteger("CachedRecipeMapIndex");
        cachedMachineMeta = aNBT.getInteger("CachedMachineMeta");
        cachedMachineName = aNBT.getString("CachedMachineName");
        cachedMachineCount = Math.max(0, aNBT.getInteger("CachedMachineCount"));
        cachedParallelLimit = Math.max(1, aNBT.getInteger("CachedParallelLimit"));
        lastCacheMessage = aNBT.getString("LastCacheMessage");
        cachedRecipeMapNames.clear();
        NBTTagList recipeList = aNBT.getTagList("CachedRecipeMaps", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < recipeList.tagCount(); i++) {
            String name = recipeList.getCompoundTagAt(i)
                .getString("Name");
            if (!name.isEmpty()) {
                cachedRecipeMapNames.add(name);
            }
        }
        if (cachedRecipeMapIndex >= cachedRecipeMapNames.size()) {
            cachedRecipeMapIndex = 0;
        }
    }

    private void addGuiSyncers(ModularWindow.Builder builder) {
        builder
            .widget(new FakeSyncWidget.IntegerSyncer(() -> cachedRecipeMapIndex, value -> cachedRecipeMapIndex = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> cachedMachineMeta, value -> cachedMachineMeta = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> cachedMachineCount, value -> cachedMachineCount = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> cachedParallelLimit, value -> cachedParallelLimit = value))
            .widget(new FakeSyncWidget.StringSyncer(() -> cachedMachineName, value -> cachedMachineName = value))
            .widget(new FakeSyncWidget.StringSyncer(() -> lastCacheMessage, value -> lastCacheMessage = value));
    }

    private void refreshRecipeCache() {
        ItemStack controller = getControllerSlot();
        if (controller == null) {
            clearCache(tr("superfactory.machine.super_proxy_factory.cache.cleared_empty"));
            return;
        }
        if (!(controller.getItem() instanceof ItemMachines)) {
            clearCache(tr("superfactory.machine.super_proxy_factory.cache.cleared_invalid_item"));
            return;
        }
        int meta = controller.getItemDamage();
        if (meta <= 0 || meta >= GregTechAPI.METATILEENTITIES.length) {
            clearCache(tr("superfactory.machine.super_proxy_factory.cache.cleared_invalid_meta") + ": " + meta);
            return;
        }

        IMetaTileEntity metaTileEntity = GregTechAPI.METATILEENTITIES[meta];
        if (!(metaTileEntity instanceof MTEMultiBlockBase)) {
            clearCache(tr("superfactory.machine.super_proxy_factory.cache.cleared_not_multiblock"));
            return;
        }
        if (!(metaTileEntity instanceof RecipeMapWorkable workable)) {
            clearCache(tr("superfactory.machine.super_proxy_factory.cache.cleared_no_recipe_map"));
            return;
        }

        List<String> recipeMaps = new ArrayList<>();
        Collection<RecipeMap<?>> available = workable.getAvailableRecipeMaps();
        if (available != null) {
            for (RecipeMap<?> recipeMap : available) {
                if (recipeMap != null && recipeMap != RecipeMaps.assemblylineVisualRecipes) {
                    recipeMaps.add(recipeMap.unlocalizedName);
                }
            }
        }
        RecipeMap<?> primary = workable.getRecipeMap();
        if (primary != null && primary != RecipeMaps.assemblylineVisualRecipes
            && !recipeMaps.contains(primary.unlocalizedName)) {
            recipeMaps.add(primary.unlocalizedName);
        }
        if (recipeMaps.isEmpty()) {
            clearCache(tr("superfactory.machine.super_proxy_factory.cache.cleared_no_usable_map"));
            return;
        }

        cachedRecipeMapNames = recipeMaps;
        cachedRecipeMapIndex = Math.min(cachedRecipeMapIndex, cachedRecipeMapNames.size() - 1);
        cachedMachineMeta = meta;
        cachedMachineName = metaTileEntity.getLocalName();
        cachedMachineCount = Math.max(1, controller.stackSize);
        cachedParallelLimit = mapMachineCountToParallel(cachedMachineCount);
        lastCacheMessage = tr("superfactory.machine.super_proxy_factory.cache.cached_prefix") + " "
            + recipeMaps.size()
            + " "
            + tr("superfactory.machine.super_proxy_factory.cache.cached_suffix")
            + " "
            + cachedMachineName;
        logDebug(
            "刷新缓存成功: 主机=" + cachedMachineName
                + ", 主机数量="
                + cachedMachineCount
                + ", 最大并行="
                + cachedParallelLimit
                + ", 配方表="
                + recipeMaps);
    }

    private ItemStack[] transformItems(ItemStack[] rawOutputs) {
        if (rawOutputs == null) {
            return null;
        }
        ItemStack[] transformed = new ItemStack[rawOutputs.length];
        int multiplier = getItemOutputMultiplier();
        long minAmount = getItemMinimumOutput();
        long maxAmount = getItemMaximumOutput();
        for (int i = 0; i < rawOutputs.length; i++) {
            ItemStack stack = rawOutputs[i];
            if (stack == null) {
                continue;
            }
            ItemStack copy = stack.copy();
            long amount = copy.stackSize;
            if (multiplier > 0) {
                amount *= multiplier;
            }
            if (minAmount > 0) {
                amount = Math.max(amount, minAmount);
            }
            if (maxAmount > 0) {
                amount = Math.min(amount, maxAmount);
            }
            copy.stackSize = (int) Math.max(0, Math.min(Integer.MAX_VALUE, amount));
            transformed[i] = copy;
        }
        return transformed;
    }

    private FluidStack[] transformFluids(FluidStack[] rawOutputs) {
        if (rawOutputs == null) {
            return null;
        }
        FluidStack[] transformed = new FluidStack[rawOutputs.length];
        int multiplier = getFluidOutputMultiplier();
        long minAmount = getFluidMinimumOutput();
        long maxAmount = getFluidMaximumOutput();
        for (int i = 0; i < rawOutputs.length; i++) {
            FluidStack stack = rawOutputs[i];
            if (stack == null) {
                continue;
            }
            FluidStack copy = stack.copy();
            long amount = copy.amount;
            if (multiplier > 0) {
                amount *= multiplier;
            }
            if (minAmount > 0) {
                amount = Math.max(amount, minAmount);
            }
            if (maxAmount > 0) {
                amount = Math.min(amount, maxAmount);
            }
            copy.amount = (int) Math.max(0, Math.min(Integer.MAX_VALUE, amount));
            transformed[i] = copy;
        }
        return transformed;
    }

    private int transformDuration(int rawDuration) {
        int result = rawDuration;
        long minRuntime = getMinimumRuntime();
        long maxRuntime = getMaximumRuntime();
        if (minRuntime > 0) {
            result = Math.max(result, (int) Math.min(Integer.MAX_VALUE, minRuntime));
        }
        if (maxRuntime > 0) {
            result = Math.min(result, (int) Math.min(Integer.MAX_VALUE, maxRuntime));
        }
        return Math.max(1, result);
    }

    private int getEffectiveParallelLimit() {
        int cachedCap = Math.max(1, cachedParallelLimit);
        int configured = Math.max(1, (int) Math.min(Integer.MAX_VALUE, Math.round(inputValues()[INDEX_PARALLEL])));
        return Math.min(cachedCap, configured);
    }

    private boolean isBatchEnabled() {
        return asBoolean(inputValues()[INDEX_BATCH], true);
    }

    private boolean isRecipeLockEnabled() {
        return asBoolean(inputValues()[INDEX_LOCK], false);
    }

    private boolean isSmartCleanupEnabled() {
        return asBoolean(inputValues()[INDEX_SMART], false);
    }

    @Override
    public boolean isInputSeparationEnabled() {
        return asBoolean(inputValues()[INDEX_INPUT_SEPARATION], false);
    }

    private int getItemOutputMultiplier() {
        return clampOutputInt(OUTPUT_DISPLAY_INDICES[0]);
    }

    private int getFluidOutputMultiplier() {
        return clampOutputInt(OUTPUT_DISPLAY_INDICES[1]);
    }

    private long getItemMinimumOutput() {
        return clampOutputLong(OUTPUT_DISPLAY_INDICES[2]);
    }

    private long getFluidMinimumOutput() {
        return clampOutputLong(OUTPUT_DISPLAY_INDICES[3]);
    }

    private long getItemMaximumOutput() {
        return clampOutputLong(OUTPUT_DISPLAY_INDICES[4]);
    }

    private long getFluidMaximumOutput() {
        return clampOutputLong(OUTPUT_DISPLAY_INDICES[5]);
    }

    private long getMaximumRuntime() {
        return clampInputLong(INDEX_MAX_TIME);
    }

    private long getMinimumRuntime() {
        long raw = clampInputLong(INDEX_MIN_TIME);
        return Math.max(1L, raw);
    }

    private boolean isNumericPowerDisplayEnabled() {
        return asBoolean(inputValues()[INDEX_POWER_DISPLAY_MODE], false);
    }

    private String formatPowerUsageDisplay() {
        long euPerTick = mMaxProgresstime > 0 ? Math.abs(lEUt) : 0;
        if (euPerTick <= 0) {
            return "0 EU/t";
        }
        if (isNumericPowerDisplayEnabled()) {
            return euPerTick + " EU/t";
        }
        int tier = 0;
        while (tier + 1 < GTValues.V.length && euPerTick > GTValues.V[tier]) {
            tier++;
        }
        long tierVoltage = Math.max(1L, GTValues.V[tier]);
        long amperage = Math.max(1L, (euPerTick + tierVoltage - 1L) / tierVoltage);
        return amperage + "A " + GTValues.VN[tier] + "/t" + EnumChatFormatting.GRAY + " (" + euPerTick + " EU/t)";
    }

    private int clampInputInt(int index) {
        double raw = inputValues()[index];
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(raw)));
    }

    private long clampInputLong(int index) {
        double raw = inputValues()[index];
        return Math.max(0L, Math.round(raw));
    }

    private int clampOutputInt(int index) {
        double raw = outputValues()[index];
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(raw)));
    }

    private long clampOutputLong(int index) {
        double raw = outputValues()[index];
        return Math.max(0L, Math.round(raw));
    }

    private boolean asBoolean(double value, boolean defaultState) {
        if (Double.isNaN(value)) {
            return defaultState;
        }
        return Math.round(value) != 0L;
    }

    private LedStatus boolStatus(double value) {
        return Math.round(value) == 0L ? LedStatus.STATUS_TOO_LOW : LedStatus.STATUS_OK;
    }

    private LedStatus featureStatus(double value, boolean implemented) {
        if (Math.round(value) == 0L) {
            return LedStatus.STATUS_NEUTRAL;
        }
        return implemented ? LedStatus.STATUS_OK : LedStatus.STATUS_WRONG;
    }

    private LedStatus timeStatus(double value, boolean allowDisabled) {
        if (allowDisabled && value <= 0) {
            return LedStatus.STATUS_NEUTRAL;
        }
        return value >= 1 ? LedStatus.STATUS_OK : LedStatus.STATUS_TOO_LOW;
    }

    private LedStatus parallelStatus(double value) {
        if (value < 1) {
            return LedStatus.STATUS_TOO_LOW;
        }
        if (value > cachedParallelLimit && cachedParallelLimit > 0) {
            return LedStatus.STATUS_TOO_HIGH;
        }
        return LedStatus.STATUS_OK;
    }

    private LedStatus displayStatus(double value) {
        return value <= 0 ? LedStatus.STATUS_NEUTRAL : LedStatus.STATUS_OK;
    }

    private double clampParameterValue(int parameterIndex, double rawValue) {
        long rounded = Math.round(rawValue);
        return switch (parameterIndex) {
            case INDEX_BATCH, INDEX_LOCK, INDEX_SMART, INDEX_INPUT_SEPARATION, INDEX_POWER_DISPLAY_MODE -> rounded <= 0
                ? 0
                : 1;
            case INDEX_MIN_TIME, INDEX_PARALLEL -> Math.max(1L, Math.min(Integer.MAX_VALUE, rounded));
            case INDEX_MAX_TIME -> rounded <= 0 ? 0 : rounded;
            default -> rounded;
        };
    }

    private int mapMachineCountToParallel(int machineCount) {
        if (machineCount <= 1) {
            return 1;
        }
        if (machineCount >= 64) {
            return Integer.MAX_VALUE;
        }
        double normalized = (machineCount - 1.0D) / 63.0D;
        double mapped = Math.pow(2.0D, normalized * 31.0D);
        return Math.max(1, Math.min(Integer.MAX_VALUE, GTUtility.safeInt(Math.round(mapped), 1)));
    }

    private String getCurrentRecipeMapDisplayName() {
        RecipeMap<?> recipeMap = getRecipeMap();
        return recipeMap == null ? tr("superfactory.common.none")
            : StatCollector.translateToLocal(recipeMap.unlocalizedName);
    }

    private void clearCache(String message) {
        cachedRecipeMapNames = new ArrayList<>();
        cachedRecipeMapIndex = 0;
        cachedMachineMeta = -1;
        cachedMachineName = "";
        cachedMachineCount = 0;
        cachedParallelLimit = 1;
        lastCacheMessage = message;
        logDebug("缓存已清空: " + message);
    }

    private String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    private void logDebug(String message) {
        SuperFactory.LOG.info("[超级代理工厂] {}", message);
    }

    private String describeItemOutputs(ItemStack[] outputs) {
        if (outputs == null || outputs.length == 0) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        for (ItemStack stack : outputs) {
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(stack.stackSize)
                .append("x")
                .append(stack.getDisplayName());
        }
        return builder.length() == 0 ? "无" : builder.toString();
    }

    private String describeFluidOutputs(FluidStack[] outputs) {
        if (outputs == null || outputs.length == 0) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        for (FluidStack stack : outputs) {
            if (stack == null || stack.amount <= 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(stack.amount)
                .append("L ")
                .append(stack.getLocalizedName());
        }
        return builder.length() == 0 ? "无" : builder.toString();
    }

    private void onCasingAdded() {
        casingCount++;
    }

    private double[] inputValues() {
        return (double[]) getParametersField("iParamsIn");
    }

    private double[] outputValues() {
        return (double[]) getParametersField("iParamsOut");
    }

    private LedStatus[] inputStatuses() {
        return (LedStatus[]) getParametersField("eParamsInStatus");
    }

    private LedStatus[] outputStatuses() {
        return (LedStatus[]) getParametersField("eParamsOutStatus");
    }

    private Object getParametersField(String name) {
        try {
            Field field = parametrization.getClass()
                .getDeclaredField(name);
            field.setAccessible(true);
            return field.get(parametrization);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to access TecTech parameter field: " + name, e);
        }
    }
}
