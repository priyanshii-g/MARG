import { useEffect, useMemo, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { getBasemapConfig } from "./layers/basemaps";
import {
  LAYER_BY_ID,
  OVERVIEW_FEATURE_ROLES,
  formatClassLabel,
  getSegmentColor,
} from "./layers/layerCatalog";
import LayerAuditPanel from "./components/LayerAuditPanel";
import MapLensHud from "./components/MapLensHud";
import EngineeringDossier from "./components/EngineeringDossier";

// -------------------------------------------------------------
// India & Benchmark Region Constants
// -------------------------------------------------------------
const PAN_INDIA_BOUNDS = [
  [6.5, 68.0],
  [37.5, 97.5],
];

const PRESET_CORRIDORS = [
  {
    id: "assam",
    tier: "TIER_2_PRECACHED_BENCHMARK",
    name: "Corridor 1: Kaziranga–Karbi Anglong (Assam)",
    tag: "Eco-Sensitive & Flood",
    start: { lat: 26.5897, lng: 93.40035 },
    end: { lat: 25.84573, lng: 93.43781 },
    center: [26.25, 93.42],
    zoom: 9,
    suggestedHubs: ["Diphu", "Dokmoka", "Bokakhat", "Howraghat", "Manja", "Silonijan"],
  },
  {
    id: "western_ghats",
    tier: "TIER_2_PRECACHED_BENCHMARK",
    name: "Corridor 2: Pune–Satara (Western Ghats, MH)",
    tag: "Steep Slope & Landslide",
    start: { lat: 18.36002, lng: 73.85352 },
    end: { lat: 17.71704, lng: 74.02648 },
    center: [18.03, 73.93],
    zoom: 9,
    suggestedHubs: ["Wai", "Shirwal", "Satara", "Bhor", "Khandala"],
  },
  {
    id: "gangetic_plains",
    tier: "TIER_2_PRECACHED_BENCHMARK",
    name: "Corridor 3: Barabanki–Ayodhya (Gangetic Plains, UP)",
    tag: "Floodplain & Prime Agri",
    start: { lat: 26.915, lng: 81.245 },
    end: { lat: 26.765, lng: 82.125 },
    center: [26.84, 81.68],
    zoom: 9,
    suggestedHubs: ["Ramnagar", "Ramsanehighat", "Rudauli", "Barabanki", "Faizabad", "Zaidpur"],
  },
  {
    id: "interstate_pan_india",
    tier: "TIER_1_DYNAMIC_PAN_INDIA",
    name: "Tier 1 Mega-Corridor: Akola (MH) to Bhopal (MP)",
    tag: "411 km Interstate",
    start: { lat: 20.15, lng: 76.85 },
    end: { lat: 23.85, lng: 77.40 },
    center: [22.0, 77.12],
    zoom: 7,
    suggestedHubs: ["Betul", "Hoshangabad"],
  },
];

function isInsideIndia(lat, lng) {
  return lat >= 6.5 && lat <= 37.5 && lng >= 68.0 && lng <= 97.5;
}

function humanizeSnapReason(reason) {
  if (!reason) return "Terrain or environmental exclusion constraint";
  const r = reason.toUpperCase();
  if (r.includes("HOSPITAL") || r.includes("MEDICAL") || r.includes("CLINIC")) {
    return "Hospital / Medical Campus Exclusion Zone (+100m safety buffer)";
  }
  if (r.includes("INSTITUTIONAL") || r.includes("SCHOOL") || r.includes("COLLEGE") || r.includes("UNIVERSITY")) {
    return "Educational / Institutional Campus Exclusion Zone (+100m safety buffer)";
  }
  if (r.includes("SACRED") || r.includes("HERITAGE") || r.includes("RELIGIOUS")) {
    return "Sacred Temple / Heritage Precinct Exclusion Zone (Zero-tolerance buffer)";
  }
  if (r.includes("COLONY") || r.includes("RESIDENTIAL_SETTLEMENT")) {
    return "Dense Residential Colony / Urban Settlement Boundary";
  }
  if (r.includes("BUILDING_FOOTPRINT")) {
    return "Existing Building Footprint / Structure Clearance";
  }
  if (r.includes("PROTECTED_AREA") || r.includes("WILDLIFE_SANCTUARY")) {
    return "Protected Wildlife Sanctuary / National Park Core (Zero-tolerance exclusion)";
  }
  if (r.includes("SLOPE")) {
    return "Excessive Mountain Slope (> 30° / Infeasible IRC gradient)";
  }
  if (r.includes("WATER_BODIES") || r.includes("PERENNIAL_RIVER") || r.includes("RESERVOIR")) {
    return "Deep Water Body / Perennial River Channel";
  }
  if (r.includes("LANDSLIDE") || r.includes("FAULT") || r.includes("GEOTECH")) {
    return "Active Geotechnical Fault / Landslide Zone";
  }
  if (r.includes("SETTLEMENT") || r.includes("BUILDING")) {
    return "Dense Urban Habitation Core";
  }
  if (r.includes("ISOLATED_GRID_NODE")) {
    return "Isolated terrain pocket with no contiguous 250m grid egress";
  }
  return reason
    .replace(/^SNAPPED_FROM_/, "")
    .replace(/_TO_NEAREST_FEASIBLE_GRID_CELL$/, "")
    .replace(/_/g, " ");
}

export default function App() {
  const mapRef = useRef(null);
  const mapContainerRef = useRef(null);
  const mapSelectionModeRef = useRef(null);

  // Base Map Layer & Type (No API keys required)
  const [baseMapType, setBaseMapType] = useState("osm");
  const [lockBasemap, setLockBasemap] = useState(false);
  const baseTileLayerRef = useRef(null);

  // Markers and Vector Layers
  const startMarkerRef = useRef(null);
  const endMarkerRef = useRef(null);
  const snapVectorGroupRef = useRef(null);
  const routeLayersGroupRef = useRef(null);
  const colorizedRouteGroupRef = useRef(null);
  const focusHighlightGroupRef = useRef(null);
  const comparativeRoutesGroupRef = useRef(null);

  // Application State
  const [selectedPresetId, setSelectedPresetId] = useState("assam");
  const [facilityMode, setFacilityMode] = useState("EXPRESSWAY");
  const [deepIngestion, setDeepIngestion] = useState(false);
  const [mapSelectionMode, setMapSelectionMode] = useState(null);

  // Coordinates (defaults to Corridor 1 Assam)
  const [start, setStart] = useState({ lat: 26.5897, lng: 93.40035 });
  const [end, setEnd] = useState({ lat: 25.84573, lng: 93.43781 });

  // Intermediate Hubs & Advanced Design Parameters
  const [autoConnectHubs, setAutoConnectHubs] = useState(true);
  const [maxAutoHubs, setMaxAutoHubs] = useState(2);
  const [targetHubs, setTargetHubs] = useState([]);
  const [customHubInput, setCustomHubInput] = useState("");
  const [maxVcRatio, setMaxVcRatio] = useState(0.70);
  const [maxDeviationPercent, setMaxDeviationPercent] = useState(15);

  // All-India 780+ Districts Search & Autocomplete State
  const [districtSuggestions, setDistrictSuggestions] = useState([]);
  const [startDistrictQuery, setStartDistrictQuery] = useState("");
  const [startDistrictSuggestions, setStartDistrictSuggestions] = useState([]);
  const [endDistrictQuery, setEndDistrictQuery] = useState("");
  const [endDistrictSuggestions, setEndDistrictSuggestions] = useState([]);

  // Routing Execution State
  const [isLoading, setIsLoading] = useState(false);
  const [status, setStatus] = useState("Select endpoints or a benchmark corridor, then generate alignment.");
  const [routingError, setRoutingError] = useState(null);
  const [routeInfo, setRouteInfo] = useState(null);
  const [networkSummary, setNetworkSummary] = useState(null);
  const [connectedHubs, setConnectedHubs] = useState([]);
  const [trafficCapacity, setTrafficCapacity] = useState(null);
  const [hubDecisions, setHubDecisions] = useState([]);
  const [showAllDecisions, setShowAllDecisions] = useState(false);

  // Multi-Objective Trade-Off & Request-Scoped User Alignment Tuning State
  const [tradeOffProfile, setTradeOffProfile] = useState(null);
  const [tradeOffPreset, setTradeOffPreset] = useState("BALANCED");
  const [envWeight, setEnvWeight] = useState(1.0);
  const [socialWeight, setSocialWeight] = useState(1.0);
  const [directnessWeight, setDirectnessWeight] = useState(1.0);
  const [alternativeRoutes, setAlternativeRoutes] = useState([]);
  const [activeAlternativeId, setActiveAlternativeId] = useState(null);
  const [isRealigning, setIsRealigning] = useState(false);
  const [statutoryClearanceReport, setStatutoryClearanceReport] = useState(null);

  // Multi-Objective Archetype Route Memory & Live Comparative Caching
  const [cachedArchetypes, setCachedArchetypes] = useState({});
  const lastCoordsKeyRef = useRef(null);

  // Endpoint Auto-Shift Notification State
  const [snapNotice, setSnapNotice] = useState(null);

  // 13-Layer Verification Audit & Interactive Map Lens State
  const [currentFeatures, setCurrentFeatures] = useState([]);
  const [layerAudit, setLayerAudit] = useState([]);
  const [routeLayerProfile, setRouteLayerProfile] = useState([]);
  const [spurLayerProfiles, setSpurLayerProfiles] = useState([]);
  const [routeDiagnostics, setRouteDiagnostics] = useState(null);
  const [terminalRoadConnections, setTerminalRoadConnections] = useState(null);
  const [spurEngineeringSummary, setSpurEngineeringSummary] = useState(null);
  const [activeLayerId, setActiveLayerId] = useState(null);
  const [pinnedLayerIds, setPinnedLayerIds] = useState(() => new Set());
  const [showMultiArchetypes, setShowMultiArchetypes] = useState(true);

  // Botanical Atelier Collapsible Drawers & Viewport Refs
  const [leftDrawerOpen, setLeftDrawerOpen] = useState(true);
  const [rightDrawerOpen, setRightDrawerOpen] = useState(true);
  const mapStudioRef = useRef(null);
  const dossierRef = useRef(null);

  // Re-measure Leaflet map smoothly whenever Left or Right drawer collapses/expands
  useEffect(() => {
    const timer = setTimeout(() => {
      if (mapRef.current) {
        mapRef.current.invalidateSize({ animate: true });
      }
    }, 280);
    return () => clearTimeout(timer);
  }, [leftDrawerOpen, rightDrawerOpen]);

  // Coords Key Tracker: Automatically clear cached routes if endpoints change
  const currentCoordsKey = start && end ? `${start.lat.toFixed(4)},${start.lng.toFixed(4)}->${end.lat.toFixed(4)},${end.lng.toFixed(4)}` : null;

  useEffect(() => {
    if (currentCoordsKey && lastCoordsKeyRef.current && currentCoordsKey !== lastCoordsKeyRef.current) {
      setCachedArchetypes({});
      setAlternativeRoutes([]);
      setActiveAlternativeId(null);
      setStatutoryClearanceReport(null);
      setCurrentFeatures([]);
      setLayerAudit([]);
      setRouteLayerProfile([]);
      setSpurLayerProfiles([]);
      setRouteDiagnostics(null);
      setTerminalRoadConnections(null);
      setSpurEngineeringSummary(null);
      setActiveLayerId(null);
      setPinnedLayerIds(new Set());
    }
    lastCoordsKeyRef.current = currentCoordsKey;
  }, [currentCoordsKey]);

  // Live Comparative Deltas vs Balanced Alignment
  const comparativeDeltasVsBalanced = useMemo(() => {
    if (!cachedArchetypes["BALANCED"] || tradeOffPreset === "BALANCED" || !routeInfo) {
      return null;
    }
    const bal = cachedArchetypes["BALANCED"];
    const currKm = parseFloat(routeInfo.routeLengthKm || 0);
    const balKm = parseFloat(bal.routeInfo?.routeLengthKm || 0);
    if (!currKm || !balKm) return null;

    const deltaKm = Number((currKm - balKm).toFixed(2));
    const deltaMins = Number(((deltaKm / 85.0) * 60.0).toFixed(1));
    const deltaVocLakh = Number((-deltaKm * 28.74).toFixed(1));

    const currForest = tradeOffProfile?.environmentalSummary?.forestHectaresDirectRow ?? 0;
    const balForest = bal.tradeOffProfile?.environmentalSummary?.forestHectaresDirectRow ?? 0;
    const deltaForestHa = Number((currForest - balForest).toFixed(1));

    const currDisplaced = tradeOffProfile?.socialSummary?.estimatedDirectlyDisplacedPopulation ?? 0;
    const balDisplaced = bal.tradeOffProfile?.socialSummary?.estimatedDirectlyDisplacedPopulation ?? 0;
    const deltaDisplaced = currDisplaced - balDisplaced;

    return {
      deltaKm,
      deltaMins,
      deltaVocLakh,
      deltaForestHa,
      deltaDisplaced,
    };
  }, [cachedArchetypes, tradeOffPreset, routeInfo, tradeOffProfile]);

  // -----------------------------------------------------------
  // Initialize Leaflet Map
  // -----------------------------------------------------------
  useEffect(() => {
    if (mapRef.current) return;

    const map = L.map(mapContainerRef.current, {
      center: [26.25, 93.42],
      zoom: 9,
      maxBounds: PAN_INDIA_BOUNDS,
      maxBoundsViscosity: 0.9,
    });

    // Standard OpenStreetMap Basemap (100% Free, No API Key Required)
    const initialTileLayer = L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      maxZoom: 19,
    }).addTo(map);
    baseTileLayerRef.current = initialTileLayer;

    // Vector Groups
    snapVectorGroupRef.current = L.layerGroup().addTo(map);
    routeLayersGroupRef.current = L.layerGroup().addTo(map);
    colorizedRouteGroupRef.current = L.layerGroup().addTo(map);
    focusHighlightGroupRef.current = L.layerGroup().addTo(map);
    comparativeRoutesGroupRef.current = L.layerGroup().addTo(map);

    // Map Click Handler for Interactive Endpoint Picking
    map.on("click", (e) => {
      const mode = mapSelectionModeRef.current;
      if (!mode) return;

      const lat = Number(e.latlng.lat.toFixed(5));
      const lng = Number(e.latlng.lng.toFixed(5));

      if (!isInsideIndia(lat, lng)) {
        setStatus("⚠️ Clicked point is outside the Pan-India planning boundary.");
        return;
      }

      if (mode === "start") {
        setStart({ lat, lng });
        setSelectedPresetId("custom");
        mapSelectionModeRef.current = null;
        setMapSelectionMode(null);
        setStatus(`📍 Origin selected: ${lat}, ${lng}`);
      } else if (mode === "end") {
        setEnd({ lat, lng });
        setSelectedPresetId("custom");
        mapSelectionModeRef.current = null;
        setMapSelectionMode(null);
        setStatus(`🏁 Destination selected: ${lat}, ${lng}`);
      } else if (mode === "hub") {
        const hubToken = `${lat.toFixed(4)} ${lng.toFixed(4)}`;
        setTargetHubs((prev) => (prev.includes(hubToken) ? prev : [...prev, hubToken]));
        mapSelectionModeRef.current = null;
        setMapSelectionMode(null);
        setStatus(`🏙️ Added Intermediate Hub: ${hubToken}`);
      }
    });

    mapRef.current = map;

    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // -----------------------------------------------------------
  // Multi-Basemap Switcher (OSM, OpenTopoMap, Esri Satellite/Topo/Light Gray/Street - 100% Free, No API Keys)
  // -----------------------------------------------------------
  useEffect(() => {
    if (!mapRef.current) return;
    if (baseTileLayerRef.current) {
      mapRef.current.removeLayer(baseTileLayerRef.current);
    }
    const cfg = getBasemapConfig(baseMapType);
    let tileErrors = 0;
    const baseTiles = L.tileLayer(cfg.url, cfg.options);
    baseTiles.on("tileerror", () => {
      tileErrors += 1;
      if (tileErrors === 4 && cfg.fallbackId && cfg.fallbackId !== baseMapType) {
        setBaseMapType(cfg.fallbackId);
      }
    });

    if (cfg.overlayUrl) {
      const refTiles = L.tileLayer(cfg.overlayUrl, {
        ...cfg.options,
        attribution: "",
      });
      const group = L.layerGroup([baseTiles, refTiles]).addTo(mapRef.current);
      if (typeof baseTiles.bringToBack === "function") baseTiles.bringToBack();
      baseTileLayerRef.current = group;
    } else {
      baseTiles.addTo(mapRef.current);
      if (typeof baseTiles.bringToBack === "function") baseTiles.bringToBack();
      baseTileLayerRef.current = baseTiles;
    }
  }, [baseMapType]);

  // -----------------------------------------------------------
  // Update Start & End Markers
  // -----------------------------------------------------------
  useEffect(() => {
    if (!mapRef.current) return;

    // Start Marker (Green)
    if (startMarkerRef.current) {
      startMarkerRef.current.setLatLng([start.lat, start.lng]);
    } else {
      const icon = L.divIcon({
        className: "marg-marker start",
        html: "<span>A</span>",
        iconSize: [28, 28],
        iconAnchor: [14, 14],
      });
      startMarkerRef.current = L.marker([start.lat, start.lng], { icon })
        .addTo(mapRef.current)
        .bindPopup("<b>Highway Origin (A)</b>");
    }

    // End Marker (Red)
    if (endMarkerRef.current) {
      endMarkerRef.current.setLatLng([end.lat, end.lng]);
    } else {
      const icon = L.divIcon({
        className: "marg-marker end",
        html: "<span>B</span>",
        iconSize: [28, 28],
        iconAnchor: [14, 14],
      });
      endMarkerRef.current = L.marker([end.lat, end.lng], { icon })
        .addTo(mapRef.current)
        .bindPopup("<b>Highway Destination (B)</b>");
    }
  }, [start, end]);

  // Handle Crosshair cursor during map-selection
  useEffect(() => {
    if (!mapRef.current) return;
    const container = mapRef.current.getContainer();
    if (mapSelectionMode) {
      container.classList.add("crosshair-cursor");
    } else {
      container.classList.remove("crosshair-cursor");
    }
  }, [mapSelectionMode]);

  // Debounced Search for Intermediate District Hubs
  useEffect(() => {
    if (!customHubInput || customHubInput.trim().length < 2) {
      setDistrictSuggestions([]);
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const res = await fetch(`/api/districts/search?q=${encodeURIComponent(customHubInput.trim())}&limit=8`);
        if (res.ok) {
          const data = await res.json();
          setDistrictSuggestions(data);
        }
      } catch (err) {
        console.warn("District search error:", err);
      }
    }, 200);
    return () => clearTimeout(timer);
  }, [customHubInput]);

  // Debounced Search for Origin District (Point A)
  useEffect(() => {
    if (!startDistrictQuery || startDistrictQuery.trim().length < 2) {
      setStartDistrictSuggestions([]);
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const res = await fetch(`/api/districts/search?q=${encodeURIComponent(startDistrictQuery.trim())}&limit=6`);
        if (res.ok) {
          const data = await res.json();
          setStartDistrictSuggestions(data);
        }
      } catch (err) {}
    }, 200);
    return () => clearTimeout(timer);
  }, [startDistrictQuery]);

  // Debounced Search for Destination District (Point B)
  useEffect(() => {
    if (!endDistrictQuery || endDistrictQuery.trim().length < 2) {
      setEndDistrictSuggestions([]);
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const res = await fetch(`/api/districts/search?q=${encodeURIComponent(endDistrictQuery.trim())}&limit=6`);
        if (res.ok) {
          const data = await res.json();
          setEndDistrictSuggestions(data);
        }
      } catch (err) {}
    }, 200);
    return () => clearTimeout(timer);
  }, [endDistrictQuery]);

  // -----------------------------------------------------------
  // Alignment Trade-Off Archetype Preset Definitions
  // -----------------------------------------------------------
  const PRESET_DEFINITIONS = {
    BALANCED: {
      id: "BALANCED",
      label: "⚖️ Balanced",
      shortLabel: "Balanced",
      env: 1.0,
      social: 1.0,
      direct: 1.0,
      color: "#1B4332",
      description: "Harmonized IRC:SP:84 engineering weighting across environment, communities & directness",
    },
    ECO_SHIELD: {
      id: "ECO_SHIELD",
      label: "🌿 Protect Nature & Rivers",
      shortLabel: "Eco-Shield",
      env: 2.5,
      social: 1.0,
      direct: 0.8,
      color: "#10b981",
      description: "Aggressively detours around dense forests, wildlife corridors, and river meander floodplains",
    },
    COMMUNITY_SAFEGUARD: {
      id: "COMMUNITY_SAFEGUARD",
      label: "🏡 Protect Rural Communities",
      shortLabel: "Community",
      env: 1.0,
      social: 2.5,
      direct: 0.8,
      color: "#8b5cf6",
      description: "Prioritizes zero demolition of rural homesteads, village borders & civic settlements",
    },
    SHORTEST_PATH: {
      id: "SHORTEST_PATH",
      label: "⚡ Shortest Direct Corridor",
      shortLabel: "Direct Express",
      env: 0.8,
      social: 0.8,
      direct: 2.5,
      color: "#06b6d4",
      description: "Maximizes geometric Euclidean efficiency, vehicle operating cost savings & travel speed",
    },
  };

  // -----------------------------------------------------------
  // Switch to Archetype (Instantly from memory if cached, else evaluate)
  // -----------------------------------------------------------
  function switchToArchetype(targetKey) {
    const p = PRESET_DEFINITIONS[targetKey];
    if (!p) return;

    // 1. If already generated & cached for current endpoints: INSTANT SWITCH!
    if (cachedArchetypes[targetKey]) {
      const cached = cachedArchetypes[targetKey];
      setTradeOffPreset(targetKey);
      setEnvWeight(p.env);
      setSocialWeight(p.social);
      setDirectnessWeight(p.direct);

      // Re-render GIS symbology & update state directly from memory (0 ms delay)
      setCurrentFeatures(cached.features || []);
      setLayerAudit(cached.layerAudit || []);
      setRouteLayerProfile(cached.routeLayerProfile || []);
      setSpurLayerProfiles(cached.spurLayerProfiles || []);
      setRouteDiagnostics(cached.routeDiagnostics || null);
      setTerminalRoadConnections(cached.terminalRoadConnections || null);
      setSpurEngineeringSummary(cached.spurEngineeringSummary || null);
      renderGeoJsonFeatures(cached.features || [], targetKey, activeLayerId, pinnedLayerIds, cached.routeLayerProfile || [], cached.spurLayerProfiles || [], false);
      setRouteInfo(cached.routeInfo);
      setNetworkSummary(cached.networkSummary);
      setTradeOffProfile(cached.tradeOffProfile);
      setConnectedHubs(cached.connectedHubs);
      setTrafficCapacity(cached.trafficCapacity);
      setHubDecisions(cached.hubDecisions);
      setStatutoryClearanceReport(cached.statutoryClearanceReport || cached.tradeOffProfile?.statutoryClearanceReport || null);

      setActiveAlternativeId("alt-" + targetKey);
      setStatus(`⚡ Instantly switched to cached ${p.label} corridor (${cached.routeInfo.routeLengthKm} km) with zero recalculation delay.`);
      return;
    }

    // 2. If not yet in cache, fetch & evaluate corridor for this archetype
    realignAlignment(targetKey);
  }

  function handleSelectPreset(key) {
    const p = PRESET_DEFINITIONS[key];
    if (!p) return;
    setTradeOffPreset(p.id);
    setEnvWeight(p.env);
    setSocialWeight(p.social);
    setDirectnessWeight(p.direct);

    // If an alignment is already loaded on the map, switch to it immediately!
    if (routeInfo) {
      switchToArchetype(key);
    }
  }

  function handleSliderChange(type, val) {
    setTradeOffPreset("CUSTOM");
    const num = parseFloat(val);
    if (type === "env") setEnvWeight(num);
    if (type === "social") setSocialWeight(num);
    if (type === "direct") setDirectnessWeight(num);
  }

  // -----------------------------------------------------------
  // Multi-Archetype Comparison Overlay on Map
  // -----------------------------------------------------------
  useEffect(() => {
    if (!mapRef.current || !comparativeRoutesGroupRef.current) return;
    comparativeRoutesGroupRef.current.clearLayers();

    if (!showMultiArchetypes) return;

    Object.entries(cachedArchetypes).forEach(([presetKey, snapshot]) => {
      if (presetKey === tradeOffPreset) return;

      const pDef = PRESET_DEFINITIONS[presetKey] || {
        shortLabel: presetKey,
        label: presetKey,
        color: "#94a3b8",
      };

      const features = snapshot.features || snapshot.data?.features || [];
      const mainlineFeat = features.find(
        (f) => f.properties?.featureRole === "GREENFIELD_MAINLINE" || f.properties?.featureRole === "MAINLINE"
      );

      if (mainlineFeat) {
        const altLength = snapshot.routeInfo?.routeLengthKm || mainlineFeat.properties?.routeLengthKm || "—";
        const altDev = snapshot.routeInfo?.deviationPercent || mainlineFeat.properties?.deviationPercent || "—";
        const layer = L.geoJSON(mainlineFeat, {
          style: {
            color: pDef.color,
            weight: 4,
            dashArray: "8, 6",
            opacity: 0.8,
          },
        });

        layer.bindTooltip(
          `<div style="font-size:11px;">
            <strong style="color:${pDef.color};">${pDef.label || pDef.shortLabel}</strong><br/>
            <span>Mainline: <b>${altLength} km</b> (+${altDev}%)</span><br/>
            <span style="font-size:10px; color:#2D6A4F; font-weight:700;">⚡ Click alignment to activate</span>
          </div>`,
          { sticky: true }
        );

        layer.on("click", () => {
          switchToArchetype(presetKey);
        });

        layer.on("mouseover", function () {
          this.setStyle({ weight: 6, opacity: 1.0 });
        });
        layer.on("mouseout", function () {
          this.setStyle({ weight: 4, opacity: 0.8 });
        });

        comparativeRoutesGroupRef.current.addLayer(layer);
      }
    });
  }, [cachedArchetypes, tradeOffPreset, showMultiArchetypes]);

  // -----------------------------------------------------------
  // Preset Selection Handler
  // -----------------------------------------------------------
  function applyPreset(preset) {
    setSelectedPresetId(preset.id);
    setStart(preset.start);
    setEnd(preset.end);
    setTargetHubs([]);
    setSnapNotice(null);
    setRoutingError(null);
    setCachedArchetypes({});
    setAlternativeRoutes([]);
    setActiveAlternativeId(null);
    setCurrentFeatures([]);
    setLayerAudit([]);
    setRouteLayerProfile([]);
    setSpurLayerProfiles([]);
    setRouteDiagnostics(null);
    setTerminalRoadConnections(null);
    setSpurEngineeringSummary(null);
    setActiveLayerId(null);
    setPinnedLayerIds(new Set());
    if (!lockBasemap) {
      setBaseMapType("osm");
    }
    setStatus(`Loaded ${preset.name}. Click 'Generate MARG Alignment' to evaluate.`);

    if (mapRef.current) {
      mapRef.current.flyTo(preset.center, preset.zoom, { duration: 1.2 });
    }
  }

  // -----------------------------------------------------------
  // Main Alignment Generation Call
  // -----------------------------------------------------------
  async function generateRoute() {
    if (!isInsideIndia(start.lat, start.lng) || !isInsideIndia(end.lat, end.lng)) {
      setRoutingError({
        title: "Coordinates Outside Boundary",
        message: "Both endpoints must lie within India (6.5°N–37.5°N, 68.0°E–97.5°E).",
      });
      return;
    }

    setIsLoading(true);
    setRoutingError(null);
    setSnapNotice(null);
    setConnectedHubs([]);
    setTrafficCapacity(null);
    setHubDecisions([]);
    setShowAllDecisions(false);
    setStatus("Synthesizing 13-layer spatial constraints & computing greenfield alignment...");

    // Clear previous vectors
    if (snapVectorGroupRef.current) snapVectorGroupRef.current.clearLayers();
    if (routeLayersGroupRef.current) routeLayersGroupRef.current.clearLayers();

    try {
      const params = new URLSearchParams({
        startLat: start.lat,
        startLon: start.lng,
        endLat: end.lat,
        endLon: end.lng,
        facilityMode: facilityMode,
      });

      if (deepIngestion) {
        params.append("deep", "true");
      }

      params.append("autoConnectHubs", autoConnectHubs.toString());
      if (autoConnectHubs && maxAutoHubs != null) {
        params.append("maxAutoHubs", maxAutoHubs.toString());
      }

      if (targetHubs.length > 0) {
        params.append("targetHubs", targetHubs.join(","));
      }
      if (maxVcRatio != null) {
        params.append("maxVcRatio", maxVcRatio.toString());
      }
      if (maxDeviationPercent != null) {
        params.append("maxDeviationPercent", maxDeviationPercent.toString());
      }

      // If user selected a preset corridor, pass region & regionId for instant pre-cached evaluation
      if (selectedPresetId && selectedPresetId !== "custom" && selectedPresetId !== "interstate_pan_india") {
        params.append("region", selectedPresetId);
        params.append("regionId", selectedPresetId);
      }

      // Multi-Objective Alignment Preference Tuning (Request-Scoped)
      params.append("envWeight", envWeight.toString());
      params.append("socialWeight", socialWeight.toString());
      params.append("directnessWeight", directnessWeight.toString());
      params.append("tradeOffPreset", tradeOffPreset);

      const response = await fetch(`/api/routes/composite?${params.toString()}`);
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || `Backend error: HTTP ${response.status}`);
      }

      const data = await response.json();
      if (!data.features || data.features.length === 0) {
        throw new Error("No route features returned by the routing engine.");
      }

      // ---------------------------------------------------------
      // 1. SMART ENDPOINT SNAPPING NOTIFICATION & VISUALIZATION
      // ---------------------------------------------------------
      const endpointRes = data.metadata?.endpointResolution;
      let snapTriggered = false;
      const snapDetails = { start: null, end: null };

      let resolvedStartLat = start.lat;
      let resolvedStartLng = start.lng;
      let resolvedEndLat = end.lat;
      let resolvedEndLng = end.lng;

      if (endpointRes) {
        // Start Point Snapped?
        if (endpointRes.startSnapped && endpointRes.resolvedStart && endpointRes.requestedStart) {
          snapTriggered = true;
          const req = endpointRes.requestedStart;
          const res = endpointRes.resolvedStart;
          const dist = Math.round(endpointRes.startSnapDistanceMeters || 0);
          const reason = humanizeSnapReason(endpointRes.startSnapReason);

          snapDetails.start = {
            requested: { lat: req.lat, lng: req.lon ?? req.lng },
            resolved: { lat: res.lat, lng: res.lon ?? res.lng },
            distanceMeters: dist,
            reason: reason,
          };

          // Update local start state to the resolved feasible coordinate!
          resolvedStartLat = res.lat;
          resolvedStartLng = res.lon ?? res.lng;
          setStart({ lat: resolvedStartLat, lng: resolvedStartLng });

          // Plot ghost marker at the original requested click
          if (snapVectorGroupRef.current) {
            const origIcon = L.divIcon({
              className: "marg-marker orig",
              html: "<span>✕</span>",
              iconSize: [26, 26],
              iconAnchor: [13, 13],
            });
            L.marker([req.lat, req.lon ?? req.lng], { icon: origIcon })
              .bindPopup(
                `<b>Blocked Origin Point</b><br/>Terrain Constraint: ${reason}<br/><i>Shifted ${dist}m outward</i>`
              )
              .addTo(snapVectorGroupRef.current);

            // Dotted shift displacement vector
            L.polyline(
              [
                [req.lat, req.lon ?? req.lng],
                [res.lat, res.lon ?? res.lng],
              ],
              {
                color: "#f59e0b",
                weight: 3,
                dashArray: "4, 6",
              }
            )
              .bindTooltip(`Auto-shifted ${dist}m away from constraint`, { sticky: true })
              .addTo(snapVectorGroupRef.current);
          }
        }

        // Goal Point Snapped?
        if (endpointRes.goalSnapped && endpointRes.resolvedGoal && endpointRes.requestedGoal) {
          snapTriggered = true;
          const req = endpointRes.requestedGoal;
          const res = endpointRes.resolvedGoal;
          const dist = Math.round(endpointRes.goalSnapDistanceMeters || 0);
          const reason = humanizeSnapReason(endpointRes.goalSnapReason);

          snapDetails.end = {
            requested: { lat: req.lat, lng: req.lon ?? req.lng },
            resolved: { lat: res.lat, lng: res.lon ?? res.lng },
            distanceMeters: dist,
            reason: reason,
          };

          // Update local end state to the resolved feasible coordinate!
          resolvedEndLat = res.lat;
          resolvedEndLng = res.lon ?? res.lng;
          setEnd({ lat: resolvedEndLat, lng: resolvedEndLng });

          if (snapVectorGroupRef.current) {
            const origIcon = L.divIcon({
              className: "marg-marker orig",
              html: "<span>✕</span>",
              iconSize: [26, 26],
              iconAnchor: [13, 13],
            });
            L.marker([req.lat, req.lon ?? req.lng], { icon: origIcon })
              .bindPopup(
                `<b>Blocked Destination Point</b><br/>Terrain Constraint: ${reason}<br/><i>Shifted ${dist}m outward</i>`
              )
              .addTo(snapVectorGroupRef.current);

            L.polyline(
              [
                [req.lat, req.lon ?? req.lng],
                [res.lat, res.lon ?? res.lng],
              ],
              {
                color: "#f59e0b",
                weight: 3,
                dashArray: "4, 6",
              }
            )
              .bindTooltip(`Auto-shifted ${dist}m away from constraint`, { sticky: true })
              .addTo(snapVectorGroupRef.current);
          }
        }
      }

      if (snapTriggered) {
        // Synchronize lastCoordsKeyRef to the backend-snapped coordinates BEFORE React runs
        // the currentCoordsKey reset effect so it never wipes the route we just generated!
        lastCoordsKeyRef.current = `${Number(resolvedStartLat).toFixed(4)},${Number(resolvedStartLng).toFixed(4)}->${Number(resolvedEndLat).toFixed(4)},${Number(resolvedEndLng).toFixed(4)}`;
        setSnapNotice(snapDetails);
      }

      // ---------------------------------------------------------
      // 2. RENDER MULTI-FEATURE GEOJSON LAYERS & CACHE RESULT
      // ---------------------------------------------------------
      const targetPreset = tradeOffPreset || "BALANCED";
      applyRouteResult(data, targetPreset);

      setStatus("✅ MARG composite highway alignment & network plan computed successfully.");
    } catch (err) {
      console.error("Routing execution error:", err);
      setRoutingError({
        title: "Routing Computation Failed",
        message: err.message || "An unexpected error occurred during corridor generation.",
      });
      setStatus("❌ Routing failed.");
    } finally {
      setIsLoading(false);
    }
  }

  // -----------------------------------------------------------
  // Helper: Extract & Apply Route Metadata & GIS Layers
  // -----------------------------------------------------------
  function applyRouteResult(data, presetKeyOverride) {
    const targetKey = presetKeyOverride || tradeOffPreset || "BALANCED";
    const meta = data.metadata || {};
    const diag = meta.routeDiagnostics || {};
    const net = meta.transportNetworkAndCapacitySummary || meta.networkAndCapacitySummary || {};
    const termConn = meta.terminalRoadConnections || {};
    const auditList = Array.isArray(meta.layerAudit) ? meta.layerAudit : [];
    const layerProfile = Array.isArray(meta.routeLayerProfile) ? meta.routeLayerProfile : [];
    const spurProfiles = Array.isArray(meta.spurLayerProfiles) ? meta.spurLayerProfiles : [];
    const spurSummary = meta.spurEngineeringSummary || null;
    const feats = Array.isArray(data.features) ? data.features : [];

    setCurrentFeatures(feats);
    setLayerAudit(auditList);
    setRouteLayerProfile(layerProfile);
    setSpurLayerProfiles(spurProfiles);
    setRouteDiagnostics(diag);
    setTerminalRoadConnections(termConn);
    setSpurEngineeringSummary(spurSummary);
    renderGeoJsonFeatures(feats, targetKey, activeLayerId, pinnedLayerIds, layerProfile, spurProfiles, true);

    const mainlineFeat = data.features.find(
      (f) => f.properties?.featureRole === "GREENFIELD_MAINLINE" || f.properties?.featureRole === "MAINLINE"
    );
    const mainProps = mainlineFeat?.properties || {};

    const routeKm = Number(diag.routeLengthKm ?? meta.routeLengthKm ?? mainProps.routeLengthKm ?? 0);
    const baseKm = Number(diag.directDistanceBaselineKm ?? meta.directDistanceBaselineKm ?? mainProps.distanceBaselineKm ?? 0);
    const devPct = Number(diag.alignmentDeviationPercent ?? meta.alignmentDeviationPercent ?? mainProps.deviationPercent ?? 0);

    const spurFeatures = (data.features || []).filter(
      (f) => f.properties?.featureRole === "DEDICATED_FEEDER_SPUR"
    );
    const hubList = spurFeatures.map((f) => ({
      name: f.properties?.servedHubName || "Regional Hub",
      spurKm: Number(f.properties?.spurLengthKm || 0).toFixed(1),
      population: f.properties?.catchmentPopulationServed || 0,
      isUserOverride: Boolean(f.properties?.immutableUserOverride),
      traffic: Math.round(f.properties?.inducedPeakTrafficPcuHr || 0),
      tieInRoadRef: f.properties?.tieInRoadRef || "Connecting Road",
      decisionReason: f.properties?.decisionReason || "",
      sixLaneRecommended: Boolean(f.properties?.sixLaneExpansionRecommended),
    }));
    setConnectedHubs(hubList);

    const trafficAnalysis = net.trafficCapacityAnalysis || {};
    setTrafficCapacity(trafficAnalysis);

    const pipelineMeta = meta.hubEvaluationPipeline || {};
    const allDecisions = pipelineMeta.hubDecisions || [];
    setHubDecisions(allDecisions);

    const newRouteInfo = {
      routeLengthKm: routeKm.toFixed(2),
      baselineLengthKm: baseKm.toFixed(2),
      deviationPercent: devPct.toFixed(2),
      tierMode: data.corridorTierMode || meta.corridorTierMode || "TIER_1_DYNAMIC_PAN_INDIA",
      crs: meta.coordinateReferenceSystem || "EPSG:32643",
      utmZone: meta.utmZone || 43,
    };
    setRouteInfo(newRouteInfo);

    const newNetSummary = {
      interchanges: net.plannedInterchangesCount ?? 0,
      feederSpurs: net.dedicatedFeederSpursCount ?? 0,
      feederSpurKm: Number(net.totalDedicatedFeederSpurLengthKm ?? 0).toFixed(1),
      terminalAccessKm: Number(
        net.totalTerminalAccessRoadLengthKm ?? termConn.totalTerminalAccessRoadLengthKm ?? 0
      ).toFixed(2),
      startRoadRef:
        termConn.startRoadConnection?.existingRoadRef ||
        termConn.startRoadConnection?.existingRoadName ||
        "Existing Road",
      goalRoadRef:
        termConn.goalRoadConnection?.existingRoadRef ||
        termConn.goalRoadConnection?.existingRoadName ||
        "Existing Road",
      riverBridges: net.riverBridgesCount ?? 0,
      riverBridgeSpanM: net.totalEstimatedRiverBridgeSpanMeters ?? 0,
      drainageStructures: net.drainageStructuresCount ?? (data.features ? data.features.filter(f => f.properties?.featureRole === "DRAINAGE_CROSSING_STRUCTURE").length : 0),
      wildlifePassages: net.wildlifePassagesCount ?? 0,
      wildlifeSpanM: net.totalRecommendedWildlifePassageSpanMeters ?? 0,
      railwayRobs: net.railwayRobCount ?? 0,
      roadFlyovers: net.highwayFlyoverUnderpassCount ?? 0,
      rowHectares: net.rightOfWayAcquisitionFootprint?.totalDirectRowAcquisitionHectares ?? "—",
      rowWidthM: net.rightOfWayAcquisitionFootprint?.mainlineRowWidthMeters ?? 90,
      buildingsInRow: net.buildingsInDirectRowCount ?? 0,
      buildingsByType: net.buildingsInDirectRowByType ?? null,
      settlementConflicts: net.settlementConflictZonesCount ?? 0,
      displacedHouseholds: net.estimatedDirectlyDisplacedHouseholds ?? 0,
      displacedPop: net.estimatedDirectlyDisplacedPopulation ?? 0,
    };
    setNetworkSummary(newNetSummary);

    const profile = meta.tradeOffProfile || null;
    setTradeOffProfile(profile);

    const clearanceReport = data.statutoryClearanceReport || meta.statutoryClearanceReport || profile?.statutoryClearanceReport || null;
    setStatutoryClearanceReport(clearanceReport);

    // Save full snapshot in Archetype Cache
    const snapshot = {
      data,
      features: feats,
      layerAudit: auditList,
      routeLayerProfile: layerProfile,
      spurLayerProfiles: spurProfiles,
      routeDiagnostics: diag,
      terminalRoadConnections: termConn,
      spurEngineeringSummary: spurSummary,
      routeInfo: newRouteInfo,
      networkSummary: newNetSummary,
      tradeOffProfile: profile,
      statutoryClearanceReport: clearanceReport,
      connectedHubs: hubList,
      trafficCapacity: trafficAnalysis,
      hubDecisions: allDecisions,
      presetKey: targetKey,
    };

    setCachedArchetypes((prev) => ({
      ...prev,
      [targetKey]: snapshot,
    }));

    // Synchronize alternativeRoutes list
    const pDef = PRESET_DEFINITIONS[targetKey];
    const altColor = pDef?.color || "#1B4332";
    const altLabel = pDef?.label || (targetKey + " Route");

    setAlternativeRoutes((prev) => {
      const existing = prev.filter((a) => a.preset !== targetKey);
      return [
        ...existing,
        {
          id: "alt-" + targetKey,
          label: altLabel,
          preset: targetKey,
          routeLengthKm: newRouteInfo.routeLengthKm,
          deviationPercent: newRouteInfo.deviationPercent,
          forestHa: profile?.environmentalSummary?.forestHectaresDirectRow ?? 0,
          displacedPop: profile?.socialSummary?.estimatedDirectlyDisplacedPopulation ?? 0,
          compositeIndex: profile?.compositeAlignmentIndex ?? 85,
          color: altColor,
        },
      ];
    });

    setActiveAlternativeId("alt-" + targetKey);

    return { routeKm, profile, meta, net, devPct, snapshot };
  }

  // -----------------------------------------------------------
  // Re-Align Route Based on Request-Scoped User Preferences
  // -----------------------------------------------------------
  async function realignAlignment(presetKeyOverride) {
    if (!start || !end) return;
    setIsRealigning(true);
    setStatus("✨ Re-evaluating spatial costs & synthesizing alternative corridor based on preferences...");

    const p = presetKeyOverride ? PRESET_DEFINITIONS[presetKeyOverride] : null;
    const targetEnv = p ? p.env : envWeight;
    const targetSocial = p ? p.social : socialWeight;
    const targetDirect = p ? p.direct : directnessWeight;
    const targetPreset = p ? p.id : tradeOffPreset;

    if (p) {
      setTradeOffPreset(p.id);
      setEnvWeight(p.env);
      setSocialWeight(p.social);
      setDirectnessWeight(p.direct);
    }

    try {
      const params = new URLSearchParams({
        startLat: start.lat,
        startLon: start.lng,
        endLat: end.lat,
        endLon: end.lng,
        facilityMode: facilityMode,
        autoConnectHubs: autoConnectHubs.toString(),
        envWeight: targetEnv.toString(),
        socialWeight: targetSocial.toString(),
        directnessWeight: targetDirect.toString(),
        tradeOffPreset: targetPreset,
      });

      if (deepIngestion) params.append("deep", "true");
      if (autoConnectHubs && maxAutoHubs != null) params.append("maxAutoHubs", maxAutoHubs.toString());
      if (targetHubs.length > 0) params.append("targetHubs", targetHubs.join(","));
      if (maxVcRatio != null) params.append("maxVcRatio", maxVcRatio.toString());
      if (maxDeviationPercent != null) params.append("maxDeviationPercent", maxDeviationPercent.toString());
      if (selectedPresetId && selectedPresetId !== "custom" && selectedPresetId !== "interstate_pan_india") {
        params.append("region", selectedPresetId);
        params.append("regionId", selectedPresetId);
      }

      const res = await fetch(`/api/routes/composite?${params.toString()}`);
      if (!res.ok) throw new Error(await res.text());
      const data = await res.json();

      const resSummary = applyRouteResult(data, targetPreset);
      const altLabel = PRESET_DEFINITIONS[targetPreset]?.shortLabel || targetPreset;
      setStatus(`✨ Re-aligned & cached corridor under ${altLabel} preferences (${resSummary.routeKm.toFixed(1)} km).`);
    } catch (err) {
      console.error("Re-alignment error:", err);
      setRoutingError({
        title: "Re-Alignment Failed",
        message: err.message || "Failed to re-align route.",
      });
      setStatus("❌ Re-alignment failed.");
    } finally {
      setIsRealigning(false);
    }
  }

  // -----------------------------------------------------------
  // Compute Allowed GeoJSON Roles for Overview vs. Active Lens + Pinned Layers
  // -----------------------------------------------------------
  function computeAllowedFeatureRoles(lensId, pinnedSet) {
    const isLensMode = Boolean(lensId) || (pinnedSet && pinnedSet.size > 0);
    if (!isLensMode) {
      return OVERVIEW_FEATURE_ROLES;
    }
    const allowed = new Set([
      "MAINLINE",
      "GREENFIELD_MAINLINE",
      "DISTANCE_BASELINE",
      "BASELINE",
      "DEDICATED_FEEDER_SPUR",
      "FEEDER_SPUR_HIGHWAY",
      "TERMINAL_ACCESS_ROAD",
      "ROAD_TIE_IN",
    ]);
    const activeIds = new Set(pinnedSet ? Array.from(pinnedSet) : []);
    if (lensId) activeIds.add(lensId);
    activeIds.forEach((id) => {
      const cfg = LAYER_BY_ID[id];
      if (cfg && Array.isArray(cfg.vectorFeatureRoles)) {
        cfg.vectorFeatureRoles.forEach((r) => allowed.add(r));
      }
    });
    return allowed;
  }

  // -----------------------------------------------------------
  // Paint Color-Coded Route Segments for Raster Layers (Mainline + All Feeder/Terminal Spurs)
  // -----------------------------------------------------------
  function renderColorizedRouteRibbon(profile, spurProfiles, lensId) {
    if (!colorizedRouteGroupRef.current) return;
    colorizedRouteGroupRef.current.clearLayers();

    const cfg = lensId ? LAYER_BY_ID[lensId] : null;
    if (!cfg || !cfg.profileField) {
      return;
    }

    const group = colorizedRouteGroupRef.current;
    const field = cfg.profileField;

    const paintSamples = (samples, segmentTitle, isSpur) => {
      if (!Array.isArray(samples) || samples.length < 2) return;
      let runCoords = [];
      let runVal = null;
      let runStartCh = 0;
      let runMaxSlope = 0;

      const flushRun = (endCh) => {
        if (runCoords.length < 2) return;
        const color = getSegmentColor(cfg, runVal);
        const casing = L.polyline(runCoords, {
          color: "#0f172a",
          weight: isSpur ? 7.2 : 9,
          opacity: 0.72,
          lineCap: "round",
          lineJoin: "round",
        });
        const ribbon = L.polyline(runCoords, {
          color,
          weight: isSpur ? 4.2 : 5.5,
          opacity: 0.98,
          lineCap: "round",
          lineJoin: "round",
        });
        const slopeExtra =
          cfg.id === "L3_SLOPE"
            ? `<br/>Peak Slope in Segment: <b>${runMaxSlope.toFixed(1)}°</b>`
            : "";
        ribbon.bindTooltip(
          `<div style="font-size:11px;line-height:1.35;">` +
            `<b>${cfg.icon} L${cfg.number}: ${cfg.shortName}</b><br/>` +
            `<span style="color:#94a3b8;">${segmentTitle}</span><br/>` +
            `Class: <b style="color:${color};">${formatClassLabel(runVal)}</b>${slopeExtra}<br/>` +
            `Chainage: km ${runStartCh.toFixed(1)} – ${endCh.toFixed(1)}` +
            `</div>`,
          { sticky: true }
        );
        group.addLayer(casing);
        group.addLayer(ribbon);
      };

      for (let i = 0; i < samples.length; i++) {
        const pt = samples[i];
        if (typeof pt.lat !== "number" || typeof pt.lon !== "number") continue;
        const val = pt[field] ?? "UNKNOWN";
        const slopeVal = typeof pt.slopeDeg === "number" ? pt.slopeDeg : 0;

        if (runCoords.length === 0) {
          runCoords = [[pt.lat, pt.lon]];
          runVal = val;
          runStartCh = Number(pt.chKm || 0);
          runMaxSlope = slopeVal;
        } else if (val === runVal) {
          runCoords.push([pt.lat, pt.lon]);
          if (slopeVal > runMaxSlope) runMaxSlope = slopeVal;
        } else {
          runCoords.push([pt.lat, pt.lon]);
          flushRun(Number(pt.chKm || runStartCh));
          runCoords = [[pt.lat, pt.lon]];
          runVal = val;
          runStartCh = Number(pt.chKm || 0);
          runMaxSlope = slopeVal;
        }
      }
      if (runCoords.length >= 2) {
        const lastCh = Number(samples[samples.length - 1]?.chKm || runStartCh);
        flushRun(lastCh);
      }
    };

    // Paint Mainline
    paintSamples(profile, "Mainline Alignment", false);

    // Paint Every Feeder Spur & Terminal Spur with identical 13-layer color coding
    if (Array.isArray(spurProfiles)) {
      spurProfiles.forEach((sp) => {
        const label = sp?.servedHubName ? `Feeder Spur — ${sp.servedHubName}` : "Feeder Spur";
        paintSamples(sp?.samples, label, true);
      });
    }
  }

  // -----------------------------------------------------------
  // Render FeatureCollection with Custom GIS Symbology (Overview vs. 13-Layer Lens)
  // -----------------------------------------------------------
  function renderGeoJsonFeatures(
    features,
    activePresetKey,
    lensIdOverride,
    pinnedOverride,
    profileOverride,
    spurProfilesOverride,
    shouldFitBounds = false
  ) {
    if (!mapRef.current || !routeLayersGroupRef.current) return;
    routeLayersGroupRef.current.clearLayers();

    const effectiveLens = lensIdOverride !== undefined ? lensIdOverride : activeLayerId;
    const effectivePinned = pinnedOverride !== undefined ? pinnedOverride : pinnedLayerIds;
    const effectiveProfile = profileOverride !== undefined ? profileOverride : routeLayerProfile;
    const effectiveSpurProfiles = spurProfilesOverride !== undefined ? spurProfilesOverride : spurLayerProfiles;

    const allowedRoles = computeAllowedFeatureRoles(effectiveLens, effectivePinned);
    const activeLensCfg = effectiveLens ? LAYER_BY_ID[effectiveLens] : null;
    const hasColorizedRibbon = Boolean(
      activeLensCfg?.profileField && Array.isArray(effectiveProfile) && effectiveProfile.length >= 2
    );
    const hasColorizedSpurs = Boolean(
      activeLensCfg?.profileField && Array.isArray(effectiveSpurProfiles) && effectiveSpurProfiles.length > 0
    );

    const group = routeLayersGroupRef.current;
    let boundsToFit = null;
    const currentPreset = activePresetKey || tradeOffPreset || "BALANCED";
    const mainlineColor = PRESET_DEFINITIONS[currentPreset]?.color || "#10b981";

    const spurTagHtml = (props) =>
      props?.segmentRole === "FEEDER_SPUR"
        ? `<div style="margin-bottom:4px;font-size:10px;font-weight:700;color:#a855f7;">🟣 Feeder Spur — ${props.servedHubName || props.parentSpurId || "Hub"}</div>`
        : "";

    (features || []).forEach((feature) => {
      const role = feature.properties?.featureRole || "";
      const geomType = feature.geometry?.type;
      const props = feature.properties || {};

      // Gate visibility strictly by Overview Mode vs. Active Lens + Pinned Layers
      const isAllowed =
        allowedRoles.has(role) ||
        (role.includes("BASELINE") && allowedRoles.has("BASELINE")) ||
        (role.includes("ROW_CORRIDOR") && allowedRoles.has("MAINLINE_ROW_CORRIDOR"));
      if (!isAllowed) return;

      // 1. Straight-Line Baseline
      if (role.includes("BASELINE")) {
        const layer = L.geoJSON(feature, {
          style: {
            color: "#94a3b8",
            weight: 2.8,
            dashArray: "6, 8",
            opacity: 0.78,
          },
        }).bindPopup("<b>Direct Distance Baseline</b><br/>Reference shortest path used for 13-Layer Before vs. After verification.");
        group.addLayer(layer);
      }

      // 2. Right-of-Way Footprint Polygons (L11 & Overview Road Structure)
      else if (role.includes("ROW_CORRIDOR") && (geomType === "Polygon" || geomType === "MultiPolygon")) {
        const layer = L.geoJSON(feature, {
          style: {
            color: effectiveLens === "L11_BUILDINGS_ROW" ? "#ec4899" : "#10b981",
            weight: effectiveLens === "L11_BUILDINGS_ROW" ? 2 : 1,
            fillColor: effectiveLens === "L11_BUILDINGS_ROW" ? "#ec4899" : "#10b981",
            fillOpacity: effectiveLens === "L11_BUILDINGS_ROW" ? 0.22 : 0.12,
            dashArray: "4, 4",
          },
        }).bindPopup(
          `<b>2D RoW Acquisition Footprint</b><br/>Standard: ${props.ircStandard || "IRC:SP:99-2023"}<br/>Width: ${props.designRowWidthMeters || 60} m`
        );
        group.addLayer(layer);
      }

      // 3. Feeder Spurs (Tier-2 & Tier-1)
      else if (role === "DEDICATED_FEEDER_SPUR" || role === "FEEDER_SPUR_HIGHWAY") {
        const reasonHtml = props.decisionReason
          ? `<br/><div style="margin-top:4px; font-size:11px; color:${props.sixLaneExpansionRecommended ? '#d97706' : '#6b7280'}; line-height:1.3"><b>Design Rule:</b> ${props.decisionReason}</div>`
          : "";
        const layer = L.geoJSON(feature, {
          style: {
            color: props.sixLaneExpansionRecommended ? "#f59e0b" : "#a855f7",
            weight: hasColorizedSpurs ? 2.5 : 5,
            opacity: hasColorizedSpurs ? 0.35 : 0.95,
          },
        }).bindPopup(
          `<b>Dedicated Feeder Spur</b><br/>Connecting to: <b>${props.servedHubName || props.connectingHub || "Regional Hub"}</b><br/>Length: ${props.spurLengthKm || props.lengthKm || "—"} km<br/>Tie-In: ${props.tieInRoadRef || "Existing Road"}${reasonHtml}`
        );
        group.addLayer(layer);
      }

      // 4. Greenfield Mainline
      else if (role === "GREENFIELD_MAINLINE" || role === "MAINLINE") {
        const layer = L.geoJSON(feature, {
          style: {
            color: mainlineColor,
            weight: hasColorizedRibbon ? 3 : 6,
            opacity: hasColorizedRibbon ? 0.35 : 0.95,
          },
        }).bindPopup(
          `<b>${PRESET_DEFINITIONS[currentPreset]?.label || "13-Layer Greenfield Mainline"}</b><br/>Length: ${props.routeLengthKm || "—"} km<br/>Deviation: ${props.deviationPercent || "—"}%`
        );
        group.addLayer(layer);
        boundsToFit = layer.getBounds();
      }

      // 5. Interchanges & Terminal Interchanges (IRC:SP:99 / IRC:SP:84)
      else if (
        (role === "INTERCHANGE" || role === "TERMINAL_INTERCHANGE" || role === "INTERCHANGE_JUNCTION") &&
        geomType === "Point"
      ) {
        const coords = feature.geometry.coordinates;
        const isTerminal = role === "TERMINAL_INTERCHANGE";
        const icon = L.divIcon({
          className: `struct-marker interchange ${isTerminal ? "terminal" : ""}`,
          html: isTerminal
            ? `<span style="display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:50%;background:#d97706;border:2.5px solid #fff;color:#fff;font-size:13px;font-weight:800;box-shadow:0 2px 8px rgba(0,0,0,0.5);">✦</span>`
            : "<span>✦</span>",
          iconSize: isTerminal ? [28, 28] : [26, 26],
          iconAnchor: isTerminal ? [14, 14] : [13, 13],
        });
        const title = isTerminal
          ? `${props.terminalRole === "ORIGIN_TERMINAL_INTERCHANGE" ? "Origin" : "Destination"} Terminal Interchange (IRC:SP:99)`
          : "Planned Grade-Separated Interchange";
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">${title}</div><div class="popup-meta">` +
            `<b>Type:</b> ${formatClassLabel(props.interchangeType || props.type || "Trumpet")}<br/>` +
            (props.existingRoadRef ? `<b>Arterial Tie-In:</b> ${props.existingRoadRef} (${formatClassLabel(props.roadCategory || "")})<br/>` : "") +
            (props.tollPlazaConfiguration ? `<b>Toll Plaza:</b> ${formatClassLabel(props.tollPlazaConfiguration)}<br/>` : "") +
            (props.connectingHub ? `<b>Served Hub:</b> ${props.connectingHub}<br/>` : "") +
            (props.chainageKm != null ? `<b>Chainage:</b> ${props.chainageKm} km` : "") +
            `</div>`
        );
        group.addLayer(marker);
      }

      // 5b. Regional Service Hubs (Tier-1)
      else if (role === "REGIONAL_SERVICE_HUB" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const marker = L.circleMarker([coords[1], coords[0]], {
          radius: 7,
          color: "#ffffff",
          weight: 2,
          fillColor: "#a855f7",
          fillOpacity: 0.9,
        }).bindPopup(
          `<div class="popup-title">🏙️ ${props.name || "Regional Hub"}</div><div class="popup-meta">Feeder Spur: ${props.spurDistanceKm ?? "—"} km • Tie-In: ${props.tieInRoadRef || "SH/MDR"}</div>`
        );
        group.addLayer(marker);
      }

      // 6. River Bridges (L7 & Overview)
      else if (role === "RIVER_BRIDGE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker bridge",
          html: "<span>🌉</span>",
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">Perennial River Bridge</div>${spurTagHtml(props)}<div class="popup-meta">River: ${props.riverName || "Major River"}<br/>Skew Span: ${props.estimatedSkewSpanMeters || props.effectiveSkewSpanMeters || "—"} m<br/>Crossing Angle: ${props.crossingAngleDegrees || "—"}°</div>`
        );
        group.addLayer(marker);
      }

      // 7. Wildlife Passages (L8)
      else if (role === "WILDLIFE_PASSAGE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker wildlife",
          html: "<span>🐾</span>",
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">Wildlife Migration Viaduct</div>${spurTagHtml(props)}<div class="popup-meta">Corridor: ${props.corridorName || "Wildlife Corridor"}<br/>Passage Span: ${props.recommendedPassageSpanMeters || "—"} m<br/>Mitigation: ${formatClassLabel(props.mitigationStructure || "Elevated Viaduct Structure")}</div>`
        );
        group.addLayer(marker);
      }

      // 7b. Statutory Wildlife Sanctuary & National Park Boundaries (L4 — Hidden in Overview!)
      else if (role === "WILDLIFE_SANCTUARY_BOUNDARY" && (geomType === "Polygon" || geomType === "MultiPolygon")) {
        const layer = L.geoJSON(feature, {
          style: {
            color: "#dc2626",
            weight: 2.5,
            dashArray: "6, 4",
            fillColor: "#ef4444",
            fillOpacity: 0.22,
          },
        }).bindPopup(
          `<div class="popup-title" style="color:#b91c1c; font-weight:700;">🛡️ ${props.name || "Wildlife Sanctuary"}</div>` +
            `<div class="popup-meta" style="font-size:11px; line-height:1.45;">` +
            `<b>Legal Statute:</b> ${props.legalStatute || "Wildlife (Protection) Act, 1972"}<br/>` +
            `<b>Location:</b> ${props.state || "—"} (${props.district || "—"})<br/>` +
            `<b>Declared Area:</b> ${props.areaKm2 ? props.areaKm2 + " km²" : "—"}<br/>` +
            `<b>Target Species:</b> ${props.keySpecies || "Protected Wildlife"}<br/>` +
            `<div style="margin-top:6px; padding:4px 8px; background:#fef2f2; border:1px solid #f87171; color:#991b1b; border-radius:4px; font-weight:700; font-size:10.5px;">` +
            `🛡️ Zero-Tolerance Hard Exclusion Boundary (WPA 1972 §29/35)` +
            `</div></div>`
        );
        group.addLayer(layer);
      }

      // 7c. 10 km Eco-Sensitive Zone Boundaries (L8)
      else if (role === "ECO_SENSITIVE_ZONE_BOUNDARY" && (geomType === "Polygon" || geomType === "MultiPolygon")) {
        const layer = L.geoJSON(feature, {
          style: {
            color: "#f59e0b",
            weight: 2,
            dashArray: "8, 6",
            fillColor: "#fbbf24",
            fillOpacity: 0.14,
          },
        }).bindPopup(
          `<div class="popup-title" style="color:#b45309;">🌿 ${props.name || "Eco-Sensitive Zone (ESZ)"}</div>` +
            `<div class="popup-meta">Buffer: ${props.eszBufferKm || 10} km MoEFCC Eco-Sensitive Zone<br/>Requires WII 2016 wildlife mitigation compliance.</div>`
        );
        group.addLayer(layer);
      }

      // 8. Grade-Separated Crossings (L6 & Overview)
      else if (role === "GRADE_SEPARATED_CROSSING" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const isRail = props.crossedCategory === "RAILWAY";
        const icon = L.divIcon({
          className: `struct-marker ${isRail ? "rail-rob" : "road-overpass"}`,
          html: `<span style="display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:${isRail ? "#1e293b" : "#2563eb"};border:2px solid #fff;font-size:12px;box-shadow:0 2px 6px rgba(0,0,0,0.45);">${isRail ? "🚊" : "🌉"}</span>`,
          iconSize: [24, 24],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">${isRail ? "Railway Over Bridge (ROB)" : "Elevated Road Bridge / Overpass"}</div>` +
            spurTagHtml(props) +
            `<div class="popup-meta">` +
            `<b>Crossed Infrastructure:</b> ${props.crossedCorridorRef || props.crossedCorridorName || "Existing Road"}<br/>` +
            `<b>Category:</b> ${(props.crossedCategory || "HIGHWAY").replace(/_/g, " ")}<br/>` +
            `<b>Structure Type:</b> ${(props.structureType || "OVERPASS").replace(/_/g, " ")}<br/>` +
            `<b>Chainage:</b> ${props.chainageKm ?? "—"} km<br/>` +
            `<b>Crossing Angle:</b> ${props.crossingAngleDegrees ?? 90}°` +
            `</div>`
        );
        group.addLayer(marker);
      }

      // 9. Corridor River Channels (L7 & L2)
      else if (role === "CORRIDOR_RIVER_CHANNEL") {
        const layer = L.geoJSON(feature, {
          style: {
            color: "#0284c7",
            weight: 3.2,
            opacity: 0.85,
          },
        }).bindPopup(
          `<b>Backend Waterway Model</b><br/>River: ${props.riverName || "River"}<br/>Class: ${props.riverClass || "PERENNIAL"}<br/>Design Width: ${props.designChannelWidthMeters || "—"} m`
        );
        group.addLayer(layer);
      }

      // 10. Drainage & Canal Crossing Structures (L13)
      else if (role === "DRAINAGE_CROSSING_STRUCTURE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker drainage",
          html: "<span>🌊</span>",
          iconSize: [24, 24],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">Canal Aqueduct / Cross-Drainage Structure</div>${spurTagHtml(props)}<div class="popup-meta">Target: ${props.targetFeatureName || "Drainage Channel"}<br/>Type: ${formatClassLabel(props.structureType || "Box Culvert")}<br/>Span: ${props.recommendedClearSpanOrWidthMeters || "—"} m<br/>Discharge: ${props.designDischargeM3PerSec || "—"} m³/s</div>`
        );
        group.addLayer(marker);
      }

      // 10b. Wetland Hydrologic Mitigation Zones (L13)
      else if (role === "WETLAND_HYDROLOGIC_MITIGATION_ZONE") {
        if (geomType === "Point") {
          const coords = feature.geometry.coordinates;
          const marker = L.circleMarker([coords[1], coords[0]], {
            radius: 10,
            color: "#0d9488",
            weight: 2.5,
            fillColor: "#14b8a6",
            fillOpacity: 0.35,
          }).bindPopup(
            `<div class="popup-title">💧 Wetland Hydrologic Mitigation Zone</div><div class="popup-meta">Wetland: ${props.wetlandName || props.name || "Notified Wetland"}<br/>Mitigation: ${formatClassLabel(props.recommendedEngineeringMitigation || "Hydrologic Culvert Equalization")}</div>`
          );
          group.addLayer(marker);
        } else {
          const layer = L.geoJSON(feature, {
            style: { color: "#0d9488", weight: 2, fillColor: "#14b8a6", fillOpacity: 0.28 },
          }).bindPopup(
            `<div class="popup-title">💧 Wetland Mitigation Zone</div><div class="popup-meta">${props.wetlandName || props.name || "Wetland Buffer"}</div>`
          );
          group.addLayer(layer);
        }
      }

      // 11. Terminal Access Road Connectors (L6 & Overview)
      else if (role === "TERMINAL_ACCESS_ROAD" && geomType === "LineString") {
        const isOrigin = props.connectorRole === "ORIGIN_ROAD_CONNECTOR";
        const layer = L.geoJSON(feature, {
          style: {
            color: "#06b6d4",
            weight: 4.5,
            opacity: 0.95,
            dashArray: "8, 6",
          },
        }).bindPopup(
          `<div class="popup-title">${isOrigin ? "Origin" : "Destination"} Terminal Feeder Spur / Access Connector</div><div class="popup-meta">Tie-In Road: ${props.existingRoadRef || "Existing Road"} (${props.existingRoadName || "—"})<br/>Category: ${formatClassLabel(props.roadCategory || "Arterial")}<br/>Connector Length: ${props.connectorLengthKm ?? "—"} km</div>`
        );
        group.addLayer(layer);
      }

      // 12. Existing Road Tie-In Junction Markers (L6 & Overview)
      else if (role === "ROAD_TIE_IN" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const isOrigin = props.tieInRole === "ORIGIN_EXISTING_ROAD_TIE_IN";
        const marker = L.circleMarker([coords[1], coords[0]], {
          radius: 7,
          color: "#ffffff",
          weight: 2.5,
          fillColor: "#06b6d4",
          fillOpacity: 0.95,
        }).bindPopup(
          `<div class="popup-title">${isOrigin ? "Origin" : "Destination"} Existing Road Tie-In</div><div class="popup-meta">Existing Road: ${props.existingRoadRef || "NH/SH"}<br/>Category: ${formatClassLabel(props.roadCategory || "Arterial Highway")}<br/>Corridor: ${props.tieInJunctionName || "Existing Road Network"}</div>`
        );
        group.addLayer(marker);
      }

      // 13. Building Demolition & Settlement RoW Conflict Zones (L5 & L11)
      else if (role === "SETTLEMENT_BUILDING_CONFLICT_ZONE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const directBlds = props.buildingsInDirectRowCount ?? 0;
        const byType = props.buildingsInDirectRowByType || {};
        const breakdownStr =
          Object.entries(byType)
            .filter(([, cnt]) => cnt > 0)
            .map(([t, cnt]) => `${t}: ${cnt}`)
            .join(", ") || "Fringe parcel touch";
        const hasDemolition = directBlds > 0;
        const icon = L.divIcon({
          className: "struct-marker conflict",
          html: `<span style="display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:${hasDemolition ? "#dc2626" : "#ec4899"};border:2px solid #fff;font-size:12px;box-shadow:0 2px 6px rgba(0,0,0,0.45);">${hasDemolition ? "🏚️" : "🏘️"}</span>`,
          iconSize: [24, 24],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">${hasDemolition ? "Building Conflict Zone (RFCTLARR 2013)" : "Settlement RoW Buffer (0 Demolitions)"}</div>` +
            `<div class="popup-meta">` +
            `<b>Location:</b> ${props.settlementName || "Corridor Settlement"}<br/>` +
            `<b>Structures in Direct RoW:</b> ${directBlds}<br/>` +
            `<b>Breakdown:</b> ${breakdownStr}<br/>` +
            `<b>Footprint Area:</b> ${props.affectedDirectRowFootprintSqMeters ?? 0} m²<br/>` +
            `<b>Mitigation:</b> ${(props.recommendedEngineeringMitigation || "RFCTLARR 2013 R&R").replace(/_/g, " ")}` +
            `</div>`
        );
        group.addLayer(marker);
      }

      // 14. Geotechnical & Landslide Mitigation Zones (L9)
      else if (role === "GEOTECH_MITIGATION_ZONE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker geotech",
          html: `<span style="display:inline-flex;align-items:center;justify-content:center;width:25px;height:25px;border-radius:50%;background:#d97706;border:2px solid #fff;font-size:12px;box-shadow:0 2px 6px rgba(0,0,0,0.5);">🪨</span>`,
          iconSize: [25, 25],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">🪨 Geotechnical Stabilization Zone</div>${spurTagHtml(props)}<div class="popup-meta">` +
            `<b>Hazard Feature:</b> ${props.name || props.featureName || "Geological Lineament / Scarp"}<br/>` +
            `<b>Treatment:</b> ${formatClassLabel(props.mitigationStructure || props.recommendedEngineeringMitigation || "Anchored Retaining Wall")}<br/>` +
            `<b>Stabilization Length:</b> ${props.recommendedStabilizationLengthMeters ?? "—"} m<br/>` +
            `<b>Crossing Angle:</b> ${props.crossingAngleDegrees ?? "—"}° • ch. ${props.chainageKm ?? "—"} km</div>`
        );
        group.addLayer(marker);
      }

      // 15. High-Voltage EHV Utility Crossing Protection (L10)
      else if (role === "UTILITY_CROSSING_PROTECTION" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker utility",
          html: `<span style="display:inline-flex;align-items:center;justify-content:center;width:25px;height:25px;border-radius:50%;background:#ca8a04;border:2px solid #fff;font-size:13px;box-shadow:0 2px 6px rgba(0,0,0,0.5);">⚡</span>`,
          iconSize: [25, 25],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">⚡ High-Voltage Utility Crossing Protection</div>${spurTagHtml(props)}<div class="popup-meta">` +
            `<b>Transmission Line:</b> ${props.lineName || props.name || "EHV Line"}<br/>` +
            `<b>Voltage Tier:</b> ${formatClassLabel(props.utilityTier || props.tier || "EHV")}<br/>` +
            `<b>Protection Spec:</b> ${formatClassLabel(props.protectionType || props.recommendedProtection || "Guard Cradle & Tower Extension")}<br/>` +
            `<b>Protective Span:</b> ${props.recommendedProtectiveSpanMeters ?? "—"} m • Angle: ${props.crossingAngleDegrees ?? "—"}°</div>`
        );
        group.addLayer(marker);
      }

      // 16. Demographic Exposure Mitigation Zones (L12)
      else if (role === "DEMOGRAPHIC_EXPOSURE_MITIGATION_ZONE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const halo = L.circle([coords[1], coords[0]], {
          radius: 300,
          color: "#8b5cf6",
          weight: 2,
          fillColor: "#8b5cf6",
          fillOpacity: 0.22,
        }).bindPopup(
          `<div class="popup-title">👥 Demographic Exposure Mitigation Zone</div><div class="popup-meta">` +
            `<b>Tract / Settlement:</b> ${props.settlementName || props.tractName || props.name || "Census Tract"}<br/>` +
            `<b>250 m Buffer Pop:</b> ${props.populationWithin250m ?? props.totalPopulation ?? "—"} residents<br/>` +
            `<b>Vulnerability Tier:</b> ${formatClassLabel(props.vulnerabilityTier || "Monitored")}<br/>` +
            `<b>Mitigation:</b> ${formatClassLabel(props.recommendedEngineeringMitigation || "Acoustic Barrier & Pedestrian Underpass")}</div>`
        );
        group.addLayer(halo);
      }
    });

    // Paint Colorized Route Ribbon on top (Mainline + Feeder/Terminal Spurs) when a raster lens is active
    renderColorizedRouteRibbon(effectiveProfile, effectiveSpurProfiles, effectiveLens);

    if (shouldFitBounds && boundsToFit && boundsToFit.isValid()) {
      mapRef.current.fitBounds(boundsToFit, { padding: [40, 40], maxZoom: 14 });
    }
  }

  // Re-render GIS overlays & colorized route ribbon instantaneously when Lens/Pins change
  useEffect(() => {
    if (!mapRef.current) return;
    if (currentFeatures.length === 0) {
      routeLayersGroupRef.current?.clearLayers();
      colorizedRouteGroupRef.current?.clearLayers();
      return;
    }
    renderGeoJsonFeatures(currentFeatures, tradeOffPreset, activeLayerId, pinnedLayerIds, routeLayerProfile, spurLayerProfiles, false);
  }, [currentFeatures, routeLayerProfile, spurLayerProfiles, activeLayerId, pinnedLayerIds, tradeOffPreset]);

  // -----------------------------------------------------------
  // 13-Layer Lens, Pin, Basemap Reset & Fly-To Handlers
  // -----------------------------------------------------------
  function handleSelectLayer(layerId) {
    focusHighlightGroupRef.current?.clearLayers();
    setActiveLayerId(layerId);
    if (!lockBasemap) {
      if (layerId && LAYER_BY_ID[layerId]) {
        setBaseMapType(LAYER_BY_ID[layerId].preferredBasemap || "osm");
      } else {
        setBaseMapType("osm");
      }
    }
  }

  function handleTogglePinLayer(layerId) {
    setPinnedLayerIds((prev) => {
      const next = new Set(prev);
      if (next.has(layerId)) next.delete(layerId);
      else next.add(layerId);
      return next;
    });
  }

  function handleClearToOverview() {
    focusHighlightGroupRef.current?.clearLayers();
    setActiveLayerId(null);
    setPinnedLayerIds(new Set());
    if (!lockBasemap) {
      setBaseMapType("osm");
    }
  }

  // Keeps active layer lens & verification card open over basic OSM map;
  // leaves auto-switch enabled so the NEXT layer clicked switches basemaps again.
  function handleResetBasicMap() {
    setBaseMapType("osm");
  }

  function handleFocusFeature(lat, lng, label) {
    if (!mapRef.current || typeof lat !== "number" || typeof lng !== "number") return;
    mapRef.current.flyTo([lat, lng], 14, { duration: 0.9 });
    if (focusHighlightGroupRef.current) {
      focusHighlightGroupRef.current.clearLayers();
      const pulse = L.circleMarker([lat, lng], {
        radius: 16,
        color: "#ffffff",
        weight: 3,
        fillColor: "#38bdf8",
        fillOpacity: 0.25,
      })
        .bindPopup(`<b>📍 ${label || "Inspected Feature"}</b><br/>${lat.toFixed(5)}, ${lng.toFixed(5)}`)
        .addTo(focusHighlightGroupRef.current);
      pulse.openPopup();
    }
  }

  function handleInspectLayerFromDossier(layerId) {
    setRightDrawerOpen(true);
    handleSelectLayer(layerId);
    if (mapStudioRef.current) {
      mapStudioRef.current.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }

  function handleLocateFromDossier(lat, lng, label) {
    if (mapStudioRef.current) {
      mapStudioRef.current.scrollIntoView({ behavior: "smooth", block: "start" });
    }
    setTimeout(() => {
      handleFocusFeature(lat, lng, label);
    }, 320);
  }

  function scrollToDossier() {
    if (dossierRef.current) {
      dossierRef.current.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }

  const activeLayerCfg = activeLayerId ? LAYER_BY_ID[activeLayerId] : null;

  return (
    <div className="app-shell">
      {/* =======================================================
          SLIM LUXURY PORCELAIN HEADER
          ======================================================= */}
      <header className="navbar">
        <div className="nav-brand">
          <div className="brand-badge">MARG</div>
          <div className="nav-title">
            <h1>MARG Corridor Studio</h1>
            <span>Multi-Objective AI for Responsible Geospatial Highway Planning · MoRTH / IRC</span>
          </div>
        </div>

        <div className="nav-actions">
          <select
            className="corridor-quick-select"
            value={selectedPresetId}
            onChange={(e) => {
              const p = PRESET_CORRIDORS.find((x) => x.id === e.target.value);
              if (p) applyPreset(p);
            }}
            aria-label="Quick Select Benchmark Corridor"
          >
            <option value="custom">Custom Pan-India Coordinates</option>
            {PRESET_CORRIDORS.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>

          <div className="badge-pill badge-pill-subtle">
            <span>900+ Districts Indexed</span>
          </div>

          <div className="badge-pill badge-pill-ready">
            <span className="status-dot" />
            <span>2-Tier Engine Ready</span>
          </div>

          {routeInfo && (
            <button
              type="button"
              className="nav-dossier-btn"
              onClick={scrollToDossier}
              title="Jump down to the 6-Chapter Engineering Dossier"
            >
              <span>Engineering Dossier</span>
              <span aria-hidden="true">↓</span>
            </button>
          )}
        </div>
      </header>

      {/* =======================================================
          ABOVE-THE-FOLD HERO MAP STUDIO (LEFT DRAWER · MAP · RIGHT DRAWER)
          ======================================================= */}
      <section ref={mapStudioRef} className="map-studio-stage" aria-label="Interactive Corridor Map Studio">
        {/* LEFT COLLAPSIBLE DRAWER: CORRIDOR PLANNER (INPUTS ONLY) */}
        <aside className={`studio-drawer left-drawer ${leftDrawerOpen ? "open" : "collapsed"}`}>
          {!leftDrawerOpen ? (
            <button
              type="button"
              className="drawer-collapsed-rail"
              onClick={() => setLeftDrawerOpen(true)}
              title="Open Corridor Planner Drawer"
            >
              <span className="rail-chevron">»</span>
              <span className="rail-label">Corridor Planner</span>
            </button>
          ) : (
            <div className="drawer-inner">
              <div className="drawer-topbar">
                <div>
                  <span className="drawer-eyebrow">STUDIO INPUTS</span>
                  <h2 className="drawer-title">Corridor Planner</h2>
                </div>
                <button
                  type="button"
                  className="drawer-toggle-btn"
                  onClick={() => setLeftDrawerOpen(false)}
                  title="Collapse Corridor Planner to widen the map"
                  aria-label="Collapse Corridor Planner"
                >
                  «
                </button>
              </div>

              <div className="drawer-scroll-body">
                {/* Group 1: Benchmark Corridors & Facility Mode */}
                <div className="planner-group">
                  <div className="planner-group-header">
                    <span className="planner-group-title">Benchmark Corridors</span>
                  </div>

                  <div className="presets-grid">
                    {PRESET_CORRIDORS.map((preset) => (
                      <button
                        key={preset.id}
                        type="button"
                        className={`preset-chip ${selectedPresetId === preset.id ? "active" : ""}`}
                        onClick={() => applyPreset(preset)}
                      >
                        <strong>{preset.name.split(":")[0]}</strong>
                        <span>{preset.tag}</span>
                      </button>
                    ))}
                  </div>

                  <div className="facility-selector">
                    {["EXPRESSWAY", "NATIONAL_HIGHWAY", "STATE_HIGHWAY"].map((mode) => (
                      <button
                        key={mode}
                        type="button"
                        className={`facility-btn ${facilityMode === mode ? "active" : ""}`}
                        onClick={() => setFacilityMode(mode)}
                      >
                        {mode === "EXPRESSWAY" ? "Expressway" : mode === "NATIONAL_HIGHWAY" ? "NH Trunk" : "State Hwy"}
                      </button>
                    ))}
                  </div>

                  <div className="toggle-row">
                    <div className="toggle-label">
                      <strong>Deep 13-Layer On-Demand Ingestion</strong>
                      <span>Synthesize rasters for arbitrary coordinates (deep=true)</span>
                    </div>
                    <label className="switch">
                      <input
                        type="checkbox"
                        checked={deepIngestion}
                        onChange={(e) => setDeepIngestion(e.target.checked)}
                      />
                      <span className="slider" />
                    </label>
                  </div>
                </div>

                {/* Group 2: Corridor Endpoints */}
                <div className="planner-group">
                  <div className="planner-group-header">
                    <span className="planner-group-title">Corridor Endpoints</span>
                  </div>

                  {/* Origin (Start) */}
                  <div className="coord-box">
                    <div className="coord-header">
                      <span className="coord-title start">Origin Point (A)</span>
                      <button
                        type="button"
                        className={`pick-map-btn ${mapSelectionMode === "start" ? "active" : ""}`}
                        onClick={() => {
                          const next = mapSelectionMode === "start" ? null : "start";
                          mapSelectionModeRef.current = next;
                          setMapSelectionMode(next);
                          if (next) setStatus("Click anywhere on the map to set Origin.");
                        }}
                      >
                        {mapSelectionMode === "start" ? "Cancel Pick" : "Pick on Map"}
                      </button>
                    </div>

                    <div className="district-autocomplete-container">
                      <input
                        type="text"
                        className="district-quick-input"
                        placeholder="Quick find origin district (e.g. Pune, Lucknow)..."
                        value={startDistrictQuery}
                        onChange={(e) => setStartDistrictQuery(e.target.value)}
                      />
                      {startDistrictSuggestions.length > 0 && (
                        <div className="district-autocomplete-dropdown">
                          {startDistrictSuggestions.map((d) => {
                            const isSub = d.adminLevel === "TEHSIL" || d.adminLevel === "TOWN" || d.adminLevel === "SUBDIVISION";
                            const badgeClass = (d.adminLevel || "district").toLowerCase();
                            return (
                              <div
                                key={d.id}
                                className="district-autocomplete-item"
                                onClick={() => {
                                  setStart({ lat: d.lat, lng: d.lon });
                                  setSelectedPresetId("custom");
                                  setStartDistrictQuery("");
                                  setStartDistrictSuggestions([]);
                                  if (mapRef.current) {
                                    mapRef.current.flyTo([d.lat, d.lon], 9, { duration: 1.0 });
                                  }
                                }}
                              >
                                <div className="district-item-main">
                                  <strong>{d.name}</strong>
                                  <span className={`admin-level-badge ${badgeClass}`}>
                                    {d.adminLevel || "DISTRICT"}
                                  </span>
                                  <span className="district-state-badge">{d.stateCode}</span>
                                </div>
                                <div className="district-item-sub">
                                  {isSub
                                    ? `${d.adminLevel || "Tehsil"} in ${d.parentDistrict} District · ${d.state}`
                                    : `District HQ: ${d.headquarters || d.name} · ${d.state}`}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>

                    <div className="coord-grid">
                      <div className="input-group">
                        <label>Latitude</label>
                        <input
                          type="number"
                          step="0.0001"
                          value={start.lat}
                          onChange={(e) => {
                            setStart((s) => ({ ...s, lat: Number(e.target.value) }));
                            setSelectedPresetId("custom");
                          }}
                        />
                      </div>
                      <div className="input-group">
                        <label>Longitude</label>
                        <input
                          type="number"
                          step="0.0001"
                          value={start.lng}
                          onChange={(e) => {
                            setStart((s) => ({ ...s, lng: Number(e.target.value) }));
                            setSelectedPresetId("custom");
                          }}
                        />
                      </div>
                    </div>
                  </div>

                  {/* Destination (End) */}
                  <div className="coord-box">
                    <div className="coord-header">
                      <span className="coord-title end">Destination Point (B)</span>
                      <button
                        type="button"
                        className={`pick-map-btn ${mapSelectionMode === "end" ? "active" : ""}`}
                        onClick={() => {
                          const next = mapSelectionMode === "end" ? null : "end";
                          mapSelectionModeRef.current = next;
                          setMapSelectionMode(next);
                          if (next) setStatus("Click anywhere on the map to set Destination.");
                        }}
                      >
                        {mapSelectionMode === "end" ? "Cancel Pick" : "Pick on Map"}
                      </button>
                    </div>

                    <div className="district-autocomplete-container">
                      <input
                        type="text"
                        className="district-quick-input"
                        placeholder="Quick find destination district (e.g. Satara, Varanasi)..."
                        value={endDistrictQuery}
                        onChange={(e) => setEndDistrictQuery(e.target.value)}
                      />
                      {endDistrictSuggestions.length > 0 && (
                        <div className="district-autocomplete-dropdown">
                          {endDistrictSuggestions.map((d) => {
                            const isSub = d.adminLevel === "TEHSIL" || d.adminLevel === "TOWN" || d.adminLevel === "SUBDIVISION";
                            const badgeClass = (d.adminLevel || "district").toLowerCase();
                            return (
                              <div
                                key={d.id}
                                className="district-autocomplete-item"
                                onClick={() => {
                                  setEnd({ lat: d.lat, lng: d.lon });
                                  setSelectedPresetId("custom");
                                  setEndDistrictQuery("");
                                  setEndDistrictSuggestions([]);
                                  if (mapRef.current) {
                                    mapRef.current.flyTo([d.lat, d.lon], 9, { duration: 1.0 });
                                  }
                                }}
                              >
                                <div className="district-item-main">
                                  <strong>{d.name}</strong>
                                  <span className={`admin-level-badge ${badgeClass}`}>
                                    {d.adminLevel || "DISTRICT"}
                                  </span>
                                  <span className="district-state-badge">{d.stateCode}</span>
                                </div>
                                <div className="district-item-sub">
                                  {isSub
                                    ? `${d.adminLevel || "Tehsil"} in ${d.parentDistrict} District · ${d.state}`
                                    : `District HQ: ${d.headquarters || d.name} · ${d.state}`}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>

                    <div className="coord-grid">
                      <div className="input-group">
                        <label>Latitude</label>
                        <input
                          type="number"
                          step="0.0001"
                          value={end.lat}
                          onChange={(e) => {
                            setEnd((s) => ({ ...s, lat: Number(e.target.value) }));
                            setSelectedPresetId("custom");
                          }}
                        />
                      </div>
                      <div className="input-group">
                        <label>Longitude</label>
                        <input
                          type="number"
                          step="0.0001"
                          value={end.lng}
                          onChange={(e) => {
                            setEnd((s) => ({ ...s, lng: Number(e.target.value) }));
                            setSelectedPresetId("custom");
                          }}
                        />
                      </div>
                    </div>
                  </div>
                </div>

                {/* Group 3: Intermediate Cities & Feeder Hubs */}
                <div className="planner-group">
                  <div className="planner-group-header">
                    <span className="planner-group-title">Cities & Design Envelope</span>
                  </div>

                  <div className="toggle-row">
                    <div className="toggle-label">
                      <strong>Auto-Connect Intermediate Hubs</strong>
                      <span>Autonomously link prominent cities along corridor</span>
                    </div>
                    <label className="switch">
                      <input
                        type="checkbox"
                        checked={autoConnectHubs}
                        onChange={(e) => setAutoConnectHubs(e.target.checked)}
                      />
                      <span className="slider" />
                    </label>
                  </div>

                  {autoConnectHubs ? (
                    <div className="auto-hubs-count-card">
                      <div className="auto-hubs-count-head">
                        <span>Cities to Connect</span>
                        <span className="auto-hubs-pill">
                          {maxAutoHubs} {maxAutoHubs === 1 ? "City" : "Cities"}
                        </span>
                      </div>
                      <div className="auto-hubs-btn-row">
                        {[1, 2, 3, 4, 5].map((num) => (
                          <button
                            key={num}
                            type="button"
                            className={`num-hub-btn ${maxAutoHubs === num ? "active" : ""}`}
                            onClick={() => setMaxAutoHubs(num)}
                            title={`Connect up to ${num} cities autonomously`}
                          >
                            {num}
                          </button>
                        ))}
                      </div>
                      <div className="auto-hubs-caption">
                        Selected via Highway Value Index (HVI) & IRC:SP:84 spacing.
                      </div>
                    </div>
                  ) : (
                    <div className="planner-note-box">
                      Auto-connect is off. Alignment connects only user-specified hubs below.
                    </div>
                  )}

                  {/* User-Specified Cities & Tehsils */}
                  <div className="hubs-box">
                    <div className="hubs-header">
                      <div>
                        <span className="hubs-title">User-Specified Cities & Tehsils</span>
                        <div className="hubs-sub">Explicitly prioritized towns or mandis</div>
                      </div>
                      <button
                        type="button"
                        className={`pick-map-btn ${mapSelectionMode === "hub" ? "active" : ""}`}
                        onClick={() => {
                          const next = mapSelectionMode === "hub" ? null : "hub";
                          mapSelectionModeRef.current = next;
                          setMapSelectionMode(next);
                          if (next) setStatus("Click anywhere on the map to add an intermediate city/hub.");
                        }}
                      >
                        {mapSelectionMode === "hub" ? "Cancel Pick" : "Pick on Map"}
                      </button>
                    </div>

                    <div className="district-autocomplete-container">
                      <div className="hubs-input-row">
                        <input
                          type="text"
                          placeholder="Search district or town (e.g. Satara, Wai)..."
                          value={customHubInput}
                          onChange={(e) => setCustomHubInput(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === "Enter" && customHubInput.trim()) {
                              e.preventDefault();
                              const val = customHubInput.trim();
                              if (!targetHubs.includes(val)) {
                                setTargetHubs([...targetHubs, val]);
                              }
                              setCustomHubInput("");
                              setDistrictSuggestions([]);
                            }
                          }}
                        />
                        <button
                          type="button"
                          className="hubs-add-btn"
                          onClick={() => {
                            if (customHubInput.trim()) {
                              const val = customHubInput.trim();
                              if (!targetHubs.includes(val)) {
                                setTargetHubs([...targetHubs, val]);
                              }
                              setCustomHubInput("");
                              setDistrictSuggestions([]);
                            }
                          }}
                        >
                          + Add
                        </button>
                      </div>

                      {districtSuggestions.length > 0 && (
                        <div className="district-autocomplete-dropdown">
                          {districtSuggestions.map((d) => {
                            const isSub =
                              d.adminLevel === "TEHSIL" ||
                              d.adminLevel === "TOWN" ||
                              d.adminLevel === "SUBDIVISION" ||
                              d.adminLevel === "TALUK" ||
                              d.adminLevel === "MANDAL";
                            const badgeClass = (d.adminLevel || "district").toLowerCase();
                            const val = isSub
                              ? `${d.name} (${d.parentDistrict} - ${d.stateCode})`
                              : `${d.name} (${d.stateCode})`;
                            return (
                              <div
                                key={d.id}
                                className="district-autocomplete-item"
                                onClick={() => {
                                  if (!targetHubs.includes(val)) {
                                    setTargetHubs([...targetHubs, val]);
                                  }
                                  setCustomHubInput("");
                                  setDistrictSuggestions([]);
                                  if (mapRef.current) {
                                    mapRef.current.flyTo([d.lat, d.lon], 9, { duration: 1.0 });
                                  }
                                }}
                              >
                                <div className="district-item-main">
                                  <strong>{d.name}</strong>
                                  <span className={`admin-level-badge ${badgeClass}`}>
                                    {d.adminLevel || "DISTRICT"}
                                  </span>
                                  <span className="district-state-badge">{d.stateCode}</span>
                                </div>
                                <div className="district-item-sub">
                                  {isSub
                                    ? `${d.adminLevel || "Tehsil"} in ${d.parentDistrict} District · ${d.state}`
                                    : `District HQ: ${d.headquarters || d.name} · ${d.state}`}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>

                    {targetHubs.length > 0 && (
                      <div className="hubs-selected-list">
                        {targetHubs.map((hub) => (
                          <span key={hub} className="hub-selected-tag">
                            <span>{hub}</span>
                            <button
                              type="button"
                              className="hub-remove-btn"
                              onClick={() => setTargetHubs(targetHubs.filter((h) => h !== hub))}
                              title="Remove"
                            >
                              ×
                            </button>
                          </span>
                        ))}
                      </div>
                    )}

                    {(() => {
                      const activePreset = PRESET_CORRIDORS.find((p) => p.id === selectedPresetId);
                      const suggestions = activePreset?.suggestedHubs || [];
                      if (suggestions.length === 0) return null;
                      return (
                        <div className="hubs-suggestions-wrap">
                          <div className="hubs-suggestions-label">Suggested Regional Hubs</div>
                          <div className="hubs-suggestions">
                            {suggestions.map((hubName) => {
                              const isSelected = targetHubs.includes(hubName);
                              return (
                                <button
                                  key={hubName}
                                  type="button"
                                  className={`hub-suggest-chip ${isSelected ? "active" : ""}`}
                                  onClick={() => {
                                    if (isSelected) {
                                      setTargetHubs(targetHubs.filter((h) => h !== hubName));
                                    } else {
                                      setTargetHubs([...targetHubs, hubName]);
                                    }
                                  }}
                                >
                                  {isSelected ? `✓ ${hubName}` : `+ ${hubName}`}
                                </button>
                              );
                            })}
                          </div>
                        </div>
                      );
                    })()}
                  </div>

                  {/* Level of Service (Target V/C Ratio) */}
                  <div className="los-block">
                    <div className="planner-field-label">Traffic Level of Service (Target V/C Ratio)</div>
                    <div className="los-selector">
                      <button
                        type="button"
                        className={`los-btn ${maxVcRatio === 0.70 ? "active" : ""}`}
                        onClick={() => setMaxVcRatio(0.70)}
                      >
                        <strong>Free-Flow (≤ 0.70)</strong>
                        <span>High speed buffer · LOS B</span>
                      </button>
                      <button
                        type="button"
                        className={`los-btn ${maxVcRatio === 0.85 ? "active" : ""}`}
                        onClick={() => setMaxVcRatio(0.85)}
                      >
                        <strong>Economical (≤ 0.85)</strong>
                        <span>Max hub intake · LOS C</span>
                      </button>
                    </div>
                  </div>

                  {/* Max Alignment Deviation Slider */}
                  <div className="deviation-box">
                    <div className="deviation-header">
                      <span className="deviation-title">Max Alignment Deviation</span>
                      <span className="deviation-badge">{maxDeviationPercent}%</span>
                    </div>
                    <input
                      type="range"
                      className="deviation-slider"
                      min="10"
                      max="30"
                      step="1"
                      value={maxDeviationPercent}
                      onChange={(e) => setMaxDeviationPercent(Number(e.target.value))}
                    />
                    <div className="deviation-scale">
                      <span>10% Direct</span>
                      <span>20% Standard</span>
                      <span>30% Wide Search</span>
                    </div>
                  </div>
                </div>

                {/* Primary CTA + Status / Error / Snap */}
                <div className="planner-cta-group">
                  <button
                    type="button"
                    className="generate-btn"
                    onClick={generateRoute}
                    disabled={isLoading}
                  >
                    {isLoading ? (
                      <>
                        <div className="spinner" />
                        <span>Computing Alignment...</span>
                      </>
                    ) : (
                      <span>Generate MARG Alignment</span>
                    )}
                  </button>

                  <div className="planner-status-line">{status}</div>

                  {routingError && (
                    <div className="error-card">
                      <div className="error-card-title">{routingError.title}</div>
                      <div className="error-card-message">{routingError.message}</div>
                    </div>
                  )}

                  {snapNotice && (
                    <div className="snap-alert-card">
                      <div className="snap-alert-header">
                        <span>Endpoint Auto-Adjustment Applied</span>
                      </div>

                      {snapNotice.start && (
                        <div className="snap-item">
                          <div className="snap-item-title">
                            <span className="snap-origin-label">Origin Auto-Shifted</span>
                            <span className="snap-badge">+{snapNotice.start.distanceMeters} m</span>
                          </div>
                          <div className="snap-reason">
                            <strong>Constraint:</strong> {snapNotice.start.reason}
                          </div>
                          <div className="snap-coords-compare">
                            <div>Requested: <del>{snapNotice.start.requested.lat}, {snapNotice.start.requested.lng}</del></div>
                            <div>Feasible: <ins>{snapNotice.start.resolved.lat}, {snapNotice.start.resolved.lng}</ins></div>
                          </div>
                        </div>
                      )}

                      {snapNotice.end && (
                        <div className="snap-item">
                          <div className="snap-item-title">
                            <span className="snap-dest-label">Destination Auto-Shifted</span>
                            <span className="snap-badge">+{snapNotice.end.distanceMeters} m</span>
                          </div>
                          <div className="snap-reason">
                            <strong>Constraint:</strong> {snapNotice.end.reason}
                          </div>
                          <div className="snap-coords-compare">
                            <div>Requested: <del>{snapNotice.end.requested.lat}, {snapNotice.end.requested.lng}</del></div>
                            <div>Feasible: <ins>{snapNotice.end.resolved.lat}, {snapNotice.end.resolved.lng}</ins></div>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}
        </aside>

        {/* CENTER FRAMED HERO MAP VIEWPORT */}
        <main className="map-viewport">
          <div ref={mapContainerRef} className="leaflet-canvas-host" />

          {/* Floating Cream-Glass Basemap Controls & Active 13-Layer Lens Legend HUD */}
          <MapLensHud
            baseMapType={baseMapType}
            lockBasemap={lockBasemap}
            onSelectBasemap={(id) => setBaseMapType(id)}
            onResetBasicMap={handleResetBasicMap}
            onToggleLockBasemap={() => setLockBasemap((v) => !v)}
            onZoomIndia={() => {
              if (mapRef.current) {
                mapRef.current.flyTo([22.5, 78.5], 5, { duration: 1 });
              }
            }}
            activeLayerId={activeLayerId}
            pinnedLayerIds={pinnedLayerIds}
            onUnpinLayer={handleTogglePinLayer}
            onExitLens={handleClearToOverview}
          />
        </main>

        {/* RIGHT COLLAPSIBLE DRAWER: DEDICATED 13-LAYER MAP LENSES */}
        <aside className={`studio-drawer right-drawer ${rightDrawerOpen ? "open" : "collapsed"}`}>
          {!rightDrawerOpen ? (
            <button
              type="button"
              className="drawer-collapsed-rail"
              onClick={() => setRightDrawerOpen(true)}
              title="Open 13-Layer Map Lenses Drawer"
            >
              <span className="rail-chevron">«</span>
              {activeLayerCfg && (
                <span
                  className="rail-active-dot"
                  style={{ background: activeLayerCfg.accentColor }}
                  title={`Active: L${activeLayerCfg.number} ${activeLayerCfg.shortName}`}
                />
              )}
              <span className="rail-label">13-Layer Lenses</span>
            </button>
          ) : (
            <div className="drawer-inner">
              <div className="drawer-topbar">
                <div>
                  <span className="drawer-eyebrow">GEOSPATIAL VERIFICATION</span>
                  <h2 className="drawer-title">13-Layer Map Lenses</h2>
                </div>
                <button
                  type="button"
                  className="drawer-toggle-btn"
                  onClick={() => setRightDrawerOpen(false)}
                  title="Collapse 13-Layer Map Lenses to widen the map"
                  aria-label="Collapse 13-Layer Map Lenses"
                >
                  »
                </button>
              </div>

              <div className="drawer-scroll-body drawer-scroll-layers">
                <LayerAuditPanel
                  layerAudit={layerAudit}
                  routeLayerProfile={routeLayerProfile}
                  allFeatures={currentFeatures}
                  activeLayerId={activeLayerId}
                  pinnedLayerIds={pinnedLayerIds}
                  onSelectLayer={handleSelectLayer}
                  onTogglePinLayer={handleTogglePinLayer}
                  onClearToOverview={handleClearToOverview}
                  onFocusFeature={handleFocusFeature}
                  showMultiArchetypes={showMultiArchetypes}
                  onToggleMultiArchetypes={() => setShowMultiArchetypes((v) => !v)}
                  cachedArchetypesCount={Object.keys(cachedArchetypes).length}
                />
              </div>
            </div>
          )}
        </aside>
      </section>

      {/* =======================================================
          SERENE EDITORIAL TRANSITION STRIP
          ======================================================= */}
      {routeInfo && (
        <div className="dossier-scroll-cue">
          <div className="dossier-scroll-cue-inner">
            <div className="cue-meta">
              <span className="cue-eyebrow">ALIGNMENT SYNTHESIZED</span>
              <span className="cue-summary">
                <strong>{routeInfo.routeLengthKm} km</strong> mainline ·{" "}
                <strong>{networkSummary?.feederSpurs ?? 0}</strong> feeder spurs ·{" "}
                <strong>{layerAudit.length || 13}</strong> spatial layers audited
              </span>
            </div>
            <button type="button" className="cue-scroll-btn" onClick={scrollToDossier}>
              <span>Scroll to Engineering Dossier (6 Chapters)</span>
              <span aria-hidden="true">↓</span>
            </button>
          </div>
        </div>
      )}

      {/* =======================================================
          BELOW-THE-FOLD SPACIOUS 6-CHAPTER ENGINEERING DOSSIER
          ======================================================= */}
      <div ref={dossierRef}>
        <EngineeringDossier
          routeInfo={routeInfo}
          networkSummary={networkSummary}
          tradeOffProfile={tradeOffProfile}
          statutoryClearanceReport={statutoryClearanceReport}
          layerAudit={layerAudit}
          routeLayerProfile={routeLayerProfile}
          routeDiagnostics={routeDiagnostics}
          terminalRoadConnections={terminalRoadConnections}
          spurEngineeringSummary={spurEngineeringSummary}
          currentFeatures={currentFeatures}
          connectedHubs={connectedHubs}
          trafficCapacity={trafficCapacity}
          hubDecisions={hubDecisions}
          snapNotice={snapNotice}
          facilityMode={facilityMode}
          presetDefinitions={PRESET_DEFINITIONS}
          tradeOffPreset={tradeOffPreset}
          envWeight={envWeight}
          socialWeight={socialWeight}
          directnessWeight={directnessWeight}
          isRealigning={isRealigning}
          alternativeRoutes={alternativeRoutes}
          activeAlternativeId={activeAlternativeId}
          comparativeDeltasVsBalanced={comparativeDeltasVsBalanced}
          onSelectPreset={handleSelectPreset}
          onSliderChange={handleSliderChange}
          onRealign={() => realignAlignment()}
          onSwitchArchetype={switchToArchetype}
          onInspectLayerOnMap={handleInspectLayerFromDossier}
          onLocateOnMap={handleLocateFromDossier}
        />
      </div>
    </div>
  );
}
