#!/usr/bin/env python3
"""
One-Time Offline Pre-Bake of Real OSM Forests/Woodlands + Local GeoTIFF Raster Fusion
for MARG Benchmark & Pre-Cached Deep Corridors.

Key Guarantees:
  1. 100% Offline at Runtime: Writes static *_lulc_samples.json files on disk at 0.01 deg
     resolution (matching RegionalLulcProvider.resolutionDeg = 0.01) so Spring Boot makes
     ZERO online requests during evaluation/demo.
  2. Strict Preservation of Existing Data: Never removes or overwrites any existing
     non-background class (FOREST, BUILT_UP, WETLAND_WATER) from the original LULC file,
     and saves an untouched backup under scripts/cache/orig_<region>_lulc_samples.json.
  3. Never touches Assam (data/cache/bhuvan-lulc-samples.json already has 3,078 real Bhuvan
     FOREST samples).

Usage:
  python scripts/bake_offline_lulc.py --fetch-only   # Fetch & cache OSM forest/water polygons
  python scripts/bake_offline_lulc.py --bake-only    # Purely offline fuse + write JSONs
  python scripts/bake_offline_lulc.py                # Both fetch (if uncached) and bake
"""

import argparse
import collections
import json
import math
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image

WGS84_A = 6378137.0
WGS84_E2 = 0.0066943799901413165
UTM_K0 = 0.9996

OVERPASS_MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]

CACHE_DIR = Path("scripts/cache")
CACHE_DIR.mkdir(parents=True, exist_ok=True)

REGIONS = {
    "western_ghats": {
        "zone": 43,
        "dir": Path("data/processed/regions/western_ghats"),
        "default_bg": "GRASS_GRAZING",
        "slope_forest_deg": 13.0,
    },
    "gangetic_plains": {
        "zone": 44,
        "dir": Path("data/processed/regions/gangetic_plains"),
        "default_bg": "AGRICULTURE",
        "slope_forest_deg": 18.0,
    },
    "corridor_2015_7685_2385_7740": {
        "zone": 43,
        "dir": Path("data/processed/regions/corridor_2015_7685_2385_7740"),
        "default_bg": "AGRICULTURE",
        "slope_forest_deg": 13.0,
    },
}


