package com.nzoth.superfactory.common.process.submit;

import java.util.Iterator;

import net.minecraft.item.ItemStack;

import com.nzoth.superfactory.common.process.ProcessRequirements;

public final class IntegratedFactoryUnloadHandler {

    private IntegratedFactoryUnloadHandler() {}

    public static void processOutputMode(Context context) {
        context.discardRunningJobsWithLoss();
        context.flushOutputBuffers();
        if (context.shouldDebugExportInternalBuffer()) {
            context.moveAllInternalToOutput();
            context.flushOutputBuffers();
        } else {
            context.clearInternalRuntimeBuffersForUnload();
        }
        context.clearStartupMaterialsForUnload();
        ProcessRequirements requirements = context.processRequirements();
        for (ProcessRequirements.ItemDemand demand : requirements.nonConsumables) {
            while (demand.stored > 0 && demand.stack != null) {
                ItemStack output = demand.stack.copy();
                output.stackSize = 1;
                if (!context.addOutput(output)) {
                    break;
                }
                demand.stored--;
            }
        }
        Iterator<ItemStack> machineIterator = requirements.storedMachines.iterator();
        while (machineIterator.hasNext()) {
            ItemStack machine = machineIterator.next();
            ItemStack output = machine.copy();
            output.stackSize = 1;
            if (!context.addOutput(output)) {
                break;
            }
            machineIterator.remove();
            context.decrementStoredMachineDemandFor(machine);
        }
        if (context.hasStoredProcessRequirements()) {
            context.markDirty();
            return;
        }
        context.unloadCurrentProcessState();
        if (context.hasDeferredRuntimeGraph()) {
            context.installDeferredProcessSubmission();
        } else if (context.pendingProcessRequirements()
            .hasSubmittedDemands()) {
                context.installPendingProcessRequirements();
            } else {
                context.enterStandby();
            }
        context.markDirty();
    }

    public interface Context {

        void discardRunningJobsWithLoss();

        void flushOutputBuffers();

        boolean shouldDebugExportInternalBuffer();

        void moveAllInternalToOutput();

        void clearInternalRuntimeBuffersForUnload();

        void clearStartupMaterialsForUnload();

        ProcessRequirements processRequirements();

        boolean addOutput(ItemStack stack);

        void decrementStoredMachineDemandFor(ItemStack machine);

        boolean hasStoredProcessRequirements();

        void unloadCurrentProcessState();

        boolean hasDeferredRuntimeGraph();

        void installDeferredProcessSubmission();

        ProcessRequirements pendingProcessRequirements();

        void installPendingProcessRequirements();

        void enterStandby();

        void markDirty();
    }
}
