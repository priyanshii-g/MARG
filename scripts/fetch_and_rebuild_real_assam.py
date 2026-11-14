#!/usr/bin/env python3
"""
Rebuild Assam (assam) ground-truth hydrology, drainage/wetlands (canonical 30 m grid),
transport network, and regional hubs directly from real OpenStreetMap ways in
scripts/osm_assam_cache.json, aligned cleanly to the canonical 30 m EPSG:32646 grid.
"""
import json
import math
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, TiffImagePlugin
from scipy.ndimage import distance_transform_edt

ROOT = Path(r"D:\CodingProjects\MARG_Backend")

WGS84_A = 6378137.0
WGS84_E2 = 0.0066943799901413165
UTM_K0 = 0.9996
ZONE = 46
EPSG = 32646

# Canonical Assam 30 m grid matching assam_protected_area_mask_utm46.tif
WIDTH = 2176
HEIGHT = 3330
RES = 30.0
MIN_X = 509952.053
MAX_Y = 2947889.616

ORIGIN_LAT, ORIGIN_LON = 26.58970, 93.40035  # Kohora
DEST_LAT, DEST_LON = 25.84573, 93.43781      # Diphu

BBOX_MIN_LAT, BBOX_MAX_LAT = 25.75, 26.65
BBOX_MIN_LON, BBOX_MAX_LON = 93.10, 93.75


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
        + (1.0 - t + c) * (a ** 3) / 6.0
        + (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * (a ** 5) / 120.0
    ) + 500000.0
    y = UTM_K0 * (
        m
        + n * tan_lat * (
            (a * a) / 2.0
            + (5.0 - t + 9.0 * c + 4.0 * c * c) * (a ** 4) / 24.0
            + (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * (a ** 6) / 720.0
        )
    )
    return x, y


def haversine_km(lat1, lon1, lat2, lon2):
    r_earth = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = (
        math.sin(math.radians(lat2 - lat1) / 2.0) ** 2
        + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2.0) ** 2
    )
    return 2.0 * r_earth * math.asin(math.sqrt(a))


def line_offset_km(lat, lon):
    mlat = math.radians((ORIGIN_LAT + DEST_LAT) / 2.0)
    kx = 111.32 * math.cos(mlat)
    ky = 111.0
    x, y = lon * kx, lat * ky
    x1, y1 = ORIGIN_LON * kx, ORIGIN_LAT * ky
    x2, y2 = DEST_LON * kx, DEST_LAT * ky
    dx, dy = x2 - x1, y2 - y1
    t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
    return math.hypot(x - (x1 + t * dx), y - (y1 + t * dy))


def dist_m(lonlat_a, lonlat_b):
    mlat = math.radians((lonlat_a[1] + lonlat_b[1]) / 2.0)
    return math.hypot(
        (lonlat_a[0] - lonlat_b[0]) * 111320.0 * math.cos(mlat),
        (lonlat_a[1] - lonlat_b[1]) * 111000.0,
    )


def polyline_length_km(coords):
    return sum(dist_m(coords[i], coords[i + 1]) for i in range(len(coords) - 1)) / 1000.0


def stitch_directed_ways(way_map, way_ids):
    """Stitch an ordered list of connected OSM ways into one clean LineString [lon, lat]."""
    pts = []
    for idx, wid in enumerate(way_ids):
        seg = [list(p) for p in way_map[wid]["coords"]]
        if not pts:
            pts.extend(seg)
            continue
        d_fwd = dist_m(pts[-1], seg[0])
        d_rev = dist_m(pts[-1], seg[-1])
        gap = min(d_fwd, d_rev)
        if idx == 1:
            d0_best = min(dist_m(pts[0], seg[0]), dist_m(pts[0], seg[-1]))
            if d0_best < gap:
                pts.reverse()
                d_fwd = dist_m(pts[-1], seg[0])
                d_rev = dist_m(pts[-1], seg[-1])
                gap = min(d_fwd, d_rev)
        if d_rev < d_fwd:
            seg.reverse()
        if gap > 200.0:
            print(f"  NOTE: stitch gap={gap:.1f}m before way {wid}")
        pts.extend(seg[1:] if gap < 5.0 else seg)
    # Deduplicate adjacent identical coordinates
    cleaned = []
    for p in pts:
        if not cleaned or abs(p[0] - cleaned[-1][0]) > 1e-8 or abs(p[1] - cleaned[-1][1]) > 1e-8:
            cleaned.append([round(p[0], 7), round(p[1], 7)])
    return cleaned


def save_geotiff_uint8(path, arr):
    img = Image.fromarray(arr.astype(np.uint8), mode="L")
    ifd = TiffImagePlugin.ImageFileDirectory_v2()
    ifd[33550] = (RES, RES, 0.0)
    ifd[33922] = (0.0, 0.0, 0.0, MIN_X, MAX_Y, 0.0)
    ifd[34735] = (
        1, 1, 0, 7,
        1024, 0, 1, 1,
        1025, 0, 1, 1,
        1026, 34737, 22, 0,
        2049, 34737, 7, 22,
        2054, 0, 1, 9102,
        3072, 0, 1, EPSG,
        3076, 0, 1, 9001,
    )
    ifd[34737] = "WGS 84 / UTM zone 46N|WGS 84|"
    ifd[42113] = "255"
    img.save(path, format="TIFF", tiffinfo=ifd, compression="tiff_deflate")


