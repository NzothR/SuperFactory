# GTNH 工序机器设计文档 v2（修订合并版）

## 1. 文档目的

本文档用于统一描述 GTNH 集成工厂 / 工序机器的工序图模型、机器运行模型、状态机、调度策略、环路规则、输出语义和可选配平工具。

v2 修订版的核心目标是：

```text
1. 将“结束节点”语义调整为“目标产物节点”。
2. 目标产物节点不再等同于拓扑终点。
3. 工序图支持多源头、多目标、多副产链和多个局部单物料自增环。
4. 图语义、配平语义和机器运行状态严格分离。
5. 运行器采用消费者优先的分层候选队列调度，而不是纯上游优先调度。
6. 使用 runCredit 平滑启动频率，减少复杂图的波形运行。
7. 普通中间物、循环物料和输出缓存都使用水位控制。
8. INPUT 只收集运行资格物与循环启动物料，不收集普通原料。
9. 停机行为区分“运行暂停”和“工序卸载”。
10. 提交新图可继承主机与 NC，但不继承内部物料状态。
11. OUTPUT 期间再次提交新图只暂缓最新提交，不立即参与当前 OUTPUT。
12. 默认清除内部中间物，Debug 模式下才导出中间物。
13. 配平算法作为可选推荐工具，不强制参与运行。
```

本文档是单一维护文档，后续工序图模型、机器运行模型和配平推荐工具都以本文档为准。

---

## 2. 核心定位

工序机器是一台：

```text
基于工序图的虚拟复合加工机
```

它用于把一组 GTNH 配方节点组织成一个可运行的生产网络，并由机器自身模拟这些节点的输入消耗、耗时、耗能、输出产出和物料流转。

机器的核心职责是：

```text
接收并校验工序图
统计并收集运行资格物
维护运行期资源快照
按图结构调度虚拟节点
模拟耗时、耗能、输入消耗和输出产出
维护内部缓存、循环水位和外部输出缓存
处理换图、输出、跳电和运行暂停
提供可选配平工具以推荐节点基础并行上限
```

主机与 NC 只作为运行资格门槛，不参与每次节点配方执行。

机器运行节点时，不真实启动每个节点对应的 GT 多方块主机，而是通过内部虚拟运行任务模拟执行。

---

## 3. 核心术语

| 术语 | 含义 |
|---|---|
| 工序图 ProcessGraph | 由节点和物料边组成的局部生产网络 |
| 节点 ProcessNode | 一个虚拟配方运行单元 |
| 目标产物节点 TargetProductNode | 标记该节点部分或全部产物为目标产物的节点 |
| 物料边 MaterialEdge | 源节点某产物被目标节点作为输入消耗的显式物料流 |
| 源头节点 SourceNode | 无入边节点，通常消耗外部输入 |
| 拓扑终点 SinkNode | 无出边节点，不一定是目标产物节点 |
| NC | Non-Consumable，不消耗运行资格物 |
| 主机 Controller | 节点所需的多方块主机方块，作为运行资格 |
| 启动物料 StartupMaterial | 循环工序启动时进入内部缓存的实际物料 |
| 本地输入 LocalInputBuffer | 机器自身输入总线 / 输入仓中的真实库存 |
| ME 输入 MEInputProvider | ME 输入舱室或存储输入总线暴露的网络库存 |
| 双输入 DualInputProvider | 双输入舱室、样板舱室等特殊输入来源 |
| 内部缓存 InternalBuffer | 机器内部保存中间物、循环物料、水位物料的缓存 |
| 输出缓存 OutputBuffer | 目标产物、副产物和可导出结果缓存 |
| 运行资格缓存 QualificationStorage | 已收集的主机与 NC |
| 基础并行上限 parallelLimit | 节点保存的基础并行上限 |
| 有效并行上限 effectiveParallelLimit | 机器参数修正后的运行时并行上限 |
| 实际并行 actualParallel | 某次 job 实际启动并行 |
| 局部环 SCC | 强连通分量形式的环结构 |
| 循环物料 CycleMaterial | 环内唯一参与回流的物料 |
| 环净输出 CycleNetOutput | 循环物料生产速率大于消耗速率的净产出 |
| runCredit | 节点运行额度，用于平滑启动频率 |
| projectedSoon | 短期预测缓存，用于水位判断 |
| 运行暂停 RuntimePause | 跳电、关电源等只暂停运行、不卸载工序 |
| 工序卸载 ProcessUnload | 提交新图或螺丝刀输出导致进入 OUTPUT 的行为 |

---

## 4. 总体架构

机器整体由以下部分组成：

```text
ProcessGraph
    节点、边、目标产物标记、环分析、配平结果

MachineRuntime
    状态机、当前图、待提交图、暂缓提交图、运行缓存、运行任务

QualificationStorage
    主机缓存、NC 缓存

ResourceSystem
    本地输入、ME 输入、双输入、内部缓存、输出缓存、循环水位、普通水位

Scheduler
    消费者优先的分层候选队列调度器

VirtualJobRunner
    虚拟节点任务启动、推进、完成、失败

BalanceTool
    可选基础并行推荐器
```

---

## 5. 工序图模型

### 5.1 图定义

工序图表示一个局部生产网络：

```text
ProcessGraph = (V, E, M)
```

其中：

```text
V = 节点集合
E = 物料流有向边集合
M = 图级元信息
```

图级元信息包括：

```text
目标产物节点集合
启动条件需求
节点参数
物料路由推断结果
环路分析结果
配平结果
```

工序图可以包含：

```text
多个源头节点
多个目标产物节点
多个拓扑终点
多个副产处理链
多个局部单物料自增环
回收节点等特殊虚拟节点
```

工序图中的边只表示物料流关系，不表示 UI 排列顺序，也不直接表示运行调度顺序。

---

### 5.2 节点模型

每个节点表示一个虚拟配方运行单元：

