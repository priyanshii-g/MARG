// Complete 13-Layer Registry for MARG Interactive Lens & Evaluator Verification

export const LAYER_DOMAINS = [
  {
    id: "TERRAIN_HAZARDS",
    title: "Terrain & Natural Hazards",
    icon: "⛰️",
    description: "LULC, Floodplains, DEM Slope & Geotechnical Landslides",
  },
  {
    id: "ENVIRONMENT_ECOLOGY",
    title: "Environment & Ecology",
    icon: "🌿",
    description: "Protected Areas, Wildlife/ESZ, Hydrology & Wetlands",
  },
  {
    id: "SOCIAL_LAND",
    title: "Social & Land Acquisition",
    icon: "🏘️",
    description: "Settlements, Building Footprints/2D RoW & Demographics",
  },
  {
    id: "INFRASTRUCTURE",
    title: "Infrastructure & Connectivity",
    icon: "🛣️",
    description: "Transport Terminals, Interchanges & EHV Utilities",
  },
];

// Overview Mode shows proposed road structures only (no red sanctuaries or environmental context halos)
export const OVERVIEW_FEATURE_ROLES = new Set([
  "MAINLINE",
  "GREENFIELD_MAINLINE",
  "DISTANCE_BASELINE",
  "BASELINE",
  "DEDICATED_FEEDER_SPUR",
  "FEEDER_SPUR_HIGHWAY",
  "INTERCHANGE",
  "TERMINAL_INTERCHANGE",
  "INTERCHANGE_JUNCTION",
  "REGIONAL_SERVICE_HUB",
  "TERMINAL_ACCESS_ROAD",
  "ROAD_TIE_IN",
  "RIVER_BRIDGE",
  "GRADE_SEPARATED_CROSSING",
  "MAINLINE_ROW_CORRIDOR",
  "FEEDER_SPUR_ROW_CORRIDOR",
]);

