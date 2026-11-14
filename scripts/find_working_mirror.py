import urllib.request
import urllib.parse
import json

bbox = "25.75,93.10,26.65,93.75"
query = f"""[out:json][timeout:25];
(
  way["waterway"~"river|canal"]({bbox});
);
out geom;"""
data = urllib.parse.urlencode({"data": query}).encode("utf-8")
headers = {
    "User-Agent": "MARG-OSM-Ingestion/2.0",
    "Accept": "application/json"
}

mirrors = [
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.openstreetmap.ru/cgi/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.nchc.org.tw/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter"
]

for m in mirrors:
    print(f"Trying {m}...")
    try:
        req = urllib.request.Request(m, data=data, headers=headers)
        with urllib.request.urlopen(req, timeout=15) as resp:
            content = resp.read().decode("utf-8")
            obj = json.loads(content)
            elements = obj.get("elements", [])
            print(f"SUCCESS {m}: {len(elements)} elements")
            with open("scripts/assam_real_rivers.json", "w", encoding="utf-8") as f:
                json.dump(elements, f)
            break
    except Exception as e:
        print(f"Failed {m}: {e}")
