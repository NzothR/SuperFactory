# GTNH 工序机器详细设计文档

## 1. 文档目的

本文档用于描述一台面向 GTNH 工序图的复合型虚拟加工机器的整体设计。

该机器用于执行玩家提交的“工序图”。工序图由多个配方节点组成，每个节点代表一个 GTNH 配方运行单元。机器不会真正把这些节点映射到官方 GT 多方块主机上逐个运行，而是根据节点信息生成内部虚拟运行任务，并由机器自身的运行系统模拟各节点的输入消耗、耗时、耗能、输出产出和物料流转。

本文档只讨论机器运行模型本身，不讨论配平算法。

---

## 2. 机器核心定位

### 2.1 机器本质

该机器是一台：

```text
基于工序图的虚拟复合加工机
```

它的作用是：

```text
接收一张工序图
校验并收集工序所需的运行资格物
初始化内部运行状态
模拟执行工序图中的所有配方节点
管理内部缓存、外部输入和输出缓存
支持普通工序、多终产物工序和循环工序
```

该机器不是多个 GT 多方块主机的真实运行集合，也不是对每个节点调用官方 GT 多方块主机配方执行路径，而是根据节点配置生成虚拟运行任务，由机器自身的算法模拟执行。

### 2.2 与普通 GT 机器的区别

普通 GT 机器运行一个配方时，通常会经过官方多方块配方检查、输入检测、并行计算、超频计算、输出检查等流程。

本机器不直接复用官方多方块运行路径。提交工序后，机器根据每个节点预先确定的配置生成内部虚拟任务模型：

```text
VirtualNodeTask {
    nodeId
    inputAmount × P
    outputAmount × P
    duration
    energy
}
```

运行时由机器自己的运行器负责检查输入、消耗输入、创建运行任务、推进耗时、结算输出、路由物料和维护循环水位。

---

## 3. 工序图与节点基础模型

### 3.1 工序图

工序图是一组配方节点及其物料流关系：

```text
ProcessGraph {
    nodes
    edges
    terminalDefinitions
    startupMaterials
}
```

其中：

```text
nodes = 配方节点集合
edges = 物料流边集合
terminalDefinitions = 终产物定义
startupMaterials = 循环工序启动物料，仅循环工序需要
```

### 3.2 节点

每个节点代表一个虚拟配方运行单元：

```text
RecipeNode {
    id
    recipe
    inputs
    outputs
    oc
    parallel
    duration
    energy
    ncItems
    requiredController
}
```

其中：

```text
oc = 当前节点基础 OC 状态
parallel = 当前节点基础最大并行数 P
duration = 当前节点固定运行耗时
energy = 当前节点固定耗能
ncItems = 节点所需 NC 物品
requiredController = 节点所需多方块主机
```

机器运行时不会写回修改节点自身的 OC、P、duration 和 energy。机器级运行参数会在运行时形成有效参数：

```text
effectiveParallelLimit = node.parallel × machine.parallelMultiplier
effectiveOc = node.oc + machine.extraOverclock
effectiveOc = min(effectiveOc, baseDuration 压到 1 tick 所需的无损超频次数)
effectiveDuration = node.baseDuration 按 effectiveOc 无损超频后得到的耗时
effectiveEnergy = node.baseEnergy 按 effectiveOc 无损超频后得到的 EU/t
```

其中机器级参数只影响运行中的有效值，不改变工序图保存的节点参数。超频保护会同时约束节点自身 OC 和机器级全局额外 OC 的合计值：当合计超频已经足以把节点基础耗时压到 1 tick 时，继续增加 OC 不再提高速度，因此运行时会使用该 1 tick 上限次数，避免无意义的额外耗能。

### 3.3 节点运行语义

节点每次启动时，按照有效参数运行：

```text
消耗 input_amount × actualParallel
运行 effectiveDuration
消耗 effectiveEnergy × actualParallel
产出 output_amount × actualParallel
```

`parallel` 表示该节点单次最多启动的并行上限，而不是必须固定跑满的并行。运行时如果输入不足以满足完整 `effectiveParallelLimit`，但仍满足一次配方的最小输入需求，机器会自动降低本次 `actualParallel` 并启动节点；如果连一次配方都无法满足，则该节点本轮不能启动。机器不会自动升并行超过 `effectiveParallelLimit`。

