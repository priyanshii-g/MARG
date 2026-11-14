import urllib.request
import json

# Request Western Ghats default route
url = "http://localhost:8080/api/routes/composite?region=western_ghats"
try:
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode('utf-8'))
        print("Success! Route length:", data.get("metadata", {}).get("routeLengthKm"))
        features = data.get("features", [])
        print("Total features:", len(features))
        for f in features:
            role = f.get("properties", {}).get("featureRole")
            geom_type = f.get("geometry", {}).get("type")
            coords = f.get("geometry", {}).get("coordinates")
            if geom_type == "Point":
                print(f"Point: role={role} coords={coords} props={f.get('properties')}")
            elif role != "GREENFIELD_MAINLINE" and role != "MAINLINE":
                print(f"Non-mainline: role={role} geom={geom_type}")
except Exception as e:
    print("Error querying route:", e)