```text
ProcessNode {
    id
    type
    recipeIdentity

    inputs
    outputs

    baseDuration
    baseEuPerTick
    overclockCount
    parallelLimit

    endNode              // V2 语义: 目标产物节点标记, NBT 兼容名
    cycleMaterial        // 手动标记的循环物料（可选，存放于 node.cycleMaterialHandler[0]）

    nonConsumableItems
    requiredController
    
    // 以下字段为设计预留，当前源码使用 endNode + 自动推断实现
    // targetProductNode
    // explicitTargetOutputs
}
```

字段说明：

```text
id = 节点唯一 ID
type = 普通配方、回收节点、虚拟节点等类型
recipeIdentity = 配方身份或配方快照
inputs = 节点输入物料
outputs = 节点输出物料
baseDuration = 节点基础耗时
baseEuPerTick = 节点基础 EU/t
overclockCount = 节点自身设置的超频次数
parallelLimit = 节点基础并行上限
targetProductNode = 是否为目标产物节点
explicitTargetOutputs = 显式目标产物输出集合
nonConsumableItems = 节点所需 NC
requiredController = 节点所需主机
```

节点自身参数只描述图语义。机器运行时可以叠加机器级并行倍率和全局额外超频，但这些运行参数不写回节点。

---

## 6. 目标产物节点

### 6.1 目标产物节点定义

旧模型中的“结束节点”在 v2 中改名为：

```text
目标产物节点
```

目标产物节点不是拓扑终点。它表示：

```text
该节点的部分或全部产物是本工序图的目标产物。
```

目标产物节点后续仍然可以连接其他节点，用于继续处理：

```text
副产物
废液
回收物
副链原料
循环回流物料
```

---

### 6.2 默认目标产物推断

为了不改变当前 UI，目标产物仍可由节点级标记推断。

默认推断规则：

```text
如果目标产物节点没有下游节点：
    该节点所有产物均视为目标产物。

如果目标产物节点存在下游节点：
    该节点输出中，不被任一下游节点消费的产物视为目标产物。
    被下游节点消费的产物视为中间物或副产处理输入。
```

示例：

```text
目标产物节点 A 输出：
    极不稳定硅岩粉
    酸性硅岩溶液

A -> B，B 消耗酸性硅岩溶液

则：
    极不稳定硅岩粉 = 目标产物
    酸性硅岩溶液 = 后续副产处理链输入
```

---

### 6.3 显式目标产物覆盖

默认推断规则不适合所有情况，尤其是循环目标物料。

因此节点可以提供：

```text
explicitTargetOutputs
```

规则：

```text
如果玩家显式标记某个输出为目标产物，则该物料在图语义上始终是目标产物。
即使它被下游消费，也不会改变它的目标产物身份。
```

如果该物料同时属于有效环的循环物料，则：

```text
该物料仍然静态地属于目标产物；
运行时只有超过 reserve 的可外排部分会进入目标输出缓存。
```

换言之，目标产物身份是图语义；实际输出数量由运行期水位和环净输出决定。

---

### 6.4 目标产物节点校验

提交图时应检查：

```text
至少存在一个目标产物节点。
每个目标产物节点至少能推断或显式得到一种目标产物。
如果目标产物节点所有输出都被下游消费，且没有 explicitTargetOutputs，应提示或拒绝。
```

---

## 7. 物料与边模型

### 7.1 物料模型

物料统一抽象为：

```text
MaterialAmount {
    materialKey
    type
    amount
}
```

其中：

```text
type = ITEM 或 FLUID
amount = 物品数量或流体 L 数
materialKey = 稳定物料标识
```

---

### 7.2 边模型

边表示直接物料流：

```text
MaterialEdge {
    fromNodeId
    toNodeId
    materialKey
    type
}
```

含义：

```text
fromNode 的某种产物被 toNode 作为输入消耗。
```

边必须满足直接相邻规则：

```text
一条边只连接直接生产者和直接消费者。
不表达“全图中某物料最终会被某节点使用”。
不表达运行优先级。
```

同一种物料可以从一个节点分配给多个下游节点。

---

### 7.3 物料分类

根据边、目标产物推断和环分析，节点产物可分为：

```text
目标产物
中间产物
副产物
循环物料
环净输出物料
```

#### 目标产物

由目标产物节点推断或显式标记得到，是本图希望获得的主要产物。

#### 中间产物

被后续节点通过边消费的物料。

#### 副产物

不属于目标产物，也没有被后续节点消费的物料。副产物默认外排。

#### 循环物料

在同一个环结构中同时被生产和消费，并参与形成闭环依赖的唯一物料。

#### 环净输出物料

循环物料在当前节点参数下，其生产速率大于消耗速率时，差值为净输出。

---

## 8. 环模型

### 8.1 环识别

使用强连通分量 SCC 识别环。

满足以下任一条件的 SCC 视为环：

```text
SCC 中节点数量 > 1
单节点存在自环
```

---

### 8.2 候选循环物料识别

在一个 SCC 中，若某物料 `m` 存在至少一条边：

```text
u -> v
```

且：

```text
u 属于 SCC
v 属于 SCC
边物料为 m
```

则 `m` 是 SCC 内部流转物料。

若 `m` 同时在 SCC 内被生产和消费，并参与 SCC 闭合依赖，则 `m` 是候选循环物料。

一个有效 SCC 中：

```text
候选循环物料数量必须等于 1
```

---

### 8.3 单循环物料约束

每个环只允许一种循环物料。

如果一个环中存在多种物料同时参与回流，则图检查失败。

理由：

```text
多物料循环需要多变量配平
GTNH 配方常带副产、概率和特殊输入，实际难以稳定配平
多物料环会显著增加调度与水位控制复杂度
```

---

### 8.4 环正净输出约束

环必须是自增环。

对于某环的唯一循环物料 `C`，使用当前节点基础参数计算速率：

