#!/usr/bin/env python3
"""
Fetch and build real OSM hydrography for Corridor 2 (Western Ghats: Pune-Bhor-Satara).
BBox: 17.60, 73.65 to 18.50, 74.30
"""
import urllib.request
import urllib.parse
import json
import math
from pathlib import Path
import numpy as np
from PIL import Image, TiffImagePlugin

WGS84_A = 6378137.0
WGS84_E2 = 0.0066943799901413165
UTM_K0 = 0.9996


def wgs84_to_utm(lat_deg, lon_deg, zone=43):
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
        + (1.0 - t + c) * (a**3) / 6.0
        + (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * (a**5) / 120.0
    ) + 500000.0
    y = UTM_K0 * (
        m
        + n * tan_lat * (
            a * a / 2.0
            + (5.0 - t + 9.0 * c + 4.0 * c * c) * (a**4) / 24.0
            + (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * (a**6) / 720.0
        )
    )
    return x, y


def p_dist(p, a, b):
    px, py = p
    ax, ay = a
    bx, by = b
    dx = bx - ax
    dy = by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    proj_x = ax + t * dx
    proj_y = ay + t * dy
    return math.hypot(px - proj_x, py - proj_y)


def rdp(pts, epsilon=0.0002):
    if len(pts) < 3:
        return pts
    dmax = 0.0
    idx = 0
    for i in range(1, len(pts) - 1):
        d = p_dist(pts[i], pts[0], pts[-1])
        if d > dmax:
            idx = i
            dmax = d
    if dmax > epsilon:
        rec1 = rdp(pts[:idx + 1], epsilon)
        rec2 = rdp(pts[idx:], epsilon)
        return rec1[:-1] + rec2
    else:
        return [pts[0], pts[-1]]


def save_geotiff_uint8(path, array_uint8, min_x, max_y, res, epsg_code):
    path.parent.mkdir(parents=True, exist_ok=True)
    tiff_info = TiffImagePlugin.ImageFileDirectory_v2()
    tiff_info[33550] = (float(res), float(res), 0.0)
    tiff_info[33922] = (0.0, 0.0, 0.0, float(min_x), float(max_y), 0.0)
    tiff_info[34735] = (1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, int(epsg_code))
    tiff_info[42113] = "255"
    img = Image.fromarray(array_uint8.astype(np.uint8))
    img.save(path, format="TIFF", tiffinfo=tiff_info, compression="tiff_deflate")


def burn_circle(raster, min_x, max_y, res, cx, cy, radius_m, val, overwrite_higher=True):
    h, w = raster.shape
    c0 = max(0, int((cx - radius_m - min_x) / res))
    c1 = min(w - 1, int((cx + radius_m - min_x) / res))
    r0 = max(0, int((max_y - (cy + radius_m)) / res))
    r1 = min(h - 1, int((max_y - (cy - radius_m)) / res))
    r2 = radius_m * radius_m
    for rr in range(r0, r1 + 1):
        py = max_y - (rr + 0.5) * res
        dy2 = (py - cy) ** 2
        for cc in range(c0, c1 + 1):
            px = min_x + (cc + 0.5) * res
            if (px - cx) ** 2 + dy2 <= r2:
                if overwrite_higher or raster[rr, cc] < val:
                    raster[rr, cc] = val


def burn_polyline(raster, min_x, max_y, res, coords_lonlat, zone, half_width_m, val):
    pts = [wgs84_to_utm(lat, lon, zone) for lon, lat in coords_lonlat]
    for i in range(1, len(pts)):
        x0, y0 = pts[i - 1]
        x1, y1 = pts[i]
        seg_len = math.hypot(x1 - x0, y1 - y0)
        steps = max(1, int(seg_len / (res * 0.6)))
        for s in range(steps + 1):
            t = s / steps
            cx = x0 + t * (x1 - x0)
            cy = y0 + t * (y1 - y0)
            burn_circle(raster, min_x, max_y, res, cx, cy, half_width_m, val, overwrite_higher=False)


def fetch_osm():
    bbox = "17.60,73.65,18.50,74.30"
    query = f"""[out:json][timeout:35];
(
  way["waterway"~"river|canal"]({bbox});
);
out geom;"""
    urls = [
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass-api.de/api/interpreter"
    ]
    data = urllib.parse.urlencode({"data": query}).encode("utf-8")
    for u in urls:
        print(f"Trying mirror {u}...")
        try:
            req = urllib.request.Request(u, data=data, headers={"User-Agent": "MARG-WG-Ingestion/1.0", "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                elements = json.loads(resp.read().decode("utf-8")).get("elements", [])
                if elements:
                    print(f"SUCCESS from {u}! Elements: {len(elements)}")
                    return elements
        except Exception as e:
            print(f"Mirror {u} failed: {e}")
    return []


def main():
    print("Fetching Western Ghats watercourses from OSM...")
    elements = fetch_osm()
    print(f"Retrieved {len(elements)} elements from OSM.")

    rivers = []
    drainage = []

    for el in elements:
        geom = el.get("geometry", [])
        if len(geom) < 2:
            continue
        tags = el.get("tags", {})
        waterway = tags.get("waterway", "")
        name = tags.get("name") or tags.get("name:en") or ""
        coords = [[round(pt["lon"], 5), round(pt["lat"], 5)] for pt in geom]
        simplified = rdp(coords, 0.0002)
        if len(simplified) < 2:
            continue

        name_lower = name.lower()
        is_river = waterway == "river" or any(rv in name_lower for rv in ["river", "nira", "krishna", "venna", "koyna", "shivganga", "kanandi"])

        if is_river:
            if "krishna" in name_lower:
                r_class = "MAJOR_PERENNIAL_RIVER"
                w_m = 180.0
                r_name = "Krishna River (Upper Basin)"
            elif "nira" in name_lower:
                r_class = "MAJOR_PERENNIAL_RIVER"
                w_m = 150.0
                r_name = "Nira River (Veer Basin)"
            elif any(m in name_lower for m in ["venna", "koyna", "shivganga"]):
                r_class = "MEDIUM_PERENNIAL_RIVER"
                w_m = 65.0
                r_name = name if name else "Western Ghats Perennial River"
            else:
                r_class = "MINOR_PERENNIAL_STREAM"
                w_m = 32.0
                lons = [c[0] for c in simplified]
                lats = [c[1] for c in simplified]
                avg_lon = sum(lons) / len(lons)
                avg_lat = sum(lats) / len(lats)
                r_name = name if name else f"Sahyadri Escarpment Watercourse (Lat {round(avg_lat, 3)}°, Lon {round(avg_lon, 3)}°)"

            rivers.append({
                "type": "Feature",
                "properties": {
                    "id": f"OSM-WG-RIV-{el['id']}",
                    "name": r_name,
                    "riverClass": r_class,
                    "basin": "Upper Krishna & Bhima Basin",
                    "designChannelWidthMeters": w_m,
                    "osmWaterway": waterway
                },
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[round(c[0], 5), round(c[1], 5)] for c in simplified]
                }
            })
        else:
            is_canal = waterway == "canal" or "canal" in name_lower or "nira" in name_lower
            drainage.append({
                "type": "Feature",
                "properties": {
                    "id": f"OSM-WG-DRN-{el['id']}",
                    "name": name if name else ("Nira Irrigation Canal Command" if is_canal else "Sahyadri Seasonal Torrent"),
                    "sublayerType": "IRRIGATION_CANAL" if is_canal else "SEASONAL_STREAM",
                    "strahlerOrder": 3 if is_canal else 2,
                    "watershedId": "WS-WESTERN_GHATS-01",
                    "designWidthMeters": 30.0 if is_canal else 16.0,
                    "peakDischargeM3PerSec": 60.0 if is_canal else 25.0,
                    "obstructionSensitivity": "MODERATE_CROSS_DRAINAGE",
                    "recommendedStructureType": "CANAL_SYPHON_AQUEDUCT_OR_CLEAR_SPAN_BRIDGE" if is_canal else "BOX_CULVERT_IRC_SP_13",
                    "osmWaterway": waterway
                },
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[round(c[0], 5), round(c[1], 5)] for c in simplified]
                }
            })

    out_dir = Path("data/processed/regions/western_ghats")
    if rivers:
        print(f"Saving {len(rivers)} rivers and {len(drainage)} drainage features for Western Ghats...")
        with open(out_dir / "western_ghats_perennial_rivers.geojson", "w", encoding="utf-8") as f:
            json.dump({"type": "FeatureCollection", "name": "Western Ghats Real Rivers", "features": rivers}, f, indent=2)
        with open(out_dir / "western_ghats_drainage_network.geojson", "w", encoding="utf-8") as f:
            json.dump({"type": "FeatureCollection", "name": "Western Ghats Real Drainage", "features": drainage}, f, indent=2)

        # Inspect existing GeoTIFF header for western_ghats_water_bodies_utm43.tif
        im = Image.open(out_dir / "western_ghats_water_bodies_utm43.tif")
        tags = dict(im.tag_v2)
        w, h = im.size
        min_x = tags[33922][3]
        max_y = tags[33922][4]
        res = tags[33550][0]
        zone = 43
        epsg = 32643

        water_raster = np.zeros((h, w), dtype=np.uint8)
        for rf in rivers:
            coords = rf["geometry"]["coordinates"]
            r_cls = rf["properties"].get("riverClass", "MEDIUM_PERENNIAL_RIVER")
            w_m = rf["properties"].get("designChannelWidthMeters", 50.0)
            if r_cls == "MAJOR_PERENNIAL_RIVER":
                burn_polyline(water_raster, min_x, max_y, res, coords, zone, w_m / 2.0 + 120.0, 1)
                burn_polyline(water_raster, min_x, max_y, res, coords, zone, w_m / 2.0 + 50.0, 2)
                burn_polyline(water_raster, min_x, max_y, res, coords, zone, max(w_m / 2.0, 50.0), 6)
            elif r_cls == "MEDIUM_PERENNIAL_RIVER":
                burn_polyline(water_raster, min_x, max_y, res, coords, zone, w_m / 2.0 + 60.0, 1)
                burn_polyline(water_raster, min_x, max_y, res, coords, zone, w_m / 2.0 + 25.0, 2)
                burn_polyline(water_raster, min_x, max_y, res, coords, zone, max(w_m / 2.0, 25.0), 4)
            else:
                burn_polyline(water_raster, min_x, max_y, res, coords, zone, 45.0, 1)
                burn_polyline(water_raster, min_x, max_y, res, coords, zone, 25.0, 3)

        save_geotiff_uint8(out_dir / "western_ghats_water_bodies_utm43.tif", water_raster, min_x, max_y, res, epsg)
        print("Saved western_ghats_water_bodies_utm43.tif")

        drainage_raster = np.zeros((h, w), dtype=np.uint8)
        for df in drainage:
            coords = df["geometry"]["coordinates"]
            sublayer = df["properties"].get("sublayerType", "SEASONAL_STREAM")
            if sublayer == "IRRIGATION_CANAL":
                burn_polyline(drainage_raster, min_x, max_y, res, coords, zone, 60.0, 1)
                burn_polyline(drainage_raster, min_x, max_y, res, coords, zone, 30.0, 4)
            else:
                burn_polyline(drainage_raster, min_x, max_y, res, coords, zone, 40.0, 1)
                burn_polyline(drainage_raster, min_x, max_y, res, coords, zone, 18.0, 3)

        save_geotiff_uint8(out_dir / "western_ghats_drainage_wetland_utm43.tif", drainage_raster, min_x, max_y, res, epsg)
        print("Saved western_ghats_drainage_wetland_utm43.tif")


if __name__ == "__main__":
    main()
