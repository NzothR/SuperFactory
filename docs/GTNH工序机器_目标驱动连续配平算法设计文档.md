# GTNH 工序机器：目标驱动连续配平算法设计文档

## 1. 文档目的

本文档定义工序机器的第二套配平算法：

```text
目标驱动连续配平
Target-Oriented Continuous Balance
```

该算法用于补充原有的“内部守恒配平”算法。

原有配平算法的核心目标是：

```text
尽可能使内部中间物生产速率 = 内部中间物消耗速率
```

新的目标驱动连续配平算法的核心目标是：

```text
以目标节点当前产出为锚点，
尽可能保持目标节点最小产出，
并让上游尽量满足目标节点输入需求，
让副产处理链尽量消耗主干副产，
从而提高目标节点连续运行概率。
```

该算法不追求全图所有中间物完全配平，也不保证所有非目标节点连续运行。  
该算法的最高优先级是：

```text
尽可能让目标节点连续运行。
```

---

## 2. 与原有配平算法的关系

工序机器保留两种配平模式：

```text
1. 内部守恒配平
2. 目标驱动连续配平
```

### 2.1 内部守恒配平

语义：

```text
对所有内部边，尽可能使上游生产速率等于下游消耗速率。
```

适合：

```text
线性合成链
严格化学方程式式工序
希望尽量无中间物残差的工序
```

### 2.2 目标驱动连续配平

语义：

```text
以目标节点为中心。
目标节点尽量不改变并行。
目标节点上游尽量满足目标节点输入需求。
目标节点与主干节点的副产处理链尽量消耗副产。
非目标节点允许间歇运行。
```

适合：

```text
多副产复杂链
多目标工序
存在目标节点后处理链的工序
存在局部单物料自增环的工序
希望目标产物尽量稳定输出的工序
```

---

## 3. 核心设计原则

目标驱动连续配平遵循以下原则：

```text
1. 目标节点是配平锚点。
2. 默认不改变目标节点 parallelLimit。
3. 优先保证目标节点理论输入需求被满足。
4. 上游节点按目标节点需求反向推导并行。
5. 副产处理链按主干副产速率正向推导并行。
6. 多目标共享上游时，同一物料需求累加。
7. 非目标节点允许间歇运行。
8. 目标节点也不是绝对不能间歇，但应尽可能避免。
9. 若目标节点当前并行不可满足，允许降低目标节点并行。
10. 若目标节点降到 1 仍不可行，则输出错误。
11. 普通环作为可提供净输出的虚拟生产单元参与配平。
12. 普通环内部配平忽略循环物料，只处理其他需求。
13. 含目标节点的环需要满足额外限制。
14. 配平结果只推荐 parallelLimit，不修改 OC、机器全局倍率或配方本身。
```

---

## 4. 基础术语

| 术语 | 含义 |
|---|---|
| 目标节点 Target Node | 标记目标产物的节点 |
| 目标主干 Target Trunk | 为目标节点供料的上游主链区域 |
| 共享主干 Shared Trunk | 被多个目标节点共同依赖的上游区域 |
| 副产处理链 Byproduct Disposal Branch | 从主干节点非主干输出边出发的后处理分支 |
| 普通环 Normal Cycle | 不含目标节点的局部单物料自增环 |
| 目标环 Target Cycle | 含目标节点的局部单物料自增环 |
| 循环物料 Cycle Material | 环内唯一参与回流并自增的物料 |
| 环净输出 Cycle Net Output | 循环物料生产速率减去消耗速率后的正净产出 |
| 需求速率 Demand Rate | 某节点或区域为了运行需要的输入速率 |
| 供给速率 Supply Rate | 某节点或区域能够提供的输出速率 |
| 推荐并行 Recommended Parallel | 算法计算出的 parallelLimit 推荐值 |

---

## 5. 输入与输出

### 5.1 输入

算法输入：

```text
ProcessGraph
GraphAnalysisResult
当前各节点 parallelLimit
当前各节点 duration
当前各节点输入输出
目标节点集合
边关系
SCC / 环分析结果
当前节点类型信息
```

### 5.2 输出

算法输出：

```text
BalanceResult {
    success
    recommendedParallelByNode
    reducedTargetNodes
    unresolvedTargets
    byproductResiduals
    warnings
    errors
}
```

### 5.3 算法不修改的内容

目标驱动连续配平不修改：

```text
节点 OC
机器全局并行倍率
机器额外 OC
配方输入输出
节点是否为目标节点
图结构
边结构
NC / 主机需求
```

它只推荐：

```text
parallelLimit
```

---

## 6. 速率模型

