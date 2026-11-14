import urllib.request
import urllib.parse
import json

query = """[out:json][timeout:25];
(
  way["waterway"~"river|canal"](25.75,93.10,26.65,93.75);
  way["railway"="rail"](25.75,93.10,26.65,93.75);
  way["highway"~"trunk|primary"](25.75,93.10,26.65,93.75);
);
out geom;"""

data = urllib.parse.urlencode({"data": query}).encode("utf-8")
headers = {
    "User-Agent": "MARG-OSM-Ingestion/2.0",
    "Accept": "application/json"
}

mirrors = [
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass-api.de/api/interpreter"
]

for url in mirrors:
    print(f"Testing {url}...")
    try:
        req = urllib.request.Request(url, data=data, headers=headers)
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            obj = json.loads(raw)
            elements = obj.get("elements", [])
            print(f"SUCCESS {url}: {len(elements)} elements")
            with open("scripts/assam_real_osm.json", "w", encoding="utf-8") as f:
                json.dump(obj, f)
            break
    except Exception as e:
        print(f"Failed {url}: {e}")
