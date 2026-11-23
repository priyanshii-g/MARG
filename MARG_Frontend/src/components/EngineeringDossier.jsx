import { useMemo, useState } from "react";
import {
  LAYER_CATALOG,
  LAYER_DOMAINS,
  formatClassLabel,
  getSegmentColor,
} from "../layers/layerCatalog";

const CHAPTER_META = [
  { id: "ch1", num: "I", short: "Overview", title: "Executive Alignment Overview & Statutory Clearance" },
  { id: "ch2", num: "II", short: "13-Layer Audit", title: "13-Layer Verification Matrix — Proof of Legitimacy" },
  { id: "ch3", num: "III", short: "Trade-Offs", title: "Multi-Objective Trade-Offs & Alignment Tuning Studio" },
  { id: "ch4", num: "IV", short: "Structures", title: "Civil & Environmental Engineering Structures Schedule" },
  { id: "ch5", num: "V", short: "Spurs & Traffic", title: "Feeder Spurs, Terminal Interchanges & Capacity (IRC:SP:99)" },
  { id: "ch6", num: "VI", short: "Social & RoW", title: "Social Impact, Building Footprints & 2D Right-of-Way (RFCTLARR 2013)" },
];

function verdictPill(verdict) {
  switch (verdict) {
    case "HARD_CONSTRAINT_PASS":
      return { label: "Hard Constraint Passed", cls: "pass" };
    case "VERIFIED_REDUCTION":
      return { label: "Verified Reduction", cls: "verified" };
    case "MITIGATED_BY_DESIGN":
      return { label: "Mitigated by Design", cls: "mitigated" };
    case "WARNING":
      return { label: "Statutory Alert", cls: "warn" };
    default:
      return { label: "Audited & Compliant", cls: "neutral" };
  }
}

function LargeSlopeChart({ profile, onFocusPoint }) {
  const pts = useMemo(() => {
    if (!Array.isArray(profile) || profile.length < 2) return [];
    return profile.filter((p) => typeof p.slopeDeg === "number" && Number.isFinite(p.slopeDeg));
  }, [profile]);

  if (pts.length < 2) return null;

  const width = 520;
  const height = 112;
  const padL = 34;
  const padR = 14;
  const padT = 14;
  const padB = 22;
  const innerW = width - padL - padR;
  const innerH = height - padT - padB;
  const maxCh = Math.max(1, pts[pts.length - 1].chKm || 1);
  const maxSlope = Math.max(32, ...pts.map((p) => p.slopeDeg));

  const xOf = (ch) => padL + (ch / maxCh) * innerW;
  const yOf = (deg) => padT + innerH - (Math.min(deg, maxSlope) / maxSlope) * innerH;

  const pathD = pts
    .map((p, idx) => `${idx === 0 ? "M" : "L"}${xOf(p.chKm).toFixed(1)},${yOf(p.slopeDeg).toFixed(1)}`)
    .join(" ");
  const areaD = `${pathD} L${xOf(pts[pts.length - 1].chKm).toFixed(1)},${(padT + innerH).toFixed(1)} L${padL},${(padT + innerH).toFixed(1)} Z`;
  const limitY = yOf(30);
  const peak = pts.reduce((a, b) => (b.slopeDeg > a.slopeDeg ? b : a), pts[0]);
  const avgSlope = (pts.reduce((acc, p) => acc + p.slopeDeg, 0) / pts.length).toFixed(1);

  return (
    <div className="dossier-slope-card">
      <div className="dossier-slope-header">
        <span>DEM Terrain Gradient Profile along Mainline</span>
        <span className="mono-num">
          Avg <strong>{avgSlope}°</strong> &nbsp;•&nbsp; Peak <strong>{peak.slopeDeg}°</strong> (km {peak.chKm})
        </span>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} className="dossier-slope-svg">
        <line x1={padL} y1={limitY} x2={width - padR} y2={limitY} stroke="#b85c38" strokeWidth="1.2" strokeDasharray="4,4" />
        <text x={4} y={limitY + 3} fontSize="9" fill="#b85c38" fontFamily="IBM Plex Mono">30°</text>
        <text x={8} y={padT + innerH + 3} fontSize="9" fill="#7b8780" fontFamily="IBM Plex Mono">0°</text>
        <path d={areaD} fill="rgba(45, 106, 79, 0.14)" />
        <path d={pathD} fill="none" stroke="#2d6a4f" strokeWidth="2" />
        <circle
          cx={xOf(peak.chKm)}
          cy={yOf(peak.slopeDeg)}
          r="4.5"
          fill="#b85c38"
          stroke="#ffffff"
          strokeWidth="1.5"
          style={{ cursor: "pointer" }}
          onClick={() => onFocusPoint && onFocusPoint(peak.lat, peak.lon, `Peak Slope ${peak.slopeDeg}° @ km ${peak.chKm}`)}
        >
          <title>Click to inspect peak slope on map</title>
        </circle>
        <text x={padL} y={height - 5} fontSize="9" fill="#7b8780" fontFamily="IBM Plex Mono">0 km</text>
        <text x={width - padR} y={height - 5} fontSize="9" fill="#7b8780" fontFamily="IBM Plex Mono" textAnchor="end">
          {maxCh.toFixed(1)} km
        </text>
      </svg>
    </div>
  );
}

function DossierChapter({ id, num, title, summaryBadge, isOpen, onToggle, children }) {
  return (
    <section id={`dossier-${id}`} className={`dossier-chapter ${isOpen ? "open" : "closed"}`}>
      <button type="button" className="dossier-chapter-header" onClick={onToggle}>
        <div className="dossier-chapter-left">
          <span className="dossier-chapter-num">Chapter {num}</span>
          <h2 className="dossier-chapter-title">{title}</h2>
        </div>
        <div className="dossier-chapter-right">
          {summaryBadge && <span className="dossier-chapter-summary">{summaryBadge}</span>}
          <span className={`dossier-chapter-chevron ${isOpen ? "open" : ""}`} aria-hidden="true">
            {isOpen ? "−" : "+"}
          </span>
        </div>
      </button>
      {isOpen && <div className="dossier-chapter-body">{children}</div>}
    </section>
  );
}

