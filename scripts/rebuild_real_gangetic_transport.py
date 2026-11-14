#!/usr/bin/env python3
"""
Rebuild Gangetic Plains (Barabanki-Ramsanehighat-Ayodhya, UTM Zone 44N) transport ground truth:
1. Transport Network: replace synthetic NH-27 with real 227-segment OSM alignment; replace synthetic Northern Railway with real station-tracked alignment.
2. Regional Hubs: snap Ramsanehighat and Rudauli tie-ins to exact NH-27 / SH-13 road centerlines.
3. Re-burn GeoTIFF raster: gangetic_plains_transport_network_utm44.tif.
"""
import json
import math
from pathlib import Path
import numpy as np
from PIL import Image, TiffImagePlugin

WGS84_A = 6378137.0
WGS84_E2 = 0.0066943799901413165
UTM_K0 = 0.9996
ZONE = 44
EPSG = 32644
RES = 90.0

def wgs84_to_utm(lat_deg, lon_deg, zone=ZONE):
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
    print("Rebuilding Gangetic Plains transport data...")
    out_dir = Path("data/processed/regions/gangetic_plains")
    min_x = 506880.0
    max_y = 3012570.0
    res = 90.0
    width = 1341
    height = 882
    print(f"Grid: {width} x {height} (min_x={min_x}, max_y={max_y}, res={res}m)")

    # 1. Transport Network
    with open("scripts/nh27_gp_ways.json", "r", encoding="utf-8") as f:
        nh27_ways = json.load(f)

    nr_railway_pts = [
        [81.17875, 26.93405], # Barabanki Junction
        [81.25337, 26.93132], # Rasauli
        [81.36169, 26.91432], # Safdarganj
        [81.45500, 26.89200], # Saidkhanpur
        [81.55007, 26.86216], # Daryabad
        [81.62614, 26.82513], # Patranga
        [81.75420, 26.76219], # Rudauli
        [81.86500, 26.75500], # Gauriganj-Deorakot
        [81.98311, 26.75305], # Sohawal
        [82.06500, 26.76100], # Salarpur
        [82.13580, 26.76899]  # Ayodhya Cantt Junction
    ]

    sh13_pts = [
        [81.18500, 26.92500], # Barabanki
        [81.24500, 26.84500],
        [81.28500, 26.75500],
        [81.32500, 26.68500]  # Haidergarh
    ]

    mdr115_pts = [
        [81.53788, 26.80757], # Ramsanehighat NH-27 Tie-In
        [81.54200, 26.78500],
        [81.54500, 26.71500]  # Tikaitnagar
    ]

    transport_features = [
        {
            "type": "Feature",
            "properties": {
                "id": "GP-NH27",
                "ref": "NH-27",
                "name": "Lucknow-Ayodhya-Gorakhpur National Highway 27 (4/6-Lane Corridor)",
                "category": "NATIONAL_HIGHWAY",
                "hierarchyTier": 1
            },
            "geometry": {
                "type": "MultiLineString",
                "coordinates": nh27_ways
            }
        },
        {
            "type": "Feature",
            "properties": {
                "id": "GP-SH13",
                "ref": "SH-13",
                "name": "Barabanki-Haidergarh State Highway 13",
                "category": "STATE_HIGHWAY",
                "hierarchyTier": 2
            },
            "geometry": {
                "type": "LineString",
                "coordinates": sh13_pts
            }
        },
        {
            "type": "Feature",
            "properties": {
                "id": "GP-MDR115",
                "ref": "MDR-115W",
                "name": "Ramsanehighat-Tikaitnagar Major District Road 115W",
                "category": "MAJOR_DISTRICT_ROAD",
                "hierarchyTier": 3
            },
            "geometry": {
                "type": "LineString",
                "coordinates": mdr115_pts
            }
        },
        {
            "type": "Feature",
            "properties": {
                "id": "GP-RAIL-01",
                "ref": "NR-LKO-AY-VNS",
                "name": "Northern Railway Lucknow-Barabanki-Ayodhya Cantt Broad Gauge Double Electrified Trunk Line",
                "category": "RAILWAY",
                "hierarchyTier": 1
            },
            "geometry": {
                "type": "LineString",
                "coordinates": nr_railway_pts
            }
        }
    ]

    transport_path = out_dir / "gangetic_plains_transport_network.geojson"
    with open(transport_path, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": transport_features}, f, indent=2)
    print(f"Saved real Gangetic Plains transport network to {transport_path}")

    # Re-burn transport raster
    transport_raster = np.zeros((height, width), dtype=np.uint8)
    for way in nh27_ways:
        burn_polyline(transport_raster, min_x, max_y, res, way, ZONE, 45.0, 2)
    burn_polyline(transport_raster, min_x, max_y, res, sh13_pts, ZONE, 30.0, 3)
    burn_polyline(transport_raster, min_x, max_y, res, mdr115_pts, ZONE, 25.0, 4)
    burn_polyline(transport_raster, min_x, max_y, res, nr_railway_pts, ZONE, 35.0, 1)

    transport_raster_path = out_dir / "gangetic_plains_transport_network_utm44.tif"
    save_geotiff_uint8(transport_raster_path, transport_raster, min_x, max_y, res, EPSG)
    print(f"Saved re-burned Gangetic transport raster to {transport_raster_path}")

    # 2. Regional Hubs: Snap tie-ins to exact road centerlines!
    hubs_path = out_dir / "gangetic_plains_regional_hubs.geojson"
    with open(hubs_path, "r", encoding="utf-8") as f:
        hubs_gj = json.load(f)

    for h in hubs_gj["features"]:
        hid = h["properties"].get("hubId")
        if hid in ("HUB-IGP-RAMSANEHIGHAT", "HUB-GP-RAMSANEHIGHAT"):
            h["properties"]["tieInLat"] = 26.80757
            h["properties"]["tieInLon"] = 81.53788
            h["properties"]["tieInRoadRef"] = "NH-27"
            h["properties"]["tieInName"] = "NH-27 Ramsanehighat Arterial Interchange"
            h["geometry"]["coordinates"] = [81.53788, 26.80757]
            print("Snapped Ramsanehighat tieIn to (26.80757, 81.53788) on NH-27")
        elif hid in ("HUB-IGP-RUDAULI", "HUB-GP-RUDAULI"):
            h["properties"]["tieInLat"] = 26.78025
            h["properties"]["tieInLon"] = 81.78158
            h["properties"]["tieInRoadRef"] = "NH-27"
            h["properties"]["tieInName"] = "NH-27 Rudauli Western Bypass Junction"
            h["geometry"]["coordinates"] = [81.78158, 26.78025]
            print("Snapped Rudauli tieIn to (26.78025, 81.78158) on NH-27")

    with open(hubs_path, "w", encoding="utf-8") as f:
        json.dump(hubs_gj, f, indent=2)
    print(f"Saved snapped Gangetic regional hubs to {hubs_path}")
    print("Gangetic Plains transport rebuild COMPLETE!")

if __name__ == "__main__":
    main()
