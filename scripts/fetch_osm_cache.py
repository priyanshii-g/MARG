#!/usr/bin/env python3
"""
Download real OpenStreetMap geometry caches for Assam and Western Ghats
using the official OSM v0.6 REST API (/api/0.6/map.json).
Saves deduplicated waterways, railways, and highways locally so all
subsequent processing runs completely offline.
"""
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

OSM_API_MAP = "https://api.openstreetmap.org/api/0.6/map.json"


def fetch_osm_bbox(
    name: str,
    min_lon: float,
    min_lat: float,
    max_lon: float,
    max_lat: float,
    depth: int = 0,
):
    url = f"{OSM_API_MAP}?bbox={min_lon:.5f},{min_lat:.5f},{max_lon:.5f},{max_lat:.5f}"
    print(f"[{name}] GET {url}", flush=True)
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "MARG-OSM-Ingestion/2.0 (Geospatial Routing Research)",
            "Accept": "application/json",
        },
    )
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=35) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            elements = data.get("elements", [])
            nodes = {}
            for el in elements:
                if el.get("type") == "node":
                    nodes[el["id"]] = [round(el["lon"], 6), round(el["lat"], 6)]
            waterways = {}
            railways = {}
            highways = {}
            for el in elements:
                if el.get("type") != "way":
                    continue
                tags = el.get("tags", {})
                wid = el["id"]
                coords = [nodes[nid] for nid in el.get("nodes", []) if nid in nodes]
                if len(coords) < 2:
                    continue
                entry = {"id": wid, "tags": tags, "coords": coords}
                ww = tags.get("waterway", "")
                if ww in ("river", "stream", "canal", "drain", "ditch", "weir", "dam"):
                    waterways[wid] = entry
                elif tags.get("natural") == "water" and tags.get("water") in ("river", "canal", "stream"):
                    waterways[wid] = entry
                if tags.get("railway") == "rail":
                    railways[wid] = entry
                hw = tags.get("highway", "")
                if hw in (
                    "motorway",
                    "trunk",
                    "primary",
                    "secondary",
                    "tertiary",
                    "unclassified",
                    "residential",
                    "motorway_link",
                    "trunk_link",
                    "primary_link",
                    "secondary_link",
                    "tertiary_link",
                ):
                    highways[wid] = entry
            print(
                f"  -> {len(elements)} elements | {len(waterways)} waterways, "
                f"{len(railways)} railways, {len(highways)} highways",
                flush=True,
            )
            return waterways, railways, highways
        except urllib.error.HTTPError as e:
            print(f"  -> HTTP {e.code} on {name} attempt {attempt + 1}: {e.reason}", flush=True)
            if e.code == 400 and depth < 3:
                mid_lon = (min_lon + max_lon) / 2.0
                mid_lat = (min_lat + max_lat) / 2.0
                print(f"  -> Subdividing {name} into 4 quadrants (depth={depth + 1})...", flush=True)
                all_ww, all_rw, all_hw = {}, {}, {}
                quads = [
                    (f"{name}_sw", min_lon, min_lat, mid_lon, mid_lat),
                    (f"{name}_se", mid_lon, min_lat, max_lon, mid_lat),
                    (f"{name}_nw", min_lon, mid_lat, mid_lon, max_lat),
                    (f"{name}_ne", mid_lon, mid_lat, max_lon, max_lat),
                ]
                for qn, x0, y0, x1, y1 in quads:
                    ww, rw, hw = fetch_osm_bbox(qn, x0, y0, x1, y1, depth + 1)
                    all_ww.update(ww)
                    all_rw.update(rw)
                    all_hw.update(hw)
                return all_ww, all_rw, all_hw
            if e.code == 400:
                raise
            time.sleep(2.0 * (attempt + 1))
        except Exception as e:
            print(f"  -> Error on {name} attempt {attempt + 1}: {e}", flush=True)
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"Failed to fetch OSM tile {name}")


def collect_tiles(tiles):
    all_ww = {}
    all_rw = {}
    all_hw = {}
    for name, min_lon, min_lat, max_lon, max_lat in tiles:
        ww, rw, hw = fetch_osm_bbox(name, min_lon, min_lat, max_lon, max_lat)
        all_ww.update(ww)
        all_rw.update(rw)
        all_hw.update(hw)
        time.sleep(0.25)
    return list(all_ww.values()), list(all_rw.values()), list(all_hw.values())