export default function EngineeringDossier({
  routeInfo,
  networkSummary,
  tradeOffProfile,
  statutoryClearanceReport,
  layerAudit,
  routeLayerProfile,
  routeDiagnostics,
  terminalRoadConnections,
  spurEngineeringSummary,
  currentFeatures,
  connectedHubs,
  trafficCapacity,
  hubDecisions,
  snapNotice,
  facilityMode,
  presetDefinitions,
  tradeOffPreset,
  envWeight,
  socialWeight,
  directnessWeight,
  isRealigning,
  alternativeRoutes,
  activeAlternativeId,
  comparativeDeltasVsBalanced,
  onSelectPreset,
  onSliderChange,
  onRealign,
  onSwitchArchetype,
  onInspectLayerOnMap,
  onLocateOnMap,
}) {
  // Chapters I & II open by default; Chapters III–VI neatly closed until clicked
  const [openChapters, setOpenChapters] = useState({
    ch1: true,
    ch2: true,
    ch3: false,
    ch4: false,
    ch5: false,
    ch6: false,
  });
  const [structureTab, setStructureTab] = useState("ALL");
  const [showHubLedger, setShowHubLedger] = useState(false);

  const toggleChapter = (id) => {
    setOpenChapters((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  const setAllChapters = (open) => {
    setOpenChapters({
      ch1: open,
      ch2: open,
      ch3: open,
      ch4: open,
      ch5: open,
      ch6: open,
    });
  };

  const jumpToChapter = (id) => {
    setOpenChapters((prev) => ({ ...prev, [id]: true }));
    setTimeout(() => {
      const el = document.getElementById(`dossier-${id}`);
      if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
    }, 40);
  };

  const auditById = useMemo(() => {
    const map = {};
    (layerAudit || []).forEach((r) => {
      if (r?.layerId) map[r.layerId] = r;
    });
    return map;
  }, [layerAudit]);

  const engineeringStructures = useMemo(() => {
    const roles = new Set([
      "RIVER_BRIDGE",
      "GRADE_SEPARATED_CROSSING",
      "WILDLIFE_PASSAGE",
      "GEOTECH_MITIGATION_ZONE",
      "UTILITY_CROSSING_PROTECTION",
      "DRAINAGE_CROSSING_STRUCTURE",
    ]);
    return (currentFeatures || [])
      .filter((f) => roles.has(f.properties?.featureRole) && f.geometry?.type === "Point")
      .map((f, idx) => {
        const p = f.properties || {};
        const [lng, lat] = f.geometry.coordinates;
        const role = p.featureRole;
        let category = "OTHER";
        let icon = "🏗️";
        let title = "Structure";
        let detail = "";

        if (role === "RIVER_BRIDGE") {
          category = "BRIDGES";
          icon = "🌉";
          title = p.riverName || "Perennial River Bridge";
          detail = `Span ${p.estimatedSkewSpanMeters || p.effectiveSkewSpanMeters || "—"} m • Angle ${p.crossingAngleDegrees || "—"}°`;
        } else if (role === "GRADE_SEPARATED_CROSSING") {
          category = "CROSSINGS";
          icon = p.crossedCategory === "RAILWAY" ? "🚊" : "🛣️";
          title = p.crossedCorridorRef || p.crossedCorridorName || (p.crossedCategory === "RAILWAY" ? "Railway ROB" : "Highway Flyover");
          detail = `${formatClassLabel(p.structureType || "Grade-Separated")} • Angle ${p.crossingAngleDegrees || 90}°`;
        } else if (role === "WILDLIFE_PASSAGE") {
          category = "WILDLIFE";
          icon = "🐘";
          title = p.corridorName || "Wildlife Migration Viaduct";
          detail = `Span ${p.recommendedPassageSpanMeters || "—"} m • ${formatClassLabel(p.mitigationStructure || "Elevated Viaduct")}`;
        } else if (role === "GEOTECH_MITIGATION_ZONE") {
          category = "GEOTECH";
          icon = "🪨";
          title = p.name || p.featureName || "Geotech Stabilization Zone";
          detail = `${formatClassLabel(p.mitigationStructure || "Stabilization")} • ${p.recommendedStabilizationLengthMeters || "—"} m`;
        } else if (role === "UTILITY_CROSSING_PROTECTION") {
          category = "UTILITIES";
          icon = "⚡";
          title = p.lineName || p.name || "EHV Transmission Crossing";
          detail = `${formatClassLabel(p.protectionType || "Guard Cradle")} • Span ${p.recommendedProtectiveSpanMeters || "—"} m`;
        } else if (role === "DRAINAGE_CROSSING_STRUCTURE") {
          category = "DRAINAGE";
          icon = "💧";
          title = p.targetFeatureName || "Cross-Drainage Culvert";
          detail = `${formatClassLabel(p.structureType || "Box Culvert")} • Span ${p.recommendedClearSpanOrWidthMeters || "—"} m`;
        }

        return {
          key: `${role}-${idx}`,
          role,
          category,
          icon,
          title,
          detail,
          chainage: p.chainageKm,
          isSpur: p.segmentRole === "FEEDER_SPUR",
          spurHub: p.servedHubName || p.parentSpurId || null,
          lat,
          lng,
        };
      });
  }, [currentFeatures]);

  const filteredStructures = useMemo(() => {
    if (structureTab === "ALL") return engineeringStructures;
    return engineeringStructures.filter((s) => s.category === structureTab);
  }, [engineeringStructures, structureTab]);

  if (!routeInfo) {
    return (
      <div className="dossier-empty-stage">
        <div className="dossier-empty-card">
          <div className="dossier-empty-eyebrow">MARG 13-Layer Geospatial Intelligence</div>
          <h2 className="dossier-empty-heading">Generate an alignment to unveil the full Engineering Dossier</h2>
          <p className="dossier-empty-copy">
            Select one of the three offline-ready regional benchmarks (Assam, Western Ghats, Gangetic Plains)
            or choose any two Indian districts above, then click <strong>Generate MARG Alignment</strong>.
            Six spacious, collapsible verification chapters will open here with full Before-vs-After proof across all 13 layers.
          </p>
        </div>
      </div>
    );
  }

  const forestHa = tradeOffProfile?.environmentalSummary?.forestHectaresDirectRow ?? 0;
  const rowHa = networkSummary?.rowHectares ?? "—";
  const rowWidthM = networkSummary?.rowWidthM ?? (facilityMode === "EXPRESSWAY" ? 60 : 45);
  const vcRatio = trafficCapacity?.projectedVcRatio ?? "—";
  const los = trafficCapacity?.levelOfService ? formatClassLabel(trafficCapacity.levelOfService) : "Free-Flow";
  const auditPassCount = (layerAudit || []).filter((r) => r.verdict !== "WARNING").length;
  const lulcAcqHa = routeDiagnostics?.rightOfWayCorridorSummary?.lulcAcquisitionHectares || null;
  const demoSummary = routeDiagnostics?.populationAndDemographicExposureSummary || null;
  const startConn = terminalRoadConnections?.startRoadConnection || null;
  const goalConn = terminalRoadConnections?.goalRoadConnection || null;

  return (
    <div id="engineering-dossier" className="dossier-wrapper">
      {/* Sticky Mini Chapter Navigation Bar */}
      <nav className="dossier-sticky-nav" aria-label="Engineering Dossier Chapters">
        <div className="dossier-sticky-inner">
          <div className="dossier-nav-pills">
            {CHAPTER_META.map((ch) => (
              <button
                key={ch.id}
                type="button"
                className={`dossier-nav-pill ${openChapters[ch.id] ? "open" : ""}`}
                onClick={() => jumpToChapter(ch.id)}
              >
                <span className="pill-roman">{ch.num}.</span>
                <span>{ch.short}</span>
              </button>
            ))}
          </div>
          <div className="dossier-nav-actions">
            <button type="button" className="dossier-action-link" onClick={() => setAllChapters(true)}>
              Expand All
            </button>
            <span className="dossier-action-sep">·</span>
            <button type="button" className="dossier-action-link" onClick={() => setAllChapters(false)}>
              Collapse All
            </button>
          </div>
        </div>
      </nav>

      {/* Spacious Editorial Container */}
      <div className="dossier-container">
        <header className="dossier-masthead">
          <div className="dossier-eyebrow">
            {routeInfo.tierMode === "TIER_2_PRECACHED_BENCHMARK"
              ? "Offline High-Resolution 13-Layer Benchmark Dossier"
              : "Pan-India Dynamic 13-Layer Alignment Dossier"}
            &nbsp;•&nbsp; {facilityMode === "EXPRESSWAY" ? "Access-Controlled Expressway (IRC:SP:99)" : "National Highway (IRC:SP:84)"}
          </div>
          <h1 className="dossier-main-heading">Corridor Engineering &amp; Verification Dossier</h1>
          <p className="dossier-subtitle">
            CRS {routeInfo.crs} (UTM Zone {routeInfo.utmZone}N) &nbsp;•&nbsp; Active Archetype:{" "}
            <strong>{presetDefinitions[tradeOffPreset]?.shortLabel || tradeOffPreset}</strong>
            &nbsp;•&nbsp; {auditPassCount}/{layerAudit?.length || 13} Spatial Constraints Verified
          </p>
        </header>

        {/* ============================================================
            CHAPTER I — EXECUTIVE OVERVIEW & STATUTORY CLEARANCE
            ============================================================ */}
        <DossierChapter
          id="ch1"
          num="I"
          title="Executive Alignment Overview & Statutory Clearance"
          summaryBadge={`${routeInfo.routeLengthKm} km • ${forestHa} ha Forest • ${statutoryClearanceReport?.hasSanctuaryIntersection ? "NBWL Alert" : "0 PA Violations"}`}
          isOpen={openChapters.ch1}
          onToggle={() => toggleChapter("ch1")}
        >
          {/* 4 Hero Editorial KPI Cards */}
          <div className="hero-kpi-grid">
            <div className="hero-kpi-card">
              <div className="hero-kpi-label">Mainline Length</div>
              <div className="hero-kpi-value">{routeInfo.routeLengthKm} <span className="hero-kpi-unit">km</span></div>
              <div className="hero-kpi-sub">
                Direct Baseline: <span className="mono-num">{routeInfo.baselineLengthKm} km</span> (+{routeInfo.deviationPercent}%)
              </div>
            </div>

            <div className="hero-kpi-card">
              <div className="hero-kpi-label">Direct 2D RoW Take</div>
              <div className="hero-kpi-value">{rowHa} <span className="hero-kpi-unit">ha</span></div>
              <div className="hero-kpi-sub">
                {rowWidthM} m Mainline Swath + 30 m Feeder Spurs
              </div>
            </div>

            <div className="hero-kpi-card">
              <div className="hero-kpi-label">Direct Forest Diversion</div>
              <div className="hero-kpi-value">{forestHa} <span className="hero-kpi-unit">ha</span></div>
              <div className="hero-kpi-sub">
                {forestHa > 0 ? "FCA 1980 Stage-I/II Clearance Scope" : "Zero Forest Canopy Diversion"}
              </div>
            </div>

            <div className="hero-kpi-card">
              <div className="hero-kpi-label">Traffic Capacity (V/C)</div>
              <div className="hero-kpi-value">{vcRatio}</div>
              <div className="hero-kpi-sub">
                {los} &nbsp;•&nbsp; {connectedHubs.length} Connected Hub{connectedHubs.length === 1 ? "" : "s"}
              </div>
            </div>
          </div>

          {/* Statutory Clearance Status Card */}
          {statutoryClearanceReport?.hasSanctuaryIntersection ? (
            <div className="dossier-statutory-alert">
              <div className="statutory-alert-top">
                <span className="statutory-tag-crimson">Mandatory Statutory NBWL &amp; Forest Clearance • WPA 1972 §29/35</span>
                <span className="mono-num">{statutoryClearanceReport.totalSanctuaryLengthKm} km Core Traversal • {statutoryClearanceReport.directSanctuaryForestTakeHectares} ha</span>
              </div>
              <h3 className="statutory-alert-heading">
                {statutoryClearanceReport.legalConsequenceHeadline || "Alignment intersects a notified Wildlife Sanctuary"}
              </h3>
              <p className="statutory-alert-cite">
                {statutoryClearanceReport.legalStatuteCitation || "Wildlife (Protection) Act, 1972 (Section 29/35) & Van (Sanrakshan Evam Samvardhan) Adhiniyam, 2023"}
              </p>
              {statutoryClearanceReport.sanctuaryCrossings?.length > 0 && (
                <div className="statutory-crossings-grid">
                  {statutoryClearanceReport.sanctuaryCrossings.map((sc, i) => (
                    <div key={i} className="statutory-crossing-card">
                      <strong>{sc.sanctuaryName}</strong> ({sc.state} — {sc.district})
                      <div className="mono-num">
                        km {sc.startKm}–{sc.endKm} ({sc.crossingLengthKm} km) • Direct Forest Take: {sc.directForestTakeHectares} ha
                      </div>
                      <div className="statutory-species">Target Species: {sc.keySpecies}</div>
                    </div>
                  ))}
                </div>
              )}
              {statutoryClearanceReport.mandatoryClearancesChecklist?.length > 0 && (
                <ul className="statutory-checklist">
                  {statutoryClearanceReport.mandatoryClearancesChecklist.map((step, i) => (
                    <li key={i}>{step}</li>
                  ))}
                </ul>
              )}
            </div>
          ) : (
            <div className="dossier-statutory-pass">
              <span className="pass-seal">✓</span>
              <div>
                <strong>Zero Statutory Wildlife Sanctuary Breaches (WPA 1972 Compliant)</strong>
                <p>
                  Verified across all 690 notified National Parks &amp; Wildlife Sanctuaries. Mainline and feeder spurs
                  maintain strict legal exclusion outside sanctuary cores.
                </p>
              </div>
            </div>
          )}

          {/* Endpoint Snapping & Terminal Access Summary */}
          {(snapNotice?.start || snapNotice?.end) && (
            <div className="dossier-snap-summary">
              <div className="dossier-subhead">Smart Endpoint Auto-Adjustment Applied</div>
              <div className="dossier-snap-grid">
                {snapNotice.start && (
                  <div className="dossier-snap-box">
                    <div className="snap-box-title">
                      <span>Origin Auto-Shifted</span>
                      <span className="mono-pill">+{snapNotice.start.distanceMeters} m</span>
                    </div>
                    <p>{snapNotice.start.reason}</p>
                    <div className="mono-num snap-coord-line">
                      {snapNotice.start.requested.lat}, {snapNotice.start.requested.lng} → <strong>{snapNotice.start.resolved.lat}, {snapNotice.start.resolved.lng}</strong>
                    </div>
                  </div>
                )}
                {snapNotice.end && (
                  <div className="dossier-snap-box">
                    <div className="snap-box-title">
                      <span>Destination Auto-Shifted</span>
                      <span className="mono-pill">+{snapNotice.end.distanceMeters} m</span>
                    </div>
                    <p>{snapNotice.end.reason}</p>
                    <div className="mono-num snap-coord-line">
                      {snapNotice.end.requested.lat}, {snapNotice.end.requested.lng} → <strong>{snapNotice.end.resolved.lat}, {snapNotice.end.resolved.lng}</strong>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}
        </DossierChapter>

        {/* ============================================================
            CHAPTER II — 13-LAYER VERIFICATION MATRIX (PROOF OF LEGITIMACY)
            ============================================================ */}
        <DossierChapter
          id="ch2"
          num="II"
          title="13-Layer Verification Matrix — Proof of Legitimacy"
          summaryBadge={`${auditPassCount}/${layerAudit?.length || 13} Verified • Before vs. After Baseline Audit`}
          isOpen={openChapters.ch2}
          onToggle={() => toggleChapter("ch2")}
        >
          <p className="dossier-intro-copy">
            Every layer below compares the <strong>Direct Distance Baseline</strong> against the synthesized{" "}
            <strong>13-Layer MARG Alignment</strong>. Click <strong>Inspect on Map ↑</strong> on any card to scroll
            directly to the interactive map studio, switch to that layer&apos;s thematic basemap, and highlight its overlays.
          </p>

          <div className="dossier-domains-container">
            {LAYER_DOMAINS.map((domain) => {
              const domainLayers = LAYER_CATALOG.filter((l) => l.domain === domain.id);
              return (
                <div key={domain.id} className="dossier-domain-block">
                  <div className="dossier-domain-heading">
                    <span className="domain-icon-circle">{domain.icon}</span>
                    <div>
                      <h3>{domain.title}</h3>
                      <p>{domain.description}</p>
                    </div>
                  </div>

                  <div className="dossier-layer-grid">
                    {domainLayers.map((cfg) => {
                      const audit = auditById[cfg.id];
                      const v = audit ? verdictPill(audit.verdict) : { label: "Pending", cls: "neutral" };
                      const comp = audit?.comparison;
                      const maxVal = comp
                        ? Math.max(0.01, Number(comp.baselineValue) || 0, Number(comp.routeValue) || 0)
                        : 1;

                      return (
                        <article
                          key={cfg.id}
                          className={`dossier-layer-card ${cfg.hasSlopeSparkline ? "wide-card" : ""}`}
                          style={{ "--layer-accent": cfg.accentColor }}
                        >
                          <div className="dl-card-top">
                            <div className="dl-card-identity">
                              <span className="dl-num-pill">L{cfg.number}</span>
                              <h4>{cfg.shortName}</h4>
                            </div>
                            <span className={`dl-verdict-pill ${v.cls}`}>{v.label}</span>
                          </div>

                          <div className="dl-standard">
                            {audit?.standard || cfg.description}
                          </div>

                          {audit ? (
                            <>
                              <p className="dl-proof-quote">{audit.headlineProof}</p>

                              {audit.hardConstraintCheck && (
                                <div className={`dl-hard-box ${audit.hardConstraintCheck.passed ? "pass" : "fail"}`}>
                                  <span>{audit.hardConstraintCheck.passed ? "✓" : "!"}</span>
                                  <span>{audit.hardConstraintCheck.detail}</span>
                                </div>
                              )}

                              {comp && (
                                <div className="dl-compare-block">
                                  <div className="dl-compare-head">
                                    <span>{comp.metricLabel}</span>
                                    {typeof comp.deltaPercent === "number" && (
                                      <span className={`dl-delta ${comp.deltaValue <= 0 ? "good" : "neutral"}`}>
                                        {comp.deltaValue > 0 ? "+" : ""}{comp.deltaPercent}%
                                      </span>
                                    )}
                                  </div>
                                  <div className="dl-bar-pair">
                                    <div className="dl-bar-line">
                                      <span className="dl-bar-lbl">Direct Baseline</span>
                                      <div className="dl-bar-track">
                                        <div
                                          className="dl-bar-fill baseline"
                                          style={{ width: `${Math.min(100, ((Number(comp.baselineValue) || 0) / maxVal) * 100)}%` }}
                                        />
                                      </div>
                                      <span className="dl-bar-num">{comp.baselineValue} {comp.unit}</span>
                                    </div>
                                    <div className="dl-bar-line">
                                      <span className="dl-bar-lbl strong">MARG 13-Layer</span>
                                      <div className="dl-bar-track">
                                        <div
                                          className="dl-bar-fill marg"
                                          style={{ width: `${Math.min(100, ((Number(comp.routeValue) || 0) / maxVal) * 100)}%` }}
                                        />
                                      </div>
                                      <span className="dl-bar-num strong">{comp.routeValue} {comp.unit}</span>
                                    </div>
                                  </div>
                                </div>
                              )}

                              {cfg.hasSlopeSparkline && (
                                <LargeSlopeChart profile={routeLayerProfile} onFocusPoint={onLocateOnMap} />
                              )}

                              {audit.exposureBreakdown && Object.keys(audit.exposureBreakdown).length > 0 && (() => {
                                const entries = Object.entries(audit.exposureBreakdown)
                                  .filter(([, val]) => Number(val) > 0)
                                  .sort((a, b) => Number(b[1]) - Number(a[1]));
                                const tot = entries.reduce((acc, [, val]) => acc + Number(val), 0);
                                if (tot <= 0) return null;
                                return (
                                  <div className="dl-exposure">
                                    <div className="dl-exposure-head">
                                      <span>Corridor Exposure Breakdown</span>
                                      {audit.penaltyContributionKmEq > 0 && (
                                        <span className="mono-num">A* Pull: {audit.penaltyContributionKmEq} km-eq</span>
                                      )}
                                    </div>
                                    <div className="dl-stacked-bar">
                                      {entries.map(([k, val]) => (
                                        <div
                                          key={k}
                                          style={{
                                            width: `${(Number(val) / tot) * 100}%`,
                                            background: getSegmentColor(cfg, k),
                                          }}
                                          title={`${formatClassLabel(k)}: ${val}`}
                                        />
                                      ))}
                                    </div>
                                    <div className="dl-exposure-legend">
                                      {entries.slice(0, 5).map(([k, val]) => (
                                        <span key={k} className="dl-exp-chip">
                                          <i style={{ background: getSegmentColor(cfg, k) }} />
                                          {formatClassLabel(k)} <strong>{val}</strong>
                                        </span>
                                      ))}
                                    </div>
                                  </div>
                                );
                              })()}
                            </>
                          ) : (
                            <p className="dl-proof-quote">{cfg.description}</p>
                          )}

                          <div className="dl-card-footer">
                            <span className="dl-fidelity">
                              {audit?.dataFidelity === "FULL_RASTER_AND_VECTOR"
                                ? "Full Offline Raster + Vector"
                                : audit?.dataFidelity === "DYNAMIC_OSM_SRTM_PROXY"
                                  ? "Pan-India Dynamic Synthesis"
                                  : "Regional Raster"}
                            </span>
                            <button
                              type="button"
                              className="dl-inspect-btn"
                              onClick={() => onInspectLayerOnMap && onInspectLayerOnMap(cfg.id)}
                            >
                              Inspect on Map ↑
                            </button>
                          </div>
                        </article>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        </DossierChapter>

        {/* ============================================================
            CHAPTER III — MULTI-OBJECTIVE TRADE-OFFS & ALIGNMENT TUNING
            ============================================================ */}
        <DossierChapter
          id="ch3"
          num="III"
          title="Multi-Objective Trade-Offs & Alignment Tuning Studio"
          summaryBadge={`Pareto Index ${tradeOffProfile?.compositeAlignmentIndex ?? "—"} • ${presetDefinitions[tradeOffPreset]?.shortLabel || tradeOffPreset}`}
          isOpen={openChapters.ch3}
          onToggle={() => toggleChapter("ch3")}
        >
          {/* Route Improvements Headline & 4 Quantified Benefit Cards */}
          {tradeOffProfile?.routeImprovements && (
            <div className="dossier-improvements-panel">
              <div className="imp-eyebrow">IRC:SP:30 &amp; MoRTH Quantitative Design Valuation</div>
              <h3 className="imp-headline">{tradeOffProfile.routeImprovements.headline}</h3>

              {tradeOffProfile.routeImprovements.quantifiedBenefits?.length > 0 && (
                <div className="dossier-benefits-grid">
                  {tradeOffProfile.routeImprovements.quantifiedBenefits.map((b, idx) => (
                    <div key={idx} className="dossier-benefit-card">
                      <div className="benefit-lbl">{b.label}</div>
                      <div className="benefit-val">{b.value}</div>
                      <div className="benefit-det">{b.detail}</div>
                    </div>
                  ))}
                </div>
              )}

              {comparativeDeltasVsBalanced && (
                <div className="dossier-live-deltas">
                  <span className="deltas-caption">Delta vs. Balanced Alignment:</span>
                  <span className={`delta-chip ${comparativeDeltasVsBalanced.deltaKm <= 0 ? "good" : "trade"}`}>
                    {comparativeDeltasVsBalanced.deltaKm <= 0
                      ? `${Math.abs(comparativeDeltasVsBalanced.deltaKm)} km shorter`
                      : `+${comparativeDeltasVsBalanced.deltaKm} km detour`}
                  </span>
                  <span className={`delta-chip ${comparativeDeltasVsBalanced.deltaMins <= 0 ? "good" : "trade"}`}>
                    {comparativeDeltasVsBalanced.deltaMins <= 0
                      ? `${Math.abs(comparativeDeltasVsBalanced.deltaMins)} min faster`
                      : `+${comparativeDeltasVsBalanced.deltaMins} min`}
                  </span>
                  <span className={`delta-chip ${comparativeDeltasVsBalanced.deltaForestHa <= 0 ? "good" : "trade"}`}>
                    {comparativeDeltasVsBalanced.deltaForestHa <= 0
                      ? `${Math.abs(comparativeDeltasVsBalanced.deltaForestHa)} ha forest spared`
                      : `+${comparativeDeltasVsBalanced.deltaForestHa} ha forest`}
                  </span>
                  <span className={`delta-chip ${comparativeDeltasVsBalanced.deltaDisplaced <= 0 ? "good" : "trade"}`}>
                    {comparativeDeltasVsBalanced.deltaDisplaced <= 0
                      ? `${Math.abs(comparativeDeltasVsBalanced.deltaDisplaced)} residents spared`
                      : `+${comparativeDeltasVsBalanced.deltaDisplaced} displaced`}
                  </span>
                </div>
              )}

              <div className="imp-rationale-grid">
                <div className="imp-rationale-box">
                  <strong>Engineering Advantage</strong>
                  <p>{tradeOffProfile.routeImprovements.engineeringAdvantage}</p>
                </div>
                <div className="imp-rationale-box">
                  <strong>Trade-Off Accepted</strong>
                  <p>{tradeOffProfile.routeImprovements.tradeOffAccepted}</p>
                </div>
              </div>
            </div>
          )}

          {/* Archetype Selector Grid */}
          <div className="dossier-subhead" style={{ marginTop: "28px" }}>
            Alignment Optimization Archetypes (Click to Compare Live)
          </div>
          <div className="archetype-studio-grid">
            {Object.values(presetDefinitions).map((p) => {
              const isSelected = tradeOffPreset === p.id;
              return (
                <button
                  key={p.id}
                  type="button"
                  className={`archetype-studio-card ${isSelected ? "active" : ""}`}
                  onClick={() => onSelectPreset(p.id)}
                  disabled={isRealigning}
                >
                  <div className="arch-card-head">
                    <strong>{p.label}</strong>
                    {isSelected && <span className="arch-active-tag">Active</span>}
                  </div>
                  <p>{p.description}</p>
                </button>
              );
            })}
          </div>

          {/* 4 Trade-Off Dimension Scores + Custom Weight Sliders */}
          <div className="tradeoff-split-grid">
            {tradeOffProfile?.dimensionScores && (
              <div className="dossier-subcard">
                <div className="dossier-subhead">Multi-Objective Dimension Scores</div>
                <div className="score-bars-stack">
                  {[
                    ["Environmental Conservation", tradeOffProfile.dimensionScores.environmentalConservation],
                    ["Social & Community Protection", tradeOffProfile.dimensionScores.socialProtection],
                    ["Geometric Directness & Economy", tradeOffProfile.dimensionScores.directnessAndEconomy],
                    ["Regional Hub Connectivity", tradeOffProfile.dimensionScores.regionalConnectivity],
                  ].map(([lbl, val]) => (
                    <div key={lbl} className="score-bar-row">
                      <div className="score-bar-meta">
                        <span>{lbl}</span>
                        <strong className="mono-num">{val ?? 0} / 100</strong>
                      </div>
                      <div className="score-bar-track">
                        <div className="score-bar-fill" style={{ width: `${Math.min(100, Number(val) || 0)}%` }} />
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div className="dossier-subcard">
              <div className="dossier-subhead">Request-Scoped Objective Weighting</div>
              <div className="weight-sliders-stack">
                <label className="weight-slider-item">
                  <div className="weight-meta">
                    <span>Environmental Protection Weight</span>
                    <strong className="mono-num">{envWeight.toFixed(1)}×</strong>
                  </div>
                  <input type="range" min="0.5" max="3.0" step="0.1" value={envWeight} onChange={(e) => onSliderChange("env", e.target.value)} />
                </label>
                <label className="weight-slider-item">
                  <div className="weight-meta">
                    <span>Social &amp; Habitation Avoidance Weight</span>
                    <strong className="mono-num">{socialWeight.toFixed(1)}×</strong>
                  </div>
                  <input type="range" min="0.5" max="3.0" step="0.1" value={socialWeight} onChange={(e) => onSliderChange("social", e.target.value)} />
                </label>
                <label className="weight-slider-item">
                  <div className="weight-meta">
                    <span>Geometric Directness Weight</span>
                    <strong className="mono-num">{directnessWeight.toFixed(1)}×</strong>
                  </div>
                  <input type="range" min="0.5" max="3.0" step="0.1" value={directnessWeight} onChange={(e) => onSliderChange("direct", e.target.value)} />
                </label>
                <button
                  type="button"
                  className="btn-botanical-primary"
                  onClick={() => onRealign()}
                  disabled={isRealigning}
                >
                  {isRealigning ? "Synthesizing Re-Alignment..." : "Synthesize Custom Weighted Alignment"}
                </button>
              </div>
            </div>
          </div>

          {/* Comparative Archetype Matrix Table */}
          {alternativeRoutes.length > 0 && (
            <div className="dossier-subcard" style={{ marginTop: "24px" }}>
              <div className="dossier-subhead">Generated Alternatives Comparison Matrix</div>
              <div className="dossier-table-wrap">
                <table className="dossier-table">
                  <thead>
                    <tr>
                      <th>Archetype</th>
                      <th>Length</th>
                      <th>Deviation</th>
                      <th>Direct Forest</th>
                      <th>Displaced Pop.</th>
                      <th>Pareto Index</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {alternativeRoutes.map((alt) => {
                      const isActive = alt.id === activeAlternativeId;
                      return (
                        <tr key={alt.id} className={isActive ? "active-row" : ""}>
                          <td><strong>{alt.label}</strong></td>
                          <td className="mono-num">{alt.routeLengthKm} km</td>
                          <td className="mono-num">+{alt.deviationPercent}%</td>
                          <td className="mono-num">{alt.forestHa} ha</td>
                          <td className="mono-num">{alt.displacedPop}</td>
                          <td className="mono-num"><strong>{alt.compositeIndex}</strong></td>
                          <td>
                            <button
                              type="button"
                              className="table-switch-btn"
                              onClick={() => onSwitchArchetype(alt.preset)}
                              disabled={isActive}
                            >
                              {isActive ? "Active" : "View"}
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </DossierChapter>

        {/* ============================================================
            CHAPTER IV — CIVIL & ENVIRONMENTAL ENGINEERING STRUCTURES
            ============================================================ */}
        <DossierChapter
          id="ch4"
          num="IV"
          title="Civil & Environmental Engineering Structures Schedule"
          summaryBadge={`${engineeringStructures.length} Engineered Structures (Mainline + Feeder Spurs)`}
          isOpen={openChapters.ch4}
          onToggle={() => toggleChapter("ch4")}
        >
          <div className="structures-kpi-strip">
            <div className="mini-kpi"><span>River Bridges</span><strong>{networkSummary?.riverBridges ?? 0}</strong><em>{networkSummary?.riverBridgeSpanM ?? 0} m span</em></div>
            <div className="mini-kpi"><span>Rail ROBs</span><strong>{networkSummary?.railwayRobs ?? 0}</strong><em>Grade-separated</em></div>
            <div className="mini-kpi"><span>Road Flyovers</span><strong>{networkSummary?.roadFlyovers ?? 0}</strong><em>Mainline crossings</em></div>
            <div className="mini-kpi"><span>Wildlife Viaducts</span><strong>{networkSummary?.wildlifePassages ?? 0}</strong><em>{networkSummary?.wildlifeSpanM ?? 0} m WII span</em></div>
            <div className="mini-kpi"><span>Drainage Culverts</span><strong>{networkSummary?.drainageStructures ?? 0}</strong><em>IRC:SP:13 sizing</em></div>
            <div className="mini-kpi"><span>Spur Structures</span><strong>{spurEngineeringSummary?.totalSpurEngineeringStructuresCount ?? 0}</strong><em>Across {spurEngineeringSummary?.spursEvaluated ?? 0} spurs</em></div>
          </div>

          <div className="structure-filter-tabs">
            {[
              ["ALL", `All Structures (${engineeringStructures.length})`],
              ["BRIDGES", "River Bridges"],
              ["CROSSINGS", "ROBs & Flyovers"],
              ["WILDLIFE", "Wildlife Viaducts"],
              ["GEOTECH", "Geotech Zones"],
              ["UTILITIES", "EHV Utilities"],
              ["DRAINAGE", "Culverts & Aqueducts"],
            ].map(([key, label]) => (
              <button
                key={key}
                type="button"
                className={`struct-tab-btn ${structureTab === key ? "active" : ""}`}
                onClick={() => setStructureTab(key)}
              >
                {label}
              </button>
            ))}
          </div>

          {filteredStructures.length === 0 ? (
            <div className="dossier-empty-inline">No crossing structures recorded in this category along the corridor.</div>
          ) : (
            <div className="structures-schedule-grid">
              {filteredStructures.map((st) => (
                <div key={st.key} className="structure-schedule-card">
                  <div className="struct-card-top">
                    <span className="struct-icon">{st.icon}</span>
                    <div className="struct-meta">
                      <strong>{st.title}</strong>
                      <span>{st.detail}</span>
                    </div>
                    {st.isSpur && <span className="spur-pill">Spur • {st.spurHub}</span>}
                  </div>
                  <div className="struct-card-foot">
                    <span className="mono-num">
                      {st.chainage != null ? `Chainage km ${st.chainage}` : `${st.lat.toFixed(4)}, ${st.lng.toFixed(4)}`}
                    </span>
                    <button
                      type="button"
                      className="dl-inspect-btn"
                      onClick={() => onLocateOnMap && onLocateOnMap(st.lat, st.lng, st.title)}
                    >
                      Locate on Map ↑
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </DossierChapter>

        {/* ============================================================
            CHAPTER V — FEEDER SPURS, TERMINALS & TRAFFIC CAPACITY
            ============================================================ */}
        <DossierChapter
          id="ch5"
          num="V"
          title="Feeder Spurs, Terminal Interchanges & Capacity (IRC:SP:99)"
          summaryBadge={`${connectedHubs.length} Hubs • ${networkSummary?.feederSpurKm ?? 0} km Spurs • V/C ${vcRatio}`}
          isOpen={openChapters.ch5}
          onToggle={() => toggleChapter("ch5")}
        >
          {/* Terminal Arterial Tie-Ins */}
          <div className="terminal-cards-grid">
            <div className="dossier-subcard">
              <div className="dossier-subhead">Origin Terminal Interchange &amp; Tie-In</div>
              <div className="terminal-road-title">
                {startConn?.existingRoadRef || networkSummary?.startRoadRef || "Existing Arterial Highway"}
              </div>
              <p className="terminal-road-meta">
                {startConn?.existingRoadName || "Classified Arterial Tie-In"} &nbsp;•&nbsp;{" "}
                <strong>{formatClassLabel(startConn?.roadCategory || startConn?.category || "NATIONAL_HIGHWAY")}</strong>
              </p>
              <div className="terminal-spec-pills">
                <span>{formatClassLabel(startConn?.terminalJunctionType || (facilityMode === "EXPRESSWAY" ? "Trumpet Grade-Separated Interchange" : "At-Grade Arterial Junction"))}</span>
                {startConn?.requiresTollBarrierPlaza && <span>Barrier Toll Plaza (120 m RoW)</span>}
                <span>Connector: {startConn?.connectorLengthKm ?? "0.0"} km</span>
              </div>
            </div>

            <div className="dossier-subcard">
              <div className="dossier-subhead">Destination Terminal Interchange &amp; Tie-In</div>
              <div className="terminal-road-title">
                {goalConn?.existingRoadRef || networkSummary?.goalRoadRef || "Existing Arterial Highway"}
              </div>
              <p className="terminal-road-meta">
                {goalConn?.existingRoadName || "Classified Arterial Tie-In"} &nbsp;•&nbsp;{" "}
                <strong>{formatClassLabel(goalConn?.roadCategory || goalConn?.category || "NATIONAL_HIGHWAY")}</strong>
              </p>
              <div className="terminal-spec-pills">
                <span>{formatClassLabel(goalConn?.terminalJunctionType || (facilityMode === "EXPRESSWAY" ? "Trumpet Grade-Separated Interchange" : "At-Grade Arterial Junction"))}</span>
                {goalConn?.requiresTollBarrierPlaza && <span>Barrier Toll Plaza (120 m RoW)</span>}
                <span>Connector: {goalConn?.connectorLengthKm ?? "0.0"} km</span>
              </div>
            </div>
          </div>

          {/* Traffic Capacity Analysis */}
          {trafficCapacity && (
            <div className="dossier-subcard" style={{ marginTop: "24px" }}>
              <div className="dossier-subhead">IRC:SP:84 Traffic Volume-to-Capacity Analysis</div>
              <div className="capacity-metrics-row">
                <div><span>Projected Peak Traffic</span><strong className="mono-num">{Math.round(trafficCapacity.projectedTotalPeakTrafficPcuHr || 0)} PCU/hr</strong></div>
                <div><span>Design Capacity</span><strong className="mono-num">{Math.round(trafficCapacity.designCapacityPcuHr || 4800)} PCU/hr</strong></div>
                <div><span>Projected V/C Ratio</span><strong className="mono-num">{trafficCapacity.projectedVcRatio ?? "—"}</strong></div>
                <div><span>Level of Service</span><strong>{los}</strong></div>
              </div>
              {trafficCapacity.capacityStandardNote && (
                <p className="capacity-note">{trafficCapacity.capacityStandardNote}</p>
              )}
            </div>
          )}

          {/* Connected Regional Hubs & Feeder Spurs */}
          {connectedHubs.length > 0 && (
            <div className="dossier-subcard" style={{ marginTop: "24px" }}>
              <div className="dossier-subhead">Connected Regional Growth Hubs ({connectedHubs.length})</div>
              <div className="hubs-cards-grid">
                {connectedHubs.map((h, i) => (
                  <div key={i} className="hub-dossier-card">
                    <div className="hub-dossier-top">
                      <strong>{h.name}</strong>
                      <span className="mono-pill">{h.spurKm} km Spur</span>
                    </div>
                    <div className="hub-dossier-meta">
                      Tie-In: <strong>{h.tieInRoadRef}</strong> • Induced Peak: +{h.traffic} PCU/hr • Catchment: {Number(h.population || 0).toLocaleString()}
                    </div>
                    {h.decisionReason && <p className="hub-dossier-reason">{h.decisionReason}</p>}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Expandable Full Hub Acceptance/Exclusion Ledger */}
          {hubDecisions.length > 0 && (
            <div style={{ marginTop: "20px" }}>
              <button
                type="button"
                className="btn-botanical-outline"
                onClick={() => setShowHubLedger((v) => !v)}
              >
                {showHubLedger ? "Hide Hub Evaluation Audit Ledger" : `Inspect Full Hub Evaluation Ledger (${hubDecisions.length} Towns Audited)`}
              </button>
              {showHubLedger && (
                <div className="dossier-table-wrap" style={{ marginTop: "14px" }}>
                  <table className="dossier-table">
                    <thead>
                      <tr>
                        <th>Hub / Town</th>
                        <th>Decision</th>
                        <th>Lateral Dist</th>
                        <th>Induced Traffic</th>
                        <th>Engineering Rationale</th>
                      </tr>
                    </thead>
                    <tbody>
                      {hubDecisions.map((d, idx) => (
                        <tr key={idx}>
                          <td><strong>{d.name}</strong> {d.district ? `(${d.district})` : ""}</td>
                          <td>
                            <span className={`dl-verdict-pill ${d.accepted ? "pass" : "neutral"}`}>
                              {d.status || (d.accepted ? "Accepted" : "Excluded")}
                            </span>
                          </td>
                          <td className="mono-num">{d.distanceToCenterlineKm ?? "—"} km</td>
                          <td className="mono-num">+{d.inducedPeakTrafficPcuHr ?? 0} PCU/hr</td>
                          <td>{d.reason}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </DossierChapter>

        {/* ============================================================
            CHAPTER VI — SOCIAL IMPACT, BUILDINGS & 2D RoW (RFCTLARR 2013)
            ============================================================ */}
        <DossierChapter
          id="ch6"
          num="VI"
          title="Social Impact, Building Footprints & 2D Right-of-Way (RFCTLARR 2013)"
          summaryBadge={`${rowHa} ha RoW • ${networkSummary?.buildingsInRow ?? 0} Direct RoW Structures • ${networkSummary?.displacedPop ?? 0} Displaced`}
          isOpen={openChapters.ch6}
          onToggle={() => toggleChapter("ch6")}
        >
          <div className="social-row-grid">
            <div className="dossier-subcard">
              <div className="dossier-subhead">2D Right-of-Way Land Acquisition Breakdown</div>
              <div className="social-big-nums">
                <div><span>Total Direct RoW</span><strong className="mono-num">{rowHa} ha</strong></div>
                <div><span>Mainline Width</span><strong className="mono-num">{rowWidthM} m</strong></div>
                <div><span>Feeder Spur Width</span><strong className="mono-num">30 m</strong></div>
              </div>
              {lulcAcqHa && Object.keys(lulcAcqHa).length > 0 && (
                <div className="lulc-ha-list">
                  {Object.entries(lulcAcqHa)
                    .filter(([, v]) => Number(v) > 0)
                    .sort((a, b) => Number(b[1]) - Number(a[1]))
                    .map(([k, v]) => (
                      <div key={k} className="lulc-ha-row">
                        <span>{formatClassLabel(k)}</span>
                        <strong className="mono-num">{v} ha</strong>
                      </div>
                    ))}
                </div>
              )}
            </div>

            <div className="dossier-subcard">
              <div className="dossier-subhead">Building Footprints &amp; Demographic Exposure (RFCTLARR 2013)</div>
              <div className="social-big-nums">
                <div><span>Direct RoW Buildings</span><strong className="mono-num">{networkSummary?.buildingsInRow ?? 0}</strong></div>
                <div><span>Displaced Households</span><strong className="mono-num">{networkSummary?.displacedHouseholds ?? 0}</strong></div>
                <div><span>Displaced Residents</span><strong className="mono-num">{networkSummary?.displacedPop ?? 0}</strong></div>
              </div>
              {demoSummary && (
                <div className="lulc-ha-list">
                  <div className="lulc-ha-row">
                    <span>Population within 250 m Environmental Buffer</span>
                    <strong className="mono-num">{Number(demoSummary.populationWithin250mEnvironmentalBuffer || 0).toLocaleString()}</strong>
                  </div>
                  <div className="lulc-ha-row">
                    <span>Population within 500 m Secondary Buffer</span>
                    <strong className="mono-num">{Number(demoSummary.populationWithin500mSecondaryBuffer || 0).toLocaleString()}</strong>
                  </div>
                  <div className="lulc-ha-row">
                    <span>Vulnerable-Age Population (250 m)</span>
                    <strong className="mono-num">{Number(demoSummary.vulnerableAgePopulationExposedWithin250m || 0).toLocaleString()}</strong>
                  </div>
                  <div className="lulc-ha-row">
                    <span>Scheduled Tribe &amp; Caste Exposure (250 m)</span>
                    <strong className="mono-num">{Number(demoSummary.scheduledTribeAndCastesExposedWithin250m || 0).toLocaleString()}</strong>
                  </div>
                </div>
              )}
              {networkSummary?.buildingsByType && Object.keys(networkSummary.buildingsByType).length > 0 && (
                <div className="building-type-pills">
                  {Object.entries(networkSummary.buildingsByType)
                    .filter(([, cnt]) => Number(cnt) > 0)
                    .map(([type, cnt]) => (
                      <span key={type} className="mono-pill">
                        {formatClassLabel(type)}: {cnt}
                      </span>
                    ))}
                </div>
              )}
            </div>
          </div>
        </DossierChapter>
      </div>
    </div>
  );
}
