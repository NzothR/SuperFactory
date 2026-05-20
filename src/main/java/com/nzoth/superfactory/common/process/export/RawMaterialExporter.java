package com.nzoth.superfactory.common.process.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fluids.FluidStack;

import com.nzoth.superfactory.common.process.ProcessEdge;
import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.util.GTUtility;
import gregtech.common.tileentities.machines.IDualInputHatch;

public final class RawMaterialExporter {

    private final Context context;

    public RawMaterialExporter(Context context) {
        this.context = context;
    }

    public void export(EntityPlayer player) {
        RawMaterialExportPlan plan = buildRawMaterialExportPlan();
        for (String nodeName : plan.oreDictionaryNodes) {
            GTUtility.sendChatToPlayer(
                player,
                EnumChatFormatting.YELLOW + nodeName
                    + ":"
                    + context.translate("superfactory.machine.super_integrated_factory.chat.raw_material_ore_manual"));
        }
        if (plan.items.isEmpty() && plan.fluids.isEmpty()) {
            if (!plan.oreDictionaryNodes.isEmpty()) {
                context.sendStatus(
                    player,
                    context.translate("superfactory.machine.super_integrated_factory.chat.raw_material_ore_notice"),
                    0xFFFFFF77);
                return;
            }
            context.sendStatus(
                player,
                context.translate("superfactory.machine.super_integrated_factory.chat.raw_material_none"),
                0xFFFF7777);
            return;
        }
        List<RawMaterialMarkerTarget> targets = collectRawMaterialMarkerTargets();
        if (targets.isEmpty()) {
            context.sendStatus(
                player,
                context.translate("superfactory.machine.super_integrated_factory.chat.raw_material_no_me_hatch"),
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
            context.sendStatus(
                player,
                context.translate(
                    "superfactory.machine.super_integrated_factory.chat.raw_material_capacity_insufficient") + " "
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
        String successMessage = context
            .translate("superfactory.machine.super_integrated_factory.chat.raw_material_exported") + ": "
            + plan.items.size()
            + " "
            + context.translate("superfactory.machine.super_integrated_factory.chat.raw_material_items")
            + ", "
            + plan.fluids.size()
            + " "
            + context.translate("superfactory.machine.super_integrated_factory.chat.raw_material_fluids");
        if (!plan.oreDictionaryNodes.isEmpty()) {
            successMessage += ", "
                + context.translate("superfactory.machine.super_integrated_factory.chat.raw_material_ore_notice");
        }
        context.sendStatus(player, successMessage, 0xFF75D17C);
        context.markDirty();
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
                    addRawMaterialOreWarning(plan, context.safeNodeName(node));
                } else {
                    addRawMaterialItem(plan, input);
                }
            }
        }
        return plan;
    }

    private List<ProcessNode> findRawMaterialRelevantNodes() {
        List<ProcessNode> relevant = new ArrayList<>();
        for (ProcessNode node : context.processGraph().nodes) {
            if (node.endNode) {
                collectRawMaterialConnectedNodes(node.id, relevant);
            }
        }
        if (!relevant.isEmpty()) {
            return relevant;
        }
        for (ProcessNode node : context.processGraph().nodes) {
            if (node.locked && node.lastRecipeCheckPassed) {
                relevant.add(node);
            }
        }
        return relevant;
    }

    private void collectRawMaterialConnectedNodes(int nodeId, List<ProcessNode> relevant) {
        ProcessNode node = context.processGraph()
            .findNode(nodeId);
        if (node == null || relevant.contains(node)) {
            return;
        }
        relevant.add(node);
        for (ProcessEdge edge : context.processGraph().edges) {
            if (edge.fromNodeId == nodeId) {
                collectRawMaterialConnectedNodes(edge.toNodeId, relevant);
            }
            if (edge.toNodeId == nodeId) {
                collectRawMaterialConnectedNodes(edge.fromNodeId, relevant);
            }
        }
    }

    private boolean hasDirectItemProducer(int consumerNodeId, ItemStack input, Set<Integer> relevantIds) {
        for (ProcessEdge edge : context.processGraph().edges) {
            if (edge.toNodeId != consumerNodeId || !relevantIds.contains(edge.fromNodeId)) {
                continue;
            }
            ProcessNode producer = context.processGraph()
                .findNode(edge.fromNodeId);
            if (producer == null) {
                continue;
            }
            for (int outputSlot = 0; outputSlot < producer.outputHandler.getSlots(); outputSlot++) {
                ItemStack output = producer.outputHandler.getStackInSlot(outputSlot);
                if (output != null && !context.isFluidDisplay(output) && context.itemMatches(input, output)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasDirectFluidProducer(int consumerNodeId, FluidStack input, Set<Integer> relevantIds) {
        for (ProcessEdge edge : context.processGraph().edges) {
            if (edge.toNodeId != consumerNodeId || !relevantIds.contains(edge.fromNodeId)) {
                continue;
            }
            ProcessNode producer = context.processGraph()
                .findNode(edge.fromNodeId);
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

    private static void addRawMaterialItem(RawMaterialExportPlan plan, ItemStack stack) {
        for (ItemStack existing : plan.items) {
            if (GTUtility.areStacksEqual(existing, stack, true)) {
                return;
            }
        }
        plan.items.add(GTUtility.copyAmount(1, stack));
    }

    private static void addRawMaterialFluid(RawMaterialExportPlan plan, FluidStack stack) {
        for (FluidStack existing : plan.fluids) {
            if (existing != null && existing.isFluidEqual(stack)) {
                return;
            }
        }
        plan.fluids.add(GTUtility.copyAmount(1, stack));
    }

    private static void addRawMaterialOreWarning(RawMaterialExportPlan plan, String nodeName) {
        if (!plan.oreDictionaryNodes.contains(nodeName)) {
            plan.oreDictionaryNodes.add(nodeName);
        }
    }

    private List<RawMaterialMarkerTarget> collectRawMaterialMarkerTargets() {
        List<RawMaterialMarkerTarget> combinedTargets = new ArrayList<>();
        List<RawMaterialMarkerTarget> separateTargets = new ArrayList<>();
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (IDualInputHatch hatch : context.dualInputHatches()) {
            if (hatch != null && seen.add(hatch)) {
                RawMaterialMarkerTarget target = RawMaterialMarkerTarget.tryCreate(hatch);
                if (target != null) {
                    (target.isCombined() ? combinedTargets : separateTargets).add(target);
                }
            }
        }
        for (MTEHatchInputBus bus : context.inputBusses()) {
            if (bus != null && seen.add(bus)) {
                RawMaterialMarkerTarget target = RawMaterialMarkerTarget.tryCreate(bus);
                if (target != null) {
                    (target.isCombined() ? combinedTargets : separateTargets).add(target);
                }
            }
        }
        for (MTEHatchInput hatch : context.inputHatches()) {
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

    public interface Context {

        ProcessGraph processGraph();

        Iterable<IDualInputHatch> dualInputHatches();

        Iterable<MTEHatchInputBus> inputBusses();

        Iterable<MTEHatchInput> inputHatches();

        boolean isFluidDisplay(ItemStack stack);

        boolean itemMatches(ItemStack input, ItemStack output);

        String safeNodeName(ProcessNode node);

        String translate(String key);

        void sendStatus(EntityPlayer player, String message, int color);

        void markDirty();
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
}
