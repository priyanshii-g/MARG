import urllib.request
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
headers = {"User-Agent": "MARG-Precision-Surveyor/1.0"}

targets = [
    ("kaziranga", "R", 9483703, "Kaziranga National Park"),
    ("north_karbi", "W", 677495329, "North Karbi Anglong WLS"),
    ("east_karbi", "W", 677493281, "East Karbi Anglong WLS"),
    ("marat_longri", "W", 677489261, "Marat Longri WLS"),
    ("nambor", "R", 9405340, "Nambor-Doigrung WLS"),
]

surveyed_geometries = {}

for key, osm_type, osm_id, name in targets:
    url = f"https://nominatim.openstreetmap.org/details.php?osmtype={osm_type}&osmid={osm_id}&polygon_geojson=1&format=json"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            geom = data.get("geometry", {})
            print(f"[{key}] {name}: geom_type={geom.get('type')}")
            surveyed_geometries[key] = {
                "name": name,
                "osm_type": osm_type,
                "osm_id": osm_id,
                "geometry": geom
            }
    except Exception as e:
        print(f"[{key}] Error fetching: {e}")

with open("scripts/osm_surveyed_assam_sanctuaries.json", "w") as f:
    json.dump(surveyed_geometries, f, indent=2)

print("Saved exact OSM geometries to scripts/osm_surveyed_assam_sanctuaries.json")