无线模式、机器并行倍率和机器额外 OC 是当前集成工厂保留的可编辑运行参数：

```text
wireless = true 时优先从无线电网一次性扣除运行任务总能量
parallelMultiplier 默认 1，用于整体放大所有节点并行上限
extraOverclock 默认 0，用于给所有节点额外叠加相同无损超频次数
```

---

### 3.4 回收节点

回收节点是工序图中的特殊虚拟节点，用于稳定封装 IC2 回收机行为。它不是普通 GT 配方节点，不需要配方表、不需要多方块主机，也没有 NC 物品需求；但它仍然可以被锁定、连线、设为结束节点，并参与运行调度。

回收节点只接受物品输入，不接受流体输入。提交工序时，回收节点的所有输入都必须来自直接上游节点的产物，不能作为外部原料导入；如果回收节点没有直接上游，或者输入物品无法由直接上游提供，则工序提交失败。这样可以保证回收节点只处理工序内部副产物或中间物，不会变成任意外部垃圾入口。

回收节点有两种产出模式：

```text
废料模式：消耗 1 个任意匹配物品，12.5% 概率产出 1 个废料
废料盒模式：消耗 9 个任意匹配物品，12.5% 概率产出 1 个废料盒
```

回收节点基础耗时固定为 20 tick，基础耗能固定为每次回收 1 EU/t，基础并行上限视为无限大。实际运行时仍会按内部可消耗物料动态下调 `actualParallel`，不会强制跑满无限并行。节点自身 OC 可编辑，并按无损超频影响回收节点的有效耗时和有效耗能。

概率产出结算必须使用批量概率算法，而不是对每个物品逐个掷骰。对于 G 级、T 级数量的物品，批量概率结算能避免运行期性能灾难；节点编辑器、配平估计和产物估计使用期望值展示，实际运行结算仍保留 GT 风格的整数概率波动。

---

## 4. 主机与 NC 的设计定位

### 4.1 多方块主机

每个节点都需要一台能够运行该配方类型的多方块主机。

主机的作用是证明该节点具备被虚拟执行的资格。主机不会在运行模式下真实执行配方，它只是节点运行权限的一部分。

### 4.2 每节点独占主机规则

一个节点需要一台主机。如果多个节点需要相同类型的主机，也必须分别提交。

例如：

```text
节点 A 需要大型研磨塔主机
节点 B 需要大型研磨塔主机
节点 C 需要大型研磨塔主机
```

则提交需求为：

```text
大型研磨塔主机 × 3
```

不能只提交一台主机来供三个节点共享。

这样设计的意义是：每个节点都被视为一个独立虚拟加工单元，节点之间可以并发运行，同类节点也不共享运行资格。

### 4.3 NC 物品

NC 是 Non-Consumable，即不消耗物品。例如：

```text
模具
催化剂
编程电路
透镜
特殊工具
特殊容器
配置物品
```

NC 物品的作用是提供节点虚拟运行资格。它们不会在每次节点运行时被消耗。

### 4.4 主机与 NC 的生命周期

主机和 NC 的生命周期如下：

```text
输入模式：从输入舱室收集
运行模式：保存在运行资格缓存中，仅作为运行条件
输出模式：返还到输出舱室
```

它们不进入普通内部物料缓存，也不参与配方输入输出流转。

---

## 5. 循环启动物料

### 5.1 启动物料定位

循环启动物料与主机、NC 不同。主机和 NC 只是运行门槛，循环启动物料是实际参与工序运行的初始物料。

循环启动物料用于让循环工序能够从初始状态开始运行。例如某个循环链需要一份循环液、催化剂或中间物才能启动，则这些物料必须在提交阶段提供。

### 5.2 启动物料收集

如果工序图被判定为循环工序，则提交阶段必须提供启动物料。

输入模式收集的需求为：

```text
requiredSubmitMaterials =
    requiredControllers
  + requiredNCItems
  + startupMaterials
```

其中：

```text
requiredControllers -> 运行资格缓存
requiredNCItems -> 运行资格缓存
startupMaterials -> 内部物料缓存
```

### 5.3 启动物料进入运行模式

进入运行模式时，启动物料会进入内部缓存：

```text
startupMaterials -> internalBuffer
```

之后它们与普通内部物料一样，可以被节点消耗、转化、回流或输出。

---

## 6. 机器状态总览

机器共有四种状态：