export const LAYER_CATALOG = [
  {
    id: "L1_LULC",
    number: 1,
    shortName: "Land Use / Land Cover",
    domain: "TERRAIN_HAZARDS",
    icon: "🛰️",
    accentColor: "#22c55e",
    preferredBasemap: "satellite",
    profileField: "lulc",
    vectorFeatureRoles: ["MAINLINE_ROW_CORRIDOR"],
    description: "Steers alignment onto barren/grazing/low-conflict land while minimizing forest diversion & built-up land take.",
    legend: [
      { key: "BARREN_WASTELAND", label: "Barren / Scrub", color: "#94a3b8" },
      { key: "GRASS_GRAZING", label: "Grass / Grazing", color: "#84cc16" },
      { key: "AGRICULTURE", label: "Agriculture", color: "#eab308" },
      { key: "FOREST", label: "Forest Cover", color: "#15803d" },
      { key: "WETLAND_WATER", label: "Wetland / Water", color: "#0284c7" },
      { key: "BUILT_UP", label: "Built-up Urban", color: "#ef4444" },
    ],
  },
  {
    id: "L2_FLOOD",
    number: 2,
    shortName: "Flood Hazard & Inundation",
    domain: "TERRAIN_HAZARDS",
    icon: "🌊",
    accentColor: "#3b82f6",
    preferredBasemap: "voyager",
    profileField: "flood",
    vectorFeatureRoles: ["CORRIDOR_RIVER_CHANNEL"],
    description: "Routes along elevated natural levees and low-inundation zones using NRSC Flood Hazard Atlas data.",
    legend: [
      { key: "NOT_IN_HAZARD_LAYER", label: "Outside Flood Zone", color: "#10b981" },
      { key: "VERY_LOW", label: "Very Low Risk", color: "#34d399" },
      { key: "LOW", label: "Low Risk", color: "#38bdf8" },
      { key: "MODERATE", label: "Moderate Flood", color: "#f59e0b" },
      { key: "HIGH", label: "High Flood Risk", color: "#f97316" },
      { key: "VERY_HIGH", label: "Very High Inundation", color: "#dc2626" },
    ],
  },
  {
    id: "L3_SLOPE",
    number: 3,
    shortName: "Terrain & Slope (DEM)",
    domain: "TERRAIN_HAZARDS",
    icon: "⛰️",
    accentColor: "#f59e0b",
    preferredBasemap: "topo",
    profileField: "slopeBand",
    hasSlopeSparkline: true,
    vectorFeatureRoles: [],
    description: "Enforces IRC ruling gradients and hard-blocks >30° cliffs using DEM elevation & slope surfaces.",
    legend: [
      { key: "SLOPE_FREE", label: "Flat (0–5°)", color: "#10b981" },
      { key: "SLOPE_LOW", label: "Gentle (5–10°)", color: "#84cc16" },
      { key: "SLOPE_MODERATE", label: "Rolling (10–15°)", color: "#eab308" },
      { key: "SLOPE_STEEP", label: "Steep (15–22°)", color: "#f97316" },
      { key: "SLOPE_VERY_STEEP", label: "Very Steep (22–30°)", color: "#ef4444" },
      { key: "SLOPE_OVER_LIMIT", label: ">30° Limit", color: "#991b1b" },
    ],
  },
  {
    id: "L4_PROTECTED_AREAS",
    number: 4,
    shortName: "Protected Areas (WPA 1972)",
    domain: "ENVIRONMENT_ECOLOGY",
    icon: "🛡️",
    accentColor: "#dc2626",
    preferredBasemap: "satellite",
    profileField: null,
    vectorFeatureRoles: ["WILDLIFE_SANCTUARY_BOUNDARY"],
    description: "Zero-tolerance hard exclusion around National Parks & Wildlife Sanctuaries under Wildlife (Protection) Act, 1972.",
    legend: [
      { key: "SANCTUARY", label: "Notified Sanctuary Core (Hard-Blocked)", color: "#dc2626" },
      { key: "MAINLINE", label: "Bypassed MARG Mainline", color: "#10b981" },
    ],
  },
  {
    id: "L5_SETTLEMENTS",
    number: 5,
    shortName: "Settlement Exposure",
    domain: "SOCIAL_LAND",
    icon: "🏘️",
    accentColor: "#f43f5e",
    preferredBasemap: "satellite",
    profileField: "settlement",
    vectorFeatureRoles: ["SETTLEMENT_BUILDING_CONFLICT_ZONE"],
    description: "Deflects greenfield alignment away from municipal urban cores and peri-urban habitation buffers.",
    legend: [
      { key: "NONE", label: "Open Greenfield", color: "#10b981" },
      { key: "OUTER_BUFFER", label: "Outer Settlement Buffer", color: "#eab308" },
      { key: "INNER_BUFFER", label: "Inner Habitation Buffer", color: "#f97316" },
      { key: "URBAN_CORE", label: "Urban Core (Avoided)", color: "#dc2626" },
    ],
  },
  {
    id: "L6_TRANSPORT",
    number: 6,
    shortName: "Transport & Terminals",
    domain: "INFRASTRUCTURE",
    icon: "🛣️",
    accentColor: "#a855f7",
    preferredBasemap: "positron",
    profileField: null,
    vectorFeatureRoles: [
      "INTERCHANGE",
      "TERMINAL_INTERCHANGE",
      "INTERCHANGE_JUNCTION",
      "REGIONAL_SERVICE_HUB",
      "DEDICATED_FEEDER_SPUR",
      "FEEDER_SPUR_HIGHWAY",
      "TERMINAL_ACCESS_ROAD",
      "ROAD_TIE_IN",
      "GRADE_SEPARATED_CROSSING",
    ],
    description: "IRC:SP:99 access-controlled terminal interchanges on NH/SH (zero local streets), feeder spurs & rail ROBs.",
    legend: [
      { key: "TERMINAL_INTERCHANGE", label: "Terminal / Hub Interchange", color: "#f59e0b" },
      { key: "DEDICATED_FEEDER_SPUR", label: "Feeder Spur / Connector", color: "#a855f7" },
      { key: "GRADE_SEPARATED_CROSSING", label: "Rail ROB / Highway Flyover", color: "#64748b" },
    ],
  },
  {
    id: "L7_HYDROLOGY",
    number: 7,
    shortName: "Hydrology & Major Rivers",
    domain: "ENVIRONMENT_ECOLOGY",
    icon: "🌉",
    accentColor: "#06b6d4",
    preferredBasemap: "relief",
    profileField: null,
    vectorFeatureRoles: ["RIVER_BRIDGE", "CORRIDOR_RIVER_CHANNEL"],
    description: "Optimizes major/medium river crossings toward orthogonal (75°–90°) bridge angles to cut hydraulic span & cost.",
    legend: [
      { key: "CORRIDOR_RIVER_CHANNEL", label: "Perennial River Channel", color: "#0284c7" },
      { key: "RIVER_BRIDGE", label: "Engineered River Bridge", color: "#06b6d4" },
    ],
  },
  {
    id: "L8_WILDLIFE_ESZ",
    number: 8,
    shortName: "Wildlife Corridors & ESZ",
    domain: "ENVIRONMENT_ECOLOGY",
    icon: "🐘",
    accentColor: "#16a34a",
    preferredBasemap: "topo",
    profileField: null,
    vectorFeatureRoles: ["ECO_SENSITIVE_ZONE_BOUNDARY", "WILDLIFE_PASSAGE"],
    description: "Buffers 10 km Eco-Sensitive Zones and engineers WII-compliant elevated viaducts & animal underpasses.",
    legend: [
      { key: "ECO_SENSITIVE_ZONE_BOUNDARY", label: "10 km Eco-Sensitive Zone (ESZ)", color: "#f59e0b" },
      { key: "WILDLIFE_PASSAGE", label: "Elevated Wildlife Viaduct / Underpass", color: "#15803d" },
    ],
  },
  {
    id: "L9_GEOTECH",
    number: 9,
    shortName: "Geotech, Soil & Landslides",
    domain: "TERRAIN_HAZARDS",
    icon: "🪨",
    accentColor: "#d97706",
    preferredBasemap: "topo",
    profileField: "geotech",
    vectorFeatureRoles: ["GEOTECH_MITIGATION_ZONE"],
    description: "Avoids active landslide shear cores, fault lineaments & liquefiable clay troughs; specifies slope stabilization.",
    legend: [
      { key: "COMPETENT_BEDROCK_STABLE_SUBGRADE", label: "Competent Bedrock", color: "#10b981" },
      { key: "WEATHERED_RESIDUAL_MODERATE_SOIL", label: "Moderate Soil", color: "#84cc16" },
      { key: "SOFT_ALLUVIAL_COMPRESSIBLE_CLAY", label: "Soft Alluvial Clay", color: "#eab308" },
      { key: "LIQUEFIABLE_ORGANIC_MARSH_SUBGRADE", label: "Liquefiable Marsh", color: "#f59e0b" },
      { key: "MODERATE_COLLUVIAL_LANDSLIDE_SUSCEPTIBILITY", label: "Colluvial Slide Zone", color: "#f97316" },
      { key: "HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP", label: "High Landslide Scarp", color: "#ef4444" },
      { key: "ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE", label: "Active Fault Shear", color: "#991b1b" },
    ],
  },
  {
    id: "L10_UTILITIES",
    number: 10,
    shortName: "High-Voltage Utilities",
    domain: "INFRASTRUCTURE",
    icon: "⚡",
    accentColor: "#eab308",
    preferredBasemap: "positron",
    profileField: null,
    vectorFeatureRoles: ["UTILITY_CROSSING_PROTECTION"],
    description: "Hard-excludes EHV substations & transmission towers; protects 400kV/220kV overhead crossings per CEA norms.",
    legend: [
      { key: "UTILITY_CROSSING_PROTECTION", label: "EHV Transmission Crossing Protection", color: "#eab308" },
    ],
  },
  {
    id: "L11_BUILDINGS_ROW",
    number: 11,
    shortName: "Building Footprints & 2D RoW",
    domain: "SOCIAL_LAND",
    icon: "🏢",
    accentColor: "#ec4899",
    preferredBasemap: "satellite",
    profileField: null,
    vectorFeatureRoles: [
      "MAINLINE_ROW_CORRIDOR",
      "FEEDER_SPUR_ROW_CORRIDOR",
      "SETTLEMENT_BUILDING_CONFLICT_ZONE",
    ],
    description: "Zero-tolerance exclusion of hospitals, schools & temples; evaluates exact 60m/45m 2D RoW building clearance.",
    legend: [
      { key: "MAINLINE_ROW_CORRIDOR", label: "2D Right-of-Way Acquisition Swath", color: "#10b981" },
      { key: "SETTLEMENT_BUILDING_CONFLICT_ZONE", label: "Building / Settlement Conflict Zone", color: "#ec4899" },
    ],
  },
  {
    id: "L12_DEMOGRAPHICS",
    number: 12,
    shortName: "Population & Demographics",
    domain: "SOCIAL_LAND",
    icon: "👥",
    accentColor: "#8b5cf6",
    preferredBasemap: "positron",
    profileField: "population",
    vectorFeatureRoles: ["DEMOGRAPHIC_EXPOSURE_MITIGATION_ZONE"],
    description: "Minimizes 250m/500m residential displacement, vulnerable-age & ST/SC exposure under RFCTLARR 2013 SIA.",
    legend: [
      { key: "UNINHABITED_OR_BACKGROUND", label: "Uninhabited", color: "#10b981" },
      { key: "SPARSE_RURAL_DISPERSED", label: "Sparse Rural", color: "#84cc16" },
      { key: "LOW_DENSITY_VILLAGE", label: "Low-Density Village", color: "#eab308" },
      { key: "MODERATE_DENSITY_SETTLEMENT", label: "Moderate Settlement", color: "#f97316" },
      { key: "HIGH_DENSITY_POPULATION_CLUSTER", label: "High-Density Cluster", color: "#dc2626" },
      { key: "DEMOGRAPHIC_EXPOSURE_MITIGATION_ZONE", label: "Monitored Demographic Tract", color: "#8b5cf6" },
    ],
  },
  {
    id: "L13_DRAINAGE_WETLANDS",
    number: 13,
    shortName: "Micro-Drainage & Wetlands",
    domain: "ENVIRONMENT_ECOLOGY",
    icon: "💧",
    accentColor: "#0891b2",
    preferredBasemap: "satellite",
    profileField: "drainage",
    vectorFeatureRoles: ["DRAINAGE_CROSSING_STRUCTURE", "WETLAND_HYDROLOGIC_MITIGATION_ZONE"],
    description: "Protects wetlands under Wetlands Rules 2017 and sizes IRC:SP:13 culverts/minor bridges across watersheds.",
    legend: [
      { key: "NONE", label: "Upland / Clear", color: "#10b981" },
      { key: "SEASONAL_STREAM_CHANNEL", label: "Seasonal Stream / Canal", color: "#06b6d4" },
      { key: "WETLAND_BUFFER", label: "Wetland Buffer", color: "#0284c7" },
      { key: "DRAINAGE_CROSSING_STRUCTURE", label: "Box Culvert / Aqueduct", color: "#0891b2" },
      { key: "WETLAND_HYDROLOGIC_MITIGATION_ZONE", label: "Wetland Mitigation Zone", color: "#14b8a6" },
    ],
  },
];

export const LAYER_BY_ID = Object.fromEntries(LAYER_CATALOG.map((l) => [l.id, l]));

export function getSegmentColor(layerConfig, rawValue) {
  if (!layerConfig || !layerConfig.legend) return "#10b981";
  const key = String(rawValue || "").toUpperCase();
  const exact = layerConfig.legend.find((item) => item.key === key);
  if (exact) return exact.color;
  // Partial fallbacks for drainage/geotech variants
  if (key.includes("WETLAND")) return "#0284c7";
  if (key.includes("STREAM") || key.includes("CANAL") || key.includes("CHANNEL") || key.includes("THALWEG")) return "#06b6d4";
  if (key.includes("HIGH") || key.includes("SEVERE") || key.includes("SHEAR")) return "#ef4444";
  if (key.includes("MODERATE")) return "#f59e0b";
  if (key.includes("LOW") || key.includes("SPARSE")) return "#84cc16";
  return "#10b981";
}

export function formatClassLabel(raw) {
  if (!raw) return "Unknown";
  return String(raw)
    .replace(/^SLOPE_/, "")
    .replace(/_/g, " ")
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
