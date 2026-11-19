import urllib.request
import urllib.parse
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

query = """[out:json][timeout:25];
(
  relation["name"~"Karbi Anglong|Kaziranga|Nambor",i];
  way["name"~"Karbi Anglong|Kaziranga|Nambor",i]["leisure"="nature_reserve"];
  way["name"~"Karbi Anglong|Kaziranga|Nambor",i]["boundary"="protected_area"];
);
out tags bb;
"""

mirrors = [
    "https://overpass.kumi.systems/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]

for url in mirrors:
    print(f"Trying {url}...")
    try:
        data = ("data=" + urllib.parse.quote(query)).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
        with urllib.request.urlopen(req, context=ctx, timeout=12) as resp:
            res = json.loads(resp.read().decode("utf-8"))
            print(f"Success on {url}! Found {len(res.get('elements', []))} elements:")
            for el in res.get("elements", []):
                tags = el.get("tags", {})
                bounds = el.get("bounds", {})
                print(f"  - {el.get('type')} {el.get('id')}: {tags.get('name')} | tags: {tags.get('boundary')}, {tags.get('leisure')} | bb: {bounds}")
            break
    except Exception as e:
        print(f"Failed {url}: {e}")
