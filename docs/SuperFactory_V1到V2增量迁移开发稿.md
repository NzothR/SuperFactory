# SuperFactory 工序集成核心 V1 → V2 增量迁移开发稿

## 1. 文档目的

本文档用于指导当前 `SuperFactory` 项目从已实现的 V1 工序集成核心迁移到 V2 工序机器模型。

本文档不是从零开发稿，而是基于当前代码现状的增量迁移方案，重点关注：

```text
1. 当前 V1 已有能力如何复用。
2. 哪些语义必须升级为 V2。
3. 哪些类和方法需要新增、拆分或替换。
4. 多环工序、循环启动物料、目标产物节点、OUTPUT 卸载语义如何迁移。
5. 调度器如何从 V1 双阶段调度迁移为 V2 分层候选队列。
6. 性能问题如何在迁移阶段提前规避。
7. 每个阶段的验收标准和回归测试重点。
```

V2 迁移原则：

```text
图语义先行，运行器后改。
OUTPUT 语义先改，调度器后改。
多环分析先落地，配平工具最后做。
性能索引从一开始设计，不等卡顿后再补。
```

---

## 2. 当前实现资产盘点

当前 V1 已经具备较完整的基础能力：

```text
1. 工序图编辑、节点管理、节点锁定。
2. 节点输入、输出、NC、主机、耗时、EU/t、OC、parallelLimit。
3. 普通节点与回收节点。
4. processGraph / runtimeGraph / pendingRuntimeGraph 三套图状态。
5. STANDBY / INPUT / RUNNING / OUTPUT 四状态。
6. ProcessRequirements 收集 NC、启动物料、主机需求。
7. internalItems / internalFluids 内部缓存。
8. outputItems / outputFluids 输出缓存。
9. runningJobs 虚拟任务。
10. RuntimeResourceSnapshot 运行资源快照。
11. ME / DualInput 等输入来源读取。
12. 普通中间物高低水位节流。
13. 循环目标物料 reserve / overflow。
14. 断电时中断 runningJobs。
15. 原材料导出。
16. long 饱和运算工具。
```

当前关键类：

```text
com.nzoth.superfactory.common.mte.MTESuperIntegratedFactory
com.nzoth.superfactory.common.process.ProcessGraph
com.nzoth.superfactory.common.process.ProcessNode
com.nzoth.superfactory.common.process.ProcessEdge
com.nzoth.superfactory.common.process.ProcessRequirements
com.nzoth.superfactory.common.process.runtime.BufferedItemStack
com.nzoth.superfactory.common.process.runtime.BufferedFluidStack
com.nzoth.superfactory.common.process.runtime.ProcessBufferUtil
com.nzoth.superfactory.common.process.runtime.ProcessRuntimeMath
```

迁移策略不是推倒重写，而是逐步把图分析、提交计划、环分析、调度器和水位管理从 `MTESuperIntegratedFactory` 中拆出。

---

## 3. V1 → V2 关键差异

### 3.1 目标节点语义变化

V1：

```text
endNode = 结束节点 / 目标终点
endNode 通常被视为拓扑终点
```

V2：

```text
targetProductNode = 目标产物节点
目标产物节点不是拓扑终点
目标产物节点后面仍可继续连接副产处理链、回收链、循环链
```

迁移策略：

```text
1. 保留 ProcessNode.endNode 字段用于 NBT 兼容。
2. 代码语义中将 endNode 解释为 targetProductNode。
3. 后续可新增 targetProductNode 字段，但第一阶段不强制。
4. 预留 explicitTargetOutputs 字段位作为占位符，第一阶段不启用，不改 UI。
5. 第一阶段目标产物仅使用自动推断。
```

---

### 3.2 环模型变化

V1：

```text
偏向单个大循环图。
结束节点参与回流时视为循环目标。
```

V2：

```text
一张工序图可以存在多个局部环。
每个环是一个 SCC。
每个有效环只允许一种循环物料。
每个环都必须具备正净输出。
每个环可以有自己的启动物料需求。
```

第一阶段迁移约束：

```text
1. 只迁移单循环物料约束。
2. 只迁移每环正净输出检查。
3. 只迁移每环启动物料收集。
4. 不引入显式目标输出字段。
5. 目标产物仍通过自动推断得到。
```

因此 V2 需要新增：

```text
SCC 分析
CycleInfo
每环启动物料推断与收集
每环 reserve / lowWater / highWater
环内消费者与环外消费者的可用量差异
```

---

### 3.3 OUTPUT 语义变化

当前 V1 OUTPUT 行为倾向于：

```text
1. abort running job 并尝试返还消耗输入。
2. moveAllInternalToOutput。
3. 返还 startupItems / startupFluids。
```

V2 目标行为：

```text
1. OUTPUT 默认不返还 running job 已消耗输入。
2. OUTPUT 默认不输出内部中间物。
3. OUTPUT 默认不返还循环启动物料。
4. OUTPUT 只输出目标/副产缓存、应输出的 NC 与主机。
5. Debug 模式开启时才输出内部中间物。
```

---

### 3.4 调度器变化

V1 当前运行调度大体是：

