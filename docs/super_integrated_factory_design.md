# Super Integrated Factory Design

## Goal

Super Integrated Factory is a late-game GTNH process packaging machine.

Its job is not to replace all factory planning with a single magic block. Instead, it should remove the repetitive
footwork from production chains that are already understood by the player. A packaged process is represented as a small
directed recipe graph:

- nodes are machine recipes
- edges are intermediate item or fluid transfers
- external inputs are graph inputs not supplied by internal edges
- external outputs are final products plus unused byproducts

The first implementation focuses on the process-management UI and persistent graph editing. Recipe solving, NEI import,
auto-balancing, and runtime execution are intentionally left as later stages.

## First Version Scope

Implement now:

- reusable custom canvas UI library
- editable grid canvas
- pan and zoom
- draggable nodes
- optional grid snapping
- temporary snap while holding Shift
- node add, edit, lock, unlock, and delete
- manual directed edges
- basic automatic edge generation entry point
- save and load enough state to reopen the UI exactly as it was
- placeholder buttons for validation, auto-balance, import, and export

Do not implement now:

- full recipe search algorithm
- NEI `+` button integration
- auto-balancing algorithm
- cyclic graph validation
- runtime process execution
- external file export format

## UI Architecture

The process-management screen should not be built from ordinary fixed-position ModularUI widgets alone. It needs a
custom canvas widget that owns its own coordinate system.

Suggested package layout:

- `com.nzoth.superfactory.common.ui.canvas`
- `CanvasWidget`
- `CanvasNode`
- `CanvasEdge`
- `CanvasViewport`
- `CanvasGrid`
- `CanvasInteractionState`

The canvas should be written as reusable UI infrastructure, not as code tightly coupled to Super Integrated Factory. The
machine-specific layer should only provide graph data, node renderers, and command callbacks.

### Canvas Behavior

Required interactions:

- drag background to pan
- mouse wheel to zoom
- drag node to move
- snap node position to grid when snapping is enabled
- hold Shift to temporarily snap while dragging
- click node to open node editor
- drag from one node port to another to create a directed edge
- select edge and delete it

The grid is visual first, functional second. Snapping exists mainly for readability.

## Node Model

A process node represents one valid recipe executable by one real machine type.

Required node fields:

- stable node id
- display name
- canvas x/y position
- locked state
- end-node flag
- controller `ItemStack`
- recipe map id/name
- selected recipe id or serialized recipe signature
- item inputs
- fluid inputs
- item outputs
- fluid outputs
- non-consumable items
- special item data
- duration
- EU/t

Node rules:

- creating a node opens the node editor first
- a node must contain a real multiblock controller before it can be locked
- players may provide partial inputs, partial outputs, or only one side
- recipe check searches the controller's recipe maps for matching recipes
- if one recipe matches, the editor fills all recipe fields automatically
- if several recipes match, the editor shows a candidate list
- if none match, the node may be closed as an unfinished draft but cannot be confirmed
- confirm always validates again
- locked nodes cannot be edited
- locked nodes do not allow the controller to be removed
- only locked nodes may be connected by edges
- unlocking a node invalidates its connections or requires reconnect validation later
- a locked node may be marked as an end node
- end nodes are balance targets and should normally represent the desired final product step
- end nodes may not have outgoing edges

### End Nodes

The node editor must allow a node to be marked as an end node.

End nodes provide the balancer with explicit targets. A process graph may have many starting inputs, but usually has one
or a small number of desired final product nodes. Starting from end nodes avoids guessing the intended terminal products
by scanning the entire graph.

Rules:

- only locked nodes can be end nodes
- draft or invalid nodes cannot be end nodes
- the UI should make end nodes visually distinct
- multiple end nodes are allowed, so players can describe combined-output processes
- end nodes cannot have outgoing edges
- trying to connect from an end node should be rejected by the UI
- marking a node as an end node should be blocked if it already has outgoing edges, unless the player removes those edges
- if no end node exists, auto-balancing should return a clear "no end node selected" result
- if several end nodes exist before multi-target balancing is implemented, auto-balancing may return a clear unsupported
  result, but the data model and UI should allow the state