```text
STANDBY  常规待机模式
INPUT    输入模式
RUNNING  运行模式
OUTPUT   输出模式
```

状态之间的主要流转关系为：

```text
提交工序
    ↓
OUTPUT 清空旧状态
    ↓
INPUT 收集新工序必需品
    ↓
RUNNING 执行工序
    ↓
OUTPUT 停止并清空
    ↓
STANDBY
```

提交工序可以在任意状态触发，但不会直接覆盖当前状态。所有新工序提交都必须先经过 OUTPUT 清空阶段。

---

## 7. 提交工序总流程

### 7.1 随时提交原则

玩家可以在任意状态点击提交工序按钮：

```text
STANDBY
INPUT
RUNNING
OUTPUT
```

但提交不会立即覆盖机器内部状态。

提交新工序只会创建一个待提交工序：

```text
pendingGraph = newGraph
```

然后机器强制进入输出模式：

```text
mode = OUTPUT
```

### 7.2 提交必须先输出的原因

这样设计是为了避免：

```text
旧工序的中间物污染新工序
旧工序的 NC 被新工序误用
旧工序的主机被新工序误判
旧运行任务残留导致状态错误
循环缓存残留影响新循环
```

所以任何新工序正式设置之前，必须先清空旧机器状态。

### 7.3 输出完成后自动进入输入模式

当输出模式确认内部状态已经完全清空后，如果存在 `pendingGraph`，则机器自动：

```text
currentGraph = pendingGraph
pendingGraph = null
初始化新工序运行状态
统计新工序提交需求
mode = INPUT
```

然后输入模式开始收集新工序所需资源。

---

## 8. STANDBY：常规待机模式

### 8.1 状态定义

STANDBY 是机器空闲状态。此时机器没有正在运行的工序，或者旧工序已经清空完成。

### 8.2 状态行为

在 STANDBY 中：

```text
不执行节点
不推进任务
不消耗物料
不扫描提交必需品
不维护循环水位
```

机器只等待玩家操作。

### 8.3 可执行操作

玩家可以：

```text
提交新工序
放入物品/流体
取出物品/流体
修改机器配置
查看上一次运行结果
```

### 8.4 提交工序后的状态变化

如果玩家在 STANDBY 提交新工序：

```text
pendingGraph = newGraph
mode = OUTPUT
```

即使 STANDBY 下内部通常为空，也仍然建议统一进入 OUTPUT，以保证状态机逻辑一致。

如果 OUTPUT 检查确认无内容需要输出，则会立即设置新工序并进入 INPUT。

---

## 9. OUTPUT：输出模式

### 9.1 状态定义

OUTPUT 是机器清空内部状态的模式。它用于停止旧工序、导出内部缓存、返还主机和 NC、导出输出缓存，并准备切换到新工序或回到待机。

### 9.2 输出模式的强制性

只要机器进入 OUTPUT，就必须完成内部清空。在内部状态没有完全清空前：

```text
不允许切换到 INPUT
不允许切换到 RUNNING
不允许设置 currentGraph 为新工序
不允许覆盖运行缓存
```

如果玩家在 OUTPUT 中再次提交新工序，则只更新 pendingGraph。机器仍保持 OUTPUT，直到清空完成。

### 9.3 输出内容

OUTPUT 模式会尝试导出：

```text
1. outputBuffer 中的终产物
2. outputBuffer 中的副产物
3. internalBuffer 中未消耗的中间物
4. cyclic target material 的保留缓存
5. NC 物品
6. 多方块主机
7. 剩余启动物料
8. 已停止任务中可返还的物料
```

输出目标通常为：

```text
输出物品舱
输出流体舱
主机/NC 返还槽
```

### 9.4 正在运行任务的处理

如果进入 OUTPUT 时仍存在正在运行的虚拟任务，需要定义处理方式。

推荐第一版采用：

```text
中止所有运行任务
返还已消耗但未完成的输入物料
不产出该任务的输出
```

也可以采用另一种策略：等待所有任务自然完成后再输出。但该策略会导致玩家切换工序时等待时间较长。

因此建议默认中止任务并返还输入。任务中止需要依赖任务记录：

```text
RunningJob {
    nodeId
    consumedInputs
    remainingTicks
    parallel
}
```

这样可以精确返还未完成任务消耗的输入物。

### 9.5 输出完成条件

