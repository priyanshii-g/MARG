import urllib.request
import urllib.parse
import json
import ssl

bbox = "25.6,93.0,26.8,94.2"
query = f"""[out:json][timeout:35];
(
  relation["boundary"="protected_area"]({bbox});
  way["boundary"="protected_area"]({bbox});
  relation["leisure"="nature_reserve"]({bbox});
  way["leisure"="nature_reserve"]({bbox});
  relation["boundary"="national_park"]({bbox});
  way["boundary"="national_park"]({bbox});
);
out body geom;
"""

mirrors = [
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.nchc.org.tw/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter"
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
    print(f"Trying {m}...")
    try:
        req = urllib.request.Request(m, data=data, headers=headers)
        with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
            content = resp.read().decode("utf-8")
            obj = json.loads(content)
            elements = obj.get("elements", [])
            print(f"SUCCESS {m}: {len(elements)} elements")
            with open("scratch/overpass_assam_raw.json", "w", encoding="utf-8") as f:
                f.write(content)
            for el in elements:
                tags = el.get("tags")
                if tags:
                    print(f"  {el.get('type')} {el.get('id')}: {tags.get('name')} | boundary={tags.get('boundary')} | leisure={tags.get('leisure')} | protect_class={tags.get('protect_class')}")
            break
    except Exception as e:
        print(f"Failed {m}: {e}")
