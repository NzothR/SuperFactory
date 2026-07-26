# SuperFactory 工序集成核心性能分析与语义保持优化报告

> 分析对象：`5MIFShSInR.sparkprofile`  
> 仓库：`https://github.com/NzothR/SuperFactory`  
> 重点对象：`MTESuperIntegratedFactory`、`IntegratedFactoryScheduler`、`RuntimeResourceSnapshot`、`IntegratedFactoryWatermarks`  
> 说明：本文中的优化建议以“保持功能语义不变”为前提。所有性能建议均需结合实际源码、GTNH/GT5u/AE2/Programmable Hatches 行为和回归测试验证后再落地。

---

## 1. 总体结论

最新 profile 显示：上一轮最严重的性能问题，也就是 **live input 反复扫描、ME Input Bus 频繁查询、`getStoredInputs` / `depleteInput` 高占用**，已经基本解决。

当前新的主要问题已经不是外部输入扣取爆炸，而是进入了第二阶段瓶颈：

```text
复杂工序图下，调度器本身的候选节点构建、节点分类、低水位判断、输出节流、资源快照查询仍然偏重。
```

也就是说，现在问题从：

```text
外部 I/O 型热点：
  getStoredInputs
  depleteInput
  MTEHatchInputBusME
  Programmable Hatches query
  AEItemStack.create
```

转移到了：

```text
算法与图分析型热点：
  buildNodeCandidates
  classifyCandidateLayer
  suppliesLowWater
  consumesAvailableInternalInput
  producesTargetOutput
  getRunnableParallel
  isExternalOutputThrottled
  RuntimeResourceSnapshot count
  IntegratedFactoryWatermarks
```

这是一种正常的优化阶段变化。第一阶段已经把“大头的错误复杂度”压下去了；现在剩下的是调度模型本身在复杂图上的复杂度问题。

---

## 2. 最新 profile 关键数据

### 2.1 基本状态

根据最新 spark profile，整体 TPS 已经基本稳定在 20 TPS 附近。MSPT 常规平均处于健康范围，但存在复杂调度时的瞬时尖峰。

概要判断：

```text
整体服务器状态：健康
普通 tick：较轻
复杂工序调度 tick：仍可能出现明显尖峰
主要业务热点：MTESuperIntegratedFactory
```

### 2.2 主线程总体耗时结构

本次 profile 中，主线程主要结构大致如下：

```text
MinecraftServer.run                         ≈ 888796 ms
├─ Thread.sleep                              ≈ 811060 ms
└─ MinecraftServer.func_71217_p              ≈ 76356 ms
   └─ DedicatedServer.func_71190_q           ≈ 53036 ms
      └─ World.updateEntities                ≈ 29488 ms
         └─ MTESuperIntegratedFactory         ≈ 20808 ms
```

这说明服务器并不是长期 CPU 跑满。主线程有大量时间处于 sleep，整体负载是健康的。

但在实际 tick 工作量中，`MTESuperIntegratedFactory` 仍然是最大的业务热点之一：

```text
World.updateEntities 总耗时        ≈ 29488 ms
MTESuperIntegratedFactory 耗时     ≈ 20808 ms
```

也就是说：

```text
SuperFactory 工序集成核心不是导致全服长期低 TPS 的严重问题，
但它仍然是复杂工序图下最需要继续优化的局部热点。
```

---

## 3. 最新热点路径

### 3.1 顶层热点路径

最新主要热点路径如下：

```text
MTESuperIntegratedFactory.onPostTick
└─ processRunningMode
   └─ scheduleRunnableNodes
      └─ IntegratedFactoryScheduler.schedule
         └─ buildNodeCandidates
            ├─ classifyCandidateLayer
            │  ├─ suppliesLowWater
            │  ├─ consumesAvailableInternalInput
            │  └─ producesTargetOutput
            ├─ runnableParallel
            ├─ isExternalOutputThrottled
            └─ distanceToTerminal
```

其中比较明显的热点：

```text
MTESuperIntegratedFactory.onPostTick       ≈ 20808 ms
processRunningMode                         ≈ 20100 ms
scheduleRunnableNodes                      ≈ 16064 ms
IntegratedFactoryScheduler.schedule        ≈ 16064 ms
buildNodeCandidates                        ≈ 11464 ms
classifyCandidateLayer                     ≈ 8268 ms
suppliesLowWater                           ≈ 5016 ms
consumesAvailableInternalInput             ≈ 1952 ms
producesTargetOutput                       ≈ 1300 ms
runnableParallel                           ≈ 1340 ms
isExternalOutputThrottled                  ≈ 1256 ms
distanceToTerminal                         ≈ 204 ms
```

### 3.2 性能问题性质

这次的核心问题不是“某个外部 API 极慢”，而是：

```text
复杂图下，每 tick 调度阶段重复做了较多图遍历、槽位扫描、buffer 线性查询和材料分类判断。
```

目前的复杂度趋势更接近：

```text
CandidateLayer 数量 × 节点数 × 分类成本 × 槽位数 × buffer 种类数 × 边数
```

理想上应逐步优化为：

```text
节点数 × 预编译节点信息 + 少量变动资源查询 + 分桶排序
```

---

## 4. 源码结构与热点函数语义分析

下面逐个解释热点函数的业务语义、当前性能问题，以及为什么可以按建议方向优化。

---

# 4.1 `IntegratedFactoryScheduler.schedule`

## 语义

`IntegratedFactoryScheduler.schedule(context, debugRuntime)` 是工序集成核心的运行时调度入口。

它的核心职责是：

1. 更新每个节点的运行积分 `runCredit`；
2. 按候选层级 `CandidateLayer` 的优先级依次查找可启动节点；
3. 每层构建候选节点列表；
4. 按排序规则选择候选；
5. 尝试启动节点；
6. 启动成功后扣除对应节点的运行积分；
7. 每 tick 启动数量不能超过 `maxNodeStartsPerTick()`。

它表达的调度策略可以理解为：

```text
在每个 tick 内：
  先保证内部中间产物能被消费；
  再保证低水位材料能被补充；
  再推进最终目标产物；
  最后允许源头生产；
同时通过 runCredit 保持不同节点之间的长期公平性。
```