输出模式完成条件是：

```text
internalBuffer 为空
outputBuffer 为空
ncStorage 为空
controllerStorage 为空
runningJobs 为空
currentGraph 运行状态已卸载
```

当输出完成后：

如果存在待提交工序：

```text
pendingGraph != null
```

则进入新工序输入流程：

```text
currentGraph = pendingGraph
pendingGraph = null
initializeRuntime(currentGraph)
buildSubmitRequirements(currentGraph)
mode = INPUT
```

否则：

```text
mode = STANDBY
```

### 9.6 OUTPUT 伪代码

```python
def tick_output_mode(machine):
    abort_or_finish_running_jobs(machine)

    export_output_buffer(machine)
    export_internal_buffer(machine)
    export_nc_storage(machine)
    export_controller_storage(machine)

    if not machine.is_internal_state_empty():
        return

    clear_current_runtime_state(machine)

    if machine.pending_graph is not None:
        machine.current_graph = machine.pending_graph
        machine.pending_graph = None

        initialize_graph_runtime(machine)
        build_submit_requirements(machine)

        machine.mode = INPUT
    else:
        machine.mode = STANDBY
```

---

## 10. INPUT：输入模式

### 10.1 状态定义

INPUT 是工序提交后的资源收集模式。它用于收集新工序所需的运行资格物和循环启动物料。

### 10.2 输入模式收集内容

INPUT 需要收集：

```text
1. 每个节点所需的多方块主机
2. 每个节点所需的 NC 物品
3. 循环工序所需的启动物料
```

其中：

```text
主机与 NC 是运行门槛
启动物料是实际进入内部缓存的运行物料
```

### 10.3 主机需求统计

遍历所有节点：

```text
for node in graph.nodes:
    requiredControllers += node.requiredController
```

如果三个节点都需要大型研磨塔主机，则需求为：

```text
大型研磨塔主机 × 3
```

### 10.4 NC 需求统计

遍历所有节点：

```text
for node in graph.nodes:
    requiredNCItems += node.ncItems
```

相同 NC 是否合并为总需求数量，取决于物品是否可堆叠。逻辑上应按数量累计。

例如：

```text
模具 X × 1
模具 X × 1
```

合并为：

```text
模具 X × 2
```

### 10.5 启动物料需求统计

如果：

```text
graph.hasCycle() == true
```

则额外加入：

```text
graph.startupMaterials
```

如果不是循环图，则不需要启动物料。

### 10.6 输入模式库存流向

输入模式收集完成后，资源流向如下：

```text
主机 -> controllerStorage
NC 物品 -> ncStorage
循环启动物料 -> internalBuffer
```

其中 controllerStorage 和 ncStorage 只用于运行资格检查，internalBuffer 会参与节点模拟运行。

### 10.7 输入模式完成条件

输入模式完成条件为：

```text
collectedControllers >= requiredControllers
collectedNCItems >= requiredNCItems
如果是循环工序：
    collectedStartupMaterials >= requiredStartupMaterials
```

满足后，机器自动进入 RUNNING。

### 10.8 INPUT 伪代码

```python
def tick_input_mode(machine):
    collect_required_items_from_input(machine)
    collect_required_fluids_from_input(machine)

    if has_all_submit_requirements(machine):
        move_collected_resources_to_runtime_storage(machine)
        initialize_running_mode(machine)
        machine.mode = RUNNING
```

---

## 11. RUNNING：运行模式

### 11.1 状态定义

RUNNING 是实际执行工序图的状态。

在该状态下：

```text
机器按照工序图执行虚拟节点任务
节点按固定 OC、P、耗时、耗能运行
调度器决定节点启动顺序
内部缓存保存中间物
输出缓存保存终产物与副产物
循环目标物料执行水位控制
```

### 11.2 进入运行模式时的检查

进入 RUNNING 后，机器首先检查内部运行需求是否满足：

```text
NC 是否满足
主机是否满足
循环启动物料是否已进入内部缓存
当前工序图是否有效
运行缓存是否初始化完成
```

如果满足：

```text
machineRunnable = true
```

否则：

```text
machineRunnable = false
```

当 machineRunnable = false 时，机器不会启动任何节点。

### 11.3 运行资格缓存

运行模式中存在两个特殊缓存：

```text
controllerStorage
ncStorage
```

它们只用于检查运行资格，不参与配方执行。

