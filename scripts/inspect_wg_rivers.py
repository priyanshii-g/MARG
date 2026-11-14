import json

with open('data/processed/regions/western_ghats/western_ghats_perennial_rivers.geojson', 'r', encoding='utf-8') as f:
    data = json.load(f)

print('Total river features:', len(data['features']))
for i, feat in enumerate(data['features']):
    props = feat['properties']
    coords = feat['geometry']['coordinates']
    lons = [c[0] for c in coords]
    lats = [c[1] for c in coords]
    print(f"{i}: id={props.get('riverId')} name={props.get('riverName')} class={props.get('riverClass')} pts={len(coords)} lon=[{min(lons):.3f}, {max(lons):.3f}] lat=[{min(lats):.3f}, {max(lats):.3f}]")
