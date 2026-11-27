#!/usr/bin/env python3
"""
Fuse Real OpenStreetMap (OSM) Buildings, Hospitals, Institutional/Religious Campuses,
and Settlement/Colony Boundaries into MARG Benchmark Corridor Data Layers (30m UTM Rasters + GeoJSONs).

Supports all 3 benchmark corridors:
  1. western_ghats   (Pune-Bhor-Satara, Maharashtra, UTM Zone 43N / EPSG:32643)
  2. assam           (Kaziranga-Karbi Anglong, Assam, UTM Zone 46N / EPSG:32646)
  3. gangetic_plains (Barabanki-Ramsanehighat-Ayodhya, UP, UTM Zone 44N / EPSG:32644)

Raw Overpass responses are cached locally under scripts/cache/ so re-runs are offline and fast.
"""

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, TiffImagePlugin

WGS84_A = 6378137.0
WGS84_E2 = 0.0066943799901413165
UTM_K0 = 0.9996
RES_METERS = 30.0
INSTITUTIONAL_SAFETY_BUFFER_M = 75.0
MAX_BUILDINGS_PER_CORRIDOR = 16000

OVERPASS_MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.openstreetmap.ru/cgi/interpreter",
]

CORRIDORS = {
    "western_ghats": {
        "zone": 43,
        "epsg": 32643,
        "bbox": (17.60, 73.65, 18.50, 74.30),  # (min_lat, min_lon, max_lat, max_lon)
        "default_district": "Pune-Satara",
        "settlements_out": Path("data/processed/regions/western_ghats/western_ghats_settlement_boundaries.geojson"),
        "buildings_out": Path("data/processed/regions/western_ghats/western_ghats_building_footprints.geojson"),
        "building_raster_out": Path("data/processed/regions/western_ghats/western_ghats_building_density_utm43.tif"),
        "settlement_raster_out": Path("data/processed/regions/western_ghats/western_ghats_settlement_exposure_utm43.tif"),
        "transport_geojson": Path("data/processed/regions/western_ghats/western_ghats_transport_network.geojson"),
        "spine": [
            (18.365, 73.855),
            (18.310, 73.868),
            (18.230, 73.890),
            (18.150, 73.930),
            (18.025, 73.952),
            (17.875, 73.978),
            (17.760, 74.000),
            (17.695, 74.015),
        ],
        # Sub-boxes along Pune-Satara corridor for crisp OSM building=* coverage
        "building_tiles": [
            (18.32, 73.78, 18.42, 73.95),  # Pune south / Katraj / Shindewadi / Khed Shivapur
            (18.18, 73.82, 18.32, 73.98),  # Khed Shivapur - Nasrapur - Kapurhol
            (18.04, 73.85, 18.18, 74.02),  # Shirwal - Khandala ghat north
            (17.90, 73.88, 18.04, 74.05),  # Khandala - Wai - Surur
            (17.76, 73.90, 17.90, 74.08),  # Bhuinj - Pachwad - Satara north
            (17.64, 73.94, 17.76, 74.10),  # Satara city & Shendre / NH-48 bypass
        ],
    },
    "assam": {
        "zone": 46,
        "epsg": 32646,
        "bbox": (25.75, 93.10, 26.65, 93.75),
        "default_district": "Karbi Anglong",
        "settlements_out": Path("data/processed/buildings/assam_settlement_boundaries.geojson"),
        "buildings_out": Path("data/processed/buildings/assam_building_footprints.geojson"),
        "building_raster_out": Path("data/processed/buildings/assam_building_density_utm46.tif"),
        "settlement_raster_out": Path("data/processed/settlements/assam_settlement_exposure_utm46.tif"),
        "transport_geojson": Path("data/processed/transport/assam_transport_network.geojson"),
        "spine": [
            (26.58970, 93.40035),
            (26.44000, 93.44000),
            (26.25000, 93.48000),
            (26.05000, 93.46000),
            (25.84573, 93.43781),
        ],
        "building_tiles": [
            (26.45, 93.25, 26.65, 93.60),
            (26.20, 93.25, 26.45, 93.65),
            (25.95, 93.25, 26.20, 93.65),
            (25.75, 93.25, 25.95, 93.65),
        ],
    },
    "gangetic_plains": {
        "zone": 44,
        "epsg": 32644,
        "bbox": (26.55, 81.10, 27.20, 82.25),
        "default_district": "Barabanki-Ayodhya",
        "settlements_out": Path("data/processed/regions/gangetic_plains/gangetic_plains_settlement_boundaries.geojson"),
        "buildings_out": Path("data/processed/regions/gangetic_plains/gangetic_plains_building_footprints.geojson"),
        "building_raster_out": Path("data/processed/regions/gangetic_plains/gangetic_plains_building_density_utm44.tif"),
        "settlement_raster_out": Path("data/processed/regions/gangetic_plains/gangetic_plains_settlement_exposure_utm44.tif"),
        "transport_geojson": Path("data/processed/regions/gangetic_plains/gangetic_plains_transport_network.geojson"),
        "spine": [
            (26.91500, 81.24500),
            (26.86000, 81.50000),
            (26.82000, 81.75000),
            (26.78000, 81.98000),
            (26.76500, 82.12500),
        ],
        "building_tiles": [
            (26.80, 81.15, 27.02, 81.45),
            (26.75, 81.45, 26.98, 81.75),
            (26.70, 81.75, 26.92, 82.00),
            (26.68, 82.00, 26.86, 82.24),
        ],
    },
}

