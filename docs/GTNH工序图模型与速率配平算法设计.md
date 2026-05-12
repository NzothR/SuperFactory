# GTNH 工序图模型与速率配平算法设计

## 1. 文档目的

本文档用于描述一个面向 GTNH 配方链的工序图模型，以及基于该模型的速率配平算法。

该模型用于表示一组 GTNH 配方之间的物料流转关系。每个节点代表一个配方，节点之间通过有向边连接，表示上一节点的某些产物被下一节点作为输入消耗。整张图可以看作一个完整工序，类似现实工业中的化学合成链、矿物处理链、石油化工链、回收链或副产物综合利用链。

配平算法的目标不是传统化学方程式意义上的单次反应配平，而是基于单位时间速率的工艺配平。算法通过调整各个配方节点的并行数 `P`，使图中所有被显式连接的内部物料满足生产速率与消耗速率一致。

---

## 2. 基本概念

### 2.1 工序图

一张工序图表示一套完整生产工艺。

形式化表示为：

```text
G = (V, E, T)
```

其中：

```text
V = 配方节点集合
E = 物料流有向边集合
T = 结束节点集合
```

工序图具有明确目的：获取一个或多个结束节点的产物。

一张图可以表示：

```text
原料输入 → 中间加工 → 分流处理 → 回收/循环 → 终产物输出 + 副产物输出
```

该图不是全局配方网络，而是一个被封装出来的局部工艺单元。

---

## 3. 节点模型

### 3.1 节点定义

每个节点代表一个 GTNH 配方。

一个节点至少包含以下信息：

```text
节点 ID
节点类型
输入物料列表
输出物料列表
基础耗时
OC 后耗时
耗能状态
OC 超频状态
P 并行数
```

可以抽象为：

```text
RecipeNode {
    id
    type
    inputs
    outputs
    duration
    energy
    oc
    parallel
}
```

其中：

```text
duration = 当前 OC 状态下的实际耗时
parallel = 当前节点并行数 P
```

配平算法只直接调整 `parallel`，不主动改变节点的 OC 状态。

---

### 3.2 节点类型

节点分为两类：

```text
普通节点
结束节点
```

---

### 3.3 普通节点

普通节点表示工序中的中间加工步骤。

普通节点的产物可以有三种去向：

```text
1. 作为直接后继普通节点的输入
2. 作为直接后继结束节点的输入
3. 不被直接后继节点消耗，作为副产物输出
```

普通节点不是该图的最终目的，而是服务于结束节点产物的中间工艺步骤。

---

### 3.4 结束节点

结束节点是当前工序图的真实终点。

一张图的目的就是为了获得结束节点的产物。

结束节点不是“可继续展开的目标节点”，而是这张工序图内部定义的最终产物节点。

一张普通非循环图可以有多个结束节点，例如：

```text
铂族金属处理链 → 铂粉
铂族金属处理链 → 钯粉
铂族金属处理链 → 铑粉
```

多个结束节点表示这张图的目标是同时获得多个终产物。

---

## 4. 配方物料表达

GTNH 配方可以近似看作化学方程式：

```text
1A + 2B + 2L C = 1D + 3E + 2L F
```

其中：

```text
A, B, D, E = 物品
C, F = 流体
L = 流体单位
```

在算法层，可以统一抽象为：

```text
MaterialAmount {
    material
    amount
    type
}
```

其中 `type` 可区分：

```text
ITEM
FLUID
```

物品和流体都参与速率配平，只是单位展示不同：

```text
物品：个/s、个/t
流体：L/s、L/t
```

---

## 5. 边模型

### 5.1 边的含义

边是有向边：

```text
u -> v
```

表示：

```text
节点 u 的某个产物被节点 v 作为输入消耗
```

边不是抽象执行顺序，而是明确的物料流关系。

一条边应至少包含：

```text
源节点 ID
目标节点 ID
物料 ID
物料类型
源节点输出物料
目标节点输入物料
```

可以抽象为：

```text
MaterialEdge {
    from
    to
    material
}
```

---

### 5.2 直接相邻规则

只有通过边直接连接的节点，才被视为直接相邻节点。

对于节点 `u`，其直接后继节点集合为：

```text
Succ(u) = { v | 存在边 u -> v }
```