```text
先调度消耗 internalBuffer 的节点。
再调度其他节点。
节点排序依赖 distanceToTerminal / endNode。
```

V2 目标调度器：

```text
消费者优先的分层候选队列调度

L0：强制推进队列
L1：内部消耗队列
L2：目标推进队列
L3：缺料补给队列
L4：普通源头队列
```

迁移期说明：

```text
1. 第一阶段只落地分层候选队列接口骨架。
2. 候选队列内部可以先继续沿用旧调度权重。
3. 调度顺序从 V1 双阶段迁移到 V2 分层队列时，不改变节点一次只启动一个 job 的模型。
4. 目标产物仍以自动推断为准。
```

---

## 4. 推荐新增包结构

```text
com.nzoth.superfactory.common.process.key
com.nzoth.superfactory.common.process.analysis
com.nzoth.superfactory.common.process.submit
com.nzoth.superfactory.common.process.schedule
com.nzoth.superfactory.common.process.watermark
com.nzoth.superfactory.common.process.runtime
```

当前已有 `runtime` 包，可继续扩展。

---

# 阶段 1：图语义分析层

## 1.1 目标

新增 V2 图分析层，先不改变运行器行为。

该阶段完成后，应能从当前 `ProcessGraph` 生成稳定的图语义结果：

```text
节点索引
边索引
物料索引
目标产物定义
直接生产者 / 直接消费者
源头节点
拓扑终点
SCC 环
每环循环物料
每环正净输出检查结果
每环启动条件
```

---

## 1.2 新增类

```text
com.nzoth.superfactory.common.process.key.MaterialKey

com.nzoth.superfactory.common.process.analysis.ProcessGraphAnalyzer
com.nzoth.superfactory.common.process.analysis.GraphAnalysisResult
com.nzoth.superfactory.common.process.analysis.NodeRelationIndex
com.nzoth.superfactory.common.process.analysis.TargetOutputResolver
com.nzoth.superfactory.common.process.analysis.SccCycleAnalyzer
com.nzoth.superfactory.common.process.analysis.CycleInfo
com.nzoth.superfactory.common.process.analysis.CycleMaterialInfo
com.nzoth.superfactory.common.process.analysis.GraphValidationResult
com.nzoth.superfactory.common.process.analysis.GraphValidationError
```

---

## 1.3 MaterialKey

用于统一物品 / 流体匹配。

```java
public final class MaterialKey {
    public enum Type {
        ITEM,
        FLUID
    }

    public final Type type;
    public final String id;
    public final int meta;
    public final String nbtFingerprint;
}
```

第一版可简化为：

```text
ITEM:
    registryName + meta + normalized NBT

FLUID:
    fluidName
```

注意：

```text
MaterialKey 只表达物料身份，不表达数量。
```

---

## 1.4 GraphAnalysisResult

建议结构：

```java
public final class GraphAnalysisResult {
    public final ProcessGraph graph;

    public final Map<Integer, ProcessNode> nodesById;

    public final Map<Integer, List<ProcessEdge>> outgoingEdgesByNode;
    public final Map<Integer, List<ProcessEdge>> incomingEdgesByNode;

    public final Map<MaterialKey, List<Integer>> producerNodesByMaterial;
    public final Map<MaterialKey, List<Integer>> consumerNodesByMaterial;

    public final Map<Integer, Set<MaterialKey>> targetOutputsByNode;
    public final Set<MaterialKey> allTargetOutputs;

    public final List<CycleInfo> cycles;
    public final Map<Integer, CycleInfo> cycleByNodeId;
    public final Map<MaterialKey, CycleInfo> cycleByMaterial;

    public final GraphValidationResult validation;
}
```

---

## 1.5 TargetOutputResolver

V2 目标产物推断规则：

```text
如果目标产物节点没有下游节点：
    所有输出视为目标产物。

如果目标产物节点存在下游节点：
    未被直接下游消费的输出视为目标产物。
    被下游消费的输出视为中间物或副产处理输入。
```

第一阶段可以暂时只支持旧字段：

```text
node.endNode == true
```

后续再加入：

```text
explicitTargetOutputs
```

第一阶段迁移约束：

```text
1. 不要求 UI 支持显式目标输出。
2. 不要求玩家手动标记 explicitTargetOutputs。
3. 目标产物全部通过下游消费关系自动推断。
4. explicitTargetOutputs 仅作为后续扩展占位符保留。
```

---

## 1.6 SCC / CycleInfo

多环支持必须使用 SCC 分析。

建议结构：

```java
public final class CycleInfo {
    public final int cycleId;
    public final List<Integer> nodeIds;

    public final MaterialKey cycleMaterial;
    public final boolean validSingleMaterialCycle;

    public final double producedRate;
    public final double consumedRate;
    public final double netRate;

    public final boolean positiveNetOutput;
    public final boolean hasStartupPath;

    public final Set<MaterialKey> requiredStartupMaterials;
}
```

第一版 rate 可使用 `double`，后续配平可改为有理数。

---

## 1.7 多环启动物料推断

V2 的启动物料不是“整张图一个循环启动物料集合”，而是每个有效环都可能有自己的启动需求。

