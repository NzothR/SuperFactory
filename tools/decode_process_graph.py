#!/usr/bin/env python3
import argparse
import gzip
import json
import sys
import struct
from collections import defaultdict


TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class NbtReader:

    def __init__(self, data):
        self.data = data
        self.pos = 0

    def read(self, size):
        chunk = self.data[self.pos:self.pos + size]
        if len(chunk) != size:
            raise EOFError("Unexpected end of NBT data")
        self.pos += size
        return chunk

    def u8(self):
        return self.read(1)[0]

    def i8(self):
        return struct.unpack(">b", self.read(1))[0]

    def i16(self):
        return struct.unpack(">h", self.read(2))[0]

    def i32(self):
        return struct.unpack(">i", self.read(4))[0]

    def i64(self):
        return struct.unpack(">q", self.read(8))[0]

    def f32(self):
        return struct.unpack(">f", self.read(4))[0]

    def f64(self):
        return struct.unpack(">d", self.read(8))[0]

    def string(self):
        size = struct.unpack(">H", self.read(2))[0]
        return self.read(size).decode("utf-8", errors="replace")

    def payload(self, tag_type, path="$"):
        if tag_type == TAG_BYTE:
            return self.i8()
        if tag_type == TAG_SHORT:
            return self.i16()
        if tag_type == TAG_INT:
            return self.i32()
        if tag_type == TAG_LONG:
            return self.i64()
        if tag_type == TAG_FLOAT:
            return self.f32()
        if tag_type == TAG_DOUBLE:
            return self.f64()
        if tag_type == TAG_BYTE_ARRAY:
            return list(self.read(self.i32()))
        if tag_type == TAG_STRING:
            return self.string()
        if tag_type == TAG_LIST:
            child_type = self.u8()
            size = self.i32()
            return [self.payload(child_type, f"{path}[{index}]") for index in range(size)]
        if tag_type == TAG_COMPOUND:
            result = {}
            while True:
                child_type = self.u8()
                if child_type == TAG_END:
                    return result
                name = self.string()
                result[name] = self.payload(child_type, f"{path}.{name}")
        if tag_type == TAG_INT_ARRAY:
            return [self.i32() for _ in range(self.i32())]
        if tag_type == TAG_LONG_ARRAY:
            return [self.i64() for _ in range(self.i32())]
        raise ValueError(f"Unsupported NBT tag type {tag_type} at {path}, pos={self.pos}")

    def root(self):
        tag_type = self.u8()
        if tag_type != TAG_COMPOUND:
            raise ValueError(f"Root tag is {tag_type}, expected compound")
        name = self.string()
        return name, self.payload(tag_type)


def load_nbt(path):
    data = open(path, "rb").read()
    if data.startswith(b"\x1f\x8b"):
        data = gzip.decompress(data)
    _, root = NbtReader(data).root()
    return root


def graph_tag(root):
    return root.get("Graph", root)


def display_amount(stack):
    if not stack:
        return 0
    tag = stack.get("tag") or {}
    if "SuperFactoryDisplayAmount" in tag:
        return int(tag["SuperFactoryDisplayAmount"])
    return max(0, int(stack.get("Count", 0)))


def stack_key(stack):
    if not stack:
        return None
    item_id = stack.get("id", "")
    damage = int(stack.get("Damage", 0))
    tag = stack.get("tag") or {}
    fluid = tag.get("Fluid")
    if isinstance(fluid, dict) and fluid.get("FluidName"):
        return "fluid:" + str(fluid.get("FluidName"))
    return f"item:{item_id}:{damage}"


def stack_desc(stack):
    key = stack_key(stack)
    if key is None:
        return None
    return f"{key}@{display_amount(stack)}"


def handler_stacks(handler):
    items = []
    for entry in (handler or {}).get("Items", []):
        stack = dict(entry)
        slot = int(stack.pop("Slot", 0))
        items.append((slot, stack))
    return sorted(items)


def node_summary(node):
    inputs = [stack_desc(stack) for _, stack in handler_stacks(node.get("Inputs")) if stack_desc(stack)]
    outputs = [stack_desc(stack) for _, stack in handler_stacks(node.get("Outputs")) if stack_desc(stack)]
    cycle_material = None
    cycle_stacks = [stack_desc(stack) for _, stack in handler_stacks(node.get("CycleMaterial")) if stack_desc(stack)]
    if cycle_stacks:
        cycle_material = cycle_stacks[0]
    return {
        "id": int(node.get("Id", 0)),
        "name": node.get("Name", ""),
        "target": bool(node.get("EndNode", 0)),
        "locked": bool(node.get("Locked", 0)),
        "parallel": int(node.get("ParallelLimit", 1)),
        "duration": int(node.get("DurationTicks", 0)),
        "cycleMaterial": cycle_material,
        "inputs": inputs,
        "outputs": outputs,
    }


def edge_summary(edge, nodes):
    from_id = int(edge.get("From", 0))
    to_id = int(edge.get("To", 0))
    return {
        "id": int(edge.get("Id", 0)),
        "from": from_id,
        "fromName": nodes.get(from_id, {}).get("name", ""),
        "to": to_id,
        "toName": nodes.get(to_id, {}).get("name", ""),
        "resource": edge.get("Resource", ""),
    }


