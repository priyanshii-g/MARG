#!/usr/bin/env python3
"""
Rebuild Gangetic Plains (Barabanki-Ramsanehighat-Ayodhya, UTM Zone 44N)
settlement, building footprint, demographic, and sacred/heritage exclusion layers:

1. Sacred & Heritage Zero-Tolerance Exclusion Zones (mask=2 in Protected Area raster + sacredExclusion=true):
   - Shri Ram Janmabhoomi Mandir Complex & Ramkot Sacred Security Precinct (26.786..26.806 N, 82.182..82.208 E)
   - Guptar Ghat Heritage Precinct (26.792..26.802 N, 82.128..82.142 E)
   - Surya Kund Heritage Precinct (26.752..26.760 N, 82.202..82.212 E)
2. Real Municipal Towns & Rural Habitations across Barabanki - Ramsanehighat - Rudauli - Sohawal - Faizabad/Ayodhya:
   - Keeps southern/eastern NH-27 Ayodhya Bypass & default origin/destination tie-ins navigable.
3. Re-burns GeoTIFF rasters (90 m, EPSG:32644, 1341 x 882):
   - gangetic_plains_protected_area_mask_utm44.tif
   - gangetic_plains_settlement_exposure_utm44.tif
   - gangetic_plains_building_density_utm44.tif
   - gangetic_plains_population_density_utm44.tif
   And corresponding GeoJSON vector files:
   - gangetic_plains_settlement_boundaries.geojson
   - gangetic_plains_building_footprints.geojson
   - gangetic_plains_population_demographics.geojson
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
MIN_X = 506880.0
MAX_Y = 3012570.0
WIDTH = 1341
HEIGHT = 882


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
            a * a / 2.0
            + (5.0 - t + 9.0 * c + 4.0 * c * c) * (a ** 4) / 24.0
            + (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * (a ** 6) / 720.0
        )
    )
    return x, y


def save_geotiff_uint8(path, array_uint8, min_x=MIN_X, max_y=MAX_Y, res=RES, epsg_code=EPSG):
    path.parent.mkdir(parents=True, exist_ok=True)
    tiff_info = TiffImagePlugin.ImageFileDirectory_v2()
    tiff_info[33550] = (float(res), float(res), 0.0)
    tiff_info[33922] = (0.0, 0.0, 0.0, float(min_x), float(max_y), 0.0)
    tiff_info[34735] = (1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, int(epsg_code))
    tiff_info[42113] = "255"
    img = Image.fromarray(array_uint8.astype(np.uint8))
    img.save(path, format="TIFF", tiffinfo=tiff_info, compression="tiff_deflate")


def ellipse_ring_lonlat(clat, clon, rx_deg, ry_deg, n_pts=24):
    pts = []
    for i in range(n_pts):
        ang = 2.0 * math.pi * i / n_pts
        pts.append([round(clon + rx_deg * math.cos(ang), 6), round(clat + ry_deg * math.sin(ang), 6)])
    pts.append(pts[0])
    return pts


def box_ring_lonlat(min_lat, min_lon, max_lat, max_lon):
    return [
        [round(min_lon, 6), round(min_lat, 6)],
        [round(max_lon, 6), round(min_lat, 6)],
        [round(max_lon, 6), round(max_lat, 6)],
        [round(min_lon, 6), round(max_lat, 6)],
        [round(min_lon, 6), round(min_lat, 6)],
    ]


def burn_ring_bbox_max(raster, ring_lonlat, val, buffer_m=0.0):
    xs, ys = zip(*(wgs84_to_utm(lat, lon) for lon, lat in ring_lonlat[:-1]))
    cx = 0.5 * (min(xs) + max(xs))
    cy = 0.5 * (min(ys) + max(ys))
    rx = 0.5 * (max(xs) - min(xs)) + buffer_m
    ry = 0.5 * (max(ys) - min(ys)) + buffer_m
    c0 = max(0, int((cx - rx - MIN_X) / RES))
    c1 = min(WIDTH - 1, int((cx + rx - MIN_X) / RES))
    r0 = max(0, int((MAX_Y - (cy + ry)) / RES))
    r1 = min(HEIGHT - 1, int((MAX_Y - (cy - ry)) / RES))
    if len(ring_lonlat) == 5:
        # Axis-aligned rectangular precinct
        for rr in range(r0, r1 + 1):
            py = MAX_Y - (rr + 0.5) * RES
            if cy - ry <= py <= cy + ry:
                for cc in range(c0, c1 + 1):
                    px = MIN_X + (cc + 0.5) * RES
                    if cx - rx <= px <= cx + rx and raster[rr, cc] < val:
                        raster[rr, cc] = val
    else:
        for rr in range(r0, r1 + 1):
            py = MAX_Y - (rr + 0.5) * RES
            ny = (py - cy) / max(1.0, ry)
            for cc in range(c0, c1 + 1):
                px = MIN_X + (cc + 0.5) * RES
                nx = (px - cx) / max(1.0, rx)
                if nx * nx + ny * ny <= 1.0 and raster[rr, cc] < val:
                    raster[rr, cc] = val


def make_building_box(clat, clon, half_m=8.0):
    dlat = half_m / 111000.0
    dlon = half_m / (111000.0 * math.cos(math.radians(clat)))
    return box_ring_lonlat(clat - dlat, clon - dlon, clat + dlat, clon + dlon)


def main():
    out_dir = Path("data/processed/regions/gangetic_plains")

    # Preserve existing mask==1 ecological reserve pixels
    pa_path = out_dir / "gangetic_plains_protected_area_mask_utm44.tif"
    if pa_path.exists():
        pa_raster = np.array(Image.open(pa_path), dtype=np.uint8)
        pa_raster[pa_raster >= 2] = 0  # rebuild mask==2 sacred/heritage cleanly
    else:
        pa_raster = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)

    stl_raster = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)
    bld_raster = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)
    demo_raster = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)

    settlement_specs = [
        # Sacred & Heritage Zero-Tolerance Exclusions (stay strictly north of NH-27 Ayodhya Bypass)
        {
            "id": "STL-GP-SAC-001",
            "name": "Shri Ram Janmabhoomi Mandir Complex & Ramkot Sacred Precinct",
            "class": "URBAN",
            "subtype": "SACRED_HERITAGE_TEMPLE_COMPLEX",
            "district": "Ayodhya",
            "sacred": True,
            "ring": box_ring_lonlat(26.7860, 82.1820, 26.8065, 82.2080),
            "clat": 26.7956,
            "clon": 82.1944,
            "area_ha": 588.0,
            "core_r": 1200.0,
            "dens_ha": 24.0,
            "dens_class": "INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT",
            "bld_code": 5,
            "stl_code": 4,
            "demo_code": 5,
            "pop": 68000,
            "urban": True,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.62,
        },
        {
            "id": "STL-GP-SAC-002",
            "name": "Guptar Ghat Saryu Heritage Precinct",
            "class": "URBAN",
            "subtype": "SACRED_HERITAGE_GHAT_PRECINCT",
            "district": "Ayodhya",
            "sacred": True,
            "ring": box_ring_lonlat(26.7920, 82.1280, 26.8030, 82.1430),
            "clat": 26.7975,
            "clon": 82.1355,
            "area_ha": 182.0,
            "core_r": 650.0,
            "dens_ha": 16.0,
            "dens_class": "INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT",
            "bld_code": 5,
            "stl_code": 4,
            "demo_code": 4,
            "pop": 18500,
            "urban": True,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.55,
        },
        {
            "id": "STL-GP-SAC-003",
            "name": "Surya Kund Darshan Nagar Heritage Precinct",
            "class": "URBAN",
            "subtype": "SACRED_HERITAGE_PRECINCT",
            "district": "Ayodhya",
            "sacred": True,
            "ring": box_ring_lonlat(26.7520, 82.2020, 26.7610, 82.2120),
            "clat": 26.7565,
            "clon": 82.2070,
            "area_ha": 98.0,
            "core_r": 450.0,
            "dens_ha": 14.0,
            "dens_class": "INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT",
            "bld_code": 5,
            "stl_code": 3,
            "demo_code": 4,
            "pop": 11200,
            "urban": False,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.52,
        },
        # Major Urban Cores & Municipal Towns (leaving NH-27 Bypass outside URBAN_CORE)
        {
            "id": "STL-GP-URB-001",
            "name": "Ayodhya Dham Municipal Urban Core",
            "class": "URBAN",
            "subtype": "URBAN_MUNICIPAL_CORE",
            "district": "Ayodhya",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.7880, 82.1920, 0.019, 0.013),
            "clat": 26.7880,
            "clon": 82.1920,
            "area_ha": 540.0,
            "core_r": 1400.0,
            "dens_ha": 22.0,
            "dens_class": "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP",
            "bld_code": 4,
            "stl_code": 4,
            "demo_code": 5,
            "pop": 115000,
            "urban": True,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.58,
        },
        {
            "id": "STL-GP-URB-002",
            "name": "Faizabad Twin-City Urban Core",
            "class": "URBAN",
            "subtype": "URBAN_MUNICIPAL_CORPORATION",
            "district": "Ayodhya",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.7790, 82.1480, 0.022, 0.011),
            "clat": 26.7790,
            "clon": 82.1480,
            "area_ha": 620.0,
            "core_r": 1500.0,
            "dens_ha": 25.0,
            "dens_class": "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP",
            "bld_code": 4,
            "stl_code": 4,
            "demo_code": 5,
            "pop": 165000,
            "urban": True,
            "vuln_tier": "LOW_VULNERABILITY",
            "svi": 0.44,
        },
        {
            "id": "STL-GP-URB-003",
            "name": "Barabanki Nawabganj Urban Core",
            "class": "URBAN",
            "subtype": "URBAN_DISTRICT_HEADQUARTERS",
            "district": "Barabanki",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.9310, 81.1930, 0.018, 0.012),
            "clat": 26.9310,
            "clon": 81.1930,
            "area_ha": 460.0,
            "core_r": 1300.0,
            "dens_ha": 21.0,
            "dens_class": "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP",
            "bld_code": 4,
            "stl_code": 4,
            "demo_code": 5,
            "pop": 146000,
            "urban": True,
            "vuln_tier": "LOW_VULNERABILITY",
            "svi": 0.45,
        },
        {
            "id": "STL-GP-URB-004",
            "name": "Rudauli Municipal Town Core",
            "class": "URBAN",
            "subtype": "URBAN_MUNICIPAL_TOWN",
            "district": "Ayodhya",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.7530, 81.7500, 0.013, 0.010),
            "clat": 26.7530,
            "clon": 81.7500,
            "area_ha": 240.0,
            "core_r": 950.0,
            "dens_ha": 17.5,
            "dens_class": "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP",
            "bld_code": 4,
            "stl_code": 4,
            "demo_code": 5,
            "pop": 58000,
            "urban": True,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.54,
        },
        {
            "id": "STL-GP-URB-005",
            "name": "Ramsanehighat Tehsil Kasba Cluster",
            "class": "RURAL",
            "subtype": "SEMI_URBAN_TEHSIL_CENTER",
            "district": "Barabanki",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.7940, 81.5370, 0.010, 0.007),
            "clat": 26.7940,
            "clon": 81.5370,
            "area_ha": 140.0,
            "core_r": 700.0,
            "dens_ha": 13.5,
            "dens_class": "MEDIUM_DENSITY_VILLAGE_CLUSTER",
            "bld_code": 3,
            "stl_code": 3,
            "demo_code": 4,
            "pop": 44000,
            "urban": False,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.56,
        },
        {
            "id": "STL-GANGETIC_PLAINS-001",
            "name": "Zaidpur Kasba Habitation",
            "class": "URBAN",
            "subtype": "URBAN_MUNICIPAL_TOWN",
            "district": "Barabanki",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.8620, 81.4600, 0.0063, 0.0052),
            "clat": 26.8620,
            "clon": 81.4600,
            "area_ha": 105.52,
            "core_r": 640.0,
            "dens_ha": 14.5,
            "dens_class": "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP",
            "bld_code": 4,
            "stl_code": 4,
            "demo_code": 5,
            "pop": 34500,
            "urban": True,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.56,
        },
        {
            "id": "STL-GANGETIC_PLAINS-002",
            "name": "Dariyabad Rural Cluster",
            "class": "RURAL",
            "subtype": "RURAL_ABADI_CLUSTER",
            "district": "Barabanki",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.8850, 81.5550, 0.0055, 0.0045),
            "clat": 26.8850,
            "clon": 81.5550,
            "area_ha": 82.0,
            "core_r": 510.0,
            "dens_ha": 10.5,
            "dens_class": "MEDIUM_DENSITY_VILLAGE_CLUSTER",
            "bld_code": 3,
            "stl_code": 3,
            "demo_code": 4,
            "pop": 14200,
            "urban": False,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.58,
        },
        {
            "id": "STL-GANGETIC_PLAINS-003",
            "name": "Sohawal Dense Abadi Belt",
            "class": "RURAL",
            "subtype": "RURAL_ABADI_CLUSTER",
            "district": "Ayodhya",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.7690, 81.9820, 0.0060, 0.0045),
            "clat": 26.7690,
            "clon": 81.9820,
            "area_ha": 88.0,
            "core_r": 540.0,
            "dens_ha": 11.2,
            "dens_class": "MEDIUM_DENSITY_VILLAGE_CLUSTER",
            "bld_code": 3,
            "stl_code": 3,
            "demo_code": 4,
            "pop": 16400,
            "urban": False,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.57,
        },
        {
            "id": "STL-GP-RUR-004",
            "name": "Safdarganj Highway Settlement",
            "class": "RURAL",
            "subtype": "RURAL_ABADI_CLUSTER",
            "district": "Barabanki",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.9060, 81.3650, 0.0050, 0.0040),
            "clat": 26.9060,
            "clon": 81.3650,
            "area_ha": 64.0,
            "core_r": 460.0,
            "dens_ha": 9.8,
            "dens_class": "MEDIUM_DENSITY_VILLAGE_CLUSTER",
            "bld_code": 3,
            "stl_code": 3,
            "demo_code": 4,
            "pop": 9800,
            "urban": False,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.55,
        },
        {
            "id": "STL-GP-RUR-005",
            "name": "Bikapur Block Town",
            "class": "RURAL",
            "subtype": "RURAL_MARKET_SETTLEMENT",
            "district": "Ayodhya",
            "sacred": False,
            "ring": ellipse_ring_lonlat(26.6050, 82.1380, 0.0070, 0.0055),
            "clat": 26.6050,
            "clon": 82.1380,
            "area_ha": 118.0,
            "core_r": 620.0,
            "dens_ha": 12.0,
            "dens_class": "MEDIUM_DENSITY_VILLAGE_CLUSTER",
            "bld_code": 3,
            "stl_code": 3,
            "demo_code": 4,
            "pop": 21500,
            "urban": False,
            "vuln_tier": "MODERATE_VULNERABILITY",
            "svi": 0.59,
        },
    ]

    settlement_features = []
    building_features = []
    demographic_features = []
    bld_counter = 1

    for idx, spec in enumerate(settlement_specs, start=1):
        ring = spec["ring"]

        # 1. Burn Protected Area sacred mask=2 if sacredExclusion
        if spec["sacred"]:
            burn_ring_bbox_max(pa_raster, ring, 2, buffer_m=0.0)

        # 2. Burn settlement exposure buffers (OUTER=1 @ +500m, INNER=2 @ +220m, core)
        burn_ring_bbox_max(stl_raster, ring, 1, buffer_m=500.0)
        burn_ring_bbox_max(stl_raster, ring, 2, buffer_m=220.0)
        burn_ring_bbox_max(stl_raster, ring, spec["stl_code"], buffer_m=0.0)

        # 3. Burn building density & demographic rasters
        burn_ring_bbox_max(bld_raster, ring, 1, buffer_m=300.0)
        burn_ring_bbox_max(bld_raster, ring, spec["bld_code"], buffer_m=0.0)

        burn_ring_bbox_max(demo_raster, ring, max(1, spec["demo_code"] - 2), buffer_m=450.0)
        burn_ring_bbox_max(demo_raster, ring, max(2, spec["demo_code"] - 1), buffer_m=180.0)
        burn_ring_bbox_max(demo_raster, ring, spec["demo_code"], buffer_m=0.0)

        # Generate representative building footprints inside settlement
        b_types = (
            ["RELIGIOUS", "INSTITUTIONAL", "RELIGIOUS", "RESIDENTIAL", "COMMERCIAL"]
            if spec["sacred"]
            else ["COMMERCIAL", "RESIDENTIAL", "INSTITUTIONAL", "RESIDENTIAL", "RELIGIOUS"]
        )
        offsets = [
            (0.0, 0.0),
            (0.0018, 0.0018),
            (-0.0018, 0.0018),
            (0.0018, -0.0018),
            (-0.0018, -0.0018),
            (0.0032, 0.0),
            (-0.0032, 0.0),
            (0.0, 0.0032),
            (0.0, -0.0032),
            (0.0010, -0.0028),
        ]
        for b_i, (dlat, dlon) in enumerate(offsets):
            bt = b_types[b_i % len(b_types)]
            blat = round(spec["clat"] + dlat, 6)
            blon = round(spec["clon"] + dlon, 6)
            building_features.append({
                "type": "Feature",
                "properties": {
                    "buildingId": f"BLD-GANGETIC_PLAINS-{bld_counter:04d}",
                    "name": f"{spec['name']} {bt.title()} #{b_i + 1}",
                    "buildingType": bt,
                    "settlementId": spec["id"],
                    "settlementName": spec["name"],
                    "settlementClassification": spec["class"],
                    "district": spec["district"],
                    "footprintAreaSqMeters": 140.0 if bt in ("RELIGIOUS", "INSTITUTIONAL") else 110.0,
                    "floors": 2 if spec["urban"] else 1,
                    "structureMaterial": "RCC_PUCCA",
                    "equivalentRadiusMeters": 8.0,
                    "demolitionConflictWeightKmEq": 0.95 if bt == "RELIGIOUS" else (0.85 if bt == "INSTITUTIONAL" else 0.42),
                    "centroidLat": blat,
                    "centroidLon": blon,
                },
                "geometry": {"type": "Polygon", "coordinates": [make_building_box(blat, blon, half_m=8.0)]},
            })
            bld_counter += 1

        settlement_features.append({
            "type": "Feature",
            "properties": {
                "settlementId": spec["id"],
                "catalogRef": spec["id"],
                "name": spec["name"],
                "settlementClassification": spec["class"],
                "settlementSubtype": spec["subtype"],
                "district": spec["district"],
                "polygonAreaHectares": spec["area_ha"],
                "coreRadiusMeters": spec["core_r"],
                "builtUpDensityBuildingsPerHa": spec["dens_ha"],
                "builtUpDensityClass": spec["dens_class"],
                "mappedBuildingFootprintCount": len(offsets),
                "sacredExclusion": bool(spec["sacred"]),
                "centroidLat": spec["clat"],
                "centroidLon": spec["clon"],
            },
            "geometry": {"type": "Polygon", "coordinates": [ring]},
        })

        pop = spec["pop"]
        area_sqkm = max(0.2, spec["area_ha"] / 100.0)
        pop_density_sqkm = round(pop / area_sqkm, 1)
        demo_class_name = (
            "VERY_HIGH_URBAN_DENSITY_CORE" if spec["demo_code"] == 5 else "HIGH_DENSITY_POPULATION_CLUSTER"
        )
        demographic_features.append({
            "type": "Feature",
            "properties": {
                "tractId": f"TRACT-GP-{idx:03d}",
                "settlementId": spec["id"],
                "censusCode": f"CEN-44-{idx:04d}",
                "name": spec["name"],
                "settlementClassification": spec["class"],
                "district": spec["district"],
                "totalPopulation": pop,
                "ruralPopulation": 0 if spec["urban"] else pop,
                "urbanPopulation": pop if spec["urban"] else 0,
                "householdsCount": int(round(pop / 4.8)),
                "averageHouseholdSize": 4.8,
                "polygonAreaHectares": spec["area_ha"],
                "populationDensityPerSqKm": pop_density_sqkm,
                "populationDensityPerHectare": round(pop / spec["area_ha"], 2),
                "populationDensityClass": demo_class_name,
                "children0To14Share": 0.28,
                "workingAge15To59Share": 0.62,
                "elderly60PlusShare": 0.10,
                "vulnerableAgeDependencyRatio": 0.61,
                "scheduledTribeAndCastesShare": 0.24,
                "literacyRate": 0.80 if spec["urban"] else 0.72,
                "agriculturalAndPrimaryLivelihoodShare": 0.15 if spec["urban"] else 0.62,
                "socialVulnerabilityIndex": spec["svi"],
                "vulnerabilityTier": spec["vuln_tier"],
                "corePopulationShare": 0.70,
                "fringeDispersionRadiusMeters": spec["core_r"] * 1.4,
                "coreRadiusMeters": spec["core_r"],
                "mappedBuildingFootprintCount": len(offsets),
                "residentialBuildingCount": 6,
                "centroidLat": spec["clat"],
                "centroidLon": spec["clon"],
            },
            "geometry": {"type": "Polygon", "coordinates": [ring]},
        })

    # Write GeoJSON files
    with open(out_dir / "gangetic_plains_settlement_boundaries.geojson", "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": settlement_features}, f, indent=2)
    with open(out_dir / "gangetic_plains_building_footprints.geojson", "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": building_features}, f, indent=2)
    with open(out_dir / "gangetic_plains_population_demographics.geojson", "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": demographic_features}, f, indent=2)

    # Save GeoTIFF rasters
    save_geotiff_uint8(pa_path, pa_raster)
    save_geotiff_uint8(out_dir / "gangetic_plains_settlement_exposure_utm44.tif", stl_raster)
    save_geotiff_uint8(out_dir / "gangetic_plains_building_density_utm44.tif", bld_raster)
    save_geotiff_uint8(out_dir / "gangetic_plains_population_density_utm44.tif", demo_raster)

    pa_vals, pa_cnts = np.unique(pa_raster, return_counts=True)
    print(f"Saved {len(settlement_features)} settlements, {len(building_features)} buildings, {len(demographic_features)} demographic tracts.")
    print(f"Protected Area raster counts: {dict(zip(pa_vals.tolist(), pa_cnts.tolist()))}")
    print("Gangetic Plains settlement & sacred precinct rebuild COMPLETE!")


if __name__ == "__main__":
    main()