```text
cycleProducedRate(C)
    = Σ outputAmount(i, C) × parallelLimit_i / duration_i

cycleConsumedRate(C)
    = Σ inputAmount(i, C) × parallelLimit_i / duration_i

cycleNetRate(C)
    = cycleProducedRate(C) - cycleConsumedRate(C)
```

要求：

```text
cycleNetRate(C) > 0
```

其中：

```text
duration_i = 节点当前基础 OC 后耗时
parallelLimit_i = 节点当前基础并行上限
```

机器级全局并行倍率 `machine.parallelMultiplier` 默认不参与环合法性检查，因为它等比例放大所有普通节点速率，不改变 `cycleNetRate` 的正负。

如果环中包含不受全局倍率影响，或倍率影响方式特殊的节点，例如回收节点，则该节点应使用自己的基础速率模型参与检查。

如果当前 P / 耗时下 `cycleNetRate(C) <= 0`，则该环无正净输出，应拒绝提交或提示玩家修改节点参数。

可选配平工具可以帮助玩家推荐 P，但不是环合法性检查的必要步骤。

---

### 8.5 环启动条件

环必须能够启动。

允许的启动来源包括：

```text
启动物料提供循环物料种子
外部输入提供循环物料种子
环内存在不依赖循环物料即可运行的节点
```

如果环完全依赖自身循环物料且没有种子来源，则图检查失败或要求补充启动物料。

---

### 8.6 环出口规则

循环物料净输出按以下规则推断：

```text
如果循环物料被环外下游节点消费：
    净输出优先作为环外下游输入。

如果循环物料是目标产物：
    无下游消费的净输出作为目标产物外排。

如果循环物料不是目标产物且没有环外下游消费：
    净输出作为副产物外排。
```

其中“循环物料是目标产物”的判定方式：

```text
环中存在目标产物节点，
并且目标产物推断结果或 explicitTargetOutputs 包含该循环物料。
```

---

### 8.7 环内与环外消费者差异

循环物料的可用量需要按消费者位置区分。

对于同一 SCC 内的环内消费者：

```text
availableForInnerConsumer(C) = internalBuffer[C]
```

对于 SCC 外的环外消费者或外部输出：

```text
availableForOuterConsumer(C) = max(0, internalBuffer[C] - reserve[C])
```

即：

```text
环内节点可以使用 reserve 内的循环物料维持运行。
环外节点与外部输出只能使用超过 reserve 的净输出部分。
```

---

### 8.8 SCC 压缩的含义

SCC 压缩只用于：

```text
环分析
配平分析
调度排序
水位控制
```

它不改变运行时节点粒度。

运行器仍按 SCC 内部原节点逐个创建虚拟 job，不会把整个 SCC 合并成一个超级 job。

---

## 9. 图合法性检查

提交工序图时应执行：

```text
1. 所有节点配方有效。
2. 所有节点 ID 唯一。
3. 所有边连接的源节点和目标节点存在。
4. 所有边连接物料匹配。
5. 所有目标产物节点能推断或显式得到目标产物。
6. 普通内部物料有直接上游来源。
7. 所有 SCC 环满足单循环物料规则。
8. 所有循环物料在当前 P/耗时下具备正净输出。
9. 所有环具备启动条件。
10. 回收节点输入必须来自直接上游。
11. 主机和 NC 需求可统计。
12. 矿典输入在运行时可记录真实消耗物。
```

图检查不处理：

```text
ME 系统是否有足够普通原料
输入舱室是否有普通原料
输出舱室是否有空间
机器运行期能量是否充足
旧图内部缓存中是否存在物料
```

这些属于机器运行模型。

---

## 10. 主机、NC 与启动条件

### 10.1 主机

主机方块代表节点运行资格。

规则：

```text
每个节点独占一台可运行其配方类型的主机。
主机不在运行期真实执行配方。
主机只作为运行资格缓存。
```

如果三个节点都需要大型研磨塔主机，则需要：

```text
大型研磨塔主机 × 3
```

---

### 10.2 NC 物品

NC 是 Non-Consumable，即不消耗运行资格物。

典型 NC：

```text
编程电路
模具
透镜
催化剂
特殊工具
配置物品
```

NC 不参与普通输入消耗，也不进入内部物料缓存。

---

### 10.3 循环启动物料

循环启动物料是真实运行物料。

规则：

```text
只在循环工序需要时收集。
输入完成后进入 internalBuffer。
参与后续节点消耗、转化、回流和水位控制。
停机或卸载工序时默认清除，不返还、不继承。
```

---

## 11. 机器状态

机器状态维持四种：

```text
STANDBY
INPUT
RUNNING
OUTPUT
```

含义：

```text
STANDBY = 无已安装工序或等待操作
INPUT = 收集当前工序所需主机、NC 和启动物料
RUNNING = 按工序图执行虚拟节点
OUTPUT = 卸载工序并处理运行资格物和缓存
```

`DRAINING` 排空模式暂不作为当前实现要求。

---

## 12. 状态行为

### 12.1 STANDBY

STANDBY 是空闲状态。

行为：

```text
不执行节点
不推进 job
不消耗物料
不维护水位
等待玩家提交工序或操作机器
```

---

### 12.2 INPUT

INPUT 用于收集当前工序的启动资格与启动条件。

INPUT 只负责收集：

```text
主机方块
NC 物品
循环启动物料
```

收集完成后：

```text
主机 -> controllerStorage
NC -> ncStorage
启动物料 -> internalBuffer
```

INPUT 不检查、不收集普通节点原材料。

普通节点原材料由 RUNNING 阶段在节点启动时动态从输入来源扣取，这与真实 GT 多方块在配方检查时动态读取输入的行为一致。

如果普通原料不足，机器可以保持 RUNNING，但不会启动相关节点。

---

### 12.3 RUNNING

RUNNING 执行工序图。

行为：