规则：

```text
1. 对每个 CycleInfo 单独分析启动条件。
2. 如果环内存在不依赖 cycleMaterial 即可运行的入口节点，则该环不强制要求 cycleMaterial 启动物料。
3. 如果环完全依赖 cycleMaterial 才能启动，则 cycleMaterial 需要作为该环启动物料。
4. 如果 cycleMaterial 可由外部输入直接提供，也可不强制自动列入 startupMaterials，但需要图检查允许。
```

第一版保守策略：

```text
若 SCC 内任一节点输入 cycleMaterial，
且 SCC 内没有可无 cycleMaterial 启动的节点，
则 cycleMaterial 进入 startupMaterials。
```

后续优化可使用依赖剥离算法：

```text
1. 初始 available = 外部输入物料集合 + 启动物料集合。
2. 在 SCC 内反复寻找输入均满足的节点。
3. 将该节点输出加入 available。
4. 若最终所有环节点都可运行，则该环可启动。
5. 否则缺失的 cycleMaterial 或关键物料进入 startupMaterials 建议。
```

---

## 1.8 验收标准

```text
1. 旧图可以正常读入并完成分析。
2. 原有 endNode 能被识别为 targetProductNode。
3. 能输出每个节点的直接上游 / 下游。
4. 能识别源头节点与拓扑终点。
5. 能识别 SCC 环。
6. 能识别每个环的唯一循环物料。
7. 能基于当前 parallelLimit / duration 判断 cycleNetRate。
8. 能输出每个环建议的启动物料。
9. 分析结果不影响当前运行行为。
```

---

# 阶段 2：OUTPUT 与运行中断语义迁移

## 2.1 目标

将当前 OUTPUT 行为从 V1 改为 V2。

这是优先级最高的运行语义改造之一。

---

## 2.2 当前需要替换的行为

V2 中应替换为：

```text
discardRunningJobsWithLoss()
默认 clear internalItems / internalFluids
默认清除 startupItems / startupFluids
只输出目标/副产 outputBuffer
只输出应返还的 NC 与主机
debugExportInternalBuffer 开启时才导出 internalItems / internalFluids
```

---

## 2.3 新增配置

```java
public static boolean debugExportIntegratedFactoryInternalBuffer = false;
```

建议配置路径：

```text
superfactory.machine.super_integrated_factory.debugExportInternalBuffer
```

默认：

```text
false
```

---

## 2.4 新增方法

```java
private void discardRunningJobsWithLoss();
private void clearInternalRuntimeBuffersForUnload();
private void exportInternalBuffersForDebug();
private void clearStartupMaterialsForUnload();
private boolean shouldDebugExportInternalBuffer();
```

---

## 2.5 修改 processOutputMode

V2 伪代码：

```java
private void processOutputMode() {
    discardRunningJobsWithLoss();

    flushOutputBuffers();

    if (shouldDebugExportInternalBuffer()) {
        moveAllInternalToOutput();
        flushOutputBuffers();
    } else {
        internalItems.clear();
        internalFluids.clear();
    }

    clearStartupMaterialsForUnload();

    processQualificationOutputOrRetention();

    if (hasOutputBlocked()) {
        markDirty();
        return;
    }

    finishOutputUnload();
}
```

注意：

```text
startupMaterials 不再输出。
running job 已消耗输入不再返还。
internalBuffer 默认不输出。
```

---

## 2.6 运行暂停保持独立

断电、关闭电源、能量不足属于运行暂停，不属于 OUTPUT 卸载。

要求：

```text
discardRunningJobsForPowerLoss 不能清空 internalItems/internalFluids/outputItems/outputFluids。
不能进入 OUTPUT。
不能清空 runtimeGraph。
```

统一语义：

```text
运行暂停 RuntimePause：
    跳电、关闭电源、能量不足。
    中断 runningJobs，但不卸载当前工序。
    保留 currentGraph、主机、NC、internalBuffer、outputBuffer。

工序卸载 ProcessUnload：
    提交新图、螺丝刀输出、明确卸载当前工序。
    进入 OUTPUT。
    默认损失 runningJobs 已消耗输入，默认清除内部中间物和启动物料。
```

---

## 2.7 验收标准

```text
1. 断电 / 关闭电源只中断 runningJobs，不清空内部缓存与输出缓存。
2. 螺丝刀 OUTPUT 默认清除 internalItems/internalFluids。
3. 螺丝刀 OUTPUT 默认不返还 startupItems/startupFluids。
4. 提交新图 OUTPUT 默认清除内部中间物。
5. runningJobs 在 OUTPUT 中直接损失，不返还 consumedInputs。
6. debugExportInternalBuffer = true 时，OUTPUT 可导出内部中间物用于调试。
```

---

# 阶段 3：SubmitPlan 与 deferredGraph

## 3.1 目标

实现 V2 提交新图流程：

```text
提交新图时：
    新图仍需要的 NC / 主机继承
    新图不需要的 NC / 主机输出
    缺少的 NC / 主机进入 INPUT
    循环启动物料不继承，必须重新收集

OUTPUT 中再次提交新图：
    只保存一个 deferredGraph
    覆盖旧 deferredGraph
    当前 OUTPUT 完成后再执行完整提交流程
```