例如节点 A 需要大型研磨塔主机和模具 X，机器只需要确认这些物品已经在资格缓存中。节点 A 每次运行时不会消耗这些物品。

### 11.4 运行库存

RUNNING 模式主要维护三类物料库存：

```text
externalInputBuffer
internalBuffer
outputBuffer
```

#### externalInputBuffer

表示玩家持续输入的可消耗原料，包括基础原料、外部补充中间物和外部补充流体。

#### internalBuffer

表示机器内部工序缓存，包括普通中间物、循环中间物、循环目标物料保留量和启动物料剩余。

#### outputBuffer

表示可导出的结果，包括普通终产物、副产物、循环目标物料净输出和溢出的中间物。

---

## 12. 虚拟节点任务模型

### 12.1 任务定义

每次节点启动时创建一个虚拟运行任务：

```text
RunningJob {
    nodeId
    parallel
    remainingTicks
    consumedInputs
    energy
}
```

其中：

```text
parallel = node.P
remainingTicks = node.duration
consumedInputs = 本次启动实际消耗的输入
energy = 本次任务耗能
```

### 12.2 任务启动

节点启动条件：

```text
所有输入物料都满足 input_amount × P
```

满足后：

```text
立即消耗输入
创建 RunningJob
加入 runningJobs 队列
```

### 12.3 任务推进

每 tick：

```text
remainingTicks -= 1
```

当：

```text
remainingTicks <= 0
```

任务完成。

### 12.4 任务完成

任务完成后，根据节点输出生成产物：

```text
output_amount × P
```

然后调用产物路由规则，把产物分配到 internalBuffer 或 outputBuffer。

---

## 13. 节点输入消耗规则

### 13.1 基础顺序

节点启动时，对每种输入物料按以下顺序消耗：

```text
1. internalBuffer
2. externalInputBuffer
```

也就是优先消耗内部缓存，内部不足时再消耗外部输入。

### 13.2 内部优先的意义

这样做可以保证已有中间物优先被推进到后续节点，减少内部残留，防止上游节点和外部输入抢占后续节点需求。

### 13.3 循环目标物料例外

如果某个输入物料是循环目标物料，则不能简单地全部消耗内部缓存。

循环目标物料受到水位保护，其内部可用量为：

```text
availableInternal(m) = max(0, internalBuffer[m] - reserveMin[m])
```

低于 reserveMin 的部分不可被消耗。

---

## 14. 产物路由规则

节点完成后，对每个输出物料单独判断。

### 14.1 普通中间物

如果该物料有直接后继消费者，则进入内部缓存：

```text
internalBuffer += material
```

### 14.2 普通终产物

如果该物料是目标产物，且不参与循环，则进入输出缓存：

```text
outputBuffer += material
```

### 14.3 副产物

如果该物料没有直接后继消费者，且不是目标产物，则作为副产物进入输出缓存：

```text
outputBuffer += material
```

节点只会结算节点输出表中实际写入的产物。若玩家在节点编辑器中删除某个官方配方副产物，则该副产物不会进入内部缓存或输出缓存，相当于该节点显式销毁/忽略该副产物。该行为是工序封装的一部分，用于允许玩家把不需要的副产物留在节点内部处理，而不污染整条工序的物料流。

矿典输入只作为“外部输入是否可匹配节点输入”的条件。运行任务实际消耗到哪一种矿典等价物，就必须记录并在中止、输出模式或回滚时返还同一种物品；不能用节点模板中的代表物替换真实输入物品。

### 14.4 循环目标物料

如果该物料既是目标产物，又参与循环，则它是循环目标物料。

循环目标物料先进入内部缓存，然后执行水位溢出：

```text
internalBuffer[m] += producedAmount

if internalBuffer[m] > reserveTarget[m]:
    overflow = internalBuffer[m] - reserveTarget[m]
    internalBuffer[m] = reserveTarget[m]
    outputBuffer[m] += overflow
```

这样可以保证循环所需物料被保留，超过目标水位的部分作为净输出。

---

## 15. 循环目标物料水位控制

### 15.1 适用对象

一个物料被判定为循环目标物料，需要满足：

```text
1. 该物料是目标产物
2. 该物料也被图内某些节点作为输入消耗
3. 当前工序图是循环工序
```

### 15.2 自动估计水位

循环物料水位采用自动估计。