某个物料是否被继续用于工序，只看它是否被直接后继节点消耗。

---

### 5.3 一对多连接

一个配方可以产生多个产物，这些产物可以被多个后续配方同时利用。

因此允许一对多连接：

```text
A -> B
A -> C
A -> D
```

这表示：

```text
A 的不同产物，或同一产物的不同流量，被多个后继节点作为输入消耗
```

一对多连接可以发生在：

```text
普通节点 -> 普通节点
普通节点 -> 结束节点
```

例如：

```text
原油蒸馏节点
  ├─ 石脑油 -> 裂化节点
  ├─ 柴油 -> 燃料处理节点
  └─ 重油 -> 蒸汽裂化节点
```

---

## 6. 副产物规则

### 6.1 副产物定义

对于普通节点 `u` 的某个输出物料 `m`：

如果不存在任何直接后继节点通过边消耗该物料，则 `m` 是副产物。

形式化表示：

```text
m ∈ Byproduct(u)
当且仅当：
m ∈ Outputs(u)
且不存在边 e = (u, v, m)
```

也就是：

```text
副产物判断只看是否作为下一直接相邻节点的输入
```

---

### 6.2 不按全图后续使用判断副产物

即使某个物料会被更后面的节点用到，只要它没有通过当前节点到目标节点的直接边连接，它仍然被当前节点判定为副产物。

例如：

```text
A -> B -> C
```

如果 `A` 产出 `x`，但是 `A.x` 没有作为 `B` 的输入，则对节点 `A` 而言：

```text
x 是副产物
```

即使节点 `C` 理论上也需要 `x`。

因此，图结构必须显式表达物料流向，不能依靠后续节点的输入需求隐式推断。

---

### 6.3 部分消耗与剩余副产

如果某个节点输出的某种物料被直接后继部分消耗，而生产速率大于直接消耗速率，则剩余部分也可以作为副产物输出。

对于节点 `u` 的物料 `m`：

```text
surplus_rate(u, m)
=
produce_rate(u, m)
-
sum_direct_consume_rate(u, m)
```

如果：

```text
surplus_rate(u, m) > 0
```

则剩余部分作为副产物流速输出。

---

### 6.4 图输出

整张图的输出包含两部分：

```text
1. 结束节点产物
2. 普通节点副产物
```

形式化表示：

```text
GraphOutput = TerminalOutput + ByproductOutput
```

其中：

```text
TerminalOutput = 所有结束节点的输出物料
ByproductOutput = 所有普通节点未被直接后继消耗的输出物料或剩余物料
```

---

## 7. 图结构规则

### 7.1 普通非循环图

普通非循环图不包含环。

它允许多个结束节点：

```text
|T| >= 1
```

该类型适合表示：

```text
矿物综合处理链
石油分馏链
铂族金属处理链
稀土分离链
副产物综合处理链
```

---

### 7.2 循环图

循环图允许存在大循环，也就是某些中间产物或回收产物可以回流到前序节点继续参与生产。

循环图用于表示：

```text
催化剂回收链
化工回流链
增殖链
闭合处理链
```

循环图必须满足：

```text
|T| = 1
```

也就是说，循环图只能有一个结束节点。

---

### 7.3 大循环

大循环是服务于唯一结束节点的增值回流结构。

其语义是：

```text
部分产物回流到前序步骤
但整体工序仍然围绕唯一目标产物产生净输出
```

例如：

```text
外部输入 -> A -> B -> C -> 结束节点
             ↑         ↓
             └─────────┘
```

这种循环是合法的，只要它是整个工序的一部分，并且服务于最终目标产物。

---

### 7.4 小环

小环是非法结构。

小环通常表现为：

```text
A -> B -> A
```

或者：

```text
A -> B -> C -> A
```

但它没有明确服务于唯一结束节点，也不能解释为工艺中必要的增值回流。

小环会导致：

```text
配方展开无限递归
成本计算不收敛
终点语义不明确
内部依赖混乱
```

因此工序图不允许小环。

---

## 8. 速率模型

### 8.1 生产速率

对于节点 `v`，物料 `m`：

```text
produce_rate(v, m) = output_amount(v, m) * P_v / duration_v
```

其中：

