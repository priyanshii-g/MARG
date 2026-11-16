# Northeast India Highway Planner

This version focuses only on the Start -> End route workflow for the eight-state North Eastern Region.

## Run
npm install
npm run dev

## How it works
1. User enters Start and End latitude/longitude OR picks both points directly on the map.
2. The app checks that both points fall inside the Northeast planning area.
3. The prototype sends the two points to the OSRM routing service.
4. OSRM returns road geometry as GeoJSON.
5. Leaflet displays the returned route on the OpenStreetMap base map.

## Final project integration
Replace the prototype OSRM request with the team's FastAPI + GIS + optimization endpoint. That backend can return GeoJSON route alternatives.

Important: GeoJSON uses [longitude, latitude], while Leaflet map positions use [latitude, longitude].
