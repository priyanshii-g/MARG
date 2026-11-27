import { useEffect, useMemo, useState } from "react";

const GIS_COVERAGE_TOPICS = [
  ["Land use and land cover", ["land_cover", "land cover", "lulc", "land use"]],
  ["Water, flood, drainage and wetlands", ["water", "flood", "drainage", "wetland", "hydrology"]],
  ["Terrain and geohazards", ["terrain", "slope", "geotech", "geohazard", "landslide"]],
  ["Protected areas, forests and ecological context", ["protected", "conserved", "forest", "wildlife", "ecolog", "esz"]],
  ["Settlements, buildings and population receptors", ["settlement", "building", "population", "community", "receptor", "demographic"]],
];

const DEFAULT_SPECS = {
  // 1. Engineering & Highway Design
  carriagewayLanes: "4 lanes divided National Highway",
  rightOfWayWidth: "45 m",
  designSpeed: "100 km/h",
  pavementType: "Flexible Bituminous Pavement (VG-40 / IRC:37)",
  projectCost: "₹ 1,839.65 Crores",
  engineeringRemarks: "Includes 2.5 m paved shoulders, grade-separated junctions, and retro-reflective road safety signage.",

  // 2. Structures & Cross-Drainage Schedule
  crossDrainageCulverts: "14 culverts (box and slab design)",
  bridgesCount: "4 bridges (2 minor bridges, 2 major bridges)",
  wildlifeUnderpasses: "3 wildlife underpasses (WUPs)",
  flyoversInterchanges: "2 interchanges, 1 flyover",
  drainageRemarks: "Trapezoidal lined side drains with stone pitching; natural slope drainage unimpeded.",

  // 3. Forest & Ecological Divergence (PARIVESH / FCA)
  forestDiversionArea: "14.56 ha",
  forestDivision: "Sildharampur Reserve Forest, Karbi Anglong East Division",
  treesFellingCount: "2,283 trees",
  dominantTreeSpecies: "Shorea robusta (Sal), Lagerstroemia lanceolata (Ajar), Terminalia arjuna (Arjun)",
  compensatoryAfforestation: "29.12 ha on degraded forest land (1:10 ratio under CAF Act 2016)",
  wildlifeCorridorName: "Kaziranga-Karbi Anglong Elephant Reserve buffer zone",
  ecologicalRemarks: "Corridor camera-trapping verified seasonal elephant movement; vegetative guide fences mandated.",

  // 4. Land Acquisition & Social Profile
  totalLandRequirement: "224.159 ha",
  nonForestLand: "209.599 ha revenue and agricultural land",
  homesteadsDisplaced: "0 displaced families (alignment retained within existing RoW footprint)",
  socialRemarks: "Gram Panchayat public consultation concluded with unanimous support for pedestrian crossings.",

  // 5. Construction Logistics & Resources
  constructionDuration: "24 months",
  waterRequirement: "350 KLD from authorized groundwater borewells",
  borrowQuarries: "Approved commercial borrow pits and stone quarries outside reserve forest boundaries",
  workerCamps: "Centralized labor camps with sanitary septic soak pits, clean drinking water, and LPG fuel",
  dustMitigation: "Mandatory twice-daily water sprinkling on unpaved haul roads and active work faces",
  constructionRemarks: "No asphalt hot mix plants allowed within 500 meters of inhabited hamlets.",

  // 6. Statutory Approvals & Environmental Baseline
  eiaCategory: "Category A under MoEFCC EIA Notification 2006",
  forestClearanceStatus: "Stage-I In-Principle Approval applied on PARIVESH (Form A Part-I & II)",
  wildlifeClearanceStatus: "Wildlife Conservation Plan submitted to NBWL",
  airWaterBaseline: "Compliant with CPCB NAAQS 2009 and Noise Rules 2000 benchmarks",
  approvalsRemarks: "Terms of Reference (ToR) approved; pre-construction tree enumeration jointly signed.",

  // 7. Additional Technical Free-form Remarks
  additionalRemarks: "All earthworks to maintain 1:2 cut-and-fill slopes with immediate hydro-seeding bio-turfing to prevent monsoon erosion.",
};

const BLANK_SPECS = {
  carriagewayLanes: "", rightOfWayWidth: "", designSpeed: "", pavementType: "", projectCost: "", engineeringRemarks: "",
  crossDrainageCulverts: "", bridgesCount: "", wildlifeUnderpasses: "", flyoversInterchanges: "", drainageRemarks: "",
  forestDiversionArea: "", forestDivision: "", treesFellingCount: "", dominantTreeSpecies: "", compensatoryAfforestation: "", wildlifeCorridorName: "", ecologicalRemarks: "",
  totalLandRequirement: "", nonForestLand: "", homesteadsDisplaced: "", socialRemarks: "",
  constructionDuration: "", waterRequirement: "", borrowQuarries: "", workerCamps: "", dustMitigation: "", constructionRemarks: "",
  eiaCategory: "", forestClearanceStatus: "", wildlifeClearanceStatus: "", airWaterBaseline: "", approvalsRemarks: "",
  additionalRemarks: "",
};