## 当前实现的性能问题

当前结构是：

```java
for (CandidateLayer layer : CandidateLayer.values()) {
    for (NodeCandidate candidate : buildNodeCandidates(context, layer, debugRuntime)) {
        ...
    }
}
```

也就是说，假设有 4 个 `CandidateLayer`，那么每个 tick 会对整张图执行 4 次候选构建。

而 `buildNodeCandidates()` 本身会遍历所有节点，并对每个节点做：

```text
runningJobsForNode
effectiveParallelLimit
effectiveDurationTicks
locked 检查
classifyCandidateLayer
isExternalOutputThrottled
runnableParallel
distanceToTerminal
```

因此复杂度接近：

```text
O(layerCount × nodeCount × nodeAnalysisCost)
```

## 优化建议

建议将“按层多次扫图”改为“一次扫图，然后按层分桶”。

当前逻辑：

```java
for (CandidateLayer layer : CandidateLayer.values()) {
    List<NodeCandidate> candidates = buildNodeCandidates(context, layer, debugRuntime);
    sort(candidates);
    tryStart(candidates);
}
```

建议逻辑：

```java
EnumMap<CandidateLayer, ArrayList<NodeCandidate>> buckets = new EnumMap<>(CandidateLayer.class);

for (ProcessNode node : context.schedulingOrder()) {
    NodeCandidate candidate = analyzeNodeOnce(context, node, debugRuntime);
    if (candidate != null) {
        buckets.get(candidate.layer).add(candidate);
    }
}

for (CandidateLayer layer : CandidateLayer.values()) {
    List<NodeCandidate> candidates = buckets.get(layer);
    sort(candidates);
    tryStart(candidates);
}
```

## 为什么这个优化语义不变

该优化只改变“候选构建的执行方式”，不改变以下语义：

```text
CandidateLayer 的优先级顺序不变；
每个节点的分类结果不变；
每层内部排序规则不变；
maxNodeStartsPerTick 限制不变；
tryStartNodeCandidate 的启动判定不变；
启动成功后 subtractRunCredit 的行为不变。
```

语义等价条件是：

```text
1. 每个节点在同一个调度 tick 中只被分类一次；
2. 分类结果与原 classifyCandidateLayer 完全一致；
3. 每个 bucket 内仍按原规则排序：
   - runCredit 降序
   - targetDistance 升序
   - node.id 升序
4. CandidateLayer 的遍历顺序保持不变；
5. tryStartNodeCandidate 仍按原候选顺序执行；
6. 启动成功后仍扣除 runCredit。
```

只要满足这些条件，调度结果应与原实现保持一致或高度一致，但候选构建成本会明显下降。

---

# 4.2 `buildNodeCandidates`

## 语义

`buildNodeCandidates(context, layer, debugRuntime)` 的语义是：

```text
在指定 CandidateLayer 中，找出当前 tick 可以尝试启动的节点候选。
```

它不是最终启动节点，而是构建候选列表。

其核心过滤条件包括：

```text
1. 节点当前没有正在运行的 job；
2. 节点 locked；
3. 有效并行上限 > 0；
4. 有效耗时 > 0；
5. 节点分类属于当前 layer；
6. 外部输出没有被节流；
7. 当前资源足以启动至少 1 并行；
8. 记录 runCredit、distanceToTerminal 等排序信息。
```

最后排序：

```text
runCredit 高的优先；
离终端目标更近的优先；
node.id 小的优先。
```

## 当前性能问题

`buildNodeCandidates()` 被每个 `CandidateLayer` 重复调用，因此其内部的昂贵判断也被重复执行。

尤其是：

```text
classifyCandidateLayer
isExternalOutputThrottled
runnableParallel
distanceToTerminal
```

这些都不应该在同一个 tick、同一个节点上被多次重复计算。

## 优化建议

引入 tick 内节点分析缓存，例如：

```java
final class TickNodeAnalysis {
    ProcessNode node;
    boolean idle;
    boolean locked;
    int effectiveParallelLimit;
    int effectiveDurationTicks;
    long effectiveEuPerTick;
    CandidateLayer layer;
    boolean externalOutputThrottled;
    int runnableParallel;
    double runCredit;
    int distanceToTerminal;
}
```

每 tick 对每个节点只做一次分析。

`buildNodeCandidates()` 可以被拆成：

```java
List<TickNodeAnalysis> analyzeAllNodesOnce();
EnumMap<CandidateLayer, List<NodeCandidate>> buildCandidateBuckets(List<TickNodeAnalysis> analyses);
```

## 为什么这个优化语义不变

该优化的前提是：同一个 scheduling tick 内，参与候选构建的输入条件不发生改变。

需要注意：

```text
tryStartNodeCandidate 成功启动某个节点后，资源快照可能变化；
如果后续候选仍使用旧 runnableParallel，可能导致候选并行数过高。
```

因此有两种安全方案：

### 方案 A：候选构建只作为粗筛，启动时重新校验

```text
buildNodeCandidates:
  使用 tick 初始快照生成候选；

tryStartNodeCandidate:
  启动前仍用最新快照 / 当前 buffer 做硬校验；
  如果资源不足则启动失败。
```

这个方案最容易保持语义正确。

### 方案 B：每次启动成功后更新本 tick 的调度快照

```text
启动成功：
  从调度快照中扣除资源；
  后续候选基于更新后的 snapshot 判断。
```

这个方案性能更好，但实现复杂度更高。

推荐先使用方案 A，确保语义安全，再逐步过渡到方案 B。

---

# 4.3 `classifyCandidateLayer`

## 语义

`classifyCandidateLayer(context, node)` 决定一个节点在当前 tick 属于哪个候选层级。

当前优先级逻辑是：

```java
if (context.consumesAvailableInternalInput(node)) {
    return CandidateLayer.INTERNAL_CONSUME;
}
if (context.suppliesLowWater(node)) {
    return CandidateLayer.LOW_WATER_SUPPLY;
}
if (context.producesTargetOutput(node)) {
    return CandidateLayer.TARGET_PROGRESS;
}
return CandidateLayer.SOURCE_PRODUCTION;
```

