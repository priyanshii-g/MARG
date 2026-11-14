import urllib.request
import urllib.parse
import json

bbox = "25.75,93.10,26.65,93.75"
query = f"""[out:json][timeout:60];
(
  way["waterway"~"river|canal"]({bbox});
);
out geom;"""

data = urllib.parse.urlencode({"data": query}).encode("utf-8")
headers = {
    "User-Agent": "MARG-OSM-Ingestion/1.0",
    "Accept": "application/json"
}

url = "https://overpass-api.de/api/interpreter"
print("Fetching Assam rivers from overpass-api.de...")
req = urllib.request.Request(url, data=data, headers=headers)
with urllib.request.urlopen(req, timeout=60) as resp:
    res = json.loads(resp.read().decode("utf-8"))
elements = res.get("elements", [])
print(f"SUCCESS! Elements retrieved: {len(elements)}")
with open("scripts/assam_real_rivers.json", "w", encoding="utf-8") as f:
    json.dump(elements, f)
