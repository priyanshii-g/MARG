import urllib.request
import json
import time

queries = [
    ("assam_dhansiri", "Dhansiri River, Assam"),
    ("assam_diphlu", "Diphlu River, Assam"),
    ("assam_kaliani", "Kaliani River, Assam"),
    ("assam_brahmaputra", "Brahmaputra, Assam"),
    ("assam_nh715", "National Highway 715, Assam"),
    ("assam_railway", "Lumding Furkating railway line, Assam"),
    ("wg_nira", "Nira River, Maharashtra"),
    ("wg_krishna", "Krishna River, Satara"),
    ("wg_venna", "Venna River, Satara"),
    ("wg_nh48", "National Highway 48, Satara"),
    ("wg_sh70", "State Highway 70, Maharashtra"),
    ("wg_railway", "Pune Miraj railway, Maharashtra")
]

results = {}
for key, q in queries:
    url = f"https://nominatim.openstreetmap.org/search?q={urllib.parse.quote(q)}&format=json&polygon_geojson=1"
    req = urllib.request.Request(url, headers={"User-Agent": "MARG-OSM-Verification/2.0"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if data:
                res = data[0]
                results[key] = {
                    "display_name": res.get("display_name"),
                    "osm_type": res.get("osm_type"),
                    "osm_id": res.get("osm_id"),
                    "geojson_type": res.get("geojson", {}).get("type"),
                    "has_coords": bool(res.get("geojson", {}).get("coordinates"))
                }
                print(f"[{key}] Found: {res.get('display_name')[:60]}... Type: {res.get('geojson', {}).get('type')}")
            else:
                print(f"[{key}] No results")
    except Exception as e:
        print(f"[{key}] Error: {e}")
    time.sleep(1) # respectful rate limit for Nominatim

with open("scripts/nominatim_test_results.json", "w", encoding="utf-8") as f:
    json.dump(results, f, indent=2)