def burn_polyline(raster, coords_lonlat, half_width_m, code, min_existing_ok=255):
    """Burn a [lon, lat] polyline onto uint8 raster using max precedence (only overwriting < min_existing_ok)."""
    if len(coords_lonlat) < 2:
        return
    ux = np.empty(len(coords_lonlat), dtype=np.float64)
    uy = np.empty(len(coords_lonlat), dtype=np.float64)
    for i, (lon, lat) in enumerate(coords_lonlat):
        ux[i], uy[i] = wgs84_to_utm(lat, lon)

    pad_px = int(math.ceil(half_width_m / RES)) + 2
    hw2 = half_width_m * half_width_m

    for i in range(len(coords_lonlat) - 1):
        x1, y1 = ux[i], uy[i]
        x2, y2 = ux[i + 1], uy[i + 1]
        c_min = int(math.floor((min(x1, x2) - MIN_X) / RES)) - pad_px
        c_max = int(math.ceil((max(x1, x2) - MIN_X) / RES)) + pad_px
        r_min = int(math.floor((MAX_Y - max(y1, y2)) / RES)) - pad_px
        r_max = int(math.ceil((MAX_Y - min(y1, y2)) / RES)) + pad_px
        if c_max < 0 or r_max < 0 or c_min >= WIDTH or r_min >= HEIGHT:
            continue
        c0 = max(0, c_min)
        c1 = min(WIDTH - 1, c_max)
        r0 = max(0, r_min)
        r1 = min(HEIGHT - 1, r_max)

        cols = MIN_X + (np.arange(c0, c1 + 1, dtype=np.float64) + 0.5) * RES
        rows = MAX_Y - (np.arange(r0, r1 + 1, dtype=np.float64) + 0.5) * RES
        gx, gy = np.meshgrid(cols, rows)

        vx, vy = x2 - x1, y2 - y1
        seg_len2 = vx * vx + vy * vy
        if seg_len2 < 1e-9:
            d2 = (gx - x1) ** 2 + (gy - y1) ** 2
        else:
            t = np.clip(((gx - x1) * vx + (gy - y1) * vy) / seg_len2, 0.0, 1.0)
            px = x1 + t * vx
            py = y1 + t * vy
            d2 = (gx - px) ** 2 + (gy - py) ** 2

        hit = d2 <= hw2
        sub = raster[r0 : r1 + 1, c0 : c1 + 1]
        mask = hit & (sub < code) & (sub < min_existing_ok)
        sub[mask] = code


def clear_radius_px(raster, lat, lon, radius_px=6):
    x, y = wgs84_to_utm(lat, lon)
    cc = int((x - MIN_X) / RES)
    rr = int((MAX_Y - y) / RES)
    r0 = max(0, rr - radius_px)
    r1 = min(HEIGHT - 1, rr + radius_px)
    c0 = max(0, cc - radius_px)
    c1 = min(WIDTH - 1, cc + radius_px)
    yy, xx = np.ogrid[r0 : r1 + 1, c0 : c1 + 1]
    mask = (yy - rr) ** 2 + (xx - cc) ** 2 <= radius_px * radius_px
    sub = raster[r0 : r1 + 1, c0 : c1 + 1]
    sub[mask] = 0


def point_in_ring(lon, lat, ring):
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i][0], ring[i][1]
        xj, yj = ring[j][0], ring[j][1]
        if ((yi > lat) != (yj > lat)) and (lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-15) + xi):
            inside = not inside
        j = i
    return inside


def sample_code(arr, lat, lon):
    x, y = wgs84_to_utm(lat, lon)
    c = int((x - MIN_X) / RES)
    r = int((MAX_Y - y) / RES)
    if 0 <= r < HEIGHT and 0 <= c < WIDTH:
        return int(arr[r, c]), r, c
    return -1, r, c


def find_first_sample_on_line(arr, coords_lonlat, expected_code, min_dist_from_pts=None):
    min_dist_from_pts = min_dist_from_pts or []
    best = None
    for idx in range(0, len(coords_lonlat)):
        lon, lat = coords_lonlat[idx]
        if not (25.77 <= lat <= 26.63 and 93.12 <= lon <= 93.74):
            continue
        code, r, c = sample_code(arr, lat, lon)
        if code == expected_code:
            ok = all(haversine_km(lat, lon, plat, plon) > 0.35 for plat, plon in min_dist_from_pts)
            if ok:
                best = (round(lat, 5), round(lon, 5))
                if idx >= len(coords_lonlat) // 4:
                    return best
    return best