```text
维护资源快照
推进 running job
完成 job 输出路由
计算可启动节点
使用消费者优先分层候选队列调度
启动虚拟节点 job
维护普通水位、循环水位和输出水位
```

---

### 12.4 OUTPUT

OUTPUT 表示工序卸载。

行为：

```text
不再启动新节点
立即终止 running job
已被 job 消耗但未完成的输入损失
默认清除内部中间物与循环物料
按提交或螺丝刀语义处理主机和 NC
Debug 模式下可额外输出内部中间物
完成后进入 STANDBY 或处理暂缓提交的新图
```

---

## 13. 状态转换规则

### 13.1 总览

| 当前状态 | 事件 | 行为 | 下个状态 |
|---|---|---|---|
| STANDBY | 提交新图 | 执行完整提交流程，构建 SubmitPlan | INPUT / RUNNING / OUTPUT |
| INPUT | 提交新图 | 当前图进入卸载流程，新图进入待提交流程 | OUTPUT |
| RUNNING | 提交新图 | 当前图进入卸载流程，新图作为 pendingGraph | OUTPUT |
| OUTPUT | 再次提交新图 | 写入 deferredGraph，覆盖旧 deferredGraph | OUTPUT |
| RUNNING | 跳电 / 关电源 / 能量不足 | 运行暂停，中断 runningJobs，保留缓存和图 | RUNNING |
| RUNNING | 螺丝刀输出 | 工序卸载，不继承主机/NC | OUTPUT |
| INPUT | 螺丝刀输出 | 工序卸载，不继承主机/NC | OUTPUT |
| OUTPUT | 输出完成且 deferredGraph 存在 | 对 deferredGraph 执行完整提交流程 | INPUT / RUNNING / OUTPUT |
| OUTPUT | 输出完成且无 deferredGraph / pendingGraph | 清除 currentGraph | STANDBY |

---

### 13.2 OUTPUT 期间提交新图

OUTPUT 期间提交新图不会改变当前 OUTPUT 行为。

规则：

```text
OUTPUT 中提交新图：
    deferredGraph = newGraph
```

如果 OUTPUT 中连续提交多个新图：

```text
始终只保留最新的 deferredGraph。
新的 deferredGraph 覆盖旧 deferredGraph。
```

当前 OUTPUT 完成后：

```text
if deferredGraph != null:
    对 deferredGraph 执行一次完整 submitGraph 流程
    deferredGraph = null
else:
    正常进入 STANDBY 或继续 pendingGraph 流程
```

OUTPUT 期间不进行 NC / 主机继承判断。

继承判断只在完整 submitGraph 流程开始时执行。

这样可以避免 OUTPUT 已经进行到一半时，由于主机或 NC 已经部分输出，导致继承比较结果不稳定。

---

## 14. 运行暂停与工序卸载

v2 明确区分两类停止行为：

```text
运行暂停 RuntimePause
工序卸载 ProcessUnload
```

---

### 14.1 运行暂停

触发来源：

```text
跳电
能量不足
关闭电源
红石禁用
机器暂停
```

效果：

```text
不进入 OUTPUT
不清除 currentGraph
不清除 internalBuffer
不清除 outputBuffer
不返还 NC
不返还主机
不清除水位状态
```

但：

```text
不再启动新的 job
正在运行的 job 立即中断
已被 job 消耗的输入物料损失
未完成 job 不产生输出
runningJobs 清空
```

恢复供电或启用后：

```text
机器继续使用当前 currentGraph 和当前缓存状态恢复调度。
```

---

### 14.2 工序卸载

触发来源：

```text
提交新工序
螺丝刀手动输出
玩家明确卸载当前工序
```

效果：

```text
进入 OUTPUT
停止当前 job
已消耗输入损失
默认清除内部中间物和循环物料
处理主机与 NC 的继承或输出
```

---

## 15. 提交新工序

### 15.1 提交目标

提交新图时，机器应尽量减少玩家重复取放主机和 NC 的操作。

玩家提交新图后：

```text
pendingGraph = newGraph
生成 SubmitPlan
进入 OUTPUT 或直接安装新图
```

---

### 15.2 SubmitPlan

提交新图时构建：

```text
SubmitPlan {
    retainedControllers
    retainedNC

    missingControllers
    missingNC
    missingStartupMaterials

    outputOldControllers
    outputOldNC

    discardOldInternalBuffers
    discardOldCycleBuffers
    discardOldJobs
}
```

---

### 15.3 可继承内容

只允许继承：

```text
主机方块
NC 物品
```

继承规则：

```text
新图仍需要的主机保留。
新图仍需要的 NC 保留。
保留数量不超过新图需求。
旧图多余主机和 NC 输出。
新图缺少的主机和 NC 进入 INPUT 收集。
```

---

### 15.4 不可继承内容

不可继承：

```text
循环启动物料
内部中间物
循环物料
目标产物缓存
副产物缓存
外部原料缓存
running job 已消耗材料
```

这些内容按卸载规则处理。

---

### 15.5 提交后状态

如果旧图不存在运行状态，且新图需求已被继承资源完全满足，并且不需要启动物料：

```text
直接进入 RUNNING
```

否则：

```text
进入 INPUT 收集缺少的主机、NC 或启动物料
```

如果存在旧图内部运行状态：

```text
先进入 OUTPUT 卸载旧工序
OUTPUT 完成后再安装 pendingGraph
```

---

## 16. 螺丝刀输出

螺丝刀切换到输出模式表示玩家明确要求拆出当前工序资源。

语义：

```text
进入 OUTPUT
不继承任何资源
输出所有主机
输出所有 NC
默认清除所有中间物、启动物料和水位缓存
Debug 模式开启时额外输出内部中间物
完成后 currentGraph = null，进入 STANDBY
```

---

## 17. OUTPUT 物料处理规则

### 17.1 默认行为

OUTPUT 默认只输出运行资格物和已形成的外部结果。

