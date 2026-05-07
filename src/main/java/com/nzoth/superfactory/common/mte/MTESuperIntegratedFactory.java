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

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
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
import com.gtnewhorizons.modularui.api.math.Pos2d;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.common.internal.network.NetworkUtils;
import com.gtnewhorizons.modularui.common.widget.ButtonWidget;
import com.gtnewhorizons.modularui.common.widget.DynamicPositionedColumn;
import com.gtnewhorizons.modularui.common.widget.FakeSyncWidget;
import com.gtnewhorizons.modularui.common.widget.SlotWidget;
import com.gtnewhorizons.modularui.common.widget.TextWidget;
import com.gtnewhorizons.modularui.common.widget.textfield.TextFieldWidget;
import com.nzoth.superfactory.Config;
import com.nzoth.superfactory.SuperFactory;
import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.ui.canvas.CanvasWidget;
import com.nzoth.superfactory.common.ui.widget.RecipePatternSlotWidget;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.Textures;
import gregtech.api.gui.modularui.GTUITextures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.logic.ProcessingLogic;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import tectech.thing.gui.TecTechUITextures;
import tectech.thing.metaTileEntity.multi.base.LedStatus;
import tectech.thing.metaTileEntity.multi.base.TTMultiblockBase;

public class MTESuperIntegratedFactory extends TTMultiblockBase implements ISurvivalConstructable {

    /*
     * Structure notes:
     * The first implementation deliberately matches the Super Proxy Factory shell: a 3x3x3 hollow cube with the
     * controller on the front center. Keep the shape small and isolated here because the integrated factory will likely
     * grow new host-machine or process-module slots later.
     */
    private static final String STRUCTURE_PIECE_MAIN = "main";
    private static final int OFFSET_H = 1;
    private static final int OFFSET_V = 1;
    private static final int OFFSET_D = 0;
    private static final int CASING_META = 0;
    private static final int CASING_INDEX = GTUtility.getCasingTextureIndex(GregTechAPI.sBlockCasings2, CASING_META);
    private static final int PROCESS_WINDOW_WIDTH = 320;
    private static final int PROCESS_WINDOW_HEIGHT = 220;
    private static final int PROCESS_TOOLBAR_X = PROCESS_WINDOW_WIDTH - 24;
    private static final int PATTERN_VISIBLE_SLOTS = 12;
    private static final int ACTION_AMOUNT_APPLY = 5;

    /*
     * Shape legend after transpose:
     * '~' is the controller position, 'B' is the hollow center air block, and 'C' is any casing or allowed hatch.
     */
    private static final String[][] STRUCTURE = new String[][] { { "CCC", "CCC", "CCC" }, { "C~C", "CBC", "CCC" },
        { "CCC", "CCC", "CCC" } };
    private static final IStructureDefinition<MTESuperIntegratedFactory> STRUCTURE_DEFINITION = StructureDefinition
        .<MTESuperIntegratedFactory>builder()
        .addShape(STRUCTURE_PIECE_MAIN, transpose(STRUCTURE))
        .addElement('B', isAir())
        .addElement(
            'C',
            buildHatchAdder(MTESuperIntegratedFactory.class)
                .atLeast(InputBus, InputHatch, OutputBus, OutputHatch, Maintenance, Energy.or(ExoticEnergy))
                .casingIndex(CASING_INDEX)
                .dot(1)
                .buildAndChain(
                    onElementPass(
                        MTESuperIntegratedFactory::onCasingAdded,
                        ofBlock(GregTechAPI.sBlockCasings2, CASING_META))))
        .build();

    /*
     * Parameter layout intentionally mirrors Super Proxy Factory. The process executor is not implemented yet, but
     * preserving the same input slots lets existing Parametrizer cards and future shared logic use the same semantics.
     */
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

    private int casingCount;
    private int currentProcessStep;
    private int totalProcessSteps;
    private com.gtnewhorizons.modularui.api.forge.ItemStackHandler amountEditHandler;
    private int amountEditSlot = -1;
    private final ProcessGraph processGraph = new ProcessGraph();
    private static MTESuperIntegratedFactory clientEditingFactory;

    public MTESuperIntegratedFactory(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTESuperIntegratedFactory(String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTESuperIntegratedFactory(this.mName);
    }

    @Override
    public IStructureDefinition<MTESuperIntegratedFactory> getStructure_EM() {
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
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.batch"),
            (base, parameter) -> switchStatus(parameter.get()));
        group0.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.lock"),
            (base, parameter) -> switchStatus(parameter.get()));

