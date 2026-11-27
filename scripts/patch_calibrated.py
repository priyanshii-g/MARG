import json

with open("scripts/calibrated_assam_osm.json", encoding="utf-8") as f:
    calib = json.load(f)

with open("scripts/build_india_protected_areas.py", "r", encoding="utf-8") as f:
    code = f.read()

kz = calib["kaziranga"]
nk = calib["north_karbi"]
ek = calib["east_karbi"]
ml = calib["marat_longri"]
nb = calib["nambor"]

calib_py = f'''# --- CALIBRATED ENTRIES (Exact surveyed OSM / WDPA legal cadastral boundaries) ---
CALIBRATED = {{
    "PA-AS-KAZIRANGA": {{
        "id": "PA-AS-KAZIRANGA",
        "name": "Kaziranga National Park & Tiger Reserve",
        "category": "NATIONAL_PARK",
        "state": "Assam",
        "district": "Golaghat / Nagaon",
        "legalStatute": "WPA 1972 Section 35 / UNESCO World Heritage Site",
        "centerLat": {kz["centerLat"]}, "centerLon": {kz["centerLon"]},
        "coreRadiusDeg": 0.045, "eszRadiusDeg": 0.1355,
        "minLat": {kz["minLat"]}, "maxLat": {kz["maxLat"]}, "minLon": {kz["minLon"]}, "maxLon": {kz["maxLon"]},
        "areaKm2": 858.98,
        "keySpecies": "Great Indian One-Horned Rhinoceros, Royal Bengal Tiger, Asian Elephant, Wild Water Buffalo",
        "boundaryPolygon": {json.dumps(kz["boundaryPolygon"])},
        "boundaryPolygons": {json.dumps(kz["boundaryPolygons"])},
        "eszPolygon": {json.dumps(kz["eszPolygon"])}
    }},
    "PA-AS-NKARBI": {{
        "id": "PA-AS-NKARBI",
        "name": "North Karbi Anglong Wildlife Sanctuary",
        "category": "WILDLIFE_SANCTUARY",
        "state": "Assam",
        "district": "Karbi Anglong",
        "legalStatute": "WPA 1972 Section 26A (Govt Notification 2000)",
        "centerLat": {nk["centerLat"]}, "centerLon": {nk["centerLon"]},
        "coreRadiusDeg": 0.045, "eszRadiusDeg": 0.1355,
        "minLat": {nk["minLat"]}, "maxLat": {nk["maxLat"]}, "minLon": {nk["minLon"]}, "maxLon": {nk["maxLon"]},
        "areaKm2": 96.00,
        "keySpecies": "Asian Elephant, Tiger, Clouded Leopard, Hoolock Gibbon, Barking Deer",
        "boundaryPolygon": {json.dumps(nk["boundaryPolygon"])},
        "boundaryPolygons": {json.dumps(nk["boundaryPolygons"])},
        "eszPolygon": {json.dumps(nk["eszPolygon"])}
    }},
    "PA-AS-EKARBI": {{
        "id": "PA-AS-EKARBI",
        "name": "East Karbi Anglong Wildlife Sanctuary",
        "category": "WILDLIFE_SANCTUARY",
        "state": "Assam",
        "district": "Karbi Anglong",
        "legalStatute": "WPA 1972 Section 26A (Govt Notification 2000)",
        "centerLat": {ek["centerLat"]}, "centerLon": {ek["centerLon"]},
        "coreRadiusDeg": 0.045, "eszRadiusDeg": 0.1355,
        "minLat": {ek["minLat"]}, "maxLat": {ek["maxLat"]}, "minLon": {ek["minLon"]}, "maxLon": {ek["maxLon"]},
        "areaKm2": 221.81,
        "keySpecies": "Asian Elephant, Tiger, Clouded Leopard, Hoolock Gibbon, Barking Deer",
        "boundaryPolygon": {json.dumps(ek["boundaryPolygon"])},
        "boundaryPolygons": {json.dumps(ek["boundaryPolygons"])},
        "eszPolygon": {json.dumps(ek["eszPolygon"])}
    }},
    "PA-AS-MARAT-LONGRI": {{
        "id": "PA-AS-MARAT-LONGRI",
        "name": "Marat Longri Wildlife Sanctuary",
        "category": "WILDLIFE_SANCTUARY",
        "state": "Assam",
        "district": "Karbi Anglong",
        "legalStatute": "WPA 1972 Section 26A (Govt Notification 2003)",
        "centerLat": {ml["centerLat"]}, "centerLon": {ml["centerLon"]},
        "coreRadiusDeg": 0.055, "eszRadiusDeg": 0.1455,
        "minLat": {ml["minLat"]}, "maxLat": {ml["maxLat"]}, "minLon": {ml["minLon"]}, "maxLon": {ml["maxLon"]},
        "areaKm2": 451.00,
        "keySpecies": "Asian Elephant, Tiger, Leopard, Himalayan Black Bear, Dhole",
        "boundaryPolygon": {json.dumps(ml["boundaryPolygon"])},
        "boundaryPolygons": {json.dumps(ml["boundaryPolygons"])},
        "eszPolygon": {json.dumps(ml["eszPolygon"])}
    }},
    "PA-AS-NAMBOR-GARAMPANI": {{
        "id": "PA-AS-NAMBOR-GARAMPANI",
        "name": "Nambor-Garampani Wildlife Sanctuary",
        "category": "WILDLIFE_SANCTUARY",
        "state": "Assam",
        "district": "Karbi Anglong / Golaghat",
        "legalStatute": "WPA 1972 Section 26A (Govt Notification 2000)",
        "centerLat": {nb["centerLat"]}, "centerLon": {nb["centerLon"]},
        "coreRadiusDeg": 0.035, "eszRadiusDeg": 0.1255,
        "minLat": {nb["minLat"]}, "maxLat": {nb["maxLat"]}, "minLon": {nb["minLon"]}, "maxLon": {nb["maxLon"]},
        "areaKm2": 43.05,
        "keySpecies": "Asian Elephant, Hoolock Gibbon, Leopard Cat, Great Pied Hornbill",
        "boundaryPolygon": {json.dumps(nb["boundaryPolygon"])},
        "boundaryPolygons": {json.dumps(nb["boundaryPolygons"])},
        "eszPolygon": {json.dumps(nb["eszPolygon"])}
    }},'''

idx = code.find("# --- CALIBRATED ENTRIES")
if idx == -1:
    idx = code.find("CALIBRATED = {")
idx_end = code.find('"PA-UK-CORBETT": {')
new_code = code[:idx] + calib_py + "\n    " + code[idx_end:]

with open("scripts/build_india_protected_areas.py", "w", encoding="utf-8") as f:
    f.write(new_code)

print("Updated scripts/build_india_protected_areas.py with MultiPolygon Assam geometries and 10 km ESZ!")