设：

```text
Consumers(m) = 所有消耗 m 的节点
```

每个消费者节点一次启动所需数量为：

```text
need(v, m) = inputAmount(v, m) × P_v
```

默认估计：

```text
reserveMin(m) = max(need(v, m))
reserveTarget(m) = ceil(reserveMin(m) × 1.5)
```

解释：

```text
reserveMin 保证至少能支撑一个消耗该物料的节点启动
reserveTarget 提供一定冗余，避免循环刚好断流
```

如果后续需要更保守策略，可以改为：

```text
reserveMin(m) = Σ need(v, m)
```

### 15.3 消耗控制

节点消耗循环目标物料时，只能消耗超过 reserveMin 的部分：

```text
available = max(0, internalBuffer[m] - reserveMin[m])
```

如果不足，则该节点不能启动，除非该物料允许从外部输入补足。

### 15.4 输出控制

循环目标物料生产后，先补充内部缓存，再输出溢出部分：

```text
internalBuffer[m] += produced

if internalBuffer[m] > reserveTarget[m]:
    outputBuffer[m] += internalBuffer[m] - reserveTarget[m]
    internalBuffer[m] = reserveTarget[m]
```

其长期净输出由生产速率与循环消耗速率共同决定。

---

## 16. 普通中间物高低水位控制

循环目标物料有专门的 `reserveMin/reserveTarget`。对于非循环的普通中间物，机器也需要防止上游节点在下游缺少其他原料时无限堆积。因此每个会被直接后继节点消耗的普通中间物也有运行期高低水位。

### 16.1 适用对象

普通中间物水位适用于：

```text
1. 当前节点输出的物料有直接后继消费者
2. 当前节点不是该物料的结束节点净输出
3. 该物料不是循环目标物料
```

### 16.2 自动估计

水位按生产者和直接消费者的有效并行估计：

```text
producerNeed = outputAmount(producer, m) × effectiveParallelLimit(producer)
consumerNeed = max(inputAmount(consumer, m) × effectiveParallelLimit(consumer))

lowWater(m) = max(producerNeed, consumerNeed, 1)
highWater(m) = max(lowWater(m) + 1, lowWater(m) × 3)
```

当内部缓存达到 `highWater` 后，上游节点暂停生产该中间物；缓存下降到 `lowWater` 或以下后，上游节点恢复。该机制只限制上游启动，不会删除已经生产的物料。

---

## 17. 调度器模型

### 17.1 调度器职责

调度器只负责决定每个调度周期中哪些节点先尝试启动。

调度器不负责：

```text
写回修改节点 P
写回修改节点 OC
动态缩放节点输入输出
重新配平
```

但调度器会使用有效运行参数，包括机器级并行倍率、机器级额外 OC 和本次可启动的 `actualParallel`。

### 17.2 调度目标

调度器目标为：

```text
优先消耗内部中间物
减少内部残留
优先推进靠近目标产物的节点
当下游缺料时运行上游节点补料
维护循环目标物料水位
避免生产过量中间物
```

### 17.3 推荐优先级

可以使用如下优先级：

```text
priority(node)
=
1000 * consumesInternalMaterial
+ 100  * closenessToTerminal
+ 50   * consumesOverstockedMaterial
+ 20   * producesNeededMaterial
- 20   * producesOverstockedMaterial
```

其中：

```text
consumesInternalMaterial:
    节点本次运行是否会消耗内部缓存

closenessToTerminal:
    节点是否靠近目标产物

consumesOverstockedMaterial:
    节点是否能消耗超过目标水位或目标缓存的物料

producesNeededMaterial:
    节点是否能生产下游当前缺少的物料

producesOverstockedMaterial:
    节点是否会继续生产已经过量的中间物
```

### 16.4 共享原料抢占规则

如果多个节点竞争同一种外部原料，则优先级高的节点先消耗。

尤其是如果后续节点能消耗内部中间物，则后续节点优先获得共享原料。

例如：

```text
配方 1：A + B = C
配方 2：A + C = D
```

当内部已有 C 时，A 应优先供给配方 2，而不是配方 1。

---

## 17. RUNNING 伪代码

