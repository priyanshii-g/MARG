// Free, zero-API-key Leaflet basemap registry for MARG Map Views

export const BASEMAPS = {
  osm: {
    id: "osm",
    label: "Street Map",
    shortLabel: "Street Map",
    icon: "🗺️",
    url: "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    options: {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19,
    },
  },
  satellite: {
    id: "satellite",
    label: "Satellite View",
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
    label: "Hills & Elevation Map",
    shortLabel: "Hills & Elevation",
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
    label: "Terrain Shaded Map",
    shortLabel: "Terrain Relief",
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
    label: "Clean Light Map",
    shortLabel: "Light Map",
    icon: "📐",
    url: "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}",
    overlayUrl: "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Reference/MapServer/tile/{z}/{y}/{x}",
    options: {
      attribution: "Tiles &copy; Esri &mdash; Esri, DeLorme, NAVTEQ",
      maxZoom: 16,
    },
    fallbackId: "osm",
  },
  voyager: {
    id: "voyager",
    label: "Rivers & Roads View",
    shortLabel: "Rivers & Roads",
    icon: "🌊",
    url: "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
    options: {
      attribution: "Tiles &copy; Esri &mdash; Source: Esri, DeLorme, NAVTEQ, USGS, Intermap, iPC, NRCAN, Esri Japan, METI, Esri China (Hong Kong), Esri (Thailand), TomTom",
      maxZoom: 19,
    },
    fallbackId: "osm",
  },
};

export const BASEMAP_ORDER = ["osm", "topo", "satellite", "relief", "positron", "voyager"];

export function getBasemapConfig(id) {
  return BASEMAPS[id] || BASEMAPS.osm;
}
