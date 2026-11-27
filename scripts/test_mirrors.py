import urllib.request
import urllib.parse
import json

mirrors = [
    'https://overpass-api.de/api/interpreter',
    'https://lz4.overpass-api.de/api/interpreter',
    'https://overpass.kumi.systems/api/interpreter'
]
query = '[out:json][timeout:25];way["highway"~"^(trunk|primary|secondary|tertiary|unclassified|residential)$"](18.20,73.80,18.45,73.95);out geom 1000;'
data = urllib.parse.urlencode({'data': query}).encode('utf-8')
for m in mirrors:
    print('Testing mirror:', m)
    try:
        req = urllib.request.Request(m, data=data, headers={'User-Agent': 'MARG-Test/1.0'})
        with urllib.request.urlopen(req, timeout=15) as r:
            res = json.loads(r.read().decode('utf-8'))
            print('  -> SUCCESS! Elements:', len(res.get('elements', [])))
            break
    except Exception as e:
        print('  -> Failed:', e)
