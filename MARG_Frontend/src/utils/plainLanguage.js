// Plain-language formatting & translation helpers for MARG Public Information UI.
// Converts backend engineering codes, statutory acronyms, and technical phrases into
// clear, everyday language that any citizen, journalist, or policymaker can read easily.

const CLASS_LABEL_MAP = {
  // Land Use & Land Cover (L1)
  BARREN_WASTELAND: "Open / Barren Land",
  GRASS_GRAZING: "Grassland & Grazing Land",
  AGRICULTURE: "Farmland",
  FOREST: "Forest & Tree Cover",
  WETLAND_WATER: "Wetlands & Water Bodies",
  BUILT_UP: "Built-up Town / City Area",
  CORRIDOR_TRAVERSED: "Route Distance",

  // Flood Risk (L2)
  NOT_IN_HAZARD_LAYER: "Safe from Flooding",
  VERY_LOW: "Very Low Flood Risk",
  LOW: "Low Flood Risk",
  MODERATE: "Moderate Flood Risk",
  HIGH: "High Flood Risk",
  VERY_HIGH: "Severe Flood Risk",
  FLOODPLAIN_RISK: "Flood-Prone Area",
  LOW_RISK: "Low Flood Risk Area",

  // Terrain & Hills (L3)
  SLOPE_FREE: "Flat Ground (0–5°)",
  SLOPE_LOW: "Gentle Slope (5–10°)",
  SLOPE_MODERATE: "Rolling Hills (10–15°)",
  SLOPE_STEEP: "Steep Hills (15–22°)",
  SLOPE_VERY_STEEP: "Very Steep Slope (22–30°)",
  SLOPE_OVER_LIMIT: "Too Steep for Safe Roads (>30°)",
  STEEP_TERRAIN: "Hilly / Steep Ground",
  PLAIN_ROLLING: "Flat & Gentle Ground",

  // Protected Areas (L4)
  PROTECTED_AREA_CORE: "Inside Protected Park",
  OUTSIDE_PROTECTED_AREAS: "Safely Outside Parks",
  SANCTUARY_CORE: "Inside Sanctuary",
  OUTSIDE_SANCTUARIES: "Safely Outside Sanctuaries",

  // Settlements (L5)
  NONE: "Open Countryside",
  OUTER_BUFFER: "Near Village Outskirts",
  INNER_BUFFER: "Close to Homes",
  URBAN_CORE: "Dense City / Town Center",
  PERI_URBAN: "Near Towns & Suburbs",
  GREENFIELD_RURAL: "Open Rural Land",

  // Soil & Landslides (L9)
  COMPETENT_BEDROCK_STABLE_SUBGRADE: "Solid, Stable Ground",
  WEATHERED_RESIDUAL_MODERATE_SOIL: "Normal Soil",
  SOFT_ALLUVIAL_COMPRESSIBLE_CLAY: "Soft Clay Soil",
  LIQUEFIABLE_ORGANIC_MARSH_SUBGRADE: "Marshy / Soft Ground",
  MODERATE_COLLUVIAL_LANDSLIDE_SUSCEPTIBILITY: "Moderate Landslide Risk",
  HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP: "High Landslide Risk Slope",
  ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE: "Active Landslide Zone",
  SOFT_SUBGRADE_OR_SCARP: "Soft Soil or Steep Slope",
  STABLE_SUBGRADE: "Stable Ground",

  // Population & Communities (L12)
  UNINHABITED_OR_BACKGROUND: "Open Land (No Homes)",
  SPARSE_RURAL_DISPERSED: "Scattered Rural Homes",
  LOW_DENSITY_VILLAGE: "Small Village Area",
  MODERATE_DENSITY_SETTLEMENT: "Growing Town Area",
  HIGH_DENSITY_POPULATION_CLUSTER: "Crowded Urban Area",
  PERI_URBAN_KM: "Near Towns",
  LOW_DENSITY_KM: "Low-Population Countryside",

  // Streams & Wetlands (L13)
  SEASONAL_STREAM_CHANNEL: "Seasonal Stream or Canal",
  WETLAND_BUFFER: "Near Wetland",
  DRAINAGE_EXPOSURE_KM: "Stream & Wetland Crossings",
  UPLAND_KM: "Dry Ground",

  // Transport, Roads & Structures (L6–L11)
  EXPRESSWAY: "Expressway",
  NATIONAL_HIGHWAY: "National Highway",
  STATE_HIGHWAY: "State Highway",
  MAJOR_DISTRICT_ROAD: "Major District Road",
  ARTERIAL: "Main Connecting Road",
  RAILWAY: "Railway Line",
  HIGHWAY: "Highway",
  PERENNIAL: "Year-Round River",
  TRUMPET: "Trumpet Highway Exit",
  DIAMOND: "Diamond Highway Exit",
  CLOVERLEAF: "Cloverleaf Interchange",
  GRADE_SEPARATED: "Flyover / Overpass",
  OVERPASS: "Road Overpass",
  UNDERPASS: "Road Underpass",
  ROB: "Railway Overbridge",
  RAILWAY_OVER_BRIDGE: "Railway Overbridge",
  ELEVATED_VIADUCT: "Elevated Wildlife Bridge",
  ELEVATED_VIADUCT_STRUCTURE: "Elevated Wildlife Bridge",
  WILDLIFE_UNDERPASS: "Wildlife Underpass",
  BOX_CULVERT: "Water Drainage Tunnel (Box Culvert)",
  GUARD_CRADLE: "Overhead Power-Line Safety Guard",
  ANCHORED_RETAINING_WALL: "Reinforced Hill Support Wall",
  DIRECT_ROW_HA: "Total Road Land Area",
  MAINLINE_KM: "Main Route Length",
  CORRIDOR_KM: "Route Length",
  CLEAR_CORRIDOR: "Clear of Power Towers",
  BRIDGES_ENGINEERED: "Planned River Bridges",

  // Traffic Levels of Service
  LOS_A_FREE_FLOW: "Very Smooth Traffic",
  LOS_B_REASONABLY_FREE_FLOW: "Smooth, Fast Traffic",
  LOS_C_STABLE_FLOW: "Steady Traffic",
  LOS_D_APPROACHING_UNSTABLE: "Busy Traffic",
  LOS_E_OVER_CAPACITY: "Heavy / Crowded Traffic",

  // Hub Evaluation Status Codes
  USER_IMMUTABLE_OVERRIDE: "Your Selected Town",
  AUTONOMOUS_HVI_SELECTED: "Auto-Connected City",
  AUTONOMOUS_HUB_DISABLED: "Auto-Connect Off",
  AUTONOMOUS_CAP_REACHED: "City Limit Reached",
  TERMINAL_ENDPOINT_EXCLUDED: "Near Start or End",
  OUTSIDE_CORRIDOR_BUFFER: "Too Far from Route",
  BELOW_MODE_POPULATION_THRESHOLD: "Smaller Town",
  CAPACITY_VC_GATE_REJECTED: "Skipped (Traffic Limit)",
  SPACING_GATE_REJECTED: "Skipped (Too Close to Exit)",
};