对于节点 `i`、物料 `m`：

```text
produceRate(i, m) = outputAmount(i, m) × P_i / duration_i
consumeRate(i, m) = inputAmount(i, m) × P_i / duration_i
```

其中：

```text
P_i = 节点 parallelLimit
duration_i = 节点当前基础耗时
```

如果是概率产物：

```text
expectedOutputAmount = stackAmount × chance / 10000
```

配平算法使用期望值。

---

## 7. 目标节点语义

目标节点是该算法的锚点。

目标节点默认使用当前 parallelLimit：

```text
P_target = currentParallelLimit(target)
```

算法优先不改变目标节点。

只有在以下情况才允许降低目标节点并行：

```text
1. 上游无法在整数并行范围内满足目标节点输入需求。
2. 目标节点副产处理链在当前目标并行下完全不可行。
3. 多目标共享上游导致无法同时满足所有目标节点。
```

降低目标节点时：

```text
只允许降低，不主动放大。
最低降低到 1。
降到 1 仍不可行则报错。
```

---

## 8. 目标节点后接目标节点的限制

目标驱动连续配平不支持以下结构：

```text
普通节点 -> 目标节点 A -> 副产处理节点 -> 目标节点 B
```

即：

```text
任一目标节点的下游可达区域中，不允许出现另一个目标节点。
```

原因：

```text
目标节点下游区域在该算法中被解释为副产处理链。
如果该区域中又出现另一个目标节点，
则该分支同时具有“副产处理”和“目标主链上游”两种语义，
会破坏算法分区假设。
```

处理方式：

```text
直接拒绝目标驱动连续配平。
提示用户使用内部守恒配平，或拆分工序图。
```

错误码建议：

```text
TARGET_DOWNSTREAM_REACHES_ANOTHER_TARGET
```

---

## 9. 图分区

目标驱动连续配平在计算前先对图进行分区。

### 9.1 TargetTrunkRegion

目标主干区域。

定义：

```text
从目标节点输入反向追溯到源头节点，
所有用于供给目标节点输入的节点和边组成目标主干。
```

如果多个目标节点共享上游节点，则共享部分属于共享主干。

主干语义：

```text
为目标节点提供运行输入。
```

### 9.2 ByproductDisposalRegion

副产处理区域。

定义：

```text
从目标主干节点的非主干输出边出发，
沿下游边可达，
且不经过任何目标节点的子图。
```

副产处理区域可以来自：

```text
目标节点的副产输出
目标节点上游普通主干节点的副产输出
共享上游节点的副产输出
普通环净输出之外的副产输出
```

副产处理语义：

```text
以最小并行尽可能消耗入口副产速率。
```

### 9.3 UnsupportedTargetCrossRegion

不支持的目标交叉区域。

定义：

```text
从某个目标节点的下游区域出发能够到达另一个目标节点。
```

该区域在新算法中非法。

---

## 10. 目标主干提取

对每个目标节点 `T`：

```text
1. 找到 T 的所有输入边。
2. 从这些输入边的源节点开始反向遍历。
3. 所有能向 T 提供输入的节点和边加入 MainTrunk(T)。
4. 遍历过程中如果遇到另一个目标节点，则标记为目标交叉错误。
```

多目标时：

```text
MainTrunk = union(MainTrunk(T1), MainTrunk(T2), ...)
```

共享上游节点保留为同一个节点，不复制。

---

## 11. 副产处理链提取

对每个主干节点 `u`：

```text
1. 遍历 u 的所有输出边。
2. 若该边属于目标主干边，则跳过。
3. 若该边指向的下游区域不包含目标节点，则该边开启一个副产处理链。
4. 沿该边正向遍历，将所有可达非目标节点加入副产处理区域。
5. 若正向遍历遇到目标节点，则报错。
```

这样副产处理链不仅包括目标节点下游，还包括所有目标主干节点的旁路副产链。

---

## 12. 目标主干反向需求传播

### 12.1 初始需求

对每个目标节点 `T`，使用当前目标节点并行计算输入需求：

```text
targetInputDemand(T, m)
=
inputAmount(T, m) × P_T / duration_T
```

其中：

```text
P_T = 当前目标节点 parallelLimit
```

---

### 12.2 向上游传播

若节点 `v` 对物料 `m` 有需求速率：

```text
needRate(v, m)
```

其上游节点 `u` 通过边 `u -> v` 生产 `m`，则要求：

```text
produceRate(u, m) >= needRate(v, m)
```

展开：

```text
outputAmount(u, m) × P_u / duration_u >= needRate(v, m)
```

因此：