---

## 3.2 新增类

```text
com.nzoth.superfactory.common.process.submit.SubmitPlan
com.nzoth.superfactory.common.process.submit.SubmitPlanner
com.nzoth.superfactory.common.process.submit.RequirementDelta
com.nzoth.superfactory.common.process.submit.StoredQualificationView
```

---

## 3.3 SubmitPlan 结构

```java
public final class SubmitPlan {
    public final ProcessGraph newGraph;
    public final ProcessRequirements newRequirements;

    public final ProcessRequirements retainedRequirements;
    public final ProcessRequirements missingRequirements;
    public final ProcessRequirements outputRequirements;

    public final List<ItemStack> retainedMachines;
    public final List<ItemStack> outputMachines;

    public final boolean requiresOutputUnload;
}
```

规则：

```text
retainedRequirements:
    新图仍需要并且旧图已有的 NC / 主机。

missingRequirements:
    新图缺少，需要 INPUT 收集的 NC / 主机 / 启动物料。

outputRequirements:
    旧图已有但新图不再需要的 NC / 主机。
```

启动物料规则：

```text
startupItems/startupFluids 永远进入 missingRequirements。
不从旧图继承。
不从旧图返还。
```

---

## 3.4 deferredGraph 字段

建议在 `MTESuperIntegratedFactory` 中新增：

```java
private final ProcessGraph deferredRuntimeGraph = new ProcessGraph();
private boolean hasDeferredRuntimeGraph;
```

---

## 3.5 提交行为

目标语义：

```java
private void submitProcessGraph(ProcessGraph graph) {
    if (factoryMode == MODE_OUTPUT) {
        deferredRuntimeGraph.readFromNBT(graph.writeToNBT());
        hasDeferredRuntimeGraph = true;
        markDirty();
        return;
    }

    pendingRuntimeGraph.readFromNBT(graph.writeToNBT());
    pendingProcessRequirements.readFromNBT(buildRequirements(graph).writeToNBT());

    submitPlan = SubmitPlanner.create(runtimeGraph, processRequirements, pendingRuntimeGraph, pendingRequirements);

    factoryMode = MODE_OUTPUT;
}
```

---

## 3.6 OUTPUT 完成行为

```java
if (hasDeferredRuntimeGraph) {
    ProcessGraph graph = copyDeferredGraph();
    clearDeferredGraph();
    submitProcessGraph(graph);
    return;
}

if (hasPendingGraph()) {
    installPendingBySubmitPlan();
    return;
}

factoryMode = MODE_STANDBY;
```

关键点：

```text
deferredGraph 不参与当前 OUTPUT。
deferredGraph 只在当前 OUTPUT 完成后执行完整提交流程。
OUTPUT 中多次提交只保留最新 deferredGraph。
```

---

## 3.7 验收标准

```text
1. RUNNING 中提交新图会进入 OUTPUT。
2. 新图仍需要的 NC / 主机可继承。
3. 新图不需要的 NC / 主机会输出。
4. 新图缺失的 NC / 主机进入 INPUT。
5. 启动物料不继承。
6. OUTPUT 中提交新图不会改变当前 OUTPUT 行为。
7. OUTPUT 中多次提交只保留最后一次。
8. 当前 OUTPUT 完成后，对最后一次 deferredGraph 执行完整提交流程。
```

---

# 阶段 4：输出路由去 endNode 化

## 4.1 目标

将当前输出路由从 V1 的 `endNode` 判断迁移到 V2 的 `GraphAnalysisResult` 判断。

V2 路由规则：

```text
如果 output 是循环物料：
    进入内部循环缓存，按 reserve 溢出。
否则如果 output 有直接消费者：
    进入 internalBuffer。
否则如果 output 是目标产物：
    进入 outputBuffer。
否则：
    进入 outputBuffer，作为副产物。
```

---

## 4.2 新增 RuntimeRouteResolver

```text
com.nzoth.superfactory.common.process.runtime.RuntimeRouteResolver
```

枚举：

```java
public enum OutputRouteType {
    CYCLE_INTERNAL,
    INTERNAL,
    TARGET_OUTPUT,
    BYPRODUCT_OUTPUT
}
```

---

## 4.3 路由方法改造

替换：

```java
private void routeItemOutput(ProcessNode node, ItemStack output, long amount)
private void routeFluidOutput(ProcessNode node, FluidStack output, long amount)
```

为：

```java
OutputRouteType route = routeResolver.resolve(node.id, materialKey);

switch (route) {
    case CYCLE_INTERNAL:
        add to internal buffer;
        spillCycleOverflow(cycleInfo, materialKey);
        break;

    case INTERNAL:
        add to internal buffer;
        break;

    case TARGET_OUTPUT:
    case BYPRODUCT_OUTPUT:
        add to output buffer;
        break;
}
```

---

## 4.4 环内 / 环外可用量

新增：

```java
long consumableInternalItemAmountForNode(ProcessNode consumer, ItemStack template)
long consumableInternalFluidAmountForNode(ProcessNode consumer, FluidStack template)
```