提交新图时输出：

```text
新图不再需要的主机
新图不再需要的 NC
已进入 outputBuffer 的目标产物
已进入 outputBuffer 的副产物
```

螺丝刀输出时输出：

```text
所有主机
所有 NC
已进入 outputBuffer 的目标产物
已进入 outputBuffer 的副产物
```

---

### 17.2 默认清除内容

默认清除：

```text
普通中间物
循环中间物
循环启动物料剩余
循环 reserve 缓存
普通中间物水位缓存
运行器内部暂存物
未完成 job 已消耗输入
```

这些被视为工序内部运行状态，不是玩家库存。

---

### 17.3 Debug 模式

配置项：

```text
debugExportInternalBuffer
```

默认：

```text
false
```

当关闭时：

```text
OUTPUT 不输出中间物，直接清除内部中间缓存。
```

当开启时：

```text
OUTPUT 输出所有内部中间物，包括普通中间物、循环中间物、启动物料剩余和水位缓存。
```

用途：

```text
调试工序
验证物料流
检查水位控制
排查节点过量生产
```

不设计白名单导出。

---

## 18. 运行期资源模型

### 18.1 输入来源

RUNNING 阶段的普通原料来自外部输入来源：

```text
localInputBuffer
MEInputProvider
DualInputProvider
```

含义：

```text
localInputBuffer = 机器本地输入总线 / 输入仓
MEInputProvider = ME 输入舱室或存储输入总线暴露的网络库存
DualInputProvider = 双输入舱室、样板舱室等特殊输入来源
```

这些统称为：

```text
ExternalInputSources
```

---

### 18.2 输入扣取顺序

节点启动时按顺序扣取：

```text
1. internalBuffer
2. localInputBuffer
3. MEInputProvider
4. DualInputProvider
```

默认顺序体现：

```text
内部中间物优先
本地输入优先于 ME 大库存
特殊双输入 / 样板输入最后处理
```

实际实现可在开发稿中进一步细化不同舱室的扣取规则。

---

## 19. 运行期资源快照

RUNNING 每 tick 构建或维护资源快照。

资源来源：

```text
internalBuffer
cycleBuffer / cycleWatermark
localInputBuffer
MEInputProvider
DualInputProvider
outputBuffer
runningJobs 的短期在途产物与本 tick 预留消耗
```

用途：

```text
计算节点可运行并行
避免同 tick 反复扫描 ME 输入
避免后续节点看到过期资源
降低矿典匹配和输入扣取开销
支持 projectedSoon 水位判断
```

---

## 20. projectedSoon

水位判断使用短期预计缓存：

```text
projectedSoon[m]
=
currentInternalBuffer[m]
+ incomingWithinLookahead[m]
- reservedThisTick[m]
```

其中：

```text
incomingWithinLookahead = remainingTicks <= lookaheadWindow 的 running job 将产出的物料
reservedThisTick = 本 tick 已被输入事务预留或消耗的物料
```

不应将长耗时 job 的远期产出全额计入当前水位判断。

原因是：

```text
长耗时 job 刚启动后产物很久才进入缓存。
若将其全额算入 incomingSoon，可能导致上游过早停止补料，下游断供。
```

默认建议：

```text
lookaheadWindow = 20 tick
```

后续开发稿可根据下游平均耗时进一步优化。

---

## 21. 输入扣取事务

资源快照只用于计算候选节点和 actualParallel。

真正启动节点时必须执行扣取事务：

```text
1. 预扣 internalBuffer
2. 再按顺序扣 localInputBuffer / MEInputProvider / DualInputProvider
3. 若任一步失败，回滚 internalBuffer 预扣
4. 成功后提交事务并创建 job
```

真实输入消耗必须在 job 启动前完成。

对于 ME 输入，应尽量使用大块扣取，单次上限不超过 `Integer.MAX_VALUE`。

矿典输入必须记录实际消耗到的具体物品，不能用模板代表物替代。

---

## 22. 虚拟节点运行模型

### 22.1 有效参数

每个节点运行时使用有效参数：

```text
effectiveParallelLimit = node.parallelLimit × machine.parallelMultiplier
effectiveOc = node.overclockCount + machine.extraOverclock
effectiveOc = min(effectiveOc, baseDuration 压到 1 tick 所需超频次数)
effectiveDuration = baseDuration 按 effectiveOc 压缩
effectiveEuPerTick = baseEuPerTick 按 effectiveOc 放大
```

---

### 22.2 actualParallel

单次 job 启动时：

```text
actualParallel <= effectiveParallelLimit
```

计算：

```text
actualParallel = min(
    effectiveParallelLimit,
    floor(每种输入可用量 / 单份输入需求) 的最小值
)
```

如果输入不足以支持一次配方：

```text
actualParallel < 1
```

则本 tick 该节点不能启动。

---

### 22.3 单节点单 job

每个节点同一时间只允许一个 running job。

含义：

```text
节点并发度由 actualParallel 表达，而不是由同节点多 job 表达。
```

如果玩家想让同一配方形成多个独立虚拟加工单元，应在工序图中复制节点，并为每个节点提交独立主机资格。

---

### 22.4 RunningJob

每次节点启动创建：

```text
RunningJob {
    nodeId
    parallel
    durationTicks
    euPerTick
    remainingTicks
    reservedEnergy
    consumedItems
    consumedFluids
}
```

启动时：

```text
消耗 input × parallel
创建 job，将消耗记录写入 consumedItems / consumedFluids
```

推进时：

```text
remainingTicks -= 1
```

完成时：

```text
产出 output × parallel
进行输出路由
```

---

## 23. 消费者优先的分层候选队列调度

### 23.1 设计目标

调度器目标不是简单上游优先，也不是简单下游贪心。

它要同时解决：

```text
复杂图运行不连续
波形生产
中间物残留
共享原料竞争
上游补料不足
输出缓存阻塞
```