```text
P_u >= ceil(needRate(v, m) × duration_u / outputAmount(u, m))
```

若同一上游节点 `u` 的同一物料 `m` 被多个下游节点需求，则需求合并：

```text
requiredRate(u, m) = Σ needRate(edge u -> consumer, m)
```

若同一节点有多个输出物料都被需求约束，则：

```text
P_u = max(P_u_required_by_each_output)
```

计算出 `P_u` 后，节点 `u` 自身输入需求继续向其上游传播：

```text
inputNeedRate(u, x)
=
inputAmount(u, x) × P_u / duration_u
```

---

## 13. 共享上游需求合并

如果多个目标节点共享上游节点，则对共享节点的需求必须合并。

例如：

```text
目标 A 需要 X: 10/s
目标 B 需要 X: 6/s
上游 U 生产 X
```

则：

```text
requiredRate(U, X) = 16/s
```

合并规则：

```text
同一上游节点
同一输出物料
来自多个下游的需求速率累加
```

不允许自动跨边重分配。

也就是说：

```text
配平只根据显式边计算需求。
如果玩家希望另一条上游供给某目标输入，需要在图中显式连边。
```

---

## 14. 副产处理正向需求传播

### 14.1 副产入口速率

对于主干节点 `u` 的非主干输出物料 `b`，其副产入口速率为：

```text
byproductRate(u, b)
=
outputAmount(u, b) × P_u / duration_u
```

其中 `P_u` 来自主干反向需求传播的推荐并行。

### 14.2 下游处理节点并行

若副产处理节点 `d` 消耗副产 `b`，则要求：

```text
consumeRate(d, b) >= byproductRate(u, b)
```

展开：

```text
inputAmount(d, b) × P_d / duration_d >= byproductRate(u, b)
```

因此：

```text
P_d >= ceil(byproductRate(u, b) × duration_d / inputAmount(d, b))
```

若一个副产处理节点同时需要处理多个入口副产，则：

```text
P_d = max(P_d_required_by_each_input)
```

### 14.3 继续向下游传播

副产处理节点运行后可能产生新的输出。

若输出继续被下游消费，则继续正向传播处理需求。

若输出无人消费，则作为副产物输出。

### 14.4 副产处理无法完全处理

如果副产处理链无法完全消耗副产：

```text
允许副产过量输出。
允许副产处理链间歇运行。
```

只有当副产处理链完全不可行，且该副产来自目标节点本身时，才允许尝试降低目标节点并行。

---

## 15. 目标节点降级策略

目标节点默认不变，但允许降级。

### 15.1 降级触发条件

允许降低目标节点并行的情况：

```text
1. 上游无法在整数范围内满足目标节点输入需求。
2. 目标节点自身副产处理链完全不可行。
3. 多目标共享上游导致无法满足所有目标节点。
```

### 15.2 降级规则

```text
只允许降低目标节点 parallelLimit。
不主动放大目标节点。
最低降到 1。
降到 1 仍不可行则报错。
```

### 15.3 多目标降级策略

第一版推荐简单策略：

```text
1. 所有目标节点默认同优先级。
2. 先尝试保持所有目标节点当前 parallelLimit。
3. 若失败，按稳定顺序逐个降低目标节点 parallelLimit。
4. 每次降低后重新执行主干需求传播与副产处理传播。
5. 直到可行或所有相关目标节点都降到 1。
```

后续可扩展：

```text
targetPriority
allowAutoReduce
minTargetParallel
```

---

## 16. 非目标节点间歇语义

该算法不保证非目标节点连续运行。

允许间歇的节点包括：

```text
目标节点上游节点
共享上游节点
副产处理链节点
普通环内部节点
普通源头节点
```

算法目标是：

```text
尽可能让目标节点不间歇。
```

如果无法保证所有目标节点连续运行，则允许部分目标节点间歇。

此时算法应输出 warning：

```text
TARGET_CONTINUITY_NOT_FULLY_GUARANTEED
```

---

## 17. 普通环处理

普通环指：

```text
不含目标节点的局部单物料自增环。
```

### 17.1 普通环作为目标上游

如果普通环位于目标节点上游，则该环被视为一个可提供净输出的虚拟生产单元。

配平时使用：

```text
cycleNetOutputRate(C)
```

作为该环对外可供应速率。

若目标主干需要物料 `C`：

```text
cycleNetOutputRate(C) >= downstreamDemand(C)
```

若当前环净输出不足，则需要提高环内部相关非循环物料节点的并行，或者提示不可满足。

---

### 17.2 普通环内部配平

普通环内部配平遵循：

```text
最小满足下游需求。
```

但计算时忽略循环物料本身的内部平衡约束。