## Node Editor

The node editor should be a modal-style screen opened from the process-management canvas. It should only close through
explicit buttons, not by accidental background clicks.

Required controls:

- controller slot
- item input editor
- fluid input editor
- item output editor
- fluid output editor
- non-consumable item editor
- special item editor
- recipe check button
- candidate recipe list
- end-node toggle
- confirm button
- close button
- unlock button for locked nodes

Close behavior:

- Close allows unfinished nodes to remain as drafts.
- Confirm requires a valid matched recipe.
- Recipe check, close, and confirm should all run lightweight consistency checks.

## Edge Model

An edge represents a directed transfer from one node output to one node input.

Required edge fields:

- stable edge id
- source node id
- source output slot/resource key
- target node id
- target input slot/resource key
- resource kind: item or fluid

Edges should store explicit endpoint mappings. Do not rely only on dynamic item equality during later graph evaluation;
explicit edges make debugging and UI display much clearer.

Edge rules:

- only locked nodes can be connected
- end nodes cannot be used as edge sources
- manual edges may create cycles
- automatic edge generation does not support cycles in the first version
- automatic generation should be conservative when several valid target inputs exist

## Graph Rules

External inputs:

- any node input that is not supplied by an internal edge

External outputs:

- final requested products
- any node output not consumed by an internal edge

Byproducts:

- no manual byproduct marking is required
- unused outputs are treated as byproducts automatically

Cycles:

- cyclic graphs are allowed at the UI/data level
- cycle legality is not validated in the first version
- future validation should reject lossy intermediate cycles
- future validation may allow productive or neutral cycles

## Persistence Strategy

### First Version: Minimal NBT

The first version should store only enough state in machine NBT to reopen the process-management UI in the same state as
when it was closed.

Persist:

- graph version
- viewport offset
- viewport zoom
- grid snapping setting
- node ids
- node positions
- node locked/draft state
- minimal controller stack data
- minimal matched recipe identity or compact recipe signature
- compact resource lists needed to redraw and reconnect
- edge endpoint ids and resource keys

Avoid storing:

- full recipe maps
- full recipe candidate search results
- repeated display-only strings
- large cached NEI data
- redundant resource copies that can be recomputed from the locked recipe

The NBT should be treated as a compact state snapshot, not as a full database.

### Future: External Process Files

If real process graphs become large enough to risk NBT bloat, process data should move out of TileEntity NBT.

Preferred future layout:

- `config/superfactory/processes/` for shared pack/global process definitions, or
- world save data folder for per-world process definitions

The machine NBT would then store only:

- process file id
- last known graph version
- viewport state
- small dirty-state marker

This also makes manual export/import much simpler: export can become copying or generating one process file.

## Export And Import

Export is not required in the first implementation.

Keep an export button placeholder in the UI so the workflow has a stable home. Later export can write a graph file or
produce a shareable encoded process string.

Import should eventually support:

- process file import
- encoded process string import
- NEI recipe import into a node

## Validation And Algorithm Placeholders

Add explicit placeholder services so later logic has clear insertion points:

- `RecipeNodeResolver`
- `ProcessGraphValidator`
- `ProcessAutoConnector`
- `ProcessBalancer`
- `ProcessExecutionPlanner`

First version behavior:

- resolver may return "not implemented"
- validator may check only trivial UI constraints
- auto-connector may be disabled or perform only simple acyclic matching
- balancer is a placeholder
- execution planner is a placeholder

## Auto-Balancing Design Notes

Auto-balancing is triggered by a button in the process-management UI. It should balance all connected, valid, locked
nodes so that internal edge resources are consumed at the same rate they are produced, and final graph output is reduced
to the smallest practical integer ratio.

This is not a simple per-craft input/output balancing problem. Every node has time, EU/t, manual overclocks, and allowed
parallel. The balancer must use rates rather than raw recipe stack sizes.

### Node Rate Inputs

For each locked recipe node, the balancer needs:

