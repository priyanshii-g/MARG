#!/usr/bin/env python3
"""
Fuse Real OSM Roads into MARG Benchmark Transport Networks.
Enriches transport networks for Western Ghats, Assam, and Gangetic Plains with real
OSM roads down to unclassified/tertiary and residential streets.

Hierarchy:
  - motorway, trunk, primary       -> NATIONAL_HIGHWAY (tier 1)
  - secondary                      -> STATE_HIGHWAY    (tier 2)
  - tertiary                       -> MAJOR_DISTRICT_ROAD (tier 3)
  - unclassified, residential      -> LOCAL_ROAD       (tier 4)
"""
import json
import math
from pathlib import Path

def classify_osm_highway(tags):
    hw = tags.get("highway", "")
    ref = tags.get("ref", "")
    name = tags.get("name", "")
    
    # Check ref/name prefixes
    ref_upper = ref.upper()
    name_upper = name.upper()
    
    if "NH" in ref_upper or "NATIONAL HIGHWAY" in name_upper:
        return "NATIONAL_HIGHWAY", 1
    if "SH" in ref_upper or "STATE HIGHWAY" in name_upper:
        return "STATE_HIGHWAY", 2
    if "MDR" in ref_upper or "ODR" in ref_upper or "DISTRICT ROAD" in name_upper:
        return "MAJOR_DISTRICT_ROAD", 3
        
    if hw in ("motorway", "trunk", "primary", "motorway_link", "trunk_link", "primary_link"):
        return "NATIONAL_HIGHWAY", 1
    elif hw in ("secondary", "secondary_link"):
        return "STATE_HIGHWAY", 2
    elif hw in ("tertiary", "tertiary_link"):
        return "MAJOR_DISTRICT_ROAD", 3
    elif hw in ("unclassified", "residential", "living_street", "road"):
        return "LOCAL_ROAD", 4
    elif hw == "service":
        svc = tags.get("service", "")
        if svc in ("parking_aisle", "driveway", "carwash"):
            return None, None
        return "LOCAL_ROAD", 4
    return None, None

def fuse_corridor_roads(cache_file, base_transport_file, corridor_prefix, bbox=None):
    base_path = Path(base_transport_file)
    if not base_path.exists():
        print(f"Base transport file not found: {base_path}")
        return
        
    with open(base_path, "r", encoding="utf-8") as f:
        base_data = json.load(f)
        
    existing_features = base_data.get("features", [])
    print(f"[{corridor_prefix}] Existing features in base file: {len(existing_features)}")
    
    # Collect existing coordinate points to avoid duplicate segments
    existing_line_sigs = set()
    for feat in existing_features:
        geom = feat.get("geometry", {})
        gtype = geom.get("type")
        coords = geom.get("coordinates", [])
        if gtype == "LineString" and len(coords) >= 2:
            sig = (round(coords[0][0], 4), round(coords[0][1], 4), round(coords[-1][0], 4), round(coords[-1][1], 4))
            existing_line_sigs.add(sig)
        elif gtype == "MultiLineString":
            for line in coords:
                if len(line) >= 2:
                    sig = (round(line[0][0], 4), round(line[0][1], 4), round(line[-1][0], 4), round(line[-1][1], 4))
                    existing_line_sigs.add(sig)
                    
    cache_path = Path(cache_file)
    if not cache_path.exists():
        print(f"Cache file not found: {cache_path}")
        return
        
    with open(cache_path, "r", encoding="utf-8") as f:
        cache_data = json.load(f)
        
    highways = cache_data.get("highways", [])
    if isinstance(highways, dict):
        highways = list(highways.values())
        
    print(f"[{corridor_prefix}] Cache highways loaded: {len(highways)}")
    
    added_count = 0
    by_category = {}
    
    for hw in highways:
        wid = hw.get("id")
        tags = hw.get("tags", {})
        coords = hw.get("coords", [])
        
        if len(coords) < 2:
            continue
            
        category, tier = classify_osm_highway(tags)
        if not category:
            continue
            
        # Check bbox filter if provided (min_lat, min_lon, max_lat, max_lon)
        if bbox:
            min_lat, min_lon, max_lat, max_lon = bbox
            if not any(min_lat <= pt[1] <= max_lat and min_lon <= pt[0] <= max_lon for pt in coords):
                continue
                
        sig1 = (round(coords[0][0], 4), round(coords[0][1], 4), round(coords[-1][0], 4), round(coords[-1][1], 4))
        sig2 = (round(coords[-1][0], 4), round(coords[-1][1], 4), round(coords[0][0], 4), round(coords[0][1], 4))
        if sig1 in existing_line_sigs or sig2 in existing_line_sigs:
            continue
            
        existing_line_sigs.add(sig1)
        
        name = tags.get("name") or tags.get("ref") or f"Local Road (way {wid})"
        ref = tags.get("ref") or tags.get("name") or f"OSM-{wid}"
        
        feat_id = f"{corridor_prefix}-OSM-{wid}"
        
        new_feat = {
            "type": "Feature",
            "properties": {
                "id": feat_id,
                "ref": ref,
                "name": name,
                "category": category,
                "hierarchyTier": tier,
                "osmWayId": wid,
                "highwayType": tags.get("highway", "")
            },
            "geometry": {
                "type": "LineString",
                "coordinates": coords
            }
        }
        existing_features.append(new_feat)
        added_count += 1
        by_category[category] = by_category.get(category, 0) + 1
        
    print(f"[{corridor_prefix}] Added {added_count} real OSM roads: {by_category}")
    print(f"[{corridor_prefix}] Total features now: {len(existing_features)}")
    
    with open(base_path, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": existing_features}, f, indent=2)
    print(f"[{corridor_prefix}] Successfully updated {base_path}\n")

def main():
    print("=== FUSING REAL OSM ROADS INTO BENCHMARK TRANSPORT NETWORKS ===\n")
    
    # 1. Western Ghats
    fuse_corridor_roads(
        "scripts/osm_wg_transport_cache.json",
        "data/processed/regions/western_ghats/western_ghats_transport_network.geojson",
        "WG",
        bbox=(17.50, 73.60, 18.60, 74.40)
    )

    # 2. Gangetic Plains
    fuse_corridor_roads(
        "scripts/osm_gp_transport_cache.json",
        "data/processed/regions/gangetic_plains/gangetic_plains_transport_network.geojson",
        "GP",
        bbox=(26.55, 81.10, 27.20, 82.25)
    )

if __name__ == "__main__":
    main()