原因：

```text
图合法性检查已经要求该环是单循环物料正净输出环。
cycleMaterial 一定自增。
因此配平算法不需要再让 cycleMaterial 内部生产速率等于消耗速率。
```

环内部配平只计算：

```text
除 cycleMaterial 之外的其他输入 / 输出需求。
```

如果这些非循环需求满足，则环会自然产生正净输出。

### 17.3 普通环对外需求

如果环净输出进入目标主干：

```text
按目标主干需求反推环所需规模。
```

如果环净输出进入副产处理链：

```text
按副产处理链需求计算。
```

如果环独立存在且不影响目标节点：

```text
保持当前 parallelLimit，不强制调整。
```

---

## 18. 含目标节点的环处理

含目标节点的环需要额外限制。

### 18.1 合法性限制

如果 SCC 含目标节点，则必须满足：

```text
1. 该 SCC 只能含一个目标节点。
2. 目标节点必须生产 cycleMaterial。
3. 目标节点不得消费 cycleMaterial。
4. cycleMaterial 不允许有 SCC 外消费者。
5. cycleMaterial 一定视为目标产物。
6. 目标节点的非 cycleMaterial 输出可以连接 SCC 外副产处理链。
```

这等价于要求：

```text
目标节点作为循环物料的生产起点。
```

### 18.2 cycleMaterial 处理

含目标节点的环中：

```text
cycleMaterial 不作为普通副产处理对象。
cycleMaterial 不参与环外下游消费。
cycleMaterial 的净输出直接作为目标产物。
```

### 18.3 目标节点副产处理

目标节点除 cycleMaterial 之外的其他输出：

```text
如果接入 SCC 外下游，则视为目标节点副产处理链。
按副产处理正向需求传播处理。
```

### 18.4 环内部配平

含目标节点的环内部配平仍然忽略 cycleMaterial 平衡约束。

只处理：

```text
除 cycleMaterial 外的其他内部需求
目标节点输入需求
环内普通节点输入需求
```

目标节点 parallelLimit 仍优先保持不变。

若无法满足，则允许降低该目标节点 parallelLimit。

---

## 19. 错误与警告

### 19.1 错误

**设计建议错误码（本文档为设计稿，源码中的实际错误码以 SccCycleAnalyzer 为准）：**

源码当前已实现的环检查错误码：

```text
CYCLE_MULTI_MATERIAL
环中循环物料数量不为 1。

CYCLE_NON_POSITIVE_NET
循环物料在当前参数下不具备正净输出。

TARGET_CYCLE_MULTIPLE_TARGETS
目标环包含多个目标节点。

TARGET_CYCLE_TARGET_NOT_INTERNAL_OUTPUT
目标节点未将循环物料作为环内输出。

TARGET_CYCLE_TARGET_NON_POSITIVE_NET
目标节点非正净产出循环物料。

TARGET_CYCLE_EXTERNAL_CONSUMER
目标环的循环物料被环外节点消费。

CYCLE_SHARED_MATERIAL
多个环共享同一循环物料（当前不支持）。
```

设计建议扩展的错误码（尚未在源码中实现）：

```text
TARGET_DOWNSTREAM_REACHES_ANOTHER_TARGET
目标节点的下游后处理区域包含另一个目标节点。

TARGET_PARALLEL_CANNOT_BE_SATISFIED
目标节点并行降到 1 后，上游仍无法满足输入需求。

TARGET_BYPRODUCT_CANNOT_BE_DISPOSED
目标节点并行降到 1 后，其必要副产处理链仍完全不可行。

UNSUPPORTED_TARGET_CROSS_REGION
多个目标节点之间存在无法分离的目标交叉区域。
```

### 19.2 警告

建议 warning：

```text
TARGET_PARALLEL_REDUCED
目标节点 parallelLimit 被降低。

TARGET_CONTINUITY_NOT_FULLY_GUARANTEED
无法保证所有目标节点连续运行。

BYPRODUCT_OVERFLOW_EXPECTED
副产处理链无法完全消耗副产，预计会有副产外排。

NON_TARGET_NODE_MAY_IDLE
非目标节点可能间歇运行。

CYCLE_USED_AS_NET_SUPPLIER
普通环被作为净输出供应单元参与配平。
```

---

## 20. 算法流程总览