它表达的是调度策略优先级：

```text
1. INTERNAL_CONSUME：
   优先消费已经存在的内部中间产物，避免内部 buffer 堆积，并推动流水线向后走。

2. LOW_WATER_SUPPLY：
   如果某个中间物料低于低水位，优先补充它，避免下游断料。

3. TARGET_PROGRESS：
   如果节点产出最终目标产物，优先推进最终产出。

4. SOURCE_PRODUCTION：
   普通源头生产，优先级最低。
```

## 当前性能问题

`classifyCandidateLayer` 本身不是简单状态字段，而是会调用三个复杂判断：

```text
consumesAvailableInternalInput
suppliesLowWater
producesTargetOutput
```

其中 `suppliesLowWater` 最重。

由于当前调度器按 layer 多次构建候选，`classifyCandidateLayer` 会在同一 tick 被重复调用。

## 优化建议

在 tick 内缓存每个 node 的分类结果：

```java
Map<Integer, CandidateLayer> candidateLayerByNode;
```

或者合并到 `TickNodeAnalysis`：

```java
analysis.layer = classifyCandidateLayerOnce(node);
```

## 为什么这个优化语义不变

`classifyCandidateLayer` 的输入主要来自：

```text
当前内部 buffer；
当前资源快照；
当前 projected incoming；
当前图结构；
当前 output route；
当前 target outputs；
当前 watermarks。
```

如果在同一个候选构建阶段内，这些输入不变，那么分类结果天然可以缓存。

需要注意：

```text
如果 tryStartNodeCandidate 成功启动节点后，会改变内部资源或 projected 状态，
那么后续节点的分类在严格语义上可能变化。
```

因此建议把缓存边界定义为：

```text
一次 build-all-candidates 阶段内缓存；
启动阶段仍允许 canStartNode 做最终硬校验；
如果需要强一致调度，可在启动成功后只更新资源快照，不强制重分类。
```

这样可以保持原有调度意图，同时避免重复分类。

---

# 4.4 `consumesAvailableInternalInput`

## 语义

`consumesAvailableInternalInput(node)` 的语义是：

```text
判断某个节点是否能消费当前内部 buffer 中已经存在的物品或流体。
```

它用于把节点分入 `INTERNAL_CONSUME` 层。

这个语义很重要，因为工序图的中间产物如果已经在内部 buffer 中堆积，优先启动能消费这些中间产物的下游节点，可以：

```text
减少中间 buffer 积压；
推动工序链向终端输出前进；
降低源头节点无意义继续生产；
让图运行更像流水线而不是只堆原料。
```

## 当前性能问题

该函数会遍历 node 的 input slots，对每个 slot 判断：

```text
是否为流体显示物；
如果是流体，查询 internalFluidAmount；
如果是物品，查询 internalItemAmount；
比较 available >= need。
```

性能成本主要来自：

```text
1. 遍历所有输入槽；
2. 反复调用 GTUtility.getFluidFromDisplayStack；
3. RuntimeResourceSnapshot 内部仍通过 List 线性扫描 buffer；
4. 对 ItemStack/FluidStack 的匹配可能较重。
```

## 优化建议

### 建议 1：预编译输入槽

在图提交或节点变更时，把节点输入编译为：

```java
final class CompiledInputSlot {
    boolean fluid;
    MaterialKey materialKey;
    ItemStack itemTemplate;
    FluidStack fluidTemplate;
    long amountPerRun;
}
```

运行时不再反复解析 `ItemStack` 是否为流体显示物。

### 建议 2：RuntimeResourceSnapshot 建索引

将：

```text
List<BufferedItemStack>
List<BufferedFluidStack>
```

补充为：

```java
Map<MaterialKey, Long> internalAmounts;
Map<MaterialKey, Long> projectedAmounts;
```

这样 `internalItemAmount` / `internalFluidAmount` 可以从线性扫描变成接近 O(1)。

## 为什么这个优化语义不变

该函数的语义只是判断“是否存在足够内部资源供该节点启动”。

预编译输入槽不会改变输入内容，只是把运行时解析提前。

资源索引也不会改变资源数量，只是换一种数据结构保存同样的 amount。

需要保持的不变量：

```text
1. MaterialKey 必须与原 item/fluid 匹配语义一致；
2. 对 OreDict、wildcard meta、NBT 特殊匹配不能被错误简化；
3. 流体显示物解析结果必须与 GTUtility.getFluidFromDisplayStack 一致；
4. buffer 变更后索引必须同步更新或重新构建。
```

---

# 4.5 `suppliesLowWater`

## 语义

`suppliesLowWater(node)` 的语义是：

```text
判断某个生产节点是否应该因为“其内部路由输出低于低水位”而被优先启动。
```

它用于把节点分入 `LOW_WATER_SUPPLY` 层。

通俗来说：

```text
如果某个节点生产的中间产物是下游需要的，
而这个中间产物库存低于低水位，
那么该节点应该优先运行，防止下游断料。
```

它解决的是流水线稳定性问题。

## 当前实现逻辑

其核心逻辑大致是：

```text
遍历 node 的 output slots；
跳过空输出；
如果该输出不是 internal route，跳过；
计算该输出当前 projected amount；
计算该输出本批次产量 batch；
计算该输出对应 lowWater；
如果 projected <= lowWater，则认为该节点供应低水位材料。
```

其中：

```text
projected amount = 当前内部库存 + lookahead 范围内即将完成的产物
lowWater = max(产出吞吐水位, 下游消费者最大需求水位)
```

## 当前性能问题

这个函数是最新 profile 中最大的分类热点。

主要原因是：

```text
suppliesLowWater
  -> getInternalItemLowWater / getInternalFluidLowWater
     -> IntegratedFactoryWatermarks.internalItemLowWater / internalFluidLowWater
        -> 遍历 runtimeEdges
        -> 找 consumer node
        -> 遍历 consumer input slots
        -> itemMatches / fluid match
        -> effectiveParallelLimit
```