因此 v2 修订版采用：

```text
消费者优先的分层候选队列调度
```

核心原则：

```text
先处理过量与阻塞
再消耗内部中间物
再推进目标链条
再按低水位补给上游
最后运行普通源头节点
```

---

### 23.2 队列层级

每个调度周期，调度器将可启动节点放入以下候选队列：

```text
L0：强制推进队列    (当前 classifyCandidateLayer 不分配此层，为预留优先级)
L1：内部消耗队列    (consumesAvailableInternalInput)
L2：目标推进队列    (producesTargetOutput; 仅在非 L1 时触发)
L3：缺料补给队列    (suppliesLowWater; 仅在非 L1 时触发)
L4：普通源头队列    (兜底)
```

调度器按：

```text
L0 -> L1 -> L2 -> L3 -> L4
```

依次尝试启动。

注意：源码中 `classifyCandidateLayer` 的实际分配顺序是 `INTERNAL_CONSUME → LOW_WATER_SUPPLY → TARGET_PROGRESS → SOURCE_PRODUCTION`，结合 `CandidateLayer` 枚举声明顺序 (`FORCED_PROGRESS, INTERNAL_CONSUME, TARGET_PROGRESS, LOW_WATER_SUPPLY, SOURCE_PRODUCTION`)，实际运行时的调度优先级为 FORCED_PROGRESS(预留) > INTERNAL_CONSUME > TARGET_PROGRESS > LOW_WATER_SUPPLY > SOURCE_PRODUCTION。

---

### 23.3 L0：强制推进队列

进入条件：

```text
能消耗超过 highWater 的内部中间物
能消耗超过 reserve 的循环净输出
能缓解 outputBuffer 高水位阻塞
```

目标：

```text
优先处理已经过量或即将阻塞系统的物料。
```

---

### 23.4 L1：内部消耗队列

进入条件：

```text
本次启动会消耗 internalBuffer 中任意中间物
本次启动会消耗环内循环物料并推动环继续转动
```

目标：

```text
消费者优先，减少中间物残留。
```

该队列负责解决共享原料竞争问题：

```text
A + B -> C
A + C -> D
```

如果内部已经有 C，则 `A + C -> D` 优先于 `A + B -> C`。

---

### 23.5 L2：目标推进队列

进入条件：

```text
能直接产生目标产物
能产生目标产物前置关键物料
靠近目标产物链条
```

目标：

```text
提升目标产物输出连续性，而不是只补半成品。
```

---

### 23.6 L3：缺料补给队列

进入条件：

```text
能生产 projectedSoon 低于 lowWater 的中间物
能生产下游即将短缺的关键物料
```

目标：

```text
在下游彻底断料前，让上游提前补料。
```

这解决复杂图“补料阶段 / 消费阶段”反复切换造成的波形问题。

---

### 23.7 L4：普通源头队列

进入条件：

```text
无入边源头节点
只消耗外部原料的节点
当前不会明显造成中间物过量的普通生产节点
```

目标：

```text
维持基础生产，但优先级最低。
```

---

### 23.8 层内排序

每个队列内部使用稳定排序：

```text
1. runCredit 高者优先
2. 更接近目标产物者优先
3. nodeId 稳定排序
```

runCredit 用于平滑启动频率：

```text
runCredit[node] += expectedStartRate[node]
```

启动一次后：

```text
runCredit[node] -= 1
```

简化默认：

```text
expectedStartRate[node] = 1 / effectiveDuration
```

如果某个节点长期未运行，它会积累 runCredit，防止饿死。

如果某个节点刚运行过，它的 runCredit 降低，避免连续霸占调度。

---

### 23.9 调度周期流程

每个调度周期：

```text
1. 推进并结算 runningJobs。
2. 更新资源快照。
3. 更新 projectedSoon。
4. 遍历当前未运行节点。
5. 判断节点是否可启动。
6. 可启动节点按语义放入 L0~L4 队列。
7. 按 L0 -> L4 顺序尝试启动。
8. 每启动一个节点后，更新本 tick 的临时资源视图。
9. 达到启动预算或没有可启动节点时结束。
```

可设置启动预算：

```text
maxStartsPerTick
```

用于避免单 tick 启动过多节点。

---

## 24. 水位控制

### 24.1 普通中间物水位

普通中间物使用软目标区间：

```text
lowWater
targetWater
highWater
```

建议默认：

```text
lowWater = 下游一次有效运行所需量
targetWater = 1~2 次下游启动需求
highWater = 2~3 次下游启动需求
```

当：

```text
projectedSoon[m] >= highWater[m]
```

生产该物料的上游节点暂停启动。

当：

```text
projectedSoon[m] <= lowWater[m]
```

生产该物料的上游节点进入缺料补给队列。

水位只限制上游启动：

```text
不阻止消费者启动
不自动删除已有库存
不阻止 OUTPUT 默认清除中间物
```

---

### 24.2 循环物料水位

每个有效环的循环物料维护：

```text
reserve
lowWater
highWater
```

含义：

```text
reserve = 维持环运行不可外排的最低保留量
lowWater = 允许上游恢复生产的低水位
highWater = 暂停继续生产该循环物料的高水位
```

规则：

```text
环内消费者可以使用 reserve 内物料。
环外消费者只能使用超过 reserve 的净输出。
外部输出只能输出超过 reserve 的净输出。
超过 highWater 时暂停继续生产循环物料的相关上游节点。
```

---

### 24.3 输出缓存水位

目标产物和副产物进入 outputBuffer。

如果输出舱室或 ME 输出无法接收：

```text
outputBuffer 暂存
达到 highWater 后阻止继续产生该外部输出的节点启动
输出空间恢复后继续导出
```

默认严格模式：

```text
若某节点的任意输出会被输出水位阻塞，则该节点不可启动。
```

这样避免副产物或目标产物被吞。

---