规则：

```text
如果物料不是循环物料：
    返回 internalBuffer 中数量。

如果 consumer 属于该物料对应 SCC：
    返回 internalBuffer 中全部数量。

如果 consumer 不属于该 SCC：
    返回 max(0, internalBuffer - reserve)。
```

---

## 4.5 验收标准

```text
1. 目标产物节点后可以继续连接副产处理链。
2. 被直接下游消费的目标节点输出不会直接外排。
3. 显式目标产物即使被消费，仍保留目标身份。
4. 循环物料环内可使用 reserve。
5. 循环物料环外只能使用超过 reserve 的净输出。
6. 无消费者且非目标的产物作为副产物输出。
```

---

# 阶段 5：多环启动物料与多环水位

## 5.1 目标

从 V1 的“单个循环目标水位”升级为 V2 的“每个局部环独立水位”。

---

## 5.2 新增类

```text
com.nzoth.superfactory.common.process.runtime.CycleRuntimeState
com.nzoth.superfactory.common.process.runtime.CycleRuntimeManager
```

结构：

```java
public final class CycleRuntimeState {
    public final int cycleId;
    public final MaterialKey cycleMaterial;

    public long reserve;
    public long lowWater;
    public long highWater;
}
```

---

## 5.3 reserve 计算

第一版使用保守策略：

```text
reserve = max(环内消费者一次有效运行所需 cycleMaterial 数量)
lowWater = reserve
targetWater = ceil(reserve * 1.5)
highWater = reserve * 3
```

如果环内有多个消费者：

```text
reserve = max(eachConsumerNeed)
```

后续可改为：

```text
reserve = sum(关键环内消费者一次需求)
```

---

## 5.4 多环启动物料写入 ProcessRequirements

当前 `ProcessRequirements` 已有：

```text
startupItems
startupFluids
```

V2 继续使用，但来源改为：

```text
GraphAnalysisResult.cycles[*].requiredStartupMaterials
```

处理流程：

```text
1. 提交图时执行 GraphAnalysis。
2. 对每个 CycleInfo 获取 requiredStartupMaterials。
3. 合并所有环的启动需求。
4. 写入 ProcessRequirements.startupItems/startupFluids。
5. INPUT 收集这些启动物料。
6. initializeRunningRuntime 时移动到 internalBuffer。
```

合并规则：

```text
同一 MaterialKey 的启动物料需求累加。
物品按 ItemStack 模板合并。
流体按 FluidStack 合并。
同一物料如果被多个环需要，应按需求总量收集。
```

---

## 5.5 多环合法性检查

提交前必须检查：

```text
1. 每个 SCC 至多一个循环物料。
2. 每个 CycleInfo.cycleNetRate > 0。
3. 每个环有启动条件。
4. 不允许一个物料同时作为多个独立环的 cycleMaterial，除非这些 SCC 可明确区分且不会共享 reserve。
```

第一版建议保守处理：

```text
若同一个 MaterialKey 出现在多个 CycleInfo 中作为 cycleMaterial：
    拒绝提交或提示“多个环共享循环物料暂不支持”。
```

理由：

```text
共享循环物料会导致 reserve 和净输出归属不清。
```

---

## 5.6 验收标准

```text
1. 单环旧图仍能运行。
2. 两个互不相关局部环可以同时存在。
3. 每个环能生成独立 CycleInfo。
4. 每个环能生成独立 startupMaterials。
5. INPUT 能收集多个环的启动物料总需求。
6. 每个环的 reserve 独立生效。
7. 环外消费者只能消费对应环超过 reserve 的净输出。
8. 多环共享同一循环物料时能被拒绝或明确提示。
```

---

# 阶段 6：分层候选队列调度器

## 6.1 目标

替换当前双阶段调度：

```text
requireInternalInput = true
requireInternalInput = false
```

改为 V2 分层候选队列：

```text
L0 强制推进
L1 内部消耗
L2 目标推进
L3 缺料补给
L4 普通源头
```

---

## 6.2 新增类

```text
com.nzoth.superfactory.common.process.schedule.ProcessScheduler
com.nzoth.superfactory.common.process.schedule.SchedulerContext
com.nzoth.superfactory.common.process.schedule.CandidateLayer
com.nzoth.superfactory.common.process.schedule.NodeCandidate
com.nzoth.superfactory.common.process.schedule.CandidateQueueSet
com.nzoth.superfactory.common.process.schedule.RunCreditState
```

---

## 6.3 CandidateLayer

```java
public enum CandidateLayer {
    FORCED_PROGRESS,
    INTERNAL_CONSUME,
    TARGET_PROGRESS,
    LOW_WATER_SUPPLY,
    SOURCE_PRODUCTION
}
```

---

## 6.4 NodeCandidate

```java
public final class NodeCandidate {
    public final ProcessNode node;
    public final int actualParallel;
    public final CandidateLayer layer;
    public final double runCredit;
    public final int targetDistance;
}
```

---

## 6.5 调度流程

