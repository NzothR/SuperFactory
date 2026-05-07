# SuperFactory

SuperFactory is a GTNH addon focused on compact, generic, late-game factory multiblocks.

## English

### Super Proxy Factory

Super Proxy Factory is the first implemented machine. It reads a GregTech multiblock controller from its controller slot,
discovers the recipe maps exposed by that machine, and runs matching recipes through its own input and output hatches.

Its recipe execution is planned in a separate runtime layer. The machine builds an execution plan from the selected recipe
map, available inputs, machine parallel limit, power limit, batch settings, and optional output/runtime transforms.

Structure:

- 3x3x3 compact hollow cube.
- Uses GregTech casing 2 meta 0.
- Controller is placed on the front center.
- The center block behind the controller is air.
- At least 7 valid casing blocks are required.
- Input buses, input hatches, output buses, output hatches, maintenance hatches, and energy or exotic energy hatches can
  replace casing positions.

Main behavior:

- Caches recipe maps from GregTech multiblock controllers.
- Supports mode switching when the cached controller exposes multiple recipe maps.
- Uses controller stack size as the parallel limit source.
- Supports input separation, configured circuits, dual input hatches, recipe lock, batch expansion, perfect overclocking,
  wireless energy reservation, output multipliers, output range clamps, and runtime clamps.
- Recycler recipes have a custom high-parallel output path.

Current status:

Super Proxy Factory compiles and is ready for in-game testing. The main remaining work is validation across GTNH's wider
machine ecosystem, especially machines with unusual recipe maps, special inputs, or modded hatch behavior.

## 中文

### 超级代理工厂

超级代理工厂是目前已经实现的第一台机器。它会从主机槽读取 GregTech 多方块控制器，识别该机器暴露出的
RecipeMap，并使用自己的输入仓、输出仓来代理执行对应配方。

它的配方执行被拆到了独立的运行计划层。机器会根据当前配方模式、可用输入、并行上限、功率上限、批处理设置
以及可选的输出/耗时变换生成执行计划。

结构：

- 3x3x3 紧凑空心立方体。
- 使用 GregTech casing 2 meta 0。
- 控制器位于正面中心。
- 控制器后方中心为空气。
- 至少需要 7 个有效机械方块。
- 输入总线、输入仓、输出总线、输出仓、维护仓、能源仓或异种能源仓可以替换机械方块位置。

主要行为：

- 缓存 GregTech 多方块控制器的配方模式。
- 当目标控制器暴露多个 RecipeMap 时，支持切换当前模式。
- 通过主机槽里的控制器堆叠数量决定并行上限。
- 支持输入隔离、编程电路匹配、双输入仓、配方锁、批处理扩展、完美超频、无线能量预扣、输出倍率、输出范围限制和运行时间限制。
- 回收机配方有单独的高并行输出路径。

当前状态：

超级代理工厂已经可以编译，接下来主要需要在游戏内测试。重点是验证 GTNH 中各种特殊机器、特殊输入和模组仓口行为。