```text
output_amount(v, m) = 节点 v 单次配方输出物料 m 的数量
P_v = 节点 v 的并行数
duration_v = 节点 v 当前 OC 后耗时
```

---

### 8.2 消耗速率

对于节点 `v`，物料 `m`：

```text
consume_rate(v, m) = input_amount(v, m) * P_v / duration_v
```

其中：

```text
input_amount(v, m) = 节点 v 单次配方输入物料 m 的数量
```

---

### 8.3 时间单位

算法内部建议统一使用 tick。

GTNH 中：

```text
20 tick = 1 second
```

如果使用 tick，则速率为：

```text
rate_per_tick = amount * P / duration_ticks
```

如果展示为每秒：

```text
rate_per_second = amount * P * 20 / duration_ticks
```

---

## 9. 配平问题定义

### 9.1 配平目标

配平算法的最终目标是：

```text
使内部相连节点之间的物料生产速率与消耗速率一致
```

也就是说，所有被显式边连接的内部物料都要满足速率守恒。

配平算法通过调整每个节点的并行数 `P` 实现这一目标。

---

### 9.2 并行数约束

并行数满足：

```text
1 <= P_i <= Int.MAX_VALUE
P_i ∈ Integer
```

其中 `P_i` 是节点 `i` 的并行数。

本算法不考虑：

```text
机器最大并行
输出仓容量
输入仓容量
电压限制
电流限制
多方块实际运行限制
```

这些由玩家或后续运行校验处理。

---

### 9.3 OC 与 P 的关系

配平算法假设每个节点的 OC 状态已经确定。

因此每个节点的实际耗时 `duration` 已知。

算法只调整：

```text
P
```

不调整：

```text
OC
机器类型
配方本身
```

如果需要同时优化 OC 和 P，则会变成更复杂的组合优化问题，不属于本文档当前算法范围。

---

## 10. 内部物料配平约束

对于普通节点 `u` 的某个输出物料 `m`，找到所有直接消耗该物料的后继节点：

```text
Consumers(u, m) = { v | 存在边 u -> v，且 v 直接消耗 m }
```

如果：

```text
Consumers(u, m) = ∅
```

则该物料是副产物，不参与内部配平。

如果：

```text
Consumers(u, m) ≠ ∅
```

则建立配平约束：

```text
produce_rate(u, m)
=
Σ consume_rate(v, m), v ∈ Consumers(u, m)
```

展开为：

```text
output_amount(u, m) * P_u / duration_u
=
Σ input_amount(v, m) * P_v / duration_v
```

这是整个配平算法的核心公式。

---

## 11. 方程组形式

将所有节点的并行数表示为向量：

```text
P = [P_1, P_2, ..., P_n]
```

每条配平约束可以写成一条线性方程：

```text
a_1P_1 + a_2P_2 + ... + a_nP_n = 0
```

最终得到：

```text
A * P = 0
```

约束为：

```text
1 <= P_i <= Int.MAX_VALUE
P_i ∈ Integer
```

算法目标是寻找一个正整数解。

---

## 12. 目标函数

### 12.1 总体目标

严格来说，配平算法首先要保证：

```text
内部物料速率守恒
```

在此基础上，再尝试让整体输出规模尽可能小。

但是不要求数学意义上的绝对最小终产物输出。

---

### 12.2 单结束节点图

对于单结束节点图，算法目标可以理解为：

```text
在内部速率配平的前提下，使结束节点输出尽量小
```

但不要求保证绝对最优。

实践中可以使用近似目标：

```text
minimize ΣP_i
```

---

### 12.3 多结束节点图

对于多结束节点图，不要求每个结束节点都达到独立最小输出。

而是要求：

```text
总体结束节点输出近似最小
```

可以使用以下目标之一：

```text
minimize Σ terminal_output_rate
```

或更简单地使用：

```text
minimize ΣP_i
```

推荐默认使用：

```text
minimize ΣP_i
```

因为它实现简单，并且通常能够得到足够小的整体工序规模。

---

### 12.4 循环图

对于单结束节点循环图，也不要求求出绝对最小净输出。

循环图的主要目标是：

```text
内部循环物料速率配平
唯一结束节点有正输出
整体 P 尽量小
```

同样可以使用：

```text
minimize ΣP_i
```

作为近似目标。

---

## 13. 算法流程