export function humanizeClassLabel(raw) {
  if (!raw) return "Not Specified";
  const key = String(raw).trim().toUpperCase().replace(/[\s-]+/g, "_");
  if (CLASS_LABEL_MAP[key]) return CLASS_LABEL_MAP[key];

  // Partial fallbacks for descriptive backend codes
  if (key.includes("TRUMPET")) return "Trumpet Highway Exit";
  if (key.includes("BARRIER_TOLL") || key.includes("TOLL_PLAZA")) return "Main Toll Plaza";
  if (key.includes("HYDROLOGIC_CULVERT") || key.includes("CULVERT")) return "Water Flow Protection Culvert";
  if (key.includes("ACOUSTIC")) return "Noise Barrier & Safe Crossing";
  if (key.includes("RFCTLARR")) return "Fair Compensation & Relocation Support";
  if (key.includes("RETAINING_WALL")) return "Hillside Retaining Wall";
  if (key.includes("VIADUCT")) return "Elevated Bridge";

  return String(raw)
    .replace(/^SLOPE_/, "")
    .replace(/_/g, " ")
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

export function humanizeUnit(unit) {
  if (!unit) return "";
  const u = String(unit).trim().toLowerCase();
  if (u === "km-eq") return "impact pts";
  if (u === "pts") return "risk pts";
  if (u === "structures") return "buildings";
  if (u === "ha") return "hectares";
  if (u === "m") return "m";
  return unit;
}

export function humanizeDataFidelity(fidelity) {
  switch (fidelity) {
    case "FULL_RASTER_AND_VECTOR":
      return "High-Detail Local Maps";
    case "DYNAMIC_OSM_SRTM_PROXY":
      return "All-India Satellite & Road Data";
    default:
      return "Regional Map Data";
  }
}

export function humanizeTrafficFlow(vcRatio, rawLos) {
  const num = Number(vcRatio);
  if (Number.isFinite(num) && num > 0) {
    const pct = Math.round(num * 100);
    if (num <= 0.40) return { label: "Very Smooth", detail: `${pct}% of road capacity used` };
    if (num <= 0.65) return { label: "Smooth Flow", detail: `${pct}% of road capacity used` };
    if (num <= 0.80) return { label: "Steady Flow", detail: `${pct}% of road capacity used` };
    if (num <= 0.95) return { label: "Busy Traffic", detail: `${pct}% of road capacity used` };
    return { label: "Near Full Capacity", detail: `${pct}% of road capacity used` };
  }
  if (rawLos) {
    return { label: humanizeClassLabel(rawLos), detail: "Estimated peak-hour flow" };
  }
  return { label: "Smooth Flow", detail: "Estimated peak-hour flow" };
}

export function humanizeLayerPurpose(layerId) {
  switch (layerId) {
    case "L1_LULC":
      return "Prefers open/barren land to keep farms, forests, and towns undisturbed.";
    case "L2_FLOOD":
      return "Uses flood maps to keep the highway on higher, drier ground.";
    case "L3_SLOPE":
      return "Keeps hill slopes gentle for safer driving and avoids steep cliffs over 30°.";
    case "L4_PROTECTED_AREAS":
      return "Follows India's Wildlife Protection Law — national parks and sanctuaries are off-limits.";
    case "L5_SETTLEMENTS":
      return "Curves around busy towns and villages to avoid noise and tearing down homes.";
    case "L6_TRANSPORT":
      return "Connects cleanly to major highways with safe flyovers over railways and roads.";
    case "L7_HYDROLOGY":
      return "Crosses major rivers straight-across to keep bridges shorter, safer, and cheaper.";
    case "L8_WILDLIFE_ESZ":
      return "Protects eco-sensitive buffers and adds elevated bridges so wildlife can cross safely.";
    case "L9_GEOTECH":
      return "Avoids weak soil and landslide-prone slopes, adding ground support where needed.";
    case "L10_UTILITIES":
      return "Stays clear of power substations and towers, protecting overhead high-voltage lines.";
    case "L11_BUILDINGS_ROW":
      return "Strictly avoids hospitals, schools, and places of worship while minimizing land take.";
    case "L12_DEMOGRAPHICS":
      return "Minimizes disruption to nearby residents, families, and vulnerable communities.";
    case "L13_DRAINAGE_WETLANDS":
      return "Protects natural wetlands and adds drainage tunnels so rainwater flows freely.";
    default:
      return "Checked automatically during route planning.";
  }
}

export function humanizeMetricLabel(label) {
  if (!label) return "";
  const replacements = [
    [/LULC Weighted Cost/gi, "Sensitive Land Impact"],
    [/Sensitive Land \(Forest\/Built-up\/Water\)/gi, "Forest, Towns & Water Crossed"],
    [/Moderate–Very High Flood Zone/gi, "Flood-Prone Ground Crossed"],
    [/Floodplain Risk Exposure/gi, "Flood-Prone Ground Crossed"],
    [/Steep Terrain \(>15° Slope\)/gi, "Steep Hillside Crossed"],
    [/Steep \/ Montane Terrain/gi, "Hilly / Steep Ground Crossed"],
    [/Protected Area Core Intrusion/gi, "Inside Protected Parks"],
    [/Protected Sanctuary Core/gi, "Inside Protected Sanctuaries"],
    [/Urban Core & Inner Buffer Exposure/gi, "Through Crowded Town Areas"],
    [/Urban \/ Peri-Urban Proximity/gi, "Near Crowded Town Areas"],
    [/Parallel\/Conflict Transport Cost/gi, "Road & Rail Conflict Score"],
    [/Grade-Separated Structures/gi, "Flyovers & Rail Overbridges"],
    [/Total River Bridge Span/gi, "Total River Bridge Length"],
    [/Hydrology & Bridge Penalty/gi, "River & Water Impact"],
    [/Wildlife & ESZ Impact Cost/gi, "Wildlife & Eco-Zone Impact"],
    [/Monitored ESZ Buffers/gi, "Nearby Eco-Sensitive Zones"],
    [/Geotechnical & Landslide Cost/gi, "Soil & Landslide Risk"],
    [/Soft Subgrade \/ Scarp Exposure/gi, "Weak Soil / Steep Slope"],
    [/Utility Conflict & Crossing Cost/gi, "Power-Line Conflict Score"],
    [/EHV Tower Conflicts/gi, "Power Tower Conflicts"],
    [/Buildings in Direct RoW/gi, "Buildings in Road Path"],
    [/Total Direct 2D RoW Area/gi, "Total Road Land Needed"],
    [/Population within 250 m Buffer/gi, "People Living Within 250 m"],
    [/Peri-Urban Population Proximity/gi, "Near Populated Towns"],
    [/Wetland Encroachment in Direct RoW/gi, "Wetland Area Affected"],
    [/Drainage Obstruction Risk Index/gi, "Water-Flow Blockage Risk"],
    [/Drainage & Wetland Exposure/gi, "Streams & Wetlands Crossed"],
  ];
  let out = String(label);
  for (const [rx, rep] of replacements) {
    out = out.replace(rx, rep);
  }
  return out;
}

/**
 * Translates backend-generated engineering sentences (layer proofs, trade-off narratives,
 * hub reasons, capacity notes) into clear, natural public-friendly English.
 */
export function humanizeText(raw) {
  if (!raw) return "";
  let s = String(raw);

  const rules = [
    // Exact / multi-word technical phrases first
    [/Direct 2D RoW forest land take:/gi, "Forest land needed for the road:"],
    [/\(Forest Conservation Act 1980 Stage-I\/II diversion required\)/gi, "(requires government forest approval & replanting)"],
    [/Direct 2D RoW/gi, "road land area"],
    [/2D RoW/gi, "road land area"],
    [/Direct RoW/gi, "road path"],
    [/\bRoW\b/g, "road land width"],
    [/\bLULC\b/g, "land and forest"],
    [/\bDEM\b/g, "elevation"],
    [/\bEHV\b/g, "high-voltage"],
    [/\bPGCIL\b/g, "Power Grid"],
    [/\bCEA Safety Regulations 2010\b/gi, "electrical safety rules"],
    [/\bSC-NBWL\b/g, "National Wildlife Board"],
    [/\bNBWL\b/g, "National Wildlife Board"],
    [/\bMoEFCC PARIVESH 2\.0 clearance\b/gi, "Environment Ministry approval"],
    [/\bMoEFCC\b/g, "Environment Ministry"],
    [/\bWII 2016\b/gi, "wildlife-friendly"],
    [/\bGSI fault-lineament rasters\b/gi, "detailed landslide maps"],
    [/\bGSI\b/g, "Geological Survey"],
    [/\bRFCTLARR 2013\b/gi, "Fair Land Compensation Law"],
    [/\bWPA 1972\s*§?\s*29\/35\b/gi, "Wildlife Protection Law"],
    [/\bWPA 1972\b/gi, "Wildlife Protection Law"],
    [/\bFCA 1980\b/gi, "Forest Conservation Law"],
    [/\bIRC:SP:99-2013\s*§?\s*2\.12\b/gi, "expressway access guidelines"],
    [/\bIRC:SP:99\b/gi, "expressway guidelines"],
    [/\bIRC:SP:84-2019\b/gi, "highway design guidelines"],
    [/\bIRC:SP:84\s*Section\s*2\.2\b/gi, "highway capacity guidelines"],
    [/\bIRC:SP:84\b/gi, "highway design guidelines"],
    [/\bIRC:SP:30\b/gi, "national transport cost guidelines"],
    [/\bIRC:SP:13\b/gi, "bridge & drainage guidelines"],
    [/\bIRC:73\s*&\s*IRC:SP:84\b/gi, "national highway safety standards"],
    [/\bIRC\s+Pareto-equilibrium\b/gi, "strong all-round balance"],
    [/\bPareto-equilibrium\b/gi, "balanced compromise"],
    [/\bPareto Alignment Index\b/gi, "Overall Balance Score"],
    [/\bIRC\s+ruling\s+gradients?\b/gi, "safe road slope limits"],
    [/\bIRC\s+grade\s+limits\b/gi, "safe road slope limits"],
    [/\bIRC\s+limits\b/gi, "safe slope limits"],
    [/\bIRC\b/g, "highway"],
    [/\bHVI ranking\b/gi, "city priority score"],
    [/\bHVI\b/g, "city priority score"],
    [/\bPCU\/hr\b/gi, "vehicles/hour"],
    [/\bV\/C\b/g, "traffic load"],
    [/\bV-C\b/g, "traffic load"],
    [/\bkm-eq\b/gi, "impact points"],
    [/\bROBs\b/g, "railway overbridges"],
    [/\bROB\b/g, "railway overbridge"],
    [/\bESZ\b/g, "eco-sensitive buffer zone"],
    [/\bR&R\b/g, "relocation & support"],
    [/\bSIA\b/g, "social impact check"],
    [/\bFleet VOC Savings\b/gi, "Annual Fuel & Vehicle Savings"],
    [/\bVOC\b/g, "fuel & vehicle cost"],

    // Technical engineering phrasing -> plain English
    [/Forced by user targetHubs override \(bypasses automatic population\/spacing\/V-C filters\)\./gi, "Included because you specifically chose this town."],
    [/Auto-connect intermediate hubs feature is turned off by user\./gi, "Not connected because automatic city connection is turned off."],
    [/Autonomous hub connection cap reached/gi, "Skipped because the selected city limit was reached"],
    [/Outside the A-B spatial corridor buffer/gi, "Too far from the main route"],
    [/Catchment population/gi, "Local area population"],
    [/Projected interchange spacing/gi, "Distance to the nearest highway exit"],
    [/diagnostic samples exceed 30° maximum infeasible cut slope/gi, "spots steeper than the 30° road safety limit"],
    [/sub-grid 125 m edge samples > 30°/gi, "short steep hillside spots over 30°"],
    [/all 250 m routing vertices ≤ 30°/gi, "all main route sections stay within the 30° safety limit"],
    [/active tectonic landslide shear core hard-constraint violations/gi, "active landslide zones crossed"],
    [/shear core samples addressed via piled viaduct \/ anchored stabilization/gi, "steep landslide spots reinforced with deep-foundation bridges & retaining walls"],
    [/unmitigated critical wildlife corridor core violations/gi, "unprotected wildlife crossing zones"],
    [/substation\/tower footprints impacted/gi, "power substations or towers affected"],
    [/EHV substations or transmission tower footprints/gi, "power substations or high-voltage towers"],
    [/gantry\/guard span/gi, "safety guard span"],
    [/deduplicated road land area land take:/gi, "Total land needed for the road:"],
    [/mainline swath/gi, "road width"],
    [/gram panchayat cores and village abadi areas/gi, "village centers and residential areas"],
    [/village abadi & school zones/gi, "village homes & schools"],
    [/Geometric Euclidean directness minimizes horizontal deflection angles and grade changes, yielding the lowest fuel consumption, tire wear, and civil earthwork demand\./gi, "A straighter, flatter road with fewer sharp curves means smoother driving, lower fuel costs, and less digging during construction."],
    [/euclidean baseline/gi, "straight-line path"],
    [/Direct Baseline/gi, "Straight-Line Route"],
    [/Baseline:/gi, "Straight-Line Route:"],
    [/\(Baseline:/gi, "(Straight-Line Route:"],
    [/vs\. baseline/gi, "compared to a straight line"],
    [/vs\. MARG/gi, "vs. Smart Route"],
    [/MARG:/g, "Smart Route:"],
    [/riparian encroachment/gi, "disturbance near riverbanks"],
    [/riparian setback/gi, "riverbank safety buffer"],
    [/natural levees/gi, "higher, drier ground"],
    [/inundation zones?/gi, "flood-prone areas"],
    [/inundation/gi, "flooding"],
    [/hydrologic mitigation/gi, "water-flow protection"],
    [/cross-drainage culverts\/aqueducts/gi, "water drainage tunnels & culverts"],
    [/cross-drainage structures/gi, "water drainage culverts"],
    [/grade-separated feeder spurs/gi, "dedicated connecting link roads"],
    [/grade-separated crossings/gi, "flyovers and railway overbridges"],
    [/grade-separated arterial tie-in policy enforced/gi, "Connects directly to major highways without disturbing local streets"],
    [/arterial highway hierarchy applied/gi, "Connects cleanly to major highways"],
    [/Terminals anchored on/gi, "Start and end connect to"],
    [/monitored tracts/gi, "nearby communities"],
    [/census dasymetric tract overlays available in Tier-2 benchmark corridors/gi, "Detailed neighborhood population maps are included in the 3 regional showcase corridors"],
    [/Tier-2 benchmark corridors/gi, "the detailed regional showcase corridors"],
    [/Tier-1 Pan-India corridor/gi, "All-India route"],
    [/Tier-2 corridors/gi, "detailed regional corridors"],
    [/dynamic OSM land-use synthesis/gi, "live satellite and land-use maps"],
    [/physiographic zones/gi, "terrain regions"],
    [/montane\/scarp corridor graded within/gi, "hilly sections kept within"],
    [/escarpment scarps/gi, "steep cliffs"],
    [/greenfield bypasses/gi, "open-land bypasses"],
    [/greenfield centerline/gi, "new road path"],
    [/greenfield expressway standards/gi, "modern highway standards"],
    [/municipal urban cores & ribbon settlements/gi, "busy town centers and roadside settlements"],
    [/metropolitan urban cores/gi, "crowded city centers"],
    [/Soft subgrade \/ black-cotton soil/gi, "Soft or clay-heavy soil"],
    [/Weak-soil, fault & landslide penalty/gi, "Weak soil & landslide risk"],
    [/geotechnical stabilization zones specified/gi, "hillside & soil reinforcement zones planned"],
    [/100% Statutory Bypass:/gi, "100% Park-Safe Route:"],
    [/100% Statutory Compliance:/gi, "100% Park-Safe Route:"],
    [/Mandatory Statutory Clearance Required:/gi, "Special Wildlife Approval Required:"],
    [/Statutory Wildlife Compliance/gi, "National Park Protection"],
    [/Statutory Clearance/gi, "Environment Approval"],
    [/Protected Area core violations/gi, "National Park or Sanctuary crossings"],
    [/notified Protected Area/gi, "protected wildlife park"],
    [/notified Wildlife Sanctuaries/gi, "protected wildlife sanctuaries"],
    [/notified Sanctuary/gi, "protected wildlife sanctuary"],
    [/alignment deviation/gi, "extra distance"],
    [/length deviation/gi, "extra distance"],
    [/horizontal curve deflections/gi, "gentle curves"],
    [/tendering/gi, "construction"],
    [/\bstructures\b/gi, "buildings"],
    [/\bha\b/g, "hectares"],
  ];

  for (const [rx, rep] of rules) {
    s = s.replace(rx, rep);
  }
  return s;
}