## 25. 输入消耗规则

节点启动前按顺序消耗：

```text
1. internalBuffer 中可消耗物料
2. localInputBuffer
3. MEInputProvider
4. DualInputProvider
```

环物料特殊规则：

```text
环内消费者可用 internalBuffer[C]
环外消费者只可用 max(0, internalBuffer[C] - reserve[C])
```

如果真实扣取失败：

```text
回滚本次内部预扣
节点不启动
不生成 job
```

---

## 26. 产物路由规则

job 完成后，产物按以下规则路由：

```text
1. 如果是环循环物料：
       进入对应循环缓存，并按 reserve/highWater 判断净输出可用性。
2. 如果被直接下游消费：
       进入 internalBuffer。
3. 如果是目标产物：
       进入 outputBuffer。
4. 如果无人消费且非目标：
       进入 outputBuffer，作为副产物。
```

目标产物节点后的副产处理链由边决定，不由目标产物节点标记强行截断。

---

## 27. 回收节点

回收节点是特殊虚拟节点。

规则：

```text
不需要主机
不需要 NC
只接受物品输入
输入必须来自直接上游节点
不允许外部输入
```

模式：

```text
废料模式：
    inputCost = 1
    12.5% 概率产出废料

废料盒模式：
    inputCost = 9
    12.5% 概率产出废料盒
```

实际运行使用批量概率结算。

配平和 GUI 估计使用期望值。

---

## 28. 跳电、能量不足与电源关闭

跳电、能量不足、关闭电源属于运行暂停，不属于工序卸载。

行为：

```text
停止运行调度
不启动新节点
中断当前 running job
已消耗但未完成的输入损失
不结算未完成 job 输出
保留 currentGraph
保留主机与 NC
保留 internalBuffer
保留 outputBuffer
保留循环水位状态
保留普通中间物水位状态
```

恢复供电后：

```text
继续基于当前缓存状态运行。
```

跳电提示应包含：

```text
坐标
机器名
节点或配方
耗时
耗能
actualParallel
effectiveOc
```

---

## 29. 工序卸载与 OUTPUT

工序卸载包括：

```text
提交新图
螺丝刀输出
明确卸载当前工序
```

卸载进入 OUTPUT。

OUTPUT 默认行为：

```text
不再启动新节点
立即终止 running job
已消耗但未完成的输入损失
不结算未完成 job 输出
默认清除所有内部中间物和循环物料
输出目标产物和副产物缓存
处理主机与 NC
```

### 提交新图

```text
新图仍需要的主机与 NC 继承
新图不需要的主机与 NC 输出
缺失的主机与 NC 进入 INPUT 收集
循环启动物料不继承，必须重新收集
```

### 螺丝刀输出

```text
输出所有主机
输出所有 NC
清除 currentGraph
进入 STANDBY
```

### Debug 模式

若：

```text
debugExportInternalBuffer = true
```

则 OUTPUT 额外输出所有内部中间物、循环中间物、启动物料剩余和水位缓存。

---

## 30. 可选配平工具

配平工具用于推荐节点基础并行上限：

```text
parallelLimit
```

它不属于运行器主循环，不会在 RUNNING 中自动反复执行。

玩家可以：

```text
手动设置 parallelLimit
点击配平按钮生成推荐 parallelLimit
接受推荐结果
继续手动修改
```

配平工具只调整：

```text
parallelLimit
```

不调整：

```text
overclockCount
machine.parallelMultiplier
machine.extraOverclock
配方本身
节点输出声明
```

---

### 30.1 速率模型

对于节点 `i`、物料 `m`：

```text
produceRate(i, m) = outputAmount(i, m) × P_i / duration_i
consumeRate(i, m) = inputAmount(i, m) × P_i / duration_i
```

其中：

```text
P_i = 推荐 parallelLimit
duration_i = 当前节点基础耗时
```

概率产物使用期望值：

```text
expectedOutputAmount = stackAmount × chance / 10000
```

---

### 30.2 边流量变量

为了处理一对多分流，配平工具可以引入边流量变量：

```text
F_e(m) = 边 e 上物料 m 的速率
```

对于边：

```text
u -> v, material = m
```

若 v 的该输入只由该边提供，则：

```text
F_e = consumeRate(v, m)
```

对生产者：

```text
Σ F_out(u, m) <= produceRate(u, m)
```

剩余：

```text
surplusRate(u, m) = produceRate(u, m) - Σ F_out(u, m)
```

剩余按目标产物或副产物处理。

---

### 30.3 普通边约束

对普通内部物料：

```text
上游分配到边的生产速率 = 下游通过边的消耗速率
```

对一对多输出：

```text
上游总生产速率 >= 所有下游边流量之和
```

---

### 30.4 环检查

配平工具不负责强制推荐环 P。

环合法性直接使用当前节点参数检查：

```text
cycleNetRate(C) > 0
```

如果不满足，提示玩家修改 P 或使用配平工具。

---

### 30.5 目标函数

推荐目标优先级：

```text
1. 内部边满足物料守恒。
2. 所有环满足单循环物料正净输出。
3. 目标产物速率为正。
4. 副产物速率可计算。
5. 节点并行数为正整数且不超过限制。
6. 多自由度情况下优先减少非目标副产与内部残差。
```

默认近似目标：

```text
minimize ΣP_i
```

不要求绝对最小终产物输出。

---

## 31. 原材料导出

机器提供“导出原材料”功能，用于把当前工序图需要从外部输入的原料写入 ME 存储输入舱室标记槽。

导出对象：

```text
所有不会被图内直接上游节点提供的输入物料
```

示例：

```text
铝矿石 -> 粉碎铝矿石 -> 洗净铝矿石 -> 洁净铝粉 -> 铝粉
```

外部输入为：

```text
铝矿石
蒸馏水
```

中间物不会导出为外部原料。

矿典输入不自动决定具体标记物，导出时跳过并提示玩家手动标记。

