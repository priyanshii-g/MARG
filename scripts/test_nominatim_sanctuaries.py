import urllib.request
import urllib.parse
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

headers = {"User-Agent": "MARG-Highway-Planner-Precision-Calibrator/1.0 (contact@marg.gov.in)"}

queries = [
    "North Karbi Anglong Wildlife Sanctuary",
    "Kaziranga National Park",
    "East Karbi Anglong Wildlife Sanctuary",
    "Nambor Wildlife Sanctuary",
    "Marat Longri Wildlife Sanctuary"
]

results = {}
for q in queries:
    url = f"https://nominatim.openstreetmap.org/search?q={urllib.parse.quote(q)}&format=json&polygon_geojson=1&limit=1"
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, context=ctx, timeout=8) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if data:
                item = data[0]
                geom = item.get("geojson", {})
                bb = item.get("boundingbox", [])
                results[q] = {
                    "osm_id": item.get("osm_id"),
                    "osm_type": item.get("osm_type"),
                    "display_name": item.get("display_name"),
                    "boundingbox": bb,
                    "geom_type": geom.get("type"),
                    "coord_count": len(geom.get("coordinates", []))
                }
                print(f"FOUND {q}: id={item.get('osm_id')}, type={geom.get('type')}, bb={bb}")
            else:
                print(f"NOT FOUND: {q}")
    except Exception as e:
        print(f"ERROR {q}: {e}")

print("\nDone Nominatim queries.")