### 13.1 总体流程

算法分为以下步骤：

```text
1. 校验工序图结构
2. 固定每个节点的 OC 后耗时
3. 构建内部物料速率方程
4. 求解正整数并行数 P
5. 写回节点并行数
6. 计算边流速
7. 计算终产物输出速率
8. 计算副产物输出速率
9. 输出配平结果
```

---

### 13.2 图结构校验

需要检查：

```text
1. 至少存在一个结束节点
2. 所有边引用的源节点和目标节点存在
3. 所有边引用的物料确实是源节点输出、目标节点输入
4. 如果图存在循环，则结束节点数量必须为 1
5. 图中不允许非法小环
```

如果图存在循环且结束节点数量大于 1，则报错：

```text
循环图只能有一个结束节点
```

---

### 13.3 构建方程

遍历所有普通节点的输出物料。

对于每个 `(u, m)`：

```text
u = 当前普通节点
m = u 的某个输出物料
```

查找所有直接消费者：

```text
Consumers(u, m)
```

如果为空，则该物料是副产物，不建立方程。

如果非空，则建立方程：

```text
output_amount(u, m) * P_u / duration_u
-
Σ input_amount(v, m) * P_v / duration_v
=
0
```

---

### 13.4 整数化处理

为了避免浮点误差，建议将方程整数化。

对于一条约束中涉及的所有节点耗时：

```text
duration_u, duration_v1, duration_v2, ...
```

取最小公倍数：

```text
L = lcm(duration_u, duration_v1, duration_v2, ...)
```

则方程：

```text
output_amount(u, m) * P_u / duration_u
=
Σ input_amount(v, m) * P_v / duration_v
```

可以整数化为：

```text
output_amount(u, m) * (L / duration_u) * P_u
-
Σ input_amount(v, m) * (L / duration_v) * P_v
=
0
```

这样矩阵 `A` 中的系数均为整数。

---

## 14. 求解策略

### 14.1 推荐策略：整数线性求解

将问题表达为：

```text
find P

subject to:
A * P = 0
1 <= P_i <= Int.MAX_VALUE
P_i ∈ Integer

objective:
minimize ΣP_i
```

该方式可以统一支持：

```text
普通 DAG
一对多分流图
多结束节点图
单结束节点循环图
复杂比例工艺图
```

可以使用：

```text
OR-Tools CP-SAT
整数线性规划求解器
自定义整数方程求解器
```

---

### 14.2 简化策略：有理数零空间 + 整数化

对于结构较简单的图，可以使用：

```text
1. 构建有理数矩阵 A
2. 求解 A 的零空间
3. 选择一个所有分量为正的方向
4. 将有理数向量乘以所有分母的 LCM
5. 再除以所有分量的 GCD
6. 得到一组较小的正整数 P
```

该策略适合：

```text
单主链
简单 DAG
简单一对多结构
简单循环结构
```

如果图存在多个自由度，则需要额外选择策略，例如最小化 `ΣP_i`。

---

### 14.3 多自由度图处理

如果方程组有多个可行解，则不要求求绝对最优。

可以使用以下近似策略：

```text
优先求任意正整数可行解
然后尝试缩小 ΣP_i
如果无法严格最小化，则接受较小的可行解
```

默认选择：

```text
ΣP_i 较小的解
```

即可满足“总体输出近似最小”的需求。

---

## 15. 伪代码