---

## 32. 数据结构建议

### 32.1 MachineRuntime

```text
MachineRuntime {
    mode

    currentGraph
    pendingGraph
    deferredGraph
    submitPlan

    controllerStorage
    ncStorage

    localInputBuffer
    meInputProvider
    dualInputProvider

    internalBuffer
    outputBuffer

    runningJobs

    runtimeResourceSnapshot
    projectedSoon

    cycleAnalysis
    cycleReserve
    cycleLowWater
    cycleHighWater

    normalLowWater
    normalTargetWater
    normalHighWater

    outputHighWater

    runCredit

    machineRunnable

    parallelMultiplier
    extraOverclock
    wireless
    debugExportInternalBuffer
}
```

---

### 32.2 SubmitPlan

```text
SubmitPlan {
    retainedControllers
    retainedNC

    missingControllers
    missingNC
    missingStartupMaterials

    outputOldControllers
    outputOldNC

    discardOldInternalBuffers
    discardOldCycleBuffers
    discardOldJobs
}
```

---

### 32.3 RunningJob

```text
RunningJob {
    nodeId
    parallel
    durationTicks
    euPerTick
    remainingTicks
    reservedEnergy
    consumedItems
    consumedFluids
}
```

---

### 32.4 RuntimeResourceSnapshot

```text
RuntimeResourceSnapshot {
    internalAvailable
    localAvailable
    meAvailable
    dualInputAvailable
    incomingWithinLookahead
    reservedThisTick
    projectedSoon
}
```

---

## 33. 总体伪代码

```python
def tick_machine(machine):
    if machine.mode == STANDBY:
        return

    if machine.mode == INPUT:
        tick_input(machine)
        return

    if machine.mode == RUNNING:
        tick_running(machine)
        return

    if machine.mode == OUTPUT:
        tick_output(machine)
        return
```

```python
def submit_graph(machine, graph):
    if machine.mode == OUTPUT:
        machine.deferredGraph = graph
        return

    machine.pendingGraph = graph
    machine.submitPlan = build_submit_plan(machine.currentGraph, graph)
    machine.mode = OUTPUT
```

```python
def tick_output(machine):
    process_output_unload(machine)

    if not output_finished(machine):
        return

    if machine.deferredGraph is not None:
        graph = machine.deferredGraph
        machine.deferredGraph = None
        submit_graph(machine, graph)
        return

    if machine.pendingGraph is not None:
        install_pending_graph(machine)
        return

    machine.currentGraph = None
    machine.mode = STANDBY
```

```python
def tick_running(machine):
    if is_power_paused(machine):
        interrupt_running_jobs_with_loss(machine)
        return

    advance_running_jobs(machine)
    finish_completed_jobs(machine)

    update_resource_snapshot(machine)
    update_projected_soon(machine)
    update_run_credit(machine)

    queues = build_layered_candidate_queues(machine)

    starts = 0

    for queue in [L0, L1, L2, L3, L4]:
        for node in queue.sorted_by_credit_and_stable_order():
            if starts >= machine.maxStartsPerTick:
                return

            actualParallel = compute_actual_parallel(machine, node)

            if actualParallel < 1:
                continue

            if is_blocked_by_watermark(machine, node):
                continue

            if not has_energy(machine, node, actualParallel):
                continue

            tx = begin_input_transaction(machine, node, actualParallel)

            if tx.commit():
                start_job(machine, node, actualParallel, tx.consumedInputs)
                starts += 1
                update_temporary_resource_view(machine)
            else:
                tx.rollback()
```

---

## 34. 设计原则总结

```text
1. 工序图是局部生产网络，不是线性链。
2. 目标产物节点不是拓扑终点。
3. 目标产物可以显式覆盖默认推断。
4. 每个有效环只允许一种循环物料。
5. 环必须在当前 P/耗时下具备正净输出。
6. 机器运行状态与图语义严格分离。
7. INPUT 只收集主机、NC 和启动物料，不收集普通原料。
8. 普通原料在 RUNNING 中动态扣取。
9. 主机和 NC 是运行资格，可以在提交新图时继承。
10. 启动物料是真实运行物料，不继承、不返还，卸载时默认清除。
11. 调度采用消费者优先的分层候选队列。
12. runCredit 用于减少波形运行。
13. projectedSoon 只统计短期即将完成的在途产物。
14. 普通中间物和循环物料都使用水位控制。
15. 输出缓存阻塞会阻止继续产生对应输出。
16. 跳电/能量不足只是运行暂停，不清缓存、不卸载图。
17. 提交新图和螺丝刀输出是工序卸载，会进入 OUTPUT。
18. OUTPUT 中再次提交新图只暂缓最新提交，不影响当前 OUTPUT。
19. OUTPUT 默认清除内部中间物，只输出外部结果与主机/NC。
20. Debug 模式才导出内部中间物。
21. 可选配平工具只推荐 parallelLimit，不强制参与运行。
```

---

## 35. 最终简述

v2 修订版将工序机器定义为一台基于工序图的虚拟生产网络执行器。工序图允许多源头、多目标、多副产链和多个局部单物料自增环。目标产物节点只是目标输出标记，不再表示拓扑终点。机器运行时根据节点有效参数创建虚拟 job，并通过消费者优先的分层候选队列调度维持连续生产。调度器先处理过量和阻塞，再消耗内部中间物，再推进目标链条，再对低水位物料补给上游，最后运行普通源头节点。运行器使用水位控制和 projectedSoon 限制中间物积压，用 runCredit 减少复杂图的波形运行。跳电或关电源只暂停运行并保留状态；提交新图或螺丝刀输出才会卸载工序。卸载时默认清除内部中间物和循环物料，只输出目标/副产结果以及应返还或不再继承的主机与 NC。配平工具作为可选推荐器，只用于生成节点基础并行上限，不改变机器运行主逻辑。