也就是说，每次判断某个 producer 是否低水位时，都可能重新遍历边和下游输入槽。

复杂图下复杂度接近：

```text
producerOutputs × runtimeEdges × consumerInputSlots × matchCost
```

这就是为什么 `suppliesLowWater` 会成为新热点。

## 优化建议

把水位依赖从运行时计算改为图编译期预计算。

### 建议数据结构

```java
final class CompiledWatermarkInfo {
    Map<NodeOutputKey, Long> lowWaterByProducerOutput;
    Map<NodeOutputKey, Long> highWaterByProducerOutput;
}
```

其中：

```java
record NodeOutputKey(int producerNodeId, MaterialKey outputMaterial) {}
```

在图提交、自动配平完成、节点并行变化、路由变化时预计算：

```text
producer output material
  -> 所有直接下游 consumer 对该 material 的最大单批需求
  -> outputThroughputPerSecond
  -> outputBatchAmount
  -> lowWater
  -> highWater
```

运行时 `suppliesLowWater()` 只需要：

```java
long projected = snapshot.projectedAmount(outputKey);
long lowWater = compiledWatermarks.lowWater(outputKey);
return projected <= lowWater;
```

## 为什么这个优化语义不变

`lowWater` 的计算依赖的是相对稳定的图结构信息：

```text
runtimeEdges；
producer output；
consumer input；
consumer effectiveParallelLimit；
producer effectiveDurationTicks；
输出 batch amount；
route 类型。
```

这些信息不会在普通 tick 内随机变化。它们通常只会在以下事件发生时变化：

```text
工序图提交；
节点配方改变；
节点并行改变；
节点超频改变；
全局并行倍率改变；
全局额外超频改变；
输出路由改变；
自动配平结果改变；
target output 改变。
```

因此可以在这些事件发生时重建 `CompiledWatermarkInfo`，而不是每 tick 重算。

需要保持的不变量：

```text
1. lowWater 计算公式必须与原 IntegratedFactoryWatermarks 保持一致；
2. 下游 consumer 需求必须包含原本所有 matching input；
3. itemMatches 的匹配语义不能被 MaterialKey 简化破坏；
4. effectiveParallelLimit / effectiveDurationTicks 变化时必须失效；
5. route 变化时必须失效；
6. 流体和物品分开处理，不能混用。
```

如果这些不变量满足，那么预编译水位不会改变功能语义，只会减少重复计算。

---

# 4.6 `producesTargetOutput`

## 语义

`producesTargetOutput(node)` 的语义是：

```text
判断某个节点是否直接产出整张工序图的目标输出。
```

它用于把节点分入 `TARGET_PROGRESS` 层。

调度语义是：

```text
如果节点直接推进最终产品产出，且不属于更高优先级的 INTERNAL_CONSUME 或 LOW_WATER_SUPPLY，则优先于普通源头生产。
```

## 当前性能问题

该函数通常遍历 output slots，并将每个 output 转为 `MaterialKey`，再判断是否属于 `runtimeGraphAnalysis.allTargetOutputs`。

单次不算特别重，但因为它在 `classifyCandidateLayer` 中被大量调用，因此累计成本明显。

## 优化建议

在图分析阶段预计算：

```java
Map<Integer, Boolean> producesTargetOutputByNode;
```

或者放入编译节点结构：

```java
compiledNode.producesTargetOutput
```

## 为什么这个优化语义不变

`producesTargetOutput` 只依赖：

```text
node output slots；
runtimeGraphAnalysis.allTargetOutputs；
MaterialKey 计算规则。
```

这些都在图结构和目标输出不变时保持稳定。

失效条件：

```text
节点输出变化；
目标输出集合变化；
图重新提交；
MaterialKey 规则变化。
```

只要在这些事件后重建缓存，语义不变。

---

# 4.7 `getRunnableParallel`

## 语义

`getRunnableParallel(node, parallelLimit, debugRuntime)` 的语义是：

```text
根据当前可用输入，计算该节点本 tick 最多能以多少并行启动。
```

它不是最终启动，而是计算理论可运行并行数。

对于普通节点：

```text
初始 runnable = parallelLimit；
遍历所有输入槽；
每个输入槽计算 available / perRun；
所有输入槽取最小值作为瓶颈并行；
如果任意输入不足，返回 0。
```

对于 recycler 节点，则使用 recycler 专用输入量和成本计算。

## 当前性能问题

该函数在最新 profile 中仍然有明显占用。

主要成本来自：

```text
1. 遍历输入槽；
2. 每个输入槽调用 availableItemAmount / availableFluidAmount；
3. available* 进一步访问 RuntimeResourceSnapshot；
4. Snapshot 内部对 buffer 仍是线性扫描；
5. 流体显示物反复解析；
6. 物品匹配可能触发 OreDict / itemMatches。
```

## 优化建议

### 建议 1：用编译输入槽替代运行时 slot 扫描解析

```java
for (CompiledInputSlot input : compiledNode.inputs) {
    long available = snapshot.amountFor(input);
    runnable = Math.min(runnable, available / input.amountPerRun);
}
```

### 建议 2：给 snapshot 建 amount 索引

```java
snapshot.amount(MaterialKey key)
```

### 建议 3：tick 内缓存 runnableParallel

在一次候选构建阶段：

```java
analysis.runnableParallel = computeRunnableParallelOnce(node);
```

启动时仍用 `canStartNode` 或 snapshot commit 做最终确认。

## 为什么这个优化语义不变

`getRunnableParallel` 的语义是“基于当前资源上限估算最大可启动并行”。

只要：

```text
1. available 计算结果与原逻辑一致；
2. perRun 数量与 getStackAmount 一致；
3. 所有输入槽都参与瓶颈计算；
4. recycler 节点保留专用逻辑；
5. 启动时仍进行硬校验；
```

那么缓存或索引不会改变功能语义。

---

# 4.8 `isExternalOutputThrottled` / `isInternalOutputThrottled`

## 语义

这两个函数的核心语义是：

```text
根据输出 buffer 的当前存量和高/低水位，决定节点是否应该暂停启动，避免输出堆积。
```

内部输出节流：

