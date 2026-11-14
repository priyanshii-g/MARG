import urllib.request
import urllib.parse
import json
import time

items_to_search = [
    ("assam_brahmaputra", "Brahmaputra, Tezpur, Assam"),
    ("assam_nh715", "National Highway 715, Bokakhat, Assam"),
    ("assam_nh129", "National Highway 129, Golaghat, Assam"),
    ("assam_railway_furkating", "Furkating Junction, Assam"),
    ("assam_railway_bokajan", "Bokajan railway station, Assam"),
    ("wg_krishna_wai", "Krishna River, Wai, Satara"),
    ("wg_railway_jejuri", "Jejuri railway station, Maharashtra"),
    ("wg_railway_shirwal", "Nira railway station, Maharashtra"),
    ("gp_railway_barabanki", "Barabanki Junction, Uttar Pradesh"),
    ("gp_railway_ayodhya", "Ayodhya Cantt railway station, Uttar Pradesh"),
    ("gp_nh27", "National Highway 27, Barabanki, Uttar Pradesh")
]

results = {}
for key, q in items_to_search:
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
                    "lat": float(res.get("lat")),
                    "lon": float(res.get("lon")),
                    "geojson_type": res.get("geojson", {}).get("type"),
                }
                print(f"[{key}] Found: {res.get('display_name')[:60]} ({res.get('osm_type')}:{res.get('osm_id')}) at {res.get('lat')},{res.get('lon')}")
            else:
                print(f"[{key}] Not found")
    except Exception as e:
        print(f"[{key}] Error: {e}")
    time.sleep(1)

with open("scripts/features_details.json", "w", encoding="utf-8") as f:
    json.dump(results, f, indent=2)
