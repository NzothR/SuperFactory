# SuperFactory

SuperFactory is a GregTech addon for GT New Horizons late-game automation.

The mod currently adds two compact generic multiblock machines:

- Recipe Proxy Nexus
- Process Integration Core

They are not meant to bypass GTNH progression. They are designed to reduce repeated machine construction, maintenance, and sprawling production lines after the player has already unlocked mature machines, AE infrastructure, and complex processing chains.

## Machines

### Recipe Proxy Nexus

Recipe Proxy Nexus proxies recipes from other GregTech multiblock machines.

Place a GregTech multiblock controller in the host slot, then update the recipe cache from the GUI. The machine reads the recipe modes exposed by that controller and runs matching recipes through its own input hatches, output hatches, and energy system.

Useful for:

- Compressing already unlocked multiblocks into one generic proxy controller.
- Running high-parallel batches of known recipe families.
- Switching between multiple recipe modes exposed by the cached controller.
- Proxying supported special recipes, such as recycler and Eye of Harmony recipes.

Main features:

- Supports normal input buses, input hatches, ME input buses, and ME input hatches.
- Supports normal output buses, output hatches, and common ME output paths.
- Supports recipe locking, batching, input separation, manual overclocking, and wireless mode.
- Recently successful recipes are cached per machine to reduce repeated full recipe-map scans.
- Wired mode consumes energy like a normal multiblock recipe. Wireless mode consumes energy from the wireless network.

Eye of Harmony notes:

- Eye of Harmony recipes use a dedicated adapter instead of relying only on normal GTRecipe fields.
- Hydrogen and helium for Eye of Harmony recipes must be supplied through ME storage input hatches.
- Normal fluid hatches and quad input hatches are not used as the Eye of Harmony hydrogen or helium supply path.
- Eye of Harmony only applies manual overclocking, with protection against wasteful overclock counts beyond the 1-tick limit.

### Process Integration Core

Process Integration Core packages and schedules a whole process graph.

Use the process management GUI to add recipe nodes, connect nodes, configure node parallel and overclock values, balance the graph, and submit it. During runtime, the machine schedules nodes according to the graph and manages intermediate products, startup materials, non-consumables, and recipe-host requirements.

Useful for:

- Packaging a complex production line into one multiblock controller.
- Managing multi-step recipe graphs with intermediate products and different node speeds.
- Reducing AE round trips and large arrays of repeated machines.
- Long-running production of fixed products.

Main features:

- Graphical process management UI.
- Supports normal recipe nodes, recycler nodes, and supported special recipe nodes.
- Supports node parallel, node overclocking, and global extra overclocking.
- Supports auto-connect, auto-balance, import, export, and raw-material export.
- Supports ME input buses and ME input hatches for graph inputs, including stored fluid requirements.
- Power loss stops active nodes immediately. Already consumed startup materials are not refunded.

## Basic Usage

### Recipe Proxy Nexus

1. Build the Recipe Proxy Nexus multiblock.
2. Put the GregTech multiblock controller to proxy into the host slot.
3. Click the GUI recipe update button to scan and cache available recipe modes.
4. If the host exposes multiple modes, right-click the machine with a screwdriver to cycle modes.
5. Provide item inputs, fluid inputs, and energy. The machine will try to run matching recipes from the selected cached mode.
6. Enable recipe lock if you want to pin the next valid recipe.

Notes:

- Manual recipe update only recognizes GregTech multiblock controllers.
- The stack size of the controller in the host slot affects the available parallel limit.
- Wireless mode does not auto-overclock and only uses manual overclocks. Normal recipes in wired mode may use automatic overclocking.
- If manual overclocking exceeds the perfect overclock count needed to reach 1 tick, the machine clamps it to the useful limit.

### Process Integration Core

1. Build the Process Integration Core multiblock.
2. Open process management.
3. Add recipe nodes or special nodes.
4. Configure each node's recipe, inputs, outputs, non-consumables, and recipe-host requirements.
5. Connect nodes manually or use auto-connect.
6. Run auto-balance and review the node rates.
7. Submit the process.
8. Provide startup materials, non-consumables, recipe hosts, and energy.
9. The machine will enter runtime mode and schedule nodes according to the submitted graph.

Notes:

- Node overclocks and the machine GUI's global extra overclocks are applied together.
- Overclocking is protected, so extra overclocks beyond the 1-tick limit are not applied.
- Turning power off stops nodes immediately instead of waiting for the current node run to finish.
- Startup materials already consumed by nodes are not refunded after power loss.

## Multiblock Structure

Both machines currently use a compact 3x3x3 hollow structure.

Common requirements:

- Controller at the front center.
- The center block behind the controller is air.
- The remaining positions use the required GregTech casing.
- Input buses, input hatches, output buses, output hatches, energy hatches, and related hatches may replace some casing positions.

See the `docs` directory for detailed structure and implementation notes.

## Recipes And Config

Both machines use assembler recipes.

The default recipes are UXV-stage recipes intended to be craftable before Eye of Harmony. Optional cheap LV testing recipes are also available, disabled by default.

Common config entries:

- `enableSuperProxyFactory`: registers Recipe Proxy Nexus.
- `enableSuperIntegratedFactory`: registers Process Integration Core.
- `enableCheapSuperProxyFactoryRecipe`: enables the cheap LV Recipe Proxy Nexus recipe.
- `enableCheapSuperIntegratedFactoryRecipe`: enables the cheap LV Process Integration Core recipe.
- `superProxyFactorySuccessfulRecipeCacheSize`: successful recipe cache size per Recipe Proxy Nexus. Defaults to 64. Set to 0 to disable.

The config file is located at `superfactory/superfactory.cfg` under the Minecraft config directory.

## Documentation

More design details are available in:

- `docs/GTNH代理工厂详细设计文档_修订版.md`
- `docs/GTNH工序图模型与速率配平算法设计.md`
- `docs/GTNH工序机器详细设计文档.md`

## Status

SuperFactory is still being developed and tested against real GTNH worlds. The core functionality is usable, but GTNH has a very large machine ecosystem, so some special machines, hatch behavior, or cross-mod compatibility paths may still need targeted support.