```python
def tick_running_mode(machine):
    machine.machineRunnable = check_runtime_requirements(machine)

    if not machine.machineRunnable:
        return

    advance_running_jobs(machine)
    finish_completed_jobs(machine)

    candidates = []

    for node in machine.current_graph.nodes:
        if can_start_node(machine, node):
            score = compute_node_priority(machine, node)
            candidates.append((score, node))

    candidates.sort(reverse=True)

    for score, node in candidates:
        if can_start_node(machine, node):
            start_node(machine, node)
```

---

## 18. 节点启动伪代码

```python
def can_start_node(machine, node):
    p = node.parallel

    for input in node.inputs:
        material = input.material
        need = input.amount * p

        if get_available_for_node(machine, material) < need:
            return False

    return True
```

```python
def start_node(machine, node):
    p = node.parallel

    consumed = {}

    for input in node.inputs:
        material = input.material
        need = input.amount * p

        consumed[material] = consume_material(machine, material, need)

    job = RunningJob(
        nodeId=node.id,
        parallel=p,
        remainingTicks=node.duration,
        consumedInputs=consumed,
        energy=node.energy
    )

    machine.runningJobs.add(job)
```

---

## 19. 输入消耗伪代码

```python
def get_available_for_node(machine, material):
    internal = get_available_internal(machine, material)
    external = get_available_external_if_allowed(machine, material)

    return internal + external
```

```python
def get_available_internal(machine, material):
    amount = machine.internalBuffer[material]

    if material in machine.cyclicTerminalMaterials:
        reserveMin = machine.reserveMin[material]
        return max(0, amount - reserveMin)

    return amount
```

```python
def consume_material(machine, material, need):
    internalAvailable = get_available_internal(machine, material)

    usedInternal = min(internalAvailable, need)
    machine.internalBuffer.remove(material, usedInternal)
    need -= usedInternal

    usedExternal = 0
    if need > 0:
        usedExternal = machine.externalInputBuffer.remove(material, need)

    return {
        "internal": usedInternal,
        "external": usedExternal
    }
```

---

## 20. 输出路由伪代码

```python
def finish_job(machine, job):
    node = machine.current_graph.get_node(job.nodeId)
    p = job.parallel

    for output in node.outputs:
        material = output.material
        amount = output.amount * p

        route_output(machine, node, material, amount)
```

```python
def route_output(machine, node, material, amount):
    if material in machine.cyclicTerminalMaterials:
        machine.internalBuffer.add(material, amount)

        target = machine.reserveTarget[material]

        if machine.internalBuffer[material] > target:
            overflow = machine.internalBuffer[material] - target
            machine.internalBuffer.remove(material, overflow)
            machine.outputBuffer.add(material, overflow)

        return

    if machine.current_graph.has_direct_consumers(node, material):
        machine.internalBuffer.add(material, amount)
        return

    if machine.current_graph.is_terminal_output(node, material):
        machine.outputBuffer.add(material, amount)
        return

    machine.outputBuffer.add(material, amount)
```

---

## 21. 总体状态机伪代码

```python
def tick_machine(machine):
    if machine.mode == STANDBY:
        tick_standby(machine)
        return

    if machine.mode == OUTPUT:
        tick_output_mode(machine)
        return

    if machine.mode == INPUT:
        tick_input_mode(machine)
        return

    if machine.mode == RUNNING:
        tick_running_mode(machine)
        return
```

```python
def submit_graph(machine, graph):
    machine.pendingGraph = graph
    machine.mode = OUTPUT
```

---

## 22. 状态切换规则总结

### 22.1 提交工序

```text
任意状态
    ↓ 点击提交
pendingGraph = newGraph
mode = OUTPUT
```

### 22.2 输出模式完成

```text
OUTPUT 清空完成
    ↓
如果 pendingGraph 存在：
    设置 currentGraph
    构建提交需求
    mode = INPUT

否则：
    mode = STANDBY
```

### 22.3 输入模式完成

```text
INPUT 收集完成
    ↓
主机 -> controllerStorage
NC -> ncStorage
启动物料 -> internalBuffer
初始化循环水位
mode = RUNNING
```

### 22.4 运行模式停止

```text
RUNNING 中玩家停止或提交新工序
    ↓
mode = OUTPUT
```

---

## 23. 机器内部主要数据结构建议

### 23.1 MachineRuntime

