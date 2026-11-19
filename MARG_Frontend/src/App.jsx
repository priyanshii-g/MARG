import { useEffect, useMemo, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";

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
    start: { lat: 18.365, lng: 73.855 },
    end: { lat: 17.695, lng: 74.015 },
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
  if (r.includes("SACRED") || r.includes("HERITAGE") || r.includes("RELIGIOUS")) {
    return "Sacred Temple / Heritage Precinct Exclusion Zone (Zero-tolerance buffer)";
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
  const baseTileLayerRef = useRef(null);

  // Markers and Vector Layers
  const startMarkerRef = useRef(null);
  const endMarkerRef = useRef(null);
  const snapVectorGroupRef = useRef(null);
  const routeLayersGroupRef = useRef(null);
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

  // Layer Visibility Filters
  const [visibleLayers, setVisibleLayers] = useState({
    mainline: true,
    baseline: true,
    spurs: true,
    interchanges: true,
    bridges: true,
    drainage: true,
    rivers: false,
    wildlife: true,
    sanctuaries: true,
    crossings: true,
    row: true,
    multiArchetypes: true,
  });

  // Coords Key Tracker: Automatically clear cached routes if endpoints change
  const currentCoordsKey = start && end ? `${start.lat.toFixed(4)},${start.lng.toFixed(4)}->${end.lat.toFixed(4)},${end.lng.toFixed(4)}` : null;

  useEffect(() => {
    if (currentCoordsKey && lastCoordsKeyRef.current && currentCoordsKey !== lastCoordsKeyRef.current) {
      setCachedArchetypes({});
      setAlternativeRoutes([]);
      setActiveAlternativeId(null);
      setStatutoryClearanceReport(null);
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
  // Basemap Switcher (OpenStreetMap vs Esri Satellite - No Keys Required)
  // -----------------------------------------------------------
  useEffect(() => {
    if (!mapRef.current) return;
    if (baseTileLayerRef.current) {
      mapRef.current.removeLayer(baseTileLayerRef.current);
    }
    let newLayer;
    if (baseMapType === "satellite") {
      newLayer = L.tileLayer(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        {
          attribution: "Tiles &copy; Esri &mdash; Source: Esri, i-cubed, USDA, USGS, AEX, GeoEye, Getmapping, Aerogrid, IGN, IGP, UPR-EGP, and the GIS User Community",
          maxZoom: 19,
        }
      );
    } else {
      newLayer = L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19,
      });
    }
    newLayer.addTo(mapRef.current);
    baseTileLayerRef.current = newLayer;
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
      color: "var(--primary)",
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
      renderGeoJsonFeatures(cached.features, targetKey);
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

    if (!visibleLayers.multiArchetypes) return;

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
            <span style="font-size:9.5px; color:#38bdf8; font-weight:700;">⚡ Click alignment to activate</span>
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
  }, [cachedArchetypes, tradeOffPreset, visibleLayers.multiArchetypes]);

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
          setStart({ lat: res.lat, lng: res.lon ?? res.lng });

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
          setEnd({ lat: res.lat, lng: res.lon ?? res.lng });

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
        setSnapNotice(snapDetails);
      }

      // ---------------------------------------------------------
      // 2. RENDER MULTI-FEATURE GEOJSON LAYERS & CACHE RESULT
      // ---------------------------------------------------------
      const targetPreset = tradeOffPreset || "BALANCED";
      renderGeoJsonFeatures(data.features, targetPreset);

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
    renderGeoJsonFeatures(data.features, targetKey);

    const meta = data.metadata || {};
    const diag = meta.routeDiagnostics || {};
    const net = meta.transportNetworkAndCapacitySummary || meta.networkAndCapacitySummary || {};
    const termConn = meta.terminalRoadConnections || {};

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
      features: data.features,
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
    const altColor = pDef?.color || "var(--primary)";
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
  // Render FeatureCollection with Custom GIS Symbology
  // -----------------------------------------------------------
  function renderGeoJsonFeatures(features, activePresetKey) {
    if (!mapRef.current || !routeLayersGroupRef.current) return;
    routeLayersGroupRef.current.clearLayers();

    const group = routeLayersGroupRef.current;
    let boundsToFit = null;
    const currentPreset = activePresetKey || tradeOffPreset || "BALANCED";
    const mainlineColor = PRESET_DEFINITIONS[currentPreset]?.color || "#10b981";

    features.forEach((feature) => {
      const role = feature.properties?.featureRole || "";
      const geomType = feature.geometry?.type;

      // 1. Straight-Line Baseline
      if (role.includes("BASELINE") && visibleLayers.baseline) {
        const layer = L.geoJSON(feature, {
          style: {
            color: "#94a3b8",
            weight: 3,
            dashArray: "6, 8",
            opacity: 0.8,
          },
        }).bindPopup("<b>Straight-Line Baseline</b><br/>Euclidean metric distance comparison.");
        group.addLayer(layer);
      }

      // 2. Right-of-Way Footprint Polygons
      else if (role.includes("ROW_CORRIDOR") && visibleLayers.row && geomType === "Polygon") {
        const layer = L.geoJSON(feature, {
          style: {
            color: "#10b981",
            weight: 1,
            fillColor: "#10b981",
            fillOpacity: 0.12,
            dashArray: "4, 4",
          },
        }).bindPopup(
          `<b>RoW Acquisition Footprint</b><br/>Standard: ${feature.properties.ircStandard || "IRC:SP:99-2023"}<br/>Width: ${feature.properties.designRowWidthMeters || 90}m`
        );
        group.addLayer(layer);
      }

      // 3. Feeder Spurs
      else if (role === "DEDICATED_FEEDER_SPUR" && visibleLayers.spurs) {
        const p = feature.properties || {};
        const reasonHtml = p.decisionReason
          ? `<br/><div style="margin-top:4px; font-size:11px; color:${p.sixLaneExpansionRecommended ? '#d97706' : '#6b7280'}; line-height:1.3"><b>Design Rule:</b> ${p.decisionReason}</div>`
          : "";
        const layer = L.geoJSON(feature, {
          style: {
            color: p.sixLaneExpansionRecommended ? "#f59e0b" : "#a855f7",
            weight: 5,
            opacity: 0.95,
          },
        }).bindPopup(
          `<b>Dedicated Feeder Spur</b><br/>Connecting to: <b>${p.servedHubName || "Regional Hub"}</b><br/>Length: ${p.spurLengthKm || "—"} km<br/>Tie-In: ${p.tieInRoadRef || "Existing Road"}${reasonHtml}`
        );
        group.addLayer(layer);
      }

      // 4. Greenfield Mainline
      else if ((role === "GREENFIELD_MAINLINE" || role === "MAINLINE") && visibleLayers.mainline) {
        const layer = L.geoJSON(feature, {
          style: {
            color: mainlineColor,
            weight: 6,
            opacity: 0.95,
          },
        }).bindPopup(
          `<b>${PRESET_DEFINITIONS[currentPreset]?.label || "13-Layer Greenfield Mainline"}</b><br/>Length: ${feature.properties.routeLengthKm || "—"} km<br/>Deviation: ${feature.properties.deviationPercent || "—"}%`
        );
        group.addLayer(layer);
        boundsToFit = layer.getBounds();
      }

      // 5. Interchanges (Filter out any spurious terminal origin/destination duplicate markers)
      else if (
        role.includes("INTERCHANGE") &&
        visibleLayers.interchanges &&
        geomType === "Point" &&
        !feature.properties?.interchangeId?.includes("ORIGIN") &&
        !feature.properties?.interchangeId?.includes("DESTINATION")
      ) {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker interchange",
          html: "<span>✦</span>",
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">Planned Interchange</div><div class="popup-meta">ID: ${feature.properties.interchangeId}<br/>Type: ${feature.properties.interchangeType || "Trumpet"}<br/>Chainage: ${feature.properties.chainageKm} km</div>`
        );
        group.addLayer(marker);
      }

      // 6. River Bridges
      else if (role === "RIVER_BRIDGE" && visibleLayers.bridges && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker bridge",
          html: "<span>🌉</span>",
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">Perennial River Bridge</div><div class="popup-meta">River: ${feature.properties.riverName || "Major River"}<br/>Skew Span: ${feature.properties.estimatedSkewSpanMeters || feature.properties.effectiveSkewSpanMeters || "—"} m<br/>Crossing Angle: ${feature.properties.crossingAngleDegrees || "—"}°</div>`
        );
        group.addLayer(marker);
      }

      // 7. Wildlife Passages
      else if (role === "WILDLIFE_PASSAGE" && visibleLayers.wildlife && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker wildlife",
          html: "<span>🐾</span>",
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">Wildlife Migration Viaduct</div><div class="popup-meta">Passage Span: ${feature.properties.recommendedPassageSpanMeters || "—"} m<br/>Mitigation: Elevated Viaduct Structure</div>`
        );
        group.addLayer(marker);
      }

      // 7b. Statutory Wildlife Sanctuary & National Park Boundaries (Wildlife Protection Act, 1972)
      else if (role === "WILDLIFE_SANCTUARY_BOUNDARY" && visibleLayers.sanctuaries && (geomType === "Polygon" || geomType === "MultiPolygon")) {
        const props = feature.properties || {};
        const layer = L.geoJSON(feature, {
          style: {
            color: "#dc2626",
            weight: 2.5,
            dashArray: "6, 4",
            fillColor: "#ef4444",
            fillOpacity: 0.18,
          },
        }).bindPopup(
          `<div class="popup-title" style="color:#b91c1c; font-weight:700;">🐾 ${props.name || "Wildlife Sanctuary"}</div>` +
          `<div class="popup-meta" style="font-size:11px; line-height:1.45;">` +
          `<b>Legal Statute:</b> ${props.legalStatute || "Wildlife (Protection) Act, 1972"}<br/>` +
          `<b>Location:</b> ${props.state || "—"} (${props.district || "—"})<br/>` +
          `<b>Declared Area:</b> ${props.areaKm2 ? props.areaKm2 + " km²" : "—"}<br/>` +
          `<b>Target Species:</b> ${props.keySpecies || "Protected Wildlife"}<br/>` +
          `<div style="margin-top:6px; padding:4px 8px; background:#fef2f2; border:1px solid #f87171; color:#991b1b; border-radius:4px; font-weight:700; font-size:10.5px;">` +
          `🚨 Mandatory SC-NBWL Clearance Required under WPA 1972 §29/35` +
          `</div></div>`
        );
        group.addLayer(layer);
      }

      // 8. Grade-Separated Crossings (Railway ROBs & Flyovers)
      else if (role === "GRADE_SEPARATED_CROSSING" && visibleLayers.crossings && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker crossing",
          html: "<span>🚊</span>",
          iconSize: [24, 24],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">Grade-Separated Structure</div><div class="popup-meta">Type: ${feature.properties.structureType || "ROB"}<br/>Chainage: ${feature.properties.chainageKm} km</div>`
        );
        group.addLayer(marker);
      }

      // 9. Corridor River Channels (Backend Hydrographic Model)
      else if (role === "CORRIDOR_RIVER_CHANNEL" && visibleLayers.rivers) {
        const layer = L.geoJSON(feature, {
          style: {
            color: "#0284c7",
            weight: 3,
            opacity: 0.85,
          },
        }).bindPopup(
          `<b>Backend Waterway Model</b><br/>River: ${feature.properties.riverName || "River"}<br/>Class: ${feature.properties.riverClass || "PERENNIAL"}<br/>Design Width: ${feature.properties.designChannelWidthMeters || "—"}m`
        );
        group.addLayer(layer);
      }

      // 10. Drainage & Canal Crossing Structures (Layer 12)
      else if (role === "DRAINAGE_CROSSING_STRUCTURE" && visibleLayers.drainage && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker drainage",
          html: "<span>🌊</span>",
          iconSize: [24, 24],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">Canal Aqueduct / Drainage Crossing</div><div class="popup-meta">Target: ${feature.properties.targetFeatureName || "Canal"}<br/>Type: ${feature.properties.structureType || "Aqueduct"}<br/>Span: ${feature.properties.recommendedClearSpanOrWidthMeters || "—"} m<br/>Discharge: ${feature.properties.designDischargeM3PerSec || "—"} m³/s</div>`
        );
        group.addLayer(marker);
      }

      // 11. Terminal Access Road Connectors (Joining User Terminals to Existing Roads)
      else if (role === "TERMINAL_ACCESS_ROAD" && visibleLayers.mainline && geomType === "LineString") {
        const props = feature.properties || {};
        const isOrigin = props.connectorRole === "ORIGIN_ROAD_CONNECTOR";
        const layer = L.geoJSON(feature, {
          style: {
            color: "#06b6d4",
            weight: 4.5,
            opacity: 0.95,
            dashArray: "8, 6",
          },
        }).bindPopup(
          `<div class="popup-title">${isOrigin ? "Origin" : "Destination"} Terminal Access Connector</div><div class="popup-meta">Tie-In Road: ${props.existingRoadRef || "Existing Road"} (${props.existingRoadName || "—"})<br/>Connector Length: ${props.connectorLengthKm ?? "—"} km<br/>Included in Mainline & Statistics: Yes</div>`
        );
        group.addLayer(layer);
      }

      // 12. Existing Road Tie-In Junction Markers
      else if (role === "ROAD_TIE_IN" && visibleLayers.mainline && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const props = feature.properties || {};
        const isOrigin = props.tieInRole === "ORIGIN_EXISTING_ROAD_TIE_IN";
        const marker = L.circleMarker([coords[1], coords[0]], {
          radius: 7,
          color: "#ffffff",
          weight: 2.5,
          fillColor: "#06b6d4",
          fillOpacity: 0.95,
        }).bindPopup(
          `<div class="popup-title">${isOrigin ? "Origin" : "Destination"} Existing Road Tie-In</div><div class="popup-meta">Existing Road: ${props.existingRoadRef || "NH/SH/MDR"}<br/>Corridor: ${props.tieInJunctionName || "Existing Road Network"}<br/>Access Connector: ${props.connectorLengthKm ?? "—"} km</div>`
        );
        group.addLayer(marker);
      }
    });

    if (boundsToFit && boundsToFit.isValid()) {
      mapRef.current.fitBounds(boundsToFit, { padding: [40, 40], maxZoom: 14 });
    }
  }

  // -----------------------------------------------------------
  // Layer Toggle Handler
  // -----------------------------------------------------------
  function toggleLayer(key) {
    setVisibleLayers((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  }

  return (
    <div className="app-container">
      {/* =======================================================
          TOP NAVBAR
          ======================================================= */}
      <header className="navbar">
        <div className="nav-brand">
          <div className="brand-badge">MARG</div>
          <div className="nav-title">
            <h1>Multi-Objective AI for Responsible Geospatial Highway Planning</h1>
            <span>Ministry of Road Transport & Highways (MoRTH) / IRC Engineering Prototype</span>
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
          >
            <option value="custom">Custom Pan-India Coordinates</option>
            {PRESET_CORRIDORS.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>

          <div className="badge-pill" style={{ background: "rgba(147, 51, 234, 0.15)", borderColor: "rgba(147, 51, 234, 0.4)" }}>
            <span style={{ color: "#c084fc", fontWeight: "600" }}>900+ Administrative Entities Indexed</span>
          </div>

          <div className="badge-pill">
            <div className="status-dot" />
            <span>2-Tier Engine Ready</span>
          </div>
        </div>
      </header>

      {/* =======================================================
          MAIN WORKSPACE
          ======================================================= */}
      <div className="main-layout">
        {/* SIDEBAR CONTROLS */}
        <aside className="sidebar">
          {/* Section 1: Presets & Benchmark Corridors */}
          <div className="sidebar-section">
            <div className="section-header">
              <span className="section-title">Benchmark Corridors & Modes</span>
            </div>

            <div className="presets-grid">
              {PRESET_CORRIDORS.map((preset) => (
                <button
                  key={preset.id}
                  className={`preset-chip ${selectedPresetId === preset.id ? "active" : ""}`}
                  onClick={() => applyPreset(preset)}
                >
                  <strong>{preset.name.split(":")[0]}</strong>
                  <br />
                  <span>{preset.tag}</span>
                </button>
              ))}
            </div>

            {/* Facility Mode Buttons */}
            <div className="facility-selector">
              {["EXPRESSWAY", "NATIONAL_HIGHWAY", "STATE_HIGHWAY"].map((mode) => (
                <button
                  key={mode}
                  className={`facility-btn ${facilityMode === mode ? "active" : ""}`}
                  onClick={() => setFacilityMode(mode)}
                >
                  {mode === "EXPRESSWAY" ? "Expressway" : mode === "NATIONAL_HIGHWAY" ? "NH Trunk" : "State Hwy"}
                </button>
              ))}
            </div>

            {/* Deep 13-Layer On-Demand Toggle */}
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

          {/* Section 2: Highway Design & Connectivity Controls */}
          <div className="sidebar-section">
            <div className="section-header">
              <span className="section-title">Design Requirements & Controls</span>
            </div>

            {/* Auto-Connect Intermediate Hubs Feature Toggle */}
            <div className="toggle-row" style={{ marginBottom: "10px" }}>
              <div className="toggle-label">
                <strong>🤖 Auto-Connect Intermediate Hubs</strong>
                <span>Autonomously connect prominent cities along corridor</span>
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

            {/* City Count Number Selector (when Auto-Connect is ON) */}
            {autoConnectHubs ? (
              <div className="auto-hubs-count-card" style={{
                background: "var(--bg-card)",
                border: "1px solid var(--border)",
                borderRadius: "8px",
                padding: "10px 14px",
                marginBottom: "12px"
              }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "8px" }}>
                  <div style={{ fontSize: "11px", fontWeight: "600", color: "var(--text-main)" }}>
                    Number of Cities to Connect:
                  </div>
                  <span style={{
                    fontSize: "12px",
                    fontWeight: "700",
                    color: "var(--primary)",
                    background: "rgba(59, 130, 246, 0.15)",
                    padding: "2px 8px",
                    borderRadius: "12px"
                  }}>
                    {maxAutoHubs} {maxAutoHubs === 1 ? "City" : "Cities"}
                  </span>
                </div>
                
                {/* Number Buttons: 1, 2, 3, 4, 5 */}
                <div style={{ display: "flex", gap: "6px" }}>
                  {[1, 2, 3, 4, 5].map((num) => (
                    <button
                      key={num}
                      type="button"
                      className={`num-hub-btn ${maxAutoHubs === num ? "active" : ""}`}
                      onClick={() => setMaxAutoHubs(num)}
                      style={{
                        flex: 1,
                        padding: "7px 0",
                        fontSize: "12px",
                        fontWeight: maxAutoHubs === num ? "700" : "500",
                        borderRadius: "6px",
                        border: maxAutoHubs === num ? "1px solid var(--primary)" : "1px solid var(--border)",
                        background: maxAutoHubs === num ? "var(--primary)" : "var(--bg-input)",
                        color: maxAutoHubs === num ? "#fff" : "var(--text-main)",
                        cursor: "pointer",
                        transition: "all 0.2s ease"
                      }}
                      title={`Connect up to ${num} cities autonomously`}
                    >
                      {num}
                    </button>
                  ))}
                </div>
                <div style={{ fontSize: "10px", color: "var(--text-muted)", marginTop: "6px" }}>
                  Auto-selects up to {maxAutoHubs} cities via Highway Value Index (HVI) & IRC:SP:84 spacing.
                </div>
              </div>
            ) : (
              <div style={{
                background: "rgba(100, 116, 139, 0.08)",
                border: "1px dashed var(--border)",
                borderRadius: "8px",
                padding: "8px 12px",
                marginBottom: "12px",
                fontSize: "11px",
                color: "var(--text-muted)"
              }}>
                ℹ️ Auto-connect is turned off. The alignment will only connect to user-specified cities below.
              </div>
            )}

            {/* 1. Intermediate Cities & Feeder Spurs (targetHubs) */}
            <div className="hubs-box">
              <div className="hubs-header">
                <div>
                  <span className="hubs-title">🏙️ User-Specified Cities & Tehsils</span>
                  <div style={{ fontSize: "10px", color: "var(--text-muted)", marginTop: "2px" }}>
                    Explicitly specify cities or mandis to connect (always prioritized)
                  </div>
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

              {/* Input Row with All-India District Autocomplete */}
              <div className="district-autocomplete-container">
                <div className="hubs-input-row">
                  <input
                    type="text"
                    placeholder="Search any Indian district or town (e.g. Satara, Barabanki)..."
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
                      const isSub = d.adminLevel === "TEHSIL" || d.adminLevel === "TOWN" || d.adminLevel === "SUBDIVISION" || d.adminLevel === "TALUK" || d.adminLevel === "MANDAL";
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
                              ? `${d.adminLevel || "Tehsil"} in ${d.parentDistrict} District • ${d.state}`
                              : `District HQ: ${d.headquarters || d.name} • ${d.state}`}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* Selected Hubs Tags */}
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

              {/* Preset Suggested Hubs Chips */}
              {(() => {
                const activePreset = PRESET_CORRIDORS.find((p) => p.id === selectedPresetId);
                const suggestions = activePreset?.suggestedHubs || [];
                if (suggestions.length === 0) return null;
                return (
                  <div>
                    <div className="hubs-suggestions-label">Suggested Regional Hubs:</div>
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

            {/* 2. Level of Service (Target V/C Ratio Toggle) */}
            <div style={{ marginBottom: "12px" }}>
              <div style={{ fontSize: "11px", fontWeight: "600", color: "var(--text-main)", marginBottom: "6px" }}>
                Traffic Level of Service (Target V/C Ratio)
              </div>
              <div className="los-selector">
                <button
                  type="button"
                  className={`los-btn ${maxVcRatio === 0.70 ? "active" : ""}`}
                  onClick={() => setMaxVcRatio(0.70)}
                >
                  <strong>Free-Flow (V/C ≤ 0.70)</strong>
                  <span>High capacity & speed buffer (LOS B)</span>
                </button>
                <button
                  type="button"
                  className={`los-btn ${maxVcRatio === 0.85 ? "active" : ""}`}
                  onClick={() => setMaxVcRatio(0.85)}
                >
                  <strong>Economical (V/C ≤ 0.85)</strong>
                  <span>Max hub intake before bottleneck (LOS C)</span>
                </button>
              </div>
            </div>

            {/* 3. Max Alignment Deviation Slider */}
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
                <span>10% (Direct/Tight)</span>
                <span>20% (Standard)</span>
                <span>30% (Wide Terrain Search)</span>
              </div>
            </div>
          </div>

          {/* Section 3: Endpoint Coordinates */}
          <div className="sidebar-section">
            <div className="section-header">
              <span className="section-title">Corridor Endpoints</span>
            </div>

            {/* Origin (Start) */}
            <div className="coord-box">
              <div className="coord-header">
                <span className="coord-title start">📍 Origin Point (A)</span>
                <button
                  className={`pick-map-btn ${mapSelectionMode === "start" ? "active" : ""}`}
                  onClick={() => {
                    const next = mapSelectionMode === "start" ? null : "start";
                    mapSelectionModeRef.current = next;
                    setMapSelectionMode(next);
                    if (next) setStatus("Click anywhere on the map to set Origin.");
                  }}
                >
                  {mapSelectionMode === "start" ? "Cancel Click" : "Pick on Map"}
                </button>
              </div>

              {/* District Quick Search for Origin */}
              <div className="district-autocomplete-container" style={{ marginBottom: "6px" }}>
                <input
                  type="text"
                  className="district-quick-input"
                  placeholder="🔍 Quick Find Origin District (e.g. Pune, Lucknow)..."
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
                              ? `${d.adminLevel || "Tehsil"} in ${d.parentDistrict} District • ${d.state}`
                              : `District HQ: ${d.headquarters || d.name} • ${d.state}`}
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
                <span className="coord-title end">🏁 Destination Point (B)</span>
                <button
                  className={`pick-map-btn ${mapSelectionMode === "end" ? "active" : ""}`}
                  onClick={() => {
                    const next = mapSelectionMode === "end" ? null : "end";
                    mapSelectionModeRef.current = next;
                    setMapSelectionMode(next);
                    if (next) setStatus("Click anywhere on the map to set Destination.");
                  }}
                >
                  {mapSelectionMode === "end" ? "Cancel Click" : "Pick on Map"}
                </button>
              </div>

              {/* District Quick Search for Destination */}
              <div className="district-autocomplete-container" style={{ marginBottom: "6px" }}>
                <input
                  type="text"
                  className="district-quick-input"
                  placeholder="🔍 Quick Find Goal District (e.g. Satara, Varanasi)..."
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
                              ? `${d.adminLevel || "Tehsil"} in ${d.parentDistrict} District • ${d.state}`
                              : `District HQ: ${d.headquarters || d.name} • ${d.state}`}
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

            {/* Run Button */}
            <button className="generate-btn" onClick={generateRoute} disabled={isLoading}>
              {isLoading ? (
                <>
                  <div className="spinner" />
                  <span>Computing Multi-Objective Alignment...</span>
                </>
              ) : (
                <>
                  <span>🚀</span>
                  <span>Generate MARG Alignment</span>
                </>
              )}
            </button>

            {/* Status Text */}
            <div style={{ fontSize: "11px", color: "var(--text-muted)", marginTop: "8px", textAlign: "center" }}>
              {status}
            </div>

            {/* Routing Error Alert */}
            {routingError && (
              <div className="error-card">
                <div className="error-card-title">⚠️ {routingError.title}</div>
                <div className="error-card-message">{routingError.message}</div>
              </div>
            )}

            {/* SMART ENDPOINT AUTO-SHIFT ALERT NOTIFICATION */}
            {snapNotice && (
              <div className="snap-alert-card">
                <div className="snap-alert-header">
                  <span>⚠️</span>
                  <span>Endpoint Auto-Adjustment Applied</span>
                </div>

                {snapNotice.start && (
                  <div className="snap-item">
                    <div className="snap-item-title">
                      <span style={{ color: "var(--emerald)" }}>Origin Point Auto-Shifted</span>
                      <span className="snap-badge">+{snapNotice.start.distanceMeters}m</span>
                    </div>
                    <div className="snap-reason">
                      <strong>Obstacle:</strong> {snapNotice.start.reason}
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
                      <span style={{ color: "var(--rose)" }}>Destination Point Auto-Shifted</span>
                      <span className="snap-badge">+{snapNotice.end.distanceMeters}m</span>
                    </div>
                    <div className="snap-reason">
                      <strong>Obstacle:</strong> {snapNotice.end.reason}
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

          {/* Section 3: Layer Toggles */}
          <div className="sidebar-section">
            <div className="section-header">
              <span className="section-title">Visualized Infrastructure Layers</span>
            </div>

            <div className="layer-toggles-grid">
              <div
                className={`layer-toggle-btn ${visibleLayers.mainline ? "on" : ""}`}
                onClick={() => toggleLayer("mainline")}
              >
                <div className="layer-legend-dot" style={{ background: "#10b981" }} />
                <span>13-L Mainline</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.baseline ? "on" : ""}`}
                onClick={() => toggleLayer("baseline")}
              >
                <div className="layer-legend-dot" style={{ background: "#94a3b8" }} />
                <span>Direct Baseline</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.spurs ? "on" : ""}`}
                onClick={() => toggleLayer("spurs")}
              >
                <div className="layer-legend-dot" style={{ background: "#a855f7" }} />
                <span>Feeder Spurs</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.interchanges ? "on" : ""}`}
                onClick={() => toggleLayer("interchanges")}
              >
                <div className="layer-legend-dot" style={{ background: "#f59e0b" }} />
                <span>Interchanges</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.bridges ? "on" : ""}`}
                onClick={() => toggleLayer("bridges")}
              >
                <div className="layer-legend-dot" style={{ background: "#06b6d4" }} />
                <span>River Bridges</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.drainage ? "on" : ""}`}
                onClick={() => toggleLayer("drainage")}
              >
                <div className="layer-legend-dot" style={{ background: "#0891b2" }} />
                <span>Canals & Culverts</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.rivers ? "on" : ""}`}
                onClick={() => toggleLayer("rivers")}
              >
                <div className="layer-legend-dot" style={{ background: "#0284c7" }} />
                <span>Waterways Model</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.wildlife ? "on" : ""}`}
                onClick={() => toggleLayer("wildlife")}
              >
                <div className="layer-legend-dot" style={{ background: "#15803d" }} />
                <span>Wildlife Passages</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.sanctuaries ? "on" : ""}`}
                onClick={() => toggleLayer("sanctuaries")}
                title="Statutory National Parks & Wildlife Sanctuaries boundaries under Wildlife (Protection) Act, 1972"
              >
                <div className="layer-legend-dot" style={{ background: "#dc2626" }} />
                <span>Sanctuaries (WPA 1972)</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.crossings ? "on" : ""}`}
                onClick={() => toggleLayer("crossings")}
              >
                <div className="layer-legend-dot" style={{ background: "#64748b" }} />
                <span>Rail ROBs & Flyovers</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.row ? "on" : ""}`}
                onClick={() => toggleLayer("row")}
              >
                <div className="layer-legend-dot" style={{ background: "#10b98155" }} />
                <span>RoW Footprint</span>
              </div>

              <div
                className={`layer-toggle-btn ${visibleLayers.multiArchetypes ? "on" : ""}`}
                onClick={() => toggleLayer("multiArchetypes")}
                title="Overlay other generated alignment alternatives as dashed comparison lines on the map"
              >
                <div className="layer-legend-dot" style={{ background: "#38bdf8", border: "1px dashed #fff" }} />
                <span>All Alignments ({Object.keys(cachedArchetypes).length})</span>
              </div>
            </div>

            {/* Marker Symbology & Legend */}
            <div className="section-header" style={{ marginTop: "12px" }}>
              <span className="section-title">Marker Symbology & Meaning</span>
            </div>
            <div className="marker-legend-grid">
              <div className="legend-item"><span className="legend-icon start">A</span> Highway Origin</div>
              <div className="legend-item"><span className="legend-icon end">B</span> Destination</div>
              <div className="legend-item"><span className="legend-icon orig">✕</span> Snapped Obstacle</div>
              <div className="legend-item"><span className="legend-icon interchange">✦</span> Interchange</div>
              <div className="legend-item"><span className="legend-icon bridge">🌉</span> River Bridge</div>
              <div className="legend-item"><span className="legend-icon drainage">🌊</span> Canal Aqueduct</div>
              <div className="legend-item"><span className="legend-icon wildlife">🐾</span> Wildlife Viaduct</div>
              <div className="legend-item"><span className="legend-icon crossing">🚊</span> Rail ROB / Flyover</div>
            </div>
          </div>

          {/* Section 4: Route Metrics & Engineering Plan */}
          {routeInfo && (
            <div className="sidebar-section">
              <div className="section-header">
                <span className="section-title">Geometric & Feasibility Metrics</span>
              </div>

              {/* ============================================================ */}
              {/* 🚨 STATUTORY NBWL & FOREST CLEARANCE ALERT (MANDATORY)       */}
              {/* ============================================================ */}
              {statutoryClearanceReport?.hasSanctuaryIntersection && (
                <div className="statutory-clearance-card">
                  <div className="statutory-badge-banner">
                    <span className="statutory-badge-pill">
                      <span>🚨</span>
                      <span>Mandatory Statutory NBWL & Forest Clearance</span>
                    </span>
                    <span style={{ fontSize: "11px", color: "#fca5a5", fontWeight: 700 }}>
                      WPA 1972 §29/35
                    </span>
                  </div>

                  <div className="statutory-headline">
                    🚨 {statutoryClearanceReport.legalConsequenceHeadline || "MANDATORY STATUTORY CLEARANCE: Alignment intersects notified Sanctuary"}
                  </div>

                  <div className="statutory-statute-citation">
                    📜 {statutoryClearanceReport.legalStatuteCitation || "Wildlife (Protection) Act, 1972 (Section 29/35) & Van (Sanrakshan Evam Samvardhan) Adhiniyam, 2023"}
                  </div>

                  <div className="statutory-impact-metrics">
                    <div className="statutory-metric-box">
                      <div className="statutory-metric-label">Sanctuary Core Length</div>
                      <div className="statutory-metric-val">{statutoryClearanceReport.totalSanctuaryLengthKm} km</div>
                    </div>
                    <div className="statutory-metric-box">
                      <div className="statutory-metric-label">Direct Forest Area Diverted</div>
                      <div className="statutory-metric-val">{statutoryClearanceReport.directSanctuaryForestTakeHectares} ha</div>
                    </div>
                    <div className="statutory-metric-box">
                      <div className="statutory-metric-label">Sanctuaries Affected</div>
                      <div className="statutory-metric-val">{statutoryClearanceReport.traversedSanctuaryNames?.length || 1}</div>
                    </div>
                  </div>

                  {/* Sanctuary Crossing Details */}
                  {statutoryClearanceReport.sanctuaryCrossings && statutoryClearanceReport.sanctuaryCrossings.length > 0 && (
                    <div className="statutory-crossings-list">
                      <div className="statutory-crossings-title">
                        <span>🐾</span>
                        <span>Traversed Protected Entities & Direct Environmental Take:</span>
                      </div>
                      {statutoryClearanceReport.sanctuaryCrossings.map((sc, idx) => (
                        <div key={idx} className="statutory-crossing-item">
                          <div>
                            <strong style={{ color: "#ffffff" }}>{sc.sanctuaryName}</strong> ({sc.state} — {sc.district})
                          </div>
                          <div style={{ fontSize: "10px", color: "#fca5a5", marginTop: "2px" }}>
                            Chainage: km {sc.startKm} to {sc.endKm} ({sc.crossingLengthKm} km core traversal) • <b>Direct Forest Take:</b> {sc.directForestTakeHectares} ha
                          </div>
                          <div style={{ fontSize: "9.5px", color: "#fecaca", marginTop: "2px" }}>
                            <b>Target Species:</b> {sc.keySpecies}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}

                  {/* 6-Point Statutory Checklist */}
                  {statutoryClearanceReport.mandatoryClearancesChecklist && (
                    <div className="statutory-checklist-box">
                      <div className="statutory-checklist-title">
                        <span>📋</span>
                        <span>Mandatory Statutory Clearances Checklist Before Construction:</span>
                      </div>
                      {statutoryClearanceReport.mandatoryClearancesChecklist.map((step, idx) => (
                        <div key={idx} className="statutory-checklist-item">
                          <span style={{ color: "#f87171", fontWeight: 800 }}>⚠️</span>
                          <span>{step}</span>
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Direct Statutory Clearance Portal Links */}
                  <div className="statutory-actions-row">
                    <a
                      href={statutoryClearanceReport.pariveshPortalUrl || "https://parivesh.nic.in/"}
                      target="_blank"
                      rel="noreferrer"
                      className="statutory-action-btn"
                    >
                      <span>🌐</span>
                      <span>MoEFCC PARIVESH 2.0 Portal</span>
                    </a>
                    <a
                      href={statutoryClearanceReport.nbwlGuidanceUrl || "https://cpc.parivesh.nic.in/writereaddata/Final_Guidelines_NBWL_recomme_for_PA.pdf"}
                      target="_blank"
                      rel="noreferrer"
                      className="statutory-action-btn secondary"
                    >
                      <span>📄</span>
                      <span>NBWL PA Guidelines</span>
                    </a>
                  </div>
                </div>
              )}

              {/* Affirmative Statutory Compliance Banner when 100% bypass achieved */}
              {statutoryClearanceReport && !statutoryClearanceReport.hasSanctuaryIntersection && (
                <div className="statutory-compliance-chip">
                  <span className="compliance-icon">🛡️</span>
                  <div>
                    <div className="compliance-title">100% Statutory Wildlife Compliance</div>
                    <div className="compliance-sub">
                      Zero encroachment on notified Wildlife Sanctuaries or National Parks (Wildlife Protection Act, 1972)
                    </div>
                  </div>
                </div>
              )}

              <div className="metrics-summary-grid">
                <div className="metric-stat-box">
                  <div className="stat-label">Mainline</div>
                  <div className="stat-value highlight">{routeInfo.routeLengthKm} km</div>
                </div>
                <div className="metric-stat-box">
                  <div className="stat-label">Baseline</div>
                  <div className="stat-value">{routeInfo.baselineLengthKm} km</div>
                </div>
                <div className="metric-stat-box">
                  <div className="stat-label">Deviation</div>
                  <div className="stat-value good">+{routeInfo.deviationPercent}%</div>
                </div>
              </div>

              {/* ============================================================ */}
              {/* 🌟 SPECIFIC ROUTE IMPROVEMENTS & QUANTIFIABLE VALUE CARD */}
              {/* ============================================================ */}
              {tradeOffProfile?.routeImprovements && (
                <div
                  className="route-improvement-card"
                  style={{
                    borderLeft: `4px solid ${PRESET_DEFINITIONS[tradeOffPreset]?.color || "var(--primary)"}`,
                  }}
                >
                  <div className="route-improvement-header">
                    <div className="route-improvement-title">
                      <span style={{ fontSize: "16px" }}>🌟</span>
                      <div>
                        <div style={{ display: "flex", alignItems: "center", gap: "6px" }}>
                          <span style={{ fontWeight: 800, fontSize: "12px", color: "var(--text-main)" }}>
                            Specific Route Improvements & Value
                          </span>
                          <span
                            className="archetype-badge-pill"
                            style={{
                              backgroundColor: `${PRESET_DEFINITIONS[tradeOffPreset]?.color || "var(--primary)"}22`,
                              color: PRESET_DEFINITIONS[tradeOffPreset]?.color || "var(--primary)",
                              borderColor: PRESET_DEFINITIONS[tradeOffPreset]?.color || "var(--primary)",
                            }}
                          >
                            {PRESET_DEFINITIONS[tradeOffPreset]?.label || tradeOffPreset}
                          </span>
                        </div>
                        <div style={{ fontSize: "10px", color: "var(--text-muted)" }}>
                          IRC:SP:30 & MoRTH Quantitative Design Valuation
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Prominent Headline Callout */}
                  <div className="improvement-headline-banner">
                    <span className="headline-icon">⚡</span>
                    <span className="headline-text">
                      {tradeOffProfile.routeImprovements.headline}
                    </span>
                  </div>

                  {/* Quantified Benefit Badges Grid (4 Cards) */}
                  {tradeOffProfile.routeImprovements.quantifiedBenefits && (
                    <div className="benefit-badges-grid">
                      {tradeOffProfile.routeImprovements.quantifiedBenefits.map((b, idx) => (
                        <div key={idx} className="benefit-badge-item" style={{ "--badge-accent": b.color }}>
                          <div className="badge-item-top">
                            <span className="badge-item-label">{b.label}</span>
                          </div>
                          <div className="badge-item-val" style={{ color: b.color }}>
                            {b.value}
                          </div>
                          <div className="badge-item-detail">{b.detail}</div>
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Live Comparative Delta vs Balanced Corridor (when active is not Balanced) */}
                  {comparativeDeltasVsBalanced && (
                    <div className="live-deltas-container">
                      <div className="live-deltas-title">
                        <span>📊 Live Comparison vs Balanced Alignment:</span>
                      </div>
                      <div className="live-deltas-pills">
                        <span className={`delta-pill ${comparativeDeltasVsBalanced.deltaKm < 0 ? "gain" : "concession"}`}>
                          📏 {comparativeDeltasVsBalanced.deltaKm < 0 ? `${Math.abs(comparativeDeltasVsBalanced.deltaKm)} km shorter` : `+${comparativeDeltasVsBalanced.deltaKm} km detour`}
                        </span>
                        <span className={`delta-pill ${comparativeDeltasVsBalanced.deltaMins < 0 ? "gain" : "concession"}`}>
                          ⏱️ {comparativeDeltasVsBalanced.deltaMins < 0 ? `${Math.abs(comparativeDeltasVsBalanced.deltaMins)} mins faster` : `+${comparativeDeltasVsBalanced.deltaMins} mins extra`}
                        </span>
                        {comparativeDeltasVsBalanced.deltaVocLakh !== 0 && (
                          <span className={`delta-pill ${comparativeDeltasVsBalanced.deltaVocLakh > 0 ? "gain" : "concession"}`}>
                            💰 {comparativeDeltasVsBalanced.deltaVocLakh > 0 ? `+₹${comparativeDeltasVsBalanced.deltaVocLakh} L/yr VOC saved` : `₹${Math.abs(comparativeDeltasVsBalanced.deltaVocLakh)} L/yr higher VOC`}
                          </span>
                        )}
                        <span className={`delta-pill ${comparativeDeltasVsBalanced.deltaForestHa <= 0 ? "gain" : "concession"}`}>
                          🌿 {comparativeDeltasVsBalanced.deltaForestHa <= 0 ? `${Math.abs(comparativeDeltasVsBalanced.deltaForestHa)} ha forest spared` : `+${comparativeDeltasVsBalanced.deltaForestHa} ha forest take`}
                        </span>
                        <span className={`delta-pill ${comparativeDeltasVsBalanced.deltaDisplaced <= 0 ? "gain" : "concession"}`}>
                          🏡 {comparativeDeltasVsBalanced.deltaDisplaced <= 0 ? `${Math.abs(comparativeDeltasVsBalanced.deltaDisplaced)} residents spared` : `+${comparativeDeltasVsBalanced.deltaDisplaced} displaced`}
                        </span>
                      </div>
                    </div>
                  )}

                  {/* Engineering Rationale & Trade-Off Transparency */}
                  <div className="improvement-engineering-box">
                    <div style={{ marginBottom: "6px" }}>
                      <strong style={{ color: "var(--accent)" }}>🎯 Engineering Advantage: </strong>
                      <span style={{ color: "var(--text-main)" }}>{tradeOffProfile.routeImprovements.engineeringAdvantage}</span>
                    </div>
                    <div>
                      <strong style={{ color: "var(--amber)" }}>⚖️ Trade-Off Accepted: </strong>
                      <span style={{ color: "var(--text-dim)" }}>{tradeOffProfile.routeImprovements.tradeOffAccepted}</span>
                    </div>
                  </div>
                </div>
              )}

              {/* ============================================================ */}
              {/* ⚖️ MULTI-OBJECTIVE TRADE-OFFS & ALIGNMENT TUNING DASHBOARD */}
              {/* ============================================================ */}
              {tradeOffProfile && (
                <div className="tradeoff-dashboard-card">
                  <div className="tradeoff-dashboard-header">
                    <div className="tradeoff-dashboard-title">
                      <span>⚖️</span>
                      <span>Multi-Objective Trade-Offs & Alignment Tuning</span>
                    </div>
                    <div className="composite-score-badge">
                      <span>Index:</span>
                      <span>{tradeOffProfile.compositeAlignmentIndex ?? 85}/100</span>
                    </div>
                  </div>

                  {/* 4-Pillar Scorecard Bars */}
                  <div className="pillar-scorecard">
                    {/* 1. Nature & Water */}
                    <div className="pillar-row">
                      <div className="pillar-meta-row">
                        <span className="pillar-label">
                          <span>🌿</span>
                          <span>Nature & Riparian Protection</span>
                        </span>
                        <span className="pillar-value" style={{ color: "#34d399" }}>
                          {tradeOffProfile.scores?.environmentalScore ?? 85}/100
                        </span>
                      </div>
                      <div className="pillar-bar-bg">
                        <div
                          className="pillar-bar-fill"
                          style={{
                            width: `${tradeOffProfile.scores?.environmentalScore ?? 85}%`,
                            background: "linear-gradient(90deg, #059669, #10b981)",
                          }}
                        />
                      </div>
                    </div>

                    {/* 2. Social & Communities */}
                    <div className="pillar-row">
                      <div className="pillar-meta-row">
                        <span className="pillar-label">
                          <span>👥</span>
                          <span>Social & Community Safeguard</span>
                        </span>
                        <span className="pillar-value" style={{ color: "#a78bfa" }}>
                          {tradeOffProfile.scores?.socialScore ?? 88}/100
                        </span>
                      </div>
                      <div className="pillar-bar-bg">
                        <div
                          className="pillar-bar-fill"
                          style={{
                            width: `${tradeOffProfile.scores?.socialScore ?? 88}%`,
                            background: "linear-gradient(90deg, #7c3aed, #8b5cf6)",
                          }}
                        />
                      </div>
                    </div>

                    {/* 3. Directness & Geometric Economy */}
                    <div className="pillar-row">
                      <div className="pillar-meta-row">
                        <span className="pillar-label">
                          <span>⚡</span>
                          <span>Directness & Travel Economy</span>
                        </span>
                        <span className="pillar-value" style={{ color: "#38bdf8" }}>
                          {tradeOffProfile.scores?.directnessScore ?? 80}/100
                        </span>
                      </div>
                      <div className="pillar-bar-bg">
                        <div
                          className="pillar-bar-fill"
                          style={{
                            width: `${tradeOffProfile.scores?.directnessScore ?? 80}%`,
                            background: "linear-gradient(90deg, #0284c7, #06b6d4)",
                          }}
                        />
                      </div>
                    </div>

                    {/* 4. Regional Connectivity */}
                    <div className="pillar-row">
                      <div className="pillar-meta-row">
                        <span className="pillar-label">
                          <span>🏙️</span>
                          <span>Regional Economic Connectivity</span>
                        </span>
                        <span className="pillar-value" style={{ color: "#fbbf24" }}>
                          {tradeOffProfile.scores?.connectivityScore ?? 90}/100
                        </span>
                      </div>
                      <div className="pillar-bar-bg">
                        <div
                          className="pillar-bar-fill"
                          style={{
                            width: `${tradeOffProfile.scores?.connectivityScore ?? 90}%`,
                            background: "linear-gradient(90deg, #d97706, #f59e0b)",
                          }}
                        />
                      </div>
                    </div>
                  </div>

                  {/* Human-Readable Trade-Off Narrative */}
                  {tradeOffProfile.tradeOffNarrative && (
                    <div className="tradeoff-narrative-box">
                      {tradeOffProfile.tradeOffNarrative}
                    </div>
                  )}

                  {/* Key Compromises Bullet Breakdown */}
                  {tradeOffProfile.keyCompromises && tradeOffProfile.keyCompromises.length > 0 && (
                    <div className="key-compromises-list">
                      <div className="key-compromises-title">Key Engineering Compromises</div>
                      {tradeOffProfile.keyCompromises.map((comp, idx) => (
                        <div key={idx} className="compromise-item">{comp}</div>
                      ))}
                    </div>
                  )}

                  {/* One-Click Quick-Chips with Instant Memory Switch */}
                  <div className="quick-chips-title">
                    <span>Align With Archetype (Quick-Chips):</span>
                    <span style={{ fontSize: "10px", color: "var(--text-dim)", fontWeight: "normal" }}>
                      Instant switch for cached (0 ms)
                    </span>
                  </div>
                  <div className="quick-chips-container">
                    {Object.values(PRESET_DEFINITIONS).map((preset) => {
                      const isCached = Boolean(cachedArchetypes[preset.id]);
                      return (
                        <button
                          key={preset.id}
                          type="button"
                          className={`quick-chip ${tradeOffPreset === preset.id ? "active" : ""}`}
                          style={{ "--chip-color": preset.color }}
                          onClick={() => handleSelectPreset(preset.id)}
                          title={preset.description}
                        >
                          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", width: "100%" }}>
                            <span>{preset.label}</span>
                            {isCached && (
                              <span className="cached-badge-mini" title="Generated in memory — Click for instant switch">
                                Ready ⚡
                              </span>
                            )}
                          </div>
                        </button>
                      );
                    })}
                  </div>

                  {/* Fine-Tuning Continuous Preference Sliders */}
                  <div className="sliders-box">
                    <div style={{ fontSize: "10.5px", fontWeight: "700", color: "var(--text-main)", marginBottom: "8px" }}>
                      🎛️ Fine-Tuning Continuous Controls (0.5× – 3.0×):
                    </div>

                    <div className="slider-group">
                      <div className="slider-header">
                        <span className="slider-label">🌿 Environmental Priority</span>
                        <span className="slider-val" style={{ color: "#34d399" }}>{envWeight.toFixed(1)}×</span>
                      </div>
                      <input
                        type="range"
                        min="0.5"
                        max="3.0"
                        step="0.1"
                        value={envWeight}
                        className="pref-slider"
                        style={{ "--slider-color": "#10b981" }}
                        onChange={(e) => handleSliderChange("env", e.target.value)}
                      />
                    </div>

                    <div className="slider-group">
                      <div className="slider-header">
                        <span className="slider-label">👥 Social Safeguard</span>
                        <span className="slider-val" style={{ color: "#a78bfa" }}>{socialWeight.toFixed(1)}×</span>
                      </div>
                      <input
                        type="range"
                        min="0.5"
                        max="3.0"
                        step="0.1"
                        value={socialWeight}
                        className="pref-slider"
                        style={{ "--slider-color": "#8b5cf6" }}
                        onChange={(e) => handleSliderChange("social", e.target.value)}
                      />
                    </div>

                    <div className="slider-group">
                      <div className="slider-header">
                        <span className="slider-label">⚡ Directness Economy</span>
                        <span className="slider-val" style={{ color: "#38bdf8" }}>{directnessWeight.toFixed(1)}×</span>
                      </div>
                      <input
                        type="range"
                        min="0.5"
                        max="3.0"
                        step="0.1"
                        value={directnessWeight}
                        className="pref-slider"
                        style={{ "--slider-color": "#06b6d4" }}
                        onChange={(e) => handleSliderChange("direct", e.target.value)}
                      />
                    </div>
                  </div>

                  {/* Re-Align Action Button */}
                  <button
                    type="button"
                    className="realign-btn"
                    disabled={isRealigning || isLoading}
                    onClick={() => realignAlignment()}
                  >
                    {isRealigning ? (
                      <>
                        <div className="spinner" style={{ width: "12px", height: "12px" }} />
                        <span>Synthesizing Preferences & Re-Aligning...</span>
                      </>
                    ) : (
                      <>
                        <span>✨</span>
                        <span>Re-Align Route Based on Preferences</span>
                      </>
                    )}
                  </button>

                  {/* Side-by-Side Alternative Route Comparison Card */}
                  {alternativeRoutes.length > 0 && (
                    <div className="comparison-card">
                      <div className="comparison-header">
                        <span>Generated Archetypes ({alternativeRoutes.length})</span>
                        <span style={{ fontSize: "10px", color: "#38bdf8" }}>⚡ Click any route to switch instantly</span>
                      </div>
                      <div style={{ display: "flex", flexDirection: "column", gap: "6px" }}>
                        {alternativeRoutes.map((alt) => {
                          const isActive = tradeOffPreset === alt.preset || activeAlternativeId === alt.id;
                          return (
                            <div
                              key={alt.id}
                              onClick={() => switchToArchetype(alt.preset)}
                              style={{
                                display: "flex",
                                justifyContent: "space-between",
                                alignItems: "center",
                                padding: "8px 10px",
                                background: isActive ? "rgba(59, 130, 246, 0.2)" : "rgba(0,0,0,0.25)",
                                border: "1px solid " + (isActive ? "var(--primary)" : "var(--border)"),
                                borderRadius: "6px",
                                fontSize: "11px",
                                cursor: "pointer",
                                transition: "all 0.15s ease",
                              }}
                              className="alt-route-row-btn"
                              title="Click to instantly switch to this alignment"
                            >
                              <div>
                                <div style={{ display: "flex", alignItems: "center", gap: "6px" }}>
                                  <strong style={{ color: alt.color }}>{alt.label}</strong>
                                  {isActive ? (
                                    <span style={{ fontSize: "9px", background: "var(--primary)", color: "#fff", padding: "1px 5px", borderRadius: "10px", fontWeight: "700" }}>Active</span>
                                  ) : (
                                    <span style={{ fontSize: "9px", background: "rgba(56, 189, 248, 0.15)", color: "#38bdf8", padding: "1px 5px", borderRadius: "10px" }}>Switch ⚡</span>
                                  )}
                                </div>
                                <div style={{ fontSize: "10px", color: "var(--text-muted)", marginTop: "2px" }}>
                                  {alt.routeLengthKm} km (+{alt.deviationPercent}%) • Index: {alt.compositeIndex}/100
                                </div>
                              </div>
                              <div style={{ textAlign: "right", fontSize: "10px" }}>
                                <div style={{ color: "#34d399" }}>Forest: {alt.forestHa} ha</div>
                                <div style={{ color: "var(--amber)" }}>Displaced: {alt.displacedPop}</div>
                              </div>
                            </div>
                          );
                        })}
                      </div>

                      {/* Side-by-Side Quick Comparison Matrix if 2+ routes generated */}
                      {alternativeRoutes.length > 1 && (
                        <div style={{ marginTop: "10px", borderTop: "1px solid rgba(255,255,255,0.08)", paddingTop: "8px" }}>
                          <div style={{ fontSize: "10px", fontWeight: "700", color: "#94a3b8", marginBottom: "6px" }}>
                            📐 Archetype Direct Comparison Matrix:
                          </div>
                          <div style={{ overflowX: "auto" }}>
                            <table style={{ width: "100%", fontSize: "10px", borderCollapse: "collapse", textAlign: "left" }}>
                              <thead>
                                <tr style={{ borderBottom: "1px solid var(--border)", color: "var(--text-dim)" }}>
                                  <th style={{ padding: "4px" }}>Archetype</th>
                                  <th style={{ padding: "4px" }}>Length</th>
                                  <th style={{ padding: "4px" }}>Dev</th>
                                  <th style={{ padding: "4px" }}>Forest</th>
                                  <th style={{ padding: "4px" }}>Displaced</th>
                                  <th style={{ padding: "4px" }}>Action</th>
                                </tr>
                              </thead>
                              <tbody>
                                {alternativeRoutes.map((alt) => {
                                  const isActive = tradeOffPreset === alt.preset || activeAlternativeId === alt.id;
                                  return (
                                    <tr
                                      key={alt.id}
                                      style={{
                                        borderBottom: "1px solid rgba(255,255,255,0.05)",
                                        background: isActive ? "rgba(59, 130, 246, 0.12)" : "transparent",
                                      }}
                                    >
                                      <td style={{ padding: "4px", color: alt.color, fontWeight: "700" }}>
                                        {alt.preset === "SHORTEST_PATH" ? "Direct Express" : alt.preset === "ECO_SHIELD" ? "Eco-Shield" : alt.preset === "COMMUNITY_SAFEGUARD" ? "Community" : "Balanced"}
                                      </td>
                                      <td style={{ padding: "4px" }}>{alt.routeLengthKm} km</td>
                                      <td style={{ padding: "4px" }}>+{alt.deviationPercent}%</td>
                                      <td style={{ padding: "4px", color: "#34d399" }}>{alt.forestHa} ha</td>
                                      <td style={{ padding: "4px", color: "var(--amber)" }}>{alt.displacedPop}</td>
                                      <td style={{ padding: "4px" }}>
                                        {isActive ? (
                                          <span style={{ color: "var(--primary)", fontWeight: "700" }}>Active</span>
                                        ) : (
                                          <button
                                            type="button"
                                            onClick={() => switchToArchetype(alt.preset)}
                                            style={{
                                              background: "rgba(59, 130, 246, 0.2)",
                                              border: "1px solid var(--primary)",
                                              color: "#93c5fd",
                                              padding: "1px 6px",
                                              borderRadius: "4px",
                                              cursor: "pointer",
                                              fontSize: "9.5px",
                                            }}
                                          >
                                            Switch
                                          </button>
                                        )}
                                      </td>
                                    </tr>
                                  );
                                })}
                              </tbody>
                            </table>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}

              {networkSummary && (
                <>
                  {/* 6-LANE CAPACITY UPGRADE / VIOLATION REASON ADVISORY CARD */}
                  {trafficCapacity?.sixLaneRecommended && (
                    <div style={{
                      background: "linear-gradient(135deg, rgba(245, 158, 11, 0.12) 0%, rgba(217, 119, 6, 0.08) 100%)",
                      border: "1px solid rgba(245, 158, 11, 0.4)",
                      borderRadius: "8px",
                      padding: "10px 14px",
                      marginTop: "12px",
                      marginBottom: "6px"
                    }}>
                      <div style={{ display: "flex", alignItems: "center", gap: "8px", marginBottom: "6px" }}>
                        <span style={{ fontSize: "16px" }}>⚡</span>
                        <strong style={{ color: "var(--amber)", fontSize: "12px" }}>
                          IRC:SP:84 Section 2.2: 6-Lane Expressway Configuration Recommended
                        </strong>
                      </div>
                      <div style={{ fontSize: "11px", color: "var(--text-main)", lineHeight: "1.4", marginBottom: "8px" }}>
                        {trafficCapacity.capacityStandardNote || "Projected peak traffic exceeds standard 4-lane design capacity. Alignment extended to 6-lane design capacity under IRC:SP:84 Section 2.2."}
                      </div>
                      <div style={{
                        display: "flex",
                        justifyContent: "space-between",
                        alignItems: "center",
                        fontSize: "10px",
                        background: "rgba(0, 0, 0, 0.25)",
                        padding: "6px 10px",
                        borderRadius: "6px",
                        border: "1px solid rgba(245, 158, 11, 0.25)"
                      }}>
                        <div>
                          <span style={{ color: "var(--text-muted)" }}>4-Lane V/C: </span>
                          <span style={{ color: "var(--rose)", fontWeight: "700" }}>{trafficCapacity.projectedVcRatio}</span>
                          <span style={{ color: "var(--text-muted)", marginLeft: "3px" }}>(Exceeds {trafficCapacity.maxVcRatioCapApplied ?? maxVcRatio ?? 0.70} Cap)</span>
                        </div>
                        <div style={{ color: "var(--text-muted)" }}>➔</div>
                        <div>
                          <span style={{ color: "var(--text-muted)" }}>6-Lane V/C: </span>
                          <span style={{ color: "var(--emerald)", fontWeight: "700" }}>
                            {trafficCapacity.projectedSixLaneVcRatio || (trafficCapacity.projectedTotalPeakTrafficPcuHr ? (trafficCapacity.projectedTotalPeakTrafficPcuHr / 7200).toFixed(2) : "0.54")}
                          </span>
                          <span style={{ color: "var(--emerald)", marginLeft: "3px" }}>({trafficCapacity.levelOfService || "LOS B"})</span>
                        </div>
                      </div>
                    </div>
                  )}

                  <div className="section-header" style={{ marginTop: "12px" }}>
                    <span className="section-title">Network Structures & Civil Works</span>
                  </div>

                  <div className="structures-list">
                    <div className="struct-item">
                      <div className="struct-name">
                        <span>✦</span>
                        <span>Planned Interchanges</span>
                      </div>
                      <div className="struct-count">{networkSummary.interchanges}</div>
                    </div>

                    <div className="struct-item">
                      <div className="struct-name">
                        <span>↗</span>
                        <span>Dedicated Feeder Spurs</span>
                      </div>
                      <div className="struct-count">
                        {networkSummary.feederSpurs} ({networkSummary.feederSpurKm} km)
                      </div>
                    </div>

                    {connectedHubs.length > 0 && (
                      <div style={{
                        marginTop: "4px",
                        marginBottom: "8px",
                        padding: "8px 10px",
                        background: "rgba(168, 85, 247, 0.08)",
                        border: "1px solid rgba(168, 85, 247, 0.25)",
                        borderRadius: "6px",
                        fontSize: "11px"
                      }}>
                        <div style={{ fontWeight: "700", color: "#a855f7", marginBottom: "6px", display: "flex", justifyContent: "space-between" }}>
                          <span>Connected Hubs & Mandis ({connectedHubs.length}):</span>
                          <span style={{ fontSize: "10px", fontWeight: "normal", color: "var(--text-muted)" }}>IRC:SP:84 Grade-Separated</span>
                        </div>
                        {connectedHubs.map((h, i) => (
                          <div key={i} style={{
                            padding: "6px 0",
                            borderBottom: i < connectedHubs.length - 1 ? "1px dashed rgba(168, 85, 247, 0.15)" : "none"
                          }}>
                            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                              <div>
                                <strong style={{ color: "var(--text-main)" }}>{h.name}</strong>
                                {h.isUserOverride ? (
                                  <span style={{
                                    marginLeft: "5px",
                                    fontSize: "9px",
                                    padding: "1px 5px",
                                    borderRadius: "4px",
                                    background: "rgba(59, 130, 246, 0.2)",
                                    color: "var(--primary)"
                                  }}>USER PICK</span>
                                ) : (
                                  <span style={{
                                    marginLeft: "5px",
                                    fontSize: "9px",
                                    padding: "1px 5px",
                                    borderRadius: "4px",
                                    background: "rgba(16, 185, 129, 0.2)",
                                    color: "var(--emerald)"
                                  }}>AUTO HVI</span>
                                )}
                                {h.sixLaneRecommended && (
                                  <span style={{
                                    marginLeft: "5px",
                                    fontSize: "9px",
                                    padding: "1px 5px",
                                    borderRadius: "4px",
                                    background: "rgba(245, 158, 11, 0.2)",
                                    color: "var(--amber)",
                                    fontWeight: "700"
                                  }}>6-LANE EXTENSION</span>
                                )}
                              </div>
                              <div style={{ textAlign: "right", fontWeight: "600", color: "#a855f7" }}>
                                {h.spurKm} km spur
                              </div>
                            </div>
                            <div style={{ fontSize: "10px", color: "var(--text-muted)", marginTop: "2px" }}>
                              {h.tieInRoadRef} • Pop: {h.population.toLocaleString()}
                            </div>
                            {h.decisionReason && (
                              <div style={{
                                fontSize: "10px",
                                color: h.sixLaneRecommended ? "var(--amber)" : "var(--text-muted)",
                                marginTop: "3px",
                                lineHeight: "1.3",
                                background: "rgba(0,0,0,0.15)",
                                padding: "3px 6px",
                                borderRadius: "4px"
                              }}>
                                <strong>Reason / Rule:</strong> {h.decisionReason}
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    )}

                    <div className="struct-item">
                      <div className="struct-name">
                        <span>🛣️</span>
                        <span>Existing Road Tie-In Connectors</span>
                      </div>
                      <div className="struct-count">
                        {networkSummary.terminalAccessKm} km ({networkSummary.startRoadRef} → {networkSummary.goalRoadRef})
                      </div>
                    </div>

                    <div className="struct-item">
                      <div className="struct-name">
                        <span>🌉</span>
                        <span>Perennial River Bridges</span>
                      </div>
                      <div className="struct-count">
                        {networkSummary.riverBridges} ({networkSummary.riverBridgeSpanM}m span)
                      </div>
                    </div>

                    <div className="struct-item">
                      <div className="struct-name">
                        <span>🌊</span>
                        <span>Canal Aqueducts / Culverts</span>
                      </div>
                      <div className="struct-count">
                        {networkSummary.drainageStructures} structures
                      </div>
                    </div>

                    <div className="struct-item">
                      <div className="struct-name">
                        <span>🐾</span>
                        <span>Wildlife Overpasses / Viaducts</span>
                      </div>
                      <div className="struct-count">
                        {networkSummary.wildlifePassages} ({networkSummary.wildlifeSpanM}m span)
                      </div>
                    </div>

                    <div className="struct-item">
                      <div className="struct-name">
                        <span>🚊</span>
                        <span>Railway ROBs & Flyovers</span>
                      </div>
                      <div className="struct-count">
                        {networkSummary.railwayRobs} ROBs / {networkSummary.roadFlyovers} Flyovers
                      </div>
                    </div>

                    <div className="struct-item">
                      <div className="struct-name">
                        <span>📐</span>
                        <span>Direct RoW Land Acquisition</span>
                      </div>
                      <div className="struct-count" style={{ color: "#60a5fa" }}>
                        {networkSummary.rowHectares} ha ({networkSummary.rowWidthM}m width)
                      </div>
                    </div>

                    <div className="struct-item">
                      <div className="struct-name">
                        <span>👥</span>
                        <span>Est. Displaced Population</span>
                      </div>
                      <div className="struct-count" style={{ color: "var(--amber)" }}>
                        {networkSummary.displacedPop} persons
                      </div>
                    </div>
                  </div>

                  {/* EXPANDABLE COMPLIANCE & EVALUATION LOG */}
                  {hubDecisions.length > 0 && (
                    <div style={{ marginTop: "12px" }}>
                      <button
                        type="button"
                        onClick={() => setShowAllDecisions((s) => !s)}
                        style={{
                          width: "100%",
                          padding: "8px 10px",
                          background: "var(--bg-card)",
                          border: "1px solid var(--border)",
                          borderRadius: "6px",
                          color: "var(--text-muted)",
                          fontSize: "11px",
                          fontWeight: "600",
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          cursor: "pointer",
                          transition: "all 0.2s ease"
                        }}
                      >
                        <span>📋 All Evaluated Cities & Constraint Decisions ({hubDecisions.length})</span>
                        <span>{showAllDecisions ? "▲ Hide" : "▼ View Decisions"}</span>
                      </button>

                      {showAllDecisions && (
                        <div style={{
                          marginTop: "6px",
                          maxHeight: "260px",
                          overflowY: "auto",
                          background: "var(--bg-card)",
                          border: "1px solid var(--border)",
                          borderRadius: "6px",
                          padding: "8px"
                        }}>
                          {hubDecisions.map((d, idx) => {
                            const isAcc = d.accepted;
                            const isSixLane = d.reason && d.reason.includes("6-lane");
                            const badgeColor = isAcc
                              ? (isSixLane ? "var(--amber)" : "var(--emerald)")
                              : "var(--text-muted)";
                            return (
                              <div
                                key={idx}
                                style={{
                                  padding: "6px 0",
                                  borderBottom: idx < hubDecisions.length - 1 ? "1px solid var(--border)" : "none",
                                  fontSize: "11px"
                                }}
                              >
                                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                                  <strong style={{ color: isAcc ? "var(--text-main)" : "var(--text-muted)" }}>
                                    {d.name} {d.district ? `(${d.district})` : ""}
                                  </strong>
                                  <span style={{
                                    fontSize: "9px",
                                    padding: "1px 6px",
                                    borderRadius: "4px",
                                    fontWeight: "700",
                                    background: isAcc
                                      ? (isSixLane ? "rgba(245, 158, 11, 0.15)" : "rgba(16, 185, 129, 0.15)")
                                      : "rgba(100, 116, 139, 0.15)",
                                    color: badgeColor
                                  }}>
                                    {d.status || (isAcc ? "ACCEPTED" : "EXCLUDED")}
                                  </span>
                                </div>
                                <div style={{
                                  fontSize: "10px",
                                  color: isAcc ? (isSixLane ? "var(--amber)" : "var(--emerald)") : "var(--rose)",
                                  marginTop: "2px",
                                  lineHeight: "1.3"
                                }}>
                                  {d.reason}
                                </div>
                                <div style={{ fontSize: "9px", color: "var(--text-muted)", marginTop: "1px" }}>
                                  Lateral Dist: {d.distanceToCenterlineKm ?? "—"} km • Induced: +{d.inducedPeakTrafficPcuHr ?? 0} PCU/hr • V/C: {d.projectedVcRatio ?? "—"}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  )}
                </>
              )}
            </div>
          )}
        </aside>

        {/* MAP VIEWPORT */}
        <main className="map-viewport">
          <div ref={mapContainerRef} style={{ height: "100%", width: "100%" }} />

          {/* Floating Quick Action Buttons on Map */}
          <div className="map-floating-bar">
            <button
              className="map-control-btn"
              onClick={() => setBaseMapType((b) => (b === "osm" ? "satellite" : "osm"))}
            >
              {baseMapType === "osm" ? "🛰️ Satellite View" : "🗺️ Street Map"}
            </button>
            <button
              className="map-control-btn"
              onClick={() => {
                if (mapRef.current) {
                  mapRef.current.flyTo([22.5, 78.5], 5, { duration: 1 });
                }
              }}
            >
              🇮🇳 Zoom to India
            </button>
          </div>
        </main>
      </div>
    </div>
  );
}
