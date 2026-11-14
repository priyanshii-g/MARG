import urllib.request
import json

def extract_relation_ways(rel_id, min_lat, min_lon, max_lat, max_lon, out_file):
    url = f'https://api.openstreetmap.org/api/0.6/relation/{rel_id}/full.json'
    print(f"Fetching relation {rel_id}...")
    req = urllib.request.Request(url, headers={'User-Agent': 'MARG-Routing/1.0'})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.loads(r.read().decode('utf-8'))
    
    elements = data.get('elements', [])
    print(f"  Elements: {len(elements)}")
    nodes = {}
    for el in elements:
        if el.get('type') == 'node':
            nodes[el['id']] = (round(el['lon'], 5), round(el['lat'], 5))
            
    filtered_ways = []
    for el in elements:
        if el.get('type') == 'way':
            way_nodes = [nodes[nid] for nid in el.get('nodes', []) if nid in nodes]
            if len(way_nodes) < 2:
                continue
            if any(min_lat <= pt[1] <= max_lat and min_lon <= pt[0] <= max_lon for pt in way_nodes):
                filtered_ways.append(way_nodes)
                
    print(f"  Filtered segments in bbox: {len(filtered_ways)}")
    with open(out_file, 'w', encoding='utf-8') as f:
        json.dump(filtered_ways, f)
    return len(filtered_ways)

# 1. NH 27 in UP: relation 11571914
print("1. Gangetic Plains: NH 27")
extract_relation_ways(11571914, 26.55, 81.10, 27.20, 82.25, 'scripts/nh27_gp_ways.json')

# 2. NH 715 in Assam: relation 3217671
print("\n2. Assam: NH 715")
extract_relation_ways(3217671, 25.75, 93.10, 26.65, 93.75, 'scripts/nh715_assam_ways.json')
