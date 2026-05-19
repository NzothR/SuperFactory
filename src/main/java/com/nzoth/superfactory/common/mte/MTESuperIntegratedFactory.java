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

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.server.MinecraftServer;
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
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.common.widget.ButtonWidget;
import com.gtnewhorizons.modularui.common.widget.DynamicPositionedColumn;
import com.gtnewhorizons.modularui.common.widget.FakeSyncWidget;
import com.gtnewhorizons.modularui.common.widget.SlotWidget;
import com.gtnewhorizons.modularui.common.widget.TextWidget;
import com.nzoth.superfactory.Config;
import com.nzoth.superfactory.SuperFactory;
import com.nzoth.superfactory.common.loader.MachineLoader;
import com.nzoth.superfactory.common.network.MessageProcessCanvasStatus;
import com.nzoth.superfactory.common.network.NetworkLoader;
import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;
import com.nzoth.superfactory.common.process.ProcessRequirements;
import com.nzoth.superfactory.common.process.analysis.CycleInfo;
import com.nzoth.superfactory.common.process.analysis.GraphAnalysisResult;
import com.nzoth.superfactory.common.process.analysis.GraphValidationError;
import com.nzoth.superfactory.common.process.analysis.ProcessGraphAnalyzer;
import com.nzoth.superfactory.common.process.export.RawMaterialExporter;
import com.nzoth.superfactory.common.process.key.MaterialKey;
import com.nzoth.superfactory.common.process.recipe.ProcessNodeRecipeApplier;
import com.nzoth.superfactory.common.process.runtime.BufferedFluidStack;
import com.nzoth.superfactory.common.process.runtime.BufferedItemStack;
import com.nzoth.superfactory.common.process.runtime.CycleRuntimeManager;
import com.nzoth.superfactory.common.process.runtime.CycleRuntimeState;
import com.nzoth.superfactory.common.process.runtime.OutputRouteType;
import com.nzoth.superfactory.common.process.runtime.ProcessBufferUtil;
import com.nzoth.superfactory.common.process.runtime.ProcessRuntimeMath;
import com.nzoth.superfactory.common.process.runtime.RunningJob;
import com.nzoth.superfactory.common.process.runtime.RuntimeOutputFormatter;
import com.nzoth.superfactory.common.process.runtime.RuntimeResourceSnapshot;
import com.nzoth.superfactory.common.process.runtime.RuntimeRouteResolver;
import com.nzoth.superfactory.common.process.schedule.IntegratedFactoryScheduler;
import com.nzoth.superfactory.common.process.schedule.NodeCandidate;
import com.nzoth.superfactory.common.process.submit.IntegratedFactoryUnloadHandler;
import com.nzoth.superfactory.common.process.watermark.IntegratedFactoryWatermarks;

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
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
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
import gregtech.common.tileentities.machines.MTEHatchOutputBusME;
import gregtech.common.tileentities.machines.MTEHatchOutputME;
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
    private static final int MODE_STANDBY = 0;
    private static final int MODE_INPUT = 1;
    private static final int MODE_RUNNING = 2;
    private static final int MODE_OUTPUT = 3;
    private static final int RUNTIME_OUTPUT_ESTIMATE_LINE_LIMIT = 30;
    private static final int STATIC_RUNTIME_NODE_LINE_THRESHOLD = 30;
    private static final int MAX_OUTPUT_ITEM_FLUSH_PER_TICK = 64;
    private static final int MAX_OUTPUT_FLUID_FLUSH_PER_TICK = 16_000_000;
    private static final boolean GRAPH_ANALYSIS_ACCEPTANCE_LOGGING = true;

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
    /** Editable design graph shown in the process GUI. It is never mutated by the runtime executor. */
    private final ProcessGraph processGraph = new ProcessGraph();
    /** Installed graph that RUNNING mode actually executes. It is replaced only after OUTPUT has cleared state. */
    private final ProcessGraph runtimeGraph = new ProcessGraph();
    /** Graph submitted while another process may still be loaded; OUTPUT mode promotes it when the machine is empty. */
    private final ProcessGraph pendingRuntimeGraph = new ProcessGraph();
    /** Last graph submitted while OUTPUT is already unloading; installed only after the current unload completes. */
    private final ProcessGraph deferredRuntimeGraph = new ProcessGraph();
    /** Last successfully submitted graph, used as an in-machine restore point for editing mistakes. */
    private final ProcessGraph submittedProcessGraphSnapshot = new ProcessGraph();
    /** Gate resources for the installed runtime graph: host machines, NC items, and startup materials. */
    private final ProcessRequirements processRequirements = new ProcessRequirements();
    /** Gate resources for the next submitted graph. */
    private final ProcessRequirements pendingProcessRequirements = new ProcessRequirements();
    private final ProcessRequirements deferredProcessRequirements = new ProcessRequirements();
    /** Intermediate products that are available to downstream virtual nodes before touching external outputs. */
    private final List<BufferedItemStack> internalItems = new ArrayList<>();
    private final List<BufferedFluidStack> internalFluids = new ArrayList<>();
    /** Products that must be exported before OUTPUT can finish or the next graph can be installed. */
    private final List<BufferedItemStack> outputItems = new ArrayList<>();
    private final List<BufferedFluidStack> outputFluids = new ArrayList<>();
    /** Hysteresis flags for upstream internal outputs that already reached their buffer high-water mark. */
    private final Set<String> throttledInternalItemOutputs = new HashSet<>();
    private final Set<String> throttledInternalFluidOutputs = new HashSet<>();
    /** Hysteresis flags for final/byproduct outputs waiting for real output buses or hatches. */
    private final Set<String> throttledExternalItemOutputs = new HashSet<>();
    private final Set<String> throttledExternalFluidOutputs = new HashSet<>();
    /** Active virtual recipe jobs. Each job records consumed inputs for diagnostics and NBT restore. */
    private final List<RunningJob> runningJobs = new ArrayList<>();
    /** Synced, fixed-size source for the main GUI runtime output estimate lines. */
    private List<String> runtimeOutputEstimateLines = new ArrayList<>();
    private final List<ProcessNode> cachedSchedulingOrder = new ArrayList<>();
    private final Map<Integer, ProcessNode> cachedRuntimeNodesById = new LinkedHashMap<>();
    private final Map<Integer, Double> runCreditByNode = new LinkedHashMap<>();
    private GraphAnalysisResult pendingGraphAnalysis;
    private GraphAnalysisResult runtimeGraphAnalysis;
    private GraphAnalysisResult deferredGraphAnalysis;
    private boolean hasDeferredRuntimeGraph;
    private final CycleRuntimeManager cycleRuntimeManager = new CycleRuntimeManager();
    private RuntimeRouteResolver runtimeRouteResolver = new RuntimeRouteResolver(null);
    private RuntimeResourceSnapshot runtimeResourceSnapshot;
    private final IntegratedFactoryScheduler.Context schedulerContext = new IntegratedFactoryScheduler.Context() {

        @Override
        public List<ProcessNode> schedulingOrder() {
            return buildSchedulingOrder();
        }

        @Override
        public int runningJobsForNode(int nodeId) {
            return countRunningJobsForNode(nodeId);
        }

        @Override
        public int effectiveParallelLimit(ProcessNode node) {
            return getEffectiveParallelLimit(node);
        }

        @Override
        public int effectiveDurationTicks(ProcessNode node) {
            return getEffectiveDurationTicks(node);
        }

        @Override
        public boolean isExternalOutputThrottled(ProcessNode node, int parallel, boolean debugRuntime) {
            return MTESuperIntegratedFactory.this.isExternalOutputThrottled(node, parallel, debugRuntime);
        }

        @Override
        public int runnableParallel(ProcessNode node, int parallelLimit, boolean debugRuntime) {
            return getRunnableParallel(node, parallelLimit, debugRuntime);
        }

        @Override
        public boolean tryStartNodeCandidate(NodeCandidate candidate, boolean debugRuntime) {
            return MTESuperIntegratedFactory.this.tryStartNodeCandidate(candidate, debugRuntime);
        }

        @Override
        public int maxNodeStartsPerTick() {
            return getMaxNodeStartsPerTick();
        }

        @Override
        public void updateRunCredits() {
            MTESuperIntegratedFactory.this.updateRunCredits();
        }

        @Override
        public double runCredit(int nodeId) {
            return runCreditByNode.getOrDefault(nodeId, 0.0D);
        }

        @Override
        public void subtractRunCredit(int nodeId, double amount) {
            runCreditByNode.put(nodeId, Math.max(0.0D, runCreditByNode.getOrDefault(nodeId, 0.0D) - amount));
        }

        @Override
        public int distanceToTerminal(ProcessNode node) {
            return MTESuperIntegratedFactory.this.distanceToTerminal(node, new LinkedHashMap<>());
        }

        @Override
        public boolean consumesAvailableInternalInput(ProcessNode node) {
            return MTESuperIntegratedFactory.this.consumesAvailableInternalInput(node);
        }

        @Override
        public boolean suppliesLowWater(ProcessNode node) {
            return MTESuperIntegratedFactory.this.suppliesLowWater(node);
        }

        @Override
        public boolean producesTargetOutput(ProcessNode node) {
            return MTESuperIntegratedFactory.this.producesTargetOutput(node);
        }

        @Override
        public boolean hasIncomingEdge(int nodeId) {
            return MTESuperIntegratedFactory.this.hasIncomingEdge(nodeId);
        }
    };
    private final IntegratedFactoryUnloadHandler.Context unloadContext = new IntegratedFactoryUnloadHandler.Context() {

        @Override
        public void discardRunningJobsWithLoss() {
            MTESuperIntegratedFactory.this.discardRunningJobsWithLoss();
        }

        @Override
        public void flushOutputBuffers() {
            MTESuperIntegratedFactory.this.flushOutputBuffers();
        }

        @Override
        public boolean shouldDebugExportInternalBuffer() {
            return MTESuperIntegratedFactory.this.shouldDebugExportInternalBuffer();
        }

        @Override
        public void moveAllInternalToOutput() {
            MTESuperIntegratedFactory.this.moveAllInternalToOutput();
        }

        @Override
        public void clearInternalRuntimeBuffersForUnload() {
            MTESuperIntegratedFactory.this.clearInternalRuntimeBuffersForUnload();
        }

        @Override
        public void clearStartupMaterialsForUnload() {
            MTESuperIntegratedFactory.this.clearStartupMaterialsForUnload();
        }

        @Override
        public ProcessRequirements processRequirements() {
            return MTESuperIntegratedFactory.this.processRequirements;
        }

        @Override
        public boolean addOutput(ItemStack stack) {
            return MTESuperIntegratedFactory.this.addOutput(stack);
        }

        @Override
        public void decrementStoredMachineDemandFor(ItemStack machine) {
            MTESuperIntegratedFactory.this.decrementStoredMachineDemandFor(machine);
        }

        @Override
        public boolean hasStoredProcessRequirements() {
            return MTESuperIntegratedFactory.this.hasStoredProcessRequirements();
        }

        @Override
        public void unloadCurrentProcessState() {
            MTESuperIntegratedFactory.this.unloadCurrentProcessState();
        }

        @Override
        public boolean hasDeferredRuntimeGraph() {
            return MTESuperIntegratedFactory.this.hasDeferredRuntimeGraph;
        }

        @Override
        public void installDeferredProcessSubmission() {
            MTESuperIntegratedFactory.this.installDeferredProcessSubmission();
        }

        @Override
        public ProcessRequirements pendingProcessRequirements() {
            return MTESuperIntegratedFactory.this.pendingProcessRequirements;
        }

        @Override
        public void installPendingProcessRequirements() {
            MTESuperIntegratedFactory.this.installPendingProcessRequirements();
        }

        @Override
        public void enterStandby() {
            MTESuperIntegratedFactory.this.factoryMode = MODE_STANDBY;
            MTESuperIntegratedFactory.this.ioCycleTicks = 0;
        }

        @Override
        public void markDirty() {
            getBaseMetaTileEntity().markDirty();
        }
    };
    private final IntegratedFactoryWatermarks.Context watermarkContext = new IntegratedFactoryWatermarks.Context() {

        @Override
        public Iterable<ProcessEdge> runtimeEdges() {
            return runtimeGraph.edges;
        }

        @Override
        public ProcessNode findRuntimeNode(int nodeId) {
            return MTESuperIntegratedFactory.this.findRuntimeNode(nodeId);
        }

        @Override
        public boolean itemMatches(ItemStack recipeInput, ItemStack provided) {
            return MTESuperIntegratedFactory.this.itemMatches(recipeInput, provided);
        }

        @Override
        public long stackAmount(ItemStack stack) {
            return getStackAmount(stack);
        }

        @Override
        public int effectiveParallelLimit(ProcessNode node) {
            return getEffectiveParallelLimit(node);
        }

        @Override
        public int effectiveDurationTicks(ProcessNode node) {
            return getEffectiveDurationTicks(node);
        }
    };
    private int factoryMode = MODE_STANDBY;
    private int ioCycleTicks;
    private long lastEnergySetupFailureLogTick = Long.MIN_VALUE;
    private long lastRuntimeDebugLogTick = Long.MIN_VALUE;
    private long lastPowerLossWarningTick = Long.MIN_VALUE;
    private Boolean lastControllerActiveState;
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
        syncControllerActiveState();
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
        syncControllerActiveState();
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
    public void onFirstTick_EM(IGregTechTileEntity baseMetaTileEntity) {
        super.onFirstTick_EM(baseMetaTileEntity);
        if (baseMetaTileEntity != null && baseMetaTileEntity.isServerSide()) {
            lastControllerActiveState = null;
            syncControllerActiveState();
            baseMetaTileEntity.issueTextureUpdate();
        }
    }

    @Override
    protected ProcessingLogic createProcessingLogic() {
        return new ProcessingLogic() {

            @Override
            public CheckRecipeResult process() {
                sanitizeParameterRelationships();
                syncControllerActiveState();
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
            sendPowerLossWarning(buildCurrentRunningJobSummary());
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
        boolean controllerActive = active || shouldRenderControllerActive(baseMetaTileEntity);
        if (controllerActive) {
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

    private boolean shouldRenderControllerActive(IGregTechTileEntity baseMetaTileEntity) {
        return baseMetaTileEntity != null && baseMetaTileEntity.isAllowedToWork() && factoryMode != MODE_STANDBY;
    }

    @Override
    public MultiblockTooltipBuilder createTooltip() {
        return new MultiblockTooltipBuilder()
            .addMachineType(tr("superfactory.machine.super_integrated_factory.tooltip.type"))
            .addInfo(tr("superfactory.machine.super_integrated_factory.tooltip.1"))
            .addInfo(tr("superfactory.machine.super_integrated_factory.tooltip.2"))
            .addInfo(tr("superfactory.machine.super_integrated_factory.tooltip.3"))
            .addInfo(tr("superfactory.machine.super_integrated_factory.tooltip.4"))
            .addInfo(tr("superfactory.machine.super_integrated_factory.tooltip.5"))
            .addInfo(tr("superfactory.machine.super_integrated_factory.tooltip.6"))
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
        builder.widget(new FakeSyncWidget.IntegerSyncer(() -> currentProcessStep, value -> currentProcessStep = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> totalProcessSteps, value -> totalProcessSteps = value))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> factoryMode, value -> {
                factoryMode = value;
                lastControllerActiveState = null;
            }))
            .widget(new FakeSyncWidget.IntegerSyncer(() -> ioCycleTicks, value -> ioCycleTicks = value))
            .widget(
                new FakeSyncWidget.StringSyncer(
                    () -> serializeLines(getRuntimeOutputEstimateLinesForSync(true)),
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
                    this::readProcessGraphPacket))
            .widget(
                new FakeSyncWidget<>(
                    () -> submittedProcessGraphSnapshot,
                    this::readSyncedSubmittedProcessGraphSnapshot,
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
        aNBT.setTag("DeferredProcessRequirements", deferredProcessRequirements.writeToNBT());
        aNBT.setTag("RuntimeGraph", runtimeGraph.writeToNBT());
        aNBT.setTag("PendingRuntimeGraph", pendingRuntimeGraph.writeToNBT());
        aNBT.setTag("DeferredRuntimeGraph", deferredRuntimeGraph.writeToNBT());
        aNBT.setBoolean("HasDeferredRuntimeGraph", hasDeferredRuntimeGraph);
        aNBT.setTag("SubmittedProcessGraphSnapshot", submittedProcessGraphSnapshot.writeToNBT());
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
        if (aNBT.hasKey("DeferredProcessRequirements", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            deferredProcessRequirements.readFromNBT(aNBT.getCompoundTag("DeferredProcessRequirements"));
        }
        if (aNBT.hasKey("RuntimeGraph", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            runtimeGraph.readFromNBT(aNBT.getCompoundTag("RuntimeGraph"));
            rebuildRuntimeSchedulingCache();
            runtimeGraphAnalysis = runtimeGraph.nodes.isEmpty() ? null
                : analyzeProcessGraph(runtimeGraph, "runtime-load");
            rebuildCycleRuntimeManager();
        }
        if (aNBT.hasKey("PendingRuntimeGraph", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            pendingRuntimeGraph.readFromNBT(aNBT.getCompoundTag("PendingRuntimeGraph"));
        }
        if (aNBT.hasKey("DeferredRuntimeGraph", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            deferredRuntimeGraph.readFromNBT(aNBT.getCompoundTag("DeferredRuntimeGraph"));
        }
        hasDeferredRuntimeGraph = aNBT.getBoolean("HasDeferredRuntimeGraph");
        if (hasDeferredRuntimeGraph && !deferredRuntimeGraph.nodes.isEmpty()) {
            deferredGraphAnalysis = analyzeProcessGraph(deferredRuntimeGraph, "deferred-load");
        }
        if (aNBT.hasKey("SubmittedProcessGraphSnapshot", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND)) {
            submittedProcessGraphSnapshot.readFromNBT(aNBT.getCompoundTag("SubmittedProcessGraphSnapshot"));
        }
        readItemList(aNBT.getTagList("InternalItems", Constants.NBT.TAG_COMPOUND), internalItems);
        readItemList(aNBT.getTagList("OutputItems", Constants.NBT.TAG_COMPOUND), outputItems);
        readFluidList(aNBT.getTagList("InternalFluids", Constants.NBT.TAG_COMPOUND), internalFluids);
        readFluidList(aNBT.getTagList("OutputFluids", Constants.NBT.TAG_COMPOUND), outputFluids);
        readRunningJobs(aNBT.getTagList("RunningJobs", Constants.NBT.TAG_COMPOUND));
        runtimeOutputEstimateLines = deserializeLines(aNBT.getString("RuntimeOutputEstimateLines"));
        lastControllerActiveState = null;
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
        ProcessGraph submittedGraph = new ProcessGraph();
        submittedGraph.readFromNBT(processGraph.writeToNBT());
        GraphAnalysisResult submittedAnalysis = analyzeProcessGraph(submittedGraph, "pending-submit");
        submittedProcessGraphSnapshot.readFromNBT(processGraph.writeToNBT());
        if (factoryMode == MODE_OUTPUT) {
            deferredProcessRequirements.readFromNBT(incoming.writeToNBT());
            deferredRuntimeGraph.readFromNBT(submittedGraph.writeToNBT());
            deferredGraphAnalysis = submittedAnalysis;
            hasDeferredRuntimeGraph = true;
            getBaseMetaTileEntity().markDirty();
            return;
        }
        applySubmittedProcessPlan(incoming, submittedGraph, submittedAnalysis);
        factoryMode = MODE_OUTPUT;
        ioCycleTicks = 0;
        getBaseMetaTileEntity().markDirty();
    }

    private void applySubmittedProcessPlan(ProcessRequirements incoming, ProcessGraph submittedGraph,
        GraphAnalysisResult submittedAnalysis) {
        pendingProcessRequirements.readFromNBT(incoming.writeToNBT());
        pendingRuntimeGraph.readFromNBT(submittedGraph.writeToNBT());
        pendingGraphAnalysis = submittedAnalysis;
        if (processRequirements.hasStoredAnything()) {
            retainReusableQualificationsForPendingGraph();
        }
        cancelCurrentProcessForOutput();
    }

    private void retainReusableQualificationsForPendingGraph() {
        for (ProcessRequirements.ItemDemand pendingDemand : pendingProcessRequirements.nonConsumables) {
            ProcessRequirements.ItemDemand currentDemand = findMatchingItemDemand(
                processRequirements.nonConsumables,
                pendingDemand.stack);
            if (currentDemand == null || currentDemand.stored <= 0) {
                continue;
            }
            int retained = Math.min(pendingDemand.required, currentDemand.stored);
            pendingDemand.stored = retained;
            currentDemand.stored -= retained;
        }
        Iterator<ItemStack> machineIterator = processRequirements.storedMachines.iterator();
        while (machineIterator.hasNext()) {
            ItemStack machine = machineIterator.next();
            if (isSuperProxyFactoryController(machine)) {
                continue;
            }
            ProcessRequirements.RecipeMapDemand pendingDemand = findPendingRecipeMapDemandForMachine(machine);
            if (pendingDemand == null || pendingDemand.stored >= pendingDemand.required) {
                continue;
            }
            pendingProcessRequirements.storedMachines.add(machine.copy());
            pendingDemand.stored++;
            machineIterator.remove();
            decrementStoredRecipeMapFor(machine);
        }
        if (Config.allowProxyFactoryAsIntegratedRecipeHost) {
            retainProxyFactoryHostsForPendingGraph();
        }
    }

    private void retainProxyFactoryHostsForPendingGraph() {
        Iterator<ItemStack> machineIterator = processRequirements.storedMachines.iterator();
        while (machineIterator.hasNext()) {
            ItemStack machine = machineIterator.next();
            if (!isSuperProxyFactoryController(machine)) {
                continue;
            }
            ProcessRequirements.RecipeMapDemand pendingDemand = findPendingRecipeMapDemandForProxyHost();
            if (pendingDemand == null) {
                continue;
            }
            pendingProcessRequirements.storedMachines.add(machine.copy());
            pendingDemand.proxyStored++;
            machineIterator.remove();
            decrementStoredProxyRecipeMap();
        }
    }

    private ProcessRequirements.ItemDemand findMatchingItemDemand(List<ProcessRequirements.ItemDemand> demands,
        ItemStack stack) {
        if (stack == null) {
            return null;
        }
        for (ProcessRequirements.ItemDemand demand : demands) {
            if (demand.stack != null && GTUtility.areStacksEqual(demand.stack, stack, true)) {
                return demand;
            }
        }
        return null;
    }

    private ProcessRequirements.RecipeMapDemand findPendingRecipeMapDemandForMachine(ItemStack machine) {
        for (ProcessRequirements.RecipeMapDemand demand : pendingProcessRequirements.recipeMaps) {
            if (demand.missing() > 0 && machineSupportsRecipeMap(machine, demand.recipeMapName)) {
                return demand;
            }
        }
        return null;
    }

    private ProcessRequirements.RecipeMapDemand findPendingRecipeMapDemandForProxyHost() {
        for (ProcessRequirements.RecipeMapDemand demand : pendingProcessRequirements.recipeMaps) {
            if (demand.missing() > 0) {
                return demand;
            }
        }
        return null;
    }

    public boolean restoreSubmittedProcessGraph(EntityPlayer player) {
        if (submittedProcessGraphSnapshot.nodes.isEmpty()) {
            sendProcessCanvasStatus(
                player,
                tr("superfactory.machine.super_integrated_factory.restore.empty"),
                0xFFFF7777);
            return false;
        }
        processGraph.readFromNBT(submittedProcessGraphSnapshot.writeToNBT());
        getBaseMetaTileEntity().markDirty();
        sendProcessCanvasStatus(
            player,
            tr("superfactory.machine.super_integrated_factory.restore.success"),
            0xFF75D17C);
        return true;
    }

    public NBTTagCompound getSubmittedProcessGraphSnapshotTag() {
        return submittedProcessGraphSnapshot.writeToNBT();
    }

    public void exportProcessRawMaterials(EntityPlayer player) {
        new RawMaterialExporter(new RawMaterialExporter.Context() {

            @Override
            public ProcessGraph processGraph() {
                return processGraph;
            }

            @Override
            public Iterable<IDualInputHatch> dualInputHatches() {
                return mDualInputHatches;
            }

            @Override
            public Iterable<MTEHatchInputBus> inputBusses() {
                return mInputBusses;
            }

            @Override
            public Iterable<MTEHatchInput> inputHatches() {
                return mInputHatches;
            }

            @Override
            public boolean isFluidDisplay(ItemStack stack) {
                return MTESuperIntegratedFactory.this.isFluidDisplay(stack);
            }

            @Override
            public boolean itemMatches(ItemStack input, ItemStack output) {
                return MTESuperIntegratedFactory.this.itemMatches(input, output);
            }

            @Override
            public String safeNodeName(ProcessNode node) {
                return MTESuperIntegratedFactory.this.safeNodeName(node);
            }

            @Override
            public String translate(String key) {
                return tr(key);
            }

            @Override
            public void sendStatus(EntityPlayer player, String message, int color) {
                sendProcessCanvasStatus(player, message, color);
            }

            @Override
            public void markDirty() {
                getBaseMetaTileEntity().markDirty();
            }
        }).export(player);
    }

    private void sendProcessCanvasStatus(EntityPlayer player, String message, int color) {
        if (player instanceof EntityPlayerMP playerMP) {
            NetworkLoader.INSTANCE.sendTo(new MessageProcessCanvasStatus(message, color), playerMP);
        } else if (player != null) {
            GTUtility.sendChatToPlayer(player, message);
        }
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
        ProcessNodeRecipeApplier.apply(processGraph, nodeId, recipeTag);
    }

    public static String buildRecipeFingerprint(GTRecipe recipe) {
        return ProcessNodeRecipeApplier.buildRecipeFingerprint(recipe);
    }

    public static String buildRecipeFingerprint(com.gtnewhorizons.modularui.api.forge.ItemStackHandler inputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler nonConsumables, int[] outputChances, int duration,
        long euPerTick) {
        return ProcessNodeRecipeApplier
            .buildRecipeFingerprint(inputs, outputs, nonConsumables, outputChances, duration, euPerTick);
    }

    public static String buildRecipeFingerprint(com.gtnewhorizons.modularui.api.forge.ItemStackHandler inputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        com.gtnewhorizons.modularui.api.forge.ItemStackHandler nonConsumables, int duration, long euPerTick) {
        return ProcessNodeRecipeApplier.buildRecipeFingerprint(inputs, outputs, nonConsumables, duration, euPerTick);
    }

    private String buildNodeFingerprint(ProcessNode node) {
        return node.buildRecipeFingerprint();
    }

    public static void applyRecipeOutputChances(com.gtnewhorizons.modularui.api.forge.ItemStackHandler outputs,
        GTRecipe recipe, ProcessNode node) {
        ProcessNodeRecipeApplier.applyRecipeOutputChances(outputs, recipe, node);
    }

    private ProcessNode getSelectedNode() {
        return processGraph.findNode(processGraph.selectedNodeId);
    }

    private void readSyncedProcessGraph(ProcessGraph syncedGraph) {
        processGraph.readFromNBT(syncedGraph.writeToNBT());
    }

    private void readSyncedSubmittedProcessGraphSnapshot(ProcessGraph syncedGraph) {
        submittedProcessGraphSnapshot.readFromNBT(syncedGraph.writeToNBT());
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

    private GraphAnalysisResult analyzeProcessGraph(ProcessGraph graph, String phase) {
        GraphAnalysisResult analysis = new ProcessGraphAnalyzer().analyze(graph);
        logGraphAnalysisAcceptance(analysis, phase);
        return analysis;
    }

    private void logGraphAnalysisAcceptance(GraphAnalysisResult analysis, String phase) {
        if (!GRAPH_ANALYSIS_ACCEPTANCE_LOGGING || analysis == null) {
            return;
        }
        SuperFactory.LOG.info(
            "[Super Integrated Factory/GraphAnalysis] phase={}, nodes={}, edges={}, targets={}, sources={}, sinks={}, cycles={}, warnings={}, errors={}",
            phase,
            analysis.nodesById.size(),
            analysis.graph.edges.size(),
            analysis.allTargetOutputs.size(),
            analysis.sourceNodeIds.size(),
            analysis.sinkNodeIds.size(),
            analysis.cycles.size(),
            analysis.validation.warningCount(),
            analysis.validation.errorCount());
        SuperFactory.LOG.info(
            "[Super Integrated Factory/GraphAnalysis] phase={}, sourceNodes={}, sinkNodes={}, targetOutputs={}",
            phase,
            analysis.sourceNodeIds,
            analysis.sinkNodeIds,
            analysis.allTargetOutputs);
        List<String> edgeSummaries = new ArrayList<>();
        int edgeCount = 0;
        for (ProcessEdge edge : analysis.graph.edges) {
            ProcessNode from = analysis.nodesById.get(edge.fromNodeId);
            ProcessNode to = analysis.nodesById.get(edge.toNodeId);
            edgeSummaries.add(
                edge.id + ":"
                    + edge.fromNodeId
                    + "/"
                    + graphNodeLogName(from)
                    + "->"
                    + edge.toNodeId
                    + "/"
                    + graphNodeLogName(to)
                    + (edge.resourceKey == null || edge.resourceKey.isEmpty() ? "" : "[" + edge.resourceKey + "]"));
            edgeCount++;
            if (edgeCount >= 32 && analysis.graph.edges.size() > edgeCount) {
                edgeSummaries.add("...+" + (analysis.graph.edges.size() - edgeCount));
                break;
            }
        }
        SuperFactory.LOG.info("[Super Integrated Factory/GraphAnalysis] phase={}, edges={}", phase, edgeSummaries);
        logWaterlineThresholds(analysis.graph, analysis, phase);
        for (CycleInfo cycle : analysis.cycles) {
            SuperFactory.LOG.info(
                "[Super Integrated Factory/GraphAnalysis] phase={}, cycle={}, nodes={}, material={}, producedRate={}, consumedRate={}, netRate={}, startupMaterials={}, validSingleMaterial={}, positiveNet={}",
                phase,
                cycle.cycleId,
                cycle.nodeIds,
                cycle.cycleMaterial,
                cycle.producedRate,
                cycle.consumedRate,
                cycle.netRate,
                cycle.requiredStartupMaterials,
                cycle.validSingleMaterialCycle,
                cycle.positiveNetOutput);
        }
        int loggedEntries = 0;
        for (GraphValidationError entry : analysis.validation.entries()) {
            SuperFactory.LOG.info("[Super Integrated Factory/GraphAnalysis] phase={}, {}", phase, entry);
            loggedEntries++;
            if (loggedEntries >= 16 && analysis.validation.entries()
                .size() > loggedEntries) {
                SuperFactory.LOG.info(
                    "[Super Integrated Factory/GraphAnalysis] phase={}, remainingValidationEntries={}",
                    phase,
                    analysis.validation.entries()
                        .size() - loggedEntries);
                break;
            }
        }
    }

    private String graphNodeLogName(ProcessNode node) {
        if (node == null) {
            return "?";
        }
        String name = node.name == null || node.name.isEmpty() ? "node" : node.name;
        return name.replace(',', ' ')
            .replace('[', '(')
            .replace(']', ')');
    }

    private void logWaterlineThresholds(ProcessGraph graph, GraphAnalysisResult analysis, String phase) {
        if (graph == null || analysis == null) {
            return;
        }
        for (ProcessNode node : graph.nodes) {
            if (node == null) {
                continue;
            }
            for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
                ItemStack output = node.outputHandler.getStackInSlot(slot);
                if (output == null) {
                    continue;
                }
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
                MaterialKey key = fluid == null ? materialKeyOf(output) : MaterialKey.ofFluid(fluid);
                OutputRouteType route = new RuntimeRouteResolver(analysis).resolve(node.id, key);
                long perRun = getStackAmount(output);
                long batch = getExpectedOutputAmount(node, output, slot, getEffectiveParallelLimit(node));
                long low = route == OutputRouteType.INTERNAL || route == OutputRouteType.CYCLE_INTERNAL
                    ? fluid == null ? getInternalItemLowWater(node, output, batch)
                        : getInternalFluidLowWater(node, fluid, batch)
                    : fluid == null ? getExternalItemLowWater(node, batch) : getExternalFluidLowWater(node, batch);
                long high = route == OutputRouteType.INTERNAL || route == OutputRouteType.CYCLE_INTERNAL
                    ? getInternalHighWater(low)
                    : getExternalHighWater(low);
                long duration = getWaterlineDuration(node);
                long throughput = getOutputThroughputPerSecond(node, batch);
                SuperFactory.LOG.info(
                    "[Super Integrated Factory/Waterline] phase={}, node={}, output={}, route={}, perRun={}, duration={}, batch={}, throughputPerSecond={}, low={}, high={}",
                    phase,
                    describeNode(node),
                    fluid == null ? describeItem(output) : describeFluid(fluid),
                    route,
                    perRun,
                    duration,
                    batch,
                    throughput,
                    low,
                    high);
            }
        }
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
        if (!getBaseMetaTileEntity().isAllowedToWork()) {
            return tr("superfactory.machine.super_integrated_factory.gui.machine_mode") + ": "
                + EnumChatFormatting.RED
                + tr("superfactory.machine.super_integrated_factory.gui.power_disabled");
        }
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
            if (Config.allowProxyFactoryAsIntegratedRecipeHost) {
                screenElements.widget(
                    new TextWidget(
                        EnumChatFormatting.GRAY
                            + tr("superfactory.machine.super_integrated_factory.gui.recipe_hosts_proxy_hint"))
                                .setDefaultColor(Color.WHITE.normal));
            }
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

    private List<String> getRuntimeOutputEstimateLinesForSync(boolean mainGuiOpen) {
        if (shouldUpdateRuntimeOutputLines(mainGuiOpen)) {
            runtimeOutputEstimateLines = buildActiveRuntimeOutputLines();
        }
        return runtimeOutputEstimateLines;
    }

    private boolean shouldUpdateRuntimeOutputLines(boolean mainGuiOpen) {
        return factoryMode == MODE_RUNNING && (mainGuiOpen || activeProcessGui != null);
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
        if (amperage > 9999L) {
            return String.format(java.util.Locale.ROOT, "%.2e EU/t", (double) euPerTick);
        }
        return amperage + "A " + GTValues.VN[tier] + "/t" + EnumChatFormatting.GRAY + " (" + euPerTick + " EU/t)";
    }

    private String buildCurrentRunningJobSummary() {
        if (runningJobs.isEmpty()) {
            return "";
        }
        for (RunningJob job : runningJobs) {
            ProcessNode node = findRuntimeNode(job.nodeId);
            if (node != null) {
                return formatRunningJobSummary(node, job);
            }
        }
        return "";
    }

    private String formatRunningJobSummary(ProcessNode node, RunningJob job) {
        String nodeName = trimToDisplayWidth(safeNodeName(node), 48);
        String recipe = buildNodeRecipeSummary(node);
        long euPerTick = safeMultiply(getJobEuPerTick(job, node), Math.max(1, job.parallel));
        return "节点 " + nodeName
            + ": "
            + recipe
            + "，耗时="
            + Math.max(0, job.durationTicks)
            + "t"
            + "，耗能="
            + formatPowerLossEnergyDisplay(euPerTick)
            + "，并行数="
            + Math.max(1, job.parallel)
            + "，超频次数="
            + Math.max(0, getEffectiveOverclockCount(node));
    }

    private String buildNodeRecipeSummary(ProcessNode node) {
        if (node == null) {
            return "?";
        }
        String inputs = describeNodeInputs(node);
        String outputs = describeNodeOutputs(node);
        return inputs + " = " + outputs;
    }

    private String describeNodeInputs(ProcessNode node) {
        ArrayList<String> parts = new ArrayList<>();
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack stack = node.inputHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            parts.add(describeItem(stack));
            if (parts.size() >= 8) {
                break;
            }
        }
        for (int slot = 0; slot < node.nonConsumableHandler.getSlots() && parts.size() < 8; slot++) {
            ItemStack stack = node.nonConsumableHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            parts.add(describeItem(stack));
        }
        return parts.isEmpty() ? "?" : String.join(" + ", parts);
    }

    private String describeNodeOutputs(ProcessNode node) {
        ArrayList<String> parts = new ArrayList<>();
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack stack = node.outputHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            parts.add(describeItem(stack));
            if (parts.size() >= 8) {
                break;
            }
        }
        return parts.isEmpty() ? "?" : String.join(" + ", parts);
    }

    private String formatPowerLossEnergyDisplay(long euPerTick) {
        if (euPerTick <= 0L) {
            return "0 EU/t";
        }
        long maxVoltage = GTValues.V[GTValues.V.length - 1];
        long amperage = Math.max(1L, (euPerTick + maxVoltage - 1L) / maxVoltage);
        return amperage + "A MAX EU/t (" + euPerTick + " EU/t)";
    }

    private void sendPowerLossWarning(String recipeSummary) {
        IGregTechTileEntity baseMetaTileEntity = getBaseMetaTileEntity();
        if (baseMetaTileEntity == null || !baseMetaTileEntity.isServerSide()) {
            return;
        }
        if (!canSendPowerLossWarning(baseMetaTileEntity)) {
            return;
        }
        EntityPlayer owner = findOnlineOwner(baseMetaTileEntity);
        if (owner == null) {
            return;
        }
        String coord = "(" + baseMetaTileEntity
            .getXCoord() + ", " + baseMetaTileEntity.getYCoord() + ", " + baseMetaTileEntity.getZCoord() + ")";
        String machine = getMachineDisplayName(baseMetaTileEntity);
        String message = "在 " + coord
            + " 的 \""
            + machine
            + "\" 发生跳电，执行配方为 \""
            + (recipeSummary == null || recipeSummary.isEmpty() ? "?" : recipeSummary)
            + "\"";
        GTUtility.sendChatToPlayer(owner, message);
    }

    private boolean canSendPowerLossWarning(IGregTechTileEntity baseMetaTileEntity) {
        if (baseMetaTileEntity == null) {
            return false;
        }
        long tick = baseMetaTileEntity.getTimer();
        if (tick - lastPowerLossWarningTick < 100L) {
            return false;
        }
        lastPowerLossWarningTick = tick;
        return true;
    }

    private EntityPlayer findOnlineOwner(IGregTechTileEntity baseMetaTileEntity) {
        if (baseMetaTileEntity == null || baseMetaTileEntity.getOwnerUuid() == null) {
            return null;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return null;
        }
        for (Object obj : server.getConfigurationManager().playerEntityList) {
            if (obj instanceof EntityPlayer player && baseMetaTileEntity.getOwnerUuid()
                .equals(player.getUniqueID())) {
                return player;
            }
        }
        return null;
    }

    private String getMachineDisplayName(IGregTechTileEntity baseMetaTileEntity) {
        if (baseMetaTileEntity != null && baseMetaTileEntity.getMetaTileEntity() != null) {
            String localName = baseMetaTileEntity.getMetaTileEntity()
                .getLocalName();
            if (localName != null && !localName.isEmpty()) {
                return localName;
            }
        }
        return getLocalName();
    }

    private void syncControllerActiveState() {
        IGregTechTileEntity baseMetaTileEntity = getBaseMetaTileEntity();
        if (baseMetaTileEntity == null || !baseMetaTileEntity.isServerSide()) {
            return;
        }
        boolean active = baseMetaTileEntity.isAllowedToWork() && factoryMode != MODE_STANDBY;
        boolean shouldUpdateTexture = lastControllerActiveState == null || lastControllerActiveState != active
            || baseMetaTileEntity.isActive() != active;
        if (!shouldUpdateTexture) {
            return;
        }
        lastControllerActiveState = active;
        if (baseMetaTileEntity.isActive() != active) {
            baseMetaTileEntity.setActive(active);
        }
        baseMetaTileEntity.issueTextureUpdate();
    }

    private long totalRunningEuPerTick() {
        long totalEu = 0L;
        for (RunningJob job : runningJobs) {
            ProcessNode node = findRuntimeNode(job.nodeId);
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
                    if (isSuperProxyFactoryController(controller)) {
                        demand.proxyStored++;
                    } else {
                        demand.stored++;
                    }
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
        boolean debugRuntime = Config.debugIntegratedFactoryRuntime && tick - lastRuntimeDebugLogTick >= 20L;
        if (!getBaseMetaTileEntity().isAllowedToWork()) {
            sendPowerLossWarning(buildCurrentRunningJobSummary());
            discardRunningJobsForPowerLoss();
            clearMachineWorkDisplay();
            getBaseMetaTileEntity().markDirty();
            return;
        }
        try {
            flushOutputBuffers();
            if (hasExternalOutputs() && runningJobs.isEmpty()) {
                clearMachineWorkDisplay();
                getBaseMetaTileEntity().markDirty();
                return;
            }
            runtimeResourceSnapshot = buildRuntimeResourceSnapshot();
            advanceRunningJobs();
            if (!isWirelessModeEnabled() && !runningJobs.isEmpty() && !canSustainWiredRuntimePower()) {
                stopMachine(ShutDownReasonRegistry.POWER_LOSS);
                clearMachineWorkDisplay();
                getBaseMetaTileEntity().markDirty();
                return;
            }
            scheduleRunnableNodes(debugRuntime);
            updateRuntimeProgressDisplay();
            if (shouldUpdateRuntimeOutputLines(false)) {
                runtimeOutputEstimateLines = buildActiveRuntimeOutputLines();
            }
            if (debugRuntime) {
                logRuntimeWaterlineState();
                lastRuntimeDebugLogTick = tick;
            }
            getBaseMetaTileEntity().markDirty();
        } finally {
            runtimeResourceSnapshot = null;
        }
    }

    /*
     * OUTPUT mode is the only state allowed to dismantle runtime state. It repeatedly exports products, discards active
     * jobs with their already-consumed inputs, and only promotes a pending graph after every local buffer is empty.
     */
    private void processOutputMode() {
        IntegratedFactoryUnloadHandler.processOutputMode(unloadContext);
    }

    private void cancelCurrentProcessForOutput() {
        currentProcessStep = 0;
        totalProcessSteps = 0;
        processRequirements.nonConsumables.removeIf(demand -> demand.stored <= 0);
        processRequirements.recipeMaps.removeIf(demand -> demand.totalStored() <= 0);
        getBaseMetaTileEntity().markDirty();
    }

    private void unloadCurrentProcessState() {
        currentProcessStep = 0;
        totalProcessSteps = 0;
        processRequirements.clear();
        runtimeGraph.readFromNBT(new ProcessGraph().writeToNBT());
        runtimeGraphAnalysis = null;
        rebuildRuntimeSchedulingCache();
        rebuildCycleRuntimeManager();
        internalItems.clear();
        internalFluids.clear();
        outputItems.clear();
        outputFluids.clear();
        runningJobs.clear();
        runCreditByNode.clear();
    }

    private void installPendingProcessRequirements() {
        ProcessRequirements pending = pendingProcessRequirements.copy();
        pendingProcessRequirements.clear();
        processRequirements.readFromNBT(pending.writeToNBT());
        runtimeGraph.readFromNBT(pendingRuntimeGraph.writeToNBT());
        runtimeGraphAnalysis = pendingGraphAnalysis == null ? analyzeProcessGraph(runtimeGraph, "runtime-install")
            : pendingGraphAnalysis;
        if (pendingGraphAnalysis != null) {
            logGraphAnalysisAcceptance(runtimeGraphAnalysis, "runtime-install");
        }
        logWaterlineThresholds(runtimeGraph, runtimeGraphAnalysis, "runtime-install");
        pendingGraphAnalysis = null;
        rebuildRuntimeSchedulingCache();
        rebuildCycleRuntimeManager();
        pendingRuntimeGraph.readFromNBT(new ProcessGraph().writeToNBT());
        clearRuntimeBuffers();
        runtimeOutputEstimateLines = new ArrayList<>();
        factoryMode = MODE_INPUT;
        ioCycleTicks = 0;
        totalProcessSteps = countSubmittedSteps();
    }

    private void installDeferredProcessSubmission() {
        ProcessRequirements deferredRequirements = deferredProcessRequirements.copy();
        ProcessGraph deferredGraph = new ProcessGraph();
        deferredGraph.readFromNBT(deferredRuntimeGraph.writeToNBT());
        GraphAnalysisResult analysis = deferredGraphAnalysis;
        deferredProcessRequirements.clear();
        deferredRuntimeGraph.readFromNBT(new ProcessGraph().writeToNBT());
        deferredGraphAnalysis = null;
        hasDeferredRuntimeGraph = false;
        applySubmittedProcessPlan(deferredRequirements, deferredGraph, analysis);
        if (pendingProcessRequirements.hasSubmittedDemands()) {
            installPendingProcessRequirements();
        } else {
            factoryMode = MODE_STANDBY;
            ioCycleTicks = 0;
        }
    }

    private void discardRunningJobsWithLoss() {
        for (RunningJob job : runningJobs) {
            refundRuntimeEnergy(job.reservedEnergy);
        }
        runningJobs.clear();
        runtimeOutputEstimateLines = new ArrayList<>();
    }

    private void clearInternalRuntimeBuffersForUnload() {
        internalItems.clear();
        internalFluids.clear();
    }

    private void clearStartupMaterialsForUnload() {
        for (ProcessRequirements.ItemDemand demand : processRequirements.startupItems) {
            demand.stored = 0;
        }
        for (ProcessRequirements.FluidDemand demand : processRequirements.startupFluids) {
            demand.stored = 0;
        }
    }

    private boolean shouldDebugExportInternalBuffer() {
        return Config.debugExportIntegratedFactoryInternalBuffer;
    }

    private void clearRuntimeBuffers() {
        internalItems.clear();
        internalFluids.clear();
        outputItems.clear();
        outputFluids.clear();
        runningJobs.clear();
        throttledInternalItemOutputs.clear();
        throttledInternalFluidOutputs.clear();
        throttledExternalItemOutputs.clear();
        throttledExternalFluidOutputs.clear();
        runtimeOutputEstimateLines = new ArrayList<>();
        currentProcessStep = 0;
        lEUt = 0L;
        mEUt = 0;
        mProgresstime = 0;
        mMaxProgresstime = 0;
    }

    private void rebuildCycleRuntimeManager() {
        cycleRuntimeManager.rebuild(runtimeGraphAnalysis, this::calculateCycleReserve);
        runtimeRouteResolver = new RuntimeRouteResolver(runtimeGraphAnalysis);
    }

    private long calculateCycleReserve(CycleInfo cycle) {
        if (cycle == null || cycle.cycleMaterial == null) {
            return 1L;
        }
        long reserve = 0L;
        for (Integer nodeId : cycle.nodeIds) {
            ProcessNode node = findRuntimeNode(nodeId);
            if (node == null) {
                continue;
            }
            for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
                ItemStack input = node.inputHandler.getStackInSlot(slot);
                if (input == null) {
                    continue;
                }
                MaterialKey key = materialKeyOf(input);
                if (cycle.cycleMaterial.equals(key)) {
                    long amount = isFluidDisplay(input) ? Math.max(1L, GTUtility.getFluidFromDisplayStack(input).amount)
                        : getStackAmount(input);
                    reserve = Math.max(reserve, safeMultiply(amount, getEffectiveParallelLimit(node)));
                }
            }
        }
        return Math.max(1L, reserve);
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
            demand.proxyStored = 0;
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
                ProcessNode node = findRuntimeNode(job.nodeId);
                if (node != null) {
                    finishRunningJob(node, job.parallel);
                }
                iterator.remove();
                currentProcessStep++;
            }
        }
    }

    private void scheduleRunnableNodes(boolean debugRuntime) {
        IntegratedFactoryScheduler.schedule(schedulerContext, debugRuntime);
    }

    private boolean tryStartNodeCandidate(NodeCandidate candidate, boolean debugRuntime) {
        ProcessNode node = candidate.node;
        int effectiveDurationTicks = getEffectiveDurationTicks(node);
        long effectiveEuPerTick = getEffectiveEuPerTick(node);
        startRecipeProcessing();
        try {
            if (!canStartNode(node, candidate.actualParallel, debugRuntime)) {
                return false;
            }
            RunningJob job = new RunningJob(
                node.id,
                candidate.actualParallel,
                effectiveDurationTicks,
                effectiveEuPerTick);
            long jobEuPerTick = safeMultiply(effectiveEuPerTick, Math.max(1L, candidate.actualParallel));
            long jobEnergy = safeMultiply(jobEuPerTick, Math.max(1L, job.durationTicks));
            long totalEuAfterStart = safeAddLong(totalRunningEuPerTick(), jobEuPerTick);
            if (!isWirelessModeEnabled() && !canStartWiredRuntimeJob(totalEuAfterStart)) {
                return false;
            }
            boolean energyReserved = reserveRuntimeEnergy(jobEnergy);
            job.reservedEnergy = energyReserved && isWirelessModeEnabled() ? jobEnergy : 0L;
            if (energyReserved && consumeNodeInputs(node, job, candidate.actualParallel)) {
                runningJobs.add(job);
                runtimeResourceSnapshot = buildRuntimeResourceSnapshot();
                return true;
            }
            refundRuntimeEnergy(job.reservedEnergy);
            job.reservedEnergy = 0L;
            return false;
        } finally {
            endRecipeProcessing();
        }
    }

    private void updateRunCredits() {
        for (ProcessNode node : buildSchedulingOrder()) {
            double credit = runCreditByNode.getOrDefault(node.id, 0.0D);
            credit += 1.0D / Math.max(1, getEffectiveDurationTicks(node));
            runCreditByNode.put(node.id, Math.min(4.0D, credit));
        }
    }

    private int getMaxNodeStartsPerTick() {
        return Math.max(1, Config.superIntegratedFactoryMaxNodeStartsPerTick);
    }

    private List<ProcessNode> buildSchedulingOrder() {
        if (!cachedSchedulingOrder.isEmpty()) {
            return cachedSchedulingOrder;
        }
        rebuildRuntimeSchedulingCache();
        return cachedSchedulingOrder;
    }

    private void rebuildRuntimeSchedulingCache() {
        cachedSchedulingOrder.clear();
        cachedRuntimeNodesById.clear();
        runCreditByNode.keySet()
            .removeIf(nodeId -> runtimeGraph.findNode(nodeId) == null);
        ArrayList<ProcessNode> nodes = new ArrayList<>(runtimeGraph.nodes);
        for (ProcessNode node : nodes) {
            cachedRuntimeNodesById.put(node.id, node);
        }
        Map<Integer, Integer> terminalDistanceCache = new LinkedHashMap<>();
        nodes.sort(
            Comparator.comparingInt((ProcessNode node) -> distanceToTerminal(node, terminalDistanceCache))
                .thenComparingInt(node -> node.id));
        cachedSchedulingOrder.addAll(nodes);
    }

    private ProcessNode findRuntimeNode(int nodeId) {
        if (cachedRuntimeNodesById.isEmpty() && !runtimeGraph.nodes.isEmpty()) {
            rebuildRuntimeSchedulingCache();
        }
        return cachedRuntimeNodesById.get(nodeId);
    }

    private boolean consumesAvailableInternalInput(ProcessNode node) {
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack input = node.inputHandler.getStackInSlot(slot);
            if (input == null) {
                continue;
            }
            if (isFluidDisplay(input)) {
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(input);
                long available = runtimeResourceSnapshot == null ? countFluidInBuffer(internalFluids, fluid)
                    : runtimeResourceSnapshot.internalFluidAmount(fluid);
                if (available >= getStackAmount(input)) {
                    return true;
                }
            } else {
                long available = runtimeResourceSnapshot == null ? countItemInBuffer(internalItems, input)
                    : runtimeResourceSnapshot.internalItemAmount(input);
                if (available >= getStackAmount(input)) {
                    return true;
                }
            }
        }
        return false;
    }

    private RuntimeResourceSnapshot buildRuntimeResourceSnapshot() {
        startRecipeProcessing();
        try {
            RuntimeResourceSnapshot snapshot = new RuntimeResourceSnapshot(new RuntimeResourceSnapshot.Context() {

                @Override
                public Iterable<IDualInputHatch> dualInputHatches() {
                    return mDualInputHatches;
                }

                @Override
                public Iterable<RunningJob> runningJobs() {
                    return runningJobs;
                }

                @Override
                public ProcessNode findRuntimeNode(int nodeId) {
                    return MTESuperIntegratedFactory.this.findRuntimeNode(nodeId);
                }

                @Override
                public long getStackAmount(ItemStack stack) {
                    return MTESuperIntegratedFactory.this.getStackAmount(stack);
                }

                @Override
                public MaterialKey materialKeyOf(ItemStack stack) {
                    return MTESuperIntegratedFactory.this.materialKeyOf(stack);
                }

                @Override
                public CycleRuntimeState cycleRuntimeState(MaterialKey key) {
                    return cycleRuntimeManager.get(key);
                }

                @Override
                public boolean itemMatchesUncached(ItemStack recipeInput, ItemStack provided) {
                    return MTESuperIntegratedFactory.this.itemMatchesUncached(recipeInput, provided);
                }
            });
            snapshot.captureInternalItems(internalItems);
            snapshot.captureInternalFluids(internalFluids);
            snapshot.captureLiveItems(normalizeLiveItemRefs(getStoredInputs()));
            snapshot.captureLiveFluids(normalizeLiveFluidRefs(getStoredFluids()));
            snapshot.captureDualInputs();
            snapshot.captureIncomingWithinLookahead();
            return snapshot;
        } finally {
            endRecipeProcessing();
        }
    }

    private ItemStack[] normalizeLiveItemRefs(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return GTValues.emptyItemStackArray;
        }
        ArrayList<ItemStack> normalized = new ArrayList<>();
        Set<ItemStack> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (ItemStack stack : stacks) {
            if (stack != null && stack.stackSize > 0 && seen.add(stack)) {
                normalized.add(stack);
            }
        }
        return normalized.toArray(new ItemStack[0]);
    }

    private FluidStack[] normalizeLiveFluidRefs(List<FluidStack> fluids) {
        if (fluids == null || fluids.isEmpty()) {
            return GTValues.emptyFluidStackArray;
        }
        ArrayList<FluidStack> normalized = new ArrayList<>();
        Set<FluidStack> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (FluidStack stack : fluids) {
            if (stack != null && stack.amount > 0 && seen.add(stack)) {
                normalized.add(stack);
            }
        }
        return normalized.toArray(new FluidStack[0]);
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
            ProcessNode next = findRuntimeNode(edge.toNodeId);
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

    private boolean hasIncomingEdge(int nodeId) {
        for (ProcessEdge edge : runtimeGraph.edges) {
            if (edge.toNodeId == nodeId && edge.fromNodeId != nodeId) {
                return true;
            }
        }
        return false;
    }

    private boolean producesTargetOutput(ProcessNode node) {
        if (node == null || runtimeGraphAnalysis == null) {
            return false;
        }
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            MaterialKey key = materialKeyOf(node.outputHandler.getStackInSlot(slot));
            if (key != null && runtimeGraphAnalysis.allTargetOutputs.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean suppliesLowWater(ProcessNode node) {
        if (node == null) {
            return false;
        }
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack output = node.outputHandler.getStackInSlot(slot);
            if (output == null || !isInternalRoute(node, output)) {
                continue;
            }
            FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
            if (fluid != null) {
                long projected = runtimeResourceSnapshot == null ? countFluidInBuffer(internalFluids, fluid)
                    : runtimeResourceSnapshot.projectedFluidAmount(fluid);
                long batch = getExpectedOutputAmount(node, output, slot, getEffectiveParallelLimit(node));
                if (projected <= getInternalFluidLowWater(node, fluid, batch)) {
                    return true;
                }
            } else {
                long projected = runtimeResourceSnapshot == null ? countItemInBuffer(internalItems, output)
                    : runtimeResourceSnapshot.projectedItemAmount(output);
                long batch = getExpectedOutputAmount(node, output, slot, getEffectiveParallelLimit(node));
                if (projected <= getInternalItemLowWater(node, output, batch)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int getRunnableParallel(ProcessNode node, int parallelLimit, boolean debugRuntime) {
        if (node.isRecyclerNode()) {
            long available = availableRecyclerInputAmount(node);
            long perRun = recyclerInputCost(node);
            if (available < perRun) {
                return 0;
            }
            long runnable = Math.min(Math.max(1L, parallelLimit), available / perRun);
            if (debugRuntime && runnable < Math.max(1L, parallelLimit)) {
                SuperFactory.LOG.info(
                    "[Super Integrated Factory/Runtime] 节点原料不足未达最大并行: node={}, runnable={}, limit={}, needPerRun={}, available={}",
                    describeNode(node),
                    runnable,
                    Math.max(1, parallelLimit),
                    perRun,
                    available);
            }
            return (int) Math.min(Integer.MAX_VALUE, runnable);
        }
        long runnable = Math.max(1, parallelLimit);
        boolean hasInput = false;
        int bottleneckSlot = -1;
        long bottleneckNeed = 0L;
        long bottleneckAvailable = 0L;
        ItemStack bottleneckStack = null;
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack stack = node.inputHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            hasInput = true;
            long perRun = Math.max(1L, getStackAmount(stack));
            long available = isFluidDisplay(stack)
                ? availableFluidAmount(node, GTUtility.getFluidFromDisplayStack(stack))
                : availableItemAmount(node, stack);
            long slotRunnable = available / perRun;
            if (slotRunnable < runnable) {
                bottleneckSlot = slot;
                bottleneckNeed = perRun;
                bottleneckAvailable = available;
                bottleneckStack = stack;
            }
            runnable = Math.min(runnable, slotRunnable);
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
        if (debugRuntime && hasInput && runnable < Math.max(1L, parallelLimit) && bottleneckStack != null) {
            SuperFactory.LOG.info(
                "[Super Integrated Factory/Runtime] 节点原料不足未达最大并行: node={}, runnable={}, limit={}, slot={}, needPerRun={}, available={}, input={}",
                describeNode(node),
                runnable,
                Math.max(1, parallelLimit),
                bottleneckSlot,
                bottleneckNeed,
                bottleneckAvailable,
                isFluidDisplay(bottleneckStack) ? describeFluid(GTUtility.getFluidFromDisplayStack(bottleneckStack))
                    : describeItem(bottleneckStack));
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

    private int getOverclocksToOneTick(int durationTicks) {
        int overclocks = 0;
        double duration = Math.max(1, durationTicks);
        while (duration >= 2.0D && overclocks < 64) {
            duration /= 4.0D;
            overclocks++;
        }
        return overclocks;
    }

    private int getEffectiveOverclockCount(ProcessNode node) {
        int requested = Math.max(0, node.overclockCount) + getGlobalExtraOverclocks();
        return Math.min(requested, getOverclocksToOneTick(getBaseDurationTicks(node)));
    }

    private int getBaseDurationTicks(ProcessNode node) {
        return Math.max(1, node.baseDurationTicks > 0 ? node.baseDurationTicks : node.durationTicks);
    }

    private long getBaseEuPerTick(ProcessNode node) {
        return Math.max(0L, node.baseEuPerTick > 0L ? node.baseEuPerTick : node.euPerTick);
    }

    private int getEffectiveParallelLimit(ProcessNode node) {
        if (node.isRecyclerNode()) {
            return Integer.MAX_VALUE;
        }
        return (int) Math
            .min(Integer.MAX_VALUE, safeMultiply(Math.max(1, node.parallelLimit), getGlobalParallelMultiplier()));
    }

    private int getEffectiveDurationTicks(ProcessNode node) {
        long duration = getBaseDurationTicks(node);
        for (int i = 0; i < getEffectiveOverclockCount(node); i++) {
            duration = Math.max(1L, (duration + 3L) / 4L);
        }
        return (int) Math.min(Integer.MAX_VALUE, duration);
    }

    private long getEffectiveEuPerTick(ProcessNode node) {
        long euPerTick = getBaseEuPerTick(node);
        for (int i = 0; i < getEffectiveOverclockCount(node); i++) {
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
        if (node.isRecyclerNode()) {
            return availableRecyclerInputAmount(node) >= safeMultiply(recyclerInputCost(node), Math.max(1, parallel));
        }
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack stack = node.inputHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            long need = safeMultiply(getStackAmount(stack), Math.max(1, parallel));
            if (isFluidDisplay(stack)) {
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
                long available = availableFluidAmount(node, fluid);
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
                long available = availableItemAmount(node, stack);
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
        if (node.isRecyclerNode()) {
            return consumeRecyclerInputs(node, job, safeMultiply(recyclerInputCost(node), Math.max(1, parallel)));
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
                long remaining = consumeFluidForNode(node, fluid, need);
                long consumed = Math.max(0L, need - remaining);
                if (consumed > 0L) {
                    addFluidToStackList(stagedFluids, copyFluidAmount(fluid, consumed));
                }
                if (remaining > 0L) {
                    rollbackStagedInputs(stagedItems, stagedFluids);
                    return false;
                }
            } else {
                long remaining = consumeItemForNode(node, stack, need, stagedItems);
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

    private long recyclerInputCost(ProcessNode node) {
        return node != null && node.recyclerOutputsScrapbox ? 9L : 1L;
    }

    private long availableRecyclerInputAmount(ProcessNode node) {
        long amount = 0L;
        for (BufferedItemStack entry : internalItems) {
            if (entry == null || entry.stack == null || entry.amount <= 0L || !recyclerAcceptsItem(node, entry.stack)) {
                continue;
            }
            long stored = countConsumableInternalItemAmount(node, entry.stack);
            amount = safeAddLong(amount, stored);
        }
        return amount;
    }

    private boolean recyclerAcceptsItem(ProcessNode node, ItemStack stack) {
        if (node == null || stack == null) {
            return false;
        }
        for (int slot = 0; slot < node.inputHandler.getSlots(); slot++) {
            ItemStack input = node.inputHandler.getStackInSlot(slot);
            if (input != null && !isFluidDisplay(input) && itemMatches(input, stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean consumeRecyclerInputs(ProcessNode node, RunningJob job, long amount) {
        long remaining = amount;
        ArrayList<ItemStack> stagedItems = new ArrayList<>();
        for (int slot = 0; slot < node.inputHandler.getSlots() && remaining > 0L; slot++) {
            ItemStack input = node.inputHandler.getStackInSlot(slot);
            if (input == null || isFluidDisplay(input)) {
                continue;
            }
            long available = countConsumableInternalItemAmount(node, input);
            long consumed = Math.min(remaining, available);
            if (consumed <= 0L) {
                continue;
            }
            long leftover = removeConsumableItemFromBuffer(node, input, consumed, stagedItems);
            remaining -= consumed - leftover;
        }
        if (remaining > 0L) {
            rollbackStagedInputs(stagedItems, java.util.Collections.emptyList());
            return false;
        }
        for (ItemStack stack : stagedItems) {
            addItemToStackList(job.consumedItems, stack);
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
        ArrayList<BufferedItemStack> jobItems = new ArrayList<>();
        ArrayList<BufferedFluidStack> jobFluids = new ArrayList<>();
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack stack = node.outputHandler.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            long amount = safeMultiply(getStackAmount(stack), Math.max(1, parallel));
            if (isFluidDisplay(stack)) {
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
                if (fluid != null) {
                    addFluidToBuffer(jobFluids, fluid, amount);
                }
            } else {
                long rolls = ParallelHelper
                    .calculateIntegralChancedOutputMultiplier(node.getOutputChance(slot), Math.max(1, parallel));
                if (rolls > 0) {
                    addItemToBuffer(jobItems, stack, safeMultiply(getStackAmount(stack), rolls));
                }
            }
        }
        for (BufferedItemStack entry : jobItems) {
            if (entry != null && entry.stack != null && entry.amount > 0L) {
                routeItemOutput(node, entry.stack, entry.amount);
            }
        }
        for (BufferedFluidStack entry : jobFluids) {
            if (entry != null && entry.fluidStack != null && entry.amount > 0L) {
                routeFluidOutput(node, entry.fluidStack, entry.amount);
            }
        }
    }

    private void routeItemOutput(ProcessNode node, ItemStack output, long amount) {
        if (output == null || amount <= 0L) {
            return;
        }
        OutputRouteType route = resolveOutputRoute(node, materialKeyOf(output));
        switch (route) {
            case CYCLE_INTERNAL:
                addItemToBuffer(internalItems, output, amount);
                spillCyclicItemOverflow(output);
                break;
            case INTERNAL:
                addItemToBuffer(internalItems, output, amount);
                break;
            case TARGET_OUTPUT:
            case BYPRODUCT_OUTPUT:
            default:
                addItemToBuffer(outputItems, output, amount);
                break;
        }
    }

    private void routeFluidOutput(ProcessNode node, FluidStack output, long amount) {
        if (output == null || amount <= 0L) {
            return;
        }
        OutputRouteType route = resolveOutputRoute(node, MaterialKey.ofFluid(output));
        switch (route) {
            case CYCLE_INTERNAL:
                addFluidToBuffer(internalFluids, output, amount);
                spillCyclicFluidOverflow(output);
                break;
            case INTERNAL:
                addFluidToBuffer(internalFluids, output, amount);
                break;
            case TARGET_OUTPUT:
            case BYPRODUCT_OUTPUT:
            default:
                addFluidToBuffer(outputFluids, output, amount);
                break;
        }
    }

    private OutputRouteType resolveOutputRoute(ProcessNode node, MaterialKey material) {
        if (node == null || material == null) {
            return OutputRouteType.BYPRODUCT_OUTPUT;
        }
        return runtimeRouteResolver.resolve(node.id, material);
    }

    private MaterialKey materialKeyOf(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        FluidStack fluid = GTUtility.getFluidFromDisplayStack(stack);
        return fluid == null ? MaterialKey.ofItem(stack) : MaterialKey.ofFluid(fluid);
    }

    private void spillCyclicItemOverflow(ItemStack template) {
        long reserveTarget = getCyclicItemHighWater(template);
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
        long reserveTarget = getCyclicFluidHighWater(template);
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

    private long getCyclicItemHighWater(ItemStack template) {
        CycleRuntimeState state = cycleRuntimeManager.get(materialKeyOf(template));
        return state == null ? Long.MAX_VALUE : state.highWater;
    }

    private long getCyclicItemReserveMin(ItemStack template) {
        CycleRuntimeState state = cycleRuntimeManager.get(materialKeyOf(template));
        return state == null ? 0L : state.reserve;
    }

    private boolean isCyclicItemTarget(ItemStack template) {
        return cycleRuntimeManager.isCycleMaterial(materialKeyOf(template));
    }

    private long getCyclicFluidHighWater(FluidStack template) {
        CycleRuntimeState state = cycleRuntimeManager.get(MaterialKey.ofFluid(template));
        return state == null ? Long.MAX_VALUE : state.highWater;
    }

    private long getCyclicFluidReserveMin(FluidStack template) {
        CycleRuntimeState state = cycleRuntimeManager.get(MaterialKey.ofFluid(template));
        return state == null ? 0L : state.reserve;
    }

    private boolean isCyclicFluidTarget(FluidStack template) {
        return cycleRuntimeManager.isCycleMaterial(MaterialKey.ofFluid(template));
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
                if (shouldThrottleInternalFluidOutput(
                    node,
                    fluid,
                    getExpectedOutputAmount(node, output, slot, getEffectiveParallelLimit(node)),
                    debugRuntime)) {
                    return true;
                }
            } else {
                if (shouldThrottleInternalItemOutput(
                    node,
                    output,
                    getExpectedOutputAmount(node, output, slot, getEffectiveParallelLimit(node)),
                    debugRuntime)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExternalOutputThrottled(ProcessNode node, int parallel, boolean debugRuntime) {
        for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
            ItemStack output = node.outputHandler.getStackInSlot(slot);
            if (output == null) {
                continue;
            }
            FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
            long perRun = getExpectedOutputAmount(node, output, slot, parallel);
            if (perRun <= 0L) {
                continue;
            }
            if (fluid != null) {
                if (shouldThrottleExternalFluidOutput(node, fluid, perRun, debugRuntime)) {
                    return true;
                }
            } else if (shouldThrottleExternalItemOutput(node, output, perRun, debugRuntime)) {
                return true;
            }
        }
        return false;
    }

    private long getExpectedItemOutputAmount(ProcessNode node, ItemStack output, int slot, int parallel) {
        if (node == null || output == null || parallel <= 0) {
            return 0L;
        }
        long rolls = ParallelHelper.calculateIntegralChancedOutputMultiplier(node.getOutputChance(slot), parallel);
        return safeMultiply(getStackAmount(output), rolls);
    }

    private long getExpectedOutputAmount(ProcessNode node, ItemStack output, int slot, int parallel) {
        if (node == null || output == null || parallel <= 0) {
            return 0L;
        }
        if (isFluidDisplay(output)) {
            return safeMultiply(Math.max(1L, getStackAmount(output)), Math.max(1L, parallel));
        }
        return getExpectedItemOutputAmount(node, output, slot, parallel);
    }

    private boolean shouldThrottleInternalItemOutput(ProcessNode node, ItemStack output, long perRun,
        boolean debugRuntime) {
        if (!isInternalRoute(node, output)) {
            logInternalRouteCheck(
                debugRuntime,
                node,
                describeItem(output),
                resolveOutputRoute(node, materialKeyOf(output)));
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
        if (!isInternalRoute(node, output)) {
            logInternalRouteCheck(
                debugRuntime,
                node,
                describeFluid(output),
                resolveOutputRoute(node, MaterialKey.ofFluid(output)));
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

    private boolean shouldThrottleExternalItemOutput(ProcessNode node, ItemStack output, long perRun,
        boolean debugRuntime) {
        if (isInternalRoute(node, output)) {
            return false;
        }
        String key = itemBufferKey(output);
        long stored = countItemInBuffer(outputItems, output);
        long lowWater = getExternalItemLowWater(node, perRun);
        long highWater = getExternalHighWater(lowWater);
        if (throttledExternalItemOutputs.contains(key)) {
            if (stored <= lowWater) {
                throttledExternalItemOutputs.remove(key);
                return false;
            }
            logExternalThrottle(debugRuntime, node, describeItem(output), stored, lowWater, highWater);
            return true;
        }
        if (stored >= highWater) {
            throttledExternalItemOutputs.add(key);
            logExternalThrottle(debugRuntime, node, describeItem(output), stored, lowWater, highWater);
            return true;
        }
        return false;
    }

    private boolean shouldThrottleExternalFluidOutput(ProcessNode node, FluidStack output, long perRun,
        boolean debugRuntime) {
        if (isInternalRoute(node, output)) {
            return false;
        }
        String key = fluidBufferKey(output);
        long stored = countFluidInBuffer(outputFluids, output);
        long lowWater = getExternalFluidLowWater(node, perRun);
        long highWater = getExternalHighWater(lowWater);
        if (throttledExternalFluidOutputs.contains(key)) {
            if (stored <= lowWater) {
                throttledExternalFluidOutputs.remove(key);
                return false;
            }
            logExternalThrottle(debugRuntime, node, describeFluid(output), stored, lowWater, highWater);
            return true;
        }
        if (stored >= highWater) {
            throttledExternalFluidOutputs.add(key);
            logExternalThrottle(debugRuntime, node, describeFluid(output), stored, lowWater, highWater);
            return true;
        }
        return false;
    }

    private boolean isInternalRoute(ProcessNode node, ItemStack output) {
        OutputRouteType route = resolveOutputRoute(node, materialKeyOf(output));
        return route == OutputRouteType.INTERNAL || route == OutputRouteType.CYCLE_INTERNAL;
    }

    private boolean isInternalRoute(ProcessNode node, FluidStack output) {
        OutputRouteType route = resolveOutputRoute(node, MaterialKey.ofFluid(output));
        return route == OutputRouteType.INTERNAL || route == OutputRouteType.CYCLE_INTERNAL;
    }

    private long getInternalItemLowWater(ProcessNode producer, ItemStack output, long perRun) {
        return IntegratedFactoryWatermarks.internalItemLowWater(watermarkContext, producer, output, perRun);
    }

    private long getInternalFluidLowWater(ProcessNode producer, FluidStack output, long perRun) {
        return IntegratedFactoryWatermarks.internalFluidLowWater(watermarkContext, producer, output, perRun);
    }

    private long getExternalItemLowWater(ProcessNode producer, long perRun) {
        return IntegratedFactoryWatermarks.externalLowWater(watermarkContext, producer, perRun);
    }

    private long getExternalFluidLowWater(ProcessNode producer, long perRun) {
        return IntegratedFactoryWatermarks.externalLowWater(watermarkContext, producer, perRun);
    }

    private long getInternalHighWater(long lowWater) {
        return IntegratedFactoryWatermarks.highWater(lowWater);
    }

    private long getExternalHighWater(long lowWater) {
        return getInternalHighWater(lowWater);
    }

    private long getOutputThroughputPerSecond(ProcessNode producer, long perRun) {
        return IntegratedFactoryWatermarks.outputThroughputPerSecond(watermarkContext, producer, perRun);
    }

    private long getOutputBatchAmount(ProcessNode producer, long perRun) {
        return IntegratedFactoryWatermarks.outputBatchAmount(perRun);
    }

    private long getWaterlineDuration(ProcessNode producer) {
        return IntegratedFactoryWatermarks.waterlineDuration(watermarkContext, producer);
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

    private void logInternalRouteCheck(boolean debugRuntime, ProcessNode node, String output, OutputRouteType route) {
        if (debugRuntime && hasOutgoingEdge(node.id)) {
            SuperFactory.LOG.info(
                "[Super Integrated Factory/Runtime] 节点输出不走内部水位: node={}, output={}, route={}",
                describeNode(node),
                output,
                route);
        }
    }

    private void logExternalThrottle(boolean debugRuntime, ProcessNode node, String output, long stored, long lowWater,
        long highWater) {
        if (debugRuntime) {
            SuperFactory.LOG.info(
                "[Super Integrated Factory/Runtime] 外部输出水位暂停: node={}, output={}, stored={}, low={}, high={}",
                describeNode(node),
                output,
                stored,
                lowWater,
                highWater);
        }
    }

    private void logRuntimeWaterlineState() {
        if (runtimeGraphAnalysis == null) {
            return;
        }
        for (ProcessNode node : runtimeGraph.nodes) {
            if (node == null) {
                continue;
            }
            for (int slot = 0; slot < node.outputHandler.getSlots(); slot++) {
                ItemStack output = node.outputHandler.getStackInSlot(slot);
                if (output == null) {
                    continue;
                }
                FluidStack fluid = GTUtility.getFluidFromDisplayStack(output);
                MaterialKey key = fluid == null ? materialKeyOf(output) : MaterialKey.ofFluid(fluid);
                OutputRouteType route = resolveOutputRoute(node, key);
                long perRun = getExpectedOutputAmount(node, output, slot, getEffectiveParallelLimit(node));
                long stored = switch (route) {
                    case INTERNAL, CYCLE_INTERNAL -> fluid == null ? countItemInBuffer(internalItems, output)
                        : countFluidInBuffer(internalFluids, fluid);
                    case TARGET_OUTPUT, BYPRODUCT_OUTPUT -> fluid == null ? countItemInBuffer(outputItems, output)
                        : countFluidInBuffer(outputFluids, fluid);
                };
                long low = route == OutputRouteType.INTERNAL || route == OutputRouteType.CYCLE_INTERNAL
                    ? fluid == null ? getInternalItemLowWater(node, output, perRun)
                        : getInternalFluidLowWater(node, fluid, perRun)
                    : fluid == null ? getExternalItemLowWater(node, perRun) : getExternalFluidLowWater(node, perRun);
                long high = route == OutputRouteType.INTERNAL || route == OutputRouteType.CYCLE_INTERNAL
                    ? getInternalHighWater(low)
                    : getExternalHighWater(low);
                SuperFactory.LOG.info(
                    "[Super Integrated Factory/Waterline] phase=runtime, node={}, output={}, route={}, stored={}, low={}, high={}",
                    describeNode(node),
                    fluid == null ? describeItem(output) : describeFluid(fluid),
                    route,
                    stored,
                    low,
                    high);
            }
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
            ProcessNode consumer = findRuntimeNode(edge.toNodeId);
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
            ProcessNode consumer = findRuntimeNode(edge.toNodeId);
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
        return new RuntimeOutputFormatter(new RuntimeOutputFormatter.Context() {

            @Override
            public ProcessGraph runtimeGraph() {
                return runtimeGraph;
            }

            @Override
            public Iterable<RunningJob> runningJobs() {
                return runningJobs;
            }

            @Override
            public ProcessNode findRuntimeNode(int nodeId) {
                return MTESuperIntegratedFactory.this.findRuntimeNode(nodeId);
            }

            @Override
            public String safeNodeName(ProcessNode node) {
                return MTESuperIntegratedFactory.this.safeNodeName(node);
            }

            @Override
            public int getEffectiveDurationTicks(ProcessNode node) {
                return MTESuperIntegratedFactory.this.getEffectiveDurationTicks(node);
            }

            @Override
            public long getStackAmount(ItemStack stack) {
                return MTESuperIntegratedFactory.this.getStackAmount(stack);
            }

            @Override
            public int staticNodeLineThreshold() {
                return STATIC_RUNTIME_NODE_LINE_THRESHOLD;
            }

            @Override
            public int visibleLineLimit() {
                return RUNTIME_OUTPUT_ESTIMATE_LINE_LIMIT;
            }

            @Override
            public String translate(String key) {
                return tr(key);
            }
        }).buildActiveRuntimeOutputLines();
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
        return RuntimeOutputFormatter.formatRate(rate);
    }

    private String formatCompactAmount(double value) {
        return RuntimeOutputFormatter.formatCompactAmount(value);
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
        return RuntimeOutputFormatter.trimToDisplayWidth(text, maxChars);
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

    private long availableItemAmount(ProcessNode consumer, ItemStack template) {
        if (runtimeResourceSnapshot != null) {
            return runtimeResourceSnapshot.itemAmount(consumer, template);
        }
        long amount = countConsumableInternalItemAmount(consumer, template);
        for (ItemStack stack : getStoredInputs()) {
            if (stack != null && stack.stackSize > 0 && itemMatches(template, stack)) {
                amount += stack.stackSize;
            }
        }
        return amount + countItemInDualInputHatches(template);
    }

    private long availableFluidAmount(ProcessNode consumer, FluidStack template) {
        if (template == null) {
            return 0L;
        }
        if (runtimeResourceSnapshot != null) {
            return runtimeResourceSnapshot.fluidAmount(consumer, template);
        }
        long amount = countConsumableInternalFluidAmount(consumer, template);
        for (FluidStack available : getStoredFluids()) {
            if (available != null && available.isFluidEqual(template)) {
                amount += available.amount;
            }
        }
        return amount + countFluidInDualInputHatches(template);
    }

    private long consumeItemForNode(ProcessNode consumer, ItemStack template, long amount,
        List<ItemStack> consumedItems) {
        long remaining = removeConsumableItemFromBuffer(consumer, template, amount, consumedItems);
        remaining = depleteItemFromLiveInputs(template, remaining, consumedItems);
        return removeItemFromDualInputHatches(template, remaining, consumedItems);
    }

    private long consumeFluidForNode(ProcessNode consumer, FluidStack template, long amount) {
        long remaining = removeConsumableFluidFromBuffer(consumer, template, amount);
        remaining = drainFluidFromInputHatches(template, remaining);
        return removeFluidFromDualInputHatches(template, remaining);
    }

    private long countConsumableInternalItemAmount(ProcessNode consumer, ItemStack template) {
        long stored = countItemInBuffer(internalItems, template);
        CycleRuntimeState state = cycleRuntimeManager.get(materialKeyOf(template));
        return state == null || consumer != null && state.containsNode(consumer.id) ? stored
            : Math.max(0L, stored - state.reserve);
    }

    private long countConsumableInternalFluidAmount(ProcessNode consumer, FluidStack template) {
        long stored = countFluidInBuffer(internalFluids, template);
        CycleRuntimeState state = cycleRuntimeManager.get(MaterialKey.ofFluid(template));
        return state == null || consumer != null && state.containsNode(consumer.id) ? stored
            : Math.max(0L, stored - state.reserve);
    }

    private long removeConsumableItemFromBuffer(ProcessNode consumer, ItemStack template, long amount,
        List<ItemStack> consumedItems) {
        long consumable = countConsumableInternalItemAmount(consumer, template);
        long fromInternal = Math.min(amount, consumable);
        return removeItemFromBuffer(internalItems, template, fromInternal, consumedItems)
            + Math.max(0L, amount - fromInternal);
    }

    private long removeConsumableFluidFromBuffer(ProcessNode consumer, FluidStack template, long amount) {
        long consumable = countConsumableInternalFluidAmount(consumer, template);
        long fromInternal = Math.min(amount, consumable);
        return removeFluidFromBuffer(internalFluids, template, fromInternal) + Math.max(0L, amount - fromInternal);
    }

    private long depleteItemFromLiveInputs(ItemStack template, long amount, List<ItemStack> consumedItems) {
        if (amount <= 0L) {
            return 0L;
        }
        if (runtimeResourceSnapshot != null) {
            return depleteItemFromLiveSnapshot(template, amount, consumedItems);
        }
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

    /*
     * Runtime scheduling works from one live-input snapshot per tick. ME input busses can satisfy int-sized virtual
     * stacks, so consume those in large chunks and only fall back to normal stack-sized chunks for plain inventories.
     */
    private long depleteItemFromLiveSnapshot(ItemStack template, long amount, List<ItemStack> consumedItems) {
        long remaining = amount;
        Iterator<BufferedItemStack> iterator = runtimeResourceSnapshot.liveItemView()
            .iterator();
        while (iterator.hasNext() && remaining > 0L) {
            BufferedItemStack entry = iterator.next();
            if (entry == null || entry.stack == null || entry.amount <= 0L || !itemMatches(template, entry.stack)) {
                continue;
            }
            while (remaining > 0L && entry.amount > 0L) {
                long requested = Math.min(remaining, Math.min(entry.amount, Integer.MAX_VALUE));
                ItemStack request = copyItemAmount(entry.stack, requested);
                if (request == null || request.stackSize <= 0) {
                    return remaining;
                }
                if (!depleteInput(request)) {
                    long consumed = depleteItemInSmallLiveChunks(entry.stack, requested, consumedItems);
                    if (consumed > 0L) {
                        entry.amount -= consumed;
                        remaining -= consumed;
                    }
                    if (entry.amount <= 0L) {
                        iterator.remove();
                    }
                    if (consumed >= requested) {
                        continue;
                    }
                    return remaining;
                }
                long consumed = Math.min(requested, getStackAmount(request));
                if (consumed <= 0L) {
                    return remaining;
                }
                addItemToStackList(consumedItems, copyItemAmount(entry.stack, consumed));
                entry.amount -= consumed;
                remaining -= consumed;
            }
            if (entry.amount <= 0L) {
                iterator.remove();
            }
        }
        return remaining;
    }

    private long depleteItemInSmallLiveChunks(ItemStack stack, long amount, List<ItemStack> consumedItems) {
        long remaining = amount;
        long consumed = 0L;
        int chunkSize = Math.max(1, Math.min(Integer.MAX_VALUE, stack.getMaxStackSize()));
        while (remaining > 0L) {
            ItemStack request = copyItemAmount(stack, Math.min(remaining, chunkSize));
            if (request == null || request.stackSize <= 0 || !depleteInput(request)) {
                break;
            }
            long moved = getStackAmount(request);
            addItemToStackList(consumedItems, request);
            consumed += moved;
            remaining -= moved;
        }
        return consumed;
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
        if (runtimeResourceSnapshot != null) {
            long remaining = amount;
            for (BufferedFluidStack entry : runtimeResourceSnapshot.liveFluidView()) {
                if (remaining <= 0L || entry == null
                    || entry.fluidStack == null
                    || entry.amount <= 0L
                    || !entry.fluidStack.isFluidEqual(template)) {
                    continue;
                }
                long requested = Math.min(remaining, entry.amount);
                long leftover = drainFluidFromInputHatchesDirect(template, requested);
                long drained = requested - leftover;
                if (drained <= 0L) {
                    break;
                }
                entry.amount -= drained;
                remaining -= drained;
            }
            return remaining;
        }
        return drainFluidFromInputHatchesDirect(template, amount);
    }

    private long drainFluidFromInputHatchesDirect(FluidStack template, long amount) {
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
        int flushEntriesLeft = getIntegratedOutputFlushEntryBudget();
        Iterator<BufferedItemStack> itemIterator = outputItems.iterator();
        while (itemIterator.hasNext() && flushEntriesLeft != 0) {
            BufferedItemStack entry = itemIterator.next();
            if (entry == null || entry.amount <= 0L || entry.stack == null) {
                itemIterator.remove();
                continue;
            }
            if (flushEntriesLeft > 0) {
                flushEntriesLeft--;
            }
            long moved = flushBufferedItemOutput(entry);
            if (moved <= 0L) {
                continue;
            }
            entry.amount = Math.max(0L, entry.amount - moved);
            if (entry.amount <= 0L) {
                itemIterator.remove();
            }
            if (moved < MAX_OUTPUT_ITEM_FLUSH_PER_TICK && !hasFastItemOutputBus()) {
                break;
            }
        }
        Iterator<BufferedFluidStack> fluidIterator = outputFluids.iterator();
        while (fluidIterator.hasNext() && flushEntriesLeft != 0) {
            BufferedFluidStack entry = fluidIterator.next();
            if (entry == null || entry.fluidStack == null || entry.amount <= 0L) {
                fluidIterator.remove();
                continue;
            }
            if (flushEntriesLeft > 0) {
                flushEntriesLeft--;
            }
            long movedToMe = flushBufferedFluidToMeHatches(entry);
            if (movedToMe > 0L) {
                entry.amount = Math.max(0L, entry.amount - movedToMe);
                if (entry.amount <= 0L) {
                    fluidIterator.remove();
                }
                continue;
            }
            FluidStack stack = copyFluidAmount(
                entry.fluidStack,
                Math.min(entry.amount, MAX_OUTPUT_FLUID_FLUSH_PER_TICK));
            int offered = stack.amount;
            if (tryFlushFluidOutput(stack) <= 0) {
                continue;
            }
            entry.amount = Math.max(0L, entry.amount - Math.max(0, offered - stack.amount));
            if (entry.amount <= 0L) {
                fluidIterator.remove();
            }
        }
    }

    private boolean hasExternalOutputs() {
        return !outputItems.isEmpty() || !outputFluids.isEmpty();
    }

    private int getIntegratedOutputFlushEntryBudget() {
        int configured = Config.superIntegratedFactoryMaxOutputFlushEntriesPerTick;
        return configured <= 0 ? -1 : configured;
    }

    private long flushBufferedItemOutput(BufferedItemStack entry) {
        long moved = flushBufferedItemToMeBusses(entry);
        if (moved > 0L) {
            return moved;
        }
        moved = flushBufferedItemToOutputBusses(entry, MAX_OUTPUT_ITEM_FLUSH_PER_TICK);
        if (moved > 0L) {
            return moved;
        }
        return flushBufferedItemToOutputHatches(entry, Math.min(entry.amount, MAX_OUTPUT_ITEM_FLUSH_PER_TICK));
    }

    private long flushBufferedItemToMeBusses(BufferedItemStack entry) {
        long remaining = entry.amount;
        for (MTEHatchOutputBus bus : mOutputBusses) {
            if (remaining <= 0L) {
                break;
            }
            if (!(bus instanceof MTEHatchOutputBusME) || bus == null || !bus.isValid()) {
                continue;
            }
            long offered = Math.min(remaining, Integer.MAX_VALUE);
            ItemStack request = copyItemAmount(entry.stack, offered);
            if (request == null || request.stackSize <= 0 || !bus.storePartial(request, false)) {
                continue;
            }
            remaining -= offered;
        }
        return entry.amount - remaining;
    }

    private long flushBufferedItemToOutputBusses(BufferedItemStack entry, long limit) {
        long remaining = Math.min(entry.amount, Math.max(0L, limit));
        for (boolean restrictiveOnly : new boolean[] { true, false }) {
            for (MTEHatchOutputBus bus : mOutputBusses) {
                if (remaining <= 0L) {
                    break;
                }
                if (bus == null || !bus.isValid()
                    || bus instanceof MTEHatchOutputBusME
                    || restrictiveOnly && !bus.isLocked()) {
                    continue;
                }
                long offered = Math.min(remaining, Math.max(1, entry.stack.getMaxStackSize()));
                ItemStack request = copyItemAmount(entry.stack, offered);
                if (request == null || request.stackSize <= 0) {
                    continue;
                }
                long before = request.stackSize;
                bus.storePartial(request, false);
                remaining -= Math.max(0L, before - request.stackSize);
            }
        }
        return Math.min(entry.amount, Math.max(0L, limit)) - remaining;
    }

    private long flushBufferedItemToOutputHatches(BufferedItemStack entry, long limit) {
        long remaining = Math.min(entry.amount, Math.max(0L, limit));
        for (MTEHatchOutput hatch : mOutputHatches) {
            if (remaining <= 0L) {
                break;
            }
            if (hatch == null || !hatch.isValid() || !hatch.outputsItems()) {
                continue;
            }
            ItemStack request = copyItemAmount(
                entry.stack,
                Math.min(remaining, Math.max(1, entry.stack.getMaxStackSize())));
            if (request == null || request.stackSize <= 0) {
                continue;
            }
            long offered = request.stackSize;
            if (hatch.getBaseMetaTileEntity()
                .addStackToSlot(1, request)) {
                remaining -= offered;
            }
        }
        return Math.min(entry.amount, Math.max(0L, limit)) - remaining;
    }

    private boolean hasFastItemOutputBus() {
        for (MTEHatchOutputBus bus : mOutputBusses) {
            if (bus instanceof MTEHatchOutputBusME && bus.isValid()) {
                return true;
            }
        }
        return false;
    }

    private long flushBufferedFluidToMeHatches(BufferedFluidStack entry) {
        long remaining = entry.amount;
        for (MTEHatchOutput hatch : mOutputHatches) {
            if (remaining <= 0L) {
                break;
            }
            if (!(hatch instanceof MTEHatchOutputME meHatch) || !hatch.isValid()
                || !hatch.canStoreFluid(entry.fluidStack)) {
                continue;
            }
            while (remaining > 0L) {
                FluidStack request = copyFluidAmount(entry.fluidStack, Math.min(remaining, Integer.MAX_VALUE));
                if (request == null || request.amount <= 0) {
                    break;
                }
                int filled = meHatch.tryFillAE(request);
                if (filled <= 0) {
                    break;
                }
                remaining -= filled;
                if (filled < request.amount) {
                    break;
                }
            }
        }
        return entry.amount - remaining;
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
            ProcessNode node = findRuntimeNode(job.nodeId);
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
        if (runtimeResourceSnapshot != null) {
            return runtimeResourceSnapshot.itemMatchesCached(recipeInput, provided);
        }
        return itemMatchesUncached(recipeInput, provided);
    }

    private boolean itemMatchesUncached(ItemStack recipeInput, ItemStack provided) {
        if (recipeInput == null || provided == null) {
            return false;
        }
        if (GTUtility.areStacksEqual(recipeInput, provided, true)) {
            return true;
        }
        int[] recipeOreIds = runtimeResourceSnapshot == null ? OreDictionary.getOreIDs(recipeInput)
            : runtimeResourceSnapshot.oreIds(recipeInput);
        int[] providedOreIds = runtimeResourceSnapshot == null ? OreDictionary.getOreIDs(provided)
            : runtimeResourceSnapshot.oreIds(provided);
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
        return Math.max(0L, ProcessNode.getDisplayAmount(stack));
    }

    private long safeMultiply(long a, long b) {
        return ProcessRuntimeMath.safeMultiply(a, b);
    }

    private long safeAddLong(long a, long b) {
        return ProcessRuntimeMath.safeAdd(a, b);
    }

    private long safeCeilMultiply(long value, long numerator, long denominator) {
        return ProcessRuntimeMath.safeCeilMultiply(value, numerator, denominator);
    }

    private ItemStack copyItemAmount(ItemStack stack, long amount) {
        return ProcessNode.withDisplayAmount(stack, amount);
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
        ProcessBufferUtil.addItem(buffer, stack, amount, (left, right) -> GTUtility.areStacksEqual(left, right, true));
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
        ProcessBufferUtil.addFluid(buffer, stack, amount);
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
        return ProcessBufferUtil.countItem(buffer, template, this::itemMatches);
    }

    private long countFluidInBuffer(List<BufferedFluidStack> buffer, FluidStack template) {
        return ProcessBufferUtil.countFluid(buffer, template);
    }

    private long removeItemFromBuffer(List<BufferedItemStack> buffer, ItemStack template, long amount) {
        return removeItemFromBuffer(buffer, template, amount, null);
    }

    private long removeItemFromBuffer(List<BufferedItemStack> buffer, ItemStack template, long amount,
        List<ItemStack> consumedItems) {
        return ProcessBufferUtil.removeItem(
            buffer,
            template,
            amount,
            this::itemMatches,
            consumedItems == null ? null
                : (stack, removed) -> addItemToStackList(consumedItems, copyItemAmount(stack, removed)));
    }

    private long removeFluidFromBuffer(List<BufferedFluidStack> buffer, FluidStack template, long amount) {
        return ProcessBufferUtil.removeFluid(buffer, template, amount);
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
                amount = safeAddLong(amount, countItemInStacks(template, inventory.getItemInputs()));
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
                amount = safeAddLong(amount, countFluidInStacks(template, inventory.getFluidInputs()));
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
                amount = safeAddLong(amount, stack.stackSize);
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
                amount = safeAddLong(amount, stack.amount);
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
        return hasStoredProcessRequirements() || pendingProcessRequirements.hasSubmittedDemands()
            || hasDeferredRuntimeGraph;
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
        ItemStack proxyController = null;
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
                if (stack == null || stack.stackSize <= 0) {
                    continue;
                }
                if (isSuperProxyFactoryController(stack)) {
                    if (Config.allowProxyFactoryAsIntegratedRecipeHost && proxyController == null) {
                        proxyController = consumeInputBusItem(bus, slot, stack);
                    }
                    continue;
                }
                if (!machineSupportsRecipeMap(stack, recipeMapName)) {
                    continue;
                }
                return consumeInputBusItem(bus, slot, stack);
            }
        }
        return proxyController;
    }

    private ItemStack consumeInputBusItem(MTEHatchInputBus bus, int slot, ItemStack stack) {
        if (bus == null || stack == null || stack.stackSize <= 0) {
            return null;
        }
        ItemStack consumed = stack.copy();
        consumed.stackSize = 1;
        bus.getBaseMetaTileEntity()
            .decrStackSize(slot, 1);
        return consumed;
    }

    private boolean machineSupportsRecipeMap(ItemStack stack, String recipeMapName) {
        if (stack == null || !(stack.getItem() instanceof ItemMachines)) {
            return false;
        }
        if (ProcessNode.FAKE_RECIPE_PROXY_HOST.equals(recipeMapName)) {
            ItemStack proxyFactory = MachineLoader.getSuperProxyFactoryController();
            return proxyFactory != null && GTUtility.areStacksEqual(stack, proxyFactory, true);
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

    private boolean isSuperProxyFactoryController(ItemStack stack) {
        ItemStack proxyFactory = MachineLoader.getSuperProxyFactoryController();
        return stack != null && proxyFactory != null && GTUtility.areStacksEqual(stack, proxyFactory, true);
    }

    private void decrementStoredRecipeMapFor(ItemStack machine) {
        for (ProcessRequirements.RecipeMapDemand demand : processRequirements.recipeMaps) {
            if (demand.stored > 0 && machineSupportsRecipeMap(machine, demand.recipeMapName)) {
                demand.stored--;
                return;
            }
        }
    }

    private void decrementStoredMachineDemandFor(ItemStack machine) {
        if (isSuperProxyFactoryController(machine)) {
            decrementStoredProxyRecipeMap();
        } else {
            decrementStoredRecipeMapFor(machine);
        }
    }

    private void decrementStoredProxyRecipeMap() {
        for (ProcessRequirements.RecipeMapDemand demand : processRequirements.recipeMaps) {
            if (demand.proxyStored > 0) {
                demand.proxyStored--;
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