```text
MachineRuntime {
    mode
    currentGraph
    pendingGraph

    requiredSubmitMaterials
    collectedSubmitMaterials

    controllerStorage
    ncStorage

    externalInputBuffer
    internalBuffer
    outputBuffer

    runningJobs

    cyclicTerminalMaterials
    reserveMin
    reserveTarget

    machineRunnable
}
```

### 23.2 RecipeNodeRuntime

```text
RecipeNodeRuntime {
    nodeId
    parallel
    duration
    energy
    requiredController
    ncItems
}
```

### 23.3 RunningJob

```text
RunningJob {
    nodeId
    parallel
    remainingTicks
    consumedInputs
    energy
}
```

### 23.4 MaterialBuffer

```text
MaterialBuffer {
    items
    fluids
}
```

物品和流体可以共用抽象接口，但保留不同单位和容量处理。

---

## 24. 原材料导出

工序管理界面提供“导出原材料”功能，用于把当前工序图需要从外部输入的原料写入 ME 存储输入舱室的标记槽。

### 24.1 导出对象

导出扫描整张工序图，计算所有不会被图内直接上游节点提供的输入物料。典型例子：

```text
铝矿石 -> 粉碎铝矿石 -> 洗净铝矿石 -> 洁净铝粉 -> 铝粉
```

该工序需要外部输入：

```text
铝矿石
蒸馏水
```

中间物不会被导出为外部原料。

### 24.2 ME 舱室规则

机器会尝试标记可用的存储输入总线/存储输入仓，以及兼容模组提供的等价 ME 存储输入舱室。导出前会检查可用标记位是否足够：

```text
标记位足够 -> 清空旧标记并写入新标记
标记位不足 -> 取消本次导出，不修改现有标记
未找到可用 ME 舱室 -> 在工序画布提示错误
```

如果存在物品与流体二合一的超级存储输入总成，优先使用总成；多个舱室按容量顺序依次写入。

### 24.3 矿典输入

矿典输入只作为匹配条件，机器不能替玩家决定具体标记哪一种矿典等价物。因此导出原材料时遇到矿典输入会跳过该项，并提示玩家到聊天栏查看需要手动标记的节点和物料。

---

## 25. 设计原则总结

本机器的核心设计原则如下：

```text
1. 工序图提交可以随时触发，但必须先进入输出模式清空旧状态。
2. 输出模式未清空前，不允许切换状态，也不允许设置新工序。
3. 每个节点独占一个多方块主机需求，相同主机需求按节点数量累计。
4. 主机和 NC 只是运行资格门槛，不参与每次配方执行。
5. 循环启动物料是真实运行物料，输入完成后进入内部缓存。
6. 运行模式不调用官方多方块主机配方执行路径，而是模拟执行虚拟节点任务。
7. 每个节点保存基础 OC、基础最大并行、耗时和耗能；机器级并行倍率和额外 OC 只形成运行时有效参数，不写回节点。
8. 节点并行是上限，本次输入不足时允许自动降低 actualParallel，但不能超过有效并行上限。
9. 节点启动时优先消耗内部缓存，再消耗外部输入。
10. 节点完成后按物料属性路由输出。
11. 循环目标物料采用自动估计水位控制。
12. 超过循环目标水位的部分才作为净终产物输出。
13. 普通中间物采用高低水位节流，避免上游在下游暂时不可运行时无限堆积。
14. 再次提交或玩家停止时，所有内部缓存、主机、NC 和运行任务都必须通过输出模式清空。
15. 断电停止时，已经被运行任务消耗的物料按普通 GT 机器语义损失，不通过输出模式返还。
```

---

## 26. 最终简述

该机器是一台工序图虚拟执行容器。玩家提交工序后，机器先进入输出模式清空旧状态，再进入输入模式收集每个节点独占所需的多方块主机、NC 物品，以及循环工序所需的启动物料。主机和 NC 只作为运行资格门槛，不参与每次配方执行；循环启动物料则进入内部缓存并实际参与运行。进入运行模式后，机器根据工序图节点生成虚拟运行任务，不调用官方多方块主机配方执行路径，而是由自身算法模拟节点有效并行、有效 OC、耗时、耗能下的输入消耗和输出产出。运行过程中机器维护外部输入、内部缓存和输出缓存，优先消耗内部中间物，并对循环目标物料和普通中间物执行水位控制，保证循环不断流且避免上游无限堆积。停止或再次提交工序时，机器必须进入输出模式并清空全部内部状态后，才能进入待机或切换到新工序。
