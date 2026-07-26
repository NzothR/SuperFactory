# Extract method names from field 3 of sampler metadata using exact protobuf structure

import struct

with open(r'D:\Project\MC\SuperFactory\分析\profile-2026-07-26_12.47.10.sparkprofile', 'rb') as f:
    data = f.read()

def read_varint(data, offset):
    result = 0
    shift = 0
    while offset < len(data):
        byte = data[offset]
        result |= (byte & 0x7f) << shift
        offset += 1
        if not (byte & 0x80):
            break
        shift += 7
    return result, offset

# Skip metadata (field 1)
offset = 0
tag, offset = read_varint(data, offset)
length, offset = read_varint(data, offset)
offset += length

# Parse sampler metadata
tag, offset = read_varint(data, offset)
length, offset = read_varint(data, offset)
sampler_meta = data[offset:offset+length]
offset += length

# The field 3 entries have sub-fields: 1=class(string), 2=method(string), 3=desc(string), 4=code_loc(string)
# Let's parse the hex structure:
# 0x1a = field 3, wire type 2 (length-delimited)
# Within each field 3 blob:
#   0x1a = field class (string): class.method
# Actually the sub-fields are encoded oddly - let me decode properly

# Let me look at a specific entry byte by byte
mo = 0
count = 0
method_table = []

while mo < len(sampler_meta):
    tag, mo = read_varint(sampler_meta, mo)
    fn, wt = tag >> 3, tag & 0x07

    if fn == 3 and wt == 2:
        length, mo = read_varint(sampler_meta, mo)
        sub = sampler_meta[mo:mo+length]
        mo += length

        # Parse sub-message
        so = 0
        class_name = ""
        method_name = ""
        method_desc = ""
        while so < len(sub):
            tag2, so = read_varint(sub, so)
            fn2, wt2 = tag2 >> 3, tag2 & 0x07
            if fn2 == 3 and wt2 == 2:  # field 3 = class name string
                l2, so = read_varint(sub, so)
                class_name = sub[so:so+l2].decode("utf-8", errors="replace")
                so += l2
            elif fn2 == 4 and wt2 == 2:  # field 4 = method name string
                l2, so = read_varint(sub, so)
                method_name = sub[so:so+l2].decode("utf-8", errors="replace")
                so += l2
            elif fn2 == 5 and wt2 == 2:  # field 5 = method descriptor
                l2, so = read_varint(sub, so)
                method_desc = sub[so:so+l2].decode("utf-8", errors="replace")
                so += l2
            elif wt2 == 0:
                _, so = read_varint(sub, so)
            elif wt2 == 2:
                l2, so = read_varint(sub, so)
                so += l2
            elif wt2 == 1:
                so += 8
            elif wt2 == 5:
                so += 4
            else:
                break

        if class_name or method_name:
            method_table.append((class_name, method_name, method_desc))

    elif wt == 2:
        length, mo = read_varint(sampler_meta, mo)
        mo += length
    elif wt == 0:
        _, mo = read_varint(sampler_meta, mo)
    else:
        break

print(f"Method table size: {len(method_table)}")
for i, (c, m, d) in enumerate(method_table[:15]):
    desc = d[:40] if d else ""
    print(f"  [{i}] {c}.{m} {desc}")

# Count and list SuperFactory methods
sf_methods = [(i, c, m, d) for i, (c, m, d) in enumerate(method_table) if 'superfactory' in c.lower() or 'SuperFactory' in c]
print(f"\nSuperFactory methods in table: {len(sf_methods)}")
for i, c, m, d in sf_methods:
    desc = d[:60] if d else ""
    print(f"  [{i}] {c}.{m} {desc}")

