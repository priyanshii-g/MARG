import urllib.request
import urllib.parse
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
headers = {"User-Agent": "MARG-Highway-Planner-Precision-Calibrator/1.0"}

for q in ["East Karbi Anglong", "Marat Longri", "Nambor", "Garampani"]:
    url = f"https://nominatim.openstreetmap.org/search?q={urllib.parse.quote(q)}&format=json&polygon_geojson=1&limit=3"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=12) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print(f"Results for '{q}': {len(data)}")
            for it in data:
                print(f"  - {it.get('osm_type')} {it.get('osm_id')}: {it.get('display_name')} | bb={it.get('boundingbox')} | geom={it.get('geojson', {}).get('type')}")
                if it.get("geojson", {}).get("type") in ["Polygon", "MultiPolygon"]:
                    filename = f"scripts/osm_{q.replace(' ', '_').lower()}_geom.json"
                    with open(filename, "w") as f:
                        json.dump(it, f)
    except Exception as e:
        print(f"Error {q}: {e}")
