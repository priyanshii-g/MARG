import { useMemo, useState } from "react";
import {
  LAYER_CATALOG,
  LAYER_DOMAINS,
  getSegmentColor,
} from "../layers/layerCatalog";
import {
  humanizeClassLabel,
  humanizeDataFidelity,
  humanizeLayerPurpose,
  humanizeMetricLabel,
  humanizeText,
  humanizeTrafficFlow,
  humanizeUnit,
} from "../utils/plainLanguage";
import PublicSummaryCard from "./PublicSummaryCard";

const CHAPTER_META = [
  { id: "ch1", num: "I", short: "Overview", title: "Route Summary & Wildlife Safety" },
  { id: "ch2", num: "II", short: "13 Checks", title: "13 Nature, Safety & Community Checks" },
  { id: "ch3", num: "III", short: "Route Options", title: "Compare Route Priorities (Nature vs. Homes vs. Speed)" },
  { id: "ch4", num: "IV", short: "Bridges & Crossings", title: "Planned Bridges, Flyovers & Safety Structures" },
  { id: "ch5", num: "V", short: "Cities & Traffic", title: "Connected Towns, Exits & Traffic Comfort" },
  { id: "ch6", num: "VI", short: "Land & People", title: "Land Needed, Homes & Nearby Communities" },
];