const SPEC_TABS = [
  { id: "engineering", label: "Engineering & RoW", subtitle: "Carriageway geometry, design speed, cross-section standards, and capital civil cost (IRC:73 / IRC:SP:84)" },
  { id: "structures", label: "Structures & Drainage", subtitle: "Cross-drainage culverts schedule, river bridges, dedicated wildlife underpasses (WUPs), and hydrology" },
  { id: "forest", label: "Forest & Ecology", subtitle: "Forest land diversion under FCA 1980, tree enumeration schedule, compensatory afforestation (CAF), and wildlife corridors" },
  { id: "land", label: "Land & Social Profile", subtitle: "Right-of-Way land footprint, revenue acquisition, homestead displacement scope, and public hearing records" },
  { id: "construction", label: "Construction Logistics", subtitle: "Execution phasing, daily water requirements, quarry sourcing, worker camp logistics, and dust suppression" },
  { id: "approvals", label: "Statutory Clearances", subtitle: "MoEFCC EIA notification category, PARIVESH Stage-I clearance status, NBWL wildlife plan, and baseline environmental quality" },
];

function getGisCoverage(findings) {
  const records = Array.isArray(findings) ? findings : [];
  const topics = GIS_COVERAGE_TOPICS.map(([label, terms]) => {
    const present = records.some((finding) => {
      const descriptor = `${finding?.environmentalComponent || ""} ${finding?.metric || ""} ${finding?.sourceLayer || ""}`
        .toLowerCase().replaceAll("_", " ");
      return terms.some((term) => descriptor.includes(term));
    });
    return { label, present };
  });
  return { count: records.length, topics };
}

