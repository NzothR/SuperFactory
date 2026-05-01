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
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
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
import com.nzoth.superfactory.Config;
import com.nzoth.superfactory.common.recipe.ProxyRecipeConsumptionPlan;
import com.nzoth.superfactory.common.recipe.ProxyRecipeExecutionPlan;
import com.nzoth.superfactory.common.recipe.ProxyRecipeExecutor;
import com.nzoth.superfactory.common.recipe.ProxyRecipeInputGroup;
import com.nzoth.superfactory.common.recipe.ProxyRecipeInputHandler;
import com.nzoth.superfactory.common.recipe.RuntimeTransform;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.SoundResource;
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
import gregtech.api.recipe.check.SingleRecipeCheck;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.ParallelHelper;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.misc.WirelessNetworkManager;
import gregtech.common.tileentities.machines.IDualInputInventory;
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
    private static final int INDEX_INPUT_SEPARATION = 1;
    private static final int INDEX_PARALLEL = 11;
    private static final int INDEX_WIRELESS = 2;
    private static final int INDEX_ITEM_MULTIPLIER = 12;
    private static final int INDEX_FLUID_MULTIPLIER = 3;
    private static final int INDEX_ITEM_MIN = 13;
    private static final int INDEX_FLUID_MIN = 4;
    private static final int INDEX_ITEM_MAX = 14;
    private static final int INDEX_FLUID_MAX = 5;
    private static final int INDEX_MIN_TIME = 15;
    private static final int INDEX_MAX_TIME = 6;
    private static final int INDEX_MANUAL_OVERCLOCKS = 16;

    private static final int OUTPUT_DISPLAY_LINE_LIMIT = 64;
    private static final int LOCK_DETAIL_INPUT_LIMIT = 3;
    private static final int LOCK_DETAIL_OUTPUT_LIMIT = 3;
    private static final int LOCK_DISPLAY_LINE_LIMIT = 8;
    private static final int RECYCLER_OUTPUT_CHANCE = 8000;
    private int casingCount;
    private List<String> cachedRecipeMapNames = new ArrayList<>();
    private int cachedRecipeMapIndex;
    private int cachedMachineMeta = -1;
    private String cachedMachineName = "";
    private int cachedMachineCount;
    private int cachedParallelLimit = 1;
    private String lastCacheMessage = "No cached machine";
    private int lastLoopSoundTick = -20;
    private List<String> currentOutputDisplayLines = new ArrayList<>();
    private List<String> recipeLockDisplayLines = new ArrayList<>();
    private long lastWirelessCalculatedEut;
    private int lastWirelessBaseDuration;
    private boolean wirelessRecipeRunning;
    /** Snapshot used for GUI/output display only; real consumption is performed against hatch inventories. */
    private List<ItemStack> inputSnapshotItems = new ArrayList<>();
    private List<FluidStack> inputSnapshotFluids = new ArrayList<>();

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
            (base, parameter) -> switchStatus(parameter.get()));
        group0.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.lock"),
            (base, parameter) -> switchStatus(parameter.get()));

        var group1 = parametrization.getGroup(1, true);
        group1.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.input_separation"),
            (base, parameter) -> switchStatus(parameter.get()));
        group1.makeInParameter(
            1,
            1,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.parallel"),
            (base, parameter) -> parallelStatus(parameter.get()));

        var group2 = parametrization.getGroup(2, true);
        group2.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.wireless_mode"),
            (base, parameter) -> switchStatus(parameter.get()));
        group2.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.item_multiplier"),
            (base, parameter) -> optionalValueStatus(parameter.get()));

        var group3 = parametrization.getGroup(3, true);
        group3.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.fluid_multiplier"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
        group3.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.item_min_output"),
            (base, parameter) -> optionalValueStatus(parameter.get()));

        var group4 = parametrization.getGroup(4, true);
        group4.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.fluid_min_output"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
        group4.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.item_max_output"),
            (base, parameter) -> optionalValueStatus(parameter.get()));

        var group5 = parametrization.getGroup(5, true);
        group5.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.fluid_max_output"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
        group5.makeInParameter(
            1,
            1,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.min_runtime"),
            (base, parameter) -> requiredPositiveStatus(parameter.get()));
        var group6 = parametrization.getGroup(6, true);
        group6.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.max_runtime"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
        group6.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_proxy_factory.param.manual_overclocks"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
    }

    @Override
    protected void parametersStatusesWrite_EM(boolean busy) {
        sanitizeParameterRelationships();
        Arrays.fill(inputStatuses(), LedStatus.STATUS_UNUSED);
        Arrays.fill(outputStatuses(), LedStatus.STATUS_UNUSED);

        inputStatuses()[INDEX_BATCH] = switchStatus(inputValues()[INDEX_BATCH]);
        inputStatuses()[INDEX_LOCK] = switchStatus(inputValues()[INDEX_LOCK]);
        inputStatuses()[INDEX_INPUT_SEPARATION] = switchStatus(inputValues()[INDEX_INPUT_SEPARATION]);
        inputStatuses()[INDEX_PARALLEL] = parallelStatus(inputValues()[INDEX_PARALLEL]);
        inputStatuses()[INDEX_WIRELESS] = switchStatus(inputValues()[INDEX_WIRELESS]);
        inputStatuses()[INDEX_ITEM_MULTIPLIER] = isMultiplierAdjustmentEnabled()
            ? optionalValueStatus(inputValues()[INDEX_ITEM_MULTIPLIER])
            : LedStatus.STATUS_UNUSED;
        inputStatuses()[INDEX_FLUID_MULTIPLIER] = isMultiplierAdjustmentEnabled()
            ? optionalValueStatus(inputValues()[INDEX_FLUID_MULTIPLIER])
            : LedStatus.STATUS_UNUSED;
        inputStatuses()[INDEX_ITEM_MIN] = isOutputRangeAdjustmentEnabled()
            ? optionalValueStatus(inputValues()[INDEX_ITEM_MIN])
            : LedStatus.STATUS_UNUSED;
        inputStatuses()[INDEX_FLUID_MIN] = isOutputRangeAdjustmentEnabled()
            ? optionalValueStatus(inputValues()[INDEX_FLUID_MIN])
            : LedStatus.STATUS_UNUSED;
        inputStatuses()[INDEX_ITEM_MAX] = isOutputRangeAdjustmentEnabled()
            ? optionalValueStatus(inputValues()[INDEX_ITEM_MAX])
            : LedStatus.STATUS_UNUSED;
        inputStatuses()[INDEX_FLUID_MAX] = isOutputRangeAdjustmentEnabled()
            ? optionalValueStatus(inputValues()[INDEX_FLUID_MAX])
            : LedStatus.STATUS_UNUSED;
        inputStatuses()[INDEX_MAX_TIME] = isRuntimeAdjustmentEnabled()
            ? optionalValueStatus(inputValues()[INDEX_MAX_TIME])
            : LedStatus.STATUS_UNUSED;
        inputStatuses()[INDEX_MIN_TIME] = isRuntimeAdjustmentEnabled()
            ? requiredPositiveStatus(inputValues()[INDEX_MIN_TIME])
            : LedStatus.STATUS_UNUSED;
        inputStatuses()[INDEX_MANUAL_OVERCLOCKS] = optionalValueStatus(inputValues()[INDEX_MANUAL_OVERCLOCKS]);
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
        sanitizeParameterRelationships();
        setInputSeparation(isInputSeparationEnabled());
        if (!isRecipeLockEnabled() && getSingleRecipeCheck() != null) {
            setSingleRecipeCheck(null);
        }
        RecipeMap<?> recipeMap = getRecipeMap();
        if (recipeMap == null) {
            currentOutputDisplayLines = new ArrayList<>();
            return CheckRecipeResultRegistry.NO_RECIPE;
        }
        CheckRecipeResult result = checkCustomProcessing();
        if (result.wasSuccessful()) {
            rebuildCurrentOutputDisplay(mOutputItems, mOutputFluids, mMaxProgresstime);
        } else {
            currentOutputDisplayLines = new ArrayList<>();
        }
        recipeLockDisplayLines = buildRecipeLockDisplayLines();
        return result;
    }

    @Override
    protected ProcessingLogic createProcessingLogic() {
        return new ProcessingLogic() {

            @Override
            public CheckRecipeResult process() {
                sanitizeParameterRelationships();
                return checkCustomProcessing();
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
        logic.setSpecialSlotItem(getSpecialSlotTemplate());
    }

    @Override
    protected void setProcessingLogicPower(ProcessingLogic logic) {
        if (isWirelessModeEnabled()) {
            logic.setAvailableVoltage(Long.MAX_VALUE / 4L);
            logic.setAvailableAmperage(1);
            logic.setAmperageOC(false);
            logic.enablePerfectOverclock();
            logic.setUnlimitedTierSkips();
            return;
        }
        super.setProcessingLogicPower(logic);
    }

    @Override
    protected CheckRecipeResult postCheckRecipe(CheckRecipeResult result, ProcessingLogic processingLogic) {
        if (isWirelessModeEnabled()) {
            return result;
        }
        return super.postCheckRecipe(result, processingLogic);
    }

    @Override
    protected void setEnergyUsage(ProcessingLogic processingLogic) {
        if (!isWirelessModeEnabled()) {
            super.setEnergyUsage(processingLogic);
            return;
        }
        lEUt = -Math.abs(processingLogic.getCalculatedEut());
        mEUt = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, lEUt));
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
        return true;
    }

    @Override
    public boolean protectsExcessItem() {
        return super.protectsExcessItem();
    }

    @Override
    public boolean protectsExcessFluid() {
        return super.protectsExcessFluid();
    }

    @Override
    public boolean getDefaultHasMaintenanceChecks() {
        return false;
    }

    @Override
    public boolean showRecipeTextInGUI() {
        return false;
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
        maybePlayLoopingSound();
        if (wirelessRecipeRunning) {
            return true;
        }
        return super.onRunningTick(aStack);
    }

    @Override
    protected void afterRecipeCheckFailed() {
        super.afterRecipeCheckFailed();
        wirelessRecipeRunning = false;
        lastLoopSoundTick = -20;
        lEUt = 0;
        currentOutputDisplayLines = new ArrayList<>();
    }

    @Override
    public void outputAfterRecipe_EM() {
        wirelessRecipeRunning = false;
        lastLoopSoundTick = -20;
        super.outputAfterRecipe_EM();
    }

    @Override
    public void stopMachine(gregtech.api.util.shutdown.ShutDownReason reason) {
        wirelessRecipeRunning = false;
        lastLoopSoundTick = -20;
        super.stopMachine(reason);
    }

    @Override
    protected int getTimeBetweenProcessSounds() {
        return 20;
    }

    @Override
    protected SoundResource getProcessStartSound() {
        return SoundResource.GTCEU_LOOP_ASSEMBLER;
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
        screenElements.widget(
            new TextWidget(tr("superfactory.machine.super_proxy_factory.name")).setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget.dynamicString(this::getRecipeModeStatusLine)
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget.dynamicString(this::getAvailableRecipeModesLine)
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget.dynamicString(this::getParallelStatusLine)
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget.dynamicString(this::getProgressStatusLine)
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget.dynamicString(this::getEnergyStatusLine)
                .setDefaultColor(Color.WHITE.normal));
        addDynamicLines(
            screenElements,
            this::getRecipeAndOutputDisplayLines,
            LOCK_DISPLAY_LINE_LIMIT + OUTPUT_DISPLAY_LINE_LIMIT);
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
                Config.reload();
                mStructureChanged = true;
                checkMachine(getBaseMetaTileEntity(), getControllerSlot());
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
        return super.createSafeVoidButton();
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
        SingleRecipeCheck recipeCheck = getSingleRecipeCheck();
        if (isRecipeLockEnabled() && recipeCheck != null) {
            aNBT.setTag("SuperFactorySingleRecipeCheck", recipeCheck.writeToNBT());
        }
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
        if (aNBT.hasKey("SuperFactorySingleRecipeCheck", Constants.NBT.TAG_COMPOUND)) {
            setSingleRecipeCheck(
                SingleRecipeCheck.tryLoad(getRecipeMap(), aNBT.getCompoundTag("SuperFactorySingleRecipeCheck")));
        }
        recipeLockDisplayLines = buildRecipeLockDisplayLines();
    }

    private void addGuiSyncers(ModularWindow.Builder builder) {
        builder
            .widget(new FakeSyncWidget.IntegerSyncer(() -> cachedRecipeMapIndex, value -> cachedRecipeMapIndex = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> cachedMachineMeta, value -> cachedMachineMeta = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> cachedMachineCount, value -> cachedMachineCount = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> cachedParallelLimit, value -> cachedParallelLimit = value))
            .widget(new FakeSyncWidget.StringSyncer(() -> cachedMachineName, value -> cachedMachineName = value))
            .widget(new FakeSyncWidget.StringSyncer(() -> lastCacheMessage, value -> lastCacheMessage = value))
            .widget(
                new FakeSyncWidget.StringSyncer(
                    () -> serializeLines(cachedRecipeMapNames),
                    value -> cachedRecipeMapNames = deserializeLines(value)))
            .widget(
                new FakeSyncWidget.StringSyncer(
                    () -> serializeLines(currentOutputDisplayLines),
                    value -> currentOutputDisplayLines = deserializeLines(value)))
            .widget(
                new FakeSyncWidget.StringSyncer(
                    () -> serializeLines(recipeLockDisplayLines),
                    value -> recipeLockDisplayLines = deserializeLines(value)));
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
                if (recipeMap != null) {
                    recipeMaps.add(recipeMap.unlocalizedName);
                }
            }
        }
        RecipeMap<?> primary = workable.getRecipeMap();
        if (primary != null && !recipeMaps.contains(primary.unlocalizedName)) {
            recipeMaps.add(primary.unlocalizedName);
        }
        if (recipeMaps.isEmpty()) {
            clearCache(tr("superfactory.machine.super_proxy_factory.cache.cleared_no_usable_map"));
            return;
        }

        cachedRecipeMapNames = recipeMaps;
        cachedRecipeMapIndex = Math.min(cachedRecipeMapIndex, cachedRecipeMapNames.size() - 1);
        setSingleRecipeCheck(null);
        currentOutputDisplayLines = new ArrayList<>();
        recipeLockDisplayLines = buildRecipeLockDisplayLines();
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
    }

    private ItemStack[] transformItems(ItemStack[] rawOutputs) {
        if (rawOutputs == null || rawOutputs.length == 0) {
            return null;
        }
        ArrayList<ItemStack> transformed = new ArrayList<>();
        int multiplier = getItemOutputMultiplier();
        long minAmount = getItemMinimumOutput();
        long maxAmount = getItemMaximumOutput();
        for (ItemStack stack : rawOutputs) {
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            long amount = stack.stackSize;
            if (multiplier > 0) {
                amount *= multiplier;
            }
            if (minAmount > 0) {
                amount = Math.max(amount, minAmount);
            }
            if (maxAmount > 0) {
                amount = Math.min(amount, maxAmount);
            }
            ParallelHelper.addItemsLong(transformed, stack, amount);
        }
        return transformed.isEmpty() ? null : transformed.toArray(new ItemStack[0]);
    }

    private FluidStack[] transformFluids(FluidStack[] rawOutputs) {
        if (rawOutputs == null || rawOutputs.length == 0) {
            return null;
        }
        ArrayList<FluidStack> transformed = new ArrayList<>();
        int multiplier = getFluidOutputMultiplier();
        long minAmount = getFluidMinimumOutput();
        long maxAmount = getFluidMaximumOutput();
        for (FluidStack stack : rawOutputs) {
            if (stack == null || stack.amount <= 0) {
                continue;
            }
            long amount = stack.amount;
            if (multiplier > 0) {
                amount *= multiplier;
            }
            if (minAmount > 0) {
                amount = Math.max(amount, minAmount);
            }
            if (maxAmount > 0) {
                amount = Math.min(amount, maxAmount);
            }
            ParallelHelper.addFluidsLong(transformed, stack, amount);
        }
        return transformed.isEmpty() ? null : transformed.toArray(new FluidStack[0]);
    }

    private int transformDuration(int rawDuration) {
        return RuntimeTransform.clampFinalDuration(rawDuration, getMinimumRuntime(), getMaximumRuntime());
    }

    private void captureInputSnapshots() {
        inputSnapshotItems = new ArrayList<>();
        for (ItemStack stack : normalizeLiveItemRefs(getStoredInputs())) {
            ItemStack copy = GTUtility.copyOrNull(stack);
            if (copy != null && copy.stackSize > 0) {
                inputSnapshotItems.add(copy);
            }
        }
        inputSnapshotFluids = new ArrayList<>();
        for (FluidStack stack : normalizeLiveFluidRefs(getStoredFluids())) {
            if (stack != null && stack.amount > 0) {
                inputSnapshotFluids.add(stack.copy());
            }
        }
    }

    private int computeInputBoundParallel(GTRecipe recipe) {
        if (recipe == null) {
            return 0;
        }
        return ProxyRecipeInputHandler.computeInputBoundParallel(
            recipe,
            Integer.MAX_VALUE,
            inputSnapshotItems.toArray(new ItemStack[0]),
            inputSnapshotFluids.toArray(new FluidStack[0]));
    }

    private boolean matchesSpecialSlot(GTRecipe recipe, ItemStack specialSlot, ItemStack[] queryItems) {
        if (recipe == null || recipe.mSpecialItems == null) {
            return true;
        }
        if (recipe.mSpecialItems instanceof ItemStack requiredStack) {
            return matchesSpecialItem(requiredStack, specialSlot, queryItems);
        }
        if (recipe.mSpecialItems instanceof ItemStack[]requiredStacks) {
            for (ItemStack requiredStack : requiredStacks) {
                if (matchesSpecialItem(requiredStack, specialSlot, queryItems)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private boolean matchesSpecialItem(ItemStack requiredStack, ItemStack specialSlot, ItemStack[] queryItems) {
        if (requiredStack == null) {
            return false;
        }
        if (specialSlot != null && GTUtility.areStacksEqual(requiredStack, specialSlot, false)) {
            return true;
        }
        if (queryItems == null) {
            return false;
        }
        for (ItemStack queryItem : queryItems) {
            if (queryItem != null && GTUtility.areStacksEqual(requiredStack, queryItem, false)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack[] scaleItemOutputs(ItemStack[] outputs, int fromParallel, int toParallel) {
        if (outputs == null || outputs.length == 0 || fromParallel <= 0 || toParallel >= fromParallel) {
            return outputs;
        }
        ArrayList<ItemStack> scaled = new ArrayList<>();
        for (ItemStack stack : outputs) {
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            long scaledAmount = scaleLongCeil(stack.stackSize, toParallel, fromParallel);
            ParallelHelper.addItemsLong(scaled, stack, scaledAmount);
        }
        return scaled.isEmpty() ? null : scaled.toArray(new ItemStack[0]);
    }

    private FluidStack[] scaleFluidOutputs(FluidStack[] outputs, int fromParallel, int toParallel) {
        if (outputs == null || outputs.length == 0 || fromParallel <= 0 || toParallel >= fromParallel) {
            return outputs;
        }
        ArrayList<FluidStack> scaled = new ArrayList<>();
        for (FluidStack stack : outputs) {
            if (stack == null || stack.amount <= 0) {
                continue;
            }
            long scaledAmount = scaleLongCeil(stack.amount, toParallel, fromParallel);
            ParallelHelper.addFluidsLong(scaled, stack, scaledAmount);
        }
        return scaled.isEmpty() ? null : scaled.toArray(new FluidStack[0]);
    }

    private int scaleIntCeil(int value, int numerator, int denominator) {
        if (value <= 0 || numerator <= 0 || denominator <= 0) {
            return value;
        }
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, scaleLongCeil(value, numerator, denominator)));
    }

    private long scaleLongCeil(long value, int numerator, int denominator) {
        if (value <= 0L || numerator <= 0 || denominator <= 0) {
            return value;
        }
        BigInteger scaled = BigInteger.valueOf(value)
            .multiply(BigInteger.valueOf(numerator))
            .add(BigInteger.valueOf(denominator - 1L))
            .divide(BigInteger.valueOf(denominator));
        return scaled.min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    }

    private double getBatchTickLimit(double calculatedDuration) {
        return 128.0D;
    }

    private boolean shouldApplyBatchExpansion(double batchTickLimit, double calculatedDuration, int currentParallel) {
        if (!isBatchEnabled() || currentParallel <= 0) {
            return false;
        }
        if (getMaximumRuntime() > 0) {
            return false;
        }
        return calculatedDuration < batchTickLimit;
    }

    private int getEffectiveParallelLimit() {
        int cachedCap = Math.max(1, cachedParallelLimit);
        int configured = Math.max(1, clampInputInt(INDEX_PARALLEL));
        return Math.min(cachedCap, configured);
    }

    private boolean isBatchEnabled() {
        return asBoolean(inputValues()[INDEX_BATCH], true);
    }

    private boolean isRecipeLockEnabled() {
        return asBoolean(inputValues()[INDEX_LOCK], false);
    }

    private boolean isMultiplierAdjustmentEnabled() {
        return Config.enableOutputMultiplierAdjustment;
    }

    private boolean isRuntimeAdjustmentEnabled() {
        return Config.enableRuntimeAdjustment;
    }

    private boolean isOutputRangeAdjustmentEnabled() {
        return Config.enableOutputRangeAdjustment;
    }

    @Override
    public boolean isInputSeparationEnabled() {
        return asBoolean(inputValues()[INDEX_INPUT_SEPARATION], false);
    }

    private boolean isWirelessModeEnabled() {
        return asBoolean(inputValues()[INDEX_WIRELESS], false);
    }

    private int getItemOutputMultiplier() {
        if (!isMultiplierAdjustmentEnabled()) {
            return 0;
        }
        return clampInputInt(INDEX_ITEM_MULTIPLIER);
    }

    private int getFluidOutputMultiplier() {
        if (!isMultiplierAdjustmentEnabled()) {
            return 0;
        }
        return clampInputInt(INDEX_FLUID_MULTIPLIER);
    }

    private long getItemMinimumOutput() {
        if (!isOutputRangeAdjustmentEnabled()) {
            return 0;
        }
        return clampInputLong(INDEX_ITEM_MIN);
    }

    private long getFluidMinimumOutput() {
        if (!isOutputRangeAdjustmentEnabled()) {
            return 0;
        }
        return clampInputLong(INDEX_FLUID_MIN);
    }

    private long getItemMaximumOutput() {
        if (!isOutputRangeAdjustmentEnabled()) {
            return 0;
        }
        return clampInputLong(INDEX_ITEM_MAX);
    }

    private long getFluidMaximumOutput() {
        if (!isOutputRangeAdjustmentEnabled()) {
            return 0;
        }
        return clampInputLong(INDEX_FLUID_MAX);
    }

    private long getMaximumRuntime() {
        if (!isRuntimeAdjustmentEnabled()) {
            return 0;
        }
        return clampInputLong(INDEX_MAX_TIME);
    }

    private long getMinimumRuntime() {
        if (!isRuntimeAdjustmentEnabled()) {
            return 1L;
        }
        long raw = clampInputLong(INDEX_MIN_TIME);
        return Math.max(1L, raw);
    }

    private int getManualOverclocks() {
        return Math.max(0, clampInputInt(INDEX_MANUAL_OVERCLOCKS));
    }

    private String formatPowerUsageDisplay() {
        long euPerTick = mMaxProgresstime > 0 ? Math.abs(lEUt) : 0;
        if (euPerTick <= 0) {
            return "0 EU/t";
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

    private boolean asBoolean(double value, boolean defaultState) {
        if (Double.isNaN(value)) {
            return defaultState;
        }
        return Math.round(value) != 0L;
    }

    private LedStatus switchStatus(double value) {
        return Math.round(value) == 0L ? LedStatus.STATUS_NEUTRAL : LedStatus.STATUS_OK;
    }

    private LedStatus optionalValueStatus(double value) {
        return value <= 0 ? LedStatus.STATUS_NEUTRAL : LedStatus.STATUS_OK;
    }

    private LedStatus requiredPositiveStatus(double value) {
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

    private double clampParameterValue(int parameterIndex, double rawValue) {
        long rounded = Math.round(rawValue);
        if (!isMultiplierAdjustmentEnabled()
            && (parameterIndex == INDEX_ITEM_MULTIPLIER || parameterIndex == INDEX_FLUID_MULTIPLIER)) {
            return 0;
        }
        if (!isRuntimeAdjustmentEnabled() && (parameterIndex == INDEX_MIN_TIME || parameterIndex == INDEX_MAX_TIME)) {
            return parameterIndex == INDEX_MIN_TIME ? 1 : 0;
        }
        if (!isOutputRangeAdjustmentEnabled() && (parameterIndex == INDEX_ITEM_MIN || parameterIndex == INDEX_FLUID_MIN
            || parameterIndex == INDEX_ITEM_MAX
            || parameterIndex == INDEX_FLUID_MAX)) {
            return 0;
        }
        return switch (parameterIndex) {
            case INDEX_BATCH, INDEX_LOCK, INDEX_INPUT_SEPARATION, INDEX_WIRELESS -> rounded <= 0 ? 0 : 1;
            case INDEX_MIN_TIME, INDEX_PARALLEL -> Math.max(1L, Math.min(Integer.MAX_VALUE, rounded));
            case INDEX_MANUAL_OVERCLOCKS -> Math.max(0L, Math.min(64L, rounded));
            default -> rounded <= 0 ? 0 : rounded;
        };
    }

    private void sanitizeParameterRelationships() {
        double[] inputs = inputValues();
        inputs[INDEX_PARALLEL] = Math.max(1D, Math.min(Integer.MAX_VALUE, Math.round(inputs[INDEX_PARALLEL])));
        inputs[INDEX_MANUAL_OVERCLOCKS] = Math.max(0D, Math.min(64D, Math.round(inputs[INDEX_MANUAL_OVERCLOCKS])));
        if (isRuntimeAdjustmentEnabled()) {
            inputs[INDEX_MIN_TIME] = Math.max(1D, Math.round(inputs[INDEX_MIN_TIME]));
        } else {
            inputs[INDEX_MIN_TIME] = 1D;
            inputs[INDEX_MAX_TIME] = 0D;
        }
        if (isMultiplierAdjustmentEnabled()) {
            inputs[INDEX_ITEM_MULTIPLIER] = Math
                .max(0D, Math.min(Integer.MAX_VALUE, Math.round(inputs[INDEX_ITEM_MULTIPLIER])));
            inputs[INDEX_FLUID_MULTIPLIER] = Math
                .max(0D, Math.min(Integer.MAX_VALUE, Math.round(inputs[INDEX_FLUID_MULTIPLIER])));
        } else {
            inputs[INDEX_ITEM_MULTIPLIER] = 0D;
            inputs[INDEX_FLUID_MULTIPLIER] = 0D;
        }
        if (!isOutputRangeAdjustmentEnabled()) {
            inputs[INDEX_ITEM_MIN] = 0D;
            inputs[INDEX_FLUID_MIN] = 0D;
            inputs[INDEX_ITEM_MAX] = 0D;
            inputs[INDEX_FLUID_MAX] = 0D;
        }
        if (inputs[INDEX_MAX_TIME] > 0 && inputs[INDEX_MAX_TIME] < inputs[INDEX_MIN_TIME]) {
            inputs[INDEX_MAX_TIME] = inputs[INDEX_MIN_TIME];
        }
        if (inputs[INDEX_ITEM_MAX] > 0 && inputs[INDEX_ITEM_MIN] > 0
            && inputs[INDEX_ITEM_MAX] < inputs[INDEX_ITEM_MIN]) {
            inputs[INDEX_ITEM_MAX] = inputs[INDEX_ITEM_MIN];
        }
        if (inputs[INDEX_FLUID_MAX] > 0 && inputs[INDEX_FLUID_MIN] > 0
            && inputs[INDEX_FLUID_MAX] < inputs[INDEX_FLUID_MIN]) {
            inputs[INDEX_FLUID_MAX] = inputs[INDEX_FLUID_MIN];
        }
    }

    private boolean canOutputItemsSafely(ItemStack[] itemOutputs) {
        return canOutputAll(itemOutputs, null);
    }

    private boolean canOutputSafely(ItemStack[] itemOutputs, FluidStack[] fluidOutputs) {
        return canOutputAll(itemOutputs, fluidOutputs);
    }

    private void consumeRealInputs(GTRecipe recipe, int parallel, ItemStack[] itemInputs, FluidStack[] fluidInputs) {
        if (parallel <= 0) {
            return;
        }
        SingleRecipeCheck recipeCheck = isRecipeLockEnabled() ? getSingleRecipeCheck() : null;
        if (recipeCheck != null && recipeCheck.getRecipe() == recipe) {
            recipeCheck.checkRecipeInputs(true, parallel, itemInputs, fluidInputs);
            return;
        }
        recipe.consumeInput(parallel, fluidInputs, itemInputs);
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

    private ItemStack getSpecialSlotTemplate() {
        RecipeMap<?> recipeMap = getRecipeMap();
        if (recipeMap == RecipeMaps.assemblylineVisualRecipes) {
            return ItemList.Tool_DataStick.get(1L);
        }
        return null;
    }

    private String getCurrentRecipeMapDisplayName() {
        RecipeMap<?> recipeMap = getRecipeMap();
        return recipeMap == null ? tr("superfactory.common.none")
            : StatCollector.translateToLocal(recipeMap.unlocalizedName);
    }

    private String getRecipeModeStatusLine() {
        if (!mMachine) {
            return tr("superfactory.machine.super_proxy_factory.gui.recipe_mode") + ": "
                + EnumChatFormatting.RED
                + tr("superfactory.machine.super_proxy_factory.gui.structure_failed");
        }
        return tr("superfactory.machine.super_proxy_factory.gui.recipe_mode") + ": "
            + (cachedRecipeMapNames.isEmpty() ? EnumChatFormatting.RED + tr("superfactory.common.none")
                : EnumChatFormatting.GREEN + getCurrentRecipeMapDisplayName());
    }

    private String getAvailableRecipeModesLine() {
        if (cachedRecipeMapNames.isEmpty()) {
            return tr("superfactory.machine.super_proxy_factory.gui.available_modes") + ": "
                + EnumChatFormatting.RED
                + tr("superfactory.common.none");
        }
        return tr("superfactory.machine.super_proxy_factory.gui.available_modes") + ": "
            + EnumChatFormatting.AQUA
            + String.join("/", getLocalizedRecipeModeNames());
    }

    private List<String> getLocalizedRecipeModeNames() {
        ArrayList<String> names = new ArrayList<>();
        for (String recipeMapName : cachedRecipeMapNames) {
            RecipeMap<?> recipeMap = RecipeMap.ALL_RECIPE_MAPS.get(recipeMapName);
            names.add(recipeMap == null ? recipeMapName : StatCollector.translateToLocal(recipeMap.unlocalizedName));
        }
        return names;
    }

    private String getParallelStatusLine() {
        return tr("superfactory.machine.super_proxy_factory.gui.parallel") + ": "
            + EnumChatFormatting.AQUA
            + getEffectiveParallelLimit()
            + EnumChatFormatting.GRAY
            + " / "
            + cachedParallelLimit;
    }

    private String getProgressStatusLine() {
        return tr("superfactory.machine.super_proxy_factory.gui.progress") + ": "
            + EnumChatFormatting.AQUA
            + mProgresstime
            + EnumChatFormatting.GRAY
            + " / "
            + mMaxProgresstime;
    }

    private String getEnergyStatusLine() {
        return tr("superfactory.machine.super_proxy_factory.gui.eu") + ": "
            + EnumChatFormatting.RED
            + formatPowerUsageDisplay();
    }

    private List<String> buildRecipeLockDisplayLines() {
        ArrayList<String> lines = new ArrayList<>();
        if (!isRecipeLockEnabled()) {
            lines.add(
                tr("superfactory.machine.super_proxy_factory.gui.recipe_lock") + ": "
                    + EnumChatFormatting.DARK_GRAY
                    + tr("superfactory.common.off"));
            return lines;
        }
        SingleRecipeCheck recipeCheck = getSingleRecipeCheck();
        if (recipeCheck == null) {
            lines.add(
                tr("superfactory.machine.super_proxy_factory.gui.recipe_lock") + ": "
                    + EnumChatFormatting.YELLOW
                    + tr("superfactory.machine.super_proxy_factory.gui.recipe_lock_pending"));
            return lines;
        }
        lines.add(
            tr("superfactory.machine.super_proxy_factory.gui.recipe_lock") + ": "
                + EnumChatFormatting.GREEN
                + tr("superfactory.machine.super_proxy_factory.gui.recipe_lock_locked"));
        GTRecipe recipe = recipeCheck.getRecipe();
        lines.addAll(buildRecipeDetailLines(recipe));
        return lines;
    }

    private List<String> buildRecipeDetailLines(GTRecipe recipe) {
        ArrayList<String> lines = new ArrayList<>();
        lines.addAll(
            buildFoldedRecipeSection(
                tr("superfactory.machine.super_proxy_factory.gui.recipe_inputs"),
                collectRecipeEntryLines(recipe.mInputs, recipe.mFluidInputs, false),
                LOCK_DETAIL_INPUT_LIMIT));
        lines.addAll(
            buildFoldedRecipeSection(
                tr("superfactory.machine.super_proxy_factory.gui.recipe_outputs"),
                collectRecipeEntryLines(recipe.mOutputs, recipe.mFluidOutputs, true),
                LOCK_DETAIL_OUTPUT_LIMIT));
        return lines;
    }

    private List<String> buildFoldedRecipeSection(String title, List<String> entries, int limit) {
        ArrayList<String> lines = new ArrayList<>();
        if (entries.isEmpty()) {
            lines.add(EnumChatFormatting.GRAY + title + ": " + tr("superfactory.common.none"));
            return lines;
        }
        lines.add(EnumChatFormatting.WHITE + title + ":");
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            lines.add(EnumChatFormatting.GRAY + "  " + entries.get(i));
        }
        if (entries.size() > limit) {
            lines.add(
                EnumChatFormatting.DARK_GRAY + "  "
                    + tr("superfactory.machine.super_proxy_factory.gui.folded_prefix")
                    + " "
                    + (entries.size() - limit)
                    + " "
                    + tr("superfactory.machine.super_proxy_factory.gui.folded_suffix"));
        }
        return lines;
    }

    private List<String> collectRecipeEntryLines(ItemStack[] items, FluidStack[] fluids, boolean outputs) {
        ArrayList<String> lines = new ArrayList<>();
        if (items != null) {
            ArrayList<ItemStack> sortedItems = new ArrayList<>();
            for (ItemStack item : items) {
                if (item != null) {
                    sortedItems.add(item);
                }
            }
            sortedItems.sort(Comparator.comparing(ItemStack::getDisplayName, String::compareTo));
            for (ItemStack item : sortedItems) {
                lines.add(item.getDisplayName() + " x" + formatCompactAmount(item.stackSize));
            }
        }
        if (fluids != null) {
            ArrayList<FluidStack> sortedFluids = new ArrayList<>();
            for (FluidStack fluid : fluids) {
                if (fluid != null) {
                    sortedFluids.add(fluid);
                }
            }
            sortedFluids.sort(Comparator.comparing(FluidStack::getLocalizedName, String::compareTo));
            for (FluidStack fluid : sortedFluids) {
                lines.add(fluid.getLocalizedName() + " " + formatCompactAmount(fluid.amount) + "L");
            }
        }
        return lines;
    }

    private List<String> getRecipeLockDisplayLines() {
        if (recipeLockDisplayLines.isEmpty()) {
            return buildRecipeLockDisplayLines();
        }
        return recipeLockDisplayLines;
    }

    private String serializeLines(List<String> lines) {
        return String.join("\u001f", lines);
    }

    private List<String> deserializeLines(String data) {
        if (data == null || data.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(data.split("\u001f", -1)));
    }

    private List<String> getDisplayedOutputLines() {
        if (currentOutputDisplayLines.isEmpty() && (mOutputItems != null || mOutputFluids != null)) {
            return buildMergedOutputDisplayLines(mOutputItems, mOutputFluids, Math.max(1, mMaxProgresstime));
        }
        if (currentOutputDisplayLines.isEmpty()) {
            return Collections.singletonList(
                tr("superfactory.machine.super_proxy_factory.gui.output") + ": "
                    + EnumChatFormatting.DARK_GRAY
                    + tr("superfactory.machine.super_proxy_factory.gui.not_working"));
        }
        return currentOutputDisplayLines;
    }

    private List<String> getRecipeAndOutputDisplayLines() {
        ArrayList<String> lines = new ArrayList<>(getRecipeLockDisplayLines());
        lines.addAll(getDisplayedOutputLines());
        return lines;
    }

    private void rebuildCurrentOutputDisplay() {
        rebuildCurrentOutputDisplay(mOutputItems, mOutputFluids, mMaxProgresstime);
    }

    private void rebuildCurrentOutputDisplay(ItemStack[] items, FluidStack[] fluids, int durationTicks) {
        currentOutputDisplayLines = buildMergedOutputDisplayLines(items, fluids, durationTicks);
    }

    private List<String> buildMergedOutputDisplayLines(ItemStack[] items, FluidStack[] fluids, int durationTicks) {
        ArrayList<DisplayEntry> entries = new ArrayList<>();
        Map<String, DisplayEntry> itemMap = new LinkedHashMap<>();
        if (items != null) {
            for (ItemStack item : items) {
                if (item == null || item.stackSize <= 0) {
                    continue;
                }
                itemMap.computeIfAbsent(
                    "I:" + item.getDisplayName(),
                    key -> new DisplayEntry(false, item.getDisplayName())).amount += item.stackSize;
            }
        }
        Map<String, DisplayEntry> fluidMap = new LinkedHashMap<>();
        if (fluids != null) {
            for (FluidStack fluid : fluids) {
                if (fluid == null || fluid.amount <= 0) {
                    continue;
                }
                fluidMap.computeIfAbsent(
                    "F:" + fluid.getLocalizedName(),
                    key -> new DisplayEntry(true, fluid.getLocalizedName())).amount += fluid.amount;
            }
        }
        entries.addAll(itemMap.values());
        entries.addAll(fluidMap.values());
        entries.sort(
            Comparator.comparing(DisplayEntry::sortBucket)
                .thenComparing(entry -> entry.name, String::compareTo));

        ArrayList<String> lines = new ArrayList<>();
        if (entries.isEmpty()) {
            return lines;
        }
        lines.add(tr("superfactory.machine.super_proxy_factory.gui.output") + ":");
        // The GUI owns a fixed amount of synced text rows; reserve the final row for a folded-count marker.
        int visibleEntryLimit = Math.max(0, OUTPUT_DISPLAY_LINE_LIMIT - 1);
        int shownEntries = entries.size();
        boolean folded = entries.size() > visibleEntryLimit;
        if (folded) {
            shownEntries = Math.max(0, visibleEntryLimit - 1);
        }
        for (int i = 0; i < shownEntries; i++) {
            DisplayEntry entry = entries.get(i);
            String quantity = entry.fluid ? formatCompactAmount(entry.amount) + "L"
                : "x" + formatCompactAmount(entry.amount);
            lines.add(
                EnumChatFormatting.GRAY + "  "
                    + entry.name
                    + " "
                    + quantity
                    + EnumChatFormatting.DARK_GRAY
                    + " (\u2248"
                    + formatRate(entry.amount, durationTicks)
                    + (entry.fluid ? "L/s" : "/s")
                    + ")");
        }
        if (folded) {
            lines.add(
                EnumChatFormatting.DARK_GRAY + "  "
                    + tr("superfactory.machine.super_proxy_factory.gui.folded_prefix")
                    + " "
                    + (entries.size() - shownEntries)
                    + " "
                    + tr("superfactory.machine.super_proxy_factory.gui.folded_suffix"));
        }
        return lines;
    }

    private String formatRate(long amount, int durationTicks) {
        if (durationTicks <= 0 || amount <= 0) {
            return "0";
        }
        double perSecond = amount * 20.0D / durationTicks;
        return formatCompactDecimal(perSecond);
    }

    private String formatCompactAmount(long amount) {
        return formatCompactDecimal(amount);
    }

    private String formatCompactDecimal(double value) {
        if (value < 1000D) {
            if (Math.abs(value - Math.rint(value)) < 0.05D) {
                return String.valueOf((long) Math.rint(value));
            }
            return String.format(Locale.ROOT, "%.1f", value);
        }
        String[] suffixes = new String[] { "K", "M", "G", "T", "P", "E" };
        double current = value;
        int suffixIndex = -1;
        while (current >= 1000D && suffixIndex + 1 < suffixes.length) {
            current /= 1000D;
            suffixIndex++;
        }
        return String.format(Locale.ROOT, current >= 100D ? "%.0f%s" : "%.1f%s", current, suffixes[suffixIndex]);
    }

    private void addDynamicLines(DynamicPositionedColumn screenElements,
        java.util.function.Supplier<List<String>> linesSupplier, int maxLines) {
        for (int i = 0; i < maxLines; i++) {
            final int lineIndex = i;
            screenElements.widget(TextWidget.dynamicString(() -> {
                List<String> lines = linesSupplier.get();
                return lineIndex < lines.size() ? lines.get(lineIndex) : "";
            })
                .setDefaultColor(Color.WHITE.normal));
        }
    }

    private void clearCache(String message) {
        cachedRecipeMapNames = new ArrayList<>();
        cachedRecipeMapIndex = 0;
        cachedMachineMeta = -1;
        cachedMachineName = "";
        cachedMachineCount = 0;
        cachedParallelLimit = 1;
        lastCacheMessage = message;
    }

    private void maybePlayLoopingSound() {
        if (mMaxProgresstime <= 0) {
            return;
        }
        if (mProgresstime == 0 || mProgresstime - lastLoopSoundTick >= 20) {
            playLoopingSound();
            lastLoopSoundTick = mProgresstime;
        }
    }

    private void playLoopingSound() {
        IGregTechTileEntity baseMetaTileEntity = getBaseMetaTileEntity();
        if (baseMetaTileEntity == null || !baseMetaTileEntity.isServerSide()) {
            return;
        }
        ResourceLocation sound = SoundResource.GTCEU_LOOP_ASSEMBLER.resourceLocation;
        GTUtility.doSoundAtClient(
            sound,
            16,
            1.0F,
            1.0F,
            (double) baseMetaTileEntity.getXCoord(),
            (double) baseMetaTileEntity.getYCoord(),
            (double) baseMetaTileEntity.getZCoord());
    }

    private CheckRecipeResult checkWirelessProcessing() {
        return checkCustomProcessing();
    }

    private CheckRecipeResult checkCustomProcessing() {
        if (getRecipeMap() == RecipeMaps.recyclerRecipes) {
            return checkCustomRecyclerRecipe();
        }
        return checkCustomStandardRecipe();
    }

    private CheckRecipeResult checkCustomStandardRecipe() {
        RecipeMap<?> recipeMap = getRecipeMap();
        if (recipeMap == null) {
            resetPendingRecipeState();
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        CheckRecipeResult result = CheckRecipeResultRegistry.NO_RECIPE;
        CheckRecipeResult dualInputResult = checkDualInputPatternHatches(recipeMap);
        if (dualInputResult.wasSuccessful()) {
            return dualInputResult;
        }
        if (dualInputResult != CheckRecipeResultRegistry.NO_RECIPE) {
            result = dualInputResult;
        }
        for (ProxyRecipeInputGroup inputGroup : ProxyRecipeInputHandler
            .collectInputGroups(mInputBusses, mInputHatches, isInputSeparationEnabled())) {
            if (inputGroup.isEmpty()) {
                continue;
            }
            CheckRecipeResult foundResult = tryStartRecipeForInputs(recipeMap, inputGroup);
            if (foundResult.wasSuccessful()) {
                return foundResult;
            }
            if (foundResult != CheckRecipeResultRegistry.NO_RECIPE) {
                result = foundResult;
            }
        }

        resetPendingRecipeState();
        return result;
    }

    private CheckRecipeResult checkDualInputPatternHatches(RecipeMap<?> recipeMap) {
        if (mDualInputHatches.isEmpty()) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }
        CheckRecipeResult result = CheckRecipeResultRegistry.NO_RECIPE;
        for (var dualInputHatch : mDualInputHatches) {
            if (dualInputHatch == null) {
                continue;
            }
            ItemStack[] sharedItems = dualInputHatch.getSharedItems();
            for (Iterator<? extends IDualInputInventory> iterator = dualInputHatch.inventories(); iterator.hasNext();) {
                IDualInputInventory slot = iterator.next();
                if (slot == null || slot.isEmpty()) {
                    continue;
                }
                ItemStack[] slotItems = slot.getItemInputs();
                FluidStack[] slotFluids = slot.getFluidInputs();
                ItemStack[] liveItems = mergeItemInputs(sharedItems, slotItems);
                FluidStack[] liveFluids = slotFluids == null ? GTValues.emptyFluidStackArray : slotFluids;
                ProxyRecipeInputGroup inputGroup = new ProxyRecipeInputGroup(
                    (byte) -1,
                    liveItems,
                    liveFluids,
                    buildQueryItems(liveItems),
                    buildQueryFluids(liveFluids));
                CheckRecipeResult foundResult = tryStartRecipeForInputs(recipeMap, inputGroup);
                if (foundResult.wasSuccessful()) {
                    return foundResult;
                }
                if (foundResult != CheckRecipeResultRegistry.NO_RECIPE) {
                    result = foundResult;
                }
            }
        }
        return result;
    }

    private ItemStack[] mergeItemInputs(ItemStack[] sharedItems, ItemStack[] slotItems) {
        ArrayList<ItemStack> merged = new ArrayList<>();
        if (sharedItems != null) {
            for (ItemStack stack : sharedItems) {
                addDualInputStack(merged, stack);
            }
        }
        if (slotItems != null) {
            for (ItemStack stack : slotItems) {
                addDualInputStack(merged, stack);
            }
        }
        return merged.isEmpty() ? GTValues.emptyItemStackArray : merged.toArray(new ItemStack[0]);
    }

    private void addDualInputStack(List<ItemStack> merged, ItemStack stack) {
        if (stack == null) {
            return;
        }
        ItemStack programmedCircuit = unwrapProgrammableHatchesCircuit(stack);
        if (programmedCircuit != null) {
            merged.add(copyAsQueryMarker(programmedCircuit));
            return;
        }
        if (isIntegratedCircuitStack(stack)) {
            merged.add(copyAsQueryMarker(stack));
            return;
        }
        if (stack.stackSize > 0) {
            merged.add(stack);
        }
    }

    private ItemStack unwrapProgrammableHatchesCircuit(ItemStack stack) {
        if (stack == null || stack.getItem() == null
            || !"reobf.proghatches.item.ItemProgrammingCircuit".equals(
                stack.getItem()
                    .getClass()
                    .getName())) {
            return null;
        }
        try {
            Method method = stack.getItem()
                .getClass()
                .getMethod("getCircuit", ItemStack.class);
            Object result = method.invoke(null, stack);
            if (result instanceof java.util.Optional<?>) {
                java.util.Optional<?> optional = (java.util.Optional<?>) result;
                Object value = optional.orElse(null);
                if (value instanceof ItemStack) {
                    return (ItemStack) value;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional compatibility path: the mod may not be installed or may change this helper.
        }
        return null;
    }

    private ItemStack copyAsQueryMarker(ItemStack stack) {
        ItemStack copy = GTUtility.copyOrNull(stack);
        if (copy != null) {
            copy.stackSize = 1;
        }
        return copy;
    }

    private boolean isIntegratedCircuitStack(ItemStack stack) {
        ItemStack integratedCircuit = GTUtility.getIntegratedCircuit(0);
        return stack != null && integratedCircuit != null && stack.getItem() == integratedCircuit.getItem();
    }

    private CheckRecipeResult tryStartRecipeForInputs(RecipeMap<?> recipeMap, ProxyRecipeInputGroup inputGroup) {
        if (inputGroup.liveItems.length == 0 && inputGroup.liveFluids.length == 0) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }
        ItemStack[] queryItems = inputGroup.queryItems == null ? GTValues.emptyItemStackArray : inputGroup.queryItems;
        FluidStack[] queryFluids = inputGroup.queryFluids == null ? GTValues.emptyFluidStackArray
            : inputGroup.queryFluids;

        SingleRecipeCheck recipeCheck = isRecipeLockEnabled() ? getSingleRecipeCheck() : null;
        if (recipeCheck != null) {
            if (!recipeMap.unlocalizedName.equals(recipeCheck.getRecipeMap().unlocalizedName)) {
                setSingleRecipeCheck(null);
                recipeLockDisplayLines = buildRecipeLockDisplayLines();
                return CheckRecipeResultRegistry.NO_RECIPE;
            }
            return tryExecuteRecipe(recipeMap, inputGroup, recipeCheck.getRecipe(), recipeCheck);
        }

        CheckRecipeResult lastFailure = CheckRecipeResultRegistry.NO_RECIPE;
        Set<GTRecipe> seenRecipes = Collections.newSetFromMap(new IdentityHashMap<>());

        for (GTRecipe recipe : recipeMap.getAllRecipes()) {
            if (recipe == null || recipe.mFakeRecipe || !recipe.mEnabled || !seenRecipes.add(recipe)) {
                continue;
            }
            CheckRecipeResult foundResult = tryExecuteRecipe(recipeMap, inputGroup, recipe, null);
            if (foundResult.wasSuccessful()) {
                return foundResult;
            }
            if (foundResult != CheckRecipeResultRegistry.NO_RECIPE) {
                lastFailure = foundResult;
            }
        }
        return lastFailure;
    }

    private CheckRecipeResult tryExecuteRecipe(RecipeMap<?> recipeMap, ProxyRecipeInputGroup inputGroup,
        GTRecipe recipe, SingleRecipeCheck recipeCheck) {
        ItemStack[] liveItemArray = inputGroup.liveItems;
        FluidStack[] liveFluidArray = inputGroup.liveFluids;
        ItemStack[] queryItems = inputGroup.queryItems == null ? GTValues.emptyItemStackArray : inputGroup.queryItems;
        FluidStack[] queryFluids = inputGroup.queryFluids == null ? GTValues.emptyFluidStackArray
            : inputGroup.queryFluids;
        if (!matchesSpecialSlot(recipe, getSpecialSlotTemplate(), queryItems)
            || !ProxyRecipeInputHandler.matchesRecipeInputs(recipe, queryItems, queryFluids)) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        int totalInputBound = recipeCheck != null
            ? recipeCheck.checkRecipeInputs(false, Integer.MAX_VALUE, liveItemArray, liveFluidArray)
            : ProxyRecipeInputHandler
                .computeConsumableInputBoundParallel(recipe, Integer.MAX_VALUE, liveItemArray, liveFluidArray);
        int inputBound = Math.min(getEffectiveParallelLimit(), totalInputBound);
        if (inputBound <= 0) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        int actualParallel = computePowerBoundParallel(recipe, inputBound);
        if (actualParallel <= 0) {
            return CheckRecipeResultRegistry.insufficientPower(recipe.mEUt);
        }

        int executionInputBound = ProxyRecipeInputHandler.hasConsumableInputs(recipe) ? totalInputBound : inputBound;
        ProxyRecipeExecutionPlan plan = buildExecutionPlan(recipe, actualParallel, executionInputBound);
        if (plan == null) {
            return CheckRecipeResultRegistry.INTERNAL_ERROR;
        }
        if (!canSupplyExecutionPlan(plan)) {
            return CheckRecipeResultRegistry.insufficientPower(plan.euPerTick);
        }
        if (!canOutputSafely(plan.outputItems, plan.outputFluids)) {
            return plan.outputItems != null && plan.outputItems.length > 0 && !canOutputItemsSafely(plan.outputItems)
                ? CheckRecipeResultRegistry.ITEM_OUTPUT_FULL
                : CheckRecipeResultRegistry.FLUID_OUTPUT_FULL;
        }

        ProxyRecipeConsumptionPlan consumptionPlan = ProxyRecipeInputHandler
            .buildConsumptionPlan(recipe, plan.actualParallel);
        if (!ProxyRecipeInputHandler.canSatisfyConsumptionPlan(consumptionPlan, liveItemArray, liveFluidArray)) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        CheckRecipeResult powerResult = prepareEnergyReservation(plan);
        if (!powerResult.wasSuccessful()) {
            return powerResult;
        }

        ItemStack[] lockItemSnapshot = null;
        FluidStack[] lockFluidSnapshot = null;
        if (isRecipeLockEnabled() && recipeCheck == null) {
            lockItemSnapshot = copyItems(liveItemArray);
            lockFluidSnapshot = copyFluids(liveFluidArray);
        }
        if (!consumeInputGroup(consumptionPlan, liveItemArray, liveFluidArray)) {
            refundEnergyReservation(plan);
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        if (lockItemSnapshot != null && lockFluidSnapshot != null) {
            // The lock stores only real consumed costs; marker and NC items are still validated by query matching.
            setSingleRecipeCheck(buildSingleRecipeCheck(recipeMap, recipe, lockItemSnapshot, lockFluidSnapshot));
            recipeLockDisplayLines = buildRecipeLockDisplayLines();
        }
        applyExecutionPlan(plan);
        updateSlots();
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    private SingleRecipeCheck buildSingleRecipeCheck(RecipeMap<?> recipeMap, GTRecipe recipe, ItemStack[] itemInputs,
        FluidStack[] fluidInputs) {
        SingleRecipeCheck.Builder builder = SingleRecipeCheck.builder(recipeMap)
            .setBefore(itemInputs, fluidInputs);
        ProxyRecipeConsumptionPlan singleCraftPlan = ProxyRecipeInputHandler.buildConsumptionPlan(recipe, 1);
        consumeInputGroup(singleCraftPlan, itemInputs, fluidInputs);
        return builder.setAfter(itemInputs, fluidInputs)
            .setRecipe(recipe)
            .build();
    }

    private ItemStack[] copyItems(ItemStack[] itemInputs) {
        if (itemInputs == null || itemInputs.length == 0) {
            return GTValues.emptyItemStackArray;
        }
        ItemStack[] copied = new ItemStack[itemInputs.length];
        for (int i = 0; i < itemInputs.length; i++) {
            copied[i] = GTUtility.copyOrNull(itemInputs[i]);
        }
        return copied;
    }

    private FluidStack[] copyFluids(FluidStack[] fluidInputs) {
        if (fluidInputs == null || fluidInputs.length == 0) {
            return GTValues.emptyFluidStackArray;
        }
        FluidStack[] copied = new FluidStack[fluidInputs.length];
        for (int i = 0; i < fluidInputs.length; i++) {
            copied[i] = fluidInputs[i] == null ? null : fluidInputs[i].copy();
        }
        return copied;
    }

    private boolean consumeInputGroup(ProxyRecipeConsumptionPlan consumptionPlan, ItemStack[] itemInputs,
        FluidStack[] fluidInputs) {
        if (!ProxyRecipeInputHandler.canSatisfyConsumptionPlan(consumptionPlan, itemInputs, fluidInputs)) {
            return false;
        }
        consumeItemDemands(consumptionPlan, itemInputs);
        consumeFluidDemands(consumptionPlan, fluidInputs);
        return true;
    }

    private void consumeItemDemands(ProxyRecipeConsumptionPlan consumptionPlan, ItemStack[] itemInputs) {
        for (ProxyRecipeConsumptionPlan.ItemDemand demand : consumptionPlan.itemDemands) {
            long remaining = demand.amount;
            for (ItemStack available : itemInputs) {
                if (available == null || available.stackSize <= 0
                    || !ProxyRecipeInputHandler.matchesItemDemand(consumptionPlan, demand, available)) {
                    continue;
                }
                int consumed = (int) Math.min(remaining, available.stackSize);
                available.stackSize -= consumed;
                remaining -= consumed;
                if (remaining <= 0L) {
                    break;
                }
            }
        }
    }

    private void consumeFluidDemands(ProxyRecipeConsumptionPlan consumptionPlan, FluidStack[] fluidInputs) {
        for (ProxyRecipeConsumptionPlan.FluidDemand demand : consumptionPlan.fluidDemands) {
            long remaining = demand.amount;
            for (FluidStack available : fluidInputs) {
                if (available == null || available.amount <= 0
                    || !ProxyRecipeInputHandler.matchesFluidDemand(demand, available)) {
                    continue;
                }
                int consumed = (int) Math.min(remaining, available.amount);
                available.amount -= consumed;
                remaining -= consumed;
                if (remaining <= 0L) {
                    break;
                }
            }
        }
    }

    private ProxyRecipeExecutionPlan buildExecutionPlan(GTRecipe recipe, int actualParallel, int inputBound) {
        ProxyRecipeExecutor.Settings settings = new ProxyRecipeExecutor.Settings();
        settings.recipe = recipe;
        settings.baseParallel = actualParallel;
        settings.inputBound = inputBound;
        settings.batchEnabled = isBatchEnabled();
        settings.maxBatchSize = getMaxBatchSize();
        settings.wirelessMode = isWirelessModeEnabled();
        settings.availablePower = getAvailableProcessingPower();
        boolean hasConsumableInputs = ProxyRecipeInputHandler.hasConsumableInputs(recipe);
        settings.minimumRuntime = getMinimumRuntime();
        settings.maximumRuntime = getMaximumRuntime();
        settings.enableHeatOverclocking = getRecipeMap() == RecipeMaps.blastFurnaceRecipes;
        settings.disableOverclocking = !hasConsumableInputs;
        settings.manualOverclocks = getManualOverclocks();
        settings.itemTransformer = this::transformItems;
        settings.fluidTransformer = this::transformFluids;

        ProxyRecipeExecutionPlan plan = ProxyRecipeExecutor.buildPlan(settings);
        if (plan == null) {
            return null;
        }

        return plan;
    }

    private CheckRecipeResult prepareEnergyReservation(ProxyRecipeExecutionPlan plan) {
        return prepareEnergyReservation(plan == null ? 0L : plan.totalEnergy);
    }

    private CheckRecipeResult prepareEnergyReservation(long totalEnergy) {
        if (!isWirelessModeEnabled()) {
            return CheckRecipeResultRegistry.SUCCESSFUL;
        }
        IGregTechTileEntity baseMetaTileEntity = getBaseMetaTileEntity();
        if (baseMetaTileEntity == null) {
            return CheckRecipeResultRegistry.INTERNAL_ERROR;
        }
        WirelessNetworkManager.strongCheckOrAddUser(baseMetaTileEntity.getOwnerUuid());
        UUID ownerUuid = WirelessNetworkManager.processInitialSettings(baseMetaTileEntity);
        BigInteger totalCost = BigInteger.valueOf(Math.max(0L, totalEnergy));
        if (totalCost.signum() > 0 && !WirelessNetworkManager.addEUToGlobalEnergyMap(ownerUuid, totalCost.negate())) {
            long insufficient = totalCost.min(BigInteger.valueOf(Long.MAX_VALUE))
                .longValue();
            return CheckRecipeResultRegistry.insufficientPower(insufficient);
        }
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    private void applyExecutionPlan(ProxyRecipeExecutionPlan plan) {
        lastWirelessCalculatedEut = Math.max(0L, plan.euPerTick);
        lastWirelessBaseDuration = Math.max(1, plan.rawDuration);
        mOutputItems = plan.outputItems;
        mOutputFluids = plan.outputFluids;
        mMaxProgresstime = Math.max(1, plan.transformedDuration);
        mProgresstime = 0;
        mEfficiency = 10000;
        mEfficiencyIncrease = 10000;
        wirelessRecipeRunning = isWirelessModeEnabled();
        lEUt = -Math.abs(plan.euPerTick);
        mEUt = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, lEUt));
    }

    private void resetPendingRecipeState() {
        wirelessRecipeRunning = false;
        lastWirelessCalculatedEut = 0L;
        lastWirelessBaseDuration = 0;
        lEUt = 0L;
        mEUt = 0;
        mOutputItems = null;
        mOutputFluids = null;
        mMaxProgresstime = 0;
        mProgresstime = 0;
        mEfficiencyIncrease = 0;
    }

    private void refundEnergyReservation(ProxyRecipeExecutionPlan plan) {
        refundEnergyReservation(plan == null ? 0L : plan.totalEnergy);
    }

    private void refundEnergyReservation(long totalEnergy) {
        if (!isWirelessModeEnabled() || totalEnergy <= 0L) {
            return;
        }
        IGregTechTileEntity baseMetaTileEntity = getBaseMetaTileEntity();
        if (baseMetaTileEntity == null) {
            return;
        }
        UUID ownerUuid = WirelessNetworkManager.processInitialSettings(baseMetaTileEntity);
        WirelessNetworkManager.addEUToGlobalEnergyMap(ownerUuid, BigInteger.valueOf(totalEnergy));
    }

    private CheckRecipeResult checkCustomRecyclerRecipe() {
        ItemStack[] liveItemArray = normalizeLiveItemRefs(getStoredInputsForColor(java.util.Optional.empty()));
        if (liveItemArray.length == 0) {
            resetPendingRecipeState();
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        ItemStack recyclerTemplate = null;
        int availableInputs = 0;
        for (ItemStack stack : liveItemArray) {
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            ItemStack recyclerOutput = GTModHandler.getRecyclerOutput(stack, 0);
            if (recyclerOutput == null) {
                continue;
            }
            if (recyclerTemplate == null) {
                recyclerTemplate = recyclerOutput.copy();
            }
            availableInputs += stack.stackSize;
        }
        if (recyclerTemplate == null || availableInputs <= 0) {
            resetPendingRecipeState();
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        int currentParallel = Math.min(availableInputs, getEffectiveParallelLimit());
        if (currentParallel <= 0) {
            resetPendingRecipeState();
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        long scrapMultiplier = ParallelHelper
            .calculateIntegralChancedOutputMultiplier(RECYCLER_OUTPUT_CHANCE, currentParallel);
        ArrayList<ItemStack> recyclerOutputs = new ArrayList<>();
        ParallelHelper.addItemsLong(recyclerOutputs, recyclerTemplate, recyclerTemplate.stackSize * scrapMultiplier);
        ItemStack[] transformedItems = transformItems(recyclerOutputs.toArray(new ItemStack[0]));
        if (transformedItems != null && transformedItems.length > 0 && !canOutputItemsSafely(transformedItems)) {
            return CheckRecipeResultRegistry.ITEM_OUTPUT_FULL;
        }

        int remaining = currentParallel;
        for (ItemStack stack : liveItemArray) {
            if (stack == null || stack.stackSize <= 0 || GTModHandler.getRecyclerOutput(stack, 0) == null) {
                continue;
            }
            int consumed = Math.min(stack.stackSize, remaining);
            stack.stackSize -= consumed;
            remaining -= consumed;
            if (remaining <= 0) {
                break;
            }
        }

        mOutputItems = transformedItems;
        mOutputFluids = null;
        mMaxProgresstime = transformDuration(45);
        mProgresstime = 0;
        mEfficiency = 10000;
        mEfficiencyIncrease = 10000;
        wirelessRecipeRunning = false;
        lEUt = -1L;
        mEUt = -1;
        updateSlots();
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    private int computePowerBoundParallel(GTRecipe recipe, int upperBound) {
        if (upperBound <= 0) {
            return 0;
        }
        if (isWirelessModeEnabled()) {
            return upperBound;
        }
        long availablePower = getAvailableProcessingPower();
        long perCraftEut = Math.max(1L, Math.abs((long) recipe.mEUt));
        if (availablePower <= 0L || perCraftEut <= 0L) {
            return 0;
        }
        long byPower = availablePower / perCraftEut;
        if (byPower <= 0L) {
            return 0;
        }
        return (int) Math.min(upperBound, Math.min(Integer.MAX_VALUE, byPower));
    }

    private boolean canSupplyExecutionPlan(ProxyRecipeExecutionPlan plan) {
        return plan != null && (isWirelessModeEnabled() || plan.euPerTick <= getAvailableProcessingPower());
    }

    private long getAvailableProcessingPower() {
        return Math.max(0L, getMaxInputEu());
    }

    private long computeRecipeTotalEnergy(GTRecipe recipe) {
        long eut = Math.max(1L, Math.abs((long) recipe.mEUt));
        long duration = Math.max(1L, recipe.mDuration);
        return safeMultiply(eut, duration);
    }

    private long safeMultiply(long left, int right) {
        if (left <= 0L || right <= 0) {
            return 0L;
        }
        BigInteger multiplied = BigInteger.valueOf(left)
            .multiply(BigInteger.valueOf(right));
        return multiplied.min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    }

    private long safeMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        BigInteger multiplied = BigInteger.valueOf(left)
            .multiply(BigInteger.valueOf(right));
        return multiplied.min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    }

    private long divideCeil(long numerator, int denominator) {
        if (numerator <= 0L) {
            return 0L;
        }
        if (denominator <= 1) {
            return numerator;
        }
        return BigInteger.valueOf(numerator)
            .add(BigInteger.valueOf(denominator - 1L))
            .divide(BigInteger.valueOf(denominator))
            .min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    }

    private String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    private ItemStack[] normalizeLiveItemRefs(List<ItemStack> liveItems) {
        if (liveItems == null || liveItems.isEmpty()) {
            return GTValues.emptyItemStackArray;
        }
        ArrayList<ItemStack> normalized = new ArrayList<>();
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ItemStack stack : liveItems) {
            if (stack == null || stack.stackSize <= 0 || !seen.add(stack)) {
                continue;
            }
            normalized.add(stack);
        }
        return normalized.toArray(new ItemStack[0]);
    }

    private FluidStack[] normalizeLiveFluidRefs(List<FluidStack> liveFluids) {
        if (liveFluids == null || liveFluids.isEmpty()) {
            return GTValues.emptyFluidStackArray;
        }
        ArrayList<FluidStack> normalized = new ArrayList<>();
        Set<FluidStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FluidStack stack : liveFluids) {
            if (stack == null || stack.amount <= 0 || !seen.add(stack)) {
                continue;
            }
            normalized.add(stack);
        }
        return normalized.toArray(new FluidStack[0]);
    }

    private ItemStack[] buildQueryItems(ItemStack[] liveItemArray) {
        if (liveItemArray == null || liveItemArray.length == 0) {
            return GTValues.emptyItemStackArray;
        }
        ArrayList<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : liveItemArray) {
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            ItemStack copy = GTUtility.copyOrNull(stack);
            if (copy == null || copy.stackSize <= 0) {
                continue;
            }
            boolean mergedExisting = false;
            for (ItemStack existing : merged) {
                if (GTUtility.areStacksEqual(existing, copy, false)) {
                    existing.stackSize += copy.stackSize;
                    mergedExisting = true;
                    break;
                }
            }
            if (!mergedExisting) {
                merged.add(copy);
            }
        }
        return merged.isEmpty() ? GTValues.emptyItemStackArray : merged.toArray(new ItemStack[0]);
    }

    private FluidStack[] buildQueryFluids(FluidStack[] liveFluidArray) {
        if (liveFluidArray == null || liveFluidArray.length == 0) {
            return GTValues.emptyFluidStackArray;
        }
        ArrayList<FluidStack> merged = new ArrayList<>();
        for (FluidStack stack : liveFluidArray) {
            if (stack == null || stack.amount <= 0) {
                continue;
            }
            FluidStack copy = stack.copy();
            boolean mergedExisting = false;
            for (FluidStack existing : merged) {
                if (GTUtility.areFluidsEqual(existing, copy)) {
                    existing.amount += copy.amount;
                    mergedExisting = true;
                    break;
                }
            }
            if (!mergedExisting) {
                merged.add(copy);
            }
        }
        return merged.isEmpty() ? GTValues.emptyFluidStackArray : merged.toArray(new FluidStack[0]);
    }

    private static final class DisplayEntry {

        private final boolean fluid;
        private final String name;
        private long amount;

        private DisplayEntry(boolean fluid, String name) {
            this.fluid = fluid;
            this.name = name;
        }

        private int sortBucket() {
            return fluid ? 1 : 0;
        }
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