function verdictPill(verdict) {
  switch (verdict) {
    case "HARD_CONSTRAINT_PASS":
      return { label: "Safety Rule Met", cls: "pass" };
    case "VERIFIED_REDUCTION":
      return { label: "Risk Reduced", cls: "verified" };
    case "MITIGATED_BY_DESIGN":
      return { label: "Protected by Design", cls: "mitigated" };
    case "WARNING":
      return { label: "Needs Special Approval", cls: "warn" };
    default:
      return { label: "Checked & Safe", cls: "neutral" };
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
        <span>Hill Steepness Along This Route (30° is the road safety limit)</span>
        <span className="mono-num">
          Average <strong>{avgSlope}°</strong> &nbsp;·&nbsp; Steepest <strong>{peak.slopeDeg}°</strong> ({peak.chKm} km from start)
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
          onClick={() => onFocusPoint && onFocusPoint(peak.lat, peak.lon, `Steepest Point (${peak.slopeDeg}°) at ${peak.chKm} km from start`)}
        >
          <title>Click to view the steepest point on the map</title>
        </circle>
        <text x={padL} y={height - 5} fontSize="9" fill="#7b8780" fontFamily="IBM Plex Mono">Start</text>
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
          <span className="dossier-chapter-num">Section {num}</span>
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
  corridorName,
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
  // Sections I & II open by default; Sections III–VI neatly closed until clicked
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
        let title = "Planned Structure";
        let detail = "";

        if (role === "RIVER_BRIDGE") {
          category = "BRIDGES";
          icon = "🌉";
          title = p.riverName || "Major River Bridge";
          detail = `Bridge length: ${p.estimatedSkewSpanMeters || p.effectiveSkewSpanMeters || "—"} meters · Crossing angle: ${p.crossingAngleDegrees || "—"}°`;
        } else if (role === "GRADE_SEPARATED_CROSSING") {
          category = "CROSSINGS";
          icon = p.crossedCategory === "RAILWAY" ? "🚊" : "🛣️";
          title = p.crossedCorridorRef || p.crossedCorridorName || (p.crossedCategory === "RAILWAY" ? "Railway Overbridge" : "Road Flyover");
          detail = `${humanizeClassLabel(p.structureType || "OVERPASS")} · Safe no-stop crossing`;
        } else if (role === "WILDLIFE_PASSAGE") {
          category = "WILDLIFE";
          icon = "🐘";
          title = p.corridorName || "Safe Wildlife Crossing";
          detail = `Length: ${p.recommendedPassageSpanMeters || "—"} meters · ${humanizeClassLabel(p.mitigationStructure || "ELEVATED_VIADUCT")}`;
        } else if (role === "GEOTECH_MITIGATION_ZONE") {
          category = "GEOTECH";
          icon = "🪨";
          title = p.name || p.featureName || "Hillside & Soil Safety Support";
          detail = `${humanizeClassLabel(p.mitigationStructure || "ANCHORED_RETAINING_WALL")} · ${p.recommendedStabilizationLengthMeters || "—"} meters long`;
        } else if (role === "UTILITY_CROSSING_PROTECTION") {
          category = "UTILITIES";
          icon = "⚡";
          title = p.lineName || p.name || "High-Voltage Power Line Crossing";
          detail = `${humanizeClassLabel(p.protectionType || "GUARD_CRADLE")} · ${p.recommendedProtectiveSpanMeters || "—"} meters guard span`;
        } else if (role === "DRAINAGE_CROSSING_STRUCTURE") {
          category = "DRAINAGE";
          icon = "💧";
          title = p.targetFeatureName || "Rainwater & Stream Drainage Tunnel";
          detail = `${humanizeClassLabel(p.structureType || "BOX_CULVERT")} · ${p.recommendedClearSpanOrWidthMeters || "—"} meters wide`;
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
          <div className="dossier-empty-eyebrow">PUBLIC ROUTE IMPACT GUIDE</div>
          <h2 className="dossier-empty-heading">Find a route above to read its complete Public Impact Report</h2>
          <p className="dossier-empty-copy">
            Choose one of our sample regions (Assam, Western Ghats, or Uttar Pradesh) or pick any two places in India above,
            then click <strong>Find Best Route</strong>. Six clear, easy-to-read sections will open here showing how the road
            affects forests, rivers, wildlife, nearby towns, homes, and daily travel.
          </p>
        </div>
      </div>
    );
  }

  const forestHa = tradeOffProfile?.environmentalSummary?.forestHectaresDirectRow ?? 0;
  const rowHa = networkSummary?.rowHectares ?? "—";
  const rowWidthM = networkSummary?.rowWidthM ?? (facilityMode === "EXPRESSWAY" ? 60 : 45);
  const vcRatio = trafficCapacity?.projectedVcRatio ?? "—";
  const flowInfo = humanizeTrafficFlow(vcRatio, trafficCapacity?.levelOfService);
  const auditPassCount = (layerAudit || []).filter((r) => r.verdict !== "WARNING").length;
  const lulcAcqHa = routeDiagnostics?.rightOfWayCorridorSummary?.lulcAcquisitionHectares || null;
  const demoSummary = routeDiagnostics?.populationAndDemographicExposureSummary || null;
  const startConn = terminalRoadConnections?.startRoadConnection || null;
  const goalConn = terminalRoadConnections?.goalRoadConnection || null;

  // Support both backend keys ("scores") and fallback keys ("dimensionScores")
  const dimScores = tradeOffProfile?.scores || tradeOffProfile?.dimensionScores || null;

  return (
    <div id="engineering-dossier" className="dossier-wrapper">
      {/* Sticky Mini Section Navigation Bar */}
      <nav className="dossier-sticky-nav" aria-label="Public Impact Report Sections">
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
              Open All Sections
            </button>
            <span className="dossier-action-sep">·</span>
            <button type="button" className="dossier-action-link" onClick={() => setAllChapters(false)}>
              Close All
            </button>
          </div>
        </div>
      </nav>

      {/* Spacious Editorial Container */}
      <div className="dossier-container">
        <header className="dossier-masthead">
          <div className="dossier-eyebrow">
            {routeInfo.tierMode === "TIER_2_PRECACHED_BENCHMARK"
              ? "High-Detail Regional Public Report"
              : "All-India Public Route Impact Report"}
            &nbsp;·&nbsp;{" "}
            {facilityMode === "EXPRESSWAY"
              ? "Access-Controlled Expressway"
              : facilityMode === "STATE_HIGHWAY"
                ? "State Highway"
                : "National Highway"}
          </div>
          <h1 className="dossier-main-heading">Public Route &amp; Impact Report</h1>
          <p className="dossier-subtitle">
            Route Style: <strong>{presetDefinitions[tradeOffPreset]?.shortLabel || tradeOffPreset}</strong>
            &nbsp;·&nbsp; {auditPassCount} of {layerAudit?.length || 13} Safety &amp; Nature Checks Passed
          </p>
        </header>

        {/* ============================================================
            PLAIN-ENGLISH NATURAL-LANGUAGE SUMMARY CARD FOR CITIZENS
            ============================================================ */}
        <PublicSummaryCard
          corridorName={corridorName}
          facilityMode={facilityMode}
          routeStyleLabel={presetDefinitions[tradeOffPreset]?.shortLabel || tradeOffPreset}
          routeInfo={routeInfo}
          networkSummary={networkSummary}
          tradeOffProfile={tradeOffProfile}
          statutoryClearanceReport={statutoryClearanceReport}
          routeDiagnostics={routeDiagnostics}
          connectedHubs={connectedHubs}
          trafficCapacity={trafficCapacity}
          layerAudit={layerAudit}
          snapNotice={snapNotice}
        />

        {/* ============================================================
            SECTION I — ROUTE SUMMARY & WILDLIFE SAFETY
            ============================================================ */}
        <DossierChapter
          id="ch1"
          num="I"
          title="Route Summary & Wildlife Safety"
          summaryBadge={`${routeInfo.routeLengthKm} km Road · ${forestHa} ha Forest · ${statutoryClearanceReport?.hasSanctuaryIntersection ? "Park Review Needed" : "0 Parks Crossed"}`}
          isOpen={openChapters.ch1}
          onToggle={() => toggleChapter("ch1")}
        >
          {/* 4 Hero Editorial KPI Cards */}
          <div className="hero-kpi-grid">
            <div className="hero-kpi-card">
              <div className="hero-kpi-label">Total Road Length</div>
              <div className="hero-kpi-value">{routeInfo.routeLengthKm} <span className="hero-kpi-unit">km</span></div>
              <div className="hero-kpi-sub">
                Straight-line distance: <span className="mono-num">{routeInfo.baselineLengthKm} km</span> (+{routeInfo.deviationPercent}% detour to avoid obstacles)
              </div>
            </div>

            <div className="hero-kpi-card">
              <div className="hero-kpi-label">Total Land Needed</div>
              <div className="hero-kpi-value">{rowHa} <span className="hero-kpi-unit">hectares</span></div>
              <div className="hero-kpi-sub">
                Based on {rowWidthM} m main road width plus 30 m connecting town roads
              </div>
            </div>

            <div className="hero-kpi-card">
              <div className="hero-kpi-label">Forest Land Affected</div>
              <div className="hero-kpi-value">{forestHa} <span className="hero-kpi-unit">hectares</span></div>
              <div className="hero-kpi-sub">
                {forestHa > 0 ? "Needs official forest approval & tree replanting" : "Zero forest land disturbed"}
              </div>
            </div>

            <div className="hero-kpi-card">
              <div className="hero-kpi-label">Expected Traffic Flow</div>
              <div className="hero-kpi-value" style={{ fontSize: "clamp(24px, 2.2vw, 30px)" }}>{flowInfo.label}</div>
              <div className="hero-kpi-sub">
                {flowInfo.detail} · {connectedHubs.length} {connectedHubs.length === 1 ? "town" : "towns"} connected
              </div>
            </div>
          </div>

          {/* Wildlife Park Protection Status Card */}
          {statutoryClearanceReport?.hasSanctuaryIntersection ? (
            <div className="dossier-statutory-alert">
              <div className="statutory-alert-top">
                <span className="statutory-tag-crimson">Special Wildlife Board &amp; Forest Approval Required</span>
                <span className="mono-num">{statutoryClearanceReport.totalSanctuaryLengthKm} km Inside Protected Park · {statutoryClearanceReport.directSanctuaryForestTakeHectares} hectares</span>
              </div>
              <h3 className="statutory-alert-heading">
                {humanizeText(statutoryClearanceReport.legalConsequenceHeadline || "This route crosses a protected Wildlife Sanctuary")}
              </h3>
              <p className="statutory-alert-cite">
                Under India&apos;s Wildlife Protection Law, building a road inside a National Park or Wildlife Sanctuary requires strict permission from the National Wildlife Board and Environment Ministry before work can begin.
              </p>
              {statutoryClearanceReport.sanctuaryCrossings?.length > 0 && (
                <div className="statutory-crossings-grid">
                  {statutoryClearanceReport.sanctuaryCrossings.map((sc, i) => (
                    <div key={i} className="statutory-crossing-card">
                      <strong>{sc.sanctuaryName}</strong> ({sc.state} — {sc.district})
                      <div className="mono-num">
                        {sc.startKm}–{sc.endKm} km from start ({sc.crossingLengthKm} km inside park) · Forest area: {sc.directForestTakeHectares} hectares
                      </div>
                      <div className="statutory-species">Protected Wildlife: {sc.keySpecies}</div>
                    </div>
                  ))}
                </div>
              )}
              {statutoryClearanceReport.mandatoryClearancesChecklist?.length > 0 && (
                <ul className="statutory-checklist">
                  {statutoryClearanceReport.mandatoryClearancesChecklist.map((step, i) => (
                    <li key={i}>{humanizeText(step)}</li>
                  ))}
                </ul>
              )}
            </div>
          ) : (
            <div className="dossier-statutory-pass">
              <span className="pass-seal">✓</span>
              <div>
                <strong>No National Parks or Wildlife Sanctuaries Crossed</strong>
                <p>
                  Checked against all 690 official National Parks and Wildlife Sanctuaries across India. Both the main
                  highway and all connecting side roads stay completely outside protected park boundaries.
                </p>
              </div>
            </div>
          )}

          {/* Start/End Safety Shift Summary */}
          {(snapNotice?.start || snapNotice?.end) && (
            <div className="dossier-snap-summary">
              <div className="dossier-subhead">Start / End Point Gently Moved for Safety</div>
              <div className="dossier-snap-grid">
                {snapNotice.start && (
                  <div className="dossier-snap-box">
                    <div className="snap-box-title">
                      <span>Starting Point Shifted</span>
                      <span className="mono-pill">Moved {snapNotice.start.distanceMeters} m</span>
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
                      <span>Destination Shifted</span>
                      <span className="mono-pill">Moved {snapNotice.end.distanceMeters} m</span>
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
            SECTION II — 13 NATURE, SAFETY & COMMUNITY CHECKS
            ============================================================ */}
        <DossierChapter
          id="ch2"
          num="II"
          title="13 Nature, Safety & Community Checks"
          summaryBadge={`${auditPassCount} of ${layerAudit?.length || 13} Passed · Straight Line vs. Smart Route`}
          isOpen={openChapters.ch2}
          onToggle={() => toggleChapter("ch2")}
        >
          <p className="dossier-intro-copy">
            Each card below compares a simple <strong>Straight-Line Route</strong> against the{" "}
            <strong>MARG Smart Route</strong> so anyone can verify how much flood risk, steep terrain, forest loss,
            and neighborhood disruption was avoided. Click <strong>See on Map ↑</strong> on any topic to view it live on the map above.
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
                      const v = audit ? verdictPill(audit.verdict) : { label: "Waiting", cls: "neutral" };
                      const comp = audit?.comparison;
                      const maxVal = comp
                        ? Math.max(0.01, Number(comp.baselineValue) || 0, Number(comp.routeValue) || 0)
                        : 1;
                      const displayUnit = comp ? humanizeUnit(comp.unit) : "";

                      return (
                        <article
                          key={cfg.id}
                          className={`dossier-layer-card ${cfg.hasSlopeSparkline ? "wide-card" : ""}`}
                          style={{ "--layer-accent": cfg.accentColor }}
                        >
                          <div className="dl-card-top">
                            <div className="dl-card-identity">
                              <span className="dl-num-pill">#{cfg.number}</span>
                              <h4>{cfg.shortName}</h4>
                            </div>
                            <span className={`dl-verdict-pill ${v.cls}`}>{v.label}</span>
                          </div>

                          <div className="dl-standard">
                            {humanizeLayerPurpose(cfg.id)}
                          </div>

                          {audit ? (
                            <>
                              <p className="dl-proof-quote">{humanizeText(audit.headlineProof)}</p>

                              {audit.hardConstraintCheck && (
                                <div className={`dl-hard-box ${audit.hardConstraintCheck.passed ? "pass" : "fail"}`}>
                                  <span>{audit.hardConstraintCheck.passed ? "✓" : "!"}</span>
                                  <span>{humanizeText(audit.hardConstraintCheck.detail)}</span>
                                </div>
                              )}

                              {comp && (
                                <div className="dl-compare-block">
                                  <div className="dl-compare-head">
                                    <span>{humanizeMetricLabel(comp.metricLabel)}</span>
                                    {typeof comp.deltaPercent === "number" && (
                                      <span className={`dl-delta ${comp.deltaValue <= 0 ? "good" : "neutral"}`}>
                                        {comp.deltaValue > 0 ? "+" : ""}{comp.deltaPercent}%
                                      </span>
                                    )}
                                  </div>
                                  <div className="dl-bar-pair">
                                    <div className="dl-bar-line">
                                      <span className="dl-bar-lbl">Straight Line</span>
                                      <div className="dl-bar-track">
                                        <div
                                          className="dl-bar-fill baseline"
                                          style={{ width: `${Math.min(100, ((Number(comp.baselineValue) || 0) / maxVal) * 100)}%` }}
                                        />
                                      </div>
                                      <span className="dl-bar-num">{comp.baselineValue} {displayUnit}</span>
                                    </div>
                                    <div className="dl-bar-line">
                                      <span className="dl-bar-lbl strong">Smart Route</span>
                                      <div className="dl-bar-track">
                                        <div
                                          className="dl-bar-fill marg"
                                          style={{ width: `${Math.min(100, ((Number(comp.routeValue) || 0) / maxVal) * 100)}%` }}
                                        />
                                      </div>
                                      <span className="dl-bar-num strong">{comp.routeValue} {displayUnit}</span>
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
                                      <span>Breakdown Along the Route</span>
                                    </div>
                                    <div className="dl-stacked-bar">
                                      {entries.map(([k, val]) => (
                                        <div
                                          key={k}
                                          style={{
                                            width: `${(Number(val) / tot) * 100}%`,
                                            background: getSegmentColor(cfg, k),
                                          }}
                                          title={`${humanizeClassLabel(k)}: ${val}`}
                                        />
                                      ))}
                                    </div>
                                    <div className="dl-exposure-legend">
                                      {entries.slice(0, 5).map(([k, val]) => (
                                        <span key={k} className="dl-exp-chip">
                                          <i style={{ background: getSegmentColor(cfg, k) }} />
                                          {humanizeClassLabel(k)} <strong>{val}</strong>
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
                              {humanizeDataFidelity(audit?.dataFidelity)}
                            </span>
                            <button
                              type="button"
                              className="dl-inspect-btn"
                              onClick={() => onInspectLayerOnMap && onInspectLayerOnMap(cfg.id)}
                            >
                              See on Map ↑
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
            SECTION III — COMPARE ROUTE PRIORITIES
            ============================================================ */}
        <DossierChapter
          id="ch3"
          num="III"
          title="Compare Route Priorities (Nature vs. Homes vs. Speed)"
          summaryBadge={`Overall Score ${tradeOffProfile?.compositeAlignmentIndex ?? "—"}/100 · ${presetDefinitions[tradeOffPreset]?.shortLabel || tradeOffPreset}`}
          isOpen={openChapters.ch3}
          onToggle={() => toggleChapter("ch3")}
        >
          {/* Key Highlights & Benefit Cards */}
          {tradeOffProfile?.routeImprovements && (
            <div className="dossier-improvements-panel">
              <div className="imp-eyebrow">What This Route Option Achieves</div>
              <h3 className="imp-headline">{humanizeText(tradeOffProfile.routeImprovements.headline)}</h3>

              {tradeOffProfile.routeImprovements.quantifiedBenefits?.length > 0 && (
                <div className="dossier-benefits-grid">
                  {tradeOffProfile.routeImprovements.quantifiedBenefits.map((b, idx) => (
                    <div key={idx} className="dossier-benefit-card">
                      <div className="benefit-lbl">{humanizeText(b.label)}</div>
                      <div className="benefit-val">{humanizeText(b.value)}</div>
                      <div className="benefit-det">{humanizeText(b.detail)}</div>
                    </div>
                  ))}
                </div>
              )}

              {comparativeDeltasVsBalanced && (
                <div className="dossier-live-deltas">
                  <span className="deltas-caption">Compared to the Balanced Route:</span>
                  <span className={`delta-chip ${comparativeDeltasVsBalanced.deltaKm <= 0 ? "good" : "trade"}`}>
                    {comparativeDeltasVsBalanced.deltaKm <= 0
                      ? `${Math.abs(comparativeDeltasVsBalanced.deltaKm)} km shorter`
                      : `+${comparativeDeltasVsBalanced.deltaKm} km longer`}
                  </span>
                  <span className={`delta-chip ${comparativeDeltasVsBalanced.deltaMins <= 0 ? "good" : "trade"}`}>
                    {comparativeDeltasVsBalanced.deltaMins <= 0
                      ? `${Math.abs(comparativeDeltasVsBalanced.deltaMins)} min faster drive`
                      : `+${comparativeDeltasVsBalanced.deltaMins} min drive`}
                  </span>
                  <span className={`delta-chip ${comparativeDeltasVsBalanced.deltaForestHa <= 0 ? "good" : "trade"}`}>
                    {comparativeDeltasVsBalanced.deltaForestHa <= 0
                      ? `${Math.abs(comparativeDeltasVsBalanced.deltaForestHa)} ha forest saved`
                      : `+${comparativeDeltasVsBalanced.deltaForestHa} ha more forest`}
                  </span>
                  <span className={`delta-chip ${comparativeDeltasVsBalanced.deltaDisplaced <= 0 ? "good" : "trade"}`}>
                    {comparativeDeltasVsBalanced.deltaDisplaced <= 0
                      ? `${Math.abs(comparativeDeltasVsBalanced.deltaDisplaced)} fewer people relocated`
                      : `+${comparativeDeltasVsBalanced.deltaDisplaced} more people affected`}
                  </span>
                </div>
              )}

              <div className="imp-rationale-grid">
                <div className="imp-rationale-box">
                  <strong>Main Benefit</strong>
                  <p>{humanizeText(tradeOffProfile.routeImprovements.engineeringAdvantage)}</p>
                </div>
                <div className="imp-rationale-box">
                  <strong>Trade-Off to Know About</strong>
                  <p>{humanizeText(tradeOffProfile.routeImprovements.tradeOffAccepted)}</p>
                </div>
              </div>
            </div>
          )}

          {/* Route Priority Cards */}
          <div className="dossier-subhead" style={{ marginTop: "28px" }}>
            Try Different Route Priorities (Click Any Card to Compare)
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
                    {isSelected && <span className="arch-active-tag">Selected</span>}
                  </div>
                  <p>{p.description}</p>
                </button>
              );
            })}
          </div>

          {/* 4 Easy Ratings + Custom Priority Sliders */}
          <div className="tradeoff-split-grid">
            {dimScores && (
              <div className="dossier-subcard">
                <div className="dossier-subhead">How This Route Scores (Out of 100)</div>
                <div className="score-bars-stack">
                  {[
                    ["Nature & Forest Protection", dimScores.environmentalScore ?? dimScores.environmentalConservation],
                    ["Protecting Homes & Villages", dimScores.socialScore ?? dimScores.socialProtection],
                    ["Short & Fuel-Efficient Path", dimScores.directnessScore ?? dimScores.directnessAndEconomy],
                    ["Connecting Nearby Towns", dimScores.connectivityScore ?? dimScores.regionalConnectivity],
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
              <div className="dossier-subhead">Set Your Own Priorities</div>
              <div className="weight-sliders-stack">
                <label className="weight-slider-item">
                  <div className="weight-meta">
                    <span>Protect Forests, Rivers &amp; Wildlife</span>
                    <strong className="mono-num">{envWeight.toFixed(1)}×</strong>
                  </div>
                  <input type="range" min="0.5" max="3.0" step="0.1" value={envWeight} onChange={(e) => onSliderChange("env", e.target.value)} />
                </label>
                <label className="weight-slider-item">
                  <div className="weight-meta">
                    <span>Avoid Homes, Schools &amp; Villages</span>
                    <strong className="mono-num">{socialWeight.toFixed(1)}×</strong>
                  </div>
                  <input type="range" min="0.5" max="3.0" step="0.1" value={socialWeight} onChange={(e) => onSliderChange("social", e.target.value)} />
                </label>
                <label className="weight-slider-item">
                  <div className="weight-meta">
                    <span>Keep Travel Distance Short</span>
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
                  {isRealigning ? "Finding Custom Route..." : "Calculate Custom Route"}
                </button>
              </div>
            </div>
          </div>

          {/* Side-by-Side Comparison Table */}
          {alternativeRoutes.length > 0 && (
            <div className="dossier-subcard" style={{ marginTop: "24px" }}>
              <div className="dossier-subhead">Side-by-Side Comparison of Routes You Tried</div>
              <div className="dossier-table-wrap">
                <table className="dossier-table">
                  <thead>
                    <tr>
                      <th>Route Option</th>
                      <th>Total Length</th>
                      <th>Extra Detour</th>
                      <th>Forest Land</th>
                      <th>People Relocated</th>
                      <th>Overall Score</th>
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
                          <td className="mono-num"><strong>{alt.compositeIndex}/100</strong></td>
                          <td>
                            <button
                              type="button"
                              className="table-switch-btn"
                              onClick={() => onSwitchArchetype(alt.preset)}
                              disabled={isActive}
                            >
                              {isActive ? "Showing" : "View"}
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
            SECTION IV — PLANNED BRIDGES, FLYOVERS & SAFETY STRUCTURES
            ============================================================ */}
        <DossierChapter
          id="ch4"
          num="IV"
          title="Planned Bridges, Flyovers & Safety Structures"
          summaryBadge={`${engineeringStructures.length} Bridges & Safety Crossings (Main Road + Side Roads)`}
          isOpen={openChapters.ch4}
          onToggle={() => toggleChapter("ch4")}
        >
          <div className="structures-kpi-strip">
            <div className="mini-kpi"><span>River Bridges</span><strong>{networkSummary?.riverBridges ?? 0}</strong><em>{networkSummary?.riverBridgeSpanM ?? 0} m total length</em></div>
            <div className="mini-kpi"><span>Railway Bridges</span><strong>{networkSummary?.railwayRobs ?? 0}</strong><em>No train waiting</em></div>
            <div className="mini-kpi"><span>Road Flyovers</span><strong>{networkSummary?.roadFlyovers ?? 0}</strong><em>No traffic signals</em></div>
            <div className="mini-kpi"><span>Wildlife Bridges</span><strong>{networkSummary?.wildlifePassages ?? 0}</strong><em>{networkSummary?.wildlifeSpanM ?? 0} m safe span</em></div>
            <div className="mini-kpi"><span>Water Culverts</span><strong>{networkSummary?.drainageStructures ?? 0}</strong><em>Monsoon drainage</em></div>
            <div className="mini-kpi"><span>Side-Road Bridges</span><strong>{spurEngineeringSummary?.totalSpurEngineeringStructuresCount ?? 0}</strong><em>Across {spurEngineeringSummary?.spursEvaluated ?? 0} link roads</em></div>
          </div>

          <div className="structure-filter-tabs">
            {[
              ["ALL", `All Structures (${engineeringStructures.length})`],
              ["BRIDGES", "River Bridges"],
              ["CROSSINGS", "Railway & Road Flyovers"],
              ["WILDLIFE", "Wildlife Crossings"],
              ["GEOTECH", "Hillside Support"],
              ["UTILITIES", "Power Line Guards"],
              ["DRAINAGE", "Stream Culverts"],
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
            <div className="dossier-empty-inline">No crossings of this type are needed along this route.</div>
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
                    {st.isSpur && <span className="spur-pill">Link Road · {st.spurHub}</span>}
                  </div>
                  <div className="struct-card-foot">
                    <span className="mono-num">
                      {st.chainage != null ? `${st.chainage} km from start` : `${st.lat.toFixed(4)}, ${st.lng.toFixed(4)}`}
                    </span>
                    <button
                      type="button"
                      className="dl-inspect-btn"
                      onClick={() => onLocateOnMap && onLocateOnMap(st.lat, st.lng, st.title)}
                    >
                      Show on Map ↑
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </DossierChapter>

        {/* ============================================================
            SECTION V — CONNECTED TOWNS, EXITS & TRAFFIC COMFORT
            ============================================================ */}
        <DossierChapter
          id="ch5"
          num="V"
          title="Connected Towns, Exits & Traffic Comfort"
          summaryBadge={`${connectedHubs.length} Connected Towns · ${networkSummary?.feederSpurKm ?? 0} km Link Roads · ${flowInfo.label}`}
          isOpen={openChapters.ch5}
          onToggle={() => toggleChapter("ch5")}
        >
          {/* Start & End Road Connections */}
          <div className="terminal-cards-grid">
            <div className="dossier-subcard">
              <div className="dossier-subhead">Where the Route Starts</div>
              <div className="terminal-road-title">
                {startConn?.existingRoadRef || networkSummary?.startRoadRef || "Existing Main Highway"}
              </div>
              <p className="terminal-road-meta">
                {startConn?.existingRoadName || "Existing Highway Connection"} &nbsp;·&nbsp;{" "}
                <strong>{humanizeClassLabel(startConn?.roadCategory || startConn?.category || "NATIONAL_HIGHWAY")}</strong>
              </p>
              <div className="terminal-spec-pills">
                <span>{humanizeClassLabel(startConn?.terminalJunctionType || (facilityMode === "EXPRESSWAY" ? "TRUMPET" : "At-Grade Highway Junction"))}</span>
                {startConn?.requiresTollBarrierPlaza && <span>Main Entry Toll Plaza</span>}
                <span>Entry Link: {startConn?.connectorLengthKm ?? "0.0"} km</span>
              </div>
            </div>

            <div className="dossier-subcard">
              <div className="dossier-subhead">Where the Route Ends</div>
              <div className="terminal-road-title">
                {goalConn?.existingRoadRef || networkSummary?.goalRoadRef || "Existing Main Highway"}
              </div>
              <p className="terminal-road-meta">
                {goalConn?.existingRoadName || "Existing Highway Connection"} &nbsp;·&nbsp;{" "}
                <strong>{humanizeClassLabel(goalConn?.roadCategory || goalConn?.category || "NATIONAL_HIGHWAY")}</strong>
              </p>
              <div className="terminal-spec-pills">
                <span>{humanizeClassLabel(goalConn?.terminalJunctionType || (facilityMode === "EXPRESSWAY" ? "TRUMPET" : "At-Grade Highway Junction"))}</span>
                {goalConn?.requiresTollBarrierPlaza && <span>Main Exit Toll Plaza</span>}
                <span>Exit Link: {goalConn?.connectorLengthKm ?? "0.0"} km</span>
              </div>
            </div>
          </div>

          {/* Expected Traffic & Comfort */}
          {trafficCapacity && (
            <div className="dossier-subcard" style={{ marginTop: "24px" }}>
              <div className="dossier-subhead">Expected Rush-Hour Traffic &amp; Driving Comfort</div>
              <div className="capacity-metrics-row">
                <div><span>Expected Peak Traffic</span><strong className="mono-num">{Math.round(trafficCapacity.projectedTotalPeakTrafficPcuHr || 0).toLocaleString()} vehicles/hr</strong></div>
                <div><span>Road Comfort Capacity</span><strong className="mono-num">{Math.round(trafficCapacity.designCapacityPcuHr || 4800).toLocaleString()} vehicles/hr</strong></div>
                <div><span>Road Usage</span><strong className="mono-num">{Number.isFinite(Number(trafficCapacity.projectedVcRatio)) ? `${Math.round(Number(trafficCapacity.projectedVcRatio) * 100)}% full` : "—"}</strong></div>
                <div><span>Driving Experience</span><strong>{flowInfo.label}</strong></div>
              </div>
              {trafficCapacity.capacityStandardNote && (
                <p className="capacity-note">{humanizeText(trafficCapacity.capacityStandardNote)}</p>
              )}
            </div>
          )}

          {/* Connected Nearby Cities */}
          {connectedHubs.length > 0 && (
            <div className="dossier-subcard" style={{ marginTop: "24px" }}>
              <div className="dossier-subhead">Cities &amp; Towns Connected Along the Way ({connectedHubs.length})</div>
              <div className="hubs-cards-grid">
                {connectedHubs.map((h, i) => (
                  <div key={i} className="hub-dossier-card">
                    <div className="hub-dossier-top">
                      <strong>{h.name}</strong>
                      <span className="mono-pill">{h.spurKm} km Link Road</span>
                    </div>
                    <div className="hub-dossier-meta">
                      Joins: <strong>{h.tieInRoadRef}</strong> · Local Traffic Added: +{h.traffic} vehicles/hr · Serves ~{Number(h.population || 0).toLocaleString()} residents
                    </div>
                    {h.decisionReason && <p className="hub-dossier-reason">{humanizeText(h.decisionReason)}</p>}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Expandable Full Town Selection Explanation */}
          {hubDecisions.length > 0 && (
            <div style={{ marginTop: "20px" }}>
              <button
                type="button"
                className="btn-botanical-outline"
                onClick={() => setShowHubLedger((v) => !v)}
              >
                {showHubLedger ? "Hide Town-by-Town Explanations" : `See Why Each Nearby Town Was Connected or Skipped (${hubDecisions.length} Towns Checked)`}
              </button>
              {showHubLedger && (
                <div className="dossier-table-wrap" style={{ marginTop: "14px" }}>
                  <table className="dossier-table">
                    <thead>
                      <tr>
                        <th>Town / City</th>
                        <th>Result</th>
                        <th>Distance from Highway</th>
                        <th>Extra Traffic</th>
                        <th>Why</th>
                      </tr>
                    </thead>
                    <tbody>
                      {hubDecisions.map((d, idx) => (
                        <tr key={idx}>
                          <td><strong>{d.name}</strong> {d.district ? `(${d.district})` : ""}</td>
                          <td>
                            <span className={`dl-verdict-pill ${d.accepted ? "pass" : "neutral"}`}>
                              {humanizeClassLabel(d.status || (d.accepted ? "Connected" : "Skipped"))}
                            </span>
                          </td>
                          <td className="mono-num">{d.distanceToCenterlineKm ?? "—"} km</td>
                          <td className="mono-num">+{d.inducedPeakTrafficPcuHr ?? 0} vehicles/hr</td>
                          <td>{humanizeText(d.reason)}</td>
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
            SECTION VI — LAND NEEDED, HOMES & NEARBY COMMUNITIES
            ============================================================ */}
        <DossierChapter
          id="ch6"
          num="VI"
          title="Land Needed, Homes & Nearby Communities"
          summaryBadge={`${rowHa} ha Land · ${networkSummary?.buildingsInRow ?? 0} Buildings in Path · ${networkSummary?.displacedPop ?? 0} People Relocated`}
          isOpen={openChapters.ch6}
          onToggle={() => toggleChapter("ch6")}
        >
          <div className="social-row-grid">
            <div className="dossier-subcard">
              <div className="dossier-subhead">Types of Land Needed for This Highway</div>
              <div className="social-big-nums">
                <div><span>Total Land Area</span><strong className="mono-num">{rowHa} ha</strong></div>
                <div><span>Main Road Width</span><strong className="mono-num">{rowWidthM} m</strong></div>
                <div><span>Link Road Width</span><strong className="mono-num">30 m</strong></div>
              </div>
              {lulcAcqHa && Object.keys(lulcAcqHa).length > 0 && (
                <div className="lulc-ha-list">
                  {Object.entries(lulcAcqHa)
                    .filter(([, v]) => Number(v) > 0)
                    .sort((a, b) => Number(b[1]) - Number(a[1]))
                    .map(([k, v]) => (
                      <div key={k} className="lulc-ha-row">
                        <span>{humanizeClassLabel(k)}</span>
                        <strong className="mono-num">{v} hectares</strong>
                      </div>
                    ))}
                </div>
              )}
            </div>

            <div className="dossier-subcard">
              <div className="dossier-subhead">Homes, Buildings &amp; Nearby Residents</div>
              <div className="social-big-nums">
                <div><span>Buildings in Path</span><strong className="mono-num">{networkSummary?.buildingsInRow ?? 0}</strong></div>
                <div><span>Families Relocated</span><strong className="mono-num">{networkSummary?.displacedHouseholds ?? 0}</strong></div>
                <div><span>People Relocated</span><strong className="mono-num">{networkSummary?.displacedPop ?? 0}</strong></div>
              </div>
              {demoSummary && (
                <div className="lulc-ha-list">
                  <div className="lulc-ha-row">
                    <span>People Living Within 250 m of Road</span>
                    <strong className="mono-num">{Number(demoSummary.populationWithin250mEnvironmentalBuffer || 0).toLocaleString()}</strong>
                  </div>
                  <div className="lulc-ha-row">
                    <span>People Living Within 500 m of Road</span>
                    <strong className="mono-num">{Number(demoSummary.populationWithin500mSecondaryBuffer || 0).toLocaleString()}</strong>
                  </div>
                  <div className="lulc-ha-row">
                    <span>Children &amp; Elderly Residents (Within 250 m)</span>
                    <strong className="mono-num">{Number(demoSummary.vulnerableAgePopulationExposedWithin250m || 0).toLocaleString()}</strong>
                  </div>
                  <div className="lulc-ha-row">
                    <span>Tribal &amp; Vulnerable Community Residents (Within 250 m)</span>
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
                        {humanizeClassLabel(type)}: {cnt}
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
