import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { getBasemapConfig } from "./layers/basemaps";
import {
  LAYER_BY_ID,
  OVERVIEW_FEATURE_ROLES,
  getSegmentColor,
} from "./layers/layerCatalog";
import { humanizeClassLabel, humanizeText } from "./utils/plainLanguage";
import LayerAuditPanel from "./components/LayerAuditPanel";
import MapLensHud from "./components/MapLensHud";
import EngineeringDossier from "./components/EngineeringDossier";
import Hero21 from "./components/Hero21.jsx";
import Mode2Workspace from "./Mode2Workspace.jsx";
import { motion } from "motion/react";
import { ArrowRight, Compass, FileCheck2, Layers, Scale, ShieldAlert, ShieldCheck, Trees } from "lucide-react";
import Mode1EiaWorkspace from "./Mode1EiaWorkspace.jsx";

// -------------------------------------------------------------
// India & Sample Region Constants
// -------------------------------------------------------------
const PAN_INDIA_BOUNDS = [
  [6.5, 68.0],
  [37.5, 97.5],
];

const PRESET_CORRIDORS = [
  {
    id: "assam",
    tier: "TIER_2_PRECACHED_BENCHMARK",
    name: "Region 1: Kaziranga–Karbi Anglong (Assam)",
    tag: "Wildlife & Floodplains",
    start: { lat: 26.5897, lng: 93.40035 },
    end: { lat: 25.84573, lng: 93.43781 },
    center: [26.25, 93.42],
    zoom: 9,
    suggestedHubs: ["Diphu", "Dokmoka", "Bokakhat", "Howraghat", "Manja", "Silonijan"],
  },
  {
    id: "western_ghats",
    tier: "TIER_2_PRECACHED_BENCHMARK",
    name: "Region 2: Pune–Satara (Western Ghats, MH)",
    tag: "Hills & Landslide Risk",
    start: { lat: 18.36002, lng: 73.85352 },
    end: { lat: 17.71704, lng: 74.02648 },
    center: [18.03, 73.93],
    zoom: 9,
    suggestedHubs: ["Wai", "Shirwal", "Satara", "Bhor", "Khandala"],
  },
  {
    id: "gangetic_plains",
    tier: "TIER_2_PRECACHED_BENCHMARK",
    name: "Region 3: Barabanki–Ayodhya (Uttar Pradesh)",
    tag: "Rivers & Farmland",
    start: { lat: 26.915, lng: 81.245 },
    end: { lat: 26.765, lng: 82.125 },
    center: [26.84, 81.68],
    zoom: 9,
    suggestedHubs: ["Ramnagar", "Ramsanehighat", "Rudauli", "Barabanki", "Faizabad", "Zaidpur"],
  },
  {
    id: "interstate_pan_india",
    tier: "TIER_1_DYNAMIC_PAN_INDIA",
    name: "Long Highway: Akola (MH) to Bhopal (MP)",
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

function extractMainlineCoordinates(features) {
  const feature = features?.find((item) => ["GREENFIELD_MAINLINE", "MAINLINE"].includes(item.properties?.featureRole));
  const geometry = feature?.geometry;
  if (geometry?.type === "LineString") return geometry.coordinates;
  if (geometry?.type === "MultiLineString") return geometry.coordinates.flat();
  return [];
}

function HomePage({ onMode1, onMode2, onEia }) {
  const modes = [
    {
      number: "01",
      tag: "Corridor Planning",
      title: "Multi-Objective Route Alignment",
      detail: "Compute feasible highway alignments across terrain slopes, floodplains, settlements, and statutory forest/wildlife buffers using tunable cost matrices.",
      action: "Open Route Planner",
      icon: Compass,
      image: "/images/mode1_image.jpg",
      onClick: onMode1,
    },
    {
      number: "02",
      tag: "DPR & Statutory Audit",
      title: "DPR Verification & Risk Scoring",
      detail: "Cross-examine submitted KML corridors and DPR PDFs against GIS ground truth, score clearance readiness (0–100%), estimate CAMPA/WUP liabilities, and test alternative alignments.",
      action: "Open Project Review",
      icon: ShieldCheck,
      image: "/images/mode2_button.png",
      onClick: onMode2,
    },
    {
      number: "03",
      tag: "Statutory Reporting",
      title: "EIA & EMP Report Generation",
      detail: "Draft structured, evidence-cited Environmental Impact Assessment reports (DOCX/PDF) combining GIS observations, live atmospheric baselines, and Indian statutory norms.",
      action: "Open EIA Studio",
      icon: FileCheck2,
      image: "/images/eia_button.jpg",
      onClick: onEia,
    },
  ];

  const aboutCapabilities = [
    {
      icon: Layers,
      title: "Deterministic 13-Layer GIS",
      detail: "Screens alignments against Bhuvan LULC, Reserve Forests, ESZ/wildlife buffers, flood zones, slopes >15%, and habitation clusters.",
    },
    {
      icon: ShieldAlert,
      title: "Regulatory Readiness & CAMPA",
      detail: "Flags FCA 1980, WLPA 1972, and EIA 2006 clearance bottlenecks, computes Eco-Class NPV, 1:2 CA land, and sizes WII wildlife underpasses.",
    },
    {
      icon: Trees,
      title: "Multi-Objective Alternatives",
      detail: "Re-routes between submitted endpoints using Eco-Shield, Balanced, and Social Safeguard weight matrices to quantify engineering vs. land trade-offs.",
    },
    {
      icon: Scale,
      title: "Auditable EIA Drafting",
      detail: "Generates multi-chapter DOCX and PDF reports where every numeric claim and legal citation is validated against traceable GIS and DPR evidence.",
    },
  ];

  return (
    <main className="min-h-screen bg-[#F7F5F0] text-[#1C2420]">
      <Hero21 onMode1={onMode1} onMode2={onMode2} onEia={onEia} />
      <section id="modes" className="relative z-30 mx-auto -mt-32 max-w-6xl px-6 pb-16 sm:px-10">
        <div className="grid gap-6 md:grid-cols-3">
          {modes.map((mode, idx) => {
            const Icon = mode.icon;
            return (
              <motion.article
                key={mode.number}
                initial={{ opacity: 0, y: 28 }}
                whileInView={{ opacity: 1, y: 0 }}
                viewport={{ once: true, amount: 0.15 }}
                transition={{ type: "spring", stiffness: 240, damping: 24, delay: idx * 0.08 }}
                whileHover={{ y: -8 }}
                onClick={mode.onClick}
                className="group flex cursor-pointer flex-col overflow-hidden rounded-3xl border border-[#D8E8DD] bg-[#FFFFFF]/95 p-3 shadow-[0_20px_50px_rgba(45,106,79,0.09)] backdrop-blur-md transition-shadow duration-300 hover:border-[#1C4A36]/50 hover:shadow-[0_28px_64px_rgba(45,106,79,0.16)]"
              >
                <div className="space-y-2 px-4 pt-3.5 pb-4">
                  <div className="flex items-center justify-between">
                    <span className="inline-flex items-center gap-1.5 rounded-full bg-[#D1E3D6] px-3 py-1 font-mono text-[11px] font-semibold text-[#143627]">
                      <Icon className="size-3.5 text-[#1C4A36]" />
                      {mode.number} · {mode.tag}
                    </span>
                    <span className="h-2 w-2 rounded-full bg-[#52B788] transition-transform group-hover:scale-125" />
                  </div>
                  <h2 className="pt-1.5 text-lg font-semibold tracking-tight text-[#143627]">
                    {mode.title}
                  </h2>
                  <p className="text-xs leading-relaxed text-[#59645C]">
                    {mode.detail}
                  </p>
                </div>

                <div className="relative mt-auto overflow-hidden rounded-2xl">
                  <img
                    src={mode.image}
                    alt={mode.title}
                    className="aspect-[16/10] w-full object-cover transition-transform duration-500 group-hover:scale-105"
                  />
                  <div className="absolute inset-0 bg-gradient-to-t from-[#143627]/75 via-[#143627]/10 to-transparent" />
                  <div className="absolute inset-x-4 bottom-3.5 flex items-center justify-between text-xs font-semibold text-[#F7F5F0]">
                    <span>{mode.action}</span>
                    <span className="flex size-8 items-center justify-center rounded-full bg-white/25 backdrop-blur-sm transition-transform group-hover:translate-x-1">
                      <ArrowRight className="size-3.5" />
                    </span>
                  </div>
                </div>
              </motion.article>
            );
          })}
        </div>

        {/* Animated About Us Section */}
        <motion.section
          id="about"
          initial={{ opacity: 0, y: 24 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true, amount: 0.15 }}
          transition={{ duration: 0.6 }}
          className="mt-20 rounded-3xl border border-[#D8E8DD] bg-[#FFFFFF] p-8 shadow-sm sm:p-10"
        >
          <div className="grid gap-8 border-b border-[#E5ECE6] pb-8 lg:grid-cols-[1.1fr_0.9fr] lg:items-end">
            <div>
              <span className="inline-flex items-center gap-2 rounded-full bg-[#D1E3D6] px-3 py-1 font-mono text-[11px] font-semibold uppercase tracking-wider text-[#1B4332]">
                Institutional Architecture
              </span>
              <h2 className="mt-3 text-2xl font-semibold tracking-tight text-[#1B4332] sm:text-3xl">
                Technical decision support for highway planning, regulatory audit, and statutory appraisal.
              </h2>
            </div>
            <p className="text-sm leading-6 text-[#59645C]">
              MARG (Multi-objective Alignment &amp; Regulatory Governance) supports highway agencies, NABET-accredited consultants, and appraisal committees in evaluating alignment feasibility before formal PARIVESH submission—uniting deterministic GIS analysis, document RAG, and Indian statutory compliance.
            </p>
          </div>

          <div className="mt-8 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
            {aboutCapabilities.map((cap, idx) => {
              const CapIcon = cap.icon;
              return (
                <motion.div
                  key={cap.title}
                  initial={{ opacity: 0, y: 18 }}
                  whileInView={{ opacity: 1, y: 0 }}
                  viewport={{ once: true, amount: 0.2 }}
                  transition={{ type: "spring", stiffness: 250, damping: 24, delay: idx * 0.07 }}
                  whileHover={{ y: -4 }}
                  className="rounded-2xl border border-[#E2EAE3] bg-[#F7F5F0]/70 p-5 transition-colors hover:border-[#143627]/40 hover:bg-[#D1E3D6]/60"
                >
                  <div className="flex size-10 items-center justify-center rounded-xl bg-[#1B4332] text-[#F7F5F0] shadow-sm">
                    <CapIcon className="size-5" />
                  </div>
                  <h3 className="mt-4 text-sm font-semibold text-[#1B4332]">{cap.title}</h3>
                  <p className="mt-2 text-xs leading-5 text-[#59645C]">{cap.detail}</p>
                </motion.div>
              );
            })}
          </div>
        </motion.section>

        <footer className="mt-12 flex flex-col gap-2 border-t border-[#D9DDD3] pt-6 text-xs leading-5 text-[#5c6b60] sm:flex-row sm:justify-between">
          <span>Preliminary technical decision support — final alignments require field surveys and competent statutory clearances.</span>
          <span>MARG · Highway Planning &amp; Regulatory Governance Platform</span>
        </footer>
      </section>
    </main>
  );
}

function humanizeSnapReason(reason) {
  if (!reason) return "Nearby safety or protected area boundary";
  const r = reason.toUpperCase();
  if (r.includes("HOSPITAL") || r.includes("MEDICAL") || r.includes("CLINIC")) {
    return "Hospital or medical center safety zone (kept at least 100 m clear)";
  }
  if (r.includes("INSTITUTIONAL") || r.includes("SCHOOL") || r.includes("COLLEGE") || r.includes("UNIVERSITY")) {
    return "School or college safety zone (kept at least 100 m clear)";
  }
  if (r.includes("SACRED") || r.includes("HERITAGE") || r.includes("RELIGIOUS")) {
    return "Temple, religious site, or heritage area (strictly protected)";
  }
  if (r.includes("COLONY") || r.includes("RESIDENTIAL_SETTLEMENT")) {
    return "Crowded residential neighborhood boundary";
  }
  if (r.includes("BUILDING_FOOTPRINT")) {
    return "Existing building or home in the way";
  }
  if (r.includes("PROTECTED_AREA") || r.includes("WILDLIFE_SANCTUARY")) {
    return "Protected National Park or Wildlife Sanctuary (strictly off-limits)";
  }
  if (r.includes("SLOPE")) {
    return "Mountain cliff too steep for a safe road (steeper than 30°)";
  }
  if (r.includes("WATER_BODIES") || r.includes("PERENNIAL_RIVER") || r.includes("RESERVOIR")) {
    return "Deep river channel or water body";
  }
  if (r.includes("LANDSLIDE") || r.includes("FAULT") || r.includes("GEOTECH")) {
    return "Active landslide or unstable ground zone";
  }
  if (r.includes("SETTLEMENT") || r.includes("BUILDING")) {
    return "Crowded town or village center";
  }
  if (r.includes("ISOLATED_GRID_NODE")) {
    return "Isolated patch of terrain with no safe road exit";
  }
  return reason
    .replace(/^SNAPPED_FROM_/, "")
    .replace(/_TO_NEAREST_FEASIBLE_GRID_CELL$/, "")
    .replace(/_/g, " ")
    .toLowerCase();
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
  const [status, setStatus] = useState("Pick a sample region or start & end places, then click Find Best Route.");
  const [routingError, setRoutingError] = useState(null);
  const [routeInfo, setRouteInfo] = useState(null);
  const [networkSummary, setNetworkSummary] = useState(null);
  const [connectedHubs, setConnectedHubs] = useState([]);
  const [trafficCapacity, setTrafficCapacity] = useState(null);
  const [hubDecisions, setHubDecisions] = useState([]);
  const [showAllDecisions, setShowAllDecisions] = useState(false);

  // Unified Workspace Navigation & Mode 1 EIA / MongoDB Route Persistence State
  const [activeWorkspace, setActiveWorkspace] = useState("home");
  const [mode1GisScreening, setMode1GisScreening] = useState(null);
  const [mode1RouteSignature, setMode1RouteSignature] = useState(null);
  const [eiaProjectName, setEiaProjectName] = useState("");
  const [eiaProjectProponent, setEiaProjectProponent] = useState("");
  const [eiaStudyArea, setEiaStudyArea] = useState("Kaziranga–Karbi Anglong corridor");
  const [eiaProjectState, setEiaProjectState] = useState("Assam");
  const [eiaAssessmentDate, setEiaAssessmentDate] = useState(new Date().toISOString().slice(0, 10));
  const [eiaLibraryStatus, setEiaLibraryStatus] = useState(null);
  const [eiaBusy, setEiaBusy] = useState(false);
  const [eiaMessage, setEiaMessage] = useState("");
  const [eiaJob, setEiaJob] = useState(null);
  const [eiaError, setEiaError] = useState("");
  const [savedRouteId, setSavedRouteId] = useState("");
  const [mongoStatus, setMongoStatus] = useState("Routes save to MongoDB when you choose Save generated route for EIA.");
  const [hasPendingRoute, setHasPendingRoute] = useState(false);
  const [routeSaveBusy, setRouteSaveBusy] = useState(false);
  const [routeSaveMessage, setRouteSaveMessage] = useState("");
  const pendingRouteRef = useRef(null);

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

  // Always reset viewport scroll to the very top when switching workspaces (Home ↔ Planner ↔ Mode 2 ↔ EIA)
  useLayoutEffect(() => {
    const scrollToTop = () => {
      window.scrollTo({ top: 0, left: 0, behavior: "instant" });
      document.documentElement.scrollTop = 0;
      document.body.scrollTop = 0;
    };
    scrollToTop();
    const rafId = requestAnimationFrame(scrollToTop);
    return () => cancelAnimationFrame(rafId);
  }, [activeWorkspace]);

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
      setMode1GisScreening(null);
      setMode1RouteSignature(null);
      pendingRouteRef.current = null;
      setHasPendingRoute(false);
      setSavedRouteId("");
      setRouteSaveMessage("");
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
    if (activeWorkspace !== "mode1" || !mapContainerRef.current || mapRef.current) return;

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
        setStatus("Please click a location inside India.");
        return;
      }

      if (mode === "start") {
        setStart({ lat, lng });
        setMode1GisScreening(null);
        setMode1RouteSignature(null);
        setRouteInfo(null);
        pendingRouteRef.current = null;
        setHasPendingRoute(false);
        setSavedRouteId("");
        setSelectedPresetId("custom");
        mapSelectionModeRef.current = null;
        setMapSelectionMode(null);
        setStatus(`📍 Starting point set: ${lat}, ${lng}`);
      } else if (mode === "end") {
        setEnd({ lat, lng });
        setMode1GisScreening(null);
        setMode1RouteSignature(null);
        setRouteInfo(null);
        pendingRouteRef.current = null;
        setHasPendingRoute(false);
        setSavedRouteId("");
        setSelectedPresetId("custom");
        mapSelectionModeRef.current = null;
        setMapSelectionMode(null);
        setStatus(`🏁 Destination selected: ${lat}, ${lng}`);
      } else if (mode === "hub") {
        const hubToken = `${lat.toFixed(4)} ${lng.toFixed(4)}`;
        setTargetHubs((prev) => (prev.includes(hubToken) ? prev : [...prev, hubToken]));
        mapSelectionModeRef.current = null;
        setMapSelectionMode(null);
        setStatus(`🏙️ Added town along the route: ${hubToken}`);
      }
    });

    mapRef.current = map;
    if (currentFeatures.length > 0) {
      renderGeoJsonFeatures(currentFeatures, tradeOffPreset, activeLayerId, pinnedLayerIds, routeLayerProfile, spurLayerProfiles, true);
    }

    return () => {
      map.remove();
      mapRef.current = null;
      baseTileLayerRef.current = null;
      startMarkerRef.current = null;
      endMarkerRef.current = null;
      snapVectorGroupRef.current = null;
      routeLayersGroupRef.current = null;
      colorizedRouteGroupRef.current = null;
      focusHighlightGroupRef.current = null;
      comparativeRoutesGroupRef.current = null;
    };
  }, [activeWorkspace]);

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
  }, [baseMapType, activeWorkspace]);

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
  }, [start, end, activeWorkspace]);

  // Handle Crosshair cursor during map-selection
  useEffect(() => {
    if (!mapRef.current) return;
    const container = mapRef.current.getContainer();
    if (mapSelectionMode) {
      container.classList.add("crosshair-cursor");
    } else {
      container.classList.remove("crosshair-cursor");
    }
  }, [mapSelectionMode, activeWorkspace]);

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
      label: "⚖️ Balanced Route",
      shortLabel: "Balanced",
      env: 1.0,
      social: 1.0,
      direct: 1.0,
      color: "#1B4332",
      description: "Weighs nature protection, avoiding homes, and travel time equally for a well-rounded route",
    },
    ECO_SHIELD: {
      id: "ECO_SHIELD",
      label: "🌿 Protect Nature & Rivers",
      shortLabel: "Nature First",
      env: 2.5,
      social: 1.0,
      direct: 0.8,
      color: "#10b981",
      description: "Takes extra detours to stay well away from dense forests, wildlife paths, and flood-prone riverbanks",
    },
    COMMUNITY_SAFEGUARD: {
      id: "COMMUNITY_SAFEGUARD",
      label: "🏡 Protect Homes & Villages",
      shortLabel: "Community First",
      env: 1.0,
      social: 2.5,
      direct: 0.8,
      color: "#8b5cf6",
      description: "Focuses on avoiding rural homes, village boundaries, schools, and local neighborhoods",
    },
    SHORTEST_PATH: {
      id: "SHORTEST_PATH",
      label: "⚡ Fastest Direct Route",
      shortLabel: "Fastest Direct",
      env: 0.8,
      social: 0.8,
      direct: 2.5,
      color: "#06b6d4",
      description: "Keeps the road as straight and short as safely possible to cut travel time and fuel costs",
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

      const cachedData = cached.data || null;
      const cachedCoords = extractMainlineCoordinates(cached.features || []);
      pendingRouteRef.current = cachedData && cachedCoords.length >= 2 ? cachedData : null;
      setHasPendingRoute(Boolean(pendingRouteRef.current));
      setMode1GisScreening(cachedData?.metadata?.environmentalSectionInput ?? null);
      const cachedStart = cachedData?.metadata?.endpointResolution?.resolvedStart;
      const cachedGoal = cachedData?.metadata?.endpointResolution?.resolvedGoal;
      setMode1RouteSignature(routeSignature(
        cachedStart ? { lat: cachedStart.lat, lng: cachedStart.lon ?? cachedStart.lng } : start,
        cachedGoal ? { lat: cachedGoal.lat, lng: cachedGoal.lon ?? cachedGoal.lng } : end,
      ));
      setSavedRouteId("");
      setRouteSaveMessage("");

      setActiveAlternativeId("alt-" + targetKey);
      setStatus(`Switched to the ${p.shortLabel} route (${cached.routeInfo.routeLengthKm} km).`);
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
            <span>Route Length: <b>${altLength} km</b> (+${altDev}% longer than straight line)</span><br/>
            <span style="font-size:10px; color:#143627; font-weight:700;">Click this line to view this route option</span>
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
  }, [cachedArchetypes, tradeOffPreset, showMultiArchetypes, activeWorkspace]);

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
    setMode1GisScreening(null);
    setMode1RouteSignature(null);
    setRouteInfo(null);
    pendingRouteRef.current = null;
    setHasPendingRoute(false);
    setSavedRouteId("");
    setRouteSaveMessage("");
    setEiaJob(null);
    setEiaProjectName(preset.name);
    setEiaStudyArea(preset.name);
    setEiaProjectState(preset.id === "western_ghats" ? "Maharashtra"
      : preset.id === "gangetic_plains" ? "Uttar Pradesh"
      : preset.id === "interstate_pan_india" ? "Maharashtra; Madhya Pradesh"
      : "Assam");
    if (!lockBasemap) {
      setBaseMapType("osm");
    }
    setStatus(`Loaded ${preset.name}. Click 'Find Best Route' to begin.`);

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
    pendingRouteRef.current = null;
    setHasPendingRoute(false);
    setSavedRouteId("");
    setRouteSaveMessage("");
    setMode1GisScreening(null);
    setMode1RouteSignature(null);
    setEiaJob(null);
    setEiaError("");
    setRoutingError(null);
    setSnapNotice(null);
    setConnectedHubs([]);
    setTrafficCapacity(null);
    setHubDecisions([]);
    setShowAllDecisions(false);
    setStatus("Checking forests, floods, hills, and homes to find the best route...");

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

      setStatus("✅ Route ready! Explore it on the map or scroll down for the Public Impact Report.");
    } catch (err) {
      console.error("Routing execution error:", err);
      setRoutingError({
        title: "Could Not Find a Route",
        message: err.message || "Something went wrong while planning this route.",
      });
      setStatus("Could not find a route between these points.");
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
      (f) => f.properties?.featureRole === "DEDICATED_FEEDER_SPUR" || f.properties?.featureRole === "FEEDER_SPUR_HIGHWAY"
    );
    const hubList = spurFeatures.map((f) => ({
      name: f.properties?.servedHubName || f.properties?.connectingHub || "Regional Hub",
      spurKm: Number(f.properties?.spurLengthKm ?? f.properties?.lengthKm ?? 0).toFixed(1),
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

    // Sync Mode 1 Environmental EIA screening & MongoDB persistence payload
    setMode1GisScreening(meta.environmentalSectionInput ?? null);
    const resolvedStartPt = meta.endpointResolution?.resolvedStart;
    const resolvedGoalPt = meta.endpointResolution?.resolvedGoal;
    setMode1RouteSignature(routeSignature(
      resolvedStartPt ? { lat: resolvedStartPt.lat, lng: resolvedStartPt.lon ?? resolvedStartPt.lng } : start,
      resolvedGoalPt ? { lat: resolvedGoalPt.lat, lng: resolvedGoalPt.lon ?? resolvedGoalPt.lng } : end,
    ));
    const mainlineCoords = extractMainlineCoordinates(feats);
    pendingRouteRef.current = mainlineCoords.length >= 2 ? data : null;
    setHasPendingRoute(mainlineCoords.length >= 2);
    setRouteSaveMessage("");
    setSavedRouteId("");

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
    setStatus("Recalculating a new route option based on your chosen priorities...");

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
      setStatus(`Ready! Showing the ${altLabel} route option (${resSummary.routeKm.toFixed(1)} km).`);
    } catch (err) {
      console.error("Re-alignment error:", err);
      setRoutingError({
        title: "Could Not Update Route",
        message: err.message || "Something went wrong while recalculating this route option.",
      });
      setStatus("Could not update the route.");
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
            ? `<br/>Steepest Point Here: <b>${runMaxSlope.toFixed(1)}°</b>`
            : "";
        ribbon.bindTooltip(
          `<div style="font-size:11px;line-height:1.4;">` +
            `<b>${cfg.icon} #${cfg.number}: ${cfg.shortName}</b><br/>` +
            `<span style="color:#56665E;">${segmentTitle}</span><br/>` +
            `Ground Type: <b style="color:${color};">${humanizeClassLabel(runVal)}</b>${slopeExtra}<br/>` +
            `Distance from Start: ${runStartCh.toFixed(1)} – ${endCh.toFixed(1)} km` +
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

    // Paint Main Highway
    paintSamples(profile, "Main Highway Route", false);

    // Paint Every Connecting Side Road with identical 13-topic color coding
    if (Array.isArray(spurProfiles)) {
      spurProfiles.forEach((sp) => {
        const label = sp?.servedHubName ? `Connecting Road — ${sp.servedHubName}` : "Connecting Side Road";
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
        ? `<div style="margin-bottom:4px;font-size:10px;font-weight:700;color:#7e22ce;">🟣 Connecting Road — ${props.servedHubName || props.parentSpurId || "Town"}</div>`
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

      // 1. Straight-Line Comparison Path
      if (role.includes("BASELINE")) {
        const layer = L.geoJSON(feature, {
          style: {
            color: "#94a3b8",
            weight: 2.8,
            dashArray: "6, 8",
            opacity: 0.78,
          },
        }).bindPopup("<b>Straight-Line Comparison Path</b><br/>Used to show how much forest, flood risk, and home disruption the smart route avoids.");
        group.addLayer(layer);
      }

      // 2. Road Land-Width Strip (L11 & Main Route)
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
          `<div class="popup-title">Land Strip Needed for Road</div><div class="popup-meta">Total Width: <b>${props.designRowWidthMeters || 60} meters</b><br/>Includes lanes, shoulders, drainage, and safety buffer.</div>`
        );
        group.addLayer(layer);
      }

      // 3. Connecting Side Roads to Nearby Towns
      else if (role === "DEDICATED_FEEDER_SPUR" || role === "FEEDER_SPUR_HIGHWAY") {
        const reasonHtml = props.decisionReason
          ? `<br/><div style="margin-top:4px; font-size:11px; color:${props.sixLaneExpansionRecommended ? '#b45309' : '#56665E'}; line-height:1.35"><b>Why connected:</b> ${humanizeText(props.decisionReason)}</div>`
          : "";
        const layer = L.geoJSON(feature, {
          style: {
            color: props.sixLaneExpansionRecommended ? "#f59e0b" : "#a855f7",
            weight: hasColorizedSpurs ? 2.5 : 5,
            opacity: hasColorizedSpurs ? 0.35 : 0.95,
          },
        }).bindPopup(
          `<div class="popup-title">Connecting Road to Nearby City</div><div class="popup-meta">Connects to: <b>${props.servedHubName || props.connectingHub || "Local Town"}</b><br/>Length: ${props.spurLengthKm || props.lengthKm || "—"} km<br/>Meets Local Road: ${props.tieInRoadRef || "Existing Highway"}${reasonHtml}</div>`
        );
        group.addLayer(layer);
      }

      // 4. Main Planned Highway
      else if (role === "GREENFIELD_MAINLINE" || role === "MAINLINE") {
        const layer = L.geoJSON(feature, {
          style: {
            color: mainlineColor,
            weight: hasColorizedRibbon ? 3 : 6,
            opacity: hasColorizedRibbon ? 0.35 : 0.95,
          },
        }).bindPopup(
          `<div class="popup-title">${PRESET_DEFINITIONS[currentPreset]?.label || "Planned Smart Highway"}</div><div class="popup-meta">Total Length: <b>${props.routeLengthKm || "—"} km</b><br/>Extra Detour to Avoid Obstacles: +${props.deviationPercent || "—"}%</div>`
        );
        group.addLayer(layer);
        boundsToFit = layer.getBounds();
      }

      // 5. Highway Exits & Interchanges
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
          ? `${props.terminalRole === "ORIGIN_TERMINAL_INTERCHANGE" ? "Start" : "End"} Highway Interchange`
          : "Planned Highway Exit";
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">${title}</div><div class="popup-meta">` +
            `<b>Design:</b> ${humanizeClassLabel(props.interchangeType || props.type || "Trumpet")}<br/>` +
            (props.existingRoadRef ? `<b>Connects to:</b> ${props.existingRoadRef} (${humanizeClassLabel(props.roadCategory || "")})<br/>` : "") +
            (props.tollPlazaConfiguration ? `<b>Toll Gate:</b> ${humanizeClassLabel(props.tollPlazaConfiguration)}<br/>` : "") +
            (props.connectingHub ? `<b>Serves City:</b> ${props.connectingHub}<br/>` : "") +
            (props.chainageKm != null ? `<b>Location:</b> ${props.chainageKm} km from start` : "") +
            `</div>`
        );
        group.addLayer(marker);
      }

      // 5b. Connected Cities & Towns
      else if (role === "REGIONAL_SERVICE_HUB" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const marker = L.circleMarker([coords[1], coords[0]], {
          radius: 7,
          color: "#ffffff",
          weight: 2,
          fillColor: "#a855f7",
          fillOpacity: 0.9,
        }).bindPopup(
          `<div class="popup-title">🏙️ ${props.name || "Connected City"}</div><div class="popup-meta">Connecting Road: ${props.spurDistanceKm ?? "—"} km · Joins: ${props.tieInRoadRef || "Local Highway"}</div>`
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
          `<div class="popup-title">Planned River Bridge</div>${spurTagHtml(props)}<div class="popup-meta"><b>River:</b> ${props.riverName || "Major River"}<br/><b>Bridge Length:</b> ${props.estimatedSkewSpanMeters || props.effectiveSkewSpanMeters || "—"} meters<br/><b>Crossing Angle:</b> ${props.crossingAngleDegrees || "—"}° (closer to 90° is safer &amp; shorter)</div>`
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
          `<div class="popup-title">Safe Wildlife Crossing Bridge</div>${spurTagHtml(props)}<div class="popup-meta"><b>Wildlife Path:</b> ${props.corridorName || "Animal Movement Zone"}<br/><b>Bridge Length:</b> ${props.recommendedPassageSpanMeters || "—"} meters<br/><b>Design:</b> ${humanizeClassLabel(props.mitigationStructure || "Elevated Wildlife Bridge")}</div>`
        );
        group.addLayer(marker);
      }

      // 7b. Protected National Parks & Sanctuaries (L4 — Hidden in Overview!)
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
            `<b>Location:</b> ${props.state || "—"} (${props.district || "—"})<br/>` +
            `<b>Protected Area Size:</b> ${props.areaKm2 ? props.areaKm2 + " sq km" : "—"}<br/>` +
            `<b>Key Wildlife:</b> ${props.keySpecies || "Protected Species"}<br/>` +
            `<div style="margin-top:6px; padding:4px 8px; background:#fef2f2; border:1px solid #f87171; color:#991b1b; border-radius:4px; font-weight:700; font-size:10.5px;">` +
            `🛡️ Strictly Protected Area — Highways Must Go Around` +
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
          `<div class="popup-title" style="color:#b45309;">🌿 ${props.name || "Eco-Sensitive Buffer Zone"}</div>` +
            `<div class="popup-meta"><b>Protected Buffer:</b> ${props.eszBufferKm || 10} km around park boundary<br/>Requires wildlife-friendly road design and animal crossings.</div>`
        );
        group.addLayer(layer);
      }

      // 8. Railway Bridges & Road Flyovers (L6 & Overview)
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
          `<div class="popup-title">${isRail ? "Railway Overbridge" : "Road Flyover / Overpass"}</div>` +
            spurTagHtml(props) +
            `<div class="popup-meta">` +
            `<b>Crosses Over:</b> ${props.crossedCorridorRef || props.crossedCorridorName || "Existing Road"}<br/>` +
            `<b>Type:</b> ${humanizeClassLabel(props.structureType || "OVERPASS")}<br/>` +
            `<b>Location:</b> ${props.chainageKm ?? "—"} km from start` +
            `</div>`
        );
        group.addLayer(marker);
      }

      // 9. Major River Channels (L7 & L2)
      else if (role === "CORRIDOR_RIVER_CHANNEL") {
        const layer = L.geoJSON(feature, {
          style: {
            color: "#0284c7",
            weight: 3.2,
            opacity: 0.85,
          },
        }).bindPopup(
          `<div class="popup-title">Major River Channel</div><div class="popup-meta"><b>River:</b> ${props.riverName || "River"}<br/><b>Flow Type:</b> ${humanizeClassLabel(props.riverClass || "PERENNIAL")}<br/><b>Estimated Width:</b> ${props.designChannelWidthMeters || "—"} meters</div>`
        );
        group.addLayer(layer);
      }

      // 10. Water Drainage Culverts & Canals (L13)
      else if (role === "DRAINAGE_CROSSING_STRUCTURE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker drainage",
          html: "<span>🌊</span>",
          iconSize: [24, 24],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">Rainwater &amp; Stream Drainage Tunnel</div>${spurTagHtml(props)}<div class="popup-meta"><b>Waterway:</b> ${props.targetFeatureName || "Local Stream or Canal"}<br/><b>Structure:</b> ${humanizeClassLabel(props.structureType || "BOX_CULVERT")}<br/><b>Opening Width:</b> ${props.recommendedClearSpanOrWidthMeters || "—"} meters</div>`
        );
        group.addLayer(marker);
      }

      // 10b. Wetland Protection Zones (L13)
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
            `<div class="popup-title">💧 Protected Wetland Crossing</div><div class="popup-meta"><b>Wetland:</b> ${props.wetlandName || props.name || "Natural Wetland"}<br/><b>Protection Plan:</b> ${humanizeClassLabel(props.recommendedEngineeringMitigation || "Water Flow Protection Culvert")}</div>`
          );
          group.addLayer(marker);
        } else {
          const layer = L.geoJSON(feature, {
            style: { color: "#0d9488", weight: 2, fillColor: "#14b8a6", fillOpacity: 0.28 },
          }).bindPopup(
            `<div class="popup-title">💧 Protected Wetland Zone</div><div class="popup-meta">${props.wetlandName || props.name || "Wetland Buffer"}</div>`
          );
          group.addLayer(layer);
        }
      }

      // 11. Start & End Connecting Roads (L6 & Overview)
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
          `<div class="popup-title">${isOrigin ? "Start" : "End"} Connecting Access Road</div><div class="popup-meta"><b>Joins Highway:</b> ${props.existingRoadRef || "Existing Road"} (${props.existingRoadName || "—"})<br/><b>Road Type:</b> ${humanizeClassLabel(props.roadCategory || "ARTERIAL")}<br/><b>Connector Length:</b> ${props.connectorLengthKm ?? "—"} km</div>`
        );
        group.addLayer(layer);
      }

      // 12. Existing Highway Connection Points (L6 & Overview)
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
          `<div class="popup-title">${isOrigin ? "Start" : "End"} Highway Connection</div><div class="popup-meta"><b>Joins:</b> ${props.existingRoadRef || "Existing Highway"}<br/><b>Road Type:</b> ${humanizeClassLabel(props.roadCategory || "NATIONAL_HIGHWAY")}</div>`
        );
        group.addLayer(marker);
      }

      // 13. Nearby Buildings & Homes Check (L5 & L11)
      else if (role === "SETTLEMENT_BUILDING_CONFLICT_ZONE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const directBlds = props.buildingsInDirectRowCount ?? 0;
        const byType = props.buildingsInDirectRowByType || {};
        const breakdownStr =
          Object.entries(byType)
            .filter(([, cnt]) => cnt > 0)
            .map(([t, cnt]) => `${humanizeClassLabel(t)}: ${cnt}`)
            .join(", ") || "Edge of town only (no buildings hit)";
        const hasDemolition = directBlds > 0;
        const icon = L.divIcon({
          className: "struct-marker conflict",
          html: `<span style="display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:${hasDemolition ? "#dc2626" : "#ec4899"};border:2px solid #fff;font-size:12px;box-shadow:0 2px 6px rgba(0,0,0,0.45);">${hasDemolition ? "🏚️" : "🏘️"}</span>`,
          iconSize: [24, 24],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">${hasDemolition ? "Buildings Inside Road Path" : "Nearby Village (0 Buildings Affected)"}</div>` +
            `<div class="popup-meta">` +
            `<b>Area:</b> ${props.settlementName || "Local Settlement"}<br/>` +
            `<b>Buildings Affected:</b> ${directBlds}<br/>` +
            `<b>Details:</b> ${breakdownStr}<br/>` +
            `<b>Support Plan:</b> ${humanizeClassLabel(props.recommendedEngineeringMitigation || "Fair Compensation & Relocation Support")}` +
            `</div>`
        );
        group.addLayer(marker);
      }

      // 14. Hillside & Landslide Protection Zones (L9)
      else if (role === "GEOTECH_MITIGATION_ZONE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker geotech",
          html: `<span style="display:inline-flex;align-items:center;justify-content:center;width:25px;height:25px;border-radius:50%;background:#d97706;border:2px solid #fff;font-size:12px;box-shadow:0 2px 6px rgba(0,0,0,0.5);">🪨</span>`,
          iconSize: [25, 25],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">🪨 Hillside &amp; Soil Safety Support</div>${spurTagHtml(props)}<div class="popup-meta">` +
            `<b>Ground Risk:</b> ${props.name || props.featureName || "Steep Slope or Soft Soil"}<br/>` +
            `<b>Safety Fix:</b> ${humanizeClassLabel(props.mitigationStructure || props.recommendedEngineeringMitigation || "ANCHORED_RETAINING_WALL")}<br/>` +
            `<b>Reinforced Length:</b> ${props.recommendedStabilizationLengthMeters ?? "—"} meters<br/>` +
            `<b>Location:</b> ${props.chainageKm ?? "—"} km from start</div>`
        );
        group.addLayer(marker);
      }

      // 15. High-Voltage Power Line Safety Crossings (L10)
      else if (role === "UTILITY_CROSSING_PROTECTION" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const icon = L.divIcon({
          className: "struct-marker utility",
          html: `<span style="display:inline-flex;align-items:center;justify-content:center;width:25px;height:25px;border-radius:50%;background:#ca8a04;border:2px solid #fff;font-size:13px;box-shadow:0 2px 6px rgba(0,0,0,0.5);">⚡</span>`,
          iconSize: [25, 25],
          iconAnchor: [12, 12],
        });
        const marker = L.marker([coords[1], coords[0]], { icon }).bindPopup(
          `<div class="popup-title">⚡ High-Voltage Power Line Crossing</div>${spurTagHtml(props)}<div class="popup-meta">` +
            `<b>Power Line:</b> ${props.lineName || props.name || "High-Voltage Line"}<br/>` +
            `<b>Safety Guard:</b> ${humanizeClassLabel(props.protectionType || props.recommendedProtection || "GUARD_CRADLE")}<br/>` +
            `<b>Protected Span:</b> ${props.recommendedProtectiveSpanMeters ?? "—"} meters</div>`
        );
        group.addLayer(marker);
      }

      // 16. Nearby Community Protection Zones (L12)
      else if (role === "DEMOGRAPHIC_EXPOSURE_MITIGATION_ZONE" && geomType === "Point") {
        const coords = feature.geometry.coordinates;
        const halo = L.circle([coords[1], coords[0]], {
          radius: 300,
          color: "#8b5cf6",
          weight: 2,
          fillColor: "#8b5cf6",
          fillOpacity: 0.22,
        }).bindPopup(
          `<div class="popup-title">👥 Nearby Community Protection Zone</div><div class="popup-meta">` +
            `<b>Community:</b> ${props.settlementName || props.tractName || props.name || "Local Neighborhood"}<br/>` +
            `<b>People Within 250 m:</b> ${props.populationWithin250m ?? props.totalPopulation ?? "—"} residents<br/>` +
            `<b>Safety Measure:</b> ${humanizeClassLabel(props.recommendedEngineeringMitigation || "Noise Barrier & Safe Pedestrian Crossing")}</div>`
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

  function routeSignature(startPoint = start, endPoint = end) {
    return JSON.stringify({
      start: startPoint,
      end: endPoint,
      selectedPresetId,
      facilityMode,
      deepIngestion,
      autoConnectHubs,
      maxAutoHubs,
      targetHubs,
      maxVcRatio,
      maxDeviationPercent,
    });
  }

  async function saveCurrentRoute() {
    const data = pendingRouteRef.current;
    if (!data) return;
    if (mode1RouteSignature !== routeSignature()) {
      setRouteSaveMessage("Route settings changed. Generate the alignment again before saving it.");
      return;
    }
    const coordinates = extractMainlineCoordinates(data.features);
    if (coordinates.length < 2) {
      setRouteSaveMessage("The generated response did not contain a usable mainline geometry.");
      return;
    }
    const metadata = data.metadata || {};
    const diagnostics = metadata.routeDiagnostics || {};
    const fallbackName = PRESET_CORRIDORS.find((preset) => preset.id === selectedPresetId)?.name;
    const projectName = eiaProjectName.trim() || fallbackName || "MARG Greenfield Highway Route";
    setRouteSaveBusy(true);
    setRouteSaveMessage("");
    try {
      const response = await fetch("/api/routes/saved", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        signal: AbortSignal.timeout(60000),
        body: JSON.stringify({
          projectName,
          projectProponent: eiaProjectProponent.trim() || null,
          studyArea: eiaStudyArea.trim() || projectName,
          projectState: eiaProjectState.trim() || null,
          facilityMode: metadata.facilityMode || facilityMode,
          regionId: metadata.regionId || (selectedPresetId !== "custom" ? selectedPresetId : null),
          coordinates,
          routeSummary: {
            routeLengthKm: routeInfo?.routeLengthKm ?? diagnostics.routeLengthKm ?? metadata.routeLengthKm ?? null,
            baselineLengthKm: routeInfo?.baselineLengthKm ?? diagnostics.directDistanceBaselineKm ?? metadata.directDistanceBaselineKm ?? null,
            deviationPercent: routeInfo?.deviationPercent ?? diagnostics.alignmentDeviationPercent ?? metadata.deviationPercent ?? null,
            facilityMode: metadata.facilityMode || facilityMode,
            tierMode: data.corridorTierMode || metadata.corridorTierMode || "TIER_1_DYNAMIC_PAN_INDIA",
          },
          routeMetadata: {
            routeDiagnostics: diagnostics,
            endpointResolution: metadata.endpointResolution || {},
            transportNetworkAndCapacitySummary: metadata.transportNetworkAndCapacitySummary || {},
            coordinateReferenceSystem: metadata.coordinateReferenceSystem || "EPSG:4326",
          },
          gisScreening: metadata.environmentalSectionInput || {},
        }),
      });
      if (!response.ok) throw new Error(await response.text());
      const saved = await response.json();
      setSavedRouteId(saved.routeId);
      setHasPendingRoute(false);
      setMongoStatus("MongoDB connected · generated routes are available for later reports.");
      setRouteSaveMessage(`Saved “${saved.projectName}” for EIA generation. Route ID: ${saved.routeId}`);
    } catch (error) {
      setRouteSaveMessage(`Could not save route to MongoDB: ${error.message || "database unavailable"}`);
    } finally {
      setRouteSaveBusy(false);
    }
  }

  async function eiaRequest(url, options = {}) {
    const headers = new Headers(options.headers || {});
    if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type", "application/json");
    const response = await fetch(url, { ...options, headers });
    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      const rawBody = await response.text();
      try {
        const body = JSON.parse(rawBody);
        const primary = body.error || body.message || message;
        message = body.detail ? `${primary} (${body.detail})` : primary;
      } catch {
        if (rawBody) message = rawBody;
      }
      throw new Error(message);
    }
    return response;
  }

  async function refreshEiaLibrary() {
    setEiaBusy(true);
    setEiaError("");
    setEiaMessage("Refreshing the environmental PDF library…");
    try {
      const response = await eiaRequest("/api/eia/library/refresh", { method: "POST" });
      const library = await response.json();
      setEiaLibraryStatus(library);
      setEiaMessage(`Indexed ${library.documentCount} documents and ${library.chunkCount} page-preserving chunks.`);
    } catch (error) {
      setEiaError(error.message || "Could not refresh the environmental library.");
      setEiaMessage("");
    } finally {
      setEiaBusy(false);
    }
  }

  async function generateEnvironmentalReport() {
    if (mode1GisScreening && (!mode1RouteSignature || mode1RouteSignature !== routeSignature())) {
      setEiaError("Route settings have changed since the displayed alignment was generated. Generate the route again before drafting its report.");
      return;
    }
    if (!eiaProjectName.trim() || !eiaStudyArea.trim() || !eiaProjectState.trim() || !eiaAssessmentDate) {
      setEiaError("Project name, study area, state/UT, and assessment date are required.");
      return;
    }

    setEiaBusy(true);
    setEiaError("");
    setEiaJob(null);
    setEiaMessage("Indexing current root-folder PDFs before evidence retrieval…");
    try {
      const refreshResponse = await eiaRequest("/api/eia/library/refresh", { method: "POST" });
      const library = await refreshResponse.json();
      setEiaLibraryStatus(library);
      if (!library.chunkCount) throw new Error("No readable PDF passages were indexed. Add the required environmental PDFs to the configured knowledge folder and refresh.");

      setEiaMessage("Retrieving guidance, screening legal references, and drafting evidence-linked sections…");
      const generationResponse = await eiaRequest("/api/eia/reports/from-library", {
        method: "POST",
        body: JSON.stringify({
          projectName: eiaProjectName.trim(),
          projectProponent: eiaProjectProponent.trim() || null,
          routeAlternativeName: mode1GisScreening ? "MARG Greenfield Route Alternative" : null,
          studyArea: eiaStudyArea.trim(),
          projectState: eiaProjectState.trim(),
          reportVersion: "v1.0",
          assessmentDate: eiaAssessmentDate,
          gisScreening: mode1GisScreening || null,
          projectFacts: [],
        }),
      });
      const receipt = await generationResponse.json();
      setEiaJob(receipt);
      if (receipt.state === "REJECTED" || receipt.state === "FAILED") {
        setEiaError(receipt.validationIssues?.map((issue) => issue.message).join(" ") || receipt.message || "The report job was rejected.");
        setEiaMessage("");
        return;
      }

      const terminalStates = new Set(["COMPLETED", "REJECTED", "FAILED"]);
      let latest = receipt;
      for (let attempt = 0; attempt < 180 && !terminalStates.has(latest.state); attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 2000));
        const statusResponse = await eiaRequest(`/api/eia/reports/jobs/${encodeURIComponent(receipt.jobId)}`);
        latest = await statusResponse.json();
        setEiaJob(latest);
        setEiaMessage(latest.message || `Report job is ${latest.state.toLowerCase()}…`);
      }
      if (latest.state === "COMPLETED") {
        setEiaMessage(`Environmental report is ready (${latest.pageCount ?? "—"} pages).`);
      } else if (latest.state === "REJECTED" || latest.state === "FAILED") {
        setEiaError(latest.validationIssues?.map((issue) => issue.message).join(" ") || latest.message || "Report generation failed.");
        setEiaMessage("");
      } else {
        setEiaMessage("Report is still processing. You can check again using this job ID: " + receipt.jobId);
      }
    } catch (error) {
      setEiaError(error.message || "Environmental report generation failed.");
      setEiaMessage("");
    } finally {
      setEiaBusy(false);
    }
  }

  async function downloadEiaArtifact(format) {
    if (!eiaJob?.jobId) return;
    setEiaError("");
    try {
      const response = await eiaRequest(`/api/eia/reports/${encodeURIComponent(eiaJob.jobId)}/${format}`);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `marg-environmental-eia-${eiaJob.jobId}.${format === "pdf" ? "pdf" : "docx"}`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (error) {
      setEiaError(error.message || "Report download failed.");
    }
  }

  if (activeWorkspace === "home") {
    return (
      <HomePage
        onMode1={() => setActiveWorkspace("mode1")}
        onMode2={() => setActiveWorkspace("mode2")}
        onEia={() => setActiveWorkspace("eia")}
      />
    );
  }

  if (activeWorkspace === "eia") {
    return <Mode1EiaWorkspace onHome={() => setActiveWorkspace("home")} />;
  }

  if (activeWorkspace === "mode2") {
    return <Mode2Workspace onHome={() => setActiveWorkspace("home")} />;
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
            <h1>MARG Route Planner</h1>
            <span>Public Guide to Smarter, Safer &amp; Greener Highways</span>
          </div>
        </div>

        <div className="nav-actions">
          <button
            type="button"
            className="home-nav-btn"
            onClick={() => setActiveWorkspace("home")}
            title="Switch between Route Planner, Project Review, and Environmental Reports"
          >
            ← All Tools
          </button>

          <select
            className="corridor-quick-select"
            value={selectedPresetId}
            onChange={(e) => {
              const p = PRESET_CORRIDORS.find((x) => x.id === e.target.value);
              if (p) applyPreset(p);
            }}
            aria-label="Quick Select Sample Region"
          >
            <option value="custom">Custom Places Anywhere in India</option>
            {PRESET_CORRIDORS.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>

          <div className="badge-pill badge-pill-subtle">
            <span>900+ Towns &amp; Districts</span>
          </div>

          <div className="badge-pill badge-pill-ready">
            <span className="status-dot" />
            <span>Map Data Ready</span>
          </div>

          {routeInfo && (
            <button
              type="button"
              className="nav-dossier-btn"
              onClick={scrollToDossier}
              title="Jump down to the 6-Section Public Impact Report"
            >
              <span>Full Impact Report</span>
              <span aria-hidden="true">↓</span>
            </button>
          )}
        </div>
      </header>

      {/* =======================================================
          ABOVE-THE-FOLD HERO MAP STUDIO (LEFT DRAWER · MAP · RIGHT DRAWER)
          ======================================================= */}
      <section ref={mapStudioRef} className="map-studio-stage" aria-label="Interactive Route Map">
        {/* LEFT COLLAPSIBLE DRAWER: ROUTE PLANNER (INPUTS ONLY) */}
        <aside className={`studio-drawer left-drawer ${leftDrawerOpen ? "open" : "collapsed"}`}>
          {!leftDrawerOpen ? (
            <button
              type="button"
              className="drawer-collapsed-rail"
              onClick={() => setLeftDrawerOpen(true)}
              title="Open Route Planner"
            >
              <span className="rail-chevron">»</span>
              <span className="rail-label">Route Planner</span>
            </button>
          ) : (
            <div className="drawer-inner">
              <div className="drawer-topbar">
                <div>
                  <span className="drawer-eyebrow">PLAN YOUR ROUTE</span>
                  <h2 className="drawer-title">Route Planner</h2>
                </div>
                <button
                  type="button"
                  className="drawer-toggle-btn"
                  onClick={() => setLeftDrawerOpen(false)}
                  title="Hide Route Planner to widen the map"
                  aria-label="Hide Route Planner"
                >
                  «
                </button>
              </div>

              <div className="drawer-scroll-body">
                {/* Group 1: Sample Regions & Road Type */}
                <div className="planner-group">
                  <div className="planner-group-header">
                    <span className="planner-group-title">Sample Regions to Explore</span>
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
                        {mode === "EXPRESSWAY" ? "Expressway" : mode === "NATIONAL_HIGHWAY" ? "National Hwy" : "State Hwy"}
                      </button>
                    ))}
                  </div>

                  <div className="toggle-row">
                    <div className="toggle-label">
                      <strong>Detailed Live Map Scan</strong>
                      <span>Check detailed terrain &amp; forest maps for custom places</span>
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

                {/* Group 2: Start & End Places */}
                <div className="planner-group">
                  <div className="planner-group-header">
                    <span className="planner-group-title">Start &amp; End Places</span>
                  </div>

                  {/* Origin (Start) */}
                  <div className="coord-box">
                    <div className="coord-header">
                      <span className="coord-title start">Starting Point (A)</span>
                      <button
                        type="button"
                        className={`pick-map-btn ${mapSelectionMode === "start" ? "active" : ""}`}
                        onClick={() => {
                          const next = mapSelectionMode === "start" ? null : "start";
                          mapSelectionModeRef.current = next;
                          setMapSelectionMode(next);
                          if (next) setStatus("Click anywhere on the map to choose your starting point.");
                        }}
                      >
                        {mapSelectionMode === "start" ? "Cancel" : "Click on Map"}
                      </button>
                    </div>

                    <div className="district-autocomplete-container">
                      <input
                        type="text"
                        className="district-quick-input"
                        placeholder="Search starting town or district (e.g. Pune, Lucknow)..."
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
                      <span className="coord-title end">Ending Point (B)</span>
                      <button
                        type="button"
                        className={`pick-map-btn ${mapSelectionMode === "end" ? "active" : ""}`}
                        onClick={() => {
                          const next = mapSelectionMode === "end" ? null : "end";
                          mapSelectionModeRef.current = next;
                          setMapSelectionMode(next);
                          if (next) setStatus("Click anywhere on the map to choose your destination.");
                        }}
                      >
                        {mapSelectionMode === "end" ? "Cancel" : "Click on Map"}
                      </button>
                    </div>

                    <div className="district-autocomplete-container">
                      <input
                        type="text"
                        className="district-quick-input"
                        placeholder="Search destination town or district (e.g. Satara, Ayodhya)..."
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

                {/* Group 3: Nearby Cities & Route Settings */}
                <div className="planner-group">
                  <div className="planner-group-header">
                    <span className="planner-group-title">Nearby Cities &amp; Route Rules</span>
                  </div>

                  <div className="toggle-row">
                    <div className="toggle-label">
                      <strong>Auto-Connect Nearby Cities</strong>
                      <span>Add highway exits for major towns along the way</span>
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
                        <span>How Many Cities to Connect</span>
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
                            title={`Connect up to ${num} cities along the route`}
                          >
                            {num}
                          </button>
                        ))}
                      </div>
                      <div className="auto-hubs-caption">
                        Picks the most important nearby towns while keeping exits safely spaced apart.
                      </div>
                    </div>
                  ) : (
                    <div className="planner-note-box">
                      Automatic city exits are off. Only towns you add below will be connected.
                    </div>
                  )}

                  {/* Towns You Want Connected */}
                  <div className="hubs-box">
                    <div className="hubs-header">
                      <div>
                        <span className="hubs-title">Towns You Want Connected</span>
                        <div className="hubs-sub">Any town you add here is always given a connecting exit</div>
                      </div>
                      <button
                        type="button"
                        className={`pick-map-btn ${mapSelectionMode === "hub" ? "active" : ""}`}
                        onClick={() => {
                          const next = mapSelectionMode === "hub" ? null : "hub";
                          mapSelectionModeRef.current = next;
                          setMapSelectionMode(next);
                          if (next) setStatus("Click anywhere on the map to add a town along the route.");
                        }}
                      >
                        {mapSelectionMode === "hub" ? "Cancel" : "Click on Map"}
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
                          <div className="hubs-suggestions-label">Nearby Towns You Can Add</div>
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

                  {/* Traffic Comfort Preference */}
                  <div className="los-block">
                    <div className="planner-field-label">Traffic Comfort Preference</div>
                    <div className="los-selector">
                      <button
                        type="button"
                        className={`los-btn ${maxVcRatio === 0.70 ? "active" : ""}`}
                        onClick={() => setMaxVcRatio(0.70)}
                      >
                        <strong>Smooth &amp; Fast</strong>
                        <span>Less crowded · higher speeds</span>
                      </button>
                      <button
                        type="button"
                        className={`los-btn ${maxVcRatio === 0.85 ? "active" : ""}`}
                        onClick={() => setMaxVcRatio(0.85)}
                      >
                        <strong>Connect More Towns</strong>
                        <span>Busier traffic · more local access</span>
                      </button>
                    </div>
                  </div>

                  {/* Allowed Detour Slider */}
                  <div className="deviation-box">
                    <div className="deviation-header">
                      <span className="deviation-title">Room to Curve Around Obstacles</span>
                      <span className="deviation-badge">Up to +{maxDeviationPercent}%</span>
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
                      <span>10% Straightest</span>
                      <span>20% Balanced</span>
                      <span>30% Extra Detour</span>
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
                        <span>Finding Best Route...</span>
                      </>
                    ) : (
                      <span>Find Best Route</span>
                    )}
                  </button>

                  <div className="planner-status-line">{status}</div>

                  {routeInfo && (
                    <div className="eia-save-box">
                      <button
                        className="eia-secondary-btn"
                        type="button"
                        onClick={saveCurrentRoute}
                        disabled={!hasPendingRoute || routeSaveBusy || mode1RouteSignature !== routeSignature()}
                      >
                        {routeSaveBusy
                          ? "Saving route…"
                          : savedRouteId && mode1RouteSignature === routeSignature()
                          ? "✓ Route saved for report"
                          : "Save route for environmental report"}
                      </button>
                      {savedRouteId && (
                        <button
                          className="eia-secondary-btn"
                          type="button"
                          onClick={() => setActiveWorkspace("eia")}
                          style={{ marginTop: "6px" }}
                        >
                          Open Environmental Report Tool →
                        </button>
                      )}
                      {(routeSaveMessage || mongoStatus) && (
                        <p className="eia-help" role="status">{routeSaveMessage || mongoStatus}</p>
                      )}
                    </div>
                  )}

                  {routingError && (
                    <div className="error-card">
                      <div className="error-card-title">{routingError.title}</div>
                      <div className="error-card-message">{routingError.message}</div>
                    </div>
                  )}

                  {snapNotice && (
                    <div className="snap-alert-card">
                      <div className="snap-alert-header">
                        <span>Start / End Point Gently Moved for Safety</span>
                      </div>

                      {snapNotice.start && (
                        <div className="snap-item">
                          <div className="snap-item-title">
                            <span className="snap-origin-label">Starting Point Moved</span>
                            <span className="snap-badge">+{snapNotice.start.distanceMeters} m</span>
                          </div>
                          <div className="snap-reason">
                            <strong>Why:</strong> {snapNotice.start.reason}
                          </div>
                          <div className="snap-coords-compare">
                            <div>Original pin: <del>{snapNotice.start.requested.lat}, {snapNotice.start.requested.lng}</del></div>
                            <div>Safe spot used: <ins>{snapNotice.start.resolved.lat}, {snapNotice.start.resolved.lng}</ins></div>
                          </div>
                        </div>
                      )}

                      {snapNotice.end && (
                        <div className="snap-item">
                          <div className="snap-item-title">
                            <span className="snap-dest-label">Destination Moved</span>
                            <span className="snap-badge">+{snapNotice.end.distanceMeters} m</span>
                          </div>
                          <div className="snap-reason">
                            <strong>Why:</strong> {snapNotice.end.reason}
                          </div>
                          <div className="snap-coords-compare">
                            <div>Original pin: <del>{snapNotice.end.requested.lat}, {snapNotice.end.requested.lng}</del></div>
                            <div>Safe spot used: <ins>{snapNotice.end.resolved.lat}, {snapNotice.end.resolved.lng}</ins></div>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>

                {/* Group 4: Quick Environmental Report */}
                <section className="planner-group eia-panel">
                  <div className="planner-group-header">
                    <span className="planner-group-title">Quick Environmental Report</span>
                  </div>
                  <p className="eia-help">
                    Prepare a downloadable environmental summary using the map findings and reference library.
                    This is an early preview and should be verified by field experts.
                  </p>
                  <div className="input-group eia-field">
                    <label htmlFor="eia-project-name">Project / corridor name</label>
                    <input
                      id="eia-project-name"
                      value={eiaProjectName}
                      onChange={(event) => setEiaProjectName(event.target.value)}
                      placeholder="e.g. Kaziranga–Karbi Anglong Greenfield Highway"
                    />
                  </div>
                  <div className="input-group eia-field">
                    <label htmlFor="eia-project-proponent">Project proponent (optional)</label>
                    <input
                      id="eia-project-proponent"
                      value={eiaProjectProponent}
                      onChange={(event) => setEiaProjectProponent(event.target.value)}
                      placeholder="Organization / agency"
                    />
                  </div>
                  <div className="input-group eia-field">
                    <label htmlFor="eia-study-area">Study area</label>
                    <input
                      id="eia-study-area"
                      value={eiaStudyArea}
                      onChange={(event) => setEiaStudyArea(event.target.value)}
                    />
                  </div>
                  <div className="coord-grid eia-grid">
                    <div className="input-group eia-field">
                      <label htmlFor="eia-project-state">State / UT</label>
                      <input
                        id="eia-project-state"
                        value={eiaProjectState}
                        onChange={(event) => setEiaProjectState(event.target.value)}
                        placeholder="Assam"
                      />
                    </div>
                    <div className="input-group eia-field">
                      <label htmlFor="eia-assessment-date">Assessment date</label>
                      <input
                        id="eia-assessment-date"
                        type="date"
                        value={eiaAssessmentDate}
                        onChange={(event) => setEiaAssessmentDate(event.target.value)}
                      />
                    </div>
                  </div>
                  <div className="eia-library-summary">
                    <span>GIS observations: {mode1GisScreening ? (mode1GisScreening.findings?.length ?? 0) : "not supplied"}</span>
                    <span>Library: {eiaLibraryStatus ? `${eiaLibraryStatus.documentCount} PDFs · ${eiaLibraryStatus.chunkCount} chunks` : "not checked"}</span>
                  </div>
                  {mode1GisScreening?.findings?.length > 0 && (
                    <div className="eia-findings-preview">
                      {mode1GisScreening.findings.slice(0, 4).map((finding) => (
                        <div key={finding.evidenceId}>
                          <strong>{finding.environmentalComponent}</strong>
                          <span>{finding.metric}: {finding.value} {finding.units}</span>
                        </div>
                      ))}
                      {mode1GisScreening.findings.length > 4 && (
                        <small>+ {mode1GisScreening.findings.length - 4} additional cited GIS observations</small>
                      )}
                    </div>
                  )}
                  <button
                    className="eia-secondary-btn"
                    type="button"
                    onClick={refreshEiaLibrary}
                    disabled={eiaBusy}
                  >
                    Refresh environmental PDF library
                  </button>
                  <button
                    className="generate-btn eia-generate-btn"
                    type="button"
                    onClick={generateEnvironmentalReport}
                    disabled={eiaBusy}
                  >
                    {eiaBusy ? <><span className="spinner" /> Preparing report…</> : "Generate environmental report"}
                  </button>
                  {eiaMessage && <p className="eia-status" role="status">{eiaMessage}</p>}
                  {eiaError && <p className="eia-error" role="alert">{eiaError}</p>}
                  {eiaJob && (
                    <div className="eia-job-card">
                      <strong>Job {eiaJob.state}</strong>
                      {eiaJob.pageCount != null && <span>{eiaJob.pageCount} pages</span>}
                      <small>{eiaJob.jobId}</small>
                      {eiaJob.state === "COMPLETED" && (
                        <div className="eia-downloads">
                          <button type="button" onClick={() => downloadEiaArtifact("pdf")}>Download PDF</button>
                          <button type="button" onClick={() => downloadEiaArtifact("document")}>Download DOCX</button>
                        </div>
                      )}
                      {eiaJob.state === "FAILED" && eiaJob.downloadUrl && (
                        <div className="eia-downloads">
                          <button type="button" onClick={() => downloadEiaArtifact("document")}>Download draft DOCX</button>
                        </div>
                      )}
                    </div>
                  )}
                </section>
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

        {/* RIGHT COLLAPSIBLE DRAWER: 13 MAP IMPACT VIEWS */}
        <aside className={`studio-drawer right-drawer ${rightDrawerOpen ? "open" : "collapsed"}`}>
          {!rightDrawerOpen ? (
            <button
              type="button"
              className="drawer-collapsed-rail"
              onClick={() => setRightDrawerOpen(true)}
              title="Open 13 Safety & Impact Map Views"
            >
              <span className="rail-chevron">«</span>
              {activeLayerCfg && (
                <span
                  className="rail-active-dot"
                  style={{ background: activeLayerCfg.accentColor }}
                  title={`Active: #${activeLayerCfg.number} ${activeLayerCfg.shortName}`}
                />
              )}
              <span className="rail-label">13 Map Views</span>
            </button>
          ) : (
            <div className="drawer-inner">
              <div className="drawer-topbar">
                <div>
                  <span className="drawer-eyebrow">EXPLORE ON THE MAP</span>
                  <h2 className="drawer-title">13 Impact Views</h2>
                </div>
                <button
                  type="button"
                  className="drawer-toggle-btn"
                  onClick={() => setRightDrawerOpen(false)}
                  title="Hide 13 Impact Views to widen the map"
                  aria-label="Hide 13 Impact Views"
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
              <span className="cue-eyebrow">ROUTE READY TO EXPLORE</span>
              <span className="cue-summary">
                <strong>{routeInfo.routeLengthKm} km</strong> main highway ·{" "}
                <strong>{networkSummary?.feederSpurs ?? 0}</strong> connecting town roads ·{" "}
                <strong>{layerAudit.length || 13}</strong> safety &amp; nature checks completed
              </span>
            </div>
            <button type="button" className="cue-scroll-btn" onClick={scrollToDossier}>
              <span>Read Full Public Impact Report (6 Sections)</span>
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
          corridorName={
            PRESET_CORRIDORS.find((p) => p.id === selectedPresetId)?.name ||
            "Custom Indian Highway Route"
          }
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