```java
public void schedule(SchedulerContext ctx) {
    ctx.advanceJobs();
    ctx.updateSnapshot();
    ctx.updateProjectedSoon();
    ctx.updateRunCredit();

    CandidateQueueSet queues = buildCandidateQueues(ctx);

    int starts = 0;

    for (CandidateLayer layer : CandidateLayer.values()) {
        for (NodeCandidate candidate : queues.get(layer)) {
            if (starts >= ctx.maxStartsPerTick()) {
                return;
            }

            if (ctx.tryStart(candidate)) {
                starts++;
                ctx.updateTemporaryResourceView(candidate);
            }
        }
    }
}
```

---

## 6.6 分阶段迁移策略

不要一次性实现全部层级。

推荐顺序：

```text
第 1 步：
    实现 L1 INTERNAL_CONSUME 与 L4 SOURCE_PRODUCTION。
    行为基本等价当前双阶段调度。

第 2 步：
    加入 maxStartsPerTick。
    防止复杂图单 tick 启动过多 job。

第 3 步：
    加入 runCredit。
    平滑节点启动频率。

第 4 步：
    加入 L3 LOW_WATER_SUPPLY。
    基于 projectedSoon <= lowWater 触发上游补料。

第 5 步：
    加入 L0 FORCED_PROGRESS。
    处理 highWater、reserve overflow、输出阻塞。

第 6 步：
    加入 L2 TARGET_PROGRESS。
    根据 targetDistance / target relevance 推进目标链。
```

---

## 6.7 runCredit

字段建议：

```java
private final Map<Integer, Double> runCreditByNode = new LinkedHashMap<>();
```

更新规则：

```text
credit += expectedStartRate
expectedStartRate = 1.0 / effectiveDurationTicks
```

启动成功：

```text
credit -= 1.0
```

限制：

```text
credit 应设置上限，防止长期缺料节点积累过高 credit。
```

建议：

```text
maxCredit = 4.0
```

---

## 6.8 maxStartsPerTick

建议新增：

```java
private static final int MAX_PROCESS_NODE_STARTS_PER_TICK = 16;
```

或配置项：

```text
superIntegratedFactoryMaxNodeStartsPerTick
```

设计建议：

```text
默认 16。
复杂图调试时可提高。
一般不建议无限制。
```

---

## 6.9 验收标准

```text
1. 原有简单链路能继续运行。
2. 共享原料场景下，下游内部消耗节点优先。
3. 中间物低于 lowWater 时，上游能提前补料。
4. 中间物超过 highWater 时，上游不再继续生产。
5. 目标产物输出比 V1 更连续。
6. 节点不会因 runCredit 长期饿死。
7. 单 tick 启动数量受 maxStartsPerTick 控制。
8. 调度器不会明显增加 TPS 压力。
```

---

# 阶段 7：projectedSoon 与水位重构

## 7.1 目标

当前 `RuntimeResourceSnapshot` 已能捕获 internal/live/dual 输入，并缓存矿典 id。V2 需要扩展：

```text
incomingWithinLookahead
reservedThisTick
projectedSoon
```

---

## 7.2 新增字段

```java
public final class RuntimeResourceSnapshot {
    ...
    private final List<BufferedItemStack> incomingItemWithinLookahead;
    private final List<BufferedFluidStack> incomingFluidWithinLookahead;

    private final List<BufferedItemStack> reservedItemThisTick;
    private final List<BufferedFluidStack> reservedFluidThisTick;
}
```

---

## 7.3 incomingWithinLookahead

规则：

```text
只统计 remainingTicks <= lookaheadWindow 的 running job 产物。
不统计长耗时 job 的远期产物。
```

默认：

```text
lookaheadWindow = 20 tick
```

---

## 7.4 reservedThisTick

每次输入事务成功后，记录本 tick 已消耗或预留的物料。

用于：

```text
后续候选节点计算 actualParallel
水位 projectedSoon 判断
避免同 tick 内多节点重复看到同一份资源
```

---

## 7.5 projectedSoon

```text
projectedSoon[m] =
    internalBuffer[m]
  + incomingWithinLookahead[m]
  - reservedThisTick[m]
```

普通水位与 L3 缺料补给都使用 projectedSoon。

---

## 7.6 验收标准

```text
1. 长耗时 job 不会让上游过早停止补料。
2. 快完成 job 可被用于短期水位判断。
3. 同 tick 内已扣资源不会被后续节点重复使用。
4. projectedSoon 可用于 lowWater/highWater 判断。
```

---

# 阶段 8：性能专项迁移

## 8.1 性能目标

```text
1. 普通几十节点工序每 tick 不明显影响 TPS。
2. 大型上百节点工序不进行全量昂贵扫描。
3. ME 查询与扣取次数受控。
4. OreDictionary 匹配不在热路径反复计算。
5. 输出 flush 每 tick 有预算。
6. 图分析只在图变更或提交时执行，不在运行 tick 中反复执行。
7. 调度候选尽量使用缓存索引。
```

---

## 8.2 必须避免的反模式

