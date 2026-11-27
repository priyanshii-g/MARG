import urllib.request
import urllib.parse
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
headers = {"User-Agent": "MARG-Highway-Planner-Precision-Calibrator/1.0"}

# Let's query Kaziranga relation 9483703 full geometry
url_kazi = "https://nominatim.openstreetmap.org/details.php?osmtype=R&osmid=9483703&polygon_geojson=1&format=json"
req = urllib.request.Request(url_kazi, headers=headers)
try:
    with urllib.request.urlopen(req, context=ctx, timeout=15) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        geom = data.get("geometry", {})
        print(f"Kaziranga geom type: {geom.get('type')}, coords: {len(geom.get('coordinates', []))}")
        with open("scripts/osm_kaziranga_geom.json", "w") as f:
            json.dump(geom, f)
except Exception as e:
    print(f"Kaziranga fetch err: {e}")

# Search North Karbi Anglong
url_nkarbi = "https://nominatim.openstreetmap.org/search?q=North+Karbi+Anglong&format=json&polygon_geojson=1&limit=5"
req = urllib.request.Request(url_nkarbi, headers=headers)
try:
    with urllib.request.urlopen(req, context=ctx, timeout=15) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        print(f"North Karbi items: {len(data)}")
        for it in data:
            print(f"- {it.get('osm_type')} {it.get('osm_id')}: {it.get('display_name')} | bb={it.get('boundingbox')} | geom={it.get('geojson', {}).get('type')}")
            with open("scripts/osm_north_karbi_geom.json", "w") as f:
                json.dump(it, f)
except Exception as e:
    print(f"North Karbi search err: {e}")
