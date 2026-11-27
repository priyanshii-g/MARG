import json
import math

with open("scripts/osm_surveyed_assam_sanctuaries.json", encoding="utf-8") as f:
    osm_data = json.load(f)

def extract_all_outer_rings(geom):
    gtype = geom.get("type")
    coords = geom.get("coordinates", [])
    rings = []
    if gtype == "Polygon":
        if coords and len(coords[0]) >= 3:
            rings.append([[round(p[1], 5), round(p[0], 5)] for p in coords[0]])
    elif gtype == "MultiPolygon":
        for poly in coords:
            if poly and len(poly[0]) >= 3:
                rings.append([[round(p[1], 5), round(p[0], 5)] for p in poly[0]])
    return rings

def expand_esz_10km(ring, buffer_km=10.0):
    # Uniform 10.0 km outward buffer (~0.0905 deg latitude, ~0.0905/cos(lat) longitude)
    c_lat = sum(p[0] for p in ring) / len(ring)
    c_lon = sum(p[1] for p in ring) / len(ring)
    cos_lat = max(0.2, math.cos(math.radians(c_lat)))
    d_deg = buffer_km / 110.574
    esz = []
    for lat, lon in ring:
        dy = lat - c_lat
        dx = (lon - c_lon) * cos_lat
        dist = math.hypot(dy, dx)
        if dist > 1e-6:
            scale = (dist + d_deg) / dist
            esz.append([
                round(c_lat + dy * scale, 5),
                round(c_lon + (dx * scale) / cos_lat, 5)
            ])
        else:
            esz.append([round(lat + d_deg, 5), round(lon, 5)])
    return esz

calibrated_updates = {}

for key in ["kaziranga", "north_karbi", "east_karbi", "marat_longri", "nambor"]:
    entry = osm_data.get(key)
    if not entry:
        continue
    rings = extract_all_outer_rings(entry["geometry"])
    if not rings:
        continue
    primary_ring = max(rings, key=len)
    all_lats = [p[0] for r in rings for p in r]
    all_lons = [p[1] for r in rings for p in r]
    min_lat, max_lat = min(all_lats), max(all_lats)
    min_lon, max_lon = min(all_lons), max(all_lons)
    c_lat = (min_lat + max_lat) / 2.0
    c_lon = (min_lon + max_lon) / 2.0
    esz_ring = expand_esz_10km(primary_ring, 10.0)

    calibrated_updates[key] = {
        "name": entry["name"],
        "minLat": round(min_lat, 5),
        "maxLat": round(max_lat, 5),
        "minLon": round(min_lon, 5),
        "maxLon": round(max_lon, 5),
        "centerLat": round(c_lat, 5),
        "centerLon": round(c_lon, 5),
        "boundaryPolygon": primary_ring,
        "boundaryPolygons": rings,
        "eszPolygon": esz_ring
    }
    print(f"{key}: {len(rings)} rings (primary={len(primary_ring)} pts), bounds [{min_lat:.4f}..{max_lat:.4f}, {min_lon:.4f}..{max_lon:.4f}]")

with open("scripts/calibrated_assam_osm.json", "w", encoding="utf-8") as f:
    json.dump(calibrated_updates, f, indent=2)
print("Saved to scripts/calibrated_assam_osm.json")
