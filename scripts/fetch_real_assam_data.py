#!/usr/bin/env python3
import urllib.request
import urllib.parse
import json

bbox = "25.75,93.10,26.65,93.75"
query = f"""[out:json][timeout:35];
(
  way["waterway"~"river|canal|stream"]({bbox});
  way["railway"="rail"]({bbox});
  way["highway"~"trunk|primary|secondary"]({bbox});
);
out geom;"""

urls = [
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass-api.de/api/interpreter"
]
data = urllib.parse.urlencode({"data": query}).encode("utf-8")
for u in urls:
    print(f"Trying mirror {u}...")
    try:
        req = urllib.request.Request(u, data=data, headers={"User-Agent": "MARG-OSM-Ingestion/1.0", "Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            content = resp.read().decode("utf-8")
            elements = json.loads(content).get("elements", [])
            print(f"SUCCESS from {u}! Total elements: {len(elements)}")
            with open("scripts/assam_osm_raw.json", "w", encoding="utf-8") as out:
                json.dump(elements, out)
            break
    except Exception as e:
        print(f"Mirror {u} failed: {e}")