```text
用于控制中间产物 buffer，防止某个内部材料生产过多。
```

外部输出节流：

```text
用于控制最终输出或副产物 buffer，防止输出缓存无限增长。
```

高低水位机制通常是滞回控制：

```text
如果 stored >= highWater：
  进入 throttled 状态；

如果 stored <= lowWater：
  解除 throttled 状态；

否则保持当前 throttled 状态。
```

这种设计可以避免节点在临界点频繁开关。

## 当前性能问题

`isExternalOutputThrottled` 本身需要遍历输出槽，计算每个输出的 expected output amount，然后调用 `shouldThrottleExternal*`。

`shouldThrottle*` 里面又会：

```text
判断 route；
构造 key；
count buffer；
计算 lowWater；
计算 highWater；
维护 throttled set。
```

这些计算在复杂图和多输出槽下累计明显。

## 优化建议

### 建议 1：预编译 output slot

```java
CompiledOutputSlot {
    MaterialKey key;
    boolean fluid;
    boolean internalRoute;
    long amountPerParallel;
    int chance;
}
```

### 建议 2：预计算 low/high water

对于图结构稳定部分，将 low/high water 缓存到：

```java
Map<NodeOutputKey, WatermarkPair>
```

### 建议 3：buffer 查询索引化

将：

```text
countItemInBuffer(outputItems, output)
countFluidInBuffer(outputFluids, fluid)
```

改为：

```java
outputAmountByKey.get(key)
```

## 为什么这个优化语义不变

节流函数的业务语义是高低水位状态机。

优化时必须保持：

```text
1. throttled set 的进入条件不变：stored >= highWater；
2. throttled set 的退出条件不变：stored <= lowWater；
3. highWater = highWater(lowWater) 的公式不变；
4. lowWater 计算语义不变；
5. internal route / external route 结果不变；
6. 输出数量 expected amount 计算不变；
7. item/fluid key 不冲突；
8. debug 日志不影响逻辑。
```

只要这些条件满足，把 route、watermark、expected amount、buffer count 预计算或索引化不会改变语义。

---

# 4.9 `RuntimeResourceSnapshot`

## 语义

`RuntimeResourceSnapshot` 的语义是：

```text
在某个运行时刻，为调度器提供一份资源视图。
```

它包含多类资源：

```text
internalItemView / internalFluidView：
  机器内部 buffer 中已有资源。

liveItemView / liveFluidView：
  外部输入总线、输入仓、ME 输入等 live 输入资源。

dualItemView / dualFluidView：
  Dual Input Hatch 中可见的配方输入资源。

incomingItemWithinLookahead / incomingFluidWithinLookahead：
  lookahead 时间窗口内即将由 running jobs 产出的资源。
```

它承担的业务语义是：

```text
调度器判断“能不能启动节点”时，不再直接频繁访问 live inventory，
而是基于快照判断资源可用性。
```

这正是上一轮优化成功的关键。

## 当前性能问题

`RuntimeResourceSnapshot` 现在已经解决了 live inventory 爆炸，但它内部仍然是 list-based 结构：

```text
List<BufferedItemStack>
List<BufferedFluidStack>
```

查询时：

```text
countItemInBuffer
countFluidInBuffer
```

仍然需要线性扫描列表。

复杂图下，如果每个节点多个输入槽、多个输出槽、多次分类、多次并行计算，那么线性扫描会被放大。

## 优化建议

将快照从“列表视图”升级为“列表 + 索引视图”。

保留 list：

```text
用于 debug；
用于 fallback；
用于复杂 OreDict 匹配；
用于保持兼容。
```

新增索引：

```java
Map<MaterialKey, Long> internalAmountByKey;
Map<MaterialKey, Long> liveAmountByKey;
Map<MaterialKey, Long> dualAmountByKey;
Map<MaterialKey, Long> incomingAmountByKey;
```

对于 OreDict 输入，可考虑：

```java
Map<Integer, Long> oreAmountByOreId;
```

或者保守一点，只给精确 item/fluid 建索引，OreDict 输入仍 fallback 到 list 扫描。

## 为什么这个优化语义不变

快照索引化只是改变数据访问方式，不改变资源来源和数量。

必须保持的不变量：

```text
1. captureInternalItems 的结果与原 internalItemView 等价；
2. captureLiveItems 的结果与原 liveItemView 等价；
3. captureDualInputs 的结果与原 dualItemView 等价；
4. captureIncomingWithinLookahead 的结果与原 incoming view 等价；
5. 同类 item/fluid 的合并规则与原 addItemToBuffer/addFluidToBuffer 一致；
6. item key 的相等语义与 GTUtility.areStacksEqual(..., true) 兼容；
7. fluid key 的相等语义与 FluidStack.isFluidEqual 兼容；
8. OreDict/wildcard/NBT 特殊输入不能被错误精确化。
```

如果无法完全保证 OreDict 语义，可以使用混合方案：

```text
精确匹配走 Map；
复杂匹配走原 list fallback。
```

这样风险最低。

---

# 4.10 `IntegratedFactoryWatermarks`

## 语义

`IntegratedFactoryWatermarks` 是水位计算模块。

它负责计算：

```text
internal item lowWater；
internal fluid lowWater；
external lowWater；
highWater；
outputThroughputPerSecond；
outputBatchAmount；
waterlineDuration。
```

核心业务目标是：

```text
让生产节点不会无限制地产生中间产物或输出产物；
同时确保下游消费者有足够库存维持稳定运行。
```

内部低水位的语义大致是：

```text
lowWater = max(
    该 producer 的每秒产出需求水位,
    单批输出量,
    直接下游消费者按最大并行启动所需的最大输入量
)
```

高水位语义是：

```text
highWater = max(lowWater + 1, lowWater * 3)
```

这是一个滞回区间：

```text
低于 lowWater：允许生产；
高于 highWater：暂停生产；
中间区域：保持当前节流状态。
```

## 当前性能问题

`internalItemLowWater()` 和 `internalFluidLowWater()` 每次调用都会：