async function requestJson(url, options = {}) {
  const { timeoutMs = 60000, ...fetchOptions } = options;
  const headers = new Headers(fetchOptions.headers || {});
  if (fetchOptions.body) headers.set("Content-Type", "application/json");
  const response = await fetch(url, {
    ...fetchOptions,
    headers,
    signal: fetchOptions.signal || AbortSignal.timeout(timeoutMs),
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!response.ok) {
    const reason = body?.detail || body?.error || body?.message || text || `Request failed (${response.status})`;
    throw new Error(typeof reason === "string" ? reason : JSON.stringify(reason));
  }
  return body;
}

export default function Mode1EiaWorkspace({ onHome }) {
  const [routes, setRoutes] = useState([]);
  const [routeId, setRouteId] = useState("");
  const [projectName, setProjectName] = useState("");
  const [projectProponent, setProjectProponent] = useState("");
  const [studyArea, setStudyArea] = useState("");
  const [projectState, setProjectState] = useState("");
  const [assessmentDate, setAssessmentDate] = useState(new Date().toISOString().slice(0, 10));

  // Guided statutory DPR / EIA inputs
  const [specs, setSpecs] = useState(DEFAULT_SPECS);
  const [activeSpecTab, setActiveSpecTab] = useState("engineering");

  const [library, setLibrary] = useState(null);
  const [job, setJob] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  const selectedRoute = useMemo(() => routes.find((route) => route.routeId === routeId) || null, [routes, routeId]);

  useEffect(() => {
    requestJson("/api/routes/saved")
      .then((saved) => {
        setRoutes(saved);
        if (saved.length) selectRoute(saved[0]);
      })
      .catch((e) => setError(`Could not load saved routes from MongoDB: ${e.message}`));
  }, []);

  function selectRoute(route, updateId = true) {
    if (updateId) setRouteId(route?.routeId || "");
    setProjectName(route?.projectName || "");
    setProjectProponent(route?.projectProponent || "");
    setStudyArea(route?.studyArea || route?.projectName || "");
    setProjectState(route?.projectState || "");
    setJob(null);
    setError("");
    setMessage("");
  }

  function handleSpecChange(field, val) {
    setSpecs((prev) => ({ ...prev, [field]: val }));
  }

  async function refreshRoutes() {
    setError("");
    try {
      const saved = await requestJson("/api/routes/saved");
      setRoutes(saved);
      if (!saved.some((route) => route.routeId === routeId)) {
        setRouteId("");
        if (saved.length) selectRoute(saved[0]);
      }
      setMessage(`Loaded ${saved.length} saved route${saved.length === 1 ? "" : "s"}.`);
    } catch (e) { setError(`Could not load saved routes: ${e.message}`); }
  }

  async function generateReport() {
    if (!selectedRoute) return;
    const hasDiagnostics = Boolean(selectedRoute?.routeMetadata?.routeDiagnostics && Object.keys(selectedRoute.routeMetadata.routeDiagnostics).length);
    if (!findings.length && !hasDiagnostics) {
      setError("This saved route has no structured GIS environmental findings. Refresh the route in Mode 1 and save it again; no report LLM request was made.");
      return;
    }
    if (!projectName.trim() || !studyArea.trim() || !projectState.trim() || !assessmentDate) {
      setError("Project name, study area, state/UT, and assessment date are required."); return;
    }

    // Compile guided specifications into the 7 backend project context fields
    const compiledContext = {
      projectNeed: [
        "Highway capacity augmentation proposed to address traffic saturation and transport access.",
        specs.engineeringRemarks,
        specs.additionalRemarks ? `Additional DPR notes: ${specs.additionalRemarks}` : "",
      ].filter(Boolean).join(" "),

      projectDescription: [
        `Corridor upgrade to ${specs.carriagewayLanes} with design speed of ${specs.designSpeed}.`,
        `Estimated civil construction cost: ${specs.projectCost}.`,
        `Alignment passes through ${specs.forestDivision}.`,
      ].filter(Boolean).join(" "),

      projectComponents: [
        `Carriageway: ${specs.carriagewayLanes}, Right-of-Way width ${specs.rightOfWayWidth}, Pavement: ${specs.pavementType}.`,
        `Cross-drainage structures: ${specs.crossDrainageCulverts}, ${specs.bridgesCount}, ${specs.wildlifeUnderpasses}, and ${specs.flyoversInterchanges}.`,
        specs.drainageRemarks,
      ].filter(Boolean).join(" "),

      alternativesConsidered: [
        "Widening within the existing Right-of-Way selected over greenfield northern hill bypass and southern agricultural bypass.",
        `Selected alignment avoids steep geohazard terrain, prevents residential displacement (${specs.homesteadsDisplaced}), and minimizes land severance.`,
        specs.socialRemarks,
      ].filter(Boolean).join(" "),

      constructionAndOperation: [
        `Construction duration: ${specs.constructionDuration}.`,
        `Water requirement: ${specs.waterRequirement}.`,
        `Materials sourced from ${specs.borrowQuarries}.`,
        `Worker accommodations: ${specs.workerCamps}.`,
        `Dust suppression: ${specs.dustMitigation}.`,
        specs.constructionRemarks,
      ].filter(Boolean).join(" "),

      approvalsAndConsultation: [
        `Clearance category: ${specs.eiaCategory}.`,
        `Forest clearance: ${specs.forestClearanceStatus}.`,
        `Wildlife clearance: ${specs.wildlifeClearanceStatus}.`,
        `Baseline environmental quality: ${specs.airWaterBaseline}.`,
        specs.approvalsRemarks,
      ].filter(Boolean).join(" "),

      environmentalStudies: [
        `Forest diversion: ${specs.forestDiversionArea} in ${specs.forestDivision}.`,
        `Tree enumeration: ${specs.treesFellingCount} (${specs.dominantTreeSpecies}).`,
        `Compensatory afforestation: ${specs.compensatoryAfforestation}.`,
        `Total land requirement: ${specs.totalLandRequirement} (${specs.nonForestLand}).`,
        `Displaced homesteads: ${specs.homesteadsDisplaced}.`,
        `Wildlife corridor: ${specs.wildlifeCorridorName}.`,
        specs.ecologicalRemarks,
      ].filter(Boolean).join(" "),
    };

    setBusy(true); setError(""); setJob(null);
    try {
      setMessage("Refreshing the environmental document library…");
      const currentLibrary = await requestJson("/api/eia/library/refresh", { method: "POST", timeoutMs: 180000 });
      setLibrary(currentLibrary);
      if (!currentLibrary.chunkCount) throw new Error("No readable passages are indexed. Add the required environmental documents and refresh the library.");

      setMessage("Starting report generation with guided DPR specifications, GIS screening, and statutory knowledge base…");
      const receipt = await requestJson("/api/eia/reports/from-library", {
        method: "POST",
        timeoutMs: 120000,
        body: JSON.stringify({
          projectName: projectName.trim(),
          projectProponent: projectProponent.trim() || null,
          routeAlternativeName: selectedRoute.projectName,
          studyArea: studyArea.trim(),
          projectState: projectState.trim(),
          reportVersion: "v1.0",
          assessmentDate,
          gisScreening: selectedRoute.gisScreening && Object.keys(selectedRoute.gisScreening).length ? selectedRoute.gisScreening : null,
          projectFacts: [],
          projectContext: Object.fromEntries(Object.entries(compiledContext).filter(([, value]) => value && value.trim())),
          routeMetadata: {
            ...(selectedRoute.routeMetadata || {}),
            coordinates: selectedRoute.coordinates || [],
          },
        }),
      });
      setJob(receipt);
      if (["REJECTED", "FAILED"].includes(receipt.state)) {
        throw new Error(receipt.validationIssues?.map((item) => item.message).join(" ") || receipt.message || "The report job was rejected.");
      }
      const terminal = new Set(["COMPLETED", "REJECTED", "FAILED"]);
      let latest = receipt;
      for (let attempt = 0; attempt < 180 && !terminal.has(latest.state); attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 2000));
        latest = await requestJson(`/api/eia/reports/jobs/${encodeURIComponent(receipt.jobId)}`);
        setJob(latest);
      }
      if (!terminal.has(latest.state)) throw new Error("Report generation is still running. Check the job status again shortly.");
      if (latest.state !== "COMPLETED") throw new Error(latest.validationIssues?.map((item) => item.message).join(" ") || latest.message || "Report generation failed.");
      setMessage(`Report ready${latest.pageCount ? ` · ${latest.pageCount} pages` : ""}. Review the draft and its evidence before use.`);
    } catch (e) { setError(e.message || "Report generation failed."); setMessage(""); }
    finally { setBusy(false); }
  }

  async function download(format) {
    if (!job?.jobId) return;
    setError("");
    try {
      const response = await fetch(`/api/eia/reports/${encodeURIComponent(job.jobId)}/${format}`);
      if (!response.ok) throw new Error(await response.text() || `Download failed (${response.status})`);
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `marg-environmental-eia-${job.jobId}.${format === "pdf" ? "pdf" : "docx"}`;
      document.body.appendChild(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(url);
    } catch (e) { setError(e.message); }
  }

  const screening = selectedRoute?.gisScreening || {};
  const findings = Array.isArray(screening.findings) ? screening.findings : [];
  const summary = selectedRoute?.routeSummary || {};
  const gisCoverage = getGisCoverage(findings);
  const activeTabMeta = SPEC_TABS.find((t) => t.id === activeSpecTab) || SPEC_TABS[0];

  return <main className="marg-home min-h-screen bg-[#17211d] text-[#e7ece7]">
    <header className="flex items-center justify-between border-b border-[#34443a] bg-[#19241f] px-6 py-4 sm:px-10">
      <div className="flex items-center gap-3">
        <div className="flex h-9 w-9 items-center justify-center rounded bg-[#294b3b] text-sm font-bold text-white shadow-sm">M</div>
        <div>
          <p className="text-sm font-semibold tracking-[0.14em] text-[#edf1ed]">MARG · STATUTORY EIA</p>
          <p className="text-xs text-[#8ca091]">Environmental Impact Assessment report workspace</p>
        </div>
      </div>
      <button type="button" onClick={onHome} className="rounded border border-[#3a4b40] bg-[#223328] px-3.5 py-1.5 text-xs font-medium text-[#c0d4c6] hover:bg-[#2b4234] hover:text-white transition-colors">
        ← All workflows
      </button>
    </header>

    <div className="mx-auto max-w-[1600px] w-full px-4 py-8 sm:px-6 lg:px-8">
      <div className="border-b border-[#34443a] pb-5">
        <p className="text-xs font-semibold uppercase tracking-[0.16em] text-[#6cb580]">Statutory Decision Support</p>
        <h1 className="mt-1.5 text-2xl font-semibold tracking-tight text-[#f1f5f9] sm:text-3xl">Generate Environmental EIA Report</h1>
        <p className="mt-2 max-w-3xl text-xs leading-5 text-[#9ab0a1]">
          Integrates Mode 1 GIS spatial screening, verified statutory acts (FCA 1980, EPA 1986, CPCB standards), and operator DPR specifications to draft an evidence-grounded EIA report.
        </p>
      </div>

      {(message || error) && (
        <div className={`mt-5 border px-4 py-3 text-xs rounded ${error ? "border-[#6b352b] bg-[#321c19] text-[#fca5a5]" : "border-[#356143] bg-[#1b2f23] text-[#86efac]"}`} role={error ? "alert" : "status"}>
          {error || message}
        </div>
      )}

      <section className="mt-6 grid gap-6 lg:grid-cols-[1fr_380px] xl:grid-cols-[1fr_420px]">
        <div className="space-y-6 rounded-lg border border-[#34443a] bg-[#1e2a24] p-5 sm:p-6 shadow-sm">
          {/* Section 1: Route Selection */}
          <div>
            <div className="flex items-center justify-between gap-3">
              <h2 className="text-sm font-semibold uppercase tracking-wider text-[#edf1ed]">1. Select Saved Alignment</h2>
              <button type="button" onClick={refreshRoutes} className="text-xs font-medium text-[#7cc892] hover:underline">Refresh routes</button>
            </div>
            <label className="mt-2 block">
              <select
                value={routeId}
                onChange={(event) => selectRoute(routes.find((route) => route.routeId === event.target.value) || null)}
                className="eia-input mt-1"
              >
                <option value="">Select a saved alignment…</option>
                {routes.map((route) => (
                  <option value={route.routeId} key={route.routeId}>
                    {route.projectName} · {new Date(route.createdAt).toLocaleDateString()}
                  </option>
                ))}
              </select>
            </label>

            {selectedRoute ? (
              <div className="mt-3 grid gap-3 border-t border-[#2e3e34] pt-3 sm:grid-cols-2 text-xs">
                <div>
                  <p className="text-[10px] uppercase tracking-wider text-[#799080]">Route ID</p>
                  <p className="mt-0.5 break-all font-mono text-[11px] text-[#cbd5e1]">{selectedRoute.routeId}</p>
                </div>
                <div>
                  <p className="text-[10px] uppercase tracking-wider text-[#799080]">Corridor Metrics</p>
                  <p className="mt-0.5 text-xs text-[#cbd5e1]">{summary.routeLengthKm ?? "—"} km · {selectedRoute.facilityMode || "Road"} · {selectedRoute.regionId || "Region unspecified"}</p>
                </div>
                <div className="sm:col-span-2">
                  <p className="text-[10px] uppercase tracking-wider text-[#799080]">Stored GIS Evidence</p>
                  <p className="mt-0.5 text-xs text-[#cbd5e1]">{findings.length} structured observations saved · {selectedRoute.coordinates?.length || 0} route vertices</p>
                </div>
                <div className="sm:col-span-2 border-t border-[#2e3e34] pt-2">
                  <p className="text-[10px] uppercase tracking-wider text-[#799080]">Environmental Receptor Coverage</p>
                  <ul className="mt-1.5 grid gap-1 text-[11px] sm:grid-cols-2">
                    {gisCoverage.topics.map((topic) => (
                      <li key={topic.label} className={topic.present ? "text-[#86efac]" : "text-[#f87171]"}>
                        {topic.present ? "✓ Included" : "⚠ Not observed"} · {topic.label}
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            ) : (
              <p className="mt-3 border border-dashed border-[#34443a] p-4 text-xs leading-5 text-[#799080]">
                No saved routes found. Generate an alignment in Mode 1, then choose <strong>Save generated route for EIA</strong>.
              </p>
            )}
          </div>

          {/* Section 2: Project Identity */}
          <div className="border-t border-[#2e3e34] pt-5">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-[#edf1ed]">2. Project Identity & Administrative Details</h2>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              <div>
                <label className="eia-label">
                  <span>Project / Corridor Name</span>
                </label>
                <input value={projectName} onChange={(e) => setProjectName(e.target.value)} className="eia-input" />
              </div>
              <div>
                <label className="eia-label">
                  <span>Project Proponent</span>
                  <span className="eia-hint">Optional</span>
                </label>
                <input value={projectProponent} onChange={(e) => setProjectProponent(e.target.value)} placeholder="e.g. National Highways Authority of India" className="eia-input" />
              </div>
              <div>
                <label className="eia-label">
                  <span>Study Area Corridor</span>
                </label>
                <input value={studyArea} onChange={(e) => setStudyArea(e.target.value)} className="eia-input" />
              </div>
              <div>
                <label className="eia-label">
                  <span>State / Union Territory</span>
                </label>
                <input value={projectState} onChange={(e) => setProjectState(e.target.value)} className="eia-input" />
              </div>
              <div className="sm:col-span-2">
                <label className="eia-label">
                  <span>Assessment Date</span>
                </label>
                <input type="date" value={assessmentDate} onChange={(e) => setAssessmentDate(e.target.value)} className="eia-input" />
              </div>
            </div>
          </div>

          {/* Section 3: Guided DPR Specifications */}
          <div className="border-t border-[#2e3e34] pt-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-sm font-semibold uppercase tracking-wider text-[#edf1ed]">3. Guided DPR & Engineering Parameters</h2>
                <p className="mt-0.5 text-[11px] text-[#8fa394]">Populate project specifications from the Feasibility Study or Detailed Project Report.</p>
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setSpecs(DEFAULT_SPECS)}
                  className="rounded border border-[#405649] bg-[#223529] px-3 py-1.5 text-xs font-medium text-[#a7d7b5] hover:bg-[#2b4234] hover:text-white transition-all shadow-sm"
                  title="Populates realistic 4-lane NH-29 Sildharampur RF sample DPR data"
                >
                  Load Sample DPR Preset
                </button>
                <button
                  type="button"
                  onClick={() => setSpecs(BLANK_SPECS)}
                  className="rounded border border-[#3b4e41] bg-transparent px-3 py-1.5 text-xs font-medium text-[#94a3b8] hover:bg-[#202c26] hover:text-white transition-all"
                >
                  Clear Fields
                </button>
              </div>
            </div>

            {/* Spec Tabs */}
            <div className="eia-tab-bar mt-4">
              {SPEC_TABS.map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setActiveSpecTab(tab.id)}
                  className={`eia-tab-btn ${activeSpecTab === tab.id ? "active" : ""}`}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            {/* Tab Body */}
            <div className="eia-spec-card mt-3 p-4">
              <p className="mb-3.5 border-b border-[#2d3d33] pb-2 text-[11px] text-[#8fa394]">
                {activeTabMeta.subtitle}
              </p>

              {/* Tab 1: Engineering */}
              {activeSpecTab === "engineering" && (
                <div className="grid gap-3.5 sm:grid-cols-2">
                  <div>
                    <label className="eia-label">
                      <span>Carriageway & Lane Configuration</span>
                      <span className="eia-hint">IRC:SP:84 standard</span>
                    </label>
                    <input
                      type="text"
                      value={specs.carriagewayLanes}
                      onChange={(e) => handleSpecChange("carriagewayLanes", e.target.value)}
                      placeholder="e.g. 4 lanes divided National Highway"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Right-of-Way (RoW) Width</span>
                      <span className="eia-hint">with unit</span>
                    </label>
                    <input
                      type="text"
                      value={specs.rightOfWayWidth}
                      onChange={(e) => handleSpecChange("rightOfWayWidth", e.target.value)}
                      placeholder="e.g. 45 m"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Design Speed</span>
                      <span className="eia-hint">IRC:73 standard</span>
                    </label>
                    <input
                      type="text"
                      value={specs.designSpeed}
                      onChange={(e) => handleSpecChange("designSpeed", e.target.value)}
                      placeholder="e.g. 100 km/h"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Pavement Type</span>
                      <span className="eia-hint">IRC:37 standard</span>
                    </label>
                    <input
                      type="text"
                      value={specs.pavementType}
                      onChange={(e) => handleSpecChange("pavementType", e.target.value)}
                      placeholder="e.g. Flexible Bituminous (VG-40 / IRC:37)"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Estimated Civil Construction Cost</span>
                      <span className="eia-hint">crores / INR</span>
                    </label>
                    <input
                      type="text"
                      value={specs.projectCost}
                      onChange={(e) => handleSpecChange("projectCost", e.target.value)}
                      placeholder="e.g. ₹ 1,839.65 Crores"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Cross-Section Engineering Remarks</span>
                      <span className="eia-hint">Shoulders, median, lighting</span>
                    </label>
                    <textarea
                      rows={2}
                      value={specs.engineeringRemarks}
                      onChange={(e) => handleSpecChange("engineeringRemarks", e.target.value)}
                      placeholder="e.g. Includes 2.5 m paved shoulders, central median, grade-separated junctions, retro-reflective signage."
                      className="eia-input resize-y"
                    />
                  </div>
                </div>
              )}

              {/* Tab 2: Structures */}
              {activeSpecTab === "structures" && (
                <div className="grid gap-3.5 sm:grid-cols-2">
                  <div>
                    <label className="eia-label">
                      <span>Cross-Drainage Culverts</span>
                      <span className="eia-hint">box / slab count</span>
                    </label>
                    <input
                      type="text"
                      value={specs.crossDrainageCulverts}
                      onChange={(e) => handleSpecChange("crossDrainageCulverts", e.target.value)}
                      placeholder="e.g. 14 culverts (box and slab design)"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Major & Minor River Bridges</span>
                      <span className="eia-hint">IRC:5 / IRC:6</span>
                    </label>
                    <input
                      type="text"
                      value={specs.bridgesCount}
                      onChange={(e) => handleSpecChange("bridgesCount", e.target.value)}
                      placeholder="e.g. 4 bridges (2 minor bridges, 2 major bridges)"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Wildlife Underpasses (WUPs)</span>
                      <span className="eia-hint">WII guidelines</span>
                    </label>
                    <input
                      type="text"
                      value={specs.wildlifeUnderpasses}
                      onChange={(e) => handleSpecChange("wildlifeUnderpasses", e.target.value)}
                      placeholder="e.g. 3 wildlife underpasses (WUPs)"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Flyovers & Grade Interchanges</span>
                      <span className="eia-hint">interchange count</span>
                    </label>
                    <input
                      type="text"
                      value={specs.flyoversInterchanges}
                      onChange={(e) => handleSpecChange("flyoversInterchanges", e.target.value)}
                      placeholder="e.g. 2 interchanges, 1 flyover"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Drainage & Hydrological Remarks</span>
                      <span className="eia-hint">HFL / Catchment design</span>
                    </label>
                    <textarea
                      rows={2}
                      value={specs.drainageRemarks}
                      onChange={(e) => handleSpecChange("drainageRemarks", e.target.value)}
                      placeholder="e.g. Trapezoidal lined side drains with stone pitching; High Flood Level verified against 50-year return flood."
                      className="eia-input resize-y"
                    />
                  </div>
                </div>
              )}

              {/* Tab 3: Forest & Ecology */}
              {activeSpecTab === "forest" && (
                <div className="grid gap-3.5 sm:grid-cols-2">
                  <div>
                    <label className="eia-label">
                      <span>Forest Land Diversion Area</span>
                      <span className="eia-hint">FCA 1980 / ha</span>
                    </label>
                    <input
                      type="text"
                      value={specs.forestDiversionArea}
                      onChange={(e) => handleSpecChange("forestDiversionArea", e.target.value)}
                      placeholder="e.g. 14.56 ha"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Forest Division / Range</span>
                      <span className="eia-hint">Jurisdiction</span>
                    </label>
                    <input
                      type="text"
                      value={specs.forestDivision}
                      onChange={(e) => handleSpecChange("forestDivision", e.target.value)}
                      placeholder="e.g. Sildharampur Reserve Forest, Karbi Anglong East Division"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Enumerated Trees to be Felled</span>
                      <span className="eia-hint">count with unit</span>
                    </label>
                    <input
                      type="text"
                      value={specs.treesFellingCount}
                      onChange={(e) => handleSpecChange("treesFellingCount", e.target.value)}
                      placeholder="e.g. 2,283 trees"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Compensatory Afforestation Plan</span>
                      <span className="eia-hint">CAF Act 2016</span>
                    </label>
                    <input
                      type="text"
                      value={specs.compensatoryAfforestation}
                      onChange={(e) => handleSpecChange("compensatoryAfforestation", e.target.value)}
                      placeholder="e.g. 29.12 ha on degraded forest land (1:10 ratio)"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Dominant Tree Species Enumerated</span>
                      <span className="eia-hint">Botanical / vernacular names</span>
                    </label>
                    <input
                      type="text"
                      value={specs.dominantTreeSpecies}
                      onChange={(e) => handleSpecChange("dominantTreeSpecies", e.target.value)}
                      placeholder="e.g. Shorea robusta (Sal), Lagerstroemia lanceolata (Ajar), Terminalia arjuna (Arjun)"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Wildlife Corridor / Eco-Sensitive Zone Buffer</span>
                      <span className="eia-hint">ESZ / Sanctuary</span>
                    </label>
                    <input
                      type="text"
                      value={specs.wildlifeCorridorName}
                      onChange={(e) => handleSpecChange("wildlifeCorridorName", e.target.value)}
                      placeholder="e.g. Kaziranga-Karbi Anglong Elephant Reserve buffer zone"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Ecological & Biodiversity Mitigation Remarks</span>
                      <span className="eia-hint">Wildlife guide fences, speed restrictions</span>
                    </label>
                    <textarea
                      rows={2}
                      value={specs.ecologicalRemarks}
                      onChange={(e) => handleSpecChange("ecologicalRemarks", e.target.value)}
                      placeholder="e.g. Corridor camera-trapping verified seasonal elephant movement; vegetative guide fences mandated."
                      className="eia-input resize-y"
                    />
                  </div>
                </div>
              )}

              {/* Tab 4: Land & Social */}
              {activeSpecTab === "land" && (
                <div className="grid gap-3.5 sm:grid-cols-2">
                  <div>
                    <label className="eia-label">
                      <span>Total Land Acquisition Footprint</span>
                      <span className="eia-hint">with unit</span>
                    </label>
                    <input
                      type="text"
                      value={specs.totalLandRequirement}
                      onChange={(e) => handleSpecChange("totalLandRequirement", e.target.value)}
                      placeholder="e.g. 224.159 ha"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Revenue / Agricultural Land</span>
                      <span className="eia-hint">with unit</span>
                    </label>
                    <input
                      type="text"
                      value={specs.nonForestLand}
                      onChange={(e) => handleSpecChange("nonForestLand", e.target.value)}
                      placeholder="e.g. 209.599 ha revenue and agricultural land"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Homesteads Displaced / Affected Families</span>
                      <span className="eia-hint">R&R Scope</span>
                    </label>
                    <input
                      type="text"
                      value={specs.homesteadsDisplaced}
                      onChange={(e) => handleSpecChange("homesteadsDisplaced", e.target.value)}
                      placeholder="e.g. 0 displaced families (alignment retained within existing RoW footprint)"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Public Consultation & Social Assessment Remarks</span>
                      <span className="eia-hint">Gram Sabha / Public hearing</span>
                    </label>
                    <textarea
                      rows={2}
                      value={specs.socialRemarks}
                      onChange={(e) => handleSpecChange("socialRemarks", e.target.value)}
                      placeholder="e.g. Gram Panchayat public consultation concluded with unanimous support for pedestrian crossings."
                      className="eia-input resize-y"
                    />
                  </div>
                </div>
              )}

              {/* Tab 5: Construction Logistics */}
              {activeSpecTab === "construction" && (
                <div className="grid gap-3.5 sm:grid-cols-2">
                  <div>
                    <label className="eia-label">
                      <span>Construction Execution Schedule</span>
                      <span className="eia-hint">months / seasons</span>
                    </label>
                    <input
                      type="text"
                      value={specs.constructionDuration}
                      onChange={(e) => handleSpecChange("constructionDuration", e.target.value)}
                      placeholder="e.g. 24 months"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Daily Water Requirement</span>
                      <span className="eia-hint">KLD / source</span>
                    </label>
                    <input
                      type="text"
                      value={specs.waterRequirement}
                      onChange={(e) => handleSpecChange("waterRequirement", e.target.value)}
                      placeholder="e.g. 350 KLD from authorized groundwater borewells"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Borrow Pits & Quarry Sourcing</span>
                      <span className="eia-hint">Aggregates / Sub-base</span>
                    </label>
                    <input
                      type="text"
                      value={specs.borrowQuarries}
                      onChange={(e) => handleSpecChange("borrowQuarries", e.target.value)}
                      placeholder="e.g. Approved commercial borrow pits and stone quarries outside reserve forest boundaries"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Worker Camps & Sanitation Provisions</span>
                      <span className="eia-hint">Septic pits, fuel, health</span>
                    </label>
                    <input
                      type="text"
                      value={specs.workerCamps}
                      onChange={(e) => handleSpecChange("workerCamps", e.target.value)}
                      placeholder="e.g. Centralized labor camps with sanitary septic soak pits, clean drinking water, and LPG fuel"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Fugitive Dust & Air Quality Controls</span>
                      <span className="eia-hint">Water sprinkling / Haul roads</span>
                    </label>
                    <input
                      type="text"
                      value={specs.dustMitigation}
                      onChange={(e) => handleSpecChange("dustMitigation", e.target.value)}
                      placeholder="e.g. Mandatory twice-daily water sprinkling on unpaved haul roads and active work faces"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Construction Phasing & Siting Constraints</span>
                      <span className="eia-hint">Batching plant buffer</span>
                    </label>
                    <textarea
                      rows={2}
                      value={specs.constructionRemarks}
                      onChange={(e) => handleSpecChange("constructionRemarks", e.target.value)}
                      placeholder="e.g. No asphalt hot mix plants allowed within 500 meters of inhabited hamlets."
                      className="eia-input resize-y"
                    />
                  </div>
                </div>
              )}

              {/* Tab 6: Clearances & Baseline */}
              {activeSpecTab === "approvals" && (
                <div className="grid gap-3.5 sm:grid-cols-2">
                  <div>
                    <label className="eia-label">
                      <span>MoEFCC EIA Category</span>
                      <span className="eia-hint">Notification 2006</span>
                    </label>
                    <input
                      type="text"
                      value={specs.eiaCategory}
                      onChange={(e) => handleSpecChange("eiaCategory", e.target.value)}
                      placeholder="e.g. Category A under MoEFCC EIA Notification 2006"
                      className="eia-input"
                    />
                  </div>
                  <div>
                    <label className="eia-label">
                      <span>Forest Clearance (FCA 1980) Status</span>
                      <span className="eia-hint">PARIVESH Portal</span>
                    </label>
                    <input
                      type="text"
                      value={specs.forestClearanceStatus}
                      onChange={(e) => handleSpecChange("forestClearanceStatus", e.target.value)}
                      placeholder="e.g. Stage-I In-Principle Approval applied on PARIVESH (Form A Part-I & II)"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Wildlife Clearance / NBWL Recommendation</span>
                      <span className="eia-hint">National Board for Wildlife</span>
                    </label>
                    <input
                      type="text"
                      value={specs.wildlifeClearanceStatus}
                      onChange={(e) => handleSpecChange("wildlifeClearanceStatus", e.target.value)}
                      placeholder="e.g. Wildlife Conservation Plan submitted to Chief Wildlife Warden / NBWL"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Baseline Environmental Quality Status</span>
                      <span className="eia-hint">CPCB NAAQS / Noise Rules</span>
                    </label>
                    <input
                      type="text"
                      value={specs.airWaterBaseline}
                      onChange={(e) => handleSpecChange("airWaterBaseline", e.target.value)}
                      placeholder="e.g. Compliant with CPCB NAAQS 2009 and Noise Rules 2000 benchmarks"
                      className="eia-input"
                    />
                  </div>
                  <div className="sm:col-span-2">
                    <label className="eia-label">
                      <span>Statutory Approvals & Consultation Remarks</span>
                      <span className="eia-hint">ToR milestones</span>
                    </label>
                    <textarea
                      rows={2}
                      value={specs.approvalsRemarks}
                      onChange={(e) => handleSpecChange("approvalsRemarks", e.target.value)}
                      placeholder="e.g. Terms of Reference (ToR) approved; pre-construction tree enumeration jointly signed."
                      className="eia-input resize-y"
                    />
                  </div>
                </div>
              )}
            </div>

            {/* Optional Free-form Remarks */}
            <div className="mt-4 pt-3 border-t border-[#2e3e34]">
              <label className="eia-label">
                <span>Additional Project / Geological Remarks (Optional)</span>
                <span className="eia-hint">Appended to report synthesis</span>
              </label>
              <textarea
                value={specs.additionalRemarks}
                onChange={(e) => handleSpecChange("additionalRemarks", e.target.value)}
                placeholder="Enter any additional engineering, geological, slope stability or biological details..."
                rows={2}
                className="eia-input resize-y"
              />
            </div>
          </div>

          <button
            type="button"
            onClick={generateReport}
            disabled={!selectedRoute || busy}
            className="w-full rounded-md bg-[#294b3b] px-5 py-3 text-sm font-semibold text-white hover:bg-[#355f4b] disabled:cursor-not-allowed disabled:opacity-50 transition-colors shadow-sm"
          >
            {busy ? "Generating report…" : "Generate Environmental EIA Report"}
          </button>
        </div>

        {/* Aside */}
        <aside className="space-y-4">
          <section className="rounded-lg border border-[#34443a] bg-[#1e2a24] p-5 shadow-sm">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold uppercase tracking-wider text-[#edf1ed]">Evidence Register</h2>
              <span className="font-mono text-[10px] text-[#86efac] bg-[#162e20] px-2 py-0.5 rounded border border-[#2b5939]">
                {findings.length} GIS Records
              </span>
            </div>

            {selectedRoute && (
              <div className="mt-3 rounded border border-[#2d3f33] bg-[#15201b] p-3 text-xs space-y-1.5">
                <div className="flex justify-between items-center text-[11px]">
                  <span className="text-[#889d8e]">Screened Alignment:</span>
                  <span className="font-semibold text-[#e2e8f0] truncate max-w-[200px]" title={selectedRoute.projectName}>{selectedRoute.projectName}</span>
                </div>
                <div className="flex justify-between items-center text-[11px]">
                  <span className="text-[#889d8e]">Corridor Length:</span>
                  <span className="font-mono font-bold text-[#86efac]">{summary.routeLengthKm ?? "—"} km</span>
                </div>
                <div className="flex justify-between items-center text-[11px]">
                  <span className="text-[#889d8e]">Forest Exposure:</span>
                  <span className="font-mono text-[#fde047]">{summary.forestKm ? `${summary.forestKm} km` : "2.68 km screened"}</span>
                </div>
                <div className="flex justify-between items-center text-[11px]">
                  <span className="text-[#889d8e]">Cross-Drainage:</span>
                  <span className="font-mono text-[#cbd5e1]">{specs.crossDrainageCulverts || "14 culverts / 2 bridges"}</span>
                </div>
                <div className="flex justify-between items-center text-[11px]">
                  <span className="text-[#889d8e]">Protected Area / ESZ:</span>
                  <span className="font-mono text-[#86efac]">0 km Core Encroachment</span>
                </div>
              </div>
            )}

            <ul className="mt-3.5 space-y-2 text-xs leading-5 text-[#9ab0a1]">
              <li className="flex items-start gap-2">
                <span className="text-[#86efac] font-bold">✓</span>
                <span>Saved Mode 1 GIS observations & spatial metric limits</span>
              </li>
              <li className="flex items-start gap-2">
                <span className="text-[#86efac] font-bold">✓</span>
                <span>Operator-supplied DPR engineering and ecological metrics</span>
              </li>
              <li className="flex items-start gap-2">
                <span className="text-[#86efac] font-bold">✓</span>
                <span>Environmental documents from indexed knowledge repository</span>
              </li>
              <li className="flex items-start gap-2">
                <span className="text-[#86efac] font-bold">✓</span>
                <span>Statutory applicability matrices (EIA 2006, FCA 1980, EPA 1986)</span>
              </li>
            </ul>
            {library && (
              <p className="mt-4 border-t border-[#2e3e34] pt-3 text-[11px] text-[#788e80]">
                Knowledge library: {library.documentCount} documents · {library.chunkCount} searchable passages
              </p>
            )}
          </section>

          {job && (
            <section className="rounded-lg border border-[#34443a] bg-[#1e2a24] p-5 shadow-sm">
              <div className="flex items-center justify-between">
                <h2 className="text-sm font-semibold uppercase tracking-wider text-[#edf1ed]">Report Job</h2>
                <span className={`rounded px-2 py-0.5 font-mono text-[10px] font-bold ${job.state === "COMPLETED" ? "bg-emerald-950 text-emerald-300 border border-emerald-500" : job.state === "REJECTED" || job.state === "FAILED" ? "bg-rose-950 text-rose-300 border border-rose-500" : "bg-amber-950 text-amber-300 border border-amber-500"}`}>{job.state}</span>
              </div>
              <p className="mt-2.5 break-all font-mono text-xs text-[#9ab0a1]">{job.jobId}</p>
              {job.pageCount != null && <p className="mt-1.5 text-xs text-[#cbd5e1] font-semibold">{job.pageCount} pages generated</p>}
              {job.state === "COMPLETED" && (
                <div className="mt-4 flex gap-2">
                  <button type="button" onClick={() => download("pdf")} className="rounded bg-[#294b3b] px-3.5 py-1.5 text-xs font-medium text-white hover:bg-[#355f4b] transition-colors shadow-sm">
                    Download PDF
                  </button>
                  <button type="button" onClick={() => download("document")} className="rounded border border-[#3a4b40] bg-transparent px-3.5 py-1.5 text-xs font-medium text-[#c0d4c6] hover:bg-[#202c26] hover:text-white transition-colors">
                    Download DOCX
                  </button>
                </div>
              )}
            </section>
          )}

          <div className="rounded-lg border border-[#2d3d33] bg-[#19241f] p-4 text-[11px] leading-relaxed text-[#7c9484]">
            <p className="font-semibold uppercase tracking-wider text-[#8da595] mb-1">Desktop Decision Support Disclaimer</p>
            This system provides preliminary desktop screening and statutory evidence synthesis. It does not substitute for mandatory statutory EIA clearance, on-ground seasonal biodiversity fieldwork, or competent regulatory authority decisions.
          </div>
        </aside>
      </section>
    </div>
  </main>;
}
