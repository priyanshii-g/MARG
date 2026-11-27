import urllib.request
import urllib.parse
import json
import ssl

queries = [
    "North Karbi Anglong Wildlife Sanctuary",
    "North Karbi Anglong",
    "East Karbi Anglong Wildlife Sanctuary",
    "Marat Longri Wildlife Sanctuary",
    "Kaziranga National Park",
    "Nambor Wildlife Sanctuary",
    "Garampani Wildlife Sanctuary"
]

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

headers = {
    "User-Agent": "MARG-OSM-Verification/1.0",
    "Accept": "application/json"
}

results = {}
for q in queries:
    url = f"https://nominatim.openstreetmap.org/search?q={urllib.parse.quote(q)}&format=geojson&polygon_geojson=1&countrycodes=in"
    print(f"Querying {q}...", flush=True)
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10, context=ctx) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            feats = data.get("features", [])
            print(f"  Found {len(feats)} features for {q}", flush=True)
            for f in feats:
                props = f.get("props", f.get("properties", {}))
                geom = f.get("geometry", {})
                print(f"    - {props.get('display_name')} (type: {geom.get('type')})", flush=True)
                results[q] = f
    except Exception as e:
        print(f"  Failed: {e}", flush=True)

with open("scratch/nominatim_sanctuaries.json", "w", encoding="utf-8") as out:
    json.dump(results, out, indent=2)
print("Saved to scratch/nominatim_sanctuaries.json", flush=True)