```python
def balance_process_graph(graph):
    # 1. 基础校验
    validate_graph_basic(graph)

    # 2. 循环图规则校验
    if graph.has_cycle():
        if len(graph.terminal_nodes) != 1:
            raise Error("循环图只能有一个结束节点")

        validate_no_invalid_small_cycle(graph)

    # 3. 确保每个节点已经有 OC 后耗时
    for node in graph.nodes:
        if node.duration <= 0:
            raise Error("节点耗时必须大于 0")

    # 4. 构建线性方程组
    equations = []

    for u in graph.normal_nodes:
        for m in u.outputs:
            consumers = graph.direct_consumers(u, m)

            if not consumers:
                # 无直接后继消耗，作为副产物
                continue

            equation = Equation()

            # 生产项：output(u,m) * P_u / duration_u
            equation.add(
                node=u,
                coefficient=Fraction(
                    u.output_amount(m),
                    u.duration
                )
            )

            # 消耗项：- input(v,m) * P_v / duration_v
            for v in consumers:
                equation.add(
                    node=v,
                    coefficient=Fraction(
                        -v.input_amount(m),
                        v.duration
                    )
                )

            equations.append(equation)

    # 5. 求解正整数并行数
    # 目标：A * P = 0
    # 约束：1 <= P_i <= Int.MAX_VALUE
    # 近似目标：minimize sum(P_i)
    P = solve_positive_integer_system(
        equations=equations,
        lower_bound=1,
        upper_bound=INT_MAX,
        objective="minimize_sum_P_approximately"
    )

    if P is None:
        raise Error("无法找到满足内部速率配平的正整数并行解")

    # 6. 写回节点并行数
    for node in graph.nodes:
        node.parallel = P[node.id]

    # 7. 计算结果
    edge_rates = calculate_edge_rates(graph)
    terminal_outputs = calculate_terminal_outputs(graph)
    byproduct_outputs = calculate_byproduct_outputs(graph)
    residuals = calculate_balance_residuals(graph)

    return BalanceResult(
        parallels=P,
        edge_rates=edge_rates,
        terminal_outputs=terminal_outputs,
        byproduct_outputs=byproduct_outputs,
        residuals=residuals
    )
```

---

## 16. 边流速计算

对每条边：

```text
e = (u, v, m)
```

边流速为：

```text
edge_rate(e)
=
input_amount(v, m) * P_v / duration_v
```

在严格配平下，也应等于：

```text
output_amount(u, m) * P_u / duration_u
```

如果是一个节点输出同一物料给多个后继，则每条边的流速由对应后继的消耗速率决定。

---

## 17. 终产物速率计算

对每个结束节点 `t` 的每个输出物料 `m`：

```text
terminal_output_rate(t, m)
=
output_amount(t, m) * P_t / duration_t
```

所有结束节点输出合并为：

```text
TerminalOutput = union of terminal_output_rate(t, m)
```

如果多个结束节点输出同种物料，则按物料合并求和。

---

## 18. 副产物速率计算

对每个普通节点 `u` 的每个输出物料 `m`：

```text
produce = output_amount(u, m) * P_u / duration_u
```

直接后继消耗速率为：

```text
direct_consume =
Σ input_amount(v, m) * P_v / duration_v
```

其中：

```text
v ∈ Consumers(u, m)
```

则副产物流速为：

```text
byproduct_rate(u, m) = produce - direct_consume
```

如果：

```text
byproduct_rate(u, m) > 0
```

则该剩余量作为副产物输出。

如果 `Consumers(u, m)` 为空，则：

```text
byproduct_rate(u, m) = produce
```

如果多个普通节点产生同种副产物，则最终输出时按物料合并求和。

---

## 19. 配平结果结构

配平结果可以设计为：

```text
BalanceResult {
    success
    parallels
    edge_rates
    terminal_outputs
    byproduct_outputs
    residuals
}
```

其中：

```text
success = 是否配平成功
parallels = 每个节点的 P 并行数
edge_rates = 每条边的物料流速
terminal_outputs = 结束节点产物速率
byproduct_outputs = 副产物速率
residuals = 内部配平残差
```

---

## 20. 配平残差

理论上，严格配平时每个内部物料约束的残差为 0。

对于约束：

```text
produce_rate(u, m)
-
Σ consume_rate(v, m)
=
0
```

残差定义为：

```text
residual(u, m)
=
produce_rate(u, m)
-
Σ consume_rate(v, m)
```

如果：

```text
residual = 0
```

表示完全配平。

如果：

```text
residual > 0
```

表示生产过剩，可作为副产或冗余输出。

如果：

```text
residual < 0
```

表示消耗大于生产，配平失败或需要外部输入补充。

在当前模型中，内部连接物料一般要求：

```text
residual = 0
```

或者在允许剩余副产模式下：

```text
residual >= 0
```

---

## 21. 大循环图处理

对于循环图，不需要拓扑排序。

循环图直接使用同一套线性方程组处理。

例如：

```text
A -> B -> C -> A
```

算法会为每条直接物料边建立速率约束：

```text
A 的输出速率 = B 的输入速率
B 的输出速率 = C 的输入速率
C 的输出速率 = A 的输入速率
```

