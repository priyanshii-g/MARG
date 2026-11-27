import urllib.request
import urllib.parse
import json

query = """
[out:json][timeout:30];
(
  way["highway"~"^(trunk|primary|secondary|tertiary|unclassified|residential)$"](18.20,73.75,18.48,74.05);
);
out geom 3000;
"""
data = urllib.parse.urlencode({'data': query}).encode('utf-8')
req = urllib.request.Request(
    'https://overpass-api.de/api/interpreter',
    data=data,
    headers={'User-Agent': 'MARG-Highway-Planner/1.0'}
)
print("Querying Overpass for Western Ghats North...")
try:
    with urllib.request.urlopen(req, timeout=30) as r:
        res = json.loads(r.read().decode('utf-8'))
        ways = res.get('elements', [])
        print(f"Success! Fetched {len(ways)} highway ways.")
except Exception as e:
    print(f"Error: {e}")
