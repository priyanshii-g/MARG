import urllib.request
import json

def fetch_and_filter_waterways():
    # 1. Brahmaputra: Relation 1067987
    print("Fetching Brahmaputra relation 1067987...")
    url = 'https://api.openstreetmap.org/api/0.6/relation/1067987/full.json'
    req = urllib.request.Request(url, headers={'User-Agent': 'MARG-Routing/1.0'})
    brahmaputra_ways = []
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read().decode('utf-8'))
        nodes = {el['id']: (round(el['lon'], 5), round(el['lat'], 5)) for el in data.get('elements', []) if el.get('type') == 'node'}
        for el in data.get('elements', []):
            if el.get('type') == 'way':
                way_pts = [nodes[nid] for nid in el.get('nodes', []) if nid in nodes]
                if len(way_pts) >= 2 and any(25.75 <= pt[1] <= 26.65 and 93.10 <= pt[0] <= 93.75 for pt in way_pts):
                    brahmaputra_ways.append(way_pts)
        print(f"Brahmaputra ways in Assam corridor: {len(brahmaputra_ways)}")
    except Exception as e:
        print(f"Brahmaputra error: {e}")

    # 2. Dhansiri: Relation 16006122
    print("Fetching Dhansiri relation 16006122...")
    url = 'https://api.openstreetmap.org/api/0.6/relation/16006122/full.json'
    req = urllib.request.Request(url, headers={'User-Agent': 'MARG-Routing/1.0'})
    dhansiri_ways = []
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read().decode('utf-8'))
        nodes = {el['id']: (round(el['lon'], 5), round(el['lat'], 5)) for el in data.get('elements', []) if el.get('type') == 'node'}
        for el in data.get('elements', []):
            if el.get('type') == 'way':
                way_pts = [nodes[nid] for nid in el.get('nodes', []) if nid in nodes]
                if len(way_pts) >= 2 and any(25.75 <= pt[1] <= 26.65 and 93.10 <= pt[0] <= 93.75 for pt in way_pts):
                    dhansiri_ways.append(way_pts)
        print(f"Dhansiri ways in Assam corridor: {len(dhansiri_ways)}")
    except Exception as e:
        print(f"Dhansiri error: {e}")

    # 3. Diphlu: Way 221977087
    print("Fetching Diphlu way 221977087...")
    url = 'https://api.openstreetmap.org/api/0.6/way/221977087/full.json'
    req = urllib.request.Request(url, headers={'User-Agent': 'MARG-Routing/1.0'})
    diphlu_pts = []
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            data = json.loads(r.read().decode('utf-8'))
        nodes = {el['id']: (round(el['lon'], 5), round(el['lat'], 5)) for el in data.get('elements', []) if el.get('type') == 'node'}
        way = next(el for el in data.get('elements', []) if el.get('type') == 'way')
        diphlu_pts = [nodes[nid] for nid in way.get('nodes', []) if nid in nodes]
        print(f"Diphlu points: {len(diphlu_pts)}")
    except Exception as e:
        print(f"Diphlu error: {e}")

    out = {
        "brahmaputra": brahmaputra_ways,
        "dhansiri": dhansiri_ways,
        "diphlu": diphlu_pts
    }
    with open('scripts/assam_real_hydro.json', 'w', encoding='utf-8') as f:
        json.dump(out, f)

fetch_and_filter_waterways()