```text
1. 每 tick 对所有节点执行多次全量输入扫描。
2. 每 tick 对所有边做嵌套遍历。
3. 每个候选节点都重新扫描 ME 网络。
4. 每次 itemMatches 都重新取 OreDictionary IDs。
5. 每次 routeOutput 都遍历全图判断消费者。
6. 每次 cycle reserve 计算都遍历所有节点。
7. 每次 OUTPUT 都无限量 flush 输出。
8. 每 tick 重建完整 GraphAnalysisResult。
```

---

## 8.3 图分析缓存

`GraphAnalysisResult` 只在以下时机重建：

```text
1. 提交工序图。
2. 编辑图锁定/解锁导致结构变化。
3. 节点输入输出变化。
4. 节点 parallelLimit / duration / OC 变化并影响环检查。
```

运行 tick 中只能读取分析结果，不应重建分析。

---

## 8.4 索引结构

GraphAnalysisResult 应提供：

```text
nodeById
outgoingEdgesByNode
incomingEdgesByNode
directConsumersByNodeAndMaterial
directProducersByNodeAndMaterial
targetOutputsByMaterial
cycleByNode
cycleByMaterial
producerNodesByMaterial
consumerNodesByMaterial
```

运行器不得在热路径用嵌套全图遍历替代索引查询。

---

## 8.5 输入快照与 ME 查询

要求：

```text
1. 每 tick 最多构建一次输入快照。
2. 候选节点计算 actualParallel 只读 snapshot。
3. 真正启动时才执行实际扣取事务。
4. ME 网络查询结果在一个 tick 内复用。
5. ME 扣取失败后不要在同 tick 重复大量尝试。
```

---

## 8.6 OreDictionary 缓存

建议：

```text
1. MaterialKey 构建阶段缓存 item oreIDs。
2. RuntimeResourceSnapshot 内缓存本 tick item oreIDs。
3. 对同一个 ItemStack identity 只计算一次 OreDictionary IDs。
4. 对同一个 itemBufferKey 只计算一次匹配结果。
```

---

## 8.7 候选队列增量优化

第一版可以每 tick 遍历节点构建候选队列。

最终应支持 dirty 机制：

```text
DirtyMaterials
DirtyNodes
CandidateCache
WatermarkBlockedSet
OutputBlockedSet
```

触发 dirty 的事件：

```text
internalBuffer 变化
outputBuffer 变化
runningJob 完成
runningJob 中断
输入事务成功
输入舱内容变化
ME 快照变化
水位跨越 low/high
能源状态变化
图提交或卸载
```

开发建议：

```text
V2 初期先保留每 tick 全节点遍历。
但 ProcessScheduler 的接口必须允许未来切换到 dirty-node 模式。
```

---

## 8.8 输出 flush 预算

当前已有输出 flush 预算，应保留并扩展语义：

```text
1. OUTPUT 模式 flush 使用预算。
2. RUNNING 模式 flush 使用预算。
3. Debug 导出内部缓存时也必须走预算。
4. flush 未完成时 OUTPUT 不结束。
```

---

## 8.9 running job 数量控制

```text
每节点最多一个 runningJob。
全机器每 tick 最多启动 N 个 job。
```

建议新增：

```text
maxStartsPerTick
```

---

## 8.10 大数与 ItemStack 拆分

继续使用：

```text
BufferedItemStack.amount: long
BufferedFluidStack.amount: long
ProcessRuntimeMath.safeMultiply / safeAdd / safeCeilMultiply
```

输出到真实 ItemStack / FluidStack 时：

```text
按 Integer.MAX_VALUE 或 hatch 能力拆分。
```

---

## 8.11 性能压力测试

至少构造：

```text
1. 10 节点线性链。
2. 30 节点多输入多输出 DAG。
3. 50 节点含副产处理链。
4. 2 个独立局部环。
5. 1 个环 + 30 个普通节点。
6. 100 节点极限图。
7. 大量 ME 输入匹配场景。
8. 大量流体输出场景。
9. 大量概率产物场景。
```

记录指标：

```text
每 tick 平均耗时
最大单 tick 耗时
ME 查询次数
实际扣料次数
候选节点数
启动 job 数
输出 flush 数量
```

---

# 阶段 9：配平工具迁移

## 9.1 目标

配平工具最后迁移，因为它是可选推荐工具，不影响运行语义。

---

## 9.2 改造重点

```text
1. endNode 改为 targetProductNode 语义。
2. 使用 GraphAnalysisResult 的边索引。
3. 使用 MaterialKey 进行物料匹配。
4. 普通边可引入边流量变量。
5. 多目标图不要求每个目标分别最小。
6. 多环图只做当前 P 下正净输出检查。
7. 推荐结果只写 parallelLimit。
```

---

## 9.3 不做的事情

配平工具不负责：

```text
1. 自动修改 OC。
2. 自动修改 machine.parallelMultiplier。
3. 自动生成启动物料。
4. 在 RUNNING 中动态调整 P。
5. 强制让所有终产物绝对最小输出。
```

---

# 阶段 10：NBT 兼容与迁移

## 10.1 DataVersion

当前：

```java
ProcessGraph.DATA_VERSION = 1
```

V2 建议升级：

```java
public static final int DATA_VERSION = 2;
```

---

## 10.2 兼容规则

读取旧图时：

