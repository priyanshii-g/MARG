#!/usr/bin/env python3
"""
Process and clean real OSM hydrography for Corridor 3 (Gangetic Plains: Barabanki-Ayodhya).
Generates high-precision, meander-accurate river vector geometries, drainage geometries,
and synchronized GeoTIFF rasters for Layer 6 (Water Bodies) and Layer 12 (Drainage/Canals).
"""
import json
import math
from pathlib import Path
import numpy as np
from PIL import Image, TiffImagePlugin

WGS84_A = 6378137.0
WGS84_E2 = 0.0066943799901413165
UTM_K0 = 0.9996


def wgs84_to_utm(lat_deg, lon_deg, zone=44):
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


def main():
    reg_dir = Path("data/processed/regions/gangetic_plains")
    rivers_path = reg_dir / "gangetic_plains_perennial_rivers.geojson"
    drainage_path = reg_dir / "gangetic_plains_drainage_network.geojson"

    # Read existing GeoJSON
    with open(rivers_path, "r", encoding="utf-8") as f:
        rivers_data = json.load(f)

    with open(drainage_path, "r", encoding="utf-8") as f:
        drainage_data = json.load(f)

    # Clean and name rivers
    processed_rivers = []
    for feat in rivers_data["features"]:
        coords = feat["geometry"]["coordinates"]
        # Simplify with ~20m epsilon
        simplified = rdp(coords, 0.0002)
        if len(simplified) < 2:
            continue
        feat["geometry"]["coordinates"] = [[round(c[0], 5), round(c[1], 5)] for c in simplified]

        p = feat["properties"]
        name = p.get("name", "")
        # Identify specific river bodies by location
        lons = [c[0] for c in simplified]
        lats = [c[1] for c in simplified]
        avg_lon = sum(lons) / len(lons)
        avg_lat = sum(lats) / len(lats)

        # Ramsanehighat Kalyani river
        if abs(avg_lon - 81.55) < 0.12 and abs(avg_lat - 26.80) < 0.10:
            p["name"] = "Kalyani River (Ramsanehighat Meander)"
            p["riverClass"] = "MEDIUM_PERENNIAL_RIVER"
            p["designChannelWidthMeters"] = 65.0
        elif avg_lat < 26.70 and "gomt" in name.lower():
            p["name"] = "Gomti River"
            p["riverClass"] = "MAJOR_PERENNIAL_RIVER"
            p["designChannelWidthMeters"] = 160.0
        elif avg_lat > 26.85 and ("ghaghara" in name.lower() or "saryu" in name.lower() or avg_lon > 82.0):
            p["name"] = "Saryu / Ghaghara River"
            p["riverClass"] = "MAJOR_PERENNIAL_RIVER"
            p["designChannelWidthMeters"] = 380.0
        elif abs(avg_lon - 81.25) < 0.08 and abs(avg_lat - 26.92) < 0.08:
            p["name"] = "Rethi River (East Barabanki)"
            p["riverClass"] = "MEDIUM_PERENNIAL_RIVER"
            p["designChannelWidthMeters"] = 45.0
        elif "Unnamed" in name or not name:
            p["name"] = f"Natural Alluvial Watercourse (Lat {round(avg_lat, 3)}°, Lon {round(avg_lon, 3)}°)"
            p["riverClass"] = "MINOR_PERENNIAL_STREAM"
            p["designChannelWidthMeters"] = 28.0

        processed_rivers.append(feat)

    rivers_data["features"] = processed_rivers
    with open(rivers_path, "w", encoding="utf-8") as f:
        json.dump(rivers_data, f, indent=2)

    # Clean drainage features
    processed_drainage = []
    for feat in drainage_data["features"]:
        coords = feat["geometry"]["coordinates"]
        simplified = rdp(coords, 0.0002)
        if len(simplified) < 2:
            continue
        feat["geometry"]["coordinates"] = [[round(c[0], 5), round(c[1], 5)] for c in simplified]
        p = feat["properties"]
        name = p.get("name", "")
        if "sharda" in name.lower() or "canal" in name.lower() or p.get("sublayerType") == "IRRIGATION_CANAL":
            p["name"] = name if name and "Unnamed" not in name else "Sharda Sahayak Irrigation Canal Network"
            p["sublayerType"] = "IRRIGATION_CANAL"
            p["recommendedStructureType"] = "CANAL_SYPHON_AQUEDUCT_OR_CLEAR_SPAN_BRIDGE"
            p["designWidthMeters"] = 35.0
        else:
            p["sublayerType"] = "SEASONAL_STREAM"
            p["recommendedStructureType"] = "BOX_CULVERT_IRC_SP_13"
            p["designWidthMeters"] = 18.0
        processed_drainage.append(feat)

    drainage_data["features"] = processed_drainage
    with open(drainage_path, "w", encoding="utf-8") as f:
        json.dump(drainage_data, f, indent=2)

    print(f"GeoJSON updated: {len(processed_rivers)} rivers, {len(processed_drainage)} drainage features.")

    # Re-burn GeoTIFF rasters
    width, height = 1341, 882
    min_x = 506880.0
    max_y = 3012570.0
    res = 90.0
    zone = 44
    epsg = 32644

    # 1. Layer 6: Water Bodies Raster
    water_raster = np.zeros((height, width), dtype=np.uint8)
    for feat in processed_rivers:
        coords = feat["geometry"]["coordinates"]
        r_class = feat["properties"].get("riverClass", "MEDIUM_PERENNIAL_RIVER")
        width_m = feat["properties"].get("designChannelWidthMeters", 50.0)

        if r_class == "MAJOR_PERENNIAL_RIVER":
            burn_polyline(water_raster, min_x, max_y, res, coords, zone, width_m / 2.0 + 150.0, 1) # Outer buffer
            burn_polyline(water_raster, min_x, max_y, res, coords, zone, width_m / 2.0 + 60.0, 2)  # Inner buffer
            burn_polyline(water_raster, min_x, max_y, res, coords, zone, max(width_m / 2.0, 60.0), 6) # Channel
        elif r_class == "MEDIUM_PERENNIAL_RIVER":
            burn_polyline(water_raster, min_x, max_y, res, coords, zone, width_m / 2.0 + 80.0, 1)
            burn_polyline(water_raster, min_x, max_y, res, coords, zone, width_m / 2.0 + 35.0, 2)
            burn_polyline(water_raster, min_x, max_y, res, coords, zone, max(width_m / 2.0, 35.0), 4) # Medium river
        else:
            burn_polyline(water_raster, min_x, max_y, res, coords, zone, 45.0, 1)
            burn_polyline(water_raster, min_x, max_y, res, coords, zone, 25.0, 3) # Minor stream

    save_geotiff_uint8(reg_dir / "gangetic_plains_water_bodies_utm44.tif", water_raster, min_x, max_y, res, epsg)
    print("Saved gangetic_plains_water_bodies_utm44.tif")

    # 2. Layer 12: Drainage & Canal Raster
    drainage_raster = np.zeros((height, width), dtype=np.uint8)
    for feat in processed_drainage:
        coords = feat["geometry"]["coordinates"]
        sublayer = feat["properties"].get("sublayerType", "SEASONAL_STREAM")
        if sublayer == "IRRIGATION_CANAL":
            burn_polyline(drainage_raster, min_x, max_y, res, coords, zone, 65.0, 1) # Swale buffer
            burn_polyline(drainage_raster, min_x, max_y, res, coords, zone, 35.0, 4) # Canal RoW
        else:
            burn_polyline(drainage_raster, min_x, max_y, res, coords, zone, 45.0, 1)
            burn_polyline(drainage_raster, min_x, max_y, res, coords, zone, 20.0, 3) # Seasonal stream

    save_geotiff_uint8(reg_dir / "gangetic_plains_drainage_wetland_utm44.tif", drainage_raster, min_x, max_y, res, epsg)
    print("Saved gangetic_plains_drainage_wetland_utm44.tif")


if __name__ == "__main__":
    main()