def main():
    Image.MAX_IMAGE_PIXELS = None
    with open(ROOT / "scripts" / "osm_assam_cache.json", "r", encoding="utf-8") as f:
        cache = json.load(f)

    ww_map = {w["id"]: w for w in cache["waterways"]}
    rw_map = {w["id"]: w for w in cache["railways"]}
    hw_map = {w["id"]: w for w in cache["highways"]}

    pa_arr = np.array(Image.open(ROOT / "data/processed/protected-areas/assam_protected_area_mask_utm46.tif"))
    slope_arr = np.array(Image.open(ROOT / "data/processed/dem/assam_slope_utm46.tif"))
    settle_arr = np.array(Image.open(ROOT / "data/processed/settlements/assam_settlement_exposure_utm46.tif"))

    # =========================================================================
    # 1. HYDROLOGY: 10 real OSM Perennial Rivers + assam_water_bodies_utm46.tif
    # =========================================================================
    river_specs = [
        ("RIV-DHANSIRI", "Dhansiri River", "MAJOR_PERENNIAL_RIVER",
         "Brahmaputra South Bank (Dhansiri Basin)", 240.0, 6, [155250498]),
        ("RIV-KALIANI", "Kaliani River", "MAJOR_PERENNIAL_RIVER",
         "Dhansiri Tributary (Central Karbi Plateau)", 140.0, 6, [771969442, 771969439, 771969440]),
        ("RIV-JAMUNA", "Jamuna River", "MAJOR_PERENNIAL_RIVER",
         "Kopili-Jamuna Basin (Southern Karbi Anglong)", 130.0, 6,
         [224983741, 224983736, 669081639, 329685522, 197997804, 329685524]),
        ("RIV-DIPHLU", "Diphlu River", "MEDIUM_PERENNIAL_RIVER",
         "Kaziranga Foothill Drainage (Brahmaputra Basin)", 75.0, 4,
         [1070970786, 1070949824, 1070942580, 1098381771]),
        ("RIV-KOHORA", "Kohora-Mora Diphlu Stream", "MINOR_PERENNIAL_STREAM",
         "Kaziranga Western Foothill Drainage", 38.0, 3, [1097815678, 1070970783]),
        ("RIV-DEOPANI-EAST", "Deopani River (Golaghat-Karbi Foothills)", "MEDIUM_PERENNIAL_RIVER",
         "Dhansiri-Deopani Eastern Foothill Basin", 70.0, 4, [155250505]),
        ("RIV-BORJAN", "Langhit-Borjan River", "MEDIUM_PERENNIAL_RIVER",
         "Jamuna Tributary (Longnit-Borjan Valley)", 60.0, 4, [329683841]),
        ("RIV-NAMBOR", "Nambor Perennial Stream", "MINOR_PERENNIAL_STREAM",
         "Nambor-Deopani Wildlife Reserve Drainage", 35.0, 3, [1417478829]),
        ("RIV-DIPHU", "Diphu Nadi", "MINOR_PERENNIAL_STREAM",
         "Diphu-Dhansiri Headwater Catchment", 32.0, 3,
         [669204332, 587774256, 587774255, 329685525]),
        ("RIV-BAGORI-WEST", "Bagori-Kaziranga Western Stream", "MINOR_PERENNIAL_STREAM",
         "Brahmaputra South Bank Western Foothills", 35.0, 3, [1189863550, 1071013135]),
    ]

    used_river_way_ids = set()
    river_features = []
    water_raster = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)

    with open(ROOT / "data/processed/hydrology/assam_wetlands_catalog.geojson", "r", encoding="utf-8") as f:
        wet_cat_gj = json.load(f)

    # First burn outer and inner riparian buffers
    stitched_rivers = {}
    for rid, rname, rclass, basin, width_m, code, wids in river_specs:
        used_river_way_ids.update(wids)
        coords = stitch_directed_ways(ww_map, wids)
        stitched_rivers[rid] = (rclass, width_m, code, coords)
        burn_polyline(water_raster, coords, max(240.0, width_m * 1.8), 1)
        burn_polyline(water_raster, coords, max(120.0, width_m * 1.1), 2)
        river_features.append({
            "type": "Feature",
            "properties": {
                "id": rid,
                "name": rname,
                "riverClass": rclass,
                "basin": basin,
                "designChannelWidthMeters": float(width_m),
                "lengthKm": round(polyline_length_km(coords), 2),
            },
            "geometry": {
                "type": "LineString",
                "coordinates": coords,
            },
        })

    # Burn 31 Bhuvan wetlands as code 5
    for feat in wet_cat_gj["features"]:
        g = feat["geometry"]
        if g["type"] == "Point":
            wlon, wlat = g["coordinates"][:2]
            rad = float(feat["properties"].get("radiusMeters", 250.0))
            wx, wy = wgs84_to_utm(wlat, wlon)
            rad = max(60.0, rad)
            px_pad = int(math.ceil(rad / RES)) + 1
            cc = int((wx - MIN_X) / RES)
            rr = int((MAX_Y - wy) / RES)
            r0, r1 = max(0, rr - px_pad), min(HEIGHT - 1, rr + px_pad)
            c0, c1 = max(0, cc - px_pad), min(WIDTH - 1, cc + px_pad)
            if r0 <= r1 and c0 <= c1:
                cols = MIN_X + (np.arange(c0, c1 + 1, dtype=np.float64) + 0.5) * RES
                rows = MAX_Y - (np.arange(r0, r1 + 1, dtype=np.float64) + 0.5) * RES
                gx, gy = np.meshgrid(cols, rows)
                hit = ((gx - wx) ** 2 + (gy - wy) ** 2) <= rad * rad
                sub = water_raster[r0 : r1 + 1, c0 : c1 + 1]
                sub[hit & (sub < 5)] = 5

    # Burn actual river channels cleanly over buffers (3, 4, 6)
    for rid, (rclass, width_m, code, coords) in stitched_rivers.items():
        half_w = max(32.0, width_m * 0.5) if code == 3 else (max(45.0, width_m * 0.5) if code == 4 else max(70.0, width_m * 0.5))
        burn_polyline(water_raster, coords, half_w, code)

    rivers_out = {
        "type": "FeatureCollection",
        "name": "assam_perennial_rivers",
        "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
        "features": river_features,
    }
    with open(ROOT / "data/processed/hydrology/assam_perennial_rivers.geojson", "w", encoding="utf-8") as f:
        json.dump(rivers_out, f, indent=2)
    print(f"Wrote {len(river_features)} real OSM perennial rivers ({len(used_river_way_ids)} OSM way IDs).")

    # =========================================================================
    # 2. DRAINAGE & WETLANDS (30 m canonical grid 2176x3330)
    # =========================================================================
    with open(ROOT / "data/processed/drainage/assam_watershed_basins.geojson", "r", encoding="utf-8") as f:
        ws_gj = json.load(f)
    with open(ROOT / "data/processed/drainage/assam_wetlands_polygons.geojson", "r", encoding="utf-8") as f:
        wet_poly_gj = json.load(f)

    ws_polys = []
    for wf in ws_gj["features"]:
        wid = wf["properties"]["watershedId"]
        geom = wf["geometry"]
        rings = [geom["coordinates"][0]] if geom["type"] == "Polygon" else [p[0] for p in geom["coordinates"]]
        pts = [pt for r in rings for pt in r]
        cx = sum(p[0] for p in pts) / len(pts)
        cy = sum(p[1] for p in pts) / len(pts)
        ws_polys.append((wid, rings, cx, cy))

    def find_watershed(lon, lat):
        for wid, rings, _, _ in ws_polys:
            for ring in rings:
                if point_in_ring(lon, lat, ring):
                    return wid
        return min(ws_polys, key=lambda w: (w[2] - lon) ** 2 + (w[3] - lat) ** 2)[0]

    # Filter 53 OSM linear waterways outside the 10 main rivers
    raw_drain_ways = [
        w for w in cache["waterways"]
        if w.get("tags", {}).get("waterway") in ("river", "stream", "canal", "drain", "ditch")
        and w["id"] not in used_river_way_ids
        and len(w["coords"]) >= 2
        and w["coords"][0] != w["coords"][-1]
    ]

    # Burn 45 wetland polygons onto 30m grid (6 = core interior, 5 = 120 m buffer)
    wet_mask_img = Image.new("L", (WIDTH, HEIGHT), 0)
    draw = ImageDraw.Draw(wet_mask_img)
    for feat in wet_poly_gj["features"]:
        g = feat["geometry"]
        rings = [g["coordinates"][0]] if g["type"] == "Polygon" else [p[0] for p in g["coordinates"]]
        for ring in rings:
            px_ring = []
            for lon, lat in ring:
                ux, uy = wgs84_to_utm(lat, lon)
                px_ring.append(((ux - MIN_X) / RES, (MAX_Y - uy) / RES))
            if len(px_ring) >= 3:
                draw.polygon(px_ring, fill=1)
    wet_core = np.array(wet_mask_img, dtype=bool)
    dist_outside = distance_transform_edt(~wet_core) * RES
    drainage_raster = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)
    drainage_raster[(~wet_core) & (dist_outside <= 120.0)] = 5
    drainage_raster[wet_core] = 6

    drainage_features = []
    sub_counts = {"IRRIGATION_CANAL": 0, "SEASONAL_STREAM": 0, "NATURAL_DRAINAGE_CHANNEL": 0, "LOCAL_WATERCOURSE": 0}
    for idx, w in enumerate(raw_drain_ways, start=1):
        coords = [[round(p[0], 7), round(p[1], 7)] for p in w["coords"]]
        tag_ww = w["tags"].get("waterway", "stream")
        len_km = polyline_length_km(coords)
        mid = coords[len(coords) // 2]
        ws_id = find_watershed(mid[0], mid[1])
        osm_name = w["tags"].get("name", "").strip()

        if tag_ww == "canal":
            sublayer = "IRRIGATION_CANAL"
            code = 4
            width_m = 22.0
            strahler = 3
            q = 42.0
            sens = "HIGH_SENSITIVITY_CLEAR_SPAN_AQUEDUCT"
            struct = "CANAL_AQUEDUCT_IRC_SP_13"
            half_w = 24.0
        elif tag_ww == "river":
            sublayer = "SEASONAL_STREAM"
            code = 3
            width_m = 26.0
            strahler = 3
            q = 48.0
            sens = "MODERATE_CROSS_DRAINAGE"
            struct = "BOX_CULVERT_IRC_SP_13"
            half_w = 24.0
        elif len_km >= 1.4:
            sublayer = "SEASONAL_STREAM"
            code = 3
            width_m = 18.0
            strahler = 2
            q = 32.0
            sens = "MODERATE_CROSS_DRAINAGE"
            struct = "BOX_CULVERT_IRC_SP_13"
            half_w = 20.0
        elif len_km >= 0.45:
            sublayer = "NATURAL_DRAINAGE_CHANNEL"
            code = 1
            width_m = 14.0
            strahler = 2
            q = 18.0
            sens = "LOW_TO_MODERATE_CULVERT"
            struct = "SLAB_OR_SINGLE_BOX_CULVERT"
            half_w = 18.0
        else:
            sublayer = "LOCAL_WATERCOURSE"
            code = 2
            width_m = 10.0
            strahler = 1
            q = 9.0
            sens = "LOW_CROSS_DRAINAGE"
            struct = "PIPE_CULVERT_IRC_SP13"
            half_w = 16.0

        sub_counts[sublayer] += 1
        burn_polyline(drainage_raster, coords, half_w, code, min_existing_ok=5)
        drainage_features.append({
            "type": "Feature",
            "properties": {
                "id": f"OSM-ASSAM-DRN-{idx:03d}",
                "osmWayId": w["id"],
                "name": osm_name if osm_name else f"OSM Assam {tag_ww.title()} #{w['id']}",
                "sublayerType": sublayer,
                "strahlerOrder": strahler,
                "watershedId": ws_id,
                "designWidthMeters": width_m,
                "peakDischargeM3PerSec": q,
                "obstructionSensitivity": sens,
                "recommendedStructureType": struct,
            },
            "geometry": {
                "type": "LineString",
                "coordinates": coords,
            },
        })

    with open(ROOT / "data/processed/drainage/assam_drainage_network.geojson", "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": drainage_features}, f, indent=2)
    print(f"Wrote {len(drainage_features)} real OSM linear drainage channels: {sub_counts}")

    # =========================================================================
    # 3. TRANSPORT NETWORK (12 real OSM corridors: 2 rail + 10 road)
    # =========================================================================
    nfr_main_coords = stitch_directed_ways(
        rw_map,
        [228411374, 669204323, 669204322, 669204303, 669204302, 669204314, 669204313, 617233526, 617233534, 1128377982, 1128377983],
    )
    nfr_ind_coords = stitch_directed_ways(rw_map, [1058938668])  # Real Dhansiri/Doldoli siding (no fake rail at 25.946)
    nh715_coords = stitch_directed_ways(
        hw_map,
        [
            44721584, 1071013136, 1071013137, 975594823, 1071123127, 1071123128,
            1147211986, 44721457, 1097809754, 1097809753, 1098070541, 1098070540,
            1098384147, 1099800216, 1099800215, 1098384148, 44721365, 1305429632,
            1305429631, 1305471268, 1305471267, 1305376891,
        ],
    )
    nh129_coords = stitch_directed_ways(hw_map, [44769780, 1305429629])
    nh29_main_coords = stitch_directed_ways(
        hw_map,
        [
            1354907372, 1354907370, 228977888, 197997712, 1354907373, 239177367,
            1536260669, 1537955834, 1537955835, 1537955836, 1537955837, 1537955838,
            1537955839, 1537955840, 1537955831, 1537955832, 1537955829, 1537955828,
            1537955827, 1537955826, 1537955825, 1537955824, 1537955823, 1537955822,
            1537955821, 1537955820, 1537955819, 1537955818, 1537955817, 1537955816,
            1537955830, 1354580400, 1354580399, 1090653949,
        ],
    )
    nh29_west_coords = stitch_directed_ways(hw_map, [1354869553])
    nh329_coords = stitch_directed_ways(
        hw_map,
        [197997734, 289248396, 669184493, 669184492, 22826880, 638591393, 638591394, 289248395, 1553205891],
    )
    sh35_coords = stitch_directed_ways(
        hw_map,
        [423879971, 423879968, 1314227327, 1314227328, 1314227329, 423879966, 423879962, 423879960, 423879959],
    )
    sh36_coords = stitch_directed_ways(hw_map, [1313338040])
    sh19_coords = stitch_directed_ways(hw_map, [1314699384])  # SH-018 primary
    mdr101_coords = stitch_directed_ways(hw_map, [423881823, 1314214557, 1314214534])
    mdr102_coords = stitch_directed_ways(hw_map, [1316220615, 1316220613])

    corridor_specs = [
        ("NFR-MAIN-01", "NFR-BG-MAIN", "Northeast Frontier Railway (Lumding-Diphu-Dimapur-Bokajan OSM)", "RAILWAY", 1, nfr_main_coords),
        ("NFR-IND-02", "NFR-DHANSIRI-SIDING", "NFR Diphu-Dhansiri Freight Yard Siding (OSM)", "RAILWAY", 1, nfr_ind_coords),
        ("NH-715", "NH-715", "National Highway 715 (Kaliabor-Bagori-Kohora-Bokakhat-Numaligarh OSM)", "NATIONAL_HIGHWAY", 1, nh715_coords),
        ("NH-129", "NH-129", "National Highway 129 (Numaligarh Southern Approach OSM)", "NATIONAL_HIGHWAY", 1, nh129_coords),
        ("NH-29-MAIN", "NH-29", "National Highway 29 (Samelangso-Longnit-Manja-Dillai-Bokajan OSM)", "NATIONAL_HIGHWAY", 1, nh29_main_coords),
        ("NH-29-WEST-SPUR", "NH-29W", "National Highway 29 Western Connector (Howraghat-Bakalia-Phuloni OSM)", "NATIONAL_HIGHWAY", 1, nh29_west_coords),
        ("NH-329", "NH-329", "National Highway 329 (Lumding-Diphu-Manja OSM)", "NATIONAL_HIGHWAY", 1, nh329_coords),
        ("SH-35", "SH-35", "State Highway 35 / SH-1017 (Kohora-Rangagora Foothill Road OSM)", "STATE_HIGHWAY", 2, sh35_coords),
        ("SH-36", "SH-36", "State Highway SH-017 (Central Karbi Plateau Collector OSM)", "STATE_HIGHWAY", 2, sh36_coords),
        ("SH-19", "SH-19", "State Highway SH-018 (Diphu Western Arterial OSM)", "STATE_HIGHWAY", 2, sh19_coords),
        ("MDR-101", "MDR-101", "Major District Road 101 (Dolamara-Kohora Northern Collector OSM)", "MAJOR_DISTRICT_ROAD", 3, mdr101_coords),
        ("MDR-102", "MDR-102", "Major District Road 102 (Longnit-Chokihola Southern Collector OSM)", "MAJOR_DISTRICT_ROAD", 3, mdr102_coords),
    ]

    transport_features = []
    transport_raster = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)

    # First burn road parallel buffers (code 1)
    for cid, cref, cname, ccat, ctier, coords in corridor_specs:
        if ccat != "RAILWAY":
            burn_polyline(transport_raster, coords, 90.0, 1)
    # Burn road centerlines (2=MDR, 3=SH, 4=NH) + opposite carriageways for NH29
    cat_code = {"MAJOR_DISTRICT_ROAD": (2, 30.0), "STATE_HIGHWAY": (3, 35.0), "NATIONAL_HIGHWAY": (4, 45.0)}
    for cid, cref, cname, ccat, ctier, coords in corridor_specs:
        if ccat in cat_code:
            code, hw = cat_code[ccat]
            burn_polyline(transport_raster, coords, hw, code)
        transport_features.append({
            "type": "Feature",
            "properties": {
                "id": cid,
                "ref": cref,
                "name": cname,
                "category": ccat,
                "hierarchyTier": ctier,
                "lengthKm": round(polyline_length_km(coords), 2),
            },
            "geometry": {"type": "LineString", "coordinates": coords},
        })

    # Burn opposite carriageway of divided NH29W on raster so both lanes are covered
    burn_polyline(transport_raster, [list(p) for p in hw_map[1354869549]["coords"]], 45.0, 4)

    # Burn railway buffer (5) and railway corridor (6)
    for cid, cref, cname, ccat, ctier, coords in corridor_specs:
        if ccat == "RAILWAY":
            burn_polyline(transport_raster, coords, 120.0, 5)
            burn_polyline(transport_raster, coords, 45.0, 6)

    with open(ROOT / "data/processed/transport/assam_transport_network.geojson", "w", encoding="utf-8") as f:
        json.dump(
            {
                "type": "FeatureCollection",
                "name": "assam_transport_network",
                "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
                "features": transport_features,
            },
            f,
            indent=2,
        )
    print(f"Wrote {len(transport_features)} real OSM transport corridors.")

    # =========================================================================
    # 4. REGIONAL HUBS: Snap all 14 tie-ins onto SAFE vertices of the 10 highways
    # =========================================================================
    with open(ROOT / "data/processed/transport/assam_regional_hubs.geojson", "r", encoding="utf-8") as f:
        hubs_gj = json.load(f)

    # Map each highway ref to its LineString coordinates
    corridor_by_ref = {cref: coords for _, cref, _, ccat, _, coords in corridor_specs if ccat != "RAILWAY"}

    def is_safe_tiein(lat, lon, hub_id):
        if not (25.755 <= lat <= 26.645 and 93.105 <= lon <= 93.745):
            return False
        if line_offset_km(lat, lon) > 21.95:
            return False
        if hub_id not in ("HUB-DIPHU", "HUB-KOHORA"):
            if haversine_km(lat, lon, ORIGIN_LAT, ORIGIN_LON) < 4.05:
                return False
            if haversine_km(lat, lon, DEST_LAT, DEST_LON) < 4.05:
                return False
        pa, r, c = sample_code(pa_arr, lat, lon)
        if pa != 0:
            return False
        sl = float(slope_arr[r, c])
        if sl < 0.0 or sl > 18.0 or math.isnan(sl):
            return False
        st = int(settle_arr[r, c])
        if st == 4:
            return False
        # Do not place interchange directly inside a designated wetland poly
        # Check a small neighborhood for wetland core (6) on drainage_raster
        if drainage_raster[r, c] in (5, 6):
            return False
        return True

    def pick_best_safe_vertex(ref, target_lat, target_lon, hub_id, used_pts, min_sep_km=0.8):
        pts = corridor_by_ref[ref]
        cands = []
        for lon, lat in pts:
            if not is_safe_tiein(lat, lon, hub_id):
                continue
            if any(haversine_km(lat, lon, ulat, ulon) < min_sep_km for ulat, ulon in used_pts):
                continue
            d = haversine_km(lat, lon, target_lat, target_lon)
            cands.append((d, lat, lon))
        if not cands:
            raise RuntimeError(f"No safe vertex found on {ref} for {hub_id}!")
        cands.sort()
        return cands[0][1], cands[0][2]

    # Target road ref + anchor target for each of the 14 hubs (all snapped to safe vertices!)
    hub_plan = {
        "HUB-DIPHU": ("NH-329", 25.8495, 93.4400, "Diphu Northern Highway Tie-In (NH-329 OSM)"),
        "HUB-KOHORA": ("NH-715", 26.5895, 93.4120, "Kohora Eastern Highway Tie-In (NH-715 OSM)"),
        "HUB-BOKAKHAT": ("NH-715", 26.6380, 93.5980, "Bokakhat Bypass Junction (NH-715 OSM)"),
        "HUB-HOWRAGHAT": ("NH-29W", 26.0630, 93.2060, "Howraghat Western Bypass Junction (NH-29W OSM)"),
        "HUB-DOKMOKA": ("SH-36", 26.3500, 93.3460, "Dokmoka Central Collector Tie-In (SH-36 / SH-017 OSM)"),
        "HUB-DILLAI": ("NH-29", 25.9550, 93.5150, "Dillai Industrial Bypass Junction (NH-29 OSM)"),
        "HUB-MANJA": ("NH-329", 25.9700, 93.4365, "Manja Tri-Highway Junction (NH-329 / NH-29 OSM)"),
        "HUB-SILONIJAN": ("SH-36", 26.3570, 93.3810, "Silonijan Collector Tie-In (SH-36 / SH-017 OSM)"),
        "HUB-LANGHIN": ("NH-29", 25.9920, 93.2900, "Langhin Arterial Tie-In (NH-29 OSM)"),
        "HUB-LONGNIT": ("NH-29", 26.0120, 93.3680, "Longnit Arterial Bypass Junction (NH-29 OSM)"),
        "HUB-PHULONI": ("NH-29W", 26.0200, 93.2440, "Phuloni Eastern Connector (NH-29W OSM)"),
        "HUB-SANTIPUR": ("NH-29", 25.9610, 93.5840, "Santipur-Bokajan Eastern Link (NH-29 OSM)"),
        "HUB-CHOKIHOLA": ("MDR-102", 26.0660, 93.4180, "Chokihola Southern Collector Tie-In (MDR-102 OSM)"),
        "HUB-DOLAMARA": ("MDR-101", 26.5490, 93.4740, "Dolamara Northern Collector Tie-In (MDR-101 OSM)"),
    }

    used_tieins = []
    hub_tiein_coords = [(ORIGIN_LAT, ORIGIN_LON), (DEST_LAT, DEST_LON)]
    print("\n=== SNAPPED 14 ASSAM HUB TIE-INS ===")
    for feat in hubs_gj["features"]:
        p = feat["properties"]
        hid = p["hubId"]
        ref, tlat, tlon, tname = hub_plan[hid]
        slat, slon = pick_best_safe_vertex(ref, tlat, tlon, hid, used_tieins, min_sep_km=0.5)
        used_tieins.append((slat, slon))
        hub_tiein_coords.append((slat, slon))
        ux, uy = wgs84_to_utm(slat, slon)
        p["tieInLat"] = round(slat, 6)
        p["tieInLon"] = round(slon, 6)
        p["tieInUtmX"] = round(ux, 1)
        p["tieInUtmY"] = round(uy, 1)
        p["tieInRoadRef"] = ref
        p["tieInName"] = tname
        feat["geometry"]["coordinates"] = [round(slon, 6), round(slat, 6)]
        _, r, c = sample_code(pa_arr, slat, slon)
        print(
            f"  {hid:16s} ({p['name']:10s}) -> ({slat:.6f},{slon:.6f}) ref={ref:7s} "
            f"dLine={line_offset_km(slat,slon):5.2f}km dO={haversine_km(slat,slon,ORIGIN_LAT,ORIGIN_LON):5.2f}km "
            f"dD={haversine_km(slat,slon,DEST_LAT,DEST_LON):5.2f}km pa={pa_arr[r,c]} sl={slope_arr[r,c]:.1f} st={settle_arr[r,c]}"
        )

    with open(ROOT / "data/processed/transport/assam_regional_hubs.geojson", "w", encoding="utf-8") as f:
        json.dump(hubs_gj, f, indent=2)

    # Clear 6 px radius around origin, destination, and all 14 hub tie-ins in hydrology & drainage rasters
    for lat, lon in hub_tiein_coords:
        clear_radius_px(water_raster, lat, lon, radius_px=6)
        clear_radius_px(drainage_raster, lat, lon, radius_px=6)

    # Save all 3 re-burned GeoTIFF rasters on canonical 30 m grid
    save_geotiff_uint8(ROOT / "data/processed/hydrology/assam_water_bodies_utm46.tif", water_raster)
    save_geotiff_uint8(ROOT / "data/processed/drainage/assam_drainage_wetland_utm46.tif", drainage_raster)
    save_geotiff_uint8(ROOT / "data/processed/transport/assam_transport_network_utm46.tif", transport_raster)
    print("\nSaved all 3 canonical 30m (2176x3330) Assam GeoTIFF rasters!")

    # =========================================================================
    # 5. VERIFY SAMPLE PIXELS FOR TEST ASSERTIONS
    # =========================================================================
    print("\n=== TEST VERIFICATION SAMPLES ===")
    print("Kohora origin water:", sample_code(water_raster, ORIGIN_LAT, ORIGIN_LON)[0],
          "drainage:", sample_code(drainage_raster, ORIGIN_LAT, ORIGIN_LON)[0])
    print("Diphu dest    water:", sample_code(water_raster, DEST_LAT, DEST_LON)[0],
          "drainage:", sample_code(drainage_raster, DEST_LAT, DEST_LON)[0])
    print("Wetland Beel (25.7750, 93.2750) water:", sample_code(water_raster, 25.7750, 93.2750)[0], "(expect 5)")
    print("Drainage Wetland (26.5480, 93.4007) drainage:", sample_code(drainage_raster, 26.5480, 93.4007)[0], "(expect 6)")
    print("Dry Hill (26.3000, 93.3400) water:", sample_code(water_raster, 26.3000, 93.3400)[0],
          "transport:", sample_code(transport_raster, 26.3000, 93.3400)[0])

    # Exact river samples inside raster
    kal_pt = find_first_sample_on_line(water_raster, stitched_rivers["RIV-KALIANI"][3], 6, hub_tiein_coords)
    dha_pt = find_first_sample_on_line(water_raster, stitched_rivers["RIV-DHANSIRI"][3], 6, hub_tiein_coords)
    deo_pt = find_first_sample_on_line(water_raster, stitched_rivers["RIV-DEOPANI-EAST"][3], 4, hub_tiein_coords)
    bor_pt = find_first_sample_on_line(water_raster, stitched_rivers["RIV-BORJAN"][3], 4, hub_tiein_coords)
    koh_pt = find_first_sample_on_line(water_raster, stitched_rivers["RIV-KOHORA"][3], 3, hub_tiein_coords)
    print("Sample Kaliani (expect 6):", kal_pt)
    print("Sample Dhansiri (expect 6):", dha_pt)
    print("Sample Deopani (expect 4):", deo_pt, "Borjan:", bor_pt)
    print("Sample Kohora stream (expect 3):", koh_pt)

    # Kaliani E-W segment check for perp/acute test (find point on Kaliani with local E-W tangent < 10 deg)
    kal_pts = stitched_rivers["RIV-KALIANI"][3]
    for j in range(10, len(kal_pts) - 10):
        p1, p2 = kal_pts[j - 5], kal_pts[j + 5]
        dlon = (p2[0] - p1[0]) * math.cos(math.radians(p1[1]))
        dlat = p2[1] - p1[1]
        ang = math.degrees(math.atan2(abs(dlat), abs(dlon)))
        lat, lon = kal_pts[j][1], kal_pts[j][0]
        if ang < 8.0 and 93.38 <= lon <= 93.55 and sample_code(water_raster, lat, lon)[0] == 6:
            print(f"Kaliani E-W crossing center (angle={ang:.1f} deg): ({lat:.5f}, {lon:.5f})")
            break

    # Exact transport samples inside raster
    nh_pt = find_first_sample_on_line(transport_raster, nh715_coords, 4)
    rw_pt = find_first_sample_on_line(transport_raster, nfr_main_coords, 6)
    sh_pt = find_first_sample_on_line(transport_raster, sh36_coords, 3)
    mdr_pt = find_first_sample_on_line(transport_raster, mdr101_coords, 2)
    print("Sample NH_CORRIDOR (expect 4):", nh_pt)
    print("Sample RAILWAY_CORRIDOR (expect 6):", rw_pt)
    print("Sample SH_CORRIDOR (expect 3):", sh_pt)
    print("Sample MDR_CORRIDOR (expect 2):", mdr_pt)

    # NH-715 E-W segment for perp/acute test
    for j in range(10, len(nh715_coords) - 10):
        p1, p2 = nh715_coords[j - 5], nh715_coords[j + 5]
        dlon = (p2[0] - p1[0]) * math.cos(math.radians(p1[1]))
        dlat = p2[1] - p1[1]
        ang = math.degrees(math.atan2(abs(dlat), abs(dlon)))
        lat, lon = nh715_coords[j][1], nh715_coords[j][0]
        if ang < 5.0 and 93.35 <= lon <= 93.45 and sample_code(transport_raster, lat, lon)[0] == 4:
            print(f"NH-715 E-W crossing center (angle={ang:.1f} deg): ({lat:.5f}, {lon:.5f})")
            break


if __name__ == "__main__":
    main()