```text
EndNode -> targetProductNode
无 explicitTargetOutputs -> 使用默认推断
旧 resourceKey -> 转换为 MaterialKey 字符串格式
旧循环逻辑 -> 通过 GraphAnalyzer 重新分析
```

---

## 10.3 旧存档安全

必须保证：

```text
1. 旧图可读取。
2. 旧节点参数可保留。
3. 旧 endNode 不丢失。
4. 旧边可继续显示。
5. 旧运行状态如果不兼容，可在加载时安全进入 STANDBY 或 OUTPUT。
```

对于运行中旧状态，建议保守处理：

```text
如果检测到旧版本 runtime state：
    清除 runningJobs
    清除 internalBuffer
    保留 processGraph 编辑图
    机器进入 STANDBY
```

---

# 11. 回归测试清单

## 11.1 基础运行

```text
1. 单节点普通配方。
2. 两节点线性链。
3. 一个上游多个下游。
4. 多上游合成一个下游。
5. 副产物输出。
6. 概率产物输出。
7. 流体输入输出。
8. ME 输入物品。
9. ME 输入流体。
10. DualInput 输入。
```

---

## 11.2 状态机

```text
1. STANDBY 提交图。
2. INPUT 中提交新图。
3. RUNNING 中提交新图。
4. OUTPUT 中提交新图。
5. OUTPUT 中连续提交多个新图。
6. 螺丝刀进入 OUTPUT。
7. OUTPUT 阻塞时不切换状态。
8. OUTPUT 完成后进入 STANDBY。
9. OUTPUT 完成后处理 deferredGraph。
```

---

## 11.3 OUTPUT 语义

```text
1. 默认不输出内部中间物。
2. 默认不返还启动物料。
3. 默认不返还 running job 已消耗输入。
4. 输出目标产物缓存。
5. 输出副产物缓存。
6. 输出多余 NC / 主机。
7. Debug 模式输出内部中间物。
```

---

## 11.4 多环

```text
1. 单局部环。
2. 两个互不相关局部环。
3. 环无启动物料但可自启动。
4. 环需要 cycleMaterial 启动物料。
5. cycleNetRate <= 0 时拒绝。
6. 环中多循环物料时拒绝。
7. 多环共享 cycleMaterial 时拒绝或提示暂不支持。
8. 环内消费者可用 reserve。
9. 环外消费者只能用净输出。
```

---

## 11.5 调度

```text
1. 内部中间物优先消耗。
2. 共享外部原料时下游优先。
3. 低水位时上游补料。
4. 高水位时上游暂停。
5. 目标产物输出连续性改善。
6. runCredit 防止节点饿死。
7. maxStartsPerTick 生效。
```

---

## 11.6 性能

```text
1. 30 节点图运行不卡顿。
2. 100 节点图不会出现明显 TPS 抖动。
3. ME 查询次数稳定。
4. 输出大量流体不单 tick 卡死。
5. 大量概率产物不会逐个掷骰。
6. 图分析不在运行 tick 反复执行。
```

---

# 12. 推荐开发顺序总览

最终推荐顺序：

```text
阶段 1：图语义分析层
阶段 2：OUTPUT 与运行中断语义迁移
阶段 3：SubmitPlan 与 deferredGraph
阶段 4：输出路由去 endNode 化
阶段 5：多环启动物料与多环水位
阶段 6：分层候选队列调度器
阶段 7：projectedSoon 与水位重构
阶段 8：性能专项迁移
阶段 9：配平工具迁移
阶段 10：NBT 兼容与迁移
```

优先级最高的最小闭环：

```text
1. GraphAnalysisResult
2. OUTPUT 默认清除中间物
3. SubmitPlan 继承 NC / 主机
4. routeOutput 去 endNode 化
5. 多环启动物料收集
6. 分层调度器 L1/L4 初版
```

---

# 13. 第一轮可交付里程碑

建议第一轮 V2 增量开发只做到：

```text
1. 新增 GraphAnalyzer，可分析目标产物、直接消费者、SCC 环。
2. 新增 SubmitPlan，可继承 NC / 主机。
3. OUTPUT 默认清除内部中间物，不返还启动物料。
4. OUTPUT 支持 debugExportInternalBuffer。
5. OUTPUT 中提交新图写入 deferredGraph。
6. routeItemOutput / routeFluidOutput 使用 GraphAnalysisResult。
7. 支持多个局部环的启动物料需求合并。
8. 调度器仍暂时使用旧双阶段，但内部改成可替换接口。
```

这一轮完成后，V2 的语义基础就已经落地，后续再替换调度器和配平工具会安全很多。

---

# 14. 结论

当前项目的 V1 完成度已经很高，V2 迁移不应该重写机器，而应基于当前运行器做分层改造。

最重要的增量模块是：

```text
GraphAnalysisResult
SubmitPlan
RuntimeRouteResolver
CycleRuntimeManager
ProcessScheduler
ProjectedSoon / RuntimeResourceSnapshot 扩展
```

只要这几个模块拆出来，当前臃肿的 `MTESuperIntegratedFactory` 就能逐步从“所有逻辑集中在一个类里”的 V1 状态，迁移到 V2 的可维护结构。
