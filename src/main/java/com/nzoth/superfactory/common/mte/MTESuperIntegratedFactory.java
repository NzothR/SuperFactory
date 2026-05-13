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
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

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
import com.nzoth.superfactory.common.network.MessageProcessCanvasStatus;
import com.nzoth.superfactory.common.network.NetworkLoader;
import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.process.ProcessRequirements;
import com.nzoth.superfactory.common.ui.canvas.CanvasWidget;
import com.nzoth.superfactory.common.ui.widget.RecipePatternSlotWidget;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.Textures;
import gregtech.api.gui.modularui.GTUITextures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.logic.ProcessingLogic;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.ParallelHelper;
import gregtech.api.util.shutdown.ShutDownReason;
import gregtech.api.util.shutdown.ShutDownReasonRegistry;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.misc.WirelessNetworkManager;
import gregtech.common.tileentities.machines.IDualInputHatch;
import gregtech.common.tileentities.machines.IDualInputInventory;
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
    private static final int MODE_STANDBY = 0;
    private static final int MODE_INPUT = 1;
    private static final int MODE_RUNNING = 2;
    private static final int MODE_OUTPUT = 3;
    private static final int RUNTIME_OUTPUT_ESTIMATE_LINE_LIMIT = 8;

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
     * Runtime parameters are intentionally narrow for the integrated factory. The graph already owns recipe identity
     * and
     * per-node tuning, so the machine only exposes global execution modifiers.
     */
    private static final int INDEX_WIRELESS = 0;
    private static final int INDEX_PARALLEL = 10;
    private static final int INDEX_MANUAL_OVERCLOCKS = 1;

    private int casingCount;
    /** Completed virtual jobs in the currently installed runtime graph. */
    private int currentProcessStep;
    /** Number of controller slots requested by the submitted graph; used as a coarse GUI progress target. */
    private int totalProcessSteps;
    private com.gtnewhorizons.modularui.api.forge.ItemStackHandler amountEditHandler;
    private int amountEditSlot = -1;
    /** Editable design graph shown in the process GUI. It is never mutated by the runtime executor. */
    private final ProcessGraph processGraph = new ProcessGraph();
    /** Installed graph that RUNNING mode actually executes. It is replaced only after OUTPUT has cleared state. */
    private final ProcessGraph runtimeGraph = new ProcessGraph();
    /** Graph submitted while another process may still be loaded; OUTPUT mode promotes it when the machine is empty. */
    private final ProcessGraph pendingRuntimeGraph = new ProcessGraph();
    /** Gate resources for the installed runtime graph: host machines, NC items, and startup materials. */
    private final ProcessRequirements processRequirements = new ProcessRequirements();
    /** Gate resources for the next submitted graph. */
    private final ProcessRequirements pendingProcessRequirements = new ProcessRequirements();
    /** Intermediate products that are available to downstream virtual nodes before touching external outputs. */
    private final List<BufferedItemStack> internalItems = new ArrayList<>();
    private final List<BufferedFluidStack> internalFluids = new ArrayList<>();
    /** Products that must be exported before OUTPUT can finish or the next graph can be installed. */
    private final List<BufferedItemStack> outputItems = new ArrayList<>();
    private final List<BufferedFluidStack> outputFluids = new ArrayList<>();
    /** Hysteresis flags for upstream internal outputs that already reached their buffer high-water mark. */
    private final Set<String> throttledInternalItemOutputs = new HashSet<>();
    private final Set<String> throttledInternalFluidOutputs = new HashSet<>();
    /** Active virtual recipe jobs. Each job owns its consumed inputs so OUTPUT can abort it losslessly. */
    private final List<RunningJob> runningJobs = new ArrayList<>();
    /** Synced, fixed-size source for the main GUI runtime output estimate lines. */
    private List<String> runtimeOutputEstimateLines = new ArrayList<>();
    private int factoryMode = MODE_STANDBY;
    private int ioCycleTicks;
    private long lastEnergySetupFailureLogTick = Long.MIN_VALUE;
    private long lastRuntimeDebugLogTick = Long.MIN_VALUE;
    private Object activeProcessGui;
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
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.wireless_mode"),
            (base, parameter) -> switchStatus(parameter.get()));
        group0.makeInParameter(
            1,
            1,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.parallel"),
            (base, parameter) -> parallelStatus(parameter.get()));

        var group1 = parametrization.getGroup(1, true);
        group1.makeInParameter(
            0,
            0,
            (base, parameter) -> tr("superfactory.machine.super_integrated_factory.param.manual_overclocks"),
            (base, parameter) -> optionalValueStatus(parameter.get()));
    }

    @Override
    protected void parametersStatusesWrite_EM(boolean busy) {
        sanitizeParameterRelationships();
        Arrays.fill(inputStatuses(), LedStatus.STATUS_UNUSED);
        Arrays.fill(outputStatuses(), LedStatus.STATUS_UNUSED);

        inputStatuses()[INDEX_WIRELESS] = switchStatus(inputValues()[INDEX_WIRELESS]);
        inputStatuses()[INDEX_PARALLEL] = parallelStatus(inputValues()[INDEX_PARALLEL]);
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
        if (isWirelessModeEnabled()) {
            clearMachineWorkDisplay();
            return factoryMode == MODE_RUNNING && runningJobs.isEmpty() ? CheckRecipeResultRegistry.NO_RECIPE
                : CheckRecipeResultRegistry.SUCCESSFUL;
        }
        if (factoryMode == MODE_INPUT || factoryMode == MODE_OUTPUT) {
            prepareIdleRuntimePulse();
            return CheckRecipeResultRegistry.SUCCESSFUL;
        }
        if (factoryMode == MODE_RUNNING && !runningJobs.isEmpty()) {
            updateRuntimeProgressDisplay();
            return CheckRecipeResultRegistry.SUCCESSFUL;
        }
        clearMachineWorkDisplay();
        return CheckRecipeResultRegistry.NO_RECIPE;
    }

    @Override
    public void onPostTick(IGregTechTileEntity baseMetaTileEntity, long tick) {
        try {
            super.onPostTick(baseMetaTileEntity, tick);
        } catch (ArithmeticException exception) {
            if (!baseMetaTileEntity.isServerSide()) {
                throw exception;
            }
            /*
             * TecTech's energy hatch summary can divide by zero when this virtual executor is restored in a state where
             * no valid energy flow exists yet. Keep the tile alive and let the integrated factory runtime rebuild its
             * own display values below instead of ejecting the integrated server.
             */
            clearMachineWorkDisplay();
            if (tick - lastEnergySetupFailureLogTick >= 200L) {
                lastEnergySetupFailureLogTick = tick;
                SuperFactory.LOG.warn(
                    "[Super Integrated Factory] Ignored TecTech energy hatch summary divide-by-zero at {} {} {} in dim {}.",
                    baseMetaTileEntity.getXCoord(),
                    baseMetaTileEntity.getYCoord(),
                    baseMetaTileEntity.getZCoord(),
                    baseMetaTileEntity.getWorld().provider.dimensionId);
            }
        }
        if (!baseMetaTileEntity.isServerSide()) {
            return;
        }
        if (factoryMode == MODE_INPUT || factoryMode == MODE_OUTPUT) {
            ioCycleTicks = (ioCycleTicks + 1) % 20;
        } else {
            ioCycleTicks = 0;
        }
        if (factoryMode == MODE_RUNNING) {
            processRunningMode(tick);
            return;
        }
        if (tick % 20L != 0L) {
            return;
        }
        if (factoryMode == MODE_INPUT) {
            processInputMode();
        } else if (factoryMode == MODE_OUTPUT) {
            processOutputMode();
        }
    }

    @Override
    protected ProcessingLogic createProcessingLogic() {
        return new ProcessingLogic() {

            @Override
            public CheckRecipeResult process() {
                sanitizeParameterRelationships();
                if (isWirelessModeEnabled()) {
                    clearMachineWorkDisplay();
                    return factoryMode == MODE_RUNNING && runningJobs.isEmpty() ? CheckRecipeResultRegistry.NO_RECIPE
                        : CheckRecipeResultRegistry.SUCCESSFUL;
                }
                if (factoryMode == MODE_INPUT || factoryMode == MODE_OUTPUT) {
                    prepareIdleRuntimePulse();
                    return CheckRecipeResultRegistry.SUCCESSFUL;
                }
                if (factoryMode == MODE_RUNNING && !runningJobs.isEmpty()) {
                    updateRuntimeProgressDisplay();
                    return CheckRecipeResultRegistry.SUCCESSFUL;
                }
                clearMachineWorkDisplay();
                return CheckRecipeResultRegistry.NO_RECIPE;
            }
        };
    }

    @Override
    public boolean canUseControllerSlotForRecipe() {
        return false;
    }

    @Override
    public boolean onRunningTick(ItemStack stack) {
        if (isWirelessModeEnabled()) {
            return true;
        }
        return super.onRunningTick(stack);
    }

    @Override
    public void stopMachine(ShutDownReason reason) {
        if (reason == ShutDownReasonRegistry.POWER_LOSS) {
            discardRunningJobsForPowerLoss();
        }
        super.stopMachine(reason);
    }

    @Override
    public void onScrewdriverRightClick(ForgeDirection side, EntityPlayer player, float x, float y, float z,
        ItemStack tool) {
        super.onScrewdriverRightClick(side, player, x, y, z, tool);
        factoryMode = (factoryMode + 1) % 4;
        if (factoryMode != MODE_OUTPUT && outputModeIsLocked()) {
            factoryMode = MODE_OUTPUT;
            GTUtility.sendChatToPlayer(
                player,
                EnumChatFormatting.RED + tr("superfactory.machine.super_integrated_factory.chat.output_stored_first"));
            return;
        }
        if (factoryMode == MODE_OUTPUT) {
            cancelCurrentProcessForOutput();
        }
        GTUtility.sendChatToPlayer(
            player,
            EnumChatFormatting.AQUA + tr("superfactory.machine.super_integrated_factory.gui.machine_mode")
                + ": "
                + getModeDisplayName());
        getBaseMetaTileEntity().markDirty();
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
            TextWidget.dynamicString(this::getMachineModeLine)
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget.dynamicString(this::getIoCycleLine)
                .setDefaultColor(Color.WHITE.normal));
        if (factoryMode == MODE_INPUT) {
            addRequirementDisplayWidgets(screenElements);
        }
        screenElements.widget(
            TextWidget.dynamicString(this::getActiveNodeCountLine)
                .setDefaultColor(Color.WHITE.normal));
        screenElements.widget(
            TextWidget.dynamicString(this::getTotalEnergyStatusLine)
                .setDefaultColor(Color.WHITE.normal));
        addRunningNodeWidgets(screenElements);
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
            .widget(new FakeSyncWidget.IntegerSyncer(() -> factoryMode, value -> factoryMode = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> ioCycleTicks, value -> ioCycleTicks = value))
            .widget(
                new FakeSyncWidget.StringSyncer(
                    () -> serializeLines(runtimeOutputEstimateLines),
                    value -> runtimeOutputEstimateLines = deserializeLines(value)))
            .widget(
                new FakeSyncWidget<>(
                    () -> processRequirements,
                    this::readSyncedProcessRequirements,
                    this::writeProcessRequirementsPacket,
                    this::readProcessRequirementsPacket))
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
        aNBT.setInteger("FactoryMode", factoryMode);
        aNBT.setInteger("IoCycleTicks", ioCycleTicks);
        aNBT.setTag("ProcessRequirements", processRequirements.writeToNBT());
        aNBT.setTag("PendingProcessRequirements", pendingProcessRequirements.writeToNBT());
        aNBT.setTag("RuntimeGraph", runtimeGraph.writeToNBT());
        aNBT.setTag("PendingRuntimeGraph", pendingRuntimeGraph.writeToNBT());
        aNBT.setTag("InternalItems", writeItemList(internalItems));
        aNBT.setTag("OutputItems", writeItemList(outputItems));
        aNBT.setTag("InternalFluids", writeFluidList(internalFluids));
        aNBT.setTag("OutputFluids", writeFluidList(outputFluids));
        aNBT.setTag("RunningJobs", writeRunningJobs());
        aNBT.setString("RuntimeOutputEstimateLines", serializeLines(runtimeOutputEstimateLines));
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
        currentProcessStep = Math.max(0, aNBT.getInteger("CurrentProcessStep"));
        totalProcessSteps = Math.max(0, aNBT.getInteger("TotalProcessSteps"));
        if (aNBT.hasKey("ProcessGraph", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            processGraph.readFromNBT(aNBT.getCompoundTag("ProcessGraph"));
        }
        factoryMode = Math.max(MODE_STANDBY, Math.min(MODE_OUTPUT, aNBT.getInteger("FactoryMode")));
        ioCycleTicks = Math.max(0, Math.min(20, aNBT.getInteger("IoCycleTicks")));
        if (aNBT.hasKey("ProcessRequirements", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            processRequirements.readFromNBT(aNBT.getCompoundTag("ProcessRequirements"));
        }
        if (aNBT.hasKey("PendingProcessRequirements", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            pendingProcessRequirements.readFromNBT(aNBT.getCompoundTag("PendingProcessRequirements"));
        }
        if (aNBT.hasKey("RuntimeGraph", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            runtimeGraph.readFromNBT(aNBT.getCompoundTag("RuntimeGraph"));
        }
        if (aNBT.hasKey("PendingRuntimeGraph", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            pendingRuntimeGraph.readFromNBT(aNBT.getCompoundTag("PendingRuntimeGraph"));
        }
        readItemList(aNBT.getTagList("InternalItems", Constants.NBT.TAG_COMPOUND), internalItems);
        readItemList(aNBT.getTagList("OutputItems", Constants.NBT.TAG_COMPOUND), outputItems);
        readFluidList(aNBT.getTagList("InternalFluids", Constants.NBT.TAG_COMPOUND), internalFluids);
        readFluidList(aNBT.getTagList("OutputFluids", Constants.NBT.TAG_COMPOUND), outputFluids);
        readRunningJobs(aNBT.getTagList("RunningJobs", Constants.NBT.TAG_COMPOUND));
        runtimeOutputEstimateLines = deserializeLines(aNBT.getString("RuntimeOutputEstimateLines"));
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

    public void submitProcessRequirements(NBTTagCompound requirementsTag) {
        ProcessRequirements incoming = new ProcessRequirements();
        incoming.readFromNBT(requirementsTag);
        pendingProcessRequirements.readFromNBT(incoming.writeToNBT());
        pendingRuntimeGraph.readFromNBT(processGraph.writeToNBT());
        factoryMode = MODE_OUTPUT;
        ioCycleTicks = 0;
        cancelCurrentProcessForOutput();
        getBaseMetaTileEntity().markDirty();
    }

    public void exportProcessRawMaterials(EntityPlayer player) {
        RawMaterialExportPlan plan = buildRawMaterialExportPlan();
        for (String nodeName : plan.oreDictionaryNodes) {
            GTUtility.sendChatToPlayer(
                player,
                EnumChatFormatting.YELLOW + nodeName
                    + ":"
                    + tr("superfactory.machine.super_integrated_factory.chat.raw_material_ore_manual"));
        }
        if (plan.items.isEmpty() && plan.fluids.isEmpty()) {
            if (!plan.oreDictionaryNodes.isEmpty()) {
                sendProcessCanvasStatus(
                    player,
                    tr("superfactory.machine.super_integrated_factory.chat.raw_material_ore_notice"),
                    0xFFFFFF77);
                return;
            }
            sendProcessCanvasStatus(
                player,
                tr("superfactory.machine.super_integrated_factory.chat.raw_material_none"),
                0xFFFF7777);
            return;
        }
        List<RawMaterialMarkerTarget> targets = collectRawMaterialMarkerTargets();
        if (targets.isEmpty()) {
            sendProcessCanvasStatus(
                player,
                tr("superfactory.machine.super_integrated_factory.chat.raw_material_no_me_hatch"),
                0xFFFF7777);
            return;
        }
        int itemCapacity = 0;
        int fluidCapacity = 0;
        for (RawMaterialMarkerTarget target : targets) {
            itemCapacity += target.itemCapacity();
            fluidCapacity += target.fluidCapacity();
        }
        if (itemCapacity < plan.items.size() || fluidCapacity < plan.fluids.size()) {
            sendProcessCanvasStatus(
                player,
                tr("superfactory.machine.super_integrated_factory.chat.raw_material_capacity_insufficient") + " "
                    + plan.items.size()
                    + "/"
                    + itemCapacity
                    + " "
                    + plan.fluids.size()
                    + "/"
                    + fluidCapacity,
                0xFFFF7777);
            return;
        }
        for (RawMaterialMarkerTarget target : targets) {
            target.clear();
        }
        int itemIndex = 0;
        int fluidIndex = 0;
        for (RawMaterialMarkerTarget target : targets) {
            while (itemIndex < plan.items.size() && target.addItem(plan.items.get(itemIndex))) {
                itemIndex++;
            }
            while (fluidIndex < plan.fluids.size() && target.addFluid(plan.fluids.get(fluidIndex))) {
                fluidIndex++;
            }
        }
        String successMessage = tr("superfactory.machine.super_integrated_factory.chat.raw_material_exported") + ": "
            + plan.items.size()
            + " "
            + tr("superfactory.machine.super_integrated_factory.chat.raw_material_items")
            + ", "
            + plan.fluids.size()
            + " "
            + tr("superfactory.machine.super_integrated_factory.chat.raw_material_fluids");
        if (!plan.oreDictionaryNodes.isEmpty()) {
            successMessage += ", " + tr("superfactory.machine.super_integrated_factory.chat.raw_material_ore_notice");
        }
        sendProcessCanvasStatus(player, successMessage, 0xFF75D17C);
        getBaseMetaTileEntity().markDirty();
    }

    private void sendProcessCanvasStatus(EntityPlayer player, String message, int color) {
        if (player instanceof EntityPlayerMP playerMP) {
            NetworkLoader.INSTANCE.sendTo(new MessageProcessCanvasStatus(message, color), playerMP);
        } else if (player != null) {
            GTUtility.sendChatToPlayer(player, message);
        }
    }

    private RawMaterialExportPlan buildRawMaterialExportPlan() {
        RawMaterialExportPlan plan = new RawMaterialExportPlan();
        List<ProcessNode> relevantNodes = findRawMaterialRelevantNodes();
        Set<Integer> relevantIds = new HashSet<>();
        for (ProcessNode node : relevantNodes) {
            relevantIds.add(node.id);
        }
        for (ProcessNode node : relevantNodes) {
            for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
                ItemStack input = node.inputHandler.getStackInSlot(slot);
                if (input == null) {
                    continue;
                }
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(input);
                boolean suppliedInternally = fluid == null ? hasDirectItemProducer(node.id, input, relevantIds)
                    : hasDirectFluidProducer(node.id, fluid, relevantIds);
                if (suppliedInternally) {
                    continue;
                }
                if (fluid != null && fluid.getFluid() != null) {
                    addRawMaterialFluid(plan, fluid);
                } else if (node.hasInputVariants(slot)) {
                    addRawMaterialOreWarning(plan, safeNodeName(node));
                } else {
                    addRawMaterialItem(plan, input);
                }
            }
        }
        return plan;
    }

    private List<ProcessNode> findRawMaterialRelevantNodes() {
        List<ProcessNode> relevant = new ArrayList<>();
        for (ProcessNode node : processGraph.nodes) {
            if (node.endNode) {
                collectRawMaterialConnectedNodes(node.id, relevant);
            }
        }
        if (!relevant.isEmpty()) {
            return relevant;
        }
        for (ProcessNode node : processGraph.nodes) {
            if (node.locked && node.lastRecipeCheckPassed) {
                relevant.add(node);
            }
        }
        return relevant;
    }

    private void collectRawMaterialConnectedNodes(int nodeId, List<ProcessNode> relevant) {
        ProcessNode node = processGraph.findNode(nodeId);
        if (node == null || relevant.contains(node)) {
            return;
        }
        relevant.add(node);
        for (ProcessEdge edge : processGraph.edges) {
            if (edge.fromNodeId == nodeId) {
                collectRawMaterialConnectedNodes(edge.toNodeId, relevant);
            }
            if (edge.toNodeId == nodeId) {
                collectRawMaterialConnectedNodes(edge.fromNodeId, relevant);
            }
        }
    }

    private boolean hasDirectItemProducer(int consumerNodeId, ItemStack input, Set<Integer> relevantIds) {
        for (ProcessEdge edge : processGraph.edges) {
            if (edge.toNodeId != consumerNodeId || !relevantIds.contains(edge.fromNodeId)) {
                continue;
            }
            ProcessNode producer = processGraph.findNode(edge.fromNodeId);
            if (producer == null) {
                continue;
            }
            for (int outputSlot = 0; outputSlot < producer.outputHandler.getSlots(); outputSlot++) {
                ItemStack output = producer.outputHandler.getStackInSlot(outputSlot);
                if (output != null && !isFluidDisplay(output) && itemMatches(input, output)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasDirectFluidProducer(int consumerNodeId, FluidStack input, Set<Integer> relevantIds) {
        for (ProcessEdge edge : processGraph.edges) {
            if (edge.toNodeId != consumerNodeId || !relevantIds.contains(edge.fromNodeId)) {
                continue;
            }
            ProcessNode producer = processGraph.findNode(edge.fromNodeId);
            if (producer == null) {
                continue;
            }
            for (int outputSlot = 0; outputSlot < producer.outputHandler.getSlots(); outputSlot++) {
                FluidStack output = GTUtility
                    .getFluidFromDisplayStack(producer.outputHandler.getStackInSlot(outputSlot));
                if (output != null && output.isFluidEqual(input)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addRawMaterialItem(RawMaterialExportPlan plan, ItemStack stack) {
        for (ItemStack existing : plan.items) {
            if (GTUtility.areStacksEqual(existing, stack, true)) {
                return;
            }
        }
        plan.items.add(copyItemAmount(stack, 1));
    }

    private void addRawMaterialFluid(RawMaterialExportPlan plan, FluidStack stack) {
        for (FluidStack existing : plan.fluids) {
            if (existing != null && existing.isFluidEqual(stack)) {
                return;
            }
        }
        plan.fluids.add(copyFluidAmount(stack, 1));
    }

    private void addRawMaterialOreWarning(RawMaterialExportPlan plan, String nodeName) {
        if (!plan.oreDictionaryNodes.contains(nodeName)) {
            plan.oreDictionaryNodes.add(nodeName);
        }
    }

    private List<RawMaterialMarkerTarget> collectRawMaterialMarkerTargets() {
        List<RawMaterialMarkerTarget> combinedTargets = new ArrayList<>();
        List<RawMaterialMarkerTarget> separateTargets = new ArrayList<>();
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (IDualInputHatch hatch : mDualInputHatches) {
            if (hatch != null && seen.add(hatch)) {
                RawMaterialMarkerTarget target = RawMaterialMarkerTarget.tryCreate(hatch);
                if (target != null) {
                    (target.isCombined() ? combinedTargets : separateTargets).add(target);
                }
            }
        }
        for (MTEHatchInputBus bus : mInputBusses) {
            if (bus != null && seen.add(bus)) {
                RawMaterialMarkerTarget target = RawMaterialMarkerTarget.tryCreate(bus);
                if (target != null) {
                    (target.isCombined() ? combinedTargets : separateTargets).add(target);
                }
            }
        }
        for (MTEHatchInput hatch : mInputHatches) {
            if (hatch != null && seen.add(hatch)) {
                RawMaterialMarkerTarget target = RawMaterialMarkerTarget.tryCreate(hatch);
                if (target != null) {
                    (target.isCombined() ? combinedTargets : separateTargets).add(target);
                }
            }
        }
        combinedTargets.addAll(separateTargets);
        return combinedTargets;
    }

    public static MTESuperIntegratedFactory getClientEditingFactory() {
        return clientEditingFactory;
    }

    public static void setClientEditingFactory(MTESuperIntegratedFactory factory) {
        clientEditingFactory = factory;
    }

    public Object getActiveProcessGui() {
        return activeProcessGui;
    }

    public void setActiveProcessGui(Object activeProcessGui) {
        this.activeProcessGui = activeProcessGui;
    }

    public void applyRecipeToNode(int nodeId, NBTTagCompound recipeTag) {
        ProcessNode node = processGraph.findNode(nodeId);
        if (node == null || node.locked) {
            return;
        }
        loadHandlerItems(node.inputHandler, recipeTag.getCompoundTag("Inputs"));
        loadInputVariants(node, recipeTag);
        loadHandlerItems(node.outputHandler, recipeTag.getCompoundTag("Outputs"));
        loadOutputChances(node, recipeTag);
        if (recipeTag.hasKey("NonConsumables")) {
            loadHandlerItems(node.nonConsumableHandler, recipeTag.getCompoundTag("NonConsumables"));
        }
        if (recipeTag.hasKey("Machine")) {
            node.machineHandler.setStackInSlot(0, ItemStack.loadItemStackFromNBT(recipeTag.getCompoundTag("Machine")));
        }
        node.durationTicks = Math.max(0, recipeTag.getInteger("DurationTicks"));
        node.euPerTick = Math.max(0L, recipeTag.getLong("EUt"));
        node.baseDurationTicks = Math.max(
            0,
            recipeTag.hasKey("BaseDurationTicks") ? recipeTag.getInteger("BaseDurationTicks") : node.durationTicks);
        node.baseEuPerTick = Math.max(0L, recipeTag.hasKey("BaseEUt") ? recipeTag.getLong("BaseEUt") : node.euPerTick);
        node.recipeHandlerName = recipeTag.getString("RecipeHandlerName");
        node.recipeMapName = recipeTag.getString("RecipeMapName");
        node.recipeFingerprint = recipeTag.getString("RecipeFingerprint");
        node.lastRecipeCheckPassed = node.recipeFingerprint != null
            && node.recipeFingerprint.equals(buildNodeFingerprint(node));
        node.locked = false;
    }

    private void loadOutputChances(ProcessNode node, NBTTagCompound recipeTag) {
        node.resetOutputChances();
        if (!recipeTag.hasKey("OutputChances", Constants.NBT.TAG_INT_ARRAY)) {
            return;
        }
        int[] chances = recipeTag.getIntArray("OutputChances");
        for (int slot = 0; slot < chances.length && slot < ProcessNode.OUTPUT_SLOTS; slot++) {
            node.setOutputChance(slot, chances[slot]);
        }
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
        applyRecipeOutputChances(outputs, recipe, null);
        if (recipe.mSpecialItems instanceof ItemStack stack) {
            nonConsumables.setStackInSlot(0, stack.copy());
        } else if (recipe.mSpecialItems instanceof ItemStack[]stacks) {
            fillHandler(nonConsumables, stacks);
        }
        return buildRecipeFingerprint(
            inputs,
            outputs,
            nonConsumables,
            buildOutputChanceArray(outputs, recipe),
            recipe.mDuration,
            recipe.mEUt);
    }

    public static String buildRecipeFingerprint(com.gtnewhorizons.modularui.api.forge.ItemStackHandler inputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler nonConsumables, int[] outputChances, int duration,
        long euPerTick) {
        return "t=" + duration
            + ";e="
            + euPerTick
            + ";i="
            + handlerFingerprint(inputs)
            + ";o="
            + handlerFingerprint(outputs)
            + ";oc="
            + java.util.Arrays.toString(outputChances == null ? defaultOutputChances() : outputChances)
            + ";nc="
            + handlerFingerprint(nonConsumables);
    }

    public static String buildRecipeFingerprint(com.gtnewhorizons.modularui.api.forge.ItemStackHandler inputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler nonConsumables, int duration, long euPerTick) {
        return buildRecipeFingerprint(inputs, outputs, nonConsumables, defaultOutputChances(), duration, euPerTick);
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

    public static void applyRecipeOutputChances(com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        GTRecipe recipe, ProcessNode node) {
        if (node != null) {
            node.resetOutputChances();
        }
        if (outputs == null || recipe == null || recipe.mChances == null) {
            return;
        }
        boolean[] usedSlots = new boolean[outputs.getSlots()];
        for (int recipeOutput = 0; recipeOutput < recipe.mOutputs.length
            && recipeOutput < recipe.mChances.length; recipeOutput++) {
            ItemStack stack = recipe.mOutputs[recipeOutput];
            if (stack == null) {
                continue;
            }
            int slot = findMatchingOutputSlot(outputs, stack, usedSlots);
            if (slot >= 0 && node != null) {
                node.setOutputChance(slot, normalizeRecipeChance(recipe.mChances[recipeOutput]));
                usedSlots[slot] = true;
            }
        }
    }

    private static int[] buildOutputChanceArray(com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        GTRecipe recipe) {
        int[] chances = defaultOutputChances();
        if (outputs == null || recipe == null || recipe.mChances == null) {
            return chances;
        }
        boolean[] usedSlots = new boolean[outputs.getSlots()];
        for (int recipeOutput = 0; recipeOutput < recipe.mOutputs.length
            && recipeOutput < recipe.mChances.length; recipeOutput++) {
            ItemStack stack = recipe.mOutputs[recipeOutput];
            if (stack == null) {
                continue;
            }
            int slot = findMatchingOutputSlot(outputs, stack, usedSlots);
            if (slot >= 0) {
                chances[slot] = normalizeRecipeChance(recipe.mChances[recipeOutput]);
                usedSlots[slot] = true;
            }
        }
        return chances;
    }

    private static int normalizeRecipeChance(int chance) {
        return chance <= 0 ? 10000 : Math.max(0, Math.min(10000, chance));
    }

    private static int[] defaultOutputChances() {
        int[] chances = new int[ProcessNode.OUTPUT_SLOTS];
        java.util.Arrays.fill(chances, 10000);
        return chances;
    }

    private static int findMatchingOutputSlot(com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        ItemStack stack) {
        return findMatchingOutputSlot(outputs, stack, null);
    }

    private static int findMatchingOutputSlot(com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        ItemStack stack, boolean[] usedSlots) {
        for (int slot = 0; slot < outputs.getSlots(); slot++) {
            if (usedSlots != null && slot < usedSlots.length && usedSlots[slot]) {
                continue;
            }
            ItemStack existing = outputs.getStackInSlot(slot);
            if (existing != null && GTUtility.areStacksEqual(existing, stack, true)) {
                return slot;
            }
        }
        return -1;
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

    private void readSyncedProcessRequirements(ProcessRequirements syncedRequirements) {
        processRequirements.readFromNBT(syncedRequirements.writeToNBT());
    }

    private void writeProcessRequirementsPacket(PacketBuffer buffer, ProcessRequirements requirements) {
        try {
            buffer.writeNBTTagCompoundToBuffer(requirements.writeToNBT());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ProcessRequirements readProcessRequirementsPacket(PacketBuffer buffer) {
        ProcessRequirements requirements = new ProcessRequirements();
        try {
            NBTTagCompound tag = buffer.readNBTTagCompoundFromBuffer();
            if (tag != null) {
                requirements.readFromNBT(tag);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return requirements;
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

    private boolean isWirelessModeEnabled() {
        return inputValues()[INDEX_WIRELESS] > 0D;
    }

    private String getActiveNodeCountLine() {
        if (factoryMode != MODE_RUNNING) {
            return "";
        }
        if (!mMachine) {
            return tr("superfactory.machine.super_integrated_factory.gui.active_nodes") + ": "
                + EnumChatFormatting.RED
                + tr("superfactory.machine.super_integrated_factory.gui.structure_failed");
        }
        return tr("superfactory.machine.super_integrated_factory.gui.active_nodes") + ": "
            + EnumChatFormatting.AQUA
            + countActiveNodeIds();
    }

    private int countActiveNodeIds() {
        List<Integer> activeNodeIds = new ArrayList<>();
        for (RunningJob job : runningJobs) {
            if (!activeNodeIds.contains(job.nodeId)) {
                activeNodeIds.add(job.nodeId);
            }
        }
        return activeNodeIds.size();
    }

    private String getMachineModeLine() {
        return tr("superfactory.machine.super_integrated_factory.gui.machine_mode") + ": "
            + modeColor(factoryMode)
            + getModeDisplayName();
    }

    private String getIoCycleLine() {
        if (factoryMode != MODE_INPUT && factoryMode != MODE_OUTPUT) {
            return "";
        }
        return tr("superfactory.machine.super_integrated_factory.gui.cycle") + ": "
            + EnumChatFormatting.AQUA
            + ioCycleTicks
            + EnumChatFormatting.GRAY
            + " / 20";
    }

    private void addRequirementDisplayWidgets(DynamicPositionedColumn screenElements) {
        boolean hasNc = false;
        for (ProcessRequirements.ItemDemand demand : processRequirements.nonConsumables) {
            if (demand.missing() > 0 && demand.stack != null) {
                hasNc = true;
                break;
            }
        }
        boolean hasStartupItems = false;
        for (ProcessRequirements.ItemDemand demand : processRequirements.startupItems) {
            if (demand.missing() > 0 && demand.stack != null) {
                hasStartupItems = true;
                break;
            }
        }
        boolean hasStartupFluids = false;
        for (ProcessRequirements.FluidDemand demand : processRequirements.startupFluids) {
            if (demand.missing() > 0 && demand.stack != null) {
                hasStartupFluids = true;
                break;
            }
        }
        boolean hasMaps = false;
        for (ProcessRequirements.RecipeMapDemand demand : processRequirements.recipeMaps) {
            if (demand.missing() > 0) {
                hasMaps = true;
                break;
            }
        }
        if (!hasNc && !hasStartupItems && !hasStartupFluids && !hasMaps) {
            screenElements.widget(
                new TextWidget(
                    EnumChatFormatting.GREEN
                        + tr("superfactory.machine.super_integrated_factory.gui.requirements_satisfied"))
                            .setDefaultColor(Color.WHITE.normal));
            return;
        }
        if (hasNc) {
            screenElements.widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.gui.nc_items") + ":")
                    .setDefaultColor(Color.WHITE.normal));
        }
        for (ProcessRequirements.ItemDemand demand : processRequirements.nonConsumables) {
            int missing = demand.missing();
            if (missing > 0 && demand.stack != null) {
                screenElements.widget(
                    new TextWidget(
                        EnumChatFormatting.YELLOW + formatRequirementStackName(demand.stack) + " x" + missing)
                            .setDefaultColor(Color.WHITE.normal));
            }
        }
        if (hasStartupItems || hasStartupFluids) {
            screenElements.widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.gui.startup_materials") + ":")
                    .setDefaultColor(Color.WHITE.normal));
        }
        for (ProcessRequirements.ItemDemand demand : processRequirements.startupItems) {
            int missing = demand.missing();
            if (missing > 0 && demand.stack != null) {
                screenElements.widget(
                    new TextWidget(
                        EnumChatFormatting.YELLOW + formatRequirementStackName(demand.stack) + " x" + missing)
                            .setDefaultColor(Color.WHITE.normal));
            }
        }
        for (ProcessRequirements.FluidDemand demand : processRequirements.startupFluids) {
            int missing = demand.missing();
            if (missing > 0 && demand.stack != null) {
                screenElements.widget(
                    new TextWidget(EnumChatFormatting.YELLOW + demand.stack.getLocalizedName() + " x" + missing + "L")
                        .setDefaultColor(Color.WHITE.normal));
            }
        }
        if (hasMaps) {
            screenElements.widget(
                new TextWidget(tr("superfactory.machine.super_integrated_factory.gui.recipe_hosts") + ":")
                    .setDefaultColor(Color.WHITE.normal));
        }
        for (ProcessRequirements.RecipeMapDemand demand : processRequirements.recipeMaps) {
            int missing = demand.missing();
            if (missing > 0) {
                screenElements.widget(
                    new TextWidget(EnumChatFormatting.YELLOW + demand.displayName + " x" + missing)
                        .setDefaultColor(Color.WHITE.normal));
            }
        }
    }

    private String getTotalEnergyStatusLine() {
        if (factoryMode != MODE_RUNNING) {
            return "";
        }
        return tr("superfactory.machine.super_integrated_factory.gui.total_eut") + ": "
            + EnumChatFormatting.RED
            + formatPowerUsageDisplay(totalRunningEuPerTick());
    }

    private void addRunningNodeWidgets(DynamicPositionedColumn screenElements) {
        for (int i = 0; i < RUNTIME_OUTPUT_ESTIMATE_LINE_LIMIT; i++) {
            final int line = i;
            screenElements.widget(
                TextWidget.dynamicString(() -> getRunningNodeLine(line))
                    .setDefaultColor(Color.WHITE.normal));
        }
    }

    private String getRunningNodeLine(int index) {
        if (factoryMode != MODE_RUNNING || index < 0 || index >= runtimeOutputEstimateLines.size()) {
            return "";
        }
        return runtimeOutputEstimateLines.get(index);
    }

    private String getModeDisplayName() {
        return switch (factoryMode) {
            case MODE_INPUT -> tr("superfactory.machine.super_integrated_factory.mode.input");
            case MODE_RUNNING -> tr("superfactory.machine.super_integrated_factory.mode.running");
            case MODE_OUTPUT -> tr("superfactory.machine.super_integrated_factory.mode.output");
            default -> tr("superfactory.machine.super_integrated_factory.mode.standby");
        };
    }

    private String formatRequirementStackName(ItemStack stack) {
        if (stack != null && GTUtility.isAnyIntegratedCircuit(stack)) {
            return tr("superfactory.machine.super_integrated_factory.gui.programmed_circuit") + " "
                + stack.getItemDamage();
        }
        return stack == null ? "" : stack.getDisplayName();
    }

    private EnumChatFormatting modeColor(int mode) {
        return switch (mode) {
            case MODE_INPUT -> EnumChatFormatting.GREEN;
            case MODE_RUNNING -> EnumChatFormatting.BLUE;
            case MODE_OUTPUT -> EnumChatFormatting.GOLD;
            default -> EnumChatFormatting.RED;
        };
    }

    private String formatPowerUsageDisplay() {
        return formatPowerUsageDisplay(mMaxProgresstime > 0 ? Math.abs(lEUt) : 0);
    }

    private String formatPowerUsageDisplay(long euPerTick) {
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

    private long totalRunningEuPerTick() {
        long totalEu = 0L;
        for (RunningJob job : runningJobs) {
            ProcessNode node = runtimeGraph.findNode(job.nodeId);
            if (node != null) {
                totalEu = safeAddLong(totalEu, safeMultiply(getJobEuPerTick(job, node), Math.max(1, job.parallel)));
            }
        }
        return totalEu;
    }

    private void prepareIdleRuntimePulse() {
        mMaxProgresstime = 20;
        mProgresstime = Math.max(0, Math.min(19, ioCycleTicks));
        lEUt = 0L;
        mEUt = 0;
        mEfficiency = getMaxEfficiency(null);
    }

    private void clearMachineWorkDisplay() {
        mMaxProgresstime = 0;
        mProgresstime = 0;
        lEUt = 0L;
        mEUt = 0;
    }

    /*
     * INPUT mode only collects gate resources. Startup resources are consumed once and moved into internal buffers when
     * all requirements are satisfied; non-consumables and host machines stay in ProcessRequirements until OUTPUT
     * returns
     * them. This keeps runtime recipes from accidentally consuming controller/NC items.
     */
    private void processInputMode() {
        startRecipeProcessing();
        try {
            for (ProcessRequirements.ItemDemand demand : processRequirements.nonConsumables) {
                while (demand.missing() > 0 && consumeNonConsumable(demand)) {
                    demand.stored++;
                }
            }
            for (ProcessRequirements.ItemDemand demand : processRequirements.startupItems) {
                while (demand.missing() > 0 && consumeStartupItem(demand)) {
                    demand.stored++;
                }
            }
            for (ProcessRequirements.FluidDemand demand : processRequirements.startupFluids) {
                int consumed = consumeStartupFluid(demand);
                if (consumed > 0) {
                    demand.stored = Math.min(demand.required, demand.stored + consumed);
                }
            }
            for (ProcessRequirements.RecipeMapDemand demand : processRequirements.recipeMaps) {
                while (demand.missing() > 0) {
                    ItemStack controller = consumeRecipeMapMachine(demand.recipeMapName);
                    if (controller == null) {
                        break;
                    }
                    processRequirements.storedMachines.add(controller);
                    demand.stored++;
                }
            }
        } finally {
            endRecipeProcessing();
        }
        if (allRequirementsSatisfied()) {
            initializeRunningRuntime();
            factoryMode = MODE_RUNNING;
            ioCycleTicks = 0;
        }
        getBaseMetaTileEntity().markDirty();
    }

    /*
     * RUNNING mode is a virtual machine scheduler. It advances already-started jobs first, then starts new node jobs
     * from
     * internal buffers before falling back to live input hatches/busses. Node timing and parallel values are fixed by
     * the
     * locked graph snapshot; the machine parameters do not mutate these jobs at runtime.
     */
    private void processRunningMode(long tick) {
        boolean debugRuntime = tick - lastRuntimeDebugLogTick >= 20L;
        if (!getBaseMetaTileEntity().isAllowedToWork()) {
            discardRunningJobsForPowerLoss();
            clearMachineWorkDisplay();
            getBaseMetaTileEntity().markDirty();
            return;
        }
        flushOutputBuffers();
        advanceRunningJobs();
        if (!isWirelessModeEnabled() && !runningJobs.isEmpty() && !canSustainWiredRuntimePower()) {
            stopMachine(ShutDownReasonRegistry.POWER_LOSS);
            clearMachineWorkDisplay();
            getBaseMetaTileEntity().markDirty();
            return;
        }
        scheduleRunnableNodes(debugRuntime);
        updateRuntimeProgressDisplay();
        runtimeOutputEstimateLines = buildActiveRuntimeOutputLines();
        if (debugRuntime) {
            lastRuntimeDebugLogTick = tick;
            SuperFactory.LOG.info(
                "[Super Integrated Factory/Runtime] tick={}, active={}, internalItems={}, internalFluids={}, outputItems={}, outputFluids={}",
                tick,
                runningJobs.size(),
                describeBufferedItemList(internalItems),
                describeBufferedFluidList(internalFluids),
                describeBufferedItemList(outputItems),
                describeBufferedFluidList(outputFluids));
        }
        getBaseMetaTileEntity().markDirty();
    }

    /*
     * OUTPUT mode is the only state allowed to dismantle runtime state. It repeatedly exports products, aborts active
     * jobs by returning their consumed inputs, and only promotes a pending graph after every local buffer is empty.
     */
    private void processOutputMode() {
        abortRunningJobsToInternalCache();
        flushOutputBuffers();
        moveAllInternalToOutput();
        flushOutputBuffers();
        for (ProcessRequirements.ItemDemand demand : processRequirements.nonConsumables) {
            while (demand.stored > 0 && demand.stack != null) {
                ItemStack output = demand.stack.copy();
                output.stackSize = 1;
                if (!addOutput(output)) {
                    break;
                }
                demand.stored--;
            }
        }
        for (ProcessRequirements.ItemDemand demand : processRequirements.startupItems) {
            while (demand.stored > 0 && demand.stack != null) {
                ItemStack output = demand.stack.copy();
                output.stackSize = 1;
                if (!addOutput(output)) {
                    break;
                }
                demand.stored--;
            }
        }
        for (ProcessRequirements.FluidDemand demand : processRequirements.startupFluids) {
            if (demand.stored > 0 && demand.stack != null) {
                FluidStack output = demand.stack.copy();
                output.amount = demand.stored;
                int filled = tryFlushFluidOutput(output);
                if (filled > 0) {
                    demand.stored = Math.max(0, demand.stored - filled);
                }
            }
        }
        Iterator<ItemStack> machineIterator = processRequirements.storedMachines.iterator();
        while (machineIterator.hasNext()) {
            ItemStack machine = machineIterator.next();
            ItemStack output = machine.copy();
            output.stackSize = 1;
            if (!addOutput(output)) {
                break;
            }
            machineIterator.remove();
            decrementStoredRecipeMapFor(machine);
        }
        if (hasStoredProcessRequirements()) {
            getBaseMetaTileEntity().markDirty();
            return;
        }
        unloadCurrentProcessState();
        if (pendingProcessRequirements.hasSubmittedDemands()) {
            installPendingProcessRequirements();
        } else {
            factoryMode = MODE_STANDBY;
            ioCycleTicks = 0;
        }
        getBaseMetaTileEntity().markDirty();
    }

    private void cancelCurrentProcessForOutput() {
        currentProcessStep = 0;
        totalProcessSteps = 0;
        processRequirements.nonConsumables.removeIf(demand -> demand.stored <= 0);
        processRequirements.recipeMaps.removeIf(demand -> demand.stored <= 0);
        getBaseMetaTileEntity().markDirty();
    }

    private void unloadCurrentProcessState() {
        currentProcessStep = 0;
        totalProcessSteps = 0;
        processRequirements.clear();
        runtimeGraph.readFromNBT(new ProcessGraph().writeToNBT());
        internalItems.clear();
        internalFluids.clear();
        outputItems.clear();
        outputFluids.clear();
        runningJobs.clear();
    }

    private void installPendingProcessRequirements() {
        ProcessRequirements pending = pendingProcessRequirements.copy();
        pendingProcessRequirements.clear();
        processRequirements.readFromNBT(pending.writeToNBT());
        runtimeGraph.readFromNBT(pendingRuntimeGraph.writeToNBT());
        pendingRuntimeGraph.readFromNBT(new ProcessGraph().writeToNBT());
        resetStoredRequirementProgress(processRequirements);
        clearRuntimeBuffers();
        runtimeOutputEstimateLines = new ArrayList<>();
        factoryMode = MODE_INPUT;
        ioCycleTicks = 0;
        totalProcessSteps = countSubmittedSteps();
    }

    private void clearRuntimeBuffers() {
        internalItems.clear();
        internalFluids.clear();
        outputItems.clear();
        outputFluids.clear();
        runningJobs.clear();
        throttledInternalItemOutputs.clear();
        throttledInternalFluidOutputs.clear();
        runtimeOutputEstimateLines = new ArrayList<>();
        currentProcessStep = 0;
        lEUt = 0L;
        mEUt = 0;
        mProgresstime = 0;
        mMaxProgresstime = 0;
    }

    private void resetStoredRequirementProgress(ProcessRequirements requirements) {
        requirements.storedMachines.clear();
        for (ProcessRequirements.ItemDemand demand : requirements.nonConsumables) {
            demand.stored = 0;
        }
        for (ProcessRequirements.ItemDemand demand : requirements.startupItems) {
            demand.stored = 0;
        }
        for (ProcessRequirements.FluidDemand demand : requirements.startupFluids) {
            demand.stored = 0;
        }
        for (ProcessRequirements.RecipeMapDemand demand : requirements.recipeMaps) {
            demand.stored = 0;
        }
    }

    private void initializeRunningRuntime() {
        for (ProcessRequirements.ItemDemand demand : processRequirements.startupItems) {
            if (demand.stack != null && demand.stored > 0) {
                addItemToBuffer(internalItems, demand.stack, demand.stored);
                demand.stored = 0;
            }
        }
        for (ProcessRequirements.FluidDemand demand : processRequirements.startupFluids) {
            if (demand.stack != null && demand.stored > 0) {
                addFluidToBuffer(internalFluids, demand.stack, demand.stored);
                demand.stored = 0;
            }
        }
        totalProcessSteps = runtimeGraph.nodes.size();
        currentProcessStep = 0;
    }

    private void advanceRunningJobs() {
        Iterator<RunningJob> iterator = runningJobs.iterator();
        while (iterator.hasNext()) {
            RunningJob job = iterator.next();
            job.remainingTicks--;
            if (job.remainingTicks <= 0) {
                ProcessNode node = runtimeGraph.findNode(job.nodeId);
                if (node != null) {
                    finishRunningJob(node, job.parallel);
                }
                iterator.remove();
                currentProcessStep++;
            }
        }
    }

    private void scheduleRunnableNodes(boolean debugRuntime) {
        for (ProcessNode node : buildSchedulingOrder()) {
            int effectiveParallelLimit = getEffectiveParallelLimit(node);
            int effectiveDurationTicks = getEffectiveDurationTicks(node);
            long effectiveEuPerTick = getEffectiveEuPerTick(node);
            if (!node.locked || effectiveParallelLimit <= 0 || effectiveDurationTicks <= 0) {
                if (debugRuntime) {
                    SuperFactory.LOG.info(
                        "[Super Integrated Factory/Runtime] 跳过节点: node={}, locked={}, duration={}, parallel={}",
                        describeNode(node),
                        node.locked,
                        effectiveDurationTicks,
                        effectiveParallelLimit);
                }
                continue;
            }
            if (countRunningJobsForNode(node.id) > 0) {
                if (debugRuntime) {
                    SuperFactory.LOG.info("[Super Integrated Factory/Runtime] 节点已在运行: node={}", describeNode(node));
                }
                continue;
            }
            startRecipeProcessing();
            try {
                int parallel = getRunnableParallel(node, effectiveParallelLimit, debugRuntime);
                if (parallel <= 0) {
                    continue;
                }
                if (canStartNode(node, parallel, debugRuntime)) {
                    RunningJob job = new RunningJob(node.id, parallel, effectiveDurationTicks, effectiveEuPerTick);
                    long jobEuPerTick = safeMultiply(effectiveEuPerTick, Math.max(1L, parallel));
                    long jobEnergy = safeMultiply(jobEuPerTick, Math.max(1L, job.durationTicks));
                    long totalEuAfterStart = safeAddLong(totalRunningEuPerTick(), jobEuPerTick);
                    if (!isWirelessModeEnabled() && !canStartWiredRuntimeJob(totalEuAfterStart)) {
                        if (debugRuntime) {
                            SuperFactory.LOG.info(
                                "[Super Integrated Factory/Runtime] 节点功率不足: node={}, parallel={}, need={} EU/t, stored={}, maxInput={}",
                                describeNode(node),
                                parallel,
                                totalEuAfterStart,
                                getEUVar(),
                                getMaxInputEnergy());
                        }
                        continue;
                    }
                    boolean energyReserved = reserveRuntimeEnergy(jobEnergy);
                    job.reservedEnergy = energyReserved && isWirelessModeEnabled() ? jobEnergy : 0L;
                    if (energyReserved && consumeNodeInputs(node, job, parallel)) {
                        runningJobs.add(job);
                        if (debugRuntime) {
                            SuperFactory.LOG.info(
                                "[Super Integrated Factory/Runtime] 启动节点: node={}, parallel={}, duration={}, consumedItems={}, consumedFluids={}",
                                describeNode(node),
                                parallel,
                                job.durationTicks,
                                describeItemList(job.consumedItems),
                                describeFluidList(job.consumedFluids));
                        }
                    } else if (debugRuntime) {
                        refundRuntimeEnergy(job.reservedEnergy);
                        job.reservedEnergy = 0L;
                        SuperFactory.LOG.info("[Super Integrated Factory/Runtime] 节点扣料失败: node={}", describeNode(node));
                    } else {
                        refundRuntimeEnergy(job.reservedEnergy);
                    }
                }
            } finally {
                endRecipeProcessing();
            }
        }
    }

    private List<ProcessNode> buildSchedulingOrder() {
        ArrayList<ProcessNode> nodes = new ArrayList<>(runtimeGraph.nodes);
        Map<Integer, Integer> terminalDistanceCache = new LinkedHashMap<>();
        nodes.sort(
            Comparator.comparing((ProcessNode node) -> consumesAvailableInternalInput(node) ? 0 : 1)
                .thenComparingInt(node -> distanceToTerminal(node, terminalDistanceCache))
                .thenComparingInt(node -> node.id));
        return nodes;
    }

    private boolean consumesAvailableInternalInput(ProcessNode node) {
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack input = node.inputHandler.getStackInSlot(slot);
            if (input == null) {
                continue;
            }
            if (isFluidDisplay(input)) {
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(input);
                if (countFluidInBuffer(internalFluids, fluid) >= getStackAmount(input)) {
                    return true;
                }
            } else if (countItemInBuffer(internalItems, input) >= getStackAmount(input)) {
                return true;
            }
        }
        return false;
    }

    private int distanceToTerminal(ProcessNode node, Map<Integer, Integer> cache) {
        return distanceToTerminal(node, cache, new HashSet<>());
    }

    private int distanceToTerminal(ProcessNode node, Map<Integer, Integer> cache, Set<Integer> visiting) {
        Integer cached = cache.get(node.id);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(node.id)) {
            return 1000;
        }
        int best = node.endNode || !hasOutgoingEdge(node.id) ? 0 : 1000;
        for (ProcessEdge edge : runtimeGraph.edges) {
            if (edge.fromNodeId != node.id || edge.toNodeId == node.id) {
                continue;
            }
            ProcessNode next = runtimeGraph.findNode(edge.toNodeId);
            if (next != null) {
                best = Math.min(best, 1 + distanceToTerminal(next, cache, visiting));
            }
        }
        visiting.remove(node.id);
        cache.put(node.id, best);
        return best;
    }

    private boolean hasOutgoingEdge(int nodeId) {
        for (ProcessEdge edge : runtimeGraph.edges) {
            if (edge.fromNodeId == nodeId && edge.toNodeId != nodeId) {
                return true;
            }
        }
        return false;
    }

    private int getRunnableParallel(ProcessNode node, int parallelLimit, boolean debugRuntime) {
        long runnable = Math.max(1, parallelLimit);
        boolean hasInput = false;
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack stack = node.inputHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            hasInput = true;
            long perRun = Math.max(1L, getStackAmount(stack));
            long available = isFluidDisplay(stack) ? availableFluidAmount(GTUtility.getFluidFromDisplayStack(stack))
                : availableItemAmount(stack);
            runnable = Math.min(runnable, available / perRun);
            if (runnable <= 0L) {
                if (debugRuntime) {
                    if (isFluidDisplay(stack)) {
                        logMissingFluid(true, node, slot, perRun, available, GTUtility.getFluidFromDisplayStack(stack));
                    } else {
                        logMissingItem(true, node, slot, perRun, available, stack);
                    }
                }
                return 0;
            }
        }
        return hasInput ? (int) Math.min(Integer.MAX_VALUE, runnable) : Math.max(1, parallelLimit);
    }

    private int countRunningJobsForNode(int nodeId) {
        int count = 0;
        for (RunningJob job : runningJobs) {
            if (job.nodeId == nodeId) {
                count++;
            }
        }
        return count;
    }

    private int getGlobalParallelMultiplier() {
        return Math.max(1, clampInputInt(INDEX_PARALLEL));
    }

    private int getGlobalExtraOverclocks() {
        return Math.max(0, clampInputInt(INDEX_MANUAL_OVERCLOCKS));
    }

    private int getEffectiveParallelLimit(ProcessNode node) {
        return (int) Math
            .min(Integer.MAX_VALUE, safeMultiply(Math.max(1, node.parallelLimit), getGlobalParallelMultiplier()));
    }

    private int getEffectiveDurationTicks(ProcessNode node) {
        long duration = Math.max(1L, node.durationTicks);
        for (int i = 0; i < getGlobalExtraOverclocks(); i++) {
            duration = Math.max(1L, (duration + 3L) / 4L);
        }
        return (int) Math.min(Integer.MAX_VALUE, duration);
    }

    private long getEffectiveEuPerTick(ProcessNode node) {
        long euPerTick = Math.max(0L, node.euPerTick);
        for (int i = 0; i < getGlobalExtraOverclocks(); i++) {
            euPerTick = safeMultiply(euPerTick, 4L);
        }
        return euPerTick;
    }

    private long getJobEuPerTick(RunningJob job, ProcessNode node) {
        return job.euPerTick > 0L ? job.euPerTick : getEffectiveEuPerTick(node);
    }

    private boolean canStartNode(ProcessNode node, int parallel, boolean debugRuntime) {
        if (isInternalOutputThrottled(node, debugRuntime)) {
            return false;
        }
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack stack = node.inputHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            long need = safeMultiply(getStackAmount(stack), Math.max(1, parallel));
            if (isFluidDisplay(stack)) {
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
                long available = availableFluidAmount(fluid);
                if (available < need) {
                    if (debugRuntime) {
                        SuperFactory.LOG.info(
                            "[Super Integrated Factory/Runtime] 节点缺少流体: node={}, slot={}, need={}, available={}, input={}, internalFluids={}",
                            describeNode(node),
                            slot,
                            need,
                            available,
                            describeFluid(fluid),
                            describeBufferedFluidList(internalFluids));
                    }
                    return false;
                }
            } else {
                long available = availableItemAmount(stack);
                if (available < need) {
                    if (debugRuntime) {
                        SuperFactory.LOG.info(
                            "[Super Integrated Factory/Runtime] 节点缺少物品: node={}, slot={}, need={}, available={}, input={}, internalItems={}",
                            describeNode(node),
                            slot,
                            need,
                            available,
                            describeItem(stack),
                            describeBufferedItemList(internalItems));
                    }
                    return false;
                }
            }
        }
        return true;
    }

    private boolean consumeNodeInputs(ProcessNode node, RunningJob job, int parallel) {
        if (!canStartNode(node, parallel, false)) {
            return false;
        }
        ArrayList<ItemStack> stagedItems = new ArrayList<>();
        ArrayList<FluidStack> stagedFluids = new ArrayList<>();
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack stack = node.inputHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            long need = safeMultiply(getStackAmount(stack), Math.max(1, parallel));
            if (isFluidDisplay(stack)) {
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
                long remaining = consumeFluidForNode(fluid, need);
                long consumed = Math.max(0L, need - remaining);
                if (consumed > 0L) {
                    addFluidToStackList(stagedFluids, copyFluidAmount(fluid, consumed));
                }
                if (remaining > 0L) {
                    rollbackStagedInputs(stagedItems, stagedFluids);
                    return false;
                }
            } else {
                long remaining = consumeItemForNode(stack, need, stagedItems);
                if (remaining > 0L) {
                    rollbackStagedInputs(stagedItems, stagedFluids);
                    return false;
                }
            }
        }
        for (ItemStack stack : stagedItems) {
            addItemToStackList(job.consumedItems, stack);
        }
        for (FluidStack stack : stagedFluids) {
            addFluidToStackList(job.consumedFluids, stack);
        }
        return true;
    }

    private void rollbackStagedInputs(List<ItemStack> stagedItems, List<FluidStack> stagedFluids) {
        for (ItemStack stack : stagedItems) {
            addItemToBuffer(internalItems, stack, getStackAmount(stack));
        }
        for (FluidStack stack : stagedFluids) {
            addFluidToBuffer(internalFluids, stack, stack.amount);
        }
    }

    private void finishRunningJob(ProcessNode node, int parallel) {
        SuperFactory.LOG
            .info("[Super Integrated Factory/Runtime] 完成节点: node={}, parallel={}", describeNode(node), parallel);
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack stack = node.outputHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            long amount = safeMultiply(getStackAmount(stack), Math.max(1, parallel));
            if (isFluidDisplay(stack)) {
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
                if (fluid != null) {
                    FluidStack output = fluid.copy();
                    output.amount = (int) Math.min(Integer.MAX_VALUE, amount);
                    routeFluidOutput(node, output);
                }
            } else {
                long rolls = ParallelHelper
                    .calculateIntegralChancedOutputMultiplier(node.getOutputChance(slot), Math.max(1, parallel));
                if (rolls > 0) {
                    ItemStack output = stack.copy();
                    output.stackSize = (int) Math.min(Integer.MAX_VALUE, safeMultiply(getStackAmount(stack), rolls));
                    routeItemOutput(node, output);
                }
            }
        }
    }

    private void routeItemOutput(ProcessNode node, ItemStack output) {
        boolean cyclicTarget = node.endNode && graphConsumesItem(output);
        boolean internal = cyclicTarget || !node.endNode && hasDirectItemConsumer(node, output);
        SuperFactory.LOG.info(
            "[Super Integrated Factory/Runtime] 路由物品输出: node={}, output={}, internal={}, cyclicTarget={}, endNode={}",
            describeNode(node),
            describeItem(output),
            internal,
            cyclicTarget,
            node.endNode);
        if (internal) {
            addItemToBuffer(internalItems, output, getStackAmount(output));
            if (cyclicTarget) {
                spillCyclicItemOverflow(output);
            }
        } else {
            addItemToBuffer(outputItems, output, getStackAmount(output));
        }
    }

    private void routeFluidOutput(ProcessNode node, FluidStack output) {
        boolean cyclicTarget = node.endNode && graphConsumesFluid(output);
        boolean internal = cyclicTarget || !node.endNode && hasDirectFluidConsumer(node, output);
        SuperFactory.LOG.info(
            "[Super Integrated Factory/Runtime] 路由流体输出: node={}, output={}, internal={}, cyclicTarget={}, endNode={}",
            describeNode(node),
            describeFluid(output),
            internal,
            cyclicTarget,
            node.endNode);
        if (internal) {
            addFluidToBuffer(internalFluids, output, output.amount);
            if (cyclicTarget) {
                spillCyclicFluidOverflow(output);
            }
        } else {
            addFluidToBuffer(outputFluids, output, output.amount);
        }
    }

    private void spillCyclicItemOverflow(ItemStack template) {
        long reserveTarget = getCyclicItemReserveTarget(template);
        long stored = countItemInBuffer(internalItems, template);
        long overflow = Math.max(0L, stored - reserveTarget);
        if (overflow <= 0L) {
            return;
        }
        long remaining = removeItemFromBuffer(internalItems, template, overflow);
        long moved = overflow - remaining;
        if (moved > 0L) {
            addItemToBuffer(outputItems, template, moved);
        }
    }

    private void spillCyclicFluidOverflow(FluidStack template) {
        long reserveTarget = getCyclicFluidReserveTarget(template);
        long stored = countFluidInBuffer(internalFluids, template);
        long overflow = Math.max(0L, stored - reserveTarget);
        if (overflow <= 0L) {
            return;
        }
        long remaining = removeFluidFromBuffer(internalFluids, template, overflow);
        long moved = overflow - remaining;
        if (moved > 0L) {
            addFluidToBuffer(outputFluids, template, moved);
        }
    }

    private long getCyclicItemReserveTarget(ItemStack template) {
        long reserveMin = getCyclicItemReserveMin(template);
        return Math.max(1L, safeCeilMultiply(reserveMin, 3L, 2L));
    }

    private long getCyclicItemReserveMin(ItemStack template) {
        long reserve = 0L;
        for (ProcessNode node : runtimeGraph.nodes) {
            for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
                ItemStack input = node.inputHandler.getStackInSlot(slot);
                if (input != null && !isFluidDisplay(input) && itemMatches(input, template)) {
                    reserve = Math.max(reserve, safeMultiply(getStackAmount(input), getEffectiveParallelLimit(node)));
                }
            }
        }
        return Math.max(1L, reserve);
    }

    private boolean isCyclicItemTarget(ItemStack template) {
        if (template == null) {
            return false;
        }
        for (ProcessNode node : runtimeGraph.nodes) {
            if (!node.endNode) {
                continue;
            }
            for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
                ItemStack output = node.outputHandler.getStackInSlot(slot);
                if (output != null && !isFluidDisplay(output)
                    && itemMatches(template, output)
                    && graphConsumesItem(output)) {
                    return true;
                }
            }
        }
        return false;
    }

    private long getCyclicFluidReserveTarget(FluidStack template) {
        long reserveMin = getCyclicFluidReserveMin(template);
        return Math.max(1L, safeCeilMultiply(reserveMin, 3L, 2L));
    }

    private long getCyclicFluidReserveMin(FluidStack template) {
        long reserve = 0L;
        for (ProcessNode node : runtimeGraph.nodes) {
            for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
                FluidStack input = GTUtility.getFluidFromDisplayStack(node.inputHandler.getStackInSlot(slot));
                if (input != null && input.isFluidEqual(template)) {
                    reserve = Math
                        .max(reserve, safeMultiply(Math.max(1L, input.amount), getEffectiveParallelLimit(node)));
                }
            }
        }
        return Math.max(1L, reserve);
    }

    private boolean isCyclicFluidTarget(FluidStack template) {
        if (template == null) {
            return false;
        }
        for (ProcessNode node : runtimeGraph.nodes) {
            if (!node.endNode) {
                continue;
            }
            for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
                FluidStack output = GTUtility.getFluidFromDisplayStack(node.outputHandler.getStackInSlot(slot));
                if (output != null && output.isFluidEqual(template) && graphConsumesFluid(output)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean graphConsumesItem(ItemStack output) {
        for (ProcessNode node : runtimeGraph.nodes) {
            if (nodeConsumesItem(node, output)) {
                return true;
            }
        }
        return false;
    }

    private boolean graphConsumesFluid(FluidStack output) {
        for (ProcessNode node : runtimeGraph.nodes) {
            if (nodeConsumesFluid(node, output)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInternalOutputThrottled(ProcessNode node, boolean debugRuntime) {
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack output = node.outputHandler.getStackInSlot(slot);
            if (output == null) {
                continue;
            }
            FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
            if (fluid != null) {
                if (shouldThrottleInternalFluidOutput(node, fluid, getStackAmount(output), debugRuntime)) {
                    return true;
                }
            } else if (shouldThrottleInternalItemOutput(node, output, getStackAmount(output), debugRuntime)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldThrottleInternalItemOutput(ProcessNode node, ItemStack output, long perRun,
        boolean debugRuntime) {
        if (node.endNode || !hasDirectItemConsumer(node, output)) {
            return false;
        }
        String key = node.id + ":" + itemBufferKey(output);
        long stored = countItemInBuffer(internalItems, output);
        long lowWater = getInternalItemLowWater(node, output, perRun);
        long highWater = getInternalHighWater(lowWater);
        if (throttledInternalItemOutputs.contains(key)) {
            if (stored <= lowWater) {
                throttledInternalItemOutputs.remove(key);
                return false;
            }
            logInternalThrottle(debugRuntime, node, describeItem(output), stored, lowWater, highWater);
            return true;
        }
        if (stored >= highWater) {
            throttledInternalItemOutputs.add(key);
            logInternalThrottle(debugRuntime, node, describeItem(output), stored, lowWater, highWater);
            return true;
        }
        return false;
    }

    private boolean shouldThrottleInternalFluidOutput(ProcessNode node, FluidStack output, long perRun,
        boolean debugRuntime) {
        if (node.endNode || !hasDirectFluidConsumer(node, output)) {
            return false;
        }
        String key = node.id + ":" + fluidBufferKey(output);
        long stored = countFluidInBuffer(internalFluids, output);
        long lowWater = getInternalFluidLowWater(node, output, perRun);
        long highWater = getInternalHighWater(lowWater);
        if (throttledInternalFluidOutputs.contains(key)) {
            if (stored <= lowWater) {
                throttledInternalFluidOutputs.remove(key);
                return false;
            }
            logInternalThrottle(debugRuntime, node, describeFluid(output), stored, lowWater, highWater);
            return true;
        }
        if (stored >= highWater) {
            throttledInternalFluidOutputs.add(key);
            logInternalThrottle(debugRuntime, node, describeFluid(output), stored, lowWater, highWater);
            return true;
        }
        return false;
    }

    private long getInternalItemLowWater(ProcessNode producer, ItemStack output, long perRun) {
        long lowWater = safeMultiply(Math.max(1L, perRun), getEffectiveParallelLimit(producer));
        for (ProcessEdge edge : runtimeGraph.edges) {
            if (edge.fromNodeId != producer.id) {
                continue;
            }
            ProcessNode consumer = runtimeGraph.findNode(edge.toNodeId);
            if (consumer == null) {
                continue;
            }
            for (int slot = 0; slot < consumer.inputHandler.getSlots(); slot++) {
                ItemStack input = consumer.inputHandler.getStackInSlot(slot);
                if (input != null && !isFluidDisplay(input) && itemMatches(input, output)) {
                    lowWater = Math
                        .max(lowWater, safeMultiply(getStackAmount(input), getEffectiveParallelLimit(consumer)));
                }
            }
        }
        return Math.max(1L, lowWater);
    }

    private long getInternalFluidLowWater(ProcessNode producer, FluidStack output, long perRun) {
        long lowWater = safeMultiply(Math.max(1L, perRun), getEffectiveParallelLimit(producer));
        for (ProcessEdge edge : runtimeGraph.edges) {
            if (edge.fromNodeId != producer.id) {
                continue;
            }
            ProcessNode consumer = runtimeGraph.findNode(edge.toNodeId);
            if (consumer == null) {
                continue;
            }
            for (int slot = 0; slot < consumer.inputHandler.getSlots(); slot++) {
                FluidStack input = GTUtility.getFluidFromDisplayStack(consumer.inputHandler.getStackInSlot(slot));
                if (input != null && input.isFluidEqual(output)) {
                    lowWater = Math
                        .max(lowWater, safeMultiply(Math.max(1L, input.amount), getEffectiveParallelLimit(consumer)));
                }
            }
        }
        return Math.max(1L, lowWater);
    }

    private long getInternalHighWater(long lowWater) {
        long minimumHigh = lowWater == Long.MAX_VALUE ? Long.MAX_VALUE : lowWater + 1L;
        return Math.max(minimumHigh, safeCeilMultiply(lowWater, 3L, 1L));
    }

    private void logInternalThrottle(boolean debugRuntime, ProcessNode node, String output, long stored, long lowWater,
        long highWater) {
        if (debugRuntime) {
            SuperFactory.LOG.info(
                "[Super Integrated Factory/Runtime] 上游节点水位暂停: node={}, output={}, stored={}, low={}, high={}",
                describeNode(node),
                output,
                stored,
                lowWater,
                highWater);
        }
    }

    private boolean reserveRuntimeEnergy(long totalEnergy) {
        if (!isWirelessModeEnabled() || totalEnergy <= 0L) {
            return true;
        }
        IGregTechTileEntity baseMetaTileEntity = getBaseMetaTileEntity();
        if (baseMetaTileEntity == null) {
            return false;
        }
        WirelessNetworkManager.strongCheckOrAddUser(baseMetaTileEntity.getOwnerUuid());
        UUID ownerUuid = WirelessNetworkManager.processInitialSettings(baseMetaTileEntity);
        return WirelessNetworkManager.addEUToGlobalEnergyMap(
            ownerUuid,
            BigInteger.valueOf(totalEnergy)
                .negate());
    }

    private void refundRuntimeEnergy(long totalEnergy) {
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

    private boolean hasDirectItemConsumer(ProcessNode node, ItemStack output) {
        for (ProcessEdge edge : runtimeGraph.edges) {
            if (edge.fromNodeId != node.id) {
                continue;
            }
            ProcessNode consumer = runtimeGraph.findNode(edge.toNodeId);
            if (consumer != null && nodeConsumesItem(consumer, output)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDirectFluidConsumer(ProcessNode node, FluidStack output) {
        for (ProcessEdge edge : runtimeGraph.edges) {
            if (edge.fromNodeId != node.id) {
                continue;
            }
            ProcessNode consumer = runtimeGraph.findNode(edge.toNodeId);
            if (consumer != null && nodeConsumesFluid(consumer, output)) {
                return true;
            }
        }
        return false;
    }

    private boolean nodeConsumesItem(ProcessNode node, ItemStack output) {
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack input = node.inputHandler.getStackInSlot(slot);
            if (input != null && !isFluidDisplay(input) && itemMatches(input, output)) {
                return true;
            }
        }
        return false;
    }

    private boolean nodeConsumesFluid(ProcessNode node, FluidStack output) {
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack input = node.inputHandler.getStackInSlot(slot);
            FluidStack fluid = GTUtility.getFluidFromDisplayStack(input);
            if (fluid != null && fluid.isFluidEqual(output)) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildActiveRuntimeOutputLines() {
        ArrayList<String> lines = new ArrayList<>();
        for (RunningJob job : runningJobs) {
            ProcessNode node = runtimeGraph.findNode(job.nodeId);
            if (node == null || !node.locked || job.durationTicks <= 0) {
                continue;
            }
            int progress = Math.max(0, job.durationTicks - job.remainingTicks);
            lines.add(
                EnumChatFormatting.AQUA + trimToDisplayWidth(safeNodeName(node), 78)
                    + EnumChatFormatting.WHITE
                    + " "
                    + buildRunningJobRateSummary(node, job)
                    + " "
                    + EnumChatFormatting.GRAY
                    + progress
                    + "/"
                    + job.durationTicks);
            if (lines.size() >= RUNTIME_OUTPUT_ESTIMATE_LINE_LIMIT) {
                break;
            }
        }
        if (runningJobs.size() > lines.size() && !lines.isEmpty()) {
            int folded = runningJobs.size() - lines.size() + 1;
            lines.set(
                lines.size() - 1,
                EnumChatFormatting.DARK_GRAY + "  "
                    + tr("superfactory.machine.super_proxy_factory.gui.folded_prefix")
                    + " "
                    + folded
                    + " "
                    + tr("superfactory.machine.super_proxy_factory.gui.folded_suffix"));
        }
        return lines;
    }

    private String buildRunningJobRateSummary(ProcessNode node, RunningJob job) {
        double rate = 0.0D;
        boolean fluidRate = false;
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack output = node.outputHandler.getStackInSlot(slot);
            if (output == null) {
                continue;
            }
            FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
            double chanceMultiplier = fluid == null ? node.getOutputChance(slot) / 10000.0D : 1.0D;
            rate = getStackAmount(output) * Math.max(1, job.parallel)
                * chanceMultiplier
                * 20.0D
                / Math.max(1, job.durationTicks);
            fluidRate = fluid != null;
            break;
        }
        return EnumChatFormatting.GREEN + formatRate(rate) + (fluidRate ? "L/s" : "/s");
    }

    private RuntimeOutputKind classifyRuntimeOutputKind(ProcessNode node, ItemStack output, FluidStack fluid) {
        boolean consumed = fluid != null ? hasDirectFluidConsumer(node, fluid) : hasDirectItemConsumer(node, output);
        if (node.endNode && consumed) {
            return RuntimeOutputKind.CYCLIC;
        }
        return consumed ? RuntimeOutputKind.INTERNAL : RuntimeOutputKind.FINAL;
    }

    private String runtimeOutputEstimateKey(ItemStack stack, FluidStack fluid, RuntimeOutputKind kind) {
        if (fluid != null && fluid.getFluid() != null) {
            return kind.name() + ":fluid:"
                + fluid.getFluid()
                    .getName();
        }
        return kind.name() + ":" + itemBufferKey(stack);
    }

    private String itemBufferKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "item:null";
        }
        String itemName = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        return "item:" + itemName + ":" + stack.getItemDamage();
    }

    private String fluidBufferKey(FluidStack stack) {
        if (stack == null || stack.getFluid() == null) {
            return "fluid:null";
        }
        return "fluid:" + stack.getFluid()
            .getName();
    }

    private String formatRate(double rate) {
        if (rate >= 1000.0D) {
            return formatCompactAmount(rate);
        }
        if (rate >= 100.0D) {
            return String.valueOf(Math.round(rate));
        }
        if (rate >= 10.0D) {
            return String.format(java.util.Locale.ROOT, "%.1f", rate);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", rate);
    }

    private String formatCompactAmount(double value) {
        String[] suffixes = { "K", "M", "G", "T", "P", "E", "Z", "Y" };
        int suffix = -1;
        double scaled = value;
        while (Math.abs(scaled) >= 1000.0D && suffix + 1 < suffixes.length) {
            scaled /= 1000.0D;
            suffix++;
        }
        if (Math.abs(scaled) >= 1000.0D) {
            return String.format(java.util.Locale.ROOT, "%.2e", value);
        }
        String number;
        double scaledAbs = Math.abs(scaled);
        if (scaledAbs >= 100.0D) {
            number = String.format(java.util.Locale.ROOT, "%.0f", scaled);
        } else if (scaledAbs >= 10.0D) {
            number = trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", scaled));
        } else {
            number = trimTrailingZero(String.format(java.util.Locale.ROOT, "%.2f", scaled));
        }
        return number + suffixes[Math.max(0, suffix)];
    }

    private String trimTrailingZero(String value) {
        while (value.endsWith("0") && value.contains(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private String describeNode(ProcessNode node) {
        if (node == null) {
            return "null";
        }
        return "#" + node.id + "(" + safeNodeName(node) + ")";
    }

    private String safeNodeName(ProcessNode node) {
        return node.name == null || node.name.isEmpty() ? node.recipeHandlerName : node.name;
    }

    private String trimToDisplayWidth(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String describeItem(ItemStack stack) {
        if (stack == null) {
            return "none";
        }
        return stack.stackSize + "x" + stack.getDisplayName();
    }

    private String describeFluid(FluidStack stack) {
        if (stack == null) {
            return "none";
        }
        return stack.amount + "L " + stack.getLocalizedName();
    }

    private String describeBufferedItemList(List<BufferedItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return "none";
        }
        ArrayList<String> parts = new ArrayList<>();
        for (BufferedItemStack stack : stacks) {
            parts.add(describeBufferedItem(stack));
            if (parts.size() >= 8) {
                break;
            }
        }
        if (stacks.size() > parts.size()) {
            parts.add("+" + (stacks.size() - parts.size()));
        }
        return String.join(", ", parts);
    }

    private String describeBufferedItem(BufferedItemStack entry) {
        if (entry == null || entry.stack == null) {
            return "none";
        }
        return entry.amount + "x" + entry.stack.getDisplayName();
    }

    private String describeItemList(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return "none";
        }
        ArrayList<String> parts = new ArrayList<>();
        for (ItemStack stack : stacks) {
            parts.add(describeItem(stack));
            if (parts.size() >= 8) {
                break;
            }
        }
        if (stacks.size() > parts.size()) {
            parts.add("+" + (stacks.size() - parts.size()));
        }
        return String.join(", ", parts);
    }

    private String describeBufferedFluidList(List<BufferedFluidStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return "none";
        }
        ArrayList<String> parts = new ArrayList<>();
        for (BufferedFluidStack stack : stacks) {
            parts.add(describeBufferedFluid(stack));
            if (parts.size() >= 8) {
                break;
            }
        }
        if (stacks.size() > parts.size()) {
            parts.add("+" + (stacks.size() - parts.size()));
        }
        return String.join(", ", parts);
    }

    private String describeBufferedFluid(BufferedFluidStack entry) {
        if (entry == null || entry.fluidStack == null) {
            return "none";
        }
        return entry.amount + "L " + entry.fluidStack.getLocalizedName();
    }

    private String describeFluidList(List<FluidStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return "none";
        }
        ArrayList<String> parts = new ArrayList<>();
        for (FluidStack stack : stacks) {
            parts.add(describeFluid(stack));
            if (parts.size() >= 8) {
                break;
            }
        }
        if (stacks.size() > parts.size()) {
            parts.add("+" + (stacks.size() - parts.size()));
        }
        return String.join(", ", parts);
    }

    private void logMissingItem(boolean debugRuntime, ProcessNode node, int slot, long need, long available,
        ItemStack stack) {
        if (!debugRuntime) {
            return;
        }
        SuperFactory.LOG.info(
            "[Super Integrated Factory/Runtime] 节点缺少物品: node={}, slot={}, need={}, available={}, input={}, internalItems={}",
            describeNode(node),
            slot,
            need,
            available,
            describeItem(stack),
            describeBufferedItemList(internalItems));
    }

    private void logMissingFluid(boolean debugRuntime, ProcessNode node, int slot, long need, long available,
        FluidStack stack) {
        if (!debugRuntime) {
            return;
        }
        SuperFactory.LOG.info(
            "[Super Integrated Factory/Runtime] 节点缺少流体: node={}, slot={}, need={}, available={}, input={}, internalFluids={}",
            describeNode(node),
            slot,
            need,
            available,
            describeFluid(stack),
            describeBufferedFluidList(internalFluids));
    }

    private long availableItemAmount(ItemStack template) {
        long amount = countConsumableInternalItemAmount(template);
        for (ItemStack stack : getStoredInputs()) {
            if (stack != null && stack.stackSize > 0 && itemMatches(template, stack)) {
                amount += stack.stackSize;
            }
        }
        return amount + countItemInDualInputHatches(template);
    }

    private long availableFluidAmount(FluidStack template) {
        if (template == null) {
            return 0L;
        }
        long amount = countConsumableInternalFluidAmount(template);
        for (FluidStack available : getStoredFluids()) {
            if (available != null && available.isFluidEqual(template)) {
                amount += available.amount;
            }
        }
        return amount + countFluidInDualInputHatches(template);
    }

    private long consumeItemForNode(ItemStack template, long amount, List<ItemStack> consumedItems) {
        long remaining = removeConsumableItemFromBuffer(template, amount, consumedItems);
        remaining = depleteItemFromLiveInputs(template, remaining, consumedItems);
        return removeItemFromDualInputHatches(template, remaining, consumedItems);
    }

    private long consumeFluidForNode(FluidStack template, long amount) {
        long remaining = removeConsumableFluidFromBuffer(template, amount);
        remaining = drainFluidFromInputHatches(template, remaining);
        return removeFluidFromDualInputHatches(template, remaining);
    }

    private long countConsumableInternalItemAmount(ItemStack template) {
        long stored = countItemInBuffer(internalItems, template);
        if (isCyclicItemTarget(template)) {
            return Math.max(0L, stored - getCyclicItemReserveMin(template));
        }
        return stored;
    }

    private long countConsumableInternalFluidAmount(FluidStack template) {
        long stored = countFluidInBuffer(internalFluids, template);
        if (isCyclicFluidTarget(template)) {
            return Math.max(0L, stored - getCyclicFluidReserveMin(template));
        }
        return stored;
    }

    private long removeConsumableItemFromBuffer(ItemStack template, long amount, List<ItemStack> consumedItems) {
        if (!isCyclicItemTarget(template)) {
            return removeItemFromBuffer(internalItems, template, amount, consumedItems);
        }
        long consumable = countConsumableInternalItemAmount(template);
        long fromInternal = Math.min(amount, consumable);
        return removeItemFromBuffer(internalItems, template, fromInternal, consumedItems)
            + Math.max(0L, amount - fromInternal);
    }

    private long removeConsumableFluidFromBuffer(FluidStack template, long amount) {
        if (!isCyclicFluidTarget(template)) {
            return removeFluidFromBuffer(internalFluids, template, amount);
        }
        long consumable = countConsumableInternalFluidAmount(template);
        long fromInternal = Math.min(amount, consumable);
        return removeFluidFromBuffer(internalFluids, template, fromInternal) + Math.max(0L, amount - fromInternal);
    }

    private long depleteItemFromLiveInputs(ItemStack template, long amount, List<ItemStack> consumedItems) {
        long remaining = amount;
        while (remaining > 0L) {
            ItemStack actual = findLiveInputStack(template);
            if (actual == null) {
                break;
            }
            ItemStack request = actual.copy();
            int maxChunk = Math.max(1, Math.min(Integer.MAX_VALUE, request.getMaxStackSize()));
            request.stackSize = (int) Math.min(Math.min(remaining, actual.stackSize), maxChunk);
            if (request.stackSize <= 0 || !depleteInput(request)) {
                break;
            }
            addItemToStackList(consumedItems, request);
            remaining -= request.stackSize;
        }
        return remaining;
    }

    private ItemStack findLiveInputStack(ItemStack template) {
        for (ItemStack stack : getStoredInputs()) {
            if (stack != null && stack.stackSize > 0 && itemMatches(template, stack)) {
                return stack;
            }
        }
        return null;
    }

    private long drainFluidFromInputHatches(FluidStack template, long amount) {
        if (template == null) {
            return amount;
        }
        long remaining = amount;
        for (MTEHatchInput hatch : mInputHatches) {
            if (remaining <= 0 || hatch == null || !hatch.isValid()) {
                continue;
            }
            FluidStack request = template.copy();
            request.amount = (int) Math.min(Integer.MAX_VALUE, remaining);
            FluidStack drained = hatch.drain(ForgeDirection.UNKNOWN, request, true);
            if (drained != null && drained.isFluidEqual(template) && drained.amount > 0) {
                remaining -= drained.amount;
            }
        }
        return remaining;
    }

    private void flushOutputBuffers() {
        Iterator<BufferedItemStack> itemIterator = outputItems.iterator();
        while (itemIterator.hasNext()) {
            BufferedItemStack entry = itemIterator.next();
            if (entry == null || entry.amount <= 0L || entry.stack == null) {
                itemIterator.remove();
                continue;
            }
            ItemStack stack = copyItemAmount(entry.stack, entry.amount);
            if (!addOutput(stack)) {
                break;
            }
            entry.amount = Math.max(0L, entry.amount - getStackAmount(stack));
            if (entry.amount <= 0L) {
                itemIterator.remove();
            }
        }
        Iterator<BufferedFluidStack> fluidIterator = outputFluids.iterator();
        while (fluidIterator.hasNext()) {
            BufferedFluidStack entry = fluidIterator.next();
            if (entry == null || entry.fluidStack == null || entry.amount <= 0L) {
                fluidIterator.remove();
                continue;
            }
            FluidStack stack = copyFluidAmount(entry.fluidStack, entry.amount);
            int offered = stack.amount;
            if (tryFlushFluidOutput(stack) <= 0) {
                break;
            }
            entry.amount = Math.max(0L, entry.amount - Math.max(0, offered - stack.amount));
            if (entry.amount <= 0L) {
                fluidIterator.remove();
            }
        }
    }

    private int tryFlushFluidOutput(FluidStack stack) {
        if (stack == null || stack.amount <= 0) {
            return 0;
        }
        int filled = fillOutputHatches(stack, true);
        if (stack.amount > 0) {
            filled += fillOutputHatches(stack, false);
        }
        return filled;
    }

    private int fillOutputHatches(FluidStack stack, boolean restrictiveOnly) {
        int filled = 0;
        for (MTEHatchOutput hatch : mOutputHatches) {
            if (stack == null || stack.amount <= 0) {
                break;
            }
            if (hatch == null || !hatch.isValid() || restrictiveOnly && hatch.mMode == 0) {
                continue;
            }
            if (!hatch.canStoreFluid(stack)) {
                continue;
            }
            FluidStack request = stack.copy();
            int accepted = hatch.fill(request, false);
            if (accepted <= 0) {
                continue;
            }
            request.amount = Math.min(stack.amount, accepted);
            int actual = hatch.fill(request, true);
            if (actual <= 0) {
                continue;
            }
            stack.amount -= actual;
            filled += actual;
        }
        return filled;
    }

    private void moveAllInternalToOutput() {
        for (BufferedItemStack entry : internalItems) {
            if (entry != null) {
                addItemToBuffer(outputItems, entry.stack, entry.amount);
            }
        }
        internalItems.clear();
        for (BufferedFluidStack entry : internalFluids) {
            if (entry != null) {
                addFluidToBuffer(outputFluids, entry.fluidStack, entry.amount);
            }
        }
        internalFluids.clear();
    }

    private void abortRunningJobsToInternalCache() {
        for (RunningJob job : runningJobs) {
            for (ItemStack stack : job.consumedItems) {
                addItemToBuffer(internalItems, stack, getStackAmount(stack));
            }
            for (FluidStack stack : job.consumedFluids) {
                addFluidToBuffer(internalFluids, stack, stack.amount);
            }
            refundRuntimeEnergy(job.reservedEnergy);
        }
        runningJobs.clear();
    }

    private void discardRunningJobsForPowerLoss() {
        for (RunningJob job : runningJobs) {
            refundRuntimeEnergy(job.reservedEnergy);
        }
        runningJobs.clear();
        runtimeOutputEstimateLines = new ArrayList<>();
    }

    private void updateRuntimeProgressDisplay() {
        int maxDuration = 0;
        int minRemaining = 0;
        long totalEu = 0L;
        for (RunningJob job : runningJobs) {
            maxDuration = Math.max(maxDuration, job.durationTicks);
            if (minRemaining == 0 || job.remainingTicks < minRemaining) {
                minRemaining = job.remainingTicks;
            }
            ProcessNode node = runtimeGraph.findNode(job.nodeId);
            if (node != null) {
                totalEu = safeAddLong(totalEu, safeMultiply(getJobEuPerTick(job, node), Math.max(1, job.parallel)));
            }
        }
        mMaxProgresstime = maxDuration;
        mProgresstime = maxDuration <= 0 ? 0 : Math.max(0, maxDuration - minRemaining);
        lEUt = isWirelessModeEnabled() || totalEu <= 0 ? 0L : -Math.min(Integer.MAX_VALUE, totalEu);
        mEUt = isWirelessModeEnabled() ? 0 : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, lEUt));
    }

    private boolean canSustainWiredRuntimePower() {
        long euPerTick = totalRunningEuPerTick();
        return euPerTick <= 0L || canStartWiredRuntimeJob(euPerTick);
    }

    private boolean canStartWiredRuntimeJob(long euPerTick) {
        if (euPerTick <= 0L) {
            return true;
        }
        return getMaxInputEnergy() > 0L && euPerTick <= getMaxInputEnergy() && getEUVar() >= euPerTick;
    }

    private boolean isFluidDisplay(ItemStack stack) {
        return GTUtility.getFluidFromDisplayStack(stack) != null;
    }

    private boolean itemMatches(ItemStack recipeInput, ItemStack provided) {
        if (recipeInput == null || provided == null) {
            return false;
        }
        if (GTUtility.areStacksEqual(recipeInput, provided, true)) {
            return true;
        }
        int[] recipeOreIds = OreDictionary.getOreIDs(recipeInput);
        int[] providedOreIds = OreDictionary.getOreIDs(provided);
        if (recipeOreIds == null || providedOreIds == null || recipeOreIds.length == 0 || providedOreIds.length == 0) {
            return false;
        }
        for (int recipeOreId : recipeOreIds) {
            for (int providedOreId : providedOreIds) {
                if (recipeOreId == providedOreId) {
                    return true;
                }
            }
        }
        return false;
    }

    private long getStackAmount(ItemStack stack) {
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        if (fluid != null) {
            return Math.max(1, fluid.amount);
        }
        return stack == null ? 0L : Math.max(1, stack.stackSize);
    }

    private long safeMultiply(long a, long b) {
        if (a > 0L && b > Long.MAX_VALUE / a) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    private long safeAddLong(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    private long safeCeilMultiply(long value, long numerator, long denominator) {
        if (denominator <= 0L) {
            return Long.MAX_VALUE;
        }
        long product = safeMultiply(Math.max(0L, value), Math.max(0L, numerator));
        if (product == Long.MAX_VALUE) {
            return product;
        }
        long roundingOffset = denominator - 1L;
        if (roundingOffset > 0L && product > Long.MAX_VALUE - roundingOffset) {
            return Long.MAX_VALUE;
        }
        return (product + roundingOffset) / denominator;
    }

    private ItemStack copyItemAmount(ItemStack stack, long amount) {
        ItemStack copy = stack.copy();
        copy.stackSize = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, amount));
        return copy;
    }

    private FluidStack copyFluidAmount(FluidStack stack, long amount) {
        if (stack == null) {
            return null;
        }
        FluidStack copy = stack.copy();
        copy.amount = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, amount));
        return copy;
    }

    private void addItemToBuffer(List<BufferedItemStack> buffer, ItemStack stack, long amount) {
        if (stack == null || amount <= 0L) {
            return;
        }
        for (BufferedItemStack existing : buffer) {
            if (existing != null && existing.stack != null && GTUtility.areStacksEqual(existing.stack, stack, true)) {
                existing.amount = safeAddLong(existing.amount, amount);
                return;
            }
        }
        buffer.add(new BufferedItemStack(stack, amount));
    }

    private void addItemToStackList(List<ItemStack> buffer, ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) {
            return;
        }
        for (ItemStack existing : buffer) {
            if (existing != null && GTUtility.areStacksEqual(existing, stack, true)) {
                existing.stackSize = (int) Math.min(Integer.MAX_VALUE, (long) existing.stackSize + stack.stackSize);
                return;
            }
        }
        buffer.add(stack.copy());
    }

    private void addFluidToBuffer(List<BufferedFluidStack> buffer, FluidStack stack, long amount) {
        if (stack == null || amount <= 0L) {
            return;
        }
        for (BufferedFluidStack existing : buffer) {
            if (existing != null && existing.fluidStack != null && existing.fluidStack.isFluidEqual(stack)) {
                existing.amount = safeAddLong(existing.amount, amount);
                return;
            }
        }
        buffer.add(new BufferedFluidStack(stack, amount));
    }

    private void addFluidToStackList(List<FluidStack> buffer, FluidStack stack) {
        if (stack == null || stack.amount <= 0) {
            return;
        }
        for (FluidStack existing : buffer) {
            if (existing != null && existing.isFluidEqual(stack)) {
                existing.amount = (int) Math.min(Integer.MAX_VALUE, (long) existing.amount + stack.amount);
                return;
            }
        }
        buffer.add(stack.copy());
    }

    private long countItemInBuffer(List<BufferedItemStack> buffer, ItemStack template) {
        long amount = 0L;
        for (BufferedItemStack entry : buffer) {
            if (entry != null && entry.stack != null && itemMatches(template, entry.stack)) {
                amount = safeAddLong(amount, entry.amount);
            }
        }
        return amount;
    }

    private long countFluidInBuffer(List<BufferedFluidStack> buffer, FluidStack template) {
        if (template == null) {
            return 0L;
        }
        long amount = 0L;
        for (BufferedFluidStack stack : buffer) {
            if (stack != null && stack.fluidStack != null && stack.fluidStack.isFluidEqual(template)) {
                amount = safeAddLong(amount, stack.amount);
            }
        }
        return amount;
    }

    private long removeItemFromBuffer(List<BufferedItemStack> buffer, ItemStack template, long amount) {
        return removeItemFromBuffer(buffer, template, amount, null);
    }

    private long removeItemFromBuffer(List<BufferedItemStack> buffer, ItemStack template, long amount,
        List<ItemStack> consumedItems) {
        long remaining = amount;
        Iterator<BufferedItemStack> iterator = buffer.iterator();
        while (iterator.hasNext() && remaining > 0L) {
            BufferedItemStack entry = iterator.next();
            if (entry == null || entry.stack == null || !itemMatches(template, entry.stack)) {
                continue;
            }
            long removed = Math.min(remaining, entry.amount);
            entry.amount -= removed;
            remaining -= removed;
            if (consumedItems != null && removed > 0L) {
                addItemToStackList(consumedItems, copyItemAmount(entry.stack, removed));
            }
            if (entry.amount <= 0L) {
                iterator.remove();
            }
        }
        return remaining;
    }

    private long removeFluidFromBuffer(List<BufferedFluidStack> buffer, FluidStack template, long amount) {
        long remaining = amount;
        Iterator<BufferedFluidStack> iterator = buffer.iterator();
        while (iterator.hasNext() && remaining > 0L) {
            BufferedFluidStack stack = iterator.next();
            if (stack == null || stack.fluidStack == null
                || template == null
                || !stack.fluidStack.isFluidEqual(template)) {
                continue;
            }
            long removed = Math.min(remaining, stack.amount);
            stack.amount -= removed;
            remaining -= removed;
            if (stack.amount <= 0L) {
                iterator.remove();
            }
        }
        return remaining;
    }

    private long countItemInDualInputHatches(ItemStack template) {
        long amount = 0L;
        for (IDualInputHatch hatch : mDualInputHatches) {
            if (hatch == null) {
                continue;
            }
            for (Iterator<? extends IDualInputInventory> iterator = hatch.inventories(); iterator.hasNext();) {
                IDualInputInventory inventory = iterator.next();
                if (inventory == null || inventory.isEmpty()) {
                    continue;
                }
                amount += countItemInStacks(template, inventory.getItemInputs());
            }
        }
        return amount;
    }

    private long countFluidInDualInputHatches(FluidStack template) {
        if (template == null) {
            return 0L;
        }
        long amount = 0L;
        for (IDualInputHatch hatch : mDualInputHatches) {
            if (hatch == null || !hatch.supportsFluids()) {
                continue;
            }
            for (Iterator<? extends IDualInputInventory> iterator = hatch.inventories(); iterator.hasNext();) {
                IDualInputInventory inventory = iterator.next();
                if (inventory == null || inventory.isEmpty()) {
                    continue;
                }
                amount += countFluidInStacks(template, inventory.getFluidInputs());
            }
        }
        return amount;
    }

    private long countItemInStacks(ItemStack template, ItemStack[] stacks) {
        long amount = 0L;
        if (stacks == null) {
            return amount;
        }
        for (ItemStack stack : stacks) {
            if (stack != null && stack.stackSize > 0 && itemMatches(template, stack)) {
                amount += stack.stackSize;
            }
        }
        return amount;
    }

    private long countFluidInStacks(FluidStack template, FluidStack[] stacks) {
        long amount = 0L;
        if (stacks == null || template == null) {
            return amount;
        }
        for (FluidStack stack : stacks) {
            if (stack != null && stack.amount > 0 && stack.isFluidEqual(template)) {
                amount += stack.amount;
            }
        }
        return amount;
    }

    private long removeItemFromDualInputHatches(ItemStack template, long amount, List<ItemStack> consumedItems) {
        long remaining = amount;
        for (IDualInputHatch hatch : mDualInputHatches) {
            if (remaining <= 0L || hatch == null) {
                continue;
            }
            for (Iterator<? extends IDualInputInventory> iterator = hatch.inventories(); iterator.hasNext()
                && remaining > 0L;) {
                IDualInputInventory inventory = iterator.next();
                if (inventory == null || inventory.isEmpty()) {
                    continue;
                }
                remaining = removeItemFromStacks(template, inventory.getItemInputs(), remaining, consumedItems);
            }
        }
        return remaining;
    }

    private long removeFluidFromDualInputHatches(FluidStack template, long amount) {
        long remaining = amount;
        for (IDualInputHatch hatch : mDualInputHatches) {
            if (remaining <= 0L || hatch == null || !hatch.supportsFluids()) {
                continue;
            }
            for (Iterator<? extends IDualInputInventory> iterator = hatch.inventories(); iterator.hasNext()
                && remaining > 0L;) {
                IDualInputInventory inventory = iterator.next();
                if (inventory == null || inventory.isEmpty()) {
                    continue;
                }
                remaining = removeFluidFromStacks(template, inventory.getFluidInputs(), remaining);
            }
        }
        return remaining;
    }

    private long removeItemFromStacks(ItemStack template, ItemStack[] stacks, long amount) {
        return removeItemFromStacks(template, stacks, amount, null);
    }

    private long removeItemFromStacks(ItemStack template, ItemStack[] stacks, long amount,
        List<ItemStack> consumedItems) {
        long remaining = amount;
        if (stacks == null) {
            return remaining;
        }
        for (ItemStack stack : stacks) {
            if (remaining <= 0L) {
                break;
            }
            if (stack == null || stack.stackSize <= 0 || !itemMatches(template, stack)) {
                continue;
            }
            int removed = (int) Math.min(remaining, stack.stackSize);
            if (consumedItems != null && removed > 0) {
                addItemToStackList(consumedItems, copyItemAmount(stack, removed));
            }
            stack.stackSize -= removed;
            remaining -= removed;
        }
        return remaining;
    }

    private long removeFluidFromStacks(FluidStack template, FluidStack[] stacks, long amount) {
        long remaining = amount;
        if (stacks == null || template == null) {
            return remaining;
        }
        for (FluidStack stack : stacks) {
            if (remaining <= 0L) {
                break;
            }
            if (stack == null || stack.amount <= 0 || !stack.isFluidEqual(template)) {
                continue;
            }
            int removed = (int) Math.min(remaining, stack.amount);
            stack.amount -= removed;
            remaining -= removed;
        }
        return remaining;
    }

    private boolean hasStoredProcessRequirements() {
        return processRequirements.hasStoredAnything() || hasRuntimeStoredAnything();
    }

    private boolean hasRuntimeStoredAnything() {
        return !internalItems.isEmpty() || !internalFluids.isEmpty()
            || !outputItems.isEmpty()
            || !outputFluids.isEmpty()
            || !runningJobs.isEmpty();
    }

    private boolean outputModeIsLocked() {
        return hasStoredProcessRequirements() || pendingProcessRequirements.hasSubmittedDemands();
    }

    private boolean allRequirementsSatisfied() {
        if (!processRequirements.hasSubmittedDemands()) {
            return false;
        }
        for (ProcessRequirements.ItemDemand demand : processRequirements.nonConsumables) {
            if (demand.missing() > 0) {
                return false;
            }
        }
        for (ProcessRequirements.ItemDemand demand : processRequirements.startupItems) {
            if (demand.missing() > 0) {
                return false;
            }
        }
        for (ProcessRequirements.FluidDemand demand : processRequirements.startupFluids) {
            if (demand.missing() > 0) {
                return false;
            }
        }
        for (ProcessRequirements.RecipeMapDemand demand : processRequirements.recipeMaps) {
            if (demand.missing() > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean consumeNonConsumable(ProcessRequirements.ItemDemand demand) {
        if (demand.stack == null) {
            return false;
        }
        ItemStack request = demand.stack.copy();
        request.stackSize = 1;
        return depleteInput(request);
    }

    private boolean consumeStartupItem(ProcessRequirements.ItemDemand demand) {
        if (demand.stack == null) {
            return false;
        }
        ItemStack request = demand.stack.copy();
        request.stackSize = 1;
        return depleteInput(request);
    }

    private int consumeStartupFluid(ProcessRequirements.FluidDemand demand) {
        if (demand.stack == null || demand.missing() <= 0) {
            return 0;
        }
        int remaining = demand.missing();
        return remaining - (int) Math.min(Integer.MAX_VALUE, drainFluidFromInputHatches(demand.stack, remaining));
    }

    private ItemStack consumeRecipeMapMachine(String recipeMapName) {
        for (MTEHatchInputBus bus : mInputBusses) {
            if (bus == null || !bus.isValid()) {
                continue;
            }
            int circuitSlot = bus.getCircuitSlot();
            for (int slot = bus.getSizeInventory() - 1; slot >= 0; slot--) {
                if (slot == circuitSlot) {
                    continue;
                }
                ItemStack stack = bus.getStackInSlot(slot);
                if (stack == null || stack.stackSize <= 0 || !machineSupportsRecipeMap(stack, recipeMapName)) {
                    continue;
                }
                ItemStack consumed = stack.copy();
                consumed.stackSize = 1;
                bus.getBaseMetaTileEntity()
                    .decrStackSize(slot, 1);
                return consumed;
            }
        }
        return null;
    }

    private boolean machineSupportsRecipeMap(ItemStack stack, String recipeMapName) {
        if (stack == null || !(stack.getItem() instanceof ItemMachines)) {
            return false;
        }
        IMetaTileEntity metaTileEntity = ItemMachines.getMetaTileEntity(stack);
        if (!(metaTileEntity instanceof MTEMultiBlockBase) || !(metaTileEntity instanceof RecipeMapWorkable workable)) {
            return false;
        }
        Collection<gregtech.api.recipe.RecipeMap<?>> recipeMaps = workable.getAvailableRecipeMaps();
        if (recipeMaps != null) {
            for (gregtech.api.recipe.RecipeMap<?> recipeMap : recipeMaps) {
                if (recipeMap != null && recipeMap.unlocalizedName.equals(recipeMapName)) {
                    return true;
                }
            }
        }
        gregtech.api.recipe.RecipeMap<?> primary = workable.getRecipeMap();
        return primary != null && primary.unlocalizedName.equals(recipeMapName);
    }

    private void decrementStoredRecipeMapFor(ItemStack machine) {
        for (ProcessRequirements.RecipeMapDemand demand : processRequirements.recipeMaps) {
            if (demand.stored > 0 && machineSupportsRecipeMap(machine, demand.recipeMapName)) {
                demand.stored--;
                return;
            }
        }
    }

    private int countSubmittedSteps() {
        int count = 0;
        for (ProcessRequirements.RecipeMapDemand demand : processRequirements.recipeMaps) {
            count += demand.required;
        }
        return count;
    }

    private NBTTagList writeItemList(List<BufferedItemStack> items) {
        NBTTagList list = new NBTTagList();
        for (BufferedItemStack entry : items) {
            if (entry != null && entry.stack != null && entry.amount > 0L) {
                NBTTagCompound tag = entry.stack.writeToNBT(new NBTTagCompound());
                tag.setLong("BufferedAmount", entry.amount);
                list.appendTag(tag);
            }
        }
        return list;
    }

    private void readItemList(NBTTagList list, List<BufferedItemStack> target) {
        target.clear();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            ItemStack stack = ItemStack.loadItemStackFromNBT(tag);
            if (stack != null && stack.stackSize > 0) {
                long amount = tag.hasKey("BufferedAmount", Constants.NBT.TAG_LONG) ? tag.getLong("BufferedAmount")
                    : stack.stackSize;
                addItemToBuffer(target, stack, amount);
            }
        }
    }

    private NBTTagList writeFluidList(List<BufferedFluidStack> fluids) {
        NBTTagList list = new NBTTagList();
        for (BufferedFluidStack entry : fluids) {
            if (entry != null && entry.fluidStack != null && entry.amount > 0L) {
                NBTTagCompound tag = entry.fluidStack.writeToNBT(new NBTTagCompound());
                tag.setLong("BufferedAmount", entry.amount);
                list.appendTag(tag);
            }
        }
        return list;
    }

    private void readFluidList(NBTTagList list, List<BufferedFluidStack> target) {
        target.clear();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            FluidStack stack = FluidStack.loadFluidStackFromNBT(tag);
            if (stack != null && stack.amount > 0) {
                long amount = tag.hasKey("BufferedAmount", Constants.NBT.TAG_LONG) ? tag.getLong("BufferedAmount")
                    : stack.amount;
                addFluidToBuffer(target, stack, amount);
            }
        }
    }

    private NBTTagList writeRunningJobs() {
        NBTTagList list = new NBTTagList();
        for (RunningJob job : runningJobs) {
            list.appendTag(job.writeToNBT());
        }
        return list;
    }

    private void readRunningJobs(NBTTagList list) {
        runningJobs.clear();
        for (int i = 0; i < list.tagCount(); i++) {
            runningJobs.add(RunningJob.readFromNBT(list.getCompoundTagAt(i)));
        }
    }

    private String serializeLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines);
    }

    private List<String> deserializeLines(String value) {
        ArrayList<String> lines = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return lines;
        }
        for (String line : value.split("\n", -1)) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static final class BufferedItemStack {

        private final ItemStack stack;
        private long amount;

        private BufferedItemStack(ItemStack stack, long amount) {
            this.stack = stack == null ? null : stack.copy();
            if (this.stack != null) {
                this.stack.stackSize = 1;
            }
            this.amount = Math.max(0L, amount);
        }
    }

    private static final class BufferedFluidStack {

        private final FluidStack fluidStack;
        private long amount;

        private BufferedFluidStack(FluidStack fluidStack, long amount) {
            this.fluidStack = fluidStack == null ? null : fluidStack.copy();
            if (this.fluidStack != null) {
                this.fluidStack.amount = 1;
            }
            this.amount = Math.max(0L, amount);
        }
    }

    private enum RuntimeOutputKind {

        INTERNAL(EnumChatFormatting.RED),
        FINAL(EnumChatFormatting.GREEN),
        CYCLIC(EnumChatFormatting.YELLOW);

        private final EnumChatFormatting color;

        RuntimeOutputKind(EnumChatFormatting color) {
            this.color = color;
        }
    }

    private static final class RuntimeOutputEstimateEntry {

        private final ItemStack item;
        private final FluidStack fluidStack;
        private final boolean fluid;
        private final RuntimeOutputKind kind;
        private double ratePerSecond;

        private RuntimeOutputEstimateEntry(ItemStack item, FluidStack fluidStack, RuntimeOutputKind kind,
            double ratePerSecond) {
            this.item = item == null ? null : item.copy();
            this.fluidStack = fluidStack == null ? null : fluidStack.copy();
            this.fluid = fluidStack != null;
            this.kind = kind;
            this.ratePerSecond = ratePerSecond;
        }

        private String displayName() {
            return fluidStack != null ? fluidStack.getLocalizedName() : item == null ? "" : item.getDisplayName();
        }
    }

    private static final class RawMaterialExportPlan {

        private final List<ItemStack> items = new ArrayList<>();
        private final List<FluidStack> fluids = new ArrayList<>();
        private final List<String> oreDictionaryNodes = new ArrayList<>();
    }

    private static final class RawMaterialMarkerTarget {

        private final Object hatch;
        private final ItemStack[] itemMarkers;
        private final FluidStack[] fluidMarkers;
        private final int itemSlotOffset;
        private int nextItemSlot;
        private int nextFluidSlot;

        private RawMaterialMarkerTarget(Object hatch, ItemStack[] itemMarkers, FluidStack[] fluidMarkers,
            int itemSlotOffset) {
            this.hatch = hatch;
            this.itemMarkers = itemMarkers;
            this.fluidMarkers = fluidMarkers;
            this.itemSlotOffset = itemSlotOffset;
        }

        private static RawMaterialMarkerTarget tryCreate(Object hatch) {
            if (hatch == null) {
                return null;
            }
            ItemStack[] dualItems = readItemArrayField(hatch, "i_mark");
            FluidStack[] dualFluids = readFluidArrayField(hatch, "f_mark");
            if (dualItems != null || dualFluids != null) {
                return new RawMaterialMarkerTarget(hatch, dualItems, dualFluids, -1);
            }
            ItemStack[] itemMarkers = null;
            int itemSlotOffset = -1;
            ItemStack[] shadowInventory = readItemArrayField(hatch, "shadowInventory");
            if (shadowInventory != null && hatch instanceof MTEHatchInputBus bus) {
                itemMarkers = new ItemStack[shadowInventory.length];
                itemSlotOffset = 0;
                for (int i = 0; i < itemMarkers.length; i++) {
                    itemMarkers[i] = bus.getStackInSlot(i);
                }
            }
            FluidStack[] fluidMarkers = readFluidArrayField(hatch, "storedFluids");
            if (itemMarkers == null && fluidMarkers == null) {
                return null;
            }
            return new RawMaterialMarkerTarget(hatch, itemMarkers, fluidMarkers, itemSlotOffset);
        }

        private boolean isCombined() {
            return itemMarkers != null && fluidMarkers != null;
        }

        private int itemCapacity() {
            return itemMarkers == null ? 0 : itemMarkers.length;
        }

        private int fluidCapacity() {
            return fluidMarkers == null ? 0 : fluidMarkers.length;
        }

        private void clear() {
            setAutoPull(false);
            if (itemMarkers != null) {
                for (int i = 0; i < itemMarkers.length; i++) {
                    setItemMarker(i, null);
                }
            }
            if (fluidMarkers != null) {
                for (int i = 0; i < fluidMarkers.length; i++) {
                    setFluidMarker(i, null);
                }
            }
            markTargetDirty();
        }

        private boolean addItem(ItemStack stack) {
            if (itemMarkers == null || nextItemSlot >= itemMarkers.length) {
                return false;
            }
            setItemMarker(nextItemSlot++, stack == null ? null : GTUtility.copyAmount(1, stack));
            markTargetDirty();
            return true;
        }

        private boolean addFluid(FluidStack stack) {
            if (fluidMarkers == null || nextFluidSlot >= fluidMarkers.length) {
                return false;
            }
            setFluidMarker(nextFluidSlot++, stack == null ? null : GTUtility.copyAmount(1, stack));
            markTargetDirty();
            return true;
        }

        private void setItemMarker(int slot, ItemStack stack) {
            if (itemMarkers == null || slot < 0 || slot >= itemMarkers.length) {
                return;
            }
            itemMarkers[slot] = stack == null ? null : stack.copy();
            if (itemSlotOffset >= 0 && hatch instanceof MTEHatchInputBus bus) {
                bus.setInventorySlotContents(itemSlotOffset + slot, stack == null ? null : stack.copy());
            }
            invoke(hatch, "updateInformationSlot", new Class<?>[] { int.class, ItemStack.class }, slot, stack);
        }

        private void setFluidMarker(int slot, FluidStack stack) {
            if (fluidMarkers == null || slot < 0 || slot >= fluidMarkers.length) {
                return;
            }
            fluidMarkers[slot] = stack == null ? null : stack.copy();
            invoke(hatch, "updateInformationSlotF", new Class<?>[] { int.class }, slot);
            invoke(hatch, "updateInformationSlot", new Class<?>[] { int.class }, slot);
        }

        private void setAutoPull(boolean enabled) {
            invoke(hatch, "setAutoPullItemList", new Class<?>[] { boolean.class }, enabled);
            invoke(hatch, "setAutoPullFluidList", new Class<?>[] { boolean.class }, enabled);
        }

        private void markTargetDirty() {
            if (hatch instanceof IMetaTileEntity meta && meta.getBaseMetaTileEntity() != null) {
                meta.getBaseMetaTileEntity()
                    .markDirty();
            }
        }

        private static ItemStack[] readItemArrayField(Object target, String name) {
            Object value = readField(target, name);
            return value instanceof ItemStack[]stacks ? stacks : null;
        }

        private static FluidStack[] readFluidArrayField(Object target, String name) {
            Object value = readField(target, name);
            return value instanceof FluidStack[]stacks ? stacks : null;
        }

        private static Object readField(Object target, String name) {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return null;
            }
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }

        private static Field findField(Class<?> type, String name) {
            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                }
            }
            return null;
        }

        private static void invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) {
            Method method = findMethod(target.getClass(), name, parameterTypes);
            if (method == null) {
                return;
            }
            try {
                method.setAccessible(true);
                method.invoke(target, args);
            } catch (ReflectiveOperationException ignored) {}
        }

        private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredMethod(name, parameterTypes);
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                }
            }
            return null;
        }
    }

    private static final class RunningJob {

        private final int nodeId;
        private final int parallel;
        private final int durationTicks;
        private final long euPerTick;
        private int remainingTicks;
        private long reservedEnergy;
        private final List<ItemStack> consumedItems = new ArrayList<>();
        private final List<FluidStack> consumedFluids = new ArrayList<>();

        private RunningJob(int nodeId, int parallel, int durationTicks, long euPerTick) {
            this.nodeId = nodeId;
            this.parallel = Math.max(1, parallel);
            this.durationTicks = Math.max(1, durationTicks);
            this.euPerTick = Math.max(0L, euPerTick);
            this.remainingTicks = this.durationTicks;
        }

        private NBTTagCompound writeToNBT() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("NodeId", nodeId);
            tag.setInteger("Parallel", parallel);
            tag.setInteger("DurationTicks", durationTicks);
            tag.setLong("EUt", euPerTick);
            tag.setInteger("RemainingTicks", remainingTicks);
            tag.setLong("ReservedEnergy", reservedEnergy);
            NBTTagList items = new NBTTagList();
            for (ItemStack stack : consumedItems) {
                if (stack != null && stack.stackSize > 0) {
                    items.appendTag(stack.writeToNBT(new NBTTagCompound()));
                }
            }
            tag.setTag("ConsumedItems", items);
            NBTTagList fluids = new NBTTagList();
            for (FluidStack stack : consumedFluids) {
                if (stack != null && stack.amount > 0) {
                    fluids.appendTag(stack.writeToNBT(new NBTTagCompound()));
                }
            }
            tag.setTag("ConsumedFluids", fluids);
            return tag;
        }

        private static RunningJob readFromNBT(NBTTagCompound tag) {
            RunningJob job = new RunningJob(
                tag.getInteger("NodeId"),
                tag.getInteger("Parallel"),
                tag.getInteger("DurationTicks"),
                tag.getLong("EUt"));
            job.remainingTicks = Math.max(0, tag.getInteger("RemainingTicks"));
            job.reservedEnergy = Math.max(0L, tag.getLong("ReservedEnergy"));
            NBTTagList items = tag.getTagList("ConsumedItems", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < items.tagCount(); i++) {
                ItemStack stack = ItemStack.loadItemStackFromNBT(items.getCompoundTagAt(i));
                if (stack != null && stack.stackSize > 0) {
                    job.consumedItems.add(stack);
                }
            }
            NBTTagList fluids = tag.getTagList("ConsumedFluids", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < fluids.tagCount(); i++) {
                FluidStack stack = FluidStack.loadFluidStackFromNBT(fluids.getCompoundTagAt(i));
                if (stack != null && stack.amount > 0) {
                    job.consumedFluids.add(stack);
                }
            }
            return job;
        }
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

    private LedStatus parallelStatus(double value) {
        return value < 1 ? LedStatus.STATUS_TOO_LOW : LedStatus.STATUS_OK;
    }

    private void sanitizeParameterRelationships() {
        double[] inputs = inputValues();
        inputs[INDEX_PARALLEL] = Math.max(1D, Math.min(Integer.MAX_VALUE, Math.round(inputs[INDEX_PARALLEL])));
        inputs[INDEX_MANUAL_OVERCLOCKS] = Math.max(0D, Math.min(64D, Math.round(inputs[INDEX_MANUAL_OVERCLOCKS])));
        for (int i = 0; i < inputs.length; i++) {
            if (i != INDEX_WIRELESS && i != INDEX_PARALLEL && i != INDEX_MANUAL_OVERCLOCKS) {
                inputs[i] = 0D;
            }
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
