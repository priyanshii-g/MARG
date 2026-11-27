// Complete 13-Layer Registry for MARG Public Map Views & Impact Checks

export const LAYER_DOMAINS = [
  {
    id: "TERRAIN_HAZARDS",
    title: "Land, Floods & Hills",
    icon: "⛰️",
    description: "Forests & farmland, flood zones, steep hills, and landslide areas",
  },
  {
    id: "ENVIRONMENT_ECOLOGY",
    title: "Nature, Rivers & Wildlife",
    icon: "🌿",
    description: "National parks, wildlife crossings, major rivers, streams & wetlands",
  },
  {
    id: "SOCIAL_LAND",
    title: "Homes, Villages & People",
    icon: "🏘️",
    description: "Towns & villages, schools/hospitals, land width & nearby residents",
  },
  {
    id: "INFRASTRUCTURE",
    title: "Roads, Railways & Power",
    icon: "🛣️",
    description: "Highway exits, railway bridges, connecting roads & high-voltage power lines",
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
    shortName: "Land & Forest Cover",
    domain: "TERRAIN_HAZARDS",
    icon: "🛰️",
    accentColor: "#22c55e",
    preferredBasemap: "satellite",
    profileField: "lulc",
    vectorFeatureRoles: ["MAINLINE_ROW_CORRIDOR"],
    description: "Guides the road onto open or grazing land so fewer forests, farms, and built-up areas are disturbed.",
    legend: [
      { key: "BARREN_WASTELAND", label: "Open / Barren Land", color: "#94a3b8" },
      { key: "GRASS_GRAZING", label: "Grassland", color: "#84cc16" },
      { key: "AGRICULTURE", label: "Farmland", color: "#eab308" },
      { key: "FOREST", label: "Forest & Trees", color: "#15803d" },
      { key: "WETLAND_WATER", label: "Water / Wetland", color: "#0284c7" },
      { key: "BUILT_UP", label: "Town / City Area", color: "#ef4444" },
    ],
  },
  {
    id: "L2_FLOOD",
    number: 2,
    shortName: "Flood Risk Zones",
    domain: "TERRAIN_HAZARDS",
    icon: "🌊",
    accentColor: "#3b82f6",
    preferredBasemap: "voyager",
    profileField: "flood",
    vectorFeatureRoles: ["CORRIDOR_RIVER_CHANNEL"],
    description: "Keeps the road on higher, drier ground to reduce monsoon flooding and road washouts.",
    legend: [
      { key: "NOT_IN_HAZARD_LAYER", label: "Safe from Flooding", color: "#10b981" },
      { key: "VERY_LOW", label: "Very Low Risk", color: "#34d399" },
      { key: "LOW", label: "Low Risk", color: "#38bdf8" },
      { key: "MODERATE", label: "Moderate Flood Risk", color: "#f59e0b" },
      { key: "HIGH", label: "High Flood Risk", color: "#f97316" },
      { key: "VERY_HIGH", label: "Severe Flood Risk", color: "#dc2626" },
    ],
  },
  {
    id: "L3_SLOPE",
    number: 3,
    shortName: "Hills & Steep Slopes",
    domain: "TERRAIN_HAZARDS",
    icon: "⛰️",
    accentColor: "#f59e0b",
    preferredBasemap: "topo",
    profileField: "slopeBand",
    hasSlopeSparkline: true,
    vectorFeatureRoles: [],
    description: "Keeps the road on gentler slopes for safer driving and blocks cliffs steeper than 30°.",
    legend: [
      { key: "SLOPE_FREE", label: "Flat (0–5°)", color: "#10b981" },
      { key: "SLOPE_LOW", label: "Gentle (5–10°)", color: "#84cc16" },
      { key: "SLOPE_MODERATE", label: "Rolling Hills (10–15°)", color: "#eab308" },
      { key: "SLOPE_STEEP", label: "Steep (15–22°)", color: "#f97316" },
      { key: "SLOPE_VERY_STEEP", label: "Very Steep (22–30°)", color: "#ef4444" },
      { key: "SLOPE_OVER_LIMIT", label: "Unsafe Cliff (>30°)", color: "#991b1b" },
    ],
  },
  {
    id: "L4_PROTECTED_AREAS",
    number: 4,
    shortName: "National Parks & Sanctuaries",
    domain: "ENVIRONMENT_ECOLOGY",
    icon: "🛡️",
    accentColor: "#dc2626",
    preferredBasemap: "osm",
    profileField: null,
    vectorFeatureRoles: ["WILDLIFE_SANCTUARY_BOUNDARY"],
    description: "Strictly keeps the highway outside India's National Parks and Wildlife Sanctuaries.",
    legend: [
      { key: "SANCTUARY", label: "Protected Park / Sanctuary (Off-Limits)", color: "#dc2626" },
      { key: "MAINLINE", label: "Safe Bypass Route", color: "#10b981" },
    ],
  },
  {
    id: "L5_SETTLEMENTS",
    number: 5,
    shortName: "Towns & Villages",
    domain: "SOCIAL_LAND",
    icon: "🏘️",
    accentColor: "#f43f5e",
    preferredBasemap: "satellite",
    profileField: "settlement",
    vectorFeatureRoles: ["SETTLEMENT_BUILDING_CONFLICT_ZONE"],
    description: "Curves the highway around busy town centers and villages to protect homes and reduce traffic noise.",
    legend: [
      { key: "NONE", label: "Open Countryside", color: "#10b981" },
      { key: "OUTER_BUFFER", label: "Village Outskirts", color: "#eab308" },
      { key: "INNER_BUFFER", label: "Close to Homes", color: "#f97316" },
      { key: "URBAN_CORE", label: "Town Center (Avoided)", color: "#dc2626" },
    ],
  },
  {
    id: "L6_TRANSPORT",
    number: 6,
    shortName: "Roads, Railways & Exits",
    domain: "INFRASTRUCTURE",
    icon: "🛣️",
    accentColor: "#a855f7",
    preferredBasemap: "osm",
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
    description: "Plans smooth highway exits, connecting roads to nearby towns, and flyovers over railways and busy roads.",
    legend: [
      { key: "TERMINAL_INTERCHANGE", label: "Highway Exit / Interchange", color: "#f59e0b" },
      { key: "DEDICATED_FEEDER_SPUR", label: "Connecting Link Road", color: "#a855f7" },
      { key: "GRADE_SEPARATED_CROSSING", label: "Railway Bridge / Road Flyover", color: "#64748b" },
    ],
  },
  {
    id: "L7_HYDROLOGY",
    number: 7,
    shortName: "Major Rivers & Bridges",
    domain: "ENVIRONMENT_ECOLOGY",
    icon: "🌉",
    accentColor: "#06b6d4",
    preferredBasemap: "relief",
    profileField: null,
    vectorFeatureRoles: ["RIVER_BRIDGE", "CORRIDOR_RIVER_CHANNEL"],
    description: "Crosses major rivers as straight-across as possible so bridges are shorter, safer, and better for river flow.",
    legend: [
      { key: "CORRIDOR_RIVER_CHANNEL", label: "Major River", color: "#0284c7" },
      { key: "RIVER_BRIDGE", label: "Planned River Bridge", color: "#06b6d4" },
    ],
  },
  {
    id: "L8_WILDLIFE_ESZ",
    number: 8,
    shortName: "Wildlife Crossings & Eco-Zones",
    domain: "ENVIRONMENT_ECOLOGY",
    icon: "🐘",
    accentColor: "#16a34a",
    preferredBasemap: "topo",
    profileField: null,
    vectorFeatureRoles: ["ECO_SENSITIVE_ZONE_BOUNDARY", "WILDLIFE_PASSAGE"],
    description: "Respects 10 km eco-sensitive buffers around parks and adds elevated wildlife bridges where animals migrate.",
    legend: [
      { key: "ECO_SENSITIVE_ZONE_BOUNDARY", label: "10 km Eco-Sensitive Buffer", color: "#f59e0b" },
      { key: "WILDLIFE_PASSAGE", label: "Elevated Wildlife Crossing", color: "#15803d" },
    ],
  },
  {
    id: "L9_GEOTECH",
    number: 9,
    shortName: "Soil Stability & Landslides",
    domain: "TERRAIN_HAZARDS",
    icon: "🪨",
    accentColor: "#d97706",
    preferredBasemap: "topo",
    profileField: "geotech",
    vectorFeatureRoles: ["GEOTECH_MITIGATION_ZONE"],
    description: "Steers clear of landslide-prone hillsides and weak marshy soil, adding retaining walls where slopes need support.",
    legend: [
      { key: "COMPETENT_BEDROCK_STABLE_SUBGRADE", label: "Solid, Stable Ground", color: "#10b981" },
      { key: "WEATHERED_RESIDUAL_MODERATE_SOIL", label: "Normal Soil", color: "#84cc16" },
      { key: "SOFT_ALLUVIAL_COMPRESSIBLE_CLAY", label: "Soft Clay Soil", color: "#eab308" },
      { key: "LIQUEFIABLE_ORGANIC_MARSH_SUBGRADE", label: "Marshy Ground", color: "#f59e0b" },
      { key: "MODERATE_COLLUVIAL_LANDSLIDE_SUSCEPTIBILITY", label: "Moderate Landslide Risk", color: "#f97316" },
      { key: "HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP", label: "High Landslide Risk", color: "#ef4444" },
      { key: "ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE", label: "Active Landslide Zone", color: "#991b1b" },
    ],
  },
  {
    id: "L10_UTILITIES",
    number: 10,
    shortName: "High-Voltage Power Lines",
    domain: "INFRASTRUCTURE",
    icon: "⚡",
    accentColor: "#eab308",
    preferredBasemap: "osm",
    profileField: null,
    vectorFeatureRoles: ["UTILITY_CROSSING_PROTECTION"],
    description: "Stays away from electrical substations and towers, adding protective guards where the road crosses high-voltage lines.",
    legend: [
      { key: "UTILITY_CROSSING_PROTECTION", label: "Power-Line Safety Crossing", color: "#eab308" },
    ],
  },
  {
    id: "L11_BUILDINGS_ROW",
    number: 11,
    shortName: "Homes, Schools & Land Width",
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
    description: "Strictly avoids hospitals, schools, and places of worship while checking how many buildings fall inside the road width.",
    legend: [
      { key: "MAINLINE_ROW_CORRIDOR", label: "Land Strip Needed for Road", color: "#10b981" },
      { key: "SETTLEMENT_BUILDING_CONFLICT_ZONE", label: "Nearby Buildings / Homes", color: "#ec4899" },
    ],
  },
  {
    id: "L12_DEMOGRAPHICS",
    number: 12,
    shortName: "Nearby Communities & People",
    domain: "SOCIAL_LAND",
    icon: "👥",
    accentColor: "#8b5cf6",
    preferredBasemap: "osm",
    profileField: "population",
    vectorFeatureRoles: ["DEMOGRAPHIC_EXPOSURE_MITIGATION_ZONE"],
    description: "Counts residents living within 250 m and 500 m of the route to minimize displacement and protect vulnerable communities.",
    legend: [
      { key: "UNINHABITED_OR_BACKGROUND", label: "Open Land (No Homes)", color: "#10b981" },
      { key: "SPARSE_RURAL_DISPERSED", label: "Scattered Rural Homes", color: "#84cc16" },
      { key: "LOW_DENSITY_VILLAGE", label: "Small Village", color: "#eab308" },
      { key: "MODERATE_DENSITY_SETTLEMENT", label: "Growing Town", color: "#f97316" },
      { key: "HIGH_DENSITY_POPULATION_CLUSTER", label: "Crowded Urban Area", color: "#dc2626" },
      { key: "DEMOGRAPHIC_EXPOSURE_MITIGATION_ZONE", label: "Monitored Neighborhood", color: "#8b5cf6" },
    ],
  },
  {
    id: "L13_DRAINAGE_WETLANDS",
    number: 13,
    shortName: "Streams, Canals & Wetlands",
    domain: "ENVIRONMENT_ECOLOGY",
    icon: "💧",
    accentColor: "#0891b2",
    preferredBasemap: "satellite",
    profileField: "drainage",
    vectorFeatureRoles: ["DRAINAGE_CROSSING_STRUCTURE", "WETLAND_HYDROLOGIC_MITIGATION_ZONE"],
    description: "Protects natural wetlands and adds drainage tunnels under the road so monsoon rainwater flows naturally.",
    legend: [
      { key: "NONE", label: "Dry Ground", color: "#10b981" },
      { key: "SEASONAL_STREAM_CHANNEL", label: "Stream or Canal", color: "#06b6d4" },
      { key: "WETLAND_BUFFER", label: "Wetland Buffer", color: "#0284c7" },
      { key: "DRAINAGE_CROSSING_STRUCTURE", label: "Water Drainage Culvert", color: "#0891b2" },
      { key: "WETLAND_HYDROLOGIC_MITIGATION_ZONE", label: "Protected Wetland Zone", color: "#14b8a6" },
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
  if (!raw) return "Not Specified";
  return String(raw)
    .replace(/^SLOPE_/, "")
    .replace(/_/g, " ")
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