def main():
    scripts_dir = Path("scripts")
    import sys
    regions = [r.lower() for r in sys.argv[1:]] if len(sys.argv) > 1 else ["western_ghats", "gangetic_plains"]

    # 1. Assam tiles: Main Kohora-Diphu corridor + all 14 hub & NFR railway/river zones
    if "assam" in regions:
        assam_tiles = [
            ("as_corridor_1", 93.35, 25.81, 93.52, 25.91),
            ("as_corridor_2", 93.35, 25.91, 93.52, 26.01),
            ("as_corridor_3", 93.35, 26.01, 93.52, 26.11),
            ("as_corridor_4", 93.35, 26.11, 93.52, 26.21),
            ("as_corridor_5", 93.35, 26.21, 93.52, 26.31),
            ("as_corridor_6", 93.35, 26.31, 93.52, 26.41),
            ("as_corridor_7", 93.35, 26.41, 93.52, 26.51),
            ("as_corridor_8", 93.35, 26.51, 93.52, 26.64),
            # Hub vicinities & western tributaries/roads
            ("as_dokmoka", 93.24, 26.17, 93.35, 26.23),
            ("as_howraghat_phuloni", 93.19, 26.04, 93.35, 26.17),
            ("as_langhin", 93.29, 26.00, 93.35, 26.04),
            ("as_silonijan", 93.52, 26.30, 93.60, 26.38),
            ("as_dillai_east", 93.52, 25.86, 93.65, 25.98),
            ("as_bokajan", 93.72, 25.99, 93.80, 26.04),
            ("as_bokakhat", 93.52, 26.58, 93.74, 26.65),
            ("as_dhansiri_mid", 93.62, 26.22, 93.70, 26.28),
            ("as_kaliani_west", 93.25, 26.38, 93.35, 26.43),
            ("as_lumding_diphu_sw", 93.25, 25.75, 93.45, 25.81),
            ("as_bagori_west", 93.15, 26.53, 93.32, 26.61),
            ("as_nh129_central", 93.52, 25.98, 93.62, 26.30),
        ]
        print("=== Fetching Assam OSM Tiles ===", flush=True)
        as_ww, as_rw, as_hw = collect_tiles(assam_tiles)
        assam_out = scripts_dir / "osm_assam_cache.json"
        with open(assam_out, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "bbox": "25.75,93.10,26.65,93.75",
                    "waterways": as_ww,
                    "railways": as_rw,
                    "highways": as_hw,
                },
                f,
            )
        print(
            f"Saved {assam_out}: {len(as_ww)} waterways, {len(as_rw)} railways, {len(as_hw)} highways",
            flush=True,
        )

    # 2. Western Ghats transport tiles (Pune to Satara complete corridor)
    if "western_ghats" in regions:
        wg_tiles = [
            ("wg_pune_katraj", 73.80, 18.40, 73.95, 18.50),
            ("wg_khed_shivapur", 73.80, 18.30, 73.95, 18.40),
            ("wg_kapurhol", 73.80, 18.20, 73.95, 18.30),
            ("wg_shirwal_nira", 73.85, 18.10, 74.02, 18.20),
            ("wg_bhor_west", 73.71, 18.10, 73.85, 18.20),
            ("wg_khandala", 73.95, 18.00, 74.08, 18.10),
            ("wg_wai_surur", 73.87, 17.90, 74.05, 18.00),
            ("wg_bhuinj_surur", 73.95, 17.80, 74.08, 17.90),
            ("wg_satara_wadhe", 74.00, 17.70, 74.08, 17.80),
            ("wg_satara_east", 74.00, 17.65, 74.09, 17.70),
        ]
        print("=== Fetching Western Ghats OSM Tiles ===", flush=True)
        wg_out = scripts_dir / "osm_wg_transport_cache.json"
        existing_wg_hw = {}
        existing_wg_rw = {}
        existing_wg_ww = {}
        if wg_out.exists():
            try:
                with open(wg_out, "r", encoding="utf-8") as f:
                    prev = json.load(f)
                    existing_wg_hw = {w["id"]: w for w in prev.get("highways", [])}
                    existing_wg_rw = {w["id"]: w for w in prev.get("railways", [])}
                    existing_wg_ww = {w["id"]: w for w in prev.get("waterways", [])}
            except Exception:
                pass
        wg_ww, wg_rw, wg_hw = collect_tiles(wg_tiles)
        for w in wg_hw: existing_wg_hw[w["id"]] = w
        for w in wg_rw: existing_wg_rw[w["id"]] = w
        for w in wg_ww: existing_wg_ww[w["id"]] = w
        with open(wg_out, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "bbox": "17.60,73.65,18.50,74.30",
                    "waterways": list(existing_wg_ww.values()),
                    "railways": list(existing_wg_rw.values()),
                    "highways": list(existing_wg_hw.values()),
                },
                f,
            )
        print(
            f"Saved {wg_out}: {len(existing_wg_ww)} waterways, {len(existing_wg_rw)} railways, {len(existing_wg_hw)} highways",
            flush=True,
        )

    # 3. Gangetic Plains transport tiles (Barabanki to Ayodhya)
    if "gangetic_plains" in regions:
        gp_tiles = [
            ("gp_barabanki_west", 81.10, 26.90, 81.25, 27.05),
            ("gp_barabanki_ramnagar", 81.25, 26.95, 81.45, 27.15),
            ("gp_safdarganj", 81.25, 26.85, 81.45, 26.95),
            ("gp_ramsanehighat", 81.45, 26.75, 81.65, 26.90),
            ("gp_rudauli", 81.65, 26.70, 81.85, 26.85),
            ("gp_sohawal", 81.85, 26.70, 82.05, 26.85),
            ("gp_ayodhya_cantt", 82.05, 26.70, 82.25, 26.85),
        ]
        print("=== Fetching Gangetic Plains OSM Tiles ===", flush=True)
        gp_ww, gp_rw, gp_hw = collect_tiles(gp_tiles)
        gp_out = scripts_dir / "osm_gp_transport_cache.json"
        with open(gp_out, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "bbox": "26.55,81.10,27.20,82.25",
                    "waterways": gp_ww,
                    "railways": gp_rw,
                    "highways": gp_hw,
                },
                f,
            )
        print(
            f"Saved {gp_out}: {len(gp_ww)} waterways, {len(gp_rw)} railways, {len(gp_hw)} highways",
            flush=True,
        )


if __name__ == "__main__":
    main()