        var group1 = parametrization.getGroup(1, true);
        group1.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.input_separation"),
            (base, parameter) -> switchStatus(parameter.get()));
        group1.makeInParameter(
            1,
            1,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.parallel"),
            (base, parameter) -> parallelStatus(parameter.get()));

        var group2 = parametrization.getGroup(2, true);
        group2.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.wireless_mode"),
            (base, parameter) -> switchStatus(parameter.get()));
        group2.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.item_multiplier"),
            (base, parameter) -> optionalValueStatus(parameter.get()));

        var group3 = parametrization.getGroup(3, true);
        group3.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.fluid_multiplier"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
        group3.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.item_min_output"),
            (base, parameter) -> optionalValueStatus(parameter.get()));

        var group4 = parametrization.getGroup(4, true);
        group4.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.fluid_min_output"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
        group4.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.item_max_output"),
            (base, parameter) -> optionalValueStatus(parameter.get()));

        var group5 = parametrization.getGroup(5, true);
        group5.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.fluid_max_output"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
        group5.makeInParameter(
            1,
            1,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.min_runtime"),
            (base, parameter) -> requiredPositiveStatus(parameter.get()));

        var group6 = parametrization.getGroup(6, true);
        group6.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.max_runtime"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
        group6.makeInParameter(
            1,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.manual_overclocks"),
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
        return CheckRecipeResultRegistry.NO_RECIPE;
    }

    @Override
    protected ProcessingLogic createProcessingLogic() {
        return new ProcessingLogic() {

            @Override
            public CheckRecipeResult process() {
                sanitizeParameterRelationships();
                return CheckRecipeResultRegistry.NO_RECIPE;
            }
        };
    }

    @Override
    public boolean canUseControllerSlotForRecipe() {
        return false;
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
            .addMachineType(tr("superfactory.machine.super_integrated_factory.tooltip.type"))
            .addInfo(tr("superfactory.machine.super_integrated_factory.tooltip.1"))
            .addInfo(tr("superfactory.machine.super_integrated_factory.tooltip.2"))
            .beginStructureBlock(3, 3, 3, false)
            .addController(tr("superfactory.machine.super_integrated_factory.tooltip.controller"))
            .addInputBus(tr("superfactory.machine.super_integrated_factory.tooltip.any_casing"), 1)
            .addInputHatch(tr("superfactory.machine.super_integrated_factory.tooltip.any_casing"), 1)
            .addOutputBus(tr("superfactory.machine.super_integrated_factory.tooltip.any_casing"), 1)
            .addOutputHatch(tr("superfactory.machine.super_integrated_factory.tooltip.any_casing"), 1)
            .addEnergyHatch(tr("superfactory.machine.super_integrated_factory.tooltip.any_casing"), 1)
            .toolTipFinisher();
    }

    @Override
    protected void drawTexts(DynamicPositionedColumn screenElements, SlotWidget inventorySlot) {
        screenElements.widget(
            new TextWidget(tr("superfactory.machine.super_integrated_factory.name"))
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget.dynamicString(this::getCurrentProcessStepLine)
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget.dynamicString(this::getTotalProcessStepsLine)
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
    }

    @Override
    public void addUIWidgets(ModularWindow.Builder builder, UIBuildContext buildContext) {
        super.addUIWidgets(builder, buildContext);
        buildContext.addSyncedWindow(20, player -> createProcessManagementWindow());
        buildContext.addSyncedWindow(21, player -> createNodeEditorWindow());
        buildContext.addSyncedWindow(22, player -> createDeleteNodeConfirmWindow());
        buildContext.addSyncedWindow(23, player -> createAmountEditorWindow());
        builder.widget(new FakeSyncWidget.IntegerSyncer(() -> currentProcessStep, value -> currentProcessStep = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> totalProcessSteps, value -> totalProcessSteps = value))
            .widget(
                new FakeSyncWidget<>(
                    () -> processGraph,
                    this::readSyncedProcessGraph,
                    this::writeProcessGraphPacket,
                    this::readProcessGraphPacket));
    }

    @Override
    protected ButtonWidget createPowerPassButton() {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (!widget.isClient()) {
                Config.reload();
                mStructureChanged = true;
                checkMachine(getBaseMetaTileEntity(), getControllerSlot());
            }
        })
            .setPlayClickSound(true)
            .setBackground(TecTechUITextures.BUTTON_STANDARD_16x16, GTUITextures.OVERLAY_BUTTON_ARROW_GREEN_UP)
            .setPos(174, 116)
            .setSize(16, 16);
        button.addTooltip(tr("superfactory.machine.super_integrated_factory.gui.tooltip.check_structure"))
            .setTooltipShowUpDelay(5);
        return button;
    }

    @Override
    protected ButtonWidget createSafeVoidButton() {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (widget.isClient()) {
                SuperFactory.proxy.openIntegratedFactoryProcessGui(this);
            }
        })
            .setPlayClickSound(true)
            .setBackground(TecTechUITextures.BUTTON_STANDARD_16x16, GTUITextures.OVERLAY_BUTTON_CHECKMARK)
            .setPos(174, 132)
            .setSize(16, 16);
        button.addTooltip(tr("superfactory.machine.super_integrated_factory.gui.tooltip.process_management"))
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
        aNBT.setInteger("CurrentProcessStep", currentProcessStep);
        aNBT.setInteger("TotalProcessSteps", totalProcessSteps);
        aNBT.setTag("ProcessGraph", processGraph.writeToNBT());
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
        currentProcessStep = Math.max(0, aNBT.getInteger("CurrentProcessStep"));
        totalProcessSteps = Math.max(0, aNBT.getInteger("TotalProcessSteps"));
        if (aNBT.hasKey("ProcessGraph", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            processGraph.readFromNBT(aNBT.getCompoundTag("ProcessGraph"));
        }
    }

    private ModularWindow createProcessManagementWindow() {
        return ModularWindow.builder(PROCESS_WINDOW_WIDTH, PROCESS_WINDOW_HEIGHT)
            .setPos(
                (screenSize, mainWindow) -> new Pos2d(
                    Math.max(0, (screenSize.width - PROCESS_WINDOW_WIDTH) / 2),
                    Math.max(0, (screenSize.height - PROCESS_WINDOW_HEIGHT) / 2)))
            .setBackground(TecTechUITextures.BACKGROUND_SCREEN_BLUE)
            .widget(
                ButtonWidget.closeWindowButton(true)
                    .setPos(PROCESS_WINDOW_WIDTH - 15, 4))
            .widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.process.title"))
                    .setDefaultColor(Color.WHITE.normal)
                    .setPos(8, 6))
            .widget(
                createProcessButton(
                    PROCESS_WINDOW_WIDTH / 2 - 8,
                    4,
                    "superfactory.machine.super_integrated_factory.process.reset_view",
                    this::resetProcessView))
            .widget(
                new CanvasWidget(processGraph, 21, 22).setPos(8, 20)
                    .setSize(PROCESS_WINDOW_WIDTH - 40, PROCESS_WINDOW_HEIGHT - 28))
            .widget(
                createProcessButton(
                    PROCESS_TOOLBAR_X,
                    20,
                    "superfactory.machine.super_integrated_factory.process.add_node",
                    this::addDraftNode))
            .widget(
                createProcessButton(
                    PROCESS_TOOLBAR_X,
                    38,
                    "superfactory.machine.super_integrated_factory.process.auto_connect",
                    this::autoConnectPlaceholder))
            .widget(
                createProcessButton(
                    PROCESS_TOOLBAR_X,
                    56,
                    "superfactory.machine.super_integrated_factory.process.balance",
                    this::balancePlaceholder))
            .widget(
                createProcessButton(
                    PROCESS_TOOLBAR_X,
                    74,
                    "superfactory.machine.super_integrated_factory.process.import",
                    this::importPlaceholder))
            .widget(
                createProcessButton(
                    PROCESS_TOOLBAR_X,
                    92,
                    "superfactory.machine.super_integrated_factory.process.export",
                    this::exportPlaceholder))
            .build();
    }

    private ModularWindow createNodeEditorWindow() {
        clientEditingFactory = this;
        ProcessNode node = getSelectedNode();
        ModularWindow.Builder builder = ModularWindow.builder(300, 210)
            .setBackground(TecTechUITextures.BACKGROUND_SCREEN_BLUE)
            .widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.node_editor.title"))
                    .setDefaultColor(Color.WHITE.normal)
                    .setPos(8, 6))
            .widget(
                ButtonWidget.closeWindowButton(true)
                    .setPos(282, 4));
        if (node == null) {
            return builder
                .widget(
                    new TextWidget(tr("superfactory.machine.super_integrated_factory.node_editor.no_node"))
                        .setDefaultColor(Color.WHITE.normal)
                        .setMaxWidth(160)
                        .setPos(8, 24))
                .build();
        }
        boolean locked = node.locked;
        builder.widget(createTextField(() -> node.name, value -> {
            node.name = value;
            invalidateNodeCheck(node);
        }, 8, 22, 110, () -> !node.locked))
            .widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.node_editor.machine"))
                    .setDefaultColor(Color.WHITE.normal)
                    .setPos(128, 24))
            .widget(
                SlotWidget.phantom(node.machineHandler, 0)
                    .setHandlePhantomActionClient(true)
                    .setControlsAmount(false)
                    .setEnabled(!locked)
                    .setPos(170, 20))
            .widget(
                createFlagButton(
                    194,
                    20,
                    () -> node.endNode,
                    value -> node.endNode = value && !processGraph.hasOutgoingEdges(node.id),
                    "superfactory.machine.super_integrated_factory.node_editor.end_node"))
            .widget(createUnlockButton(node, 214, 20))
            .widget(createCheckRecipeButton(node, 238, 20))
            .widget(createConfirmNodeButton(node, 258, 20))
            .widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.node_editor.inputs"))
                    .setDefaultColor(Color.WHITE.normal)
                    .setPos(8, 44))
            .widget(
                new TextWidget("=").setDefaultColor(Color.WHITE.normal)
                    .setPos(140, 73))
            .widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.node_editor.outputs"))
                    .setDefaultColor(Color.WHITE.normal)
                    .setPos(154, 44))
            .widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.node_editor.non_consumables"))
                    .setDefaultColor(Color.WHITE.normal)
                    .setPos(8, 100))
            .widget(createTextField(() -> String.valueOf(node.durationTicks), value -> {
                node.durationTicks = parseInt(value, 0);
                invalidateNodeCheck(node);
            }, 178, 106, 38, () -> !node.locked))
            .widget(createTextField(() -> String.valueOf(node.euPerTick), value -> {
                node.euPerTick = parseLong(value, 0L);
                invalidateNodeCheck(node);
            }, 252, 106, 38, () -> !node.locked))
            .widget(createTextField(() -> String.valueOf(node.overclockCount), value -> {
                node.overclockCount = parseInt(value, 0);
                invalidateNodeCheck(node);
            }, 178, 128, 38, () -> !node.locked))
            .widget(createTextField(() -> String.valueOf(node.parallelLimit), value -> {
                node.parallelLimit = Math.max(1, parseInt(value, 1));
                invalidateNodeCheck(node);
            }, 252, 128, 38, () -> !node.locked))
            .widget(
                new TextWidget("t").setDefaultColor(Color.WHITE.normal)
                    .setPos(164, 108))
            .widget(
                new TextWidget("EU/t").setDefaultColor(Color.WHITE.normal)
                    .setPos(226, 108))
            .widget(
                new TextWidget("OC").setDefaultColor(Color.WHITE.normal)
                    .setPos(162, 130))
            .widget(
                new TextWidget("P").setDefaultColor(Color.WHITE.normal)
                    .setPos(238, 130));

        addFormulaSlots(builder, node);
        addNonConsumableSlots(builder, node);
        if (locked) {
            addLockedOverlay(builder);
        }
        return builder.build();
    }

    private ModularWindow createDeleteNodeConfirmWindow() {
        return ModularWindow.builder(160, 64)
            .setBackground(TecTechUITextures.BACKGROUND_SCREEN_BLUE)
            .widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.node_delete.confirm"))
                    .setDefaultColor(Color.WHITE.normal)
                    .setMaxWidth(144)
                    .setPos(8, 8))
            .widget(createDeleteConfirmButton(48, 38, true))
            .widget(createDeleteConfirmButton(96, 38, false))
            .build();
    }

    private ModularWindow createAmountEditorWindow() {
        return ModularWindow.builder(150, 62)
            .setBackground(TecTechUITextures.BACKGROUND_SCREEN_BLUE)
            .widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.amount_editor.title"))
                    .setDefaultColor(Color.WHITE.normal)
                    .setPos(8, 6))
            .widget(createAmountTextField(8, 24, 86))
            .widget(createAmountConfirmButton(102, 22))
            .widget(
                ButtonWidget.closeWindowButton(true)
                    .setPos(132, 4))
            .build();
    }

    private String getEditedStackAmountText() {
        ItemStack stack = getEditedStack();
        return stack == null ? "0" : String.valueOf(getDisplayAmount(stack));
    }

    private void setEditedStackAmountText(String value) {
        ItemStack stack = getEditedStack();
        if (stack == null) {
            return;
        }
        int amount = Math.max(1, parseInt(value, getDisplayAmount(stack)));
        if (stack.hasTagCompound() && stack.getTagCompound()
            .hasKey("mFluidDisplayAmount")) {
            stack.getTagCompound()
                .setLong("mFluidDisplayAmount", amount);
            stack.stackSize = 1;
        } else {
            stack.stackSize = amount;
        }
    }

    private void clearAmountEditor() {
        amountEditHandler = null;
        amountEditSlot = -1;
    }

    private int getDisplayAmount(ItemStack stack) {
        if (stack.hasTagCompound() && stack.getTagCompound()
            .hasKey("mFluidDisplayAmount")) {
            long amount = stack.getTagCompound()
                .getLong("mFluidDisplayAmount");
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, amount));
        }
        return stack.stackSize;
    }

    private ItemStack getEditedStack() {
        if (amountEditHandler == null || amountEditSlot < 0 || amountEditSlot >= amountEditHandler.getSlots()) {
            return null;
        }
        return amountEditHandler.getStackInSlot(amountEditSlot);
    }

    private TextFieldWidget createAmountTextField(int x, int y, int width) {
        return (TextFieldWidget) new TextFieldWidget() {

            @Override
            public boolean onKeyPressed(char character, int keyCode) {
                if (keyCode == 28 || keyCode == 156) {
                    syncToServer(ACTION_AMOUNT_APPLY, buffer -> NetworkUtils.writeStringSafe(buffer, getText()));
                    return true;
                }
                return super.onKeyPressed(character, keyCode);
            }

            @Override
            public void readOnServer(int id, PacketBuffer buf) {
                if (id == ACTION_AMOUNT_APPLY) {
                    setEditedStackAmountText(NetworkUtils.readStringSafe(buf));
                    clearAmountEditor();
                    getContext().closeWindow(23);
                    return;
                }
                super.readOnServer(id, buf);
            }
        }.setGetter(this::getEditedStackAmountText)
            .setSetter(this::setEditedStackAmountText)
            .setMaxLength(16)
            .setTextColor(Color.WHITE.normal)
            .setBackground(GTUITextures.BACKGROUND_TEXT_FIELD)
            .setPos(x, y)
            .setSize(width, 12);
    }

    private ButtonWidget createAmountConfirmButton(int x, int y) {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (!widget.isClient()) {
                setEditedStackAmountText(getEditedStackAmountText());
                clearAmountEditor();
                widget.getContext()
                    .closeWindow(23);
            }
        })
            .setPlayClickSound(true)
            .setBackground(TecTechUITextures.BUTTON_STANDARD_16x16, GTUITextures.OVERLAY_BUTTON_CHECKMARK)
            .setPos(x, y)
            .setSize(16, 16);
        button.addTooltip(tr("superfactory.machine.super_integrated_factory.amount_editor.confirm"))
            .setTooltipShowUpDelay(5);
        return button;
    }

    private ButtonWidget createDeleteConfirmButton(int x, int y, boolean confirm) {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (!widget.isClient() && confirm) {
                processGraph.deleteSelectedNode();
                widget.getContext()
                    .closeWindow(21);
            }
            if (!widget.isClient()) {
                widget.getContext()
                    .closeWindow(22);
            }
        })
            .setPlayClickSound(true)
            .setBackground(
                TecTechUITextures.BUTTON_STANDARD_16x16,
                confirm ? GTUITextures.OVERLAY_BUTTON_CHECKMARK : GTUITextures.OVERLAY_BUTTON_CROSS)
            .setPos(x, y)
            .setSize(16, 16);
        button
            .addTooltip(
                tr(
                    confirm ? "superfactory.machine.super_integrated_factory.node_delete.confirm_yes"
                        : "superfactory.machine.super_integrated_factory.node_delete.confirm_no"))
            .setTooltipShowUpDelay(5);
        return button;
    }

    private ButtonWidget createProcessButton(int x, int y, String translationKey, Runnable action) {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            boolean clientSideViewAction = "superfactory.machine.super_integrated_factory.process.reset_view"
                .equals(translationKey)
                || "superfactory.machine.super_integrated_factory.process.add_node".equals(translationKey);
            if (clientSideViewAction || !widget.isClient()) {
                action.run();
            }
        })
            .setPlayClickSound(true)
            .setBackground(TecTechUITextures.BUTTON_STANDARD_16x16, GTUITextures.OVERLAY_BUTTON_CHECKMARK)
            .setPos(x, y)
            .setSize(16, 16);
        button.addTooltip(tr(translationKey))
            .setTooltipShowUpDelay(5);
        return button;
    }

    private TextFieldWidget createTextField(Supplier<String> getter, Consumer<String> setter, int x, int y, int width,
        BooleanSupplier enabledSupplier) {
        return (TextFieldWidget) new TextFieldWidget().setGetter(getter)
            .setSetter(setter)
            .setMaxLength(64)
            .setTextColor(Color.WHITE.normal)
            .setBackground(GTUITextures.BACKGROUND_TEXT_FIELD)
            .setPos(x, y)
            .setSize(width, 12)
            .setEnabled(widget -> enabledSupplier.getAsBoolean());
    }

    private ButtonWidget createFlagButton(int x, int y, BooleanSupplier getter, Consumer<Boolean> setter,
        String translationKey) {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            setter.accept(!getter.getAsBoolean());
            widget.notifyTooltipChange();
        })
            .setPlayClickSound(true)
            .setBackground(
                () -> new com.gtnewhorizons.modularui.api.drawable.IDrawable[] {
                    TecTechUITextures.BUTTON_STANDARD_16x16,
                    getter.getAsBoolean() ? GTUITextures.OVERLAY_BUTTON_ARROW_GREEN_UP
                        : GTUITextures.OVERLAY_BUTTON_POWER_SWITCH_OFF })
            .setPos(x, y)
            .setSize(16, 16);
        button.addTooltip(tr(translationKey))
            .setTooltipShowUpDelay(5);
        return button;
    }

    private ButtonWidget createUnlockButton(ProcessNode node, int x, int y) {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (node.locked) {
                node.locked = false;
                node.lastRecipeCheckPassed = false;
                widget.notifyTooltipChange();
            }
        })
            .setPlayClickSound(true)
            .setBackground(
                TecTechUITextures.BUTTON_STANDARD_16x16,
                node.locked ? GTUITextures.OVERLAY_BUTTON_CROSS : GTUITextures.OVERLAY_BUTTON_POWER_SWITCH_OFF)
            .setPos(x, y)
            .setSize(16, 16)
            .setEnabled(node.locked);
        button.addTooltip(tr("superfactory.machine.super_integrated_factory.node_editor.unlock"))
            .setTooltipShowUpDelay(5);
        return button;
    }

    private ButtonWidget createCheckRecipeButton(ProcessNode node, int x, int y) {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (!node.locked) {
                checkNodeRecipe(node, false);
                widget.notifyTooltipChange();
            }
        })
            .setPlayClickSound(true)
            .setBackground(
                () -> new com.gtnewhorizons.modularui.api.drawable.IDrawable[] {
                    TecTechUITextures.BUTTON_STANDARD_16x16,
                    node.lastRecipeCheckPassed ? GTUITextures.OVERLAY_BUTTON_CHECKMARK
                        : GTUITextures.OVERLAY_BUTTON_CROSS })
            .setPos(x, y)
            .setSize(16, 16)
            .setEnabled(!node.locked);
        button.addTooltip(tr("superfactory.machine.super_integrated_factory.node_editor.check_recipe"))
            .setTooltipShowUpDelay(5);
        return button;
    }

    private ButtonWidget createConfirmNodeButton(ProcessNode node, int x, int y) {
        ButtonWidget button = (ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (!node.locked) {
                if (checkNodeRecipe(node, true)) {
                    node.locked = true;
                }
                widget.notifyTooltipChange();
            }
        })
            .setPlayClickSound(true)
            .setBackground(
                TecTechUITextures.BUTTON_STANDARD_16x16,
                node.lastRecipeCheckPassed ? GTUITextures.OVERLAY_BUTTON_ARROW_GREEN_UP
                    : GTUITextures.OVERLAY_BUTTON_CROSS)
            .setPos(x, y)
            .setSize(16, 16)
            .setEnabled(!node.locked);
        button.addTooltip(tr("superfactory.machine.super_integrated_factory.node_editor.confirm"))
            .setTooltipShowUpDelay(5);
        return button;
    }

    private boolean checkNodeRecipe(ProcessNode node, boolean strict) {
        /*
         * The first UI pass only knows recipes imported through NEI. Treat that imported recipe as a strict snapshot,
         * so manual edits cannot be locked until the future recipe-map resolver can prove they still match.
         */
        boolean hasRecipeSnapshot = node.recipeFingerprint != null && !node.recipeFingerprint.isEmpty();
        boolean matchesSnapshot = hasRecipeSnapshot && node.recipeFingerprint.equals(buildNodeFingerprint(node));
        boolean hasMachine = node.machineHandler.getStackInSlot(0) != null;
        node.lastRecipeCheckPassed = hasMachine && matchesSnapshot;
        return node.lastRecipeCheckPassed;
    }

    private void addFormulaSlots(ModularWindow.Builder builder, ProcessNode node) {
        addPageButton(
            builder,
            112,
            43,
            () -> node.inputPage = Math.max(0, node.inputPage - 1),
            "superfactory.machine.super_integrated_factory.node_editor.prev_inputs");
        addPageButton(
            builder,
            126,
            43,
            () -> node.inputPage = Math.min(getInputMaxPage(), node.inputPage + 1),
            "superfactory.machine.super_integrated_factory.node_editor.next_inputs");
        builder.widget(
            TextWidget.dynamicString(() -> pageText(node.inputPage, getInputMaxPage()))
                .setDefaultColor(Color.WHITE.normal)
                .setPos(85, 45));
        addPageButton(
            builder,
            258,
            43,
            () -> node.outputPage = Math.max(0, node.outputPage - 1),
            "superfactory.machine.super_integrated_factory.node_editor.prev_outputs");
        addPageButton(
            builder,
            272,
            43,
            () -> node.outputPage = Math.min(getOutputMaxPage(), node.outputPage + 1),
            "superfactory.machine.super_integrated_factory.node_editor.next_outputs");
        builder.widget(
            TextWidget.dynamicString(() -> pageText(node.outputPage, getOutputMaxPage()))
                .setDefaultColor(Color.WHITE.normal)
                .setPos(231, 45));

        int inputStart = Math.min(node.inputPage, getInputMaxPage()) * PATTERN_VISIBLE_SLOTS;
        for (int visible = 0; visible < PATTERN_VISIBLE_SLOTS; visible++) {
            int slot = inputStart + visible;
            int x = 8 + visible % 6 * 21;
            int y = 58 + visible / 6 * 20;
            RecipePatternSlotWidget slotWidget = new RecipePatternSlotWidget(node.inputHandler, slot, () -> {
                openAmountEditor(node.inputHandler, slot);
                invalidateNodeCheck(node);
            }, 23).setLockedSupplier(() -> node.locked)
                .setChangeAction(() -> invalidateNodeCheck(node));
            slotWidget.setHandlePhantomActionClient(true)
                .setControlsAmount(true)
                .setEnabled(!node.locked)
                .setPos(x, y);
            builder.widget(slotWidget);
        }
        int outputStart = Math.min(node.outputPage, getOutputMaxPage()) * PATTERN_VISIBLE_SLOTS;
        for (int visible = 0; visible < PATTERN_VISIBLE_SLOTS; visible++) {
            int slot = outputStart + visible;
            int x = 154 + visible % 6 * 21;
            int y = 58 + visible / 6 * 20;
            RecipePatternSlotWidget slotWidget = new RecipePatternSlotWidget(node.outputHandler, slot, () -> {
                openAmountEditor(node.outputHandler, slot);
                invalidateNodeCheck(node);
            }, 23).setLockedSupplier(() -> node.locked)
                .setChangeAction(() -> invalidateNodeCheck(node));
            slotWidget.setHandlePhantomActionClient(true)
                .setControlsAmount(true)
                .setEnabled(!node.locked)
                .setPos(x, y);
            builder.widget(slotWidget);
        }
    }

    private void openAmountEditor(com.gtnewhorizons.modularui.api.forge.ItemStackHandler handler, int slot) {
        if (handler.getStackInSlot(slot) == null) {
            return;
        }
        amountEditHandler = handler;
        amountEditSlot = slot;
        getBaseMetaTileEntity().markDirty();
    }

    private void invalidateNodeCheck(ProcessNode node) {
        if (!node.locked) {
            node.lastRecipeCheckPassed = false;
        }
    }

    private void addNonConsumableSlots(ModularWindow.Builder builder, ProcessNode node) {
        for (int i = 0; i < ProcessNode.NON_CONSUMABLE_SLOTS; i++) {
            builder.widget(
                SlotWidget.phantom(node.nonConsumableHandler, i)
                    .setHandlePhantomActionClient(true)
                    .setControlsAmount(true)
                    .setEnabled(!node.locked)
                    .setPos(8 + i % 3 * 18, 114 + i / 3 * 18));
        }
    }

    private void addLockedOverlay(ModularWindow.Builder builder) {
        builder.widget(
            new TextWidget(EnumChatFormatting.GRAY + "\u00a7lLOCKED").setDefaultColor(Color.WHITE.normal)
                .setPos(74, 78))
            .widget(
                new TextWidget(EnumChatFormatting.GRAY + "\u00a7lLOCKED").setDefaultColor(Color.WHITE.normal)
                    .setPos(220, 78))
            .widget(
                new TextWidget(EnumChatFormatting.GRAY + "\u00a7lLOCKED").setDefaultColor(Color.WHITE.normal)
                    .setPos(32, 140))
            .widget(
                new TextWidget(EnumChatFormatting.GRAY + "\u00a7lLOCKED").setDefaultColor(Color.WHITE.normal)
                    .setPos(204, 126));
    }

    private void addPageButton(ModularWindow.Builder builder, int x, int y, Runnable action, String translationKey) {
        builder.widget((ButtonWidget) new ButtonWidget().setOnClick((clickData, widget) -> {
            if (!widget.isClient()) {
                action.run();
                widget.notifyTooltipChange();
            }
        })
            .setPlayClickSound(true)
            .setBackground(TecTechUITextures.BUTTON_STANDARD_16x16, GTUITextures.OVERLAY_BUTTON_CHECKMARK)
            .setPos(x, y)
            .setSize(12, 12)
            .addTooltip(tr(translationKey))
            .setTooltipShowUpDelay(5));
    }

    private int getInputMaxPage() {
        return Math.max(0, (ProcessNode.INPUT_SLOTS - 1) / PATTERN_VISIBLE_SLOTS);
    }

    private int getOutputMaxPage() {
        return Math.max(0, (ProcessNode.OUTPUT_SLOTS - 1) / PATTERN_VISIBLE_SLOTS);
    }

    private String pageText(int page, int maxPage) {
        return (Math.min(page, maxPage) + 1) + "/" + (maxPage + 1);
    }

    private void addDraftNode() {
        ProcessNode node = processGraph.addDraftNode(-processGraph.viewportX + 32, -processGraph.viewportY + 32);
        node.name = tr("superfactory.machine.super_integrated_factory.process.new_node") + " " + node.id;
        processGraph.selectedNodeId = node.id;
    }

    private void autoConnectPlaceholder() {
        if (processGraph.nodes.size() < 2 || !processGraph.edges.isEmpty()) {
            return;
        }
        ProcessNode from = processGraph.nodes.get(0);
        ProcessNode to = processGraph.nodes.get(1);
        if (from.endNode || !from.locked || !to.locked) {
            return;
        }
        processGraph.edges.add(new ProcessEdge(processGraph.nextEdgeId++, from.id, to.id));
    }

    private void balancePlaceholder() {}

    private void importPlaceholder() {}

    private void exportPlaceholder() {}

    private void resetProcessView() {
        processGraph.viewportX = 0;
        processGraph.viewportY = 0;
        processGraph.zoom = 1.0D;
    }

    private void toggleSnap() {
        processGraph.snapToGrid = !processGraph.snapToGrid;
    }

    public int getSelectedProcessNodeId() {
        return processGraph.selectedNodeId;
    }

    public ProcessGraph getProcessGraph() {
        return processGraph;
    }

    public void readProcessGraphFromClient(NBTTagCompound graphTag) {
        processGraph.readFromNBT(graphTag);
        getBaseMetaTileEntity().markDirty();
    }

    public static MTESuperIntegratedFactory getClientEditingFactory() {
        return clientEditingFactory;
    }

    public static void setClientEditingFactory(MTESuperIntegratedFactory factory) {
        clientEditingFactory = factory;
    }

    public void applyRecipeToNode(int nodeId, NBTTagCompound recipeTag) {
        ProcessNode node = processGraph.findNode(nodeId);
        if (node == null) {
            return;
        }
        loadHandlerItems(node.inputHandler, recipeTag.getCompoundTag("Inputs"));
        loadInputVariants(node, recipeTag);
        loadHandlerItems(node.outputHandler, recipeTag.getCompoundTag("Outputs"));
        if (recipeTag.hasKey("NonConsumables")) {
            loadHandlerItems(node.nonConsumableHandler, recipeTag.getCompoundTag("NonConsumables"));
        }
        if (recipeTag.hasKey("Machine")) {
            node.machineHandler.setStackInSlot(0, ItemStack.loadItemStackFromNBT(recipeTag.getCompoundTag("Machine")));
        }
        node.durationTicks = Math.max(0, recipeTag.getInteger("DurationTicks"));
        node.euPerTick = Math.max(0L, recipeTag.getLong("EUt"));
        node.recipeHandlerName = recipeTag.getString("RecipeHandlerName");
        node.recipeMapName = recipeTag.getString("RecipeMapName");
        node.recipeFingerprint = recipeTag.getString("RecipeFingerprint");
        node.lastRecipeCheckPassed = node.recipeFingerprint != null
            && node.recipeFingerprint.equals(buildNodeFingerprint(node));
        node.locked = false;
    }

    private void loadHandlerItems(com.gtnewhorizons.modularui.api.forge.ItemStackHandler handler,
        NBTTagCompound handlerTag) {
        for (int i = 0; i < handler.getSlots(); i++) {
            handler.setStackInSlot(i, null);
        }
        handler.deserializeNBT(handlerTag);
    }

    private void loadInputVariants(ProcessNode node, NBTTagCompound recipeTag) {
        for (int i = 0; i < ProcessNode.INPUT_SLOTS; i++) {
            node.clearInputVariants(i);
        }
        if (!recipeTag.hasKey("InputVariants", Constants.NBT.TAG_LIST)) {
            return;
        }
        NBTTagList inputVariantList = recipeTag.getTagList("InputVariants", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < inputVariantList.tagCount(); i++) {
            NBTTagCompound variantTag = inputVariantList.getCompoundTagAt(i);
            int slot = Math.max(0, Math.min(ProcessNode.INPUT_SLOTS - 1, variantTag.getInteger("Slot")));
            node.inputVariants[slot].readFromNBT(variantTag);
        }
    }

    public static String buildRecipeFingerprint(GTRecipe recipe) {
        if (recipe == null) {
            return "";
        }
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler inputs = new com.gtnewhorizons.modularui.api.forge.ItemStackHandler(
            ProcessNode.INPUT_SLOTS);
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs = new com.gtnewhorizons.modularui.api.forge.ItemStackHandler(
            ProcessNode.OUTPUT_SLOTS);
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler nonConsumables = new com.gtnewhorizons.modularui.api.forge.ItemStackHandler(
            ProcessNode.NON_CONSUMABLE_SLOTS);
        fillHandler(inputs, recipe.mInputs);
        fillHandlerWithFluids(inputs, recipe.mFluidInputs);
        fillHandler(outputs, recipe.mOutputs);
        fillHandlerWithFluids(outputs, recipe.mFluidOutputs);
        if (recipe.mSpecialItems instanceof ItemStack stack) {
            nonConsumables.setStackInSlot(0, stack.copy());
        } else if (recipe.mSpecialItems instanceof ItemStack[]stacks) {
            fillHandler(nonConsumables, stacks);
        }
        return buildRecipeFingerprint(inputs, outputs, nonConsumables, recipe.mDuration, recipe.mEUt);
    }

    public static String buildRecipeFingerprint(com.gtnewhorizons.modularui.api.forge.ItemStackHandler inputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler nonConsumables, int duration, long euPerTick) {
        return "t=" + duration
            + ";e="
            + euPerTick
            + ";i="
            + handlerFingerprint(inputs)
            + ";o="
            + handlerFingerprint(outputs)
            + ";nc="
            + handlerFingerprint(nonConsumables);
    }

    private String buildNodeFingerprint(ProcessNode node) {
        return node.buildRecipeFingerprint();
    }

    private static void fillHandler(com.gtnewhorizons.modularui.api.forge.ItemStackHandler handler,
        ItemStack[] stacks) {
        if (stacks == null) {
            return;
        }
        int slot = firstEmptySlot(handler);
        for (ItemStack stack : stacks) {
            if (stack != null && stack.stackSize > 0 && slot < handler.getSlots()) {
                handler.setStackInSlot(slot++, stack.copy());
            }
        }
    }

    private static void fillHandlerWithFluids(com.gtnewhorizons.modularui.api.forge.ItemStackHandler handler,
        FluidStack[] stacks) {
        if (stacks == null) {
            return;
        }
        int slot = firstEmptySlot(handler);
        for (FluidStack stack : stacks) {
            if (stack != null && stack.amount > 0 && slot < handler.getSlots()) {
                ItemStack display = GTUtility.getFluidDisplayStack(stack, true);
                if (display != null) {
                    handler.setStackInSlot(slot++, display);
                }
            }
        }
    }

    private static int firstEmptySlot(com.gtnewhorizons.modularui.api.forge.ItemStackHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (handler.getStackInSlot(i) == null) {
                return i;
            }
        }
        return handler.getSlots();
    }

    private static String handlerFingerprint(com.gtnewhorizons.modularui.api.forge.ItemStackHandler handler) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack != null) {
                parts.add(stackFingerprint(stack));
            }
        }
        parts.sort(Comparator.naturalOrder());
        return parts.toString();
    }

    private static String stackFingerprint(ItemStack stack) {
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        if (fluid != null && fluid.getFluid() != null) {
            return "fluid:" + fluid.getFluid()
                .getName() + "@" + Math.max(1, fluid.amount);
        }
        String itemName = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        return "item:" + itemName + ":" + stack.getItemDamage() + "@" + Math.max(1, stack.stackSize);
    }

    private ProcessNode getSelectedNode() {
        return processGraph.findNode(processGraph.selectedNodeId);
    }

    private void readSyncedProcessGraph(ProcessGraph syncedGraph) {
        processGraph.readFromNBT(syncedGraph.writeToNBT());
    }

    private void writeProcessGraphPacket(PacketBuffer buffer, ProcessGraph graph) {
        try {
            buffer.writeNBTTagCompoundToBuffer(graph.writeToNBT());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ProcessGraph readProcessGraphPacket(PacketBuffer buffer) {
        ProcessGraph graph = new ProcessGraph();
        try {
            NBTTagCompound tag = buffer.readNBTTagCompoundFromBuffer();
            if (tag != null) {
                graph.readFromNBT(tag);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return graph;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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

    private int getEffectiveParallelLimit() {
        return Math.max(1, clampInputInt(INDEX_PARALLEL));
    }

    private String getCurrentProcessStepLine() {
        if (!mMachine) {
            return tr("superfactory.machine.super_integrated_factory.gui.current_step") + ": "
                + EnumChatFormatting.RED
                + tr("superfactory.machine.super_integrated_factory.gui.structure_failed");
        }
        return tr("superfactory.machine.super_integrated_factory.gui.current_step") + ": "
            + EnumChatFormatting.AQUA
            + currentProcessStep;
    }

    private String getTotalProcessStepsLine() {
        return tr("superfactory.machine.super_integrated_factory.gui.total_steps") + ": "
            + EnumChatFormatting.AQUA
            + totalProcessSteps;
    }

    private String getParallelStatusLine() {
        return tr("superfactory.machine.super_integrated_factory.gui.parallel") + ": "
            + EnumChatFormatting.AQUA
            + getEffectiveParallelLimit();
    }

    private String getProgressStatusLine() {
        return tr("superfactory.machine.super_integrated_factory.gui.progress") + ": "
            + EnumChatFormatting.AQUA
            + mProgresstime
            + EnumChatFormatting.GRAY
            + " / "
            + mMaxProgresstime;
    }

    private String getEnergyStatusLine() {
        return tr("superfactory.machine.super_integrated_factory.gui.eu") + ": "
            + EnumChatFormatting.RED
            + formatPowerUsageDisplay();
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
        return value < 1 ? LedStatus.STATUS_TOO_LOW : LedStatus.STATUS_OK;
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

    private String tr(String key) {
        return StatCollector.translateToLocal(key);
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
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to access TecTech parameter field " + name, exception);
        }
    }
}
