package com.nzoth.superfactory.common.process.runtime;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fluids.FluidStack;

import com.nzoth.superfactory.common.process.ProcessGraph;
import com.nzoth.superfactory.common.process.ProcessNode;

import gregtech.api.util.GTUtility;

public final class RuntimeOutputFormatter {

    private final Context context;

    public RuntimeOutputFormatter(Context context) {
        this.context = context;
    }

    public List<String> buildActiveRuntimeOutputLines() {
        ArrayList<RunningJobLine> entries = new ArrayList<>();
        Map<Integer, RunningJob> jobsByNode = new LinkedHashMap<>();
        for (RunningJob job : context.runningJobs()) {
            ProcessNode node = context.findRuntimeNode(job.nodeId);
            if (node == null || !node.locked || job.durationTicks <= 0) {
                continue;
            }
            RunningJob current = jobsByNode.get(node.id);
            if (current == null || job.remainingTicks < current.remainingTicks) {
                jobsByNode.put(node.id, job);
            }
        }
        boolean staticNodeDisplay = context.runtimeGraph().nodes.size() <= context.staticNodeLineThreshold();
        if (staticNodeDisplay) {
            for (ProcessNode node : context.runtimeGraph().nodes) {
                if (node == null || !node.locked) {
                    continue;
                }
                RunningJob job = jobsByNode.get(node.id);
                String nodeName = context.safeNodeName(node);
                entries.add(new RunningJobLine(nodeName, node.endNode, buildRuntimeNodeLine(node, job)));
            }
        } else {
            for (RunningJob job : context.runningJobs()) {
                ProcessNode node = context.findRuntimeNode(job.nodeId);
                if (node == null || !node.locked || job.durationTicks <= 0) {
                    continue;
                }
                String nodeName = context.safeNodeName(node);
                entries.add(new RunningJobLine(nodeName, node.endNode, buildRuntimeNodeLine(node, job)));
            }
        }
        Collator collator = Collator.getInstance(Locale.CHINA);
        entries.sort(
            Comparator.comparing((RunningJobLine line) -> !line.targetNode)
                .thenComparing(line -> line.nodeName, collator)
                .thenComparing(line -> line.text));
        ArrayList<String> lines = new ArrayList<>();
        int visibleLimit = Math.max(0, context.visibleLineLimit());
        int shownLimit = entries.size() > visibleLimit ? Math.max(0, visibleLimit - 1) : visibleLimit;
        for (int i = 0; i < entries.size() && i < shownLimit; i++) {
            lines.add(entries.get(i).text);
        }
        if (entries.size() > shownLimit && visibleLimit > 0) {
            lines.add(
                EnumChatFormatting.DARK_GRAY + "  "
                    + context.translate("superfactory.machine.super_proxy_factory.gui.folded_prefix")
                    + " "
                    + (entries.size() - shownLimit)
                    + " "
                    + context.translate("superfactory.machine.super_proxy_factory.gui.folded_suffix"));
        }
        return lines;
    }

    private String buildRuntimeNodeLine(ProcessNode node, RunningJob job) {
        String nodeName = context.safeNodeName(node);
        if (job == null || job.durationTicks <= 0) {
            return EnumChatFormatting.DARK_AQUA + trimToDisplayWidth(nodeName, 78)
                + EnumChatFormatting.WHITE
                + " "
                + EnumChatFormatting.GREEN
                + "0/s"
                + " "
                + EnumChatFormatting.GRAY
                + "0/"
                + Math.max(1, context.getEffectiveDurationTicks(node));
        }
        int progress = Math.max(0, job.durationTicks - job.remainingTicks);
        return EnumChatFormatting.AQUA + trimToDisplayWidth(nodeName, 78)
            + EnumChatFormatting.WHITE
            + " "
            + buildRunningJobRateSummary(node, job)
            + " "
            + EnumChatFormatting.GRAY
            + progress
            + "/"
            + job.durationTicks;
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
            rate = context.getStackAmount(output) * Math.max(1, job.parallel)
                * chanceMultiplier
                * 20.0D
                / Math.max(1, job.durationTicks);
            fluidRate = fluid != null;
            break;
        }
        return EnumChatFormatting.GREEN + formatRate(rate) + (fluidRate ? "L/s" : "/s");
    }

    public static String formatRate(double rate) {
        if (rate >= 1000.0D) {
            return formatCompactAmount(rate);
        }
        if (rate >= 100.0D) {
            return String.valueOf(Math.round(rate));
        }
        if (rate >= 10.0D) {
            return String.format(Locale.ROOT, "%.1f", rate);
        }
        return String.format(Locale.ROOT, "%.2f", rate);
    }

    public static String formatCompactAmount(double value) {
        String[] suffixes = { "K", "M", "G", "T", "P", "E", "Z", "Y" };
        int suffix = -1;
        double scaled = value;
        while (Math.abs(scaled) >= 1000.0D && suffix + 1 < suffixes.length) {
            scaled /= 1000.0D;
            suffix++;
        }
        if (Math.abs(scaled) >= 1000.0D) {
            return String.format(Locale.ROOT, "%.2e", value);
        }
        String number;
        double scaledAbs = Math.abs(scaled);
        if (scaledAbs >= 100.0D) {
            number = String.format(Locale.ROOT, "%.0f", scaled);
        } else if (scaledAbs >= 10.0D) {
            number = trimTrailingZero(String.format(Locale.ROOT, "%.1f", scaled));
        } else {
            number = trimTrailingZero(String.format(Locale.ROOT, "%.2f", scaled));
        }
        return number + suffixes[Math.max(0, suffix)];
    }

    public static String trimToDisplayWidth(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static String trimTrailingZero(String value) {
        while (value.endsWith("0") && value.contains(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    public interface Context {

        ProcessGraph runtimeGraph();

        Iterable<RunningJob> runningJobs();

        ProcessNode findRuntimeNode(int nodeId);

        String safeNodeName(ProcessNode node);

        int getEffectiveDurationTicks(ProcessNode node);

        long getStackAmount(ItemStack stack);

        int staticNodeLineThreshold();

        int visibleLineLimit();

        String translate(String key);
    }

    private static final class RunningJobLine {

        private final String nodeName;
        private final boolean targetNode;
        private final String text;

        private RunningJobLine(String nodeName, boolean targetNode, String text) {
            this.nodeName = nodeName == null ? "" : nodeName;
            this.targetNode = targetNode;
            this.text = text == null ? "" : text;
        }
    }
}
