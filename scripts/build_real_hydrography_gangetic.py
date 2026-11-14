#!/usr/bin/env python3
import urllib.request
import urllib.parse
import json
import math
from pathlib import Path

# Corridor bounding box for Corridor 3 (Gangetic Plains): Barabanki to Ayodhya
# 26.55, 81.10 to 27.20, 82.25
BBOX = "26.55,81.10,27.20,82.25"

# Query Overpass API for rivers, streams, and canals
query = f"""[out:json][timeout:60];
(
  way["waterway"~"river|canal|stream"]({BBOX});
);
out geom;"""

url = "https://overpass-api.de/api/interpreter"
data = urllib.parse.urlencode({"data": query}).encode("utf-8")
headers = {
    "User-Agent": "MARG-Hydrography-Ingestion/1.0",
    "Accept": "application/json"
}

print("Fetching real watercourse geometry from Overpass API...")
req = urllib.request.Request(url, data=data, headers=headers)
with urllib.request.urlopen(req, timeout=60) as resp:
    result = json.loads(resp.read().decode("utf-8"))

elements = result.get("elements", [])
print(f"Total elements retrieved: {len(elements)}")

rivers = []
drainage = []

# Classify elements into perennial rivers (Layer 6) vs canals/drainage (Layer 12)
for el in elements:
    geom = el.get("geometry", [])
    if len(geom) < 2:
        continue
    
    tags = el.get("tags", {})
    waterway = tags.get("waterway", "")
    name = tags.get("name") or tags.get("name:en") or ""
    
    coords = [[round(pt["lon"], 5), round(pt["lat"], 5)] for pt in geom]
    
    # Check if this is a major/medium river
    is_river = waterway == "river" or any(rv in name.lower() for rv in ["river", "kalyani", "rethi", "gomti", "ghaghara", "saryu", "chhoti sarju", "sarju"])
    
    if is_river:
        # Determine river hierarchy class
        name_lower = name.lower()
        if any(m in name_lower for m in ["gomti", "ghaghara", "saryu", "ganga"]):
            r_class = "MAJOR_PERENNIAL_RIVER"
            width = 160.0 if "gomti" in name_lower else 400.0
        elif any(m in name_lower for m in ["kalyani", "rethi"]):
            r_class = "MEDIUM_PERENNIAL_RIVER"
            width = 55.0
        else:
            r_class = "MINOR_PERENNIAL_STREAM"
            width = 30.0
            
        rivers.append({
            "type": "Feature",
            "properties": {
                "id": f"OSM-RIV-{el['id']}",
                "name": name if name else f"Unnamed River / Watercourse (OSM #{el['id']})",
                "riverClass": r_class,
                "basin": "Ganga-Ghaghara-Gomti Alluvial Basin",
                "designChannelWidthMeters": width,
                "osmWaterway": waterway
            },
            "geometry": {
                "type": "LineString",
                "coordinates": coords
            }
        })
    else:
        # Canal / Stream / Drainage channel (Layer 12)
        is_canal = waterway == "canal" or "canal" in name.lower() or "sharda" in name.lower()
        drainage.append({
            "type": "Feature",
            "properties": {
                "id": f"OSM-DRN-{el['id']}",
                "name": name if name else f"Unnamed Irrigation/Drainage Channel (OSM #{el['id']})",
                "sublayerType": "IRRIGATION_CANAL" if is_canal else "SEASONAL_STREAM",
                "strahlerOrder": 3 if is_canal else 2,
                "watershedId": "WS-GANGETIC_PLAINS-OSM",
                "designWidthMeters": 35.0 if is_canal else 15.0,
                "peakDischargeM3PerSec": 85.0 if is_canal else 25.0,
                "obstructionSensitivity": "MODERATE_CROSS_DRAINAGE",
                "recommendedStructureType": "CANAL_SYPHON_AQUEDUCT_OR_CLEAR_SPAN_BRIDGE" if is_canal else "BOX_CULVERT_IRC_SP_13",
                "osmWaterway": waterway
            },
            "geometry": {
                "type": "LineString",
                "coordinates": coords
            }
        })

print(f"Parsed {len(rivers)} physical river features and {len(drainage)} canal/drainage features.")

# Write perennial rivers GeoJSON
rivers_fc = {
    "type": "FeatureCollection",
    "name": "Barabanki-Ayodhya Indo-Gangetic Plains Real River Network (OSM Ground Truth)",
    "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
    "features": rivers
}

# Write drainage GeoJSON
drainage_fc = {
    "type": "FeatureCollection",
    "name": "Barabanki-Ayodhya Indo-Gangetic Plains Real Drainage & Canal Network (OSM Ground Truth)",
    "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
    "features": drainage
}

out_dir = Path("data/processed/regions/gangetic_plains")
out_dir.mkdir(parents=True, exist_ok=True)

with open(out_dir / "gangetic_plains_perennial_rivers.geojson", "w", encoding="utf-8") as f:
    json.dump(rivers_fc, f, indent=2)

with open(out_dir / "gangetic_plains_drainage_network.geojson", "w", encoding="utf-8") as f:
    json.dump(drainage_fc, f, indent=2)

print("Saved gangetic_plains_perennial_rivers.geojson and gangetic_plains_drainage_network.geojson successfully!")