```text
遍历 runtimeEdges；
筛选 fromNodeId == producer.id；
findRuntimeNode(edge.toNodeId)；
遍历 consumer input slots；
判断输入是否匹配 producer output；
计算 consumer effectiveParallelLimit；
更新 lowWater。
```

这在运行时反复执行，复杂图下非常昂贵。

## 优化建议

将水位计算分成两部分：

### 稳定部分：图编译期计算

```text
producer -> consumer 依赖关系；
producer output material；
consumer input material；
consumer max demand；
route 类型；
target 输出关系。
```

### 动态部分：运行时查询

```text
当前 stored/projected amount；
throttled set 状态。
```

也就是说：

```java
CompiledWatermarkInfo compiled = compileWatermarks(runtimeGraph, routes, effectiveNodeStats);

long lowWater = compiled.lowWater(producerId, materialKey);
long highWater = compiled.highWater(producerId, materialKey);
long stored = snapshot.projectedAmount(materialKey);
```

## 为什么这个优化语义不变

水位公式本身不变，只是把稳定输入提前计算。

需要在以下情况下失效重建：

```text
1. 工序图重新提交；
2. 节点输入/输出变化；
3. 节点并行变化；
4. 节点耗时/超频变化；
5. 全局并行倍率变化；
6. 全局额外超频变化；
7. 输出 route 变化；
8. target output 集合变化；
9. 自动配平结果变化。
```

只要这些事件触发重建，运行时读取预编译水位与原本每次计算语义一致。

---

# 4.11 `tryStartNodeCandidate`

## 语义

`tryStartNodeCandidate(candidate, debugRuntime)` 是从“候选节点”进入“真实启动节点”的关键函数。

它的语义包括：

```text
1. 获取节点有效耗时；
2. 获取节点有效 EU/t；
3. 进入 recipe processing 状态；
4. 检查节点是否仍可启动；
5. 构造 RunningJob；
6. 检查有线/无线能源约束；
7. 预留能源；
8. 消耗节点输入；
9. 加入 runningJobs；
10. 重建 runtimeResourceSnapshot；
11. 失败时回滚能源；
12. 退出 recipe processing 状态。
```

它是调度器的最终安全闸门。

## 当前性能问题

最新 profile 中仍然能看到 `tryStartNodeCandidate` 下方有一定 ME 相关信息槽刷新和 `startRecipeProcessing` 成本。

当前实现中，如果每个 candidate 都单独：

```text
startRecipeProcessing
...
endRecipeProcessing
```

那么复杂图下尝试候选越多，ME/GT hatch 生命周期成本越高。

## 优化建议

### 建议 1：扩大 recipe-processing scope

将 recipe processing scope 从“每个候选一次”改为“每个调度 tick 一次”：

```java
startRecipeProcessing();
try {
    runtimeResourceSnapshot = buildRuntimeResourceSnapshotWithoutStartEnd();
    scheduleRunnableNodes(debugRuntime);
} finally {
    endRecipeProcessing();
}
```

### 建议 2：避免 snapshot 构建和 candidate 启动嵌套调用 start/end

将：

```text
buildRuntimeResourceSnapshot()
tryStartNodeCandidate()
```

内部的 `startRecipeProcessing/endRecipeProcessing` 去重，确保不会重复触发 ME 信息槽刷新。

## 为什么这个优化语义不变

`startRecipeProcessing/endRecipeProcessing` 的语义是给 GT/ME 输入舱室提供一个“当前正在处理配方”的生命周期上下文。

如果原本每个 candidate 都是：

```text
start -> check/consume -> end
```

改为每 tick：

```text
start -> 多个 check/consume -> end
```

语义是否等价，取决于 GT/ME hatch 对 start/end 的副作用。

需要验证：

```text
1. startRecipeProcessing 是否只是设置状态和刷新信息槽；
2. endRecipeProcessing 是否会提交或清理某些一次性状态；
3. 多个候选在同一个 start/end scope 内消费输入是否会互相污染；
4. ME Input Bus 是否依赖每次 recipe 独立 start/end；
5. Programmable Hatches 是否依赖每次独立生命周期。
```

如果 start/end 只是刷新和状态包裹，那么扩大 scope 是安全的。  
如果某些 hatch 把 start/end 当成单次配方事务边界，则不能直接合并，需要更细粒度处理。

因此这条建议收益可能很高，但必须做回归测试。

---

# 4.12 `consumeNodeInputs`

## 语义

`consumeNodeInputs(node, job, parallel)` 是实际扣除输入并记录到 job 的函数。

它的核心语义是：

```text
1. 启动前再次确认 canStartNode；
2. 对每个输入槽计算 need；
3. 从内部 buffer 或可用输入中消费对应物品/流体；
4. 将已消费内容暂存到 stagedItems/stagedFluids；
5. 如果中途失败，回滚 staged inputs；
6. 成功后，把 staged inputs 记录到 job.consumedItems / consumedFluids。
```

这个函数维护了非常重要的不变量：

```text
节点启动要么完整成功，要么完整失败；
不能只消费一部分输入后失败；
失败时必须回滚；
成功后 job 必须记录消耗内容，以便断电/回滚/状态保存等逻辑使用。
```

## 当前性能问题

`consumeNodeInputs` 在这次 profile 中已经不是最大热点，说明上一轮优化有效。

但仍有一些可优化点：

```text
tryStartNodeCandidate 先 canStartNode；
consumeNodeInputs 开头又 canStartNode；
实际消费时还会再查询资源；
成功后重建 runtimeResourceSnapshot。
```

## 优化建议

短期建议保留 `consumeNodeInputs` 的硬校验，避免语义风险。

中长期可以引入 `ConsumptionPlan`：

```java
final class ConsumptionPlan {
    List<ItemRequest> itemRequests;
    List<FluidRequest> fluidRequests;
    List<ItemStack> stagedItems;
    List<FluidStack> stagedFluids;
}
```

调度阶段生成计划：

```text
candidate -> plan
```

提交阶段执行计划：

```text
commit(plan)
```

成功后更新 snapshot，而不是整份重建。

## 为什么这个优化语义不变

`ConsumptionPlan` 必须保持事务语义：

