import urllib.request
import urllib.parse
import json
import ssl

bbox = "26.0,93.2,26.6,93.6"
query = f"""[out:json][timeout:35];
(
  relation["boundary"]({bbox});
  way["boundary"]({bbox});
  relation["leisure"]({bbox});
  way["leisure"]({bbox});
  relation["landuse"="forest"]({bbox});
  way["landuse"="forest"]({bbox});
  relation["natural"="wood"]({bbox});
  way["natural"="wood"]({bbox});
);
out tags;
"""

mirrors = [
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass-api.de/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter"
]

data = urllib.parse.urlencode({"data": query}).encode("utf-8")
headers = {
    "User-Agent": "MARG-OSM-Ingestion/2.0",
    "Accept": "application/json"
}

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

for m in mirrors:
    print(f"Trying {m}...", flush=True)
    try:
        req = urllib.request.Request(m, data=data, headers=headers)
        with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
            content = resp.read().decode("utf-8")
            obj = json.loads(content)
            elements = obj.get("elements", [])
            print(f"SUCCESS {m}: {len(elements)} elements", flush=True)
            for el in elements:
                tags = el.get("tags", {})
                print(f"  {el.get('type')} {el.get('id')}: name={tags.get('name')} | boundary={tags.get('boundary')} | landuse={tags.get('landuse')} | natural={tags.get('natural')} | leisure={tags.get('leisure')}")
            break
    except Exception as e:
        print(f"Failed {m}: {e}", flush=True)
