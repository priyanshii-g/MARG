import urllib.request
import json
import math

# We can query the ways of relation 5805326 (NH 48 MH) that fall within Western Ghats corridor:
# Western Ghats BBox: 17.60, 73.65 to 18.50, 74.30
url = 'https://api.openstreetmap.org/api/0.6/relation/5805326/full.json'
print("Fetching NH 48 MH full relation from OSM...")
req = urllib.request.Request(url, headers={'User-Agent': 'MARG-Routing/1.0'})
with urllib.request.urlopen(req, timeout=30) as r:
    data = json.loads(r.read().decode('utf-8'))

elements = data.get('elements', [])
print(f"Total elements: {len(elements)}")

nodes = {}
for el in elements:
    if el.get('type') == 'node':
        nodes[el['id']] = (round(el['lon'], 5), round(el['lat'], 5))

wg_ways = []
for el in elements:
    if el.get('type') == 'way':
        way_nodes = [nodes[nid] for nid in el.get('nodes', []) if nid in nodes]
        if len(way_nodes) < 2:
            continue
        # Check if way intersects Western Ghats bbox
        if any(17.60 <= pt[1] <= 18.50 and 73.65 <= pt[0] <= 74.30 for pt in way_nodes):
            wg_ways.append(way_nodes)

print(f"Total NH 48 way segments in Western Ghats: {len(wg_ways)}")
with open('scripts/nh48_wg_ways.json', 'w', encoding='utf-8') as f:
    json.dump(wg_ways, f)