```text
1. 所有输入都满足才提交；
2. 任意输入不足则不提交；
3. 中途失败必须回滚；
4. 成功后 consumedItems / consumedFluids 与原逻辑一致；
5. 消耗来源优先级与原逻辑一致；
6. cyclic reserve 规则与原逻辑一致。
```

如果不能保证事务语义，宁可暂时保留当前实现。

---

# 4.13 `countRunningJobsForNode`

## 语义

该函数判断某个节点当前是否已有运行中的 job。

在调度器中，它用于避免同一节点重复启动：

```text
如果 runningJobsForNode(node.id) > 0，则跳过该节点。
```

## 当前性能问题

当前实现是线性扫描：

```java
int count = 0;
for (RunningJob job : runningJobs) {
    if (job.nodeId == nodeId) {
        count++;
    }
}
```

如果每个 tick 对每个 node 都调用一次，则复杂度是：

```text
nodeCount × runningJobCount
```

当前它不是最大热点，但属于低风险可优化点。

## 优化建议

维护增量计数：

```java
Int2IntMap runningJobCountByNode;
```

启动 job：

```java
runningJobCountByNode.addTo(node.id, 1);
```

结束 job：

```java
runningJobCountByNode.addTo(node.id, -1);
```

或每 tick 开头构建一次：

```java
runningJobCountByNode.clear();
for (RunningJob job : runningJobs) {
    runningJobCountByNode.addTo(job.nodeId, 1);
}
```

## 为什么这个优化语义不变

该函数只是查询运行中 job 数量。  
只要 map 与 `runningJobs` 列表保持一致，结果完全等价。

需要注意：

```text
1. job 启动失败不能增加计数；
2. job 完成后必须减少计数；
3. job 被异常清理或断电停止时也必须同步减少；
4. 读档恢复 runningJobs 时需要重建 map。
```

---

## 5. 推荐优化路线

### P0：一次全图扫描 + CandidateLayer 分桶

优先级最高。

目标：

```text
把 layerCount 次全图扫描降为 1 次全图扫描。
```

收益对应 profile 中的：

```text
buildNodeCandidates
classifyCandidateLayer
suppliesLowWater
consumesAvailableInternalInput
producesTargetOutput
```

语义保持关键点：

```text
CandidateLayer 优先级不变；
每层内部排序不变；
tryStart 顺序不变；
maxNodeStartsPerTick 不变；
runCredit 扣除语义不变。
```

---

### P1：TickNodeAnalysis 缓存

目标：

```text
同一 tick、同一节点的 effectiveParallelLimit、effectiveDurationTicks、candidateLayer、runnableParallel、distanceToTerminal 等只计算一次。
```

语义保持关键点：

```text
缓存只在一个调度 tick 内有效；
启动时仍有 canStartNode 硬校验；
资源变化后不能长期复用旧 runnableParallel。
```

---

### P1：水位依赖预编译

目标：

```text
把 IntegratedFactoryWatermarks 中运行时扫边、扫消费者输入槽的逻辑提前到图编译期。
```

收益对应：

```text
suppliesLowWater
getInternalItemLowWater
getInternalFluidLowWater
```

语义保持关键点：

```text
lowWater/highWater 公式不变；
item/fluid 匹配语义不变；
图、路由、并行、超频、目标输出变化时失效重建。
```

---

### P2：RuntimeResourceSnapshot 索引化

目标：

```text
把频繁的 countItemInBuffer/countFluidInBuffer 线性扫描改为 MaterialKey -> amount 查询。
```

收益对应：

```text
consumesAvailableInternalInput
getRunnableParallel
canStartNode
isExternalOutputThrottled
suppliesLowWater projected amount 查询
```

语义保持关键点：

```text
精确匹配和复杂匹配分层；
OreDict/wildcard/NBT 不要被错误简化；
buffer 变更后索引同步。
```

---

### P2：CompiledNode / CompiledSlot

目标：

```text
把节点输入输出槽中的流体显示物解析、MaterialKey 生成、stack amount、输出 chance、route 类型提前计算。
```

收益对应：

```text
GTUtility.getFluidFromDisplayStack
materialKeyOf
getStackAmount
isInternalRoute
getExpectedOutputAmount
```

语义保持关键点：

```text
节点输入输出变化后重建；
全局并行/超频变化时重新计算动态字段；
route 变化后重建 route 字段。
```

---

### P2：减少 startRecipeProcessing/endRecipeProcessing 调用

目标：

```text
尽量从每 candidate 一次降低到每调度 tick 一次。
```

收益对应：

```text
ME Input Bus 信息槽刷新；
SuperInputBusME.updateAllInformationSlots；
SuperInputHatchME.updateAllInformationSlots；
NetworkMonitor.extractItems。
```

语义保持关键点：

```text
必须确认 GT/ME hatch 不依赖每个候选独立 recipe-processing 生命周期；
如果依赖，则不能直接合并。
```

---

### P3：runningJobsByNode 计数缓存

目标：

```text
把 runningJobsForNode 从线性扫描改为 O(1) 查询。
```

语义保持关键点：

```text
runningJobs 列表与计数 map 始终一致。
```

---

## 6. 建议的验证方式

### 6.1 单项优化前后对比

每次只改一个大方向，例如：

```text
1. CandidateLayer 分桶；
2. TickNodeAnalysis；
3. Watermark 预编译；
4. Snapshot 索引化；
5. start/end scope 合并。
```

每次改完跑同样工序图、同样负载、同样 spark 命令。

建议观察：

```text
MTESuperIntegratedFactory.onPostTick
scheduleRunnableNodes
IntegratedFactoryScheduler.schedule
buildNodeCandidates
classifyCandidateLayer
suppliesLowWater
getRunnableParallel
isExternalOutputThrottled
RuntimeResourceSnapshot.count*
IntegratedFactoryWatermarks.*
MSPT p95 / p99 / max
```

### 6.2 功能回归测试重点

性能优化必须保证语义不变，建议重点测：

