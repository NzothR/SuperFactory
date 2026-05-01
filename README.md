# SuperFactory

SuperFactory is a GTNH addon focused on compact, generic, late-game factory multiblocks.

The first machine, Super Proxy Factory, is currently implemented and under testing. Its goal is to provide one small
multiblock that can proxy the recipe maps of other GregTech multiblock machines without hand-written recipe copies.

## Super Proxy Factory

Super Proxy Factory reads a GregTech multiblock controller from its controller slot, discovers the recipe maps exposed by
that machine, and runs matching recipes through its own input and output hatches.

Compared with a normal proxy machine, its recipe execution is planned in a separate runtime layer. The machine builds a
recipe execution plan from the selected recipe map, available inputs, machine parallel limit, power limit, batch settings,
and optional output/runtime transforms.

### Structure

The current structure is a compact 3x3x3 cube using GregTech casing 2 meta 0.

- The controller is placed on the front center.
- The center block behind the controller is air.
- At least 7 valid casing blocks are required.
- Input buses, input hatches, output buses, output hatches, maintenance hatches, and energy or exotic energy hatches can
  replace casing positions.

### Recipe Map Selection

Place a GregTech multiblock controller item in the Super Proxy Factory controller slot, then refresh the cache from the
machine UI.

The proxy accepts only GregTech machine controller items whose meta tile entity is both:

- an `MTEMultiBlockBase`
- a `RecipeMapWorkable`

All available recipe maps exposed by that controller are cached. If the machine exposes more than one recipe map, use the
mode switch button or screwdriver interaction to select the active mode.

The amount of controller items in the slot controls the machine parallel limit. One controller gives 1 parallel, while a
full stack maps up to `Integer.MAX_VALUE` parallel.

### Inputs

Super Proxy Factory supports normal input buses and input hatches.

Input separation follows GregTech-style behavior:

- when disabled, inputs are grouped by hatch color if colors are present
- when enabled, uncolored buses are treated independently and colored buses/hatches are grouped by color

Configured integrated circuits participate in recipe matching but are not consumed. Programmable Hatches circuit wrappers
are also unwrapped for matching when present.

Dual input hatches are supported for pattern-like workflows. Their shared items and per-slot inputs are combined for
recipe lookup, while the real consumption plan still consumes only material inputs.

### Recipe Lock

Recipe lock stores the matched recipe and its real one-craft material cost. Marker items such as programmed circuits are
used for matching but are not stored as consumed costs.

If the active recipe map changes, the stored recipe lock is cleared automatically.

### Parallel, Batch, and Overclocking

The machine computes recipe execution in three stages:

1. Input bound: maximum parallel allowed by available real inputs.
2. Power bound: maximum parallel allowed by local power or wireless power mode.
3. Batch expansion: optional expansion beyond base parallel when the overclocked runtime is short enough.

Perfect overclocking is used by default. Blast furnace recipes enable heat overclock handling from the original recipe
heat value.

Manual overclock count can be configured through TecTech parameters. When set, it overrides automatic overclock count.

### Wireless Mode

Wireless mode reserves the whole recipe energy cost up front from the owner's wireless EU network. If the recipe cannot
reserve enough energy, it will not start.

When a wireless recipe fails after reservation but before start, the reserved energy is refunded.

### Output and Runtime Parameters

Several optional parameters are gated by config:

- item output multiplier
- fluid output multiplier
- minimum item output amount
- minimum fluid output amount
- maximum item output amount
- maximum fluid output amount
- minimum runtime
- maximum runtime

These transforms are applied after recipe parallel and batch output calculation. They are intended for pack-specific
balancing and are disabled by default in config.

### Special Cases

Recycler recipes are handled by a custom path so their chance-based output can scale cleanly with high parallel.

Assembly line visual recipes use a data stick as the special-slot template when matching special items.

### Current Status

Super Proxy Factory compiles and is ready for in-game testing. The main remaining work is validation across GTNH's wider
machine ecosystem, especially machines with unusual recipe maps, special inputs, or modded hatch behavior.