# Now parse thread node data
print(f"\n=== Thread Nodes ===")
node_count = 0
while offset < len(data):
    tag, offset = read_varint(data, offset)
    fn, wt = tag >> 3, tag & 0x07
    if fn != 3 or wt != 2:
        break
    length, offset = read_varint(data, offset)
    node_data = data[offset:offset+length]
    offset += length

    # Parse ThreadNode
    no = 0
    thread_name = "?"
    thread_time = 0
    children_offset = 0
    children_len = 0

    while no < len(node_data):
        tag2, no = read_varint(node_data, no)
        fn2, wt2 = tag2 >> 3, tag2 & 0x07
        if fn2 == 1 and wt2 == 2:  # thread name
            l, no = read_varint(node_data, no)
            thread_name = node_data[no:no+l].decode("utf-8", errors="replace")
            no += l
        elif fn2 == 2 and wt2 == 0:  # node time
            thread_time, no = read_varint(node_data, no)
        elif fn2 == 3 and wt2 == 2:  # children
            l, no = read_varint(node_data, no)
            children_offset = no
            children_len = l
            no += l
        elif wt2 == 0:
            _, no = read_varint(node_data, no)
        elif wt2 == 2:
            l, no = read_varint(node_data, no)
            no += l
        elif wt2 == 1:
            no += 8
        elif wt2 == 5:
            no += 4
        else:
            break

    node_count += 1

    # Skip threads with very little time
    if thread_time < 1000:
        continue

    print(f"\n  Thread: {thread_name}, total_time={thread_time}us ({thread_time/1000:.1f}ms)")

    if children_len == 0 or children_offset == 0:
        continue

    # Parse the tree recursively and extract ID + time pairs
    def extract_tree(data, offset, depth=0):
        """Parse StackTraceNode entries, returning list of (method_id, time_us, depth)"""
        results = []
        while offset < len(data):
            tag, offset = read_varint(data, offset) if offset < len(data) else (0, offset)
            fn, wt = tag >> 3, tag & 0x07
            method_id = -1
            time_us = 0
            child_offset = 0
            child_len = 0

            if fn == 1 and wt == 0:  # method_id
                method_id, offset = read_varint(data, offset)
            elif fn == 2 and wt == 0:  # parent_time_us
                time_us, offset = read_varint(data, offset)
            elif fn == 3 and wt == 0:  # line_no
                _, offset = read_varint(data, offset)
            elif fn == 4 and wt == 2:  # children
                l, offset = read_varint(data, offset)
                child_offset = offset
                child_len = l
                offset += l
            elif fn == 5 and wt == 0:  # is_duplicate?
                _, offset = read_varint(data, offset)
            elif wt == 0:
                _, offset = read_varint(data, offset)
            elif wt == 2:
                l, offset = read_varint(data, offset)
                offset += l
            elif wt == 1:
                offset += 8
            elif wt == 5:
                offset += 4
            else:
                break

            if method_id >= 0:
                if method_id < len(method_table):
                    c, m, d = method_table[method_id]
                    results.append((method_id, time_us, depth, c, m))

                if child_len > 0:
                    child_results = extract_tree(data, child_offset, child_offset + child_len, depth + 1)
                    results.extend(child_results)

        return results

    children = node_data[children_offset:children_offset + children_len]
    tree_results = extract_tree(children, 0)

    # Find SuperFactory method calls and aggregate
    from collections import defaultdict
    method_agg = defaultdict(int)
    class_agg = defaultdict(int)
    for mid, time_us, depth, c, m in tree_results:
        method_agg[(c, m)] += time_us
        class_agg[c] += time_us

    # Top 10 classes
    sorted_cls = sorted(class_agg.items(), key=lambda x: -x[1])
    print(f"\n  Top 10 classes by self time:")
    for cls, t in sorted_cls[:15]:
        pct = t * 100.0 / thread_time if thread_time > 0 else 0
        print(f"    {t}us ({t/1000:.1f}ms, {pct:.1f}%): {cls}")

    # SuperFactory specific breakdown
    sf_agg = [(t, c, m) for (c, m), t in method_agg.items() if 'superfactory' in c.lower()]
    if sf_agg:
        sf_agg.sort(key=lambda x: -x[0])
        print(f"\n  SuperFactory methods breakdown:")
        for t, c, m in sf_agg:
            pct = t * 100.0 / thread_time if thread_time > 0 else 0
            print(f"    {t}us ({t/1000:.2f}ms, {pct:.2f}%): {c}.{m}")
