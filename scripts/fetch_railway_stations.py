import urllib.request
import urllib.parse
import json
import time

def get_stations(station_names, state):
    results = []
    for s in station_names:
        q = f"{s}, {state}"
        url = f"https://nominatim.openstreetmap.org/search?q={urllib.parse.quote(q)}&format=json"
        req = urllib.request.Request(url, headers={"User-Agent": "MARG-Railway/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                d = json.loads(r.read().decode("utf-8"))
                if d:
                    lat, lon = float(d[0]["lat"]), float(d[0]["lon"])
                    results.append({"name": s, "lat": lat, "lon": lon, "display": d[0]["display_name"]})
                    print(f"  {s}: [{lon:.5f}, {lat:.5f}]")
                else:
                    print(f"  {s}: NOT FOUND")
        except Exception as e:
            print(f"  {s}: ERR {e}")
        time.sleep(0.5)
    return results

print("1. Gangetic Plains Railway Stations (Northern Railway: Barabanki to Ayodhya):")
gp_stations = [
    "Barabanki Junction", "Rasauli railway station", "Safdarganj railway station",
    "Daryabad railway station", "Patranga railway station", "Rudauli railway station",
    "Sohawal railway station", "Ayodhya Cantt railway station"
]
gp_res = get_stations(gp_stations, "Uttar Pradesh")

print("\n2. Western Ghats Railway Stations (Central Railway: Pune to Satara):")
wg_stations = [
    "Pune Junction", "Sasvad Road railway station", "Jejuri railway station",
    "Nira railway station", "Lonand railway station", "Wathar railway station", "Satara railway station"
]
wg_res = get_stations(wg_stations, "Maharashtra")

print("\n3. Assam Railway Stations (Northeast Frontier Railway: Lumding to Furkating):")
assam_stations = [
    "Lumding Junction", "Diphu railway station", "Bokajan railway station",
    "Barpathar railway station", "Sarupathar railway station", "Furkating Junction"
]
assam_res = get_stations(assam_stations, "Assam")

out = {"gp": gp_res, "wg": wg_res, "assam": assam_res}
with open("scripts/all_railway_stations.json", "w", encoding="utf-8") as f:
    json.dump(out, f, indent=2)