def tarjans(nodes, edges):
    adjacency = defaultdict(list)
    for edge in edges:
        adjacency[edge["from"]].append(edge["to"])
    index = 0
    stack = []
    on_stack = set()
    indices = {}
    low = {}
    components = []

    def strongconnect(node_id):
        nonlocal index
        indices[node_id] = index
        low[node_id] = index
        index += 1
        stack.append(node_id)
        on_stack.add(node_id)
        for next_id in adjacency[node_id]:
            if next_id not in indices:
                strongconnect(next_id)
                low[node_id] = min(low[node_id], low[next_id])
            elif next_id in on_stack:
                low[node_id] = min(low[node_id], indices[next_id])
        if low[node_id] == indices[node_id]:
            component = []
            while True:
                item = stack.pop()
                on_stack.remove(item)
                component.append(item)
                if item == node_id:
                    break
            if len(component) > 1 or node_id in adjacency[node_id]:
                components.append(sorted(component))

    for node_id in nodes:
        if node_id not in indices:
            strongconnect(node_id)
    return components


def rates_for_material(node, material, outputs):
    stacks = handler_stacks(node.get("Outputs" if outputs else "Inputs"))
    amount = sum(display_amount(stack) for _, stack in stacks if stack_key(stack) == material)
    duration = max(1, int(node.get("DurationTicks", 0)))
    parallel = max(1, int(node.get("ParallelLimit", 1)))
    return amount * parallel / duration


def analyze(path):
    root = graph_tag(load_nbt(path))
    nodes = {int(node.get("Id", 0)): node_summary(node) for node in root.get("Nodes", [])}
    raw_nodes = {int(node.get("Id", 0)): node for node in root.get("Nodes", [])}
    edges = [edge_summary(edge, nodes) for edge in root.get("Edges", [])]
    components = tarjans(nodes, edges)
    cycle_summaries = []
    for component in components:
        materials = set()
        for node_id in component:
            raw = raw_nodes[node_id]
            for _, stack in handler_stacks(raw.get("Inputs")) + handler_stacks(raw.get("Outputs")):
                key = stack_key(stack)
                if key:
                    materials.add(key)
        material_rates = []
        rate_by_material = {}
        for material in sorted(materials):
            produced = sum(rates_for_material(raw_nodes[node_id], material, True) for node_id in component)
            consumed = sum(rates_for_material(raw_nodes[node_id], material, False) for node_id in component)
            if produced > 0 and consumed > 0:
                entry = {
                    "material": material,
                    "produced": produced,
                    "consumed": consumed,
                    "net": produced - consumed,
                }
                material_rates.append(entry)
                rate_by_material[material] = entry
        target_internal_outputs = []
        target_ids = [node_id for node_id in component if nodes[node_id]["target"]]
        if len(target_ids) == 1:
            target_id = target_ids[0]
            target_outputs = {stack_key(stack) for _, stack in handler_stacks(raw_nodes[target_id].get("Outputs"))}
            for edge in edges:
                if edge["from"] != target_id or edge["to"] not in component:
                    continue
                consumer_inputs = {
                    stack_key(stack) for _, stack in handler_stacks(raw_nodes[edge["to"]].get("Inputs"))
                }
                for material in sorted(target_outputs & consumer_inputs):
                    produced = rates_for_material(raw_nodes[target_id], material, True)
                    consumed = rates_for_material(raw_nodes[target_id], material, False)
                    if material in rate_by_material and produced > consumed:
                        target_internal_outputs.append(rate_by_material[material])
        cycle_summaries.append({
            "nodes": [{"id": node_id, "name": nodes[node_id]["name"], "target": nodes[node_id]["target"]}
                      for node_id in component],
            "edges": [edge for edge in edges if edge["from"] in component and edge["to"] in component],
            "candidateMaterials": material_rates,
            "targetInternalPositiveNetOutputs": target_internal_outputs,
        })
    return {"nodes": list(nodes.values()), "edges": edges, "cycles": cycle_summaries}


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Decode a SuperFactory process graph .dat file")
    parser.add_argument("path")
    parser.add_argument("--json", action="store_true", help="Print full JSON instead of a compact report")
    args = parser.parse_args()
    graph = analyze(args.path)
    if args.json:
        print(json.dumps(graph, ensure_ascii=False, indent=2))
        return
    print(f"nodes={len(graph['nodes'])}, edges={len(graph['edges'])}, cycles={len(graph['cycles'])}")
    for node in graph["nodes"]:
        marker = " target" if node["target"] else ""
        print(f"[{node['id']}] {node['name']}{marker} P={node['parallel']} t={node['duration']}")
        if node["cycleMaterial"]:
            print("  cycle: " + node["cycleMaterial"])
        if node["inputs"]:
            print("  in : " + ", ".join(node["inputs"][:8]) + (" ..." if len(node["inputs"]) > 8 else ""))
        if node["outputs"]:
            print("  out: " + ", ".join(node["outputs"][:8]) + (" ..." if len(node["outputs"]) > 8 else ""))
    print("edges:")
    for edge in graph["edges"]:
        resource = f" [{edge['resource']}]" if edge["resource"] else ""
        print(f"  {edge['id']}: {edge['fromName']}#{edge['from']} -> {edge['toName']}#{edge['to']}{resource}")
    print("cycles:")
    for index, cycle in enumerate(graph["cycles"], 1):
        print(f"  cycle {index}: " + ", ".join(f"{n['name']}#{n['id']}" for n in cycle["nodes"]))
        for material in cycle["candidateMaterials"]:
            print(
                f"    {material['material']}: produced={material['produced']}, "
                f"consumed={material['consumed']}, net={material['net']}")
        if cycle["targetInternalPositiveNetOutputs"]:
            print("    target-internal positive net outputs:")
            for material in cycle["targetInternalPositiveNetOutputs"]:
                print(f"      {material['material']}: net={material['net']}")


if __name__ == "__main__":
    main()
