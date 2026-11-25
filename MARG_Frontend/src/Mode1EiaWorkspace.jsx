import { useEffect, useMemo, useState } from "react";

const GIS_COVERAGE_TOPICS = [
  ["Land use and land cover", ["land_cover", "land cover", "lulc", "land use"]],
  ["Water, flood, drainage and wetlands", ["water", "flood", "drainage", "wetland", "hydrology"]],
  ["Terrain and geohazards", ["terrain", "slope", "geotech", "geohazard", "landslide"]],
  ["Protected areas, forests and ecological context", ["protected", "conserved", "forest", "wildlife", "ecolog", "esz"]],
  ["Settlements, buildings and population receptors", ["settlement", "building", "population", "community", "receptor", "demographic"]],
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
  const [projectContext, setProjectContext] = useState({
    projectNeed: "",
    projectDescription: "",
    projectComponents: "",
    alternativesConsidered: "",
    constructionAndOperation: "",
    approvalsAndConsultation: "",
    environmentalStudies: "",
  });
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
    setProjectContext({ projectNeed: "", projectDescription: "", projectComponents: "",
      alternativesConsidered: "", constructionAndOperation: "", approvalsAndConsultation: "",
      environmentalStudies: "" });
    setJob(null);
    setError("");
    setMessage("");
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
    setBusy(true); setError(""); setJob(null);
    try {
      setMessage("Refreshing the environmental document library…");
      const currentLibrary = await requestJson("/api/eia/library/refresh", { method: "POST", timeoutMs: 180000 });
      setLibrary(currentLibrary);
      if (!currentLibrary.chunkCount) throw new Error("No readable passages are indexed. Add the required environmental documents and refresh the library.");

      setMessage("Starting the report from this route’s saved GIS screening and the environmental knowledge base…");
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
          projectContext: Object.fromEntries(Object.entries(projectContext).filter(([, value]) => value.trim())),
          routeMetadata: selectedRoute.routeMetadata || {},
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

  return <main className="marg-home min-h-screen bg-[#f4f3ed] text-[#202820]">
    <header className="flex items-center justify-between border-b border-[#d9ddd3] px-6 py-4 sm:px-10">
      <div className="flex items-center gap-3"><div className="flex h-10 w-10 items-center justify-center rounded-sm bg-[#294b3b] text-sm font-bold text-white">M</div><div><p className="text-sm font-semibold tracking-[0.16em]">MARG · ENVIRONMENTAL EIA</p><p className="text-xs text-[#68736b]">Reports for saved Mode 1 routes</p></div></div>
      <button type="button" onClick={onHome} className="border border-[#aeb8ad] px-4 py-2 text-sm font-medium hover:bg-white">← All workflows</button>
    </header>
    <div className="mx-auto max-w-5xl px-5 py-9 sm:px-8">
      <div className="border-b border-[#d9ddd3] pb-6"><p className="text-xs font-semibold uppercase tracking-[0.18em] text-[#557260]">Environmental report workspace</p><h1 className="mt-2 text-3xl font-medium tracking-tight sm:text-4xl">Generate a report from a saved route</h1><p className="mt-3 max-w-3xl text-sm leading-6 text-[#68736b]">Choose a route saved in Mode 1. MARG uses its GIS screening, the environmental library and any project context you provide to draft evidence-linked sections.</p></div>
      {(message || error) && <div className={`mt-5 border px-4 py-3 text-sm ${error ? "border-[#c88978] bg-[#f8e9e4] text-[#73382c]" : "border-[#b8c9b7] bg-[#e9f0e7] text-[#35543d]"}`} role={error ? "alert" : "status"}>{error || message}</div>}
      <section className="mt-6 grid gap-6 lg:grid-cols-[1fr_0.8fr]">
        <div className="space-y-5 border border-[#d9ddd3] bg-[#fbfbf8] p-5 sm:p-6">
          <div className="flex items-center justify-between gap-3"><h2 className="text-lg font-medium">1. Select saved route</h2><button type="button" onClick={refreshRoutes} className="text-xs font-medium text-[#557260] hover:underline">Refresh routes</button></div>
          <label className="block text-xs font-medium text-[#59645c]">Saved Mode 1 route<select value={routeId} onChange={(event) => selectRoute(routes.find((route) => route.routeId === event.target.value) || null)} className="mt-2 block w-full border border-[#cbd2c8] bg-white px-3 py-3 text-sm outline-none focus:border-[#557260]"><option value="">Select a saved route…</option>{routes.map((route) => <option value={route.routeId} key={route.routeId}>{route.projectName} · {new Date(route.createdAt).toLocaleString()}</option>)}</select></label>
          {selectedRoute ? <div className="grid gap-3 border-t border-[#e2e5dd] pt-4 sm:grid-cols-2"><div><p className="text-[11px] uppercase tracking-wide text-[#7c887e]">Route ID</p><p className="mt-1 break-all font-mono text-xs">{selectedRoute.routeId}</p></div><div><p className="text-[11px] uppercase tracking-wide text-[#7c887e]">Route details</p><p className="mt-1 text-sm">{summary.routeLengthKm ?? "—"} km · {selectedRoute.facilityMode || "Road"} · {selectedRoute.regionId || "Region unspecified"}</p></div><div className="sm:col-span-2"><p className="text-[11px] uppercase tracking-wide text-[#7c887e]">Stored GIS screening</p><p className="mt-1 text-sm">{findings.length} structured GIS observations saved · {selectedRoute.coordinates?.length || 0} route vertices</p></div><div className="sm:col-span-2 border-t border-[#e2e5dd] pt-3"><p className="text-[11px] uppercase tracking-wide text-[#7c887e]">Route GIS evidence coverage</p><ul className="mt-2 grid gap-1 text-xs sm:grid-cols-2">{gisCoverage.topics.map((topic) => <li key={topic.label} className={topic.present ? "text-[#35543d]" : "text-[#8a4b3d]"}>{topic.present ? "Included" : "No observation in saved payload"} · {topic.label}</li>)}</ul><p className="mt-2 text-[11px] leading-5 text-[#7c887e]">Coverage reflects findings stored with this route. Missing findings are data gaps, not evidence that environmental receptors are absent. Route GIS does not provide field air, noise, water-quality, soil, or seasonal biodiversity measurements.</p></div></div> : <p className="border border-dashed border-[#cbd2c8] p-5 text-sm leading-6 text-[#7c887e]">No saved routes found. Generate an alignment in Mode 1, then choose <strong>Save generated route for EIA</strong>.</p>}
          <div className="border-t border-[#e2e5dd] pt-4"><h2 className="text-lg font-medium">2. Project details</h2><div className="mt-3 grid gap-3 sm:grid-cols-2"><label className="text-xs font-medium text-[#59645c]">Project / corridor name<input value={projectName} onChange={(e) => setProjectName(e.target.value)} className="mt-1 block w-full border border-[#cbd2c8] bg-white px-3 py-2.5 text-sm"/></label><label className="text-xs font-medium text-[#59645c]">Project proponent<input value={projectProponent} onChange={(e) => setProjectProponent(e.target.value)} placeholder="Optional" className="mt-1 block w-full border border-[#cbd2c8] bg-white px-3 py-2.5 text-sm"/></label><label className="text-xs font-medium text-[#59645c]">Study area<input value={studyArea} onChange={(e) => setStudyArea(e.target.value)} className="mt-1 block w-full border border-[#cbd2c8] bg-white px-3 py-2.5 text-sm"/></label><label className="text-xs font-medium text-[#59645c]">State / UT<input value={projectState} onChange={(e) => setProjectState(e.target.value)} className="mt-1 block w-full border border-[#cbd2c8] bg-white px-3 py-2.5 text-sm"/></label><label className="text-xs font-medium text-[#59645c] sm:col-span-2">Assessment date<input type="date" value={assessmentDate} onChange={(e) => setAssessmentDate(e.target.value)} className="mt-1 block w-full border border-[#cbd2c8] bg-white px-3 py-2.5 text-sm"/></label></div></div>
          <div className="border-t border-[#e2e5dd] pt-4"><h2 className="text-lg font-medium">3. Project brief for the report</h2><p className="mt-1 text-xs leading-5 text-[#7c887e]">Use the DPR, approved ToR or other project records where possible. These entries will be cited as operator-provided and unverified. Put units next to figures; do not enter approvals or survey results unless you have a source.</p><div className="mt-3 space-y-3">{[
            ["projectNeed", "Purpose and need", "Why is the corridor proposed? What transport or access need does the project address?"],
            ["projectDescription", "Project description and status", "Greenfield or upgrade, termini, districts, project stage, and any known project reference."],
            ["projectComponents", "Project components and design", "Road cross-section, lanes, right-of-way, bridges, tunnels, interchanges, camps or other works. Include units for figures."],
            ["alternativesConsidered", "Alternatives considered", "Other alignments or options considered, and the source-backed reason for the selected corridor."],
            ["constructionAndOperation", "Construction and operation", "Construction approach, schedule, materials, water, spoil, traffic and operation details, if documented."],
            ["approvalsAndConsultation", "Approvals and consultation", "ToR, environmental/forest/wildlife approvals, public hearing or consultation status. State unknown where not confirmed."],
            ["environmentalStudies", "Available environmental studies", "Baseline survey periods, seasons, methods, stations and results. Do not treat GIS screening as a field survey."],
          ].map(([key, label, placeholder]) => <label key={key} className="block text-xs font-medium text-[#59645c]">{label}<textarea value={projectContext[key]} onChange={(e) => setProjectContext((current) => ({ ...current, [key]: e.target.value }))} placeholder={placeholder} rows={2} maxLength={5000} className="mt-1 block w-full resize-y border border-[#cbd2c8] bg-white px-3 py-2.5 text-sm leading-5 outline-none focus:border-[#557260]"/></label>)}</div></div>
          <button type="button" onClick={generateReport} disabled={!selectedRoute || busy} className="w-full bg-[#294b3b] px-5 py-3.5 text-sm font-semibold text-white hover:bg-[#355f4b] disabled:cursor-not-allowed disabled:opacity-50">{busy ? "Generating report…" : "Generate environmental EIA report"}</button>
        </div>
        <aside className="space-y-4">
          <section className="border border-[#d9ddd3] bg-[#fbfbf8] p-5"><h2 className="font-medium">Evidence included</h2><ul className="mt-3 space-y-2 text-sm leading-6 text-[#59645c]"><li>Saved Mode 1 route screening and evidence IDs</li><li>Environmental documents from the indexed knowledge library</li><li>Legal applicability and evidence limitations reported by the EIA pipeline</li></ul>{library && <p className="mt-4 border-t border-[#e2e5dd] pt-3 text-xs text-[#7c887e]">Library: {library.documentCount} documents · {library.chunkCount} searchable passages</p>}</section>
          {job && <section className="border border-[#d9ddd3] bg-[#fbfbf8] p-5"><div className="flex items-center justify-between"><h2 className="font-medium">Report job</h2><span className="bg-[#edf1eb] px-2 py-1 font-mono text-[10px] text-[#557260]">{job.state}</span></div><p className="mt-3 break-all font-mono text-xs text-[#68736b]">{job.jobId}</p>{job.pageCount != null && <p className="mt-2 text-sm">{job.pageCount} pages</p>}{job.state === "COMPLETED" && <div className="mt-4 flex gap-2"><button type="button" onClick={() => download("pdf")} className="bg-[#294b3b] px-4 py-2 text-sm text-white">Download PDF</button><button type="button" onClick={() => download("document")} className="border border-[#aeb8ad] px-4 py-2 text-sm">Download DOCX</button></div>}</section>}
          <p className="text-xs leading-5 text-[#7c887e]">This is preliminary, desktop decision support. It is not a statutory EIA, clearance, or substitute for field surveys and qualified expert review.</p>
        </aside>
      </section>
    </div>
  </main>;
}