```text
1. 图分析
   解析目标节点、边、SCC、目标产物、副产处理分支。

2. 基础合法性检查
   检查目标节点、边、环、cycleMaterial、目标交叉区域。

3. 初始化目标节点并行
   默认使用当前 parallelLimit。

4. 目标交叉检查
   若目标节点下游可达另一个目标节点，则失败。

5. 提取目标主干
   从所有目标节点反向追溯输入来源。

6. 合并共享上游需求
   多目标共享同一上游物料时，需求速率累加。

7. 反向传播目标输入需求
   计算主干节点推荐并行。

8. 提取副产处理链
   从主干节点非主干输出边正向追溯。

9. 正向传播副产处理需求
   计算副产处理链节点推荐并行。

10. 处理普通环
    普通环作为净输出供应单元。
    环内部忽略 cycleMaterial，只计算其他需求。

11. 处理含目标节点的环
    应用目标环限制。
    cycleMaterial 净输出作为目标产物。

12. 可行性检查
    若目标输入或必要副产处理不可行，尝试降低目标节点并行。

13. 输出推荐 parallelLimit
    并输出 warning / error 信息。
```

---

## 21. 伪代码

```python
def target_oriented_balance(graph):
    analysis = analyze_graph(graph)

    validation = validate_for_target_oriented_balance(analysis)
    if not validation.success:
        return BalanceResult.failure(validation.errors)

    target_parallel = {
        t.id: t.parallelLimit
        for t in analysis.targetNodes
    }

    while True:
        result = try_balance_with_target_parallel(graph, analysis, target_parallel)

        if result.success:
            return result

        reducible = find_reducible_target(result, target_parallel)

        if reducible is None:
            return BalanceResult.failure(result.errors)

        target_parallel[reducible.id] -= 1

        if target_parallel[reducible.id] < 1:
            return BalanceResult.failure([
                "TARGET_PARALLEL_CANNOT_BE_SATISFIED"
            ])
```

```python
def try_balance_with_target_parallel(graph, analysis, target_parallel):
    trunk = extract_target_trunks(analysis, target_parallel)

    if trunk.has_target_cross_region:
        return failure("TARGET_DOWNSTREAM_REACHES_ANOTHER_TARGET")

    demand_map = new_demand_map()

    for target in analysis.targetNodes:
        add_target_input_demands(demand_map, target, target_parallel[target.id])

    recommended = {}

    propagate_upstream_demands(
        analysis=analysis,
        demand_map=demand_map,
        recommended=recommended
    )

    byproduct_regions = extract_byproduct_disposal_regions(
        analysis=analysis,
        trunk=trunk
    )

    propagate_byproduct_demands(
        analysis=analysis,
        byproduct_regions=byproduct_regions,
        recommended=recommended
    )

    handle_normal_cycles(
        analysis=analysis,
        demand_map=demand_map,
        recommended=recommended
    )

    handle_target_cycles(
        analysis=analysis,
        target_parallel=target_parallel,
        recommended=recommended
    )

    feasibility = check_feasibility(analysis, recommended)

    if not feasibility.success:
        return failure(feasibility.errors)

    return success(recommended)
```

---

## 22. 与运行器水位的关系

目标驱动连续配平不直接使用运行器水位数值。

原因：

```text
水位是在 parallelLimit、duration、消费者需求确定之后计算出来的。
配平算法本身正是在推荐 parallelLimit。
因此配平阶段不能依赖最终水位。
```

新算法的作用是：

```text
让上游理论供给速率尽量接近目标节点理论需求速率，
让副产处理链理论处理速率尽量接近入口副产速率，
从而降低运行时超过水位、停机等待、波形生产的概率。
```

---

## 23. GUI 建议

配平工具界面建议提供两个按钮：

```text
内部守恒配平
目标驱动连续配平
```

目标驱动连续配平结果展示：

```text
目标节点并行是否被降低
哪些主干节点被调整
哪些副产处理节点被调整
哪些普通环作为净输出供应单元
预计副产外排
无法保证连续运行的目标节点
错误与警告信息
```

---

## 24. 设计总结

目标驱动连续配平是一套以目标节点为中心的启发式配平算法。

它不是为了让所有内部边完全守恒，而是为了：

```text
1. 尽可能保持目标节点当前 parallelLimit。
2. 尽可能让目标节点输入需求被上游满足。
3. 尽可能让目标节点连续运行。
4. 尽可能用最小并行处理目标主干产生的副产。
5. 允许非目标节点间歇。
6. 允许副产过量外排。
7. 对普通环使用环净输出作为供应能力。
8. 对普通环内部计算忽略 cycleMaterial，只处理其他需求。
9. 对含目标节点的环施加更严格限制。
```

最终形成两种互补配平模式：

```text
内部守恒配平：
    适合追求内部物料速率完全平衡。

目标驱动连续配平：
    适合追求目标产物稳定、最小、连续输出。
```