def wgs84_to_utm(lat_deg: float, lon_deg: float, zone: int):
    lat = math.radians(lat_deg)
    lon = math.radians(lon_deg)
    lon0 = math.radians((zone - 1) * 6.0 - 180.0 + 3.0)
    ep2 = WGS84_E2 / (1.0 - WGS84_E2)
    sin_lat = math.sin(lat)
    cos_lat = math.cos(lat)
    tan_lat = math.tan(lat)
    n = WGS84_A / math.sqrt(1.0 - WGS84_E2 * sin_lat * sin_lat)
    t = tan_lat * tan_lat
    c = ep2 * cos_lat * cos_lat
    a_coef = cos_lat * (lon - lon0)
    m = WGS84_A * (
        (1.0 - WGS84_E2 / 4.0 - 3.0 * WGS84_E2**2 / 64.0 - 5.0 * WGS84_E2**3 / 256.0) * lat
        - (3.0 * WGS84_E2 / 8.0 + 3.0 * WGS84_E2**2 / 32.0 + 45.0 * WGS84_E2**3 / 1024.0) * math.sin(2.0 * lat)
        + (15.0 * WGS84_E2**2 / 256.0 + 45.0 * WGS84_E2**3 / 1024.0) * math.sin(4.0 * lat)
        - (35.0 * WGS84_E2**3 / 3072.0) * math.sin(6.0 * lat)
    )
    easting = 500000.0 + UTM_K0 * n * (
        a_coef
        + (1.0 - t + c) * a_coef**3 / 6.0
        + (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * a_coef**5 / 120.0
    )
    northing = UTM_K0 * (
        m
        + n * tan_lat * (
            a_coef**2 / 2.0
            + (5.0 - t + 9.0 * c + 4.0 * c * c) * a_coef**4 / 24.0
            + (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * a_coef**6 / 720.0
        )
    )
    return easting, northing


class LocalGeoTiffSampler:
    """Zero-dependency GeoTIFF sampler using Pillow + numpy."""

    def __init__(self, tif_path: Path, zone: int):
        self.valid = False
        self.zone = zone
        if not tif_path or not tif_path.exists():
            return
        try:
            Image.MAX_IMAGE_PIXELS = None
            with Image.open(tif_path) as img:
                tags = img.tag_v2
                if 33550 not in tags or 33922 not in tags:
                    return
                scale = tags[33550]
                tie = tags[33922]
                self.scale_x = float(scale[0])
                self.scale_y = float(scale[1])
                self.origin_x = float(tie[3])
                self.origin_y = float(tie[4])
                self.arr = np.array(img)
                self.height, self.width = self.arr.shape[:2]
                self.valid = True
        except Exception as exc:
            print(f"  [warn] Could not read {tif_path.name}: {exc}")

    def sample(self, lat: float, lon: float):
        if not self.valid:
            return None
        x, y = wgs84_to_utm(lat, lon, self.zone)
        col = int((x - self.origin_x) / self.scale_x)
        row = int((self.origin_y - y) / self.scale_y)
        if 0 <= row < self.height and 0 <= col < self.width:
            val = float(self.arr[row, col])
            if math.isnan(val) or val < -9000.0 or val == 255.0:
                return None
            return val
        return None


def compute_region_bbox(orig_samples):
    lats = [float(s["lat"]) for s in orig_samples]
    lons = [float(s["lon"]) for s in orig_samples]
    return min(lats), min(lons), max(lats), max(lons)


def fetch_osm_forest_water(region_id: str, bbox, force: bool = False):
    cache_file = CACHE_DIR / f"osm_lulc_{region_id}.json"
    if cache_file.exists() and not force:
        print(f"  [cache] Reusing {cache_file}")
        return json.loads(cache_file.read_text(encoding="utf-8"))

    min_lat, min_lon, max_lat, max_lon = bbox
    # Tile large bboxes into <=1.2 deg tiles so Overpass stays fast & reliable
    lat_steps = max(1, int(math.ceil((max_lat - min_lat) / 1.2)))
    lon_steps = max(1, int(math.ceil((max_lon - min_lon) / 1.2)))
    d_lat = (max_lat - min_lat) / lat_steps
    d_lon = (max_lon - min_lon) / lon_steps

    all_polys = []
    seen_ids = set()

    for i in range(lat_steps):
        for j in range(lon_steps):
            s_lat = round(min_lat + i * d_lat, 4)
            n_lat = round(min_lat + (i + 1) * d_lat, 4)
            w_lon = round(min_lon + j * d_lon, 4)
            e_lon = round(min_lon + (j + 1) * d_lon, 4)
            query = (
                f"[out:json][timeout:45];("
                f'way["landuse"="forest"]({s_lat},{w_lon},{n_lat},{e_lon});'
                f'relation["landuse"="forest"]({s_lat},{w_lon},{n_lat},{e_lon});'
                f'way["natural"="wood"]({s_lat},{w_lon},{n_lat},{e_lon});'
                f'relation["natural"="wood"]({s_lat},{w_lon},{n_lat},{e_lon});'
                f'way["boundary"="forest"]({s_lat},{w_lon},{n_lat},{e_lon});'
                f'relation["boundary"="forest"]({s_lat},{w_lon},{n_lat},{e_lon});'
                f'way["natural"="water"]({s_lat},{w_lon},{n_lat},{e_lon});'
                f'way["natural"="wetland"]({s_lat},{w_lon},{n_lat},{e_lon});'
                f");out tags geom;"
            )
            data = None
            for mirror in OVERPASS_MIRRORS:
                try:
                    req = urllib.request.Request(
                        mirror,
                        data=urllib.parse.urlencode({"data": query}).encode("utf-8"),
                        headers={"User-Agent": "MARG-Offline-LULC-Baker/1.0"},
                    )
                    with urllib.request.urlopen(req, timeout=50) as resp:
                        data = json.loads(resp.read().decode("utf-8"))
                        break
                except Exception as exc:
                    print(f"    [mirror retry] {mirror} failed for tile ({i},{j}): {exc}")
                    time.sleep(1.0)
            if not data:
                print(f"  [warn] Tile ({i},{j}) skipped (Overpass unavailable)")
                continue

            for el in data.get("elements", []):
                eid = f"{el.get('type')}/{el.get('id')}"
                if eid in seen_ids:
                    continue
                seen_ids.add(eid)
                tags = el.get("tags", {})
                if (
                    tags.get("landuse") == "forest"
                    or tags.get("natural") == "wood"
                    or tags.get("boundary") == "forest"
                ):
                    cls = "FOREST"
                elif tags.get("natural") in ("water", "wetland"):
                    cls = "WETLAND_WATER"
                else:
                    continue

                rings = []
                if el.get("type") == "way" and el.get("geometry"):
                    ring = [(pt["lat"], pt["lon"]) for pt in el["geometry"]]
                    if len(ring) >= 3:
                        rings.append(ring)
                elif el.get("type") == "relation" and el.get("members"):
                    for mem in el["members"]:
                        if mem.get("role") in ("outer", "") and mem.get("geometry"):
                            ring = [(pt["lat"], pt["lon"]) for pt in mem["geometry"]]
                            if len(ring) >= 3:
                                rings.append(ring)

                for ring in rings:
                    rlats = [p[0] for p in ring]
                    rlons = [p[1] for p in ring]
                    all_polys.append(
                        {
                            "cls": cls,
                            "name": tags.get("name", ""),
                            "bbox": [min(rlats), min(rlons), max(rlats), max(rlons)],
                            "ring": [[round(p[0], 5), round(p[1], 5)] for p in ring],
                        }
                    )
            print(f"    tile ({i+1}/{lat_steps}, {j+1}/{lon_steps}) -> cumulative {len(all_polys)} polygons")
            time.sleep(0.5)

    payload = {"regionId": region_id, "bbox": list(bbox), "polygons": all_polys}
    cache_file.write_text(json.dumps(payload), encoding="utf-8")
    print(f"  [saved] {len(all_polys)} OSM forest/water polygons -> {cache_file}")
    return payload


def point_in_ring(lat: float, lon: float, ring) -> bool:
    inside = False
    n = len(ring)
    j = n - 1
    for i in range(n):
        yi, xi = ring[i]
        yj, xj = ring[j]
        if ((yi > lat) != (yj > lat)) and (
            lon < (xj - xi) * (lat - yi) / ((yj - yi) if (yj - yi) != 0 else 1e-12) + xi
        ):
            inside = not inside
        j = i
    return inside


def classify_from_osm(lat: float, lon: float, forest_polys, water_polys):
    for p in forest_polys:
        b = p["bbox"]
        if b[0] <= lat <= b[2] and b[1] <= lon <= b[3]:
            if point_in_ring(lat, lon, p["ring"]):
                return "FOREST", p.get("name") or "OSM_FOREST"
    for p in water_polys:
        b = p["bbox"]
        if b[0] <= lat <= b[2] and b[1] <= lon <= b[3]:
            if point_in_ring(lat, lon, p["ring"]):
                return "WETLAND_WATER", p.get("name") or "OSM_WATER"
    return None, None


def bake_region(region_id: str, cfg: dict, do_fetch: bool, do_bake: bool, force_fetch: bool):
    reg_dir: Path = cfg["dir"]
    lulc_path = reg_dir / f"{region_id}_lulc_samples.json"
    if not lulc_path.exists():
        print(f"[skip] {lulc_path} not found")
        return

    print(f"\n=== Processing {region_id} ===")
    backup_path = CACHE_DIR / f"orig_{region_id}_lulc_samples.json"
    if not backup_path.exists():
        backup_path.write_text(lulc_path.read_text(encoding="utf-8"), encoding="utf-8")
        print(f"  [backup] Saved original samples -> {backup_path}")

    # Always read from untouched original backup so repeated runs are idempotent & non-destructive
    orig_samples = json.loads(backup_path.read_text(encoding="utf-8"))
    bbox = compute_region_bbox(orig_samples)
    print(f"  Original samples: {len(orig_samples)} | bbox={bbox} | counts={dict(collections.Counter(s['type'] for s in orig_samples))}")

    osm_data = None
    if do_fetch:
        osm_data = fetch_osm_forest_water(region_id, bbox, force=force_fetch)
    else:
        cache_file = CACHE_DIR / f"osm_lulc_{region_id}.json"
        if cache_file.exists():
            osm_data = json.loads(cache_file.read_text(encoding="utf-8"))
            print(f"  [offline] Loaded {len(osm_data.get('polygons', []))} cached OSM polygons from {cache_file}")

    if not do_bake:
        return

    forest_polys = [p for p in (osm_data or {}).get("polygons", []) if p["cls"] == "FOREST"]
    water_polys = [p for p in (osm_data or {}).get("polygons", []) if p["cls"] == "WETLAND_WATER"]

    # Index original non-background samples so EVERY old FOREST / BUILT_UP / WETLAND_WATER is preserved 100%
    orig_by_key = {}
    for s in orig_samples:
        k = (round(float(s["lat"]), 2), round(float(s["lon"]), 2))
        orig_by_key[k] = s

    # Load local offline GeoTIFF rasters for this region
    zone = cfg["zone"]
    slope_tif = LocalGeoTiffSampler(reg_dir / f"{region_id}_slope_utm{zone}.tif", zone)
    wildlife_tif = LocalGeoTiffSampler(reg_dir / f"{region_id}_wildlife_esz_corridors_utm{zone}.tif", zone)
    pa_tif = LocalGeoTiffSampler(reg_dir / f"{region_id}_protected_area_mask_utm{zone}.tif", zone)
    settle_tif = LocalGeoTiffSampler(reg_dir / f"{region_id}_settlement_exposure_utm{zone}.tif", zone)
    water_tif = LocalGeoTiffSampler(reg_dir / f"{region_id}_water_bodies_utm{zone}.tif", zone)

    min_lat, min_lon, max_lat, max_lon = bbox
    default_bg = cfg["default_bg"]
    slope_forest_deg = cfg["slope_forest_deg"]

    out_samples = []
    preserved_old = 0
    osm_forest_hits = 0
    raster_forest_hits = 0

    lat_steps = int(round((max_lat - min_lat) / 0.01)) + 1
    lon_steps = int(round((max_lon - min_lon) / 0.01)) + 1

    for i in range(lat_steps):
        lat = round(min_lat + i * 0.01, 2)
        for j in range(lon_steps):
            lon = round(min_lon + j * 0.01, 2)
            key = (lat, lon)
            old = orig_by_key.get(key)

            # Rule 1: Strictly preserve existing non-background classifications from old model
            if old and old.get("type") in ("FOREST", "BUILT_UP", "WETLAND_WATER"):
                out_samples.append(old)
                preserved_old += 1
                continue

            l_class = old["type"] if old else default_bg
            source_tag = old.get("rawFeatureInfo", f"LOCAL_OFFLINE_{region_id.upper()}") if old else f"OFFLINE_PREBAKE_{region_id.upper()}"

            # Rule 2: Real OSM Forest/Woodland & Water Polygons (cached offline)
            osm_cls, osm_name = classify_from_osm(lat, lon, forest_polys, water_polys)
            if osm_cls == "FOREST":
                l_class = "FOREST"
                source_tag = f"OSM_OFFLINE_FOREST:{osm_name[:40]}"
                osm_forest_hits += 1
            elif osm_cls == "WETLAND_WATER":
                l_class = "WETLAND_WATER"
                source_tag = f"OSM_OFFLINE_WATER:{osm_name[:40]}"
            else:
                # Rule 3: Local GeoTIFF Multi-Raster Fusion (100% Offline)
                settle_val = settle_tif.sample(lat, lon)
                water_val = water_tif.sample(lat, lon)
                pa_val = pa_tif.sample(lat, lon)
                wild_val = wildlife_tif.sample(lat, lon)
                slope_val = slope_tif.sample(lat, lon)

                if settle_val is not None and settle_val >= 3.0:
                    l_class = "BUILT_UP"
                    source_tag = f"OFFLINE_SETTLEMENT_RASTER_{region_id.upper()}"
                elif water_val is not None and water_val >= 3.0:
                    l_class = "WETLAND_WATER"
                    source_tag = f"OFFLINE_WATER_RASTER_{region_id.upper()}"
                elif (pa_val is not None and pa_val >= 1.0) or (wild_val is not None and wild_val >= 2.0):
                    l_class = "FOREST"
                    source_tag = f"OFFLINE_WILDLIFE_FOREST_CORRIDOR_{region_id.upper()}"
                    raster_forest_hits += 1
                elif slope_val is not None and slope_val >= slope_forest_deg:
                    l_class = "FOREST"
                    source_tag = f"OFFLINE_DEM_HILL_WOODLAND_{region_id.upper()}"
                    raster_forest_hits += 1

            out_samples.append(
                {
                    "lat": lat,
                    "lon": lon,
                    "type": l_class,
                    "rawFeatureInfo": source_tag,
                    "remoteSuccess": True,
                    "error": None,
                }
            )

    lulc_path.write_text(json.dumps(out_samples, indent=2), encoding="utf-8")
    counts = dict(collections.Counter(s["type"] for s in out_samples))
    print(f"  [done] Wrote {len(out_samples)} 0.01-deg offline samples -> {lulc_path}")
    print(f"         preservedOld={preserved_old} | osmForestHits={osm_forest_hits} | rasterForestHits={raster_forest_hits}")
    print(f"         Final counts: {counts}")


def main():
    parser = argparse.ArgumentParser(description="Pre-bake real OSM forests + local GeoTIFFs into offline MARG LULC files.")
    parser.add_argument("--fetch-only", action="store_true", help="Only fetch and cache OSM forest/water polygons.")
    parser.add_argument("--bake-only", action="store_true", help="Only fuse cached OSM + local GeoTIFFs offline (no network).")
    parser.add_argument("--force-fetch", action="store_true", help="Re-download OSM polygons even if cached.")
    parser.add_argument("--regions", nargs="*", default=list(REGIONS.keys()), help="Region IDs to process.")
    args = parser.parse_args()

    do_fetch = not args.bake_only
    do_bake = not args.fetch_only

    for rid in args.regions:
        if rid not in REGIONS:
            print(f"[warn] Unknown region: {rid}")
            continue
        bake_region(rid, REGIONS[rid], do_fetch=do_fetch, do_bake=do_bake, force_fetch=args.force_fetch)


if __name__ == "__main__":
    main()