- base item inputs per craft
- base fluid inputs per craft
- base item outputs per craft
- base fluid outputs per craft
- base duration
- base EU/t
- node allowed parallel
- node manual overclock count

The machine-wide total parallel limit is finite. For early development, treat it as `Integer.MAX_VALUE`. Later, upgrade
progression will lower or raise this limit. The sum of allocated node parallels must never exceed the machine-wide limit.

Each node may specify its own maximum allowed parallel. The balancer may allocate any value from 1 to that node limit.

### Overclock Handling

Automatic overclock selection is intentionally not part of the first balancing algorithm.

Each node has a manual OC count:

- default is 0
- 0 means no manual overclock
- values above 0 apply that many GT-style overclocks to that node only

After OC is applied, the node computes its effective EU/t and effective duration. Duration rounding should follow the
same rules GT uses for recipe overclocking, because rate calculation depends on tick-accurate duration.

If effective duration would fall below 1 tick, trigger OC protection. OC protection automatically lowers the node manual
OC count to the highest value that keeps one single recipe craft at exactly or above 1 tick, before parallel is applied.

This avoids wasting energy on overclocks that no longer reduce real execution time. Parallel and later batch logic may
still increase throughput, but the node's single-craft duration should not be pushed below 1 tick by manual OC alone.

Invariant:

- effective single-craft duration after manual OC is at least 1 tick
- if the requested OC count would break that invariant, the node stores the protected lower OC count
- the UI should show that OC protection adjusted the value

### Rate Calculation

For balancing, every recipe node is converted into per-tick resource rates:

- input rate = `(input amount per craft * allocated parallel * subTickBatchMultiplier) / effective duration`
- output rate = `(output amount per craft * allocated parallel * subTickBatchMultiplier) / effective duration`

Use exact rational numbers internally if possible. Avoid floating point for the actual balance solve; rounding should
happen only when producing final integer execution ratios.

Resources should include both items and fluids in one resource vector model:

- item resource key: item id, meta, and relevant NBT/ore-dict matching mode
- fluid resource key: fluid id

Non-consumable items do not participate as consumed resources, but they still belong to node recipe validity.

### Balance Problem

Once all node resource flows are converted to rates, balancing starts from the selected end node and walks upstream
through connected dependencies. This prevents the balancer from treating unrelated byproduct-only or disconnected nodes
as process targets.

After the target subgraph is identified, balancing becomes a chemical-equation-like linear solve.

For every internal edge resource:

- upstream production rate * upstream scale
- must satisfy downstream consumption rate * downstream scale

The target is the smallest integer node scale vector that makes connected internal resources exactly sufficient, while
respecting:

- node allowed parallel limits
- machine-wide total parallel limit
- no negative intermediate resource balance
- no lossy cycles

Unused node outputs become external byproducts. Inputs not supplied by internal edges become external graph inputs.

### Cycles

Cyclic graphs are allowed by the UI, but balancing cyclic graphs is a later-stage algorithm.

Rules to preserve:

- intermediate cycles may not be lossy
- productive or neutral cycles may be allowed
- automatic edge generation should not create cycles in the first implementation

For the first balancer implementation, it is acceptable to handle only acyclic connected graphs and return a clear
"cyclic balancing not implemented" result for cyclic graphs.

## Implementation Order

1. Define compact graph data classes.
2. Add NBT read/write for graph view state, nodes, and edges.
3. Build reusable canvas widget.
4. Add process-management window to Super Integrated Factory.
5. Add node editor window with draft and locked states.
6. Add manual edge creation.
7. Add save/load round trip.
8. Add placeholder buttons for check, auto-connect, auto-balance, import, and export.
9. Add recipe resolver implementation.
10. Add NEI import.
11. Add graph validation and balancing.
12. Add runtime execution.

## Open Questions

- Whether process files should live in `config` or in per-world save data once NBT becomes too large.
- How to generate stable recipe signatures for recipes that do not expose stable ids.
- Whether unlocking a node should delete existing edges immediately or mark them invalid until revalidated.
- How much controller stack data must be stored to safely preserve locked-node ownership and consumption.