```text
1. 普通线性工序图；
2. 多输入、多输出节点；
3. 有循环中间产物的图；
4. 有 internal route / external route 混合的图；
5. 有 target output 和 byproduct output 的图；
6. 有低水位补料需求的图；
7. 有高水位节流的图；
8. 节点并行变化；
9. 全局并行倍率变化；
10. 节点超频和全局额外超频变化；
11. 断电停止；
12. runningJobs 保存/读取；
13. ME 输入总线；
14. Dual Input Hatch；
15. Recycler 节点；
16. OreDict 输入；
17. NBT 物品；
18. 流体显示物输入输出。
```

### 6.3 建议增加 debug counter

建议给调度器增加轻量统计：

```text
nodesScanned
candidatesBuilt
candidateLayerCalls
suppliesLowWaterCalls
watermarkCacheHits
watermarkCacheMisses
snapshotItemQueries
snapshotFluidQueries
snapshotFallbackScans
runnableParallelCalls
canStartNodeCalls
consumeNodeInputsCalls
startRecipeProcessingCalls
endRecipeProcessingCalls
```

优化目标示例：

```text
CandidateLayer 分桶后：
  nodesScanned 应接近 nodeCount，而不是 layerCount × nodeCount。

Watermark 预编译后：
  runtimeEdges 扫描次数应接近 0。

Snapshot 索引化后：
  fallback list scan 次数应显著下降。

start/end scope 合并后：
  startRecipeProcessingCalls 应接近每 tick 1 次，而不是每 candidate 1 次。
```

---

## 7. 最终建议摘要

当前最值得优先做的是：

```text
1. IntegratedFactoryScheduler 改为一次全图扫描 + CandidateLayer 分桶；
2. 引入 TickNodeAnalysis，避免同 tick 重复计算节点分类、并行、节流；
3. 预编译 Watermark 依赖，消除 suppliesLowWater 中的运行时扫边；
4. RuntimeResourceSnapshot 建 MaterialKey -> amount 索引；
5. 预编译节点输入输出槽，减少 fluid display / MaterialKey / stackAmount 反复解析；
6. 谨慎合并 startRecipeProcessing/endRecipeProcessing scope；
7. runningJobsForNode 改为计数缓存。
```

其中，最优先的是前两项：

```text
一次全图扫描 + 分桶
TickNodeAnalysis 缓存
```

它们最直接对应当前 profile 中的最大热点，同时对业务语义影响最小。

从语义保持角度看，所有优化都应遵循一个原则：

```text
可以缓存“同一调度阶段内不变的事实”；
可以预编译“图结构决定的事实”；
可以索引“数量不变但查询方式改变的数据”；
但不能省略启动前硬校验、事务性输入扣除、低/高水位状态机、route 判定和 cycle reserve 语义。
```

如果严格遵守这些边界，当前性能问题可以继续压低，同时不破坏工序图调度的正确性和稳定性。

---

## 8. 附：建议改造后的高层结构草案

### 8.1 调度入口

```java
public static int schedule(Context context, boolean debugRuntime) {
    context.updateRunCredits();

    TickScheduleAnalysis analysis = context.buildTickScheduleAnalysis(debugRuntime);

    int starts = 0;
    for (CandidateLayer layer : CandidateLayer.values()) {
        List<NodeCandidate> candidates = analysis.candidates(layer);
        candidates.sort(CANDIDATE_ORDER);

        for (NodeCandidate candidate : candidates) {
            if (starts >= context.maxNodeStartsPerTick()) {
                return starts;
            }

            if (context.tryStartNodeCandidate(candidate, debugRuntime)) {
                starts++;
                context.subtractRunCredit(candidate.node.id, 1.0D);
                analysis.onCandidateStarted(candidate);
            }
        }
    }
    return starts;
}
```

### 8.2 Tick 分析

```java
final class TickScheduleAnalysis {
    EnumMap<CandidateLayer, ArrayList<NodeCandidate>> candidatesByLayer;
    Int2ObjectMap<TickNodeAnalysis> nodeAnalysisById;

    List<NodeCandidate> candidates(CandidateLayer layer) {
        return candidatesByLayer.getOrDefault(layer, EMPTY);
    }

    void onCandidateStarted(NodeCandidate candidate) {
        // 可选：更新本 tick 内的资源预测或标记后续必须硬校验
    }
}
```

### 8.3 编译节点结构

```java
final class CompiledProcessNode {
    int nodeId;
    List<CompiledInputSlot> inputs;
    List<CompiledOutputSlot> outputs;
    boolean producesTargetOutput;
    int distanceToTerminal;
}
```

### 8.4 编译槽位结构

```java
final class CompiledInputSlot {
    boolean fluid;
    MaterialKey key;
    ItemStack itemTemplate;
    FluidStack fluidTemplate;
    long amountPerRun;
    boolean requiresOreDictFallback;
}
```

### 8.5 水位缓存

```java
final class CompiledWatermarkInfo {
    Long2LongMap lowWaterByNodeOutput;
    Long2LongMap highWaterByNodeOutput;
}
```

### 8.6 快照索引

```java
final class RuntimeResourceSnapshot {
    Map<MaterialKey, Long> internalAmountByKey;
    Map<MaterialKey, Long> liveAmountByKey;
    Map<MaterialKey, Long> dualAmountByKey;
    Map<MaterialKey, Long> incomingAmountByKey;

    long amountAvailableFor(ProcessNode consumer, CompiledInputSlot input) {
        // 精确匹配走索引；
        // OreDict / wildcard / NBT 特殊情况走 fallback。
    }
}
```

---

## 9. 结语

最新 profile 的状态可以概括为：

```text
第一阶段问题：已解决。
第二阶段问题：调度算法在复杂图下仍有优化空间。
```

当前已经不是“功能逻辑明显错误导致的灾难性性能问题”，而是“复杂图调度器需要工程化缓存、预编译和索引化”的问题。

下一轮优化的重点不应再放在外部输入扣取，而应放在：

```text
调度阶段去重；
图结构预编译；
资源视图索引化；
水位计算缓存；
启动前硬校验保留；
事务性扣除语义保留。
```

这样既能继续降低复杂图下的 300ms 级尖峰，也能最大限度保持现有功能行为不变。
