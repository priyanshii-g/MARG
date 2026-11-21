// Free, zero-API-key Leaflet basemap registry for MARG 13-Layer Lens Explorer

export const BASEMAPS = {
  osm: {
    id: "osm",
    label: "Basic Street Map",
    shortLabel: "Basic Map (OSM)",
    icon: "🗺️",
    url: "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    options: {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19,
    },
  },
  satellite: {
    id: "satellite",
    label: "Satellite Imagery",
    shortLabel: "Satellite",
    icon: "🛰️",
    url: "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    options: {
      attribution: "Tiles &copy; Esri &mdash; Source: Esri, Maxar, Earthstar Geographics, IGN, IGP",
      maxZoom: 19,
    },
  },
  topo: {
    id: "topo",
    label: "Elevation & Contours (Topo)",
    shortLabel: "Topo / DEM",
    icon: "⛰️",
    url: "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
    options: {
      attribution: "Map data: &copy; OpenStreetMap contributors, SRTM | Map style: &copy; OpenTopoMap (CC-BY-SA)",
      maxZoom: 17,
    },
    fallbackId: "relief",
  },
  relief: {
    id: "relief",
    label: "Terrain Shaded Relief",
    shortLabel: "Shaded Relief",
    icon: "🏔️",
    url: "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}",
    options: {
      attribution: "Tiles &copy; Esri &mdash; Esri, DeLorme, NAVTEQ, TomTom, Intermap, USGS, FAO, NPS",
      maxZoom: 18,
    },
    fallbackId: "osm",
  },
  positron: {
    id: "positron",
    label: "Clean Infrastructure Light",
    shortLabel: "Light Canvas",
    icon: "📐",
    url: "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png",
    options: {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/">CARTO</a>',
      subdomains: "abcd",
      maxZoom: 19,
    },
    fallbackId: "osm",
  },
  voyager: {
    id: "voyager",
    label: "Hydrology & Flood Contrast",
    shortLabel: "Voyager",
    icon: "🌊",
    url: "https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png",
    options: {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/">CARTO</a>',
      subdomains: "abcd",
      maxZoom: 19,
    },
    fallbackId: "osm",
  },
};

export const BASEMAP_ORDER = ["osm", "topo", "satellite", "relief", "positron", "voyager"];

export function getBasemapConfig(id) {
  return BASEMAPS[id] || BASEMAPS.osm;
}
