import urllib.request
import urllib.parse
import json

q = """[out:json][timeout:15];
(
  way["railway"="rail"](26.7,81.2,27.0,82.2);
);
out geom 20;"""

data = urllib.parse.urlencode({"data": q}).encode("utf-8")
headers = {"User-Agent": "MARG-Ingest/1.0", "Accept": "application/json"}

urls = [
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.nchc.org.tw/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
]

for u in urls:
    try:
        print(f"Testing {u}...")
        req = urllib.request.Request(u, data=data, headers=headers)
        with urllib.request.urlopen(req, timeout=12) as resp:
            d = json.loads(resp.read().decode("utf-8"))
            print(f"SUCCESS {u}: {len(d.get('elements', []))} elements")
            break
    except Exception as e:
        print(f"FAILED {u}: {e}")