CACHE_DIR = Path("scripts/cache")
CACHE_DIR.mkdir(parents=True, exist_ok=True)


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
    a = cos_lat * (lon - lon0)
    e4 = WGS84_E2 * WGS84_E2
    e6 = e4 * WGS84_E2
    m = WGS84_A * (
        (1.0 - WGS84_E2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0) * lat
        - (3.0 * WGS84_E2 / 8.0 + 3.0 * e4 / 32.0 + 45.0 * e6 / 1024.0) * math.sin(2.0 * lat)
        + (15.0 * e4 / 256.0 + 45.0 * e6 / 1024.0) * math.sin(4.0 * lat)
        - (35.0 * e6 / 3072.0) * math.sin(6.0 * lat)
    )
    x = UTM_K0 * n * (
        a
        + (1.0 - t + c) * (a ** 3) / 6.0
        + (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * (a ** 5) / 120.0
    ) + 500000.0
    y = UTM_K0 * (
        m
        + n * tan_lat * (
            a * a / 2.0
            + (5.0 - t + 9.0 * c + 4.0 * c * c) * (a ** 4) / 24.0
            + (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * (a ** 6) / 720.0
        )
    )
    return x, y


def overpass_query(cache_key: str, query: str, refresh: bool = False):
    cache_path = CACHE_DIR / f"{cache_key}.json"
    if cache_path.exists() and not refresh:
        try:
            with open(cache_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            print(f"  [cache hit] {cache_key}: {len(data.get('elements', []))} elements", flush=True)
            return data.get("elements", [])
        except Exception:
            pass

    payload = urllib.parse.urlencode({"data": query}).encode("utf-8")
    headers = {
        "User-Agent": "MARG-OSM-BuildingFusion/3.0 (Highway Planning Research)",
        "Accept": "application/json",
    }
    last_err = None
    for attempt in range(1):
        for mirror in OVERPASS_MIRRORS:
            try:
                print(f"  [overpass] {cache_key} -> {mirror} (attempt {attempt + 1})...", flush=True)
                req = urllib.request.Request(mirror, data=payload, headers=headers)
                with urllib.request.urlopen(req, timeout=4) as resp:
                    raw = resp.read().decode("utf-8")
                    parsed = json.loads(raw)
                elements = parsed.get("elements", [])
                with open(cache_path, "w", encoding="utf-8") as f:
                    json.dump({"elements": elements}, f)
                print(f"  [ok] {cache_key}: {len(elements)} elements", flush=True)
                time.sleep(0.5)
                return elements
            except Exception as e:
                last_err = e
                print(f"    mirror failed ({mirror}): {e}", flush=True)
                time.sleep(0.5)
    print(f"  [WARN] All Overpass mirrors failed for {cache_key}: {last_err}", flush=True)
    return []


def circle_ring_lonlat(clat: float, clon: float, radius_m: float, n_pts: int = 12):
    dlat = radius_m / 111111.0
    dlon = radius_m / max(10000.0, 111111.0 * math.cos(math.radians(clat)))
    ring = []
    for i in range(n_pts):
        ang = 2.0 * math.pi * i / n_pts
        ring.append([round(clon + dlon * math.cos(ang), 6), round(clat + dlat * math.sin(ang), 6)])
    ring.append(ring[0])
    return ring


def extract_polygon_ring(el: dict, fallback_radius_m: float = 0.0):
    etype = el.get("type")
    if etype == "node":
        lat, lon = el.get("lat"), el.get("lon")
        if lat is None or lon is None or fallback_radius_m <= 0:
            return None
        return circle_ring_lonlat(lat, lon, fallback_radius_m, n_pts=12)

    if etype == "way":
        geom = el.get("geometry")
        if not geom or len(geom) < 3:
            center = el.get("center")
            if center and fallback_radius_m > 0:
                return circle_ring_lonlat(center["lat"], center["lon"], fallback_radius_m, n_pts=12)
            return None
        ring = [[round(pt["lon"], 6), round(pt["lat"], 6)] for pt in geom if "lat" in pt and "lon" in pt]
        if len(ring) < 3:
            return None
        if ring[0] != ring[-1]:
            ring.append(ring[0])
        if len(ring) < 4:
            return None
        return ring

    if etype == "relation":
        members = el.get("members", [])
        outer_segments = []
        for m in members:
            if m.get("type") == "way" and m.get("role", "outer") in ("outer", ""):
                g = m.get("geometry")
                if g and len(g) >= 2:
                    seg = [[round(pt["lon"], 6), round(pt["lat"], 6)] for pt in g if "lat" in pt and "lon" in pt]
                    if len(seg) >= 2:
                        outer_segments.append(seg)
        if not outer_segments:
            bounds = el.get("bounds")
            if bounds:
                clat = 0.5 * (bounds["minlat"] + bounds["maxlat"])
                clon = 0.5 * (bounds["minlon"] + bounds["maxlon"])
                rx = max(25.0, (bounds["maxlon"] - bounds["minlon"]) * 111000.0 * math.cos(math.radians(clat)) * 0.5)
                ry = max(25.0, (bounds["maxlat"] - bounds["minlat"]) * 111000.0 * 0.5)
                if max(rx, ry) <= 8000.0:
                    return [
                        [round(bounds["minlon"], 6), round(bounds["minlat"], 6)],
                        [round(bounds["maxlon"], 6), round(bounds["minlat"], 6)],
                        [round(bounds["maxlon"], 6), round(bounds["maxlat"], 6)],
                        [round(bounds["minlon"], 6), round(bounds["maxlat"], 6)],
                        [round(bounds["minlon"], 6), round(bounds["minlat"], 6)],
                    ]
            return None
        # Stitch longest outer ring
        ring = list(outer_segments[0])
        remaining = outer_segments[1:]
        while remaining:
            end_pt = ring[-1]
            found_idx = None
            rev = False
            for idx, seg in enumerate(remaining):
                if seg[0] == end_pt:
                    found_idx, rev = idx, False
                    break
                if seg[-1] == end_pt:
                    found_idx, rev = idx, True
                    break
            if found_idx is None:
                break
            seg = remaining.pop(found_idx)
            if rev:
                seg = list(reversed(seg))
            ring.extend(seg[1:])
        if len(ring) >= 3:
            if ring[0] != ring[-1]:
                ring.append(ring[0])
            if len(ring) >= 4:
                return ring
    return None


def ring_metrics_utm(ring_lonlat, zone: int):
    pts = [wgs84_to_utm(lat, lon, zone) for lon, lat in ring_lonlat[:-1]]
    if len(pts) < 3:
        return None
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    cx = sum(xs) / len(xs)
    cy = sum(ys) / len(ys)
    # Shoelace area in m^2
    area2 = 0.0
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        area2 += x1 * y2 - x2 * y1
    area_m2 = abs(area2) * 0.5
    if area_m2 < 4.0:
        return None
    clat = sum(lat for _, lat in ring_lonlat[:-1]) / len(pts)
    clon = sum(lon for lon, _ in ring_lonlat[:-1]) / len(pts)
    eq_radius = math.sqrt(area_m2 / math.pi)
    return {
        "area_m2": area_m2,
        "eq_radius_m": eq_radius,
        "centroid_lat": round(clat, 6),
        "centroid_lon": round(clon, 6),
        "utm_pts": pts,
        "min_x": min(xs),
        "max_x": max(xs),
        "min_y": min(ys),
        "max_y": max(ys),
    }


def min_dist_to_spine_km(lat: float, lon: float, spine):
    best = 1e9
    for i in range(len(spine) - 1):
        lat1, lon1 = spine[i]
        lat2, lon2 = spine[i + 1]
        dx = lon2 - lon1
        dy = lat2 - lat1
        len2 = dx * dx + dy * dy
        t = 0.0 if len2 <= 1e-12 else max(0.0, min(1.0, ((lon - lon1) * dx + (lat - lat1) * dy) / len2))
        px = lon1 + t * dx
        py = lat1 + t * dy
        d_km = math.hypot((lat - py) * 111.1, (lon - px) * 111.1 * math.cos(math.radians(lat)))
        if d_km < best:
            best = d_km
    return best


def classify_institutional(tags: dict):
    amenity = tags.get("amenity", "").lower()
    healthcare = tags.get("healthcare", "").lower()
    btype = tags.get("building", "").lower()
    historic = tags.get("historic", "").lower()
    name = tags.get("name", "") or tags.get("name:en", "")

    if amenity in ("hospital", "clinic", "doctors") or healthcare in ("hospital", "clinic", "centre") or btype == "hospital":
        is_major = (amenity == "hospital" or healthcare == "hospital" or btype == "hospital")
        return {
            "category": "HOSPITAL_MEDICAL_CAMPUS",
            "subtype": "HOSPITAL_MEDICAL_CAMPUS" if is_major else "MEDICAL_CLINIC_COMPOUND",
            "building_type": "INSTITUTIONAL",
            "sacred": False,
            "hard_exclusion": True,
            "hospital": True,
            "fallback_r": 95.0 if is_major else 45.0,
            "default_name": name or ("Hospital Campus" if is_major else "Medical Clinic"),
        }
    if amenity == "place_of_worship" or btype in ("temple", "church", "mosque", "cathedral", "shrine") or historic in ("temple", "monument", "memorial", "archaeological_site"):
        return {
            "category": "RELIGIOUS_PRECINCT",
            "subtype": "SACRED_RELIGIOUS_PRECINCT",
            "building_type": "RELIGIOUS",
            "sacred": True,
            "hard_exclusion": True,
            "hospital": False,
            "fallback_r": 45.0,
            "default_name": name or "Religious / Sacred Compound",
        }
    if amenity in ("university", "college", "school") or btype in ("university", "college", "school"):
        is_univ = amenity in ("university", "college") or btype in ("university", "college")
        return {
            "category": "INSTITUTIONAL_CAMPUS",
            "subtype": "UNIVERSITY_COLLEGE_CAMPUS" if is_univ else "SCHOOL_EDUCATIONAL_CAMPUS",
            "building_type": "INSTITUTIONAL",
            "sacred": False,
            "hard_exclusion": True,
            "hospital": False,
            "fallback_r": 110.0 if is_univ else 55.0,
            "default_name": name or ("College / University Campus" if is_univ else "School Campus"),
        }
    return None


def classify_building(tags: dict):
    bval = tags.get("building", "yes").lower()
    amenity = tags.get("amenity", "").lower()
    healthcare = tags.get("healthcare", "").lower()
    shop = tags.get("shop", "")

    if amenity in ("hospital", "clinic", "doctors") or healthcare in ("hospital", "clinic") or bval == "hospital":
        return ("INSTITUTIONAL", True, True, 3, "RCC_INSTITUTIONAL_HOSPITAL")
    if amenity == "place_of_worship" or bval in ("temple", "church", "mosque", "shrine", "cathedral"):
        return ("RELIGIOUS", False, True, 2, "MASONRY_SACRED_STRUCTURE")
    if amenity in ("school", "college", "university", "public_building", "townhall", "police", "fire_station") or bval in ("school", "college", "university", "public", "civic", "government"):
        return ("INSTITUTIONAL", False, True, 2, "RCC_INSTITUTIONAL")
    if bval in ("industrial", "warehouse", "factory", "manufacture") or tags.get("industrial"):
        return ("INDUSTRIAL", False, False, 1, "STEEL_INDUSTRIAL_SHED")
    if bval in ("commercial", "retail", "office", "hotel", "supermarket", "kiosk") or shop:
        return ("COMMERCIAL", False, False, 2, "RCC_COMMERCIAL")
    if bval in ("service", "utility", "water_tower", "transformer_tower", "community_centre"):
        return ("VILLAGE_INFRASTRUCTURE", False, False, 1, "PUCCA_COMMUNITY_UTILITY")
    levels = 1
    try:
        lv = tags.get("building:levels")
        if lv:
            levels = max(1, min(15, int(float(str(lv).split(";")[0]))))
    except Exception:
        levels = 1
    if bval in ("apartments", "dormitory"):
        levels = max(levels, 3)
    return ("RESIDENTIAL", False, False, levels, "RCC_PUCCA" if levels >= 2 else "PUCCA_SEMI_PUCCA")


def save_geotiff_uint8(path: Path, array_uint8: np.ndarray, min_x: float, max_y: float, res: float, epsg_code: int):
    path.parent.mkdir(parents=True, exist_ok=True)
    tiff_info = TiffImagePlugin.ImageFileDirectory_v2()
    tiff_info[33550] = (float(res), float(res), 0.0)
    tiff_info[33922] = (0.0, 0.0, 0.0, float(min_x), float(max_y), 0.0)
    tiff_info[34735] = (1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, int(epsg_code))
    tiff_info[42113] = "255"
    img = Image.fromarray(array_uint8.astype(np.uint8))
    img.save(path, format="TIFF", tiffinfo=tiff_info, compression="tiff_deflate")


def utm_ring_to_px(utm_pts, min_x: float, max_y: float, res: float):
    return [(int(round((x - min_x) / res)), int(round((max_y - y) / res))) for x, y in utm_pts]


def draw_buffered_poly(draw: ImageDraw.ImageDraw, px_pts, val: int, buffer_px: int = 0):
    if len(px_pts) < 3:
        return
    draw.polygon(px_pts, fill=val, outline=val)
    if buffer_px > 0:
        w = max(1, buffer_px * 2)
        closed = px_pts + [px_pts[0]]
        draw.line(closed, fill=val, width=w, joint="curve")
        for px, py in px_pts:
            draw.ellipse((px - buffer_px, py - buffer_px, px + buffer_px, py + buffer_px), fill=val)


def load_existing_sacred_exclusions(path: Path):
    preserved = []
    if not path.exists():
        return preserved
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        for feat in data.get("features", []):
            props = feat.get("properties", {})
            sub = str(props.get("settlementSubtype", "")).upper()
            if props.get("sacredExclusion") or "SACRED" in sub or "HERITAGE" in sub:
                props["sacredExclusion"] = True
                props["hardExclusion"] = True
                props.setdefault("exclusionCategory", "RELIGIOUS_PRECINCT")
                preserved.append(feat)
    except Exception as e:
        print(f"  [warn] could not read existing sacred exclusions from {path}: {e}")
    return preserved


def process_corridor(region_id: str, cfg: dict, refresh: bool = False):
    print(f"\n============================================================", flush=True)
    print(f"FUSING REAL OSM DATA FOR CORRIDOR: {region_id} (UTM {cfg['zone']}N / 30m)", flush=True)
    print(f"============================================================", flush=True)

    min_lat, min_lon, max_lat, max_lon = cfg["bbox"]
    zone = cfg["zone"]
    epsg = cfg["epsg"]
    bbox_str = f"{min_lat},{min_lon},{max_lat},{max_lon}"

    # 1. Preserve curated sacred exclusions (e.g., Ayodhya Mandir precinct)
    preserved_sacred = load_existing_sacred_exclusions(cfg["settlements_out"])
    if preserved_sacred:
        print(f"  Preserved {len(preserved_sacred)} curated sacred exclusion precincts.", flush=True)

    # 2. Query A: Critical Institutional, Medical & Religious Campuses
    q_inst = f"""[out:json][timeout:60];
(
  nwr["amenity"~"^(hospital|clinic|doctors|university|college|school|place_of_worship)$"]({bbox_str});
  nwr["healthcare"~"^(hospital|clinic|centre)$"]({bbox_str});
  nwr["building"~"^(hospital|university|college|school|temple|church|mosque|shrine|cathedral)$"]({bbox_str});
);
out geom;"""
    inst_elements = overpass_query(f"osm_{region_id}_institutional", q_inst, refresh=refresh)

    # 3. Query B: Settlement & Colony Boundaries
    q_stl = f"""[out:json][timeout:60];
(
  wr["landuse"~"^(residential|commercial|industrial|retail|institutional)$"]({bbox_str});
  nwr["place"~"^(city|town|village|suburb|neighbourhood|quarter|hamlet)$"]({bbox_str});
);
out geom;"""
    stl_elements = overpass_query(f"osm_{region_id}_settlements", q_stl, refresh=refresh)

    # 4. Query C: Real OSM building=* footprints across corridor tiles
    raw_buildings_by_id = {}
    for idx, (s, w, n, e) in enumerate(cfg["building_tiles"]):
        q_bld = f"""[out:json][timeout:60];
(
  way["building"]({s},{w},{n},{e});
);
out geom;"""
        b_els = overpass_query(f"osm_{region_id}_buildings_t{idx}", q_bld, refresh=refresh)
        for el in b_els:
            if el.get("type") == "way":
                raw_buildings_by_id[el["id"]] = el

    print(f"  Raw OSM fetched: {len(inst_elements)} institutional/medical/sacred, "
          f"{len(stl_elements)} settlements/landuse, {len(raw_buildings_by_id)} building ways", flush=True)

    settlement_features = list(preserved_sacred)
    building_features = []
    seen_inst_osm = set()
    seen_stl_osm = set()
    seen_bld_osm = set()

    # Keep spatial buckets of settlements to link buildings to containing settlements
    settlement_records_for_raster = []
    building_records_for_raster = []

    # Add preserved sacred exclusions to raster burn list
    for feat in preserved_sacred:
        ring = feat["geometry"]["coordinates"][0]
        m = ring_metrics_utm(ring, zone)
        if m:
            settlement_records_for_raster.append({
                "utm_pts": m["utm_pts"],
                "bld_code": 5,
                "stl_code": 4,
                "buffer_m": INSTITUTIONAL_SAFETY_BUFFER_M,
                "id": feat["properties"]["settlementId"],
                "name": feat["properties"]["name"],
                "cls": feat["properties"]["settlementClassification"],
                "min_x": m["min_x"], "max_x": m["max_x"], "min_y": m["min_y"], "max_y": m["max_y"],
            })

    # --- Process Query A: Critical Institutional, Medical & Religious Campuses ---
    inst_idx = 0
    for el in inst_elements:
        osm_key = f"{el.get('type')}/{el.get('id')}"
        if osm_key in seen_inst_osm:
            continue
        tags = el.get("tags", {})
        info = classify_institutional(tags)
        if not info:
            continue
        ring = extract_polygon_ring(el, fallback_radius_m=info["fallback_r"])
        if not ring:
            continue
        m = ring_metrics_utm(ring, zone)
        if not m:
            continue
        # Skip absurdly large erroneous polygons (> 1500 ha)
        area_ha = m["area_m2"] / 10000.0
        if area_ha > 1500.0:
            continue
        seen_inst_osm.add(osm_key)
        inst_idx += 1
        sid = f"STL-{region_id.upper()}-INST-{inst_idx:04d}"
        name = info["default_name"]
        district = tags.get("addr:district") or tags.get("addr:city") or cfg["default_district"]

        settlement_features.append({
            "type": "Feature",
            "properties": {
                "settlementId": sid,
                "catalogRef": osm_key,
                "osmId": osm_key,
                "name": name,
                "settlementClassification": "URBAN",
                "settlementSubtype": info["subtype"],
                "district": district,
                "polygonAreaHectares": round(area_ha, 4),
                "coreRadiusMeters": round(max(25.0, m["eq_radius_m"]), 2),
                "builtUpDensityBuildingsPerHa": 20.0,
                "builtUpDensityClass": "INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT",
                "mappedBuildingFootprintCount": 1,
                "sacredExclusion": info["sacred"],
                "hardExclusion": True,
                "hospitalOrMedical": info["hospital"],
                "exclusionCategory": info["category"],
                "centroidLat": m["centroid_lat"],
                "centroidLon": m["centroid_lon"],
            },
            "geometry": {
                "type": "Polygon",
                "coordinates": [ring],
            },
        })
        settlement_records_for_raster.append({
            "utm_pts": m["utm_pts"],
            "bld_code": 5,
            "stl_code": 4,
            "buffer_m": INSTITUTIONAL_SAFETY_BUFFER_M,
            "id": sid,
            "name": name,
            "cls": "URBAN",
            "min_x": m["min_x"], "max_x": m["max_x"], "min_y": m["min_y"], "max_y": m["max_y"],
        })

        # Also add as a building footprint if compact (< 25 ha) so RoW impact & demolition checks track it
        if area_ha <= 25.0:
            seen_bld_osm.add(el.get("id"))
            bid = f"BLD-{region_id.upper()}-INST-{inst_idx:04d}"
            building_features.append({
                "type": "Feature",
                "properties": {
                    "buildingId": bid,
                    "osmId": osm_key,
                    "name": name,
                    "buildingType": info["building_type"],
                    "settlementId": sid,
                    "settlementName": name,
                    "settlementClassification": "URBAN",
                    "district": district,
                    "footprintAreaSqMeters": round(m["area_m2"], 2),
                    "floors": 3 if info["hospital"] else 2,
                    "structureMaterial": "RCC_INSTITUTIONAL_HOSPITAL" if info["hospital"] else "RCC_INSTITUTIONAL",
                    "equivalentRadiusMeters": round(max(8.0, m["eq_radius_m"]), 2),
                    "demolitionConflictWeightKmEq": 100.0,
                    "hospitalOrMedical": info["hospital"],
                    "hardExclusion": True,
                    "centroidLat": m["centroid_lat"],
                    "centroidLon": m["centroid_lon"],
                },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [ring],
                },
            })
            building_records_for_raster.append({
                "utm_pts": m["utm_pts"],
                "bld_code": 5,
                "buffer_m": INSTITUTIONAL_SAFETY_BUFFER_M,
            })

    # --- Process Query B: Settlement & Colony Boundaries ---
    # First process real polygon ways/relations (`landuse=*`, polygon `place=*`), then `place=*` nodes
    stl_polys_first = [el for el in stl_elements if el.get("type") in ("way", "relation")]
    stl_nodes = [el for el in stl_elements if el.get("type") == "node"]
    existing_landuse_boxes = []

    stl_idx = 0
    for el in stl_polys_first + stl_nodes:
        osm_key = f"{el.get('type')}/{el.get('id')}"
        if osm_key in seen_stl_osm:
            continue
        tags = el.get("tags", {})
        landuse = tags.get("landuse", "").lower()
        place = tags.get("place", "").lower()
        name = tags.get("name", "") or tags.get("name:en", "")
        district = tags.get("addr:district") or tags.get("addr:city") or cfg["default_district"]

        if el.get("type") == "node":
            # Avoid duplicating a place node that is already inside an OSM landuse polygon
            lat, lon = el.get("lat"), el.get("lon")
            if lat is None or lon is None:
                continue
            ux, uy = wgs84_to_utm(lat, lon, zone)
            inside_existing = False
            for (bminx, bmaxx, bminy, bmaxy) in existing_landuse_boxes:
                if bminx <= ux <= bmaxx and bminy <= uy <= bmaxy:
                    inside_existing = True
                    break
            if inside_existing and place in ("neighbourhood", "hamlet", "village"):
                continue

        fallback_r = {
            "city": 900.0,
            "town": 600.0,
            "suburb": 450.0,
            "quarter": 350.0,
            "neighbourhood": 260.0,
            "village": 240.0,
            "hamlet": 130.0,
        }.get(place, 0.0)

        ring = extract_polygon_ring(el, fallback_radius_m=fallback_r)
        if not ring:
            continue
        m = ring_metrics_utm(ring, zone)
        if not m:
            continue
        area_ha = m["area_m2"] / 10000.0
        # Skip huge regional boundary polygons (> 3500 ha) that aren't colonies
        if area_ha > 3500.0 or area_ha < 0.05:
            continue
        seen_stl_osm.add(osm_key)

        if el.get("type") in ("way", "relation"):
            existing_landuse_boxes.append((m["min_x"], m["max_x"], m["min_y"], m["max_y"]))

        # Determine classification, density class, hard-exclusion, and raster code
        if landuse == "industrial":
            s_cls = "INDUSTRIAL"
            subtype = "OSM_INDUSTRIAL_ESTATE"
            dens_cls = "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP"
            dens_ha = 12.0
            hard_excl = True
            excl_cat = "INDUSTRIAL_COMPLEX"
            bld_code = 4
            stl_code = 4
        elif landuse == "institutional":
            s_cls = "URBAN"
            subtype = "OSM_INSTITUTIONAL_CAMPUS"
            dens_cls = "INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT"
            dens_ha = 18.0
            hard_excl = True
            excl_cat = "INSTITUTIONAL_CAMPUS"
            bld_code = 5
            stl_code = 4
        elif landuse in ("residential", "commercial", "retail") or place in ("city", "town", "suburb", "quarter", "neighbourhood"):
            s_cls = "URBAN"
            if landuse in ("commercial", "retail"):
                subtype = "OSM_URBAN_COMMERCIAL_CORE"
                excl_cat = "URBAN_COMMERCIAL_CORE"
            else:
                subtype = "OSM_RESIDENTIAL_COLONY"
                excl_cat = "DENSE_RESIDENTIAL_COLONY"
            dens_cls = "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP"
            dens_ha = 18.0
            hard_excl = True
            bld_code = 4
            stl_code = 4
        else:
            # Rural village / hamlet
            s_cls = "RURAL"
            subtype = "OSM_RURAL_VILLAGE_HABITATION" if place == "village" else "OSM_RURAL_HAMLET"
            dens_cls = "MEDIUM_DENSITY_VILLAGE_CLUSTER" if place == "village" else "LOW_DENSITY_RURAL_RIBBON"
            dens_ha = 7.5 if place == "village" else 3.5
            hard_excl = False
            excl_cat = "RURAL_HABITATION"
            bld_code = 3 if place == "village" else 2
            stl_code = 3

        stl_idx += 1
        sid = f"STL-{region_id.upper()}-OSM-{stl_idx:04d}"
        if not name:
            name = f"{subtype.replace('OSM_', '').replace('_', ' ').title()} #{stl_idx}"

        settlement_features.append({
            "type": "Feature",
            "properties": {
                "settlementId": sid,
                "catalogRef": osm_key,
                "osmId": osm_key,
                "name": name,
                "settlementClassification": s_cls,
                "settlementSubtype": subtype,
                "district": district,
                "polygonAreaHectares": round(area_ha, 4),
                "coreRadiusMeters": round(max(30.0, m["eq_radius_m"]), 2),
                "builtUpDensityBuildingsPerHa": dens_ha,
                "builtUpDensityClass": dens_cls,
                "mappedBuildingFootprintCount": 0,
                "sacredExclusion": False,
                "hardExclusion": hard_excl,
                "hospitalOrMedical": False,
                "exclusionCategory": excl_cat,
                "centroidLat": m["centroid_lat"],
                "centroidLon": m["centroid_lon"],
            },
            "geometry": {
                "type": "Polygon",
                "coordinates": [ring],
            },
        })
        settlement_records_for_raster.append({
            "utm_pts": m["utm_pts"],
            "bld_code": bld_code,
            "stl_code": stl_code,
            "buffer_m": INSTITUTIONAL_SAFETY_BUFFER_M if bld_code == 5 else 25.0,
            "id": sid,
            "name": name,
            "cls": s_cls,
            "min_x": m["min_x"], "max_x": m["max_x"], "min_y": m["min_y"], "max_y": m["max_y"],
        })

    # --- Process Query C: Real OSM building=* Footprints ---
    candidate_buildings = []
    for wid, el in raw_buildings_by_id.items():
        if wid in seen_bld_osm:
            continue
        ring = extract_polygon_ring(el, fallback_radius_m=0.0)
        if not ring:
            continue
        m = ring_metrics_utm(ring, zone)
        if not m or m["area_m2"] < 12.0 or m["area_m2"] > 200000.0:
            continue
        tags = el.get("tags", {})
        b_type, is_hosp, is_hard, floors, material = classify_building(tags)
        spine_dist_km = min_dist_to_spine_km(m["centroid_lat"], m["centroid_lon"], cfg["spine"])
        priority = (0 if is_hard else 1, spine_dist_km)
        candidate_buildings.append((priority, wid, el, ring, m, b_type, is_hosp, is_hard, floors, material))

    candidate_buildings.sort(key=lambda x: x[0])
    # Always burn ALL fetched buildings into the 30m raster so no area loses density signal,
    # and store the highest-priority corridor buildings in the vector GeoJSON index
    for _, wid, el, ring, m, b_type, is_hosp, is_hard, floors, material in candidate_buildings:
        bld_code = 5 if is_hard else (2 if m["area_m2"] > 400.0 else 1)
        building_records_for_raster.append({
            "utm_pts": m["utm_pts"],
            "bld_code": bld_code,
            "buffer_m": INSTITUTIONAL_SAFETY_BUFFER_M if is_hard else 20.0,
        })

    selected_buildings = candidate_buildings[:MAX_BUILDINGS_PER_CORRIDOR]

    # Build quick grid bucket for settlement lookup so each building links to its settlement
    stl_grid = {}
    for sr in settlement_records_for_raster:
        bx0 = int(sr["min_x"] // 1500.0)
        bx1 = int(sr["max_x"] // 1500.0)
        by0 = int(sr["min_y"] // 1500.0)
        by1 = int(sr["max_y"] // 1500.0)
        for bx in range(bx0, bx1 + 1):
            for by in range(by0, by1 + 1):
                stl_grid.setdefault((bx, by), []).append(sr)

    bld_idx = 0
    for _, wid, el, ring, m, b_type, is_hosp, is_hard, floors, material in selected_buildings:
        tags = el.get("tags", {})
        ux, uy = wgs84_to_utm(m["centroid_lat"], m["centroid_lon"], zone)
        matched_stl = None
        for sr in stl_grid.get((int(ux // 1500.0), int(uy // 1500.0)), []):
            if sr["min_x"] <= ux <= sr["max_x"] and sr["min_y"] <= uy <= sr["max_y"]:
                matched_stl = sr
                break
        sid = matched_stl["id"] if matched_stl else f"STL-{region_id.upper()}-ISOLATED"
        sname = matched_stl["name"] if matched_stl else f"{region_id.replace('_', ' ').title()} Rural Corridor Fringe"
        scls = matched_stl["cls"] if matched_stl else "RURAL"
        name = tags.get("name") or tags.get("name:en") or f"{sname} {b_type.title()} #{bld_idx + 1}"
        district = tags.get("addr:district") or tags.get("addr:city") or cfg["default_district"]
        weight_map = {
            "RESIDENTIAL": 15.0,
            "COMMERCIAL": 25.0,
            "INDUSTRIAL": 35.0,
            "VILLAGE_INFRASTRUCTURE": 20.0,
            "INSTITUTIONAL": 100.0,
            "RELIGIOUS": 100.0,
        }
        bld_idx += 1
        building_features.append({
            "type": "Feature",
            "properties": {
                "buildingId": f"BLD-{region_id.upper()}-OSM-{bld_idx:05d}",
                "osmId": f"way/{wid}",
                "name": name,
                "buildingType": b_type,
                "settlementId": sid,
                "settlementName": sname,
                "settlementClassification": scls,
                "district": district,
                "footprintAreaSqMeters": round(m["area_m2"], 2),
                "floors": floors,
                "structureMaterial": material,
                "equivalentRadiusMeters": round(max(4.0, m["eq_radius_m"]), 2),
                "demolitionConflictWeightKmEq": weight_map.get(b_type, 15.0),
                "hospitalOrMedical": is_hosp,
                "hardExclusion": is_hard,
                "centroidLat": m["centroid_lat"],
                "centroidLon": m["centroid_lon"],
            },
            "geometry": {
                "type": "Polygon",
                "coordinates": [ring],
            },
        })

    # Write GeoJSON catalogs
    cfg["settlements_out"].parent.mkdir(parents=True, exist_ok=True)
    with open(cfg["settlements_out"], "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": settlement_features}, f)
    cfg["buildings_out"].parent.mkdir(parents=True, exist_ok=True)
    with open(cfg["buildings_out"], "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": building_features}, f)

    print(f"  Wrote {len(settlement_features)} settlements/campuses -> {cfg['settlements_out']}", flush=True)
    print(f"  Wrote {len(building_features)} building footprints -> {cfg['buildings_out']}", flush=True)

    # --- Burn 30m UTM GeoTIFF Rasters ---
    corners = [
        wgs84_to_utm(min_lat, min_lon, zone),
        wgs84_to_utm(min_lat, max_lon, zone),
        wgs84_to_utm(max_lat, min_lon, zone),
        wgs84_to_utm(max_lat, max_lon, zone),
    ]
    min_x = math.floor(min(c[0] for c in corners) / RES_METERS) * RES_METERS
    max_x = math.ceil(max(c[0] for c in corners) / RES_METERS) * RES_METERS
    min_y = math.floor(min(c[1] for c in corners) / RES_METERS) * RES_METERS
    max_y = math.ceil(max(c[1] for c in corners) / RES_METERS) * RES_METERS
    width = int(round((max_x - min_x) / RES_METERS))
    height = int(round((max_y - min_y) / RES_METERS))
    print(f"  Burning 30m UTM {zone}N rasters: {width} x {height} px...", flush=True)

    # Paint in ascending priority order (1 -> 2 -> 3 -> 4 -> 5) so higher severity always wins
    bld_img = Image.new("L", (width, height), 0)
    bld_draw = ImageDraw.Draw(bld_img)

    stl_img = Image.new("L", (width, height), 0)
    stl_draw = ImageDraw.Draw(stl_img)

    # Outer & inner settlement exposure buffers first (codes 1 and 2)
    for sr in settlement_records_for_raster:
        px_pts = utm_ring_to_px(sr["utm_pts"], min_x, max_y, RES_METERS)
        draw_buffered_poly(stl_draw, px_pts, 1, buffer_px=int(round(360.0 / RES_METERS)))
    for sr in settlement_records_for_raster:
        px_pts = utm_ring_to_px(sr["utm_pts"], min_x, max_y, RES_METERS)
        draw_buffered_poly(stl_draw, px_pts, 2, buffer_px=int(round(120.0 / RES_METERS)))

    # Rural cores (stl=3) then urban/institutional cores (stl=4)
    for target_stl_code in (3, 4):
        for sr in settlement_records_for_raster:
            if sr["stl_code"] == target_stl_code:
                px_pts = utm_ring_to_px(sr["utm_pts"], min_x, max_y, RES_METERS)
                buf_px = int(round(sr["buffer_m"] / RES_METERS))
                draw_buffered_poly(stl_draw, px_pts, target_stl_code, buffer_px=buf_px)

    # Building density raster: burn codes 1, 2, 3, 4, 5 in strict priority order
    for code in (1, 2, 3, 4, 5):
        for br in building_records_for_raster:
            if br["bld_code"] == code:
                px_pts = utm_ring_to_px(br["utm_pts"], min_x, max_y, RES_METERS)
                buf_px = int(round(br["buffer_m"] / RES_METERS))
                draw_buffered_poly(bld_draw, px_pts, code, buffer_px=buf_px)
        for sr in settlement_records_for_raster:
            if sr["bld_code"] == code:
                px_pts = utm_ring_to_px(sr["utm_pts"], min_x, max_y, RES_METERS)
                buf_px = int(round(sr["buffer_m"] / RES_METERS))
                draw_buffered_poly(bld_draw, px_pts, code, buffer_px=buf_px)

    bld_arr = np.array(bld_img, dtype=np.uint8)
    stl_arr = np.array(stl_img, dtype=np.uint8)
    save_geotiff_uint8(cfg["building_raster_out"], bld_arr, min_x, max_y, RES_METERS, epsg)
    save_geotiff_uint8(cfg["settlement_raster_out"], stl_arr, min_x, max_y, RES_METERS, epsg)

    counts = {int(v): int((bld_arr == v).sum()) for v in range(6)}
    print(f"  Burned 30m Building Density Raster -> {cfg['building_raster_out']} | pixel counts={counts}", flush=True)
    print(f"  Burned 30m Settlement Exposure Raster -> {cfg['settlement_raster_out']}", flush=True)

    # Diagnostic check for western_ghats endpoints
    if region_id == "western_ghats":
        diagnose_western_ghats_endpoints(bld_arr, stl_arr, min_x, max_y, zone, settlement_features, cfg["transport_geojson"])


def diagnose_western_ghats_endpoints(bld_arr, stl_arr, min_x, max_y, zone, settlement_features, transport_path: Path):
    print("\n--- Western Ghats Endpoint Diagnostics (Pune-Satara) ---", flush=True)
    h, w = bld_arr.shape

    def sample(lat, lon):
        x, y = wgs84_to_utm(lat, lon, zone)
        c = int((x - min_x) / RES_METERS)
        r = int((max_y - y) / RES_METERS)
        if 0 <= r < h and 0 <= c < w:
            return int(bld_arr[r, c]), int(stl_arr[r, c])
        return -1, -1

    for label, lat, lon in [
        ("OLD Start (Pune)", 18.36500, 73.85500),
        ("OLD End (Satara)", 17.69500, 74.01500),
    ]:
        bc, sc = sample(lat, lon)
        nearby = []
        for f in settlement_features:
            p = f["properties"]
            d_m = math.hypot((p["centroidLat"] - lat) * 111111.0, (p["centroidLon"] - lon) * 111111.0 * math.cos(math.radians(lat)))
            if d_m <= 1200.0:
                nearby.append((round(d_m), p["name"], p["settlementSubtype"], p.get("hardExclusion")))
        nearby.sort()
        print(f"  {label} ({lat}, {lon}): bld_code={bc}, stl_code={sc} | nearby <=1.2km: {nearby[:6]}", flush=True)

    # Scan NH-48 / road points near Pune south (18.33..18.39) and Satara (17.65..17.73) for clean greenfield tie-ins
    if transport_path.exists():
        with open(transport_path, "r", encoding="utf-8") as f:
            tn = json.load(f)
        road_pts = []
        for feat in tn.get("features", []):
            props = feat.get("properties", {})
            if props.get("category") in ("NATIONAL_HIGHWAY", "STATE_HIGHWAY"):
                geom = feat.get("geometry", {})
                coords = geom.get("coordinates", [])
                if geom.get("type") == "LineString":
                    for lon, lat in coords:
                        road_pts.append((lat, lon, props.get("ref") or props.get("name") or "Highway"))
        for label, lat_lo, lat_hi, target_lat, target_lon in [
            ("Clean Pune-side NH-48 tie-in candidates", 18.32, 18.40, 18.365, 73.855),
            ("Clean Satara-side NH-48 tie-in candidates", 17.65, 17.74, 17.695, 74.015),
        ]:
            cands = []
            for lat, lon, ref in road_pts:
                if lat_lo <= lat <= lat_hi:
                    # Check 300m neighborhood is completely clear (bld_code == 0 and stl_code <= 1)
                    clear = True
                    for dlat in (-0.0025, 0.0, 0.0025):
                        for dlon in (-0.0025, 0.0, 0.0025):
                            bc, sc = sample(lat + dlat, lon + dlon)
                            if bc >= 2 or sc >= 3:
                                clear = False
                                break
                        if not clear:
                            break
                    if clear:
                        d_m = math.hypot((lat - target_lat) * 111111.0, (lon - target_lon) * 111111.0 * math.cos(math.radians(lat)))
                        cands.append((round(d_m), round(lat, 5), round(lon, 5), ref))
            cands.sort()
            print(f"  {label}: {cands[:5]}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--regions", nargs="*", default=["western_ghats", "assam", "gangetic_plains"])
    parser.add_argument("--refresh", action="store_true")
    args = parser.parse_args()

    for rid in args.regions:
        if rid not in CORRIDORS:
            print(f"Unknown region: {rid}", file=sys.stderr)
            sys.exit(1)
        process_corridor(rid, CORRIDORS[rid], refresh=args.refresh)


if __name__ == "__main__":
    main()