只要存在正整数解，就可以完成内部配平。

循环图额外满足：

```text
1. 只能有一个结束节点
2. 不能存在非法小环
3. 结束节点最终输出速率必须为正
```

循环图不要求求出绝对最小净输出，只要求：

```text
内部循环速率配平
P 为正整数
P 尽量小
结束节点有正输出
```

---

## 22. 多结束节点图处理

多结束节点图只允许出现在非循环图中。

其配平逻辑与单结束节点图相同，区别在于目标输出包含多个结束节点。

多结束节点图不要求每个目标产物分别最小化。

算法只需要：

```text
1. 保证内部相连物料速率配平
2. 所有结束节点输出为正
3. 总体输出规模近似较小
```

推荐近似目标：

```text
minimize ΣP_i
```

或者：

```text
minimize Σ terminal_output_rate
```

默认建议使用 `ΣP_i`，因为它简单稳定，且通常能间接限制整体输出规模。

---

## 23. 不可配平情况

算法应能识别以下失败场景：

### 23.1 无正整数解

如果无法找到：

```text
A * P = 0
P_i >= 1
P_i ∈ Integer
```

则配平失败。

---

### 23.2 图结构非法

例如：

```text
1. 没有结束节点
2. 边引用不存在的节点
3. 边引用的物料不匹配
4. 循环图存在多个结束节点
5. 存在非法小环
```

---

### 23.3 并行数超出范围

如果某个节点需要：

```text
P_i > Int.MAX_VALUE
```

则配平失败。

---

### 23.4 结束节点无正输出

如果配平后结束节点输出速率为 0，则图不成立。

当前模型要求：

```text
结束节点输出速率 > 0
```

---

## 24. 示例

### 24.1 工序定义

给定两个节点：

```text
节点 1:
1A + 1L B = 2C + 1L D
duration = 20t

节点 2:
1C + 1F = 1G
duration = 40t
```

边：

```text
节点 1 --C--> 节点 2
```

结束节点：

```text
节点 2
```

---

### 24.2 初始速率

如果：

```text
P1 = 1
P2 = 1
```

则：

```text
节点 1 生产 C:
2C / 20t = 0.1C/t = 2C/s

节点 2 消耗 C:
1C / 40t = 0.025C/t = 0.5C/s
```

内部物料 `C` 未配平。

---

### 24.3 建立方程

要求：

```text
2 * P1 / 20
=
1 * P2 / 40
```

化简：

```text
0.1P1 = 0.025P2
```

得到：

```text
P2 = 4P1
```

取最小正整数解：

```text
P1 = 1
P2 = 4
```

---

### 24.4 配平结果

配平后：

```text
节点 1 生产 C:
2 * 1 / 20t = 0.1C/t = 2C/s

节点 2 消耗 C:
1 * 4 / 40t = 0.1C/t = 2C/s
```

内部物料 `C` 配平。

节点 2 产出：

```text
G:
1 * 4 / 40t = 0.1G/t = 2G/s
```

节点 1 的 `D` 没有被直接后继消耗，因此为副产物：

```text
D:
1L * 1 / 20t = 0.05L/t = 1L/s
```

最终图输出为：

```text
终产物：
G = 2G/s

副产物：
D = 1L/s
```

---

## 25. 总结

该模型将 GTNH 配方链抽象为一张有向工序图。

其核心规则是：

```text
节点 = 配方
边 = 直接物料流
结束节点 = 工序目标产物节点
副产物 = 未被直接后继消耗的普通节点产物或剩余产物
```

配平算法的核心思想是：

```text
将每个节点的并行数 P 作为正整数变量，
根据所有显式连接的内部物料建立速率守恒方程，
通过求解正整数方程组得到各节点并行数，
从而使内部生产速率与消耗速率匹配。
```

算法统一支持：

```text
普通 DAG 工序图
一对多分流工序图
多结束节点非循环图
单结束节点大循环图
```

算法不追求绝对最小终产物输出，而是在保证内部速率配平的基础上，使用较小的 `ΣP_i` 作为近似最小规模目标。

最终结果包括：

```text
每个节点的并行数
每条边的流速
所有结束节点产物流速
所有副产物流速
内部残差信息
```
