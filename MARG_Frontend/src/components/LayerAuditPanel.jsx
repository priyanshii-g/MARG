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
  humanizeUnit,
} from "../utils/plainLanguage";

function verdictMeta(verdict) {
  switch (verdict) {
    case "HARD_CONSTRAINT_PASS":
      return { label: "SAFE & PASSED", cls: "pass" };
    case "VERIFIED_REDUCTION":
      return { label: "RISK REDUCED", cls: "verified" };
    case "MITIGATED_BY_DESIGN":
      return { label: "PROTECTED", cls: "mitigated" };
    case "WARNING":
      return { label: "NEEDS REVIEW", cls: "warn" };
    default:
      return { label: "CHECKED", cls: "neutral" };
  }
}

function extractFeatureCoordAndLabel(feature) {
  const props = feature.properties || {};
  const geom = feature.geometry || {};
  const role = props.featureRole || "";
  const label =
    props.name ||
    props.riverName ||
    props.channelName ||
    props.targetFeatureName ||
    props.corridorName ||
    props.sanctuaryName ||
    props.settlementName ||
    props.connectingHub ||
    props.roadRef ||
    props.roadName ||
    props.lineName ||
    props.wetlandName ||
    props.structureType ||
    props.interchangeType ||
    humanizeClassLabel(role);

  const sub =
    (props.areaHectares != null && role === "CORRIDOR_WETLAND_POLYGON"
      ? `${props.areaHectares} ha • ${humanizeClassLabel(props.wetlandCategory || "Wetland")}`
      : null) ||
    props.recommendedMitigation ||
    props.recommendedEngineeringMitigation ||
    props.mitigationStructure ||
    props.protectionType ||
    props.bridgeType ||
    props.structureType ||
    props.tieInRoadRef ||
    props.sublayerType ||
    props.category ||
    (props.chainageKm != null ? `${props.chainageKm} km from start` : "");

  let lat = null;
  let lng = null;
  if (geom.type === "Point" && Array.isArray(geom.coordinates)) {
    [lng, lat] = geom.coordinates;
  } else if (geom.type === "LineString" && Array.isArray(geom.coordinates) && geom.coordinates.length > 0) {
    const mid = geom.coordinates[Math.floor(geom.coordinates.length / 2)];
    [lng, lat] = mid;
  } else if (geom.type === "Polygon" && Array.isArray(geom.coordinates?.[0]) && geom.coordinates[0].length > 0) {
    const ring = geom.coordinates[0];
    let sLat = 0;
    let sLng = 0;
    ring.forEach(([x, y]) => {
      sLng += x;
      sLat += y;
    });
    lng = sLng / ring.length;
    lat = sLat / ring.length;
  } else if (geom.type === "MultiPolygon" && Array.isArray(geom.coordinates?.[0]?.[0])) {
    const ring = geom.coordinates[0][0];
    let sLat = 0;
    let sLng = 0;
    ring.forEach(([x, y]) => {
      sLng += x;
      sLat += y;
    });
    lng = sLng / ring.length;
    lat = sLat / ring.length;
  }

  return { lat, lng, label, sub, role };
}

function SlopeSparkline({ profile, onFocusPoint }) {
  const pts = useMemo(() => {
    if (!Array.isArray(profile) || profile.length < 2) return [];
    return profile.filter((p) => typeof p.slopeDeg === "number" && Number.isFinite(p.slopeDeg));
  }, [profile]);

  if (pts.length < 2) return null;

  const width = 280;
  const height = 74;
  const padL = 24;
  const padR = 8;
  const padT = 8;
  const padB = 16;
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
    <div className="lens-sparkline-box">
      <div className="lens-sparkline-header">
        <span>Hill Steepness Along This Route</span>
        <span>
          Avg <strong>{avgSlope}°</strong> · Steepest <strong>{peak.slopeDeg}°</strong>
        </span>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} className="lens-sparkline-svg">
        {/* 30° Road Safety Limit Reference Line */}
        <line
          x1={padL}
          y1={limitY}
          x2={width - padR}
          y2={limitY}
          stroke="#B85C38"
          strokeWidth="1"
          strokeDasharray="3,3"
        />
        <text x={2} y={limitY + 3} fontSize="7.5" fill="#B85C38" fontWeight="600">
          30°
        </text>
        <text x={2} y={padT + innerH + 2} fontSize="7.5" fill="#6C7A72">
          0°
        </text>

        <path d={areaD} fill="rgba(45, 106, 79, 0.15)" />
        <path d={pathD} fill="none" stroke="#2D6A4F" strokeWidth="1.7" />

        {/* Peak marker clickable */}
        <circle
          cx={xOf(peak.chKm)}
          cy={yOf(peak.slopeDeg)}
          r="3.4"
          fill="#B85C38"
          stroke="#FFFFFF"
          strokeWidth="1.2"
          style={{ cursor: "pointer" }}
          onClick={() => onFocusPoint && onFocusPoint(peak.lat, peak.lon, `Steepest Point (${peak.slopeDeg}°) at ${peak.chKm} km from start`)}
        >
          <title>Click to view steepest point ({peak.slopeDeg}° at {peak.chKm} km from start)</title>
        </circle>

        <text x={padL} y={height - 3} fontSize="7.5" fill="#6C7A72">
          Start
        </text>
        <text x={width - padR} y={height - 3} fontSize="7.5" fill="#6C7A72" textAnchor="end">
          {maxCh.toFixed(1)} km
        </text>
      </svg>
    </div>
  );
}

export default function LayerAuditPanel({
  layerAudit,
  routeLayerProfile,
  allFeatures,
  activeLayerId,
  pinnedLayerIds,
  onSelectLayer,
  onTogglePinLayer,
  onClearToOverview,
  onFocusFeature,
  showMultiArchetypes,
  onToggleMultiArchetypes,
  cachedArchetypesCount,
}) {
  const [collapsedDomains, setCollapsedDomains] = useState({});

  const auditById = useMemo(() => {
    const map = {};
    if (Array.isArray(layerAudit)) {
      layerAudit.forEach((rec) => {
        if (rec?.layerId) map[rec.layerId] = rec;
      });
    }
    return map;
  }, [layerAudit]);

  const summaryStats = useMemo(() => {
    if (!Array.isArray(layerAudit) || layerAudit.length === 0) {
      return { total: 13, passed: 0, warnings: 0 };
    }
    let passed = 0;
    let warnings = 0;
    layerAudit.forEach((r) => {
      if (r.verdict === "WARNING") warnings++;
      else passed++;
    });
    return { total: layerAudit.length, passed, warnings };
  }, [layerAudit]);

  const toggleDomain = (domainId) => {
    setCollapsedDomains((prev) => ({ ...prev, [domainId]: !prev[domainId] }));
  };

  return (
    <div className="sidebar-section layer-audit-section">
      <div className="section-header" style={{ marginBottom: "8px" }}>
        <span className="section-title">13 Safety &amp; Impact Map Views</span>
        {Array.isArray(layerAudit) && layerAudit.length > 0 && (
          <span className={`audit-summary-pill ${summaryStats.warnings > 0 ? "warn" : "ok"}`}>
            {summaryStats.warnings > 0
              ? `${summaryStats.warnings} Review Item${summaryStats.warnings > 1 ? "s" : ""}`
              : `${summaryStats.passed}/${summaryStats.total} Passed`}
          </span>
        )}
      </div>

      {/* Mode Bar: Main Route View vs Active Topic + Compare Routes */}
      <div className="lens-mode-bar">
        <button
          type="button"
          className={`lens-overview-btn ${!activeLayerId ? "active" : ""}`}
          onClick={onClearToOverview}
          title="Show the planned road, exits, side roads, and bridges on the street map"
        >
          <span>🛣️</span>
          <span>Main Route</span>
        </button>

        {cachedArchetypesCount > 1 && (
          <button
            type="button"
            className={`lens-compare-btn ${showMultiArchetypes ? "active" : ""}`}
            onClick={onToggleMultiArchetypes}
            title="Show other route options you tried as dashed lines on the map"
          >
            <span>🔀</span>
            <span>Compare ({cachedArchetypesCount})</span>
          </button>
        )}
      </div>

      <p className="lens-helper-hint">
        {!activeLayerId
          ? "Showing the planned road and bridges. Click any of the 13 topics below to see how this route avoids floods, forests, steep hills, and homes."
          : "Map view active. Click 📌 to pin another topic on top, or click Main Route to return."}
      </p>

      {/* 4 Topic Groups housing all 13 checks */}
      <div className="layer-domains-stack">
        {LAYER_DOMAINS.map((domain) => {
          const domainLayers = LAYER_CATALOG.filter((l) => l.domain === domain.id);
          const isCollapsed = !!collapsedDomains[domain.id];

          return (
            <div key={domain.id} className="layer-domain-group">
              <button
                type="button"
                className="layer-domain-header"
                onClick={() => toggleDomain(domain.id)}
              >
                <span className="layer-domain-title">
                  <span>{domain.icon}</span>
                  <span>{domain.title}</span>
                  <span className="layer-domain-count">{domainLayers.length}</span>
                </span>
                <span className="layer-domain-chevron">{isCollapsed ? "▸" : "▾"}</span>
              </button>

              {!isCollapsed && (
                <div className="layer-cards-list">
                  {domainLayers.map((cfg) => {
                    const audit = auditById[cfg.id] || null;
                    const isActive = activeLayerId === cfg.id;
                    const isPinned = pinnedLayerIds.has(cfg.id);
                    const vMeta = audit ? verdictMeta(audit.verdict) : null;
                    const comp = audit?.comparison;

                    // Quick inline status chip for collapsed row
                    let rowChip = null;
                    if (audit) {
                      if (audit.verdict === "WARNING") {
                        rowChip = { text: "REVIEW", cls: "warn" };
                      } else if (comp && typeof comp.deltaPercent === "number" && comp.deltaPercent <= -5) {
                        rowChip = { text: `${comp.deltaPercent}%`, cls: "verified" };
                      } else if (audit.verdict === "HARD_CONSTRAINT_PASS") {
                        rowChip = { text: "SAFE", cls: "pass" };
                      } else if (audit.mitigationCount > 0) {
                        rowChip = { text: `${audit.mitigationCount}x`, cls: "mitigated" };
                      } else {
                        rowChip = { text: "OK", cls: "neutral" };
                      }
                    }

                    // Features belonging to this topic for interactive jump-list
                    const layerFeatures = isActive && Array.isArray(allFeatures)
                      ? allFeatures
                          .filter((f) => cfg.vectorFeatureRoles.includes(f.properties?.featureRole))
                          .map(extractFeatureCoordAndLabel)
                          .filter((item) => item.lat != null && item.lng != null)
                      : [];

                    return (
                      <div
                        key={cfg.id}
                        className={`layer-audit-card ${isActive ? "active" : ""} ${isPinned ? "pinned" : ""}`}
                        style={{ "--layer-accent": cfg.accentColor }}
                      >
                        <div className="layer-card-row">
                          <button
                            type="button"
                            className="layer-card-trigger"
                            onClick={() => onSelectLayer(isActive ? null : cfg.id)}
                          >
                            <span className="layer-num-badge" style={{ background: cfg.accentColor }}>
                              {cfg.number}
                            </span>
                            <span className="layer-row-icon">{cfg.icon}</span>
                            <span className="layer-row-name">{cfg.shortName}</span>
                            {rowChip && (
                              <span className={`layer-status-chip ${rowChip.cls}`}>
                                {rowChip.text}
                              </span>
                            )}
                          </button>

                          <button
                            type="button"
                            className={`layer-pin-btn ${isPinned ? "pinned" : ""}`}
                            title={isPinned ? "Unpin this view from the map" : "Keep this view pinned while checking other topics"}
                            onClick={(e) => {
                              e.stopPropagation();
                              onTogglePinLayer(cfg.id);
                            }}
                          >
                            📌
                          </button>
                        </div>

                        {/* Expanded Plain-Language Check Card */}
                        {isActive && (
                          <div className="layer-card-body">
                            <p className="layer-card-desc">{cfg.description}</p>

                            {!audit ? (
                              <div className="layer-empty-audit">
                                Find a route first to see how it performs on {cfg.shortName.toLowerCase()}.
                              </div>
                            ) : (
                              <>
                                {/* Verdict & Data Source Banner */}
                                <div className="layer-verdict-row">
                                  <span className={`layer-verdict-badge ${vMeta.cls}`}>
                                    {vMeta.label}
                                  </span>
                                  <span className="layer-fidelity-tag">
                                    {humanizeDataFidelity(audit.dataFidelity)}
                                  </span>
                                </div>

                                <div className="layer-standard-cite">
                                  {humanizeLayerPurpose(cfg.id)}
                                </div>

                                {/* Headline Summary */}
                                <div className="layer-headline-proof">
                                  {humanizeText(audit.headlineProof)}
                                </div>

                                {/* Key Safety Check Box */}
                                {audit.hardConstraintCheck && (
                                  <div
                                    className={`layer-hard-check ${
                                      audit.hardConstraintCheck.passed ? "pass" : "fail"
                                    }`}
                                  >
                                    <span>{audit.hardConstraintCheck.passed ? "✅" : "⚠️"}</span>
                                    <span>{humanizeText(audit.hardConstraintCheck.detail)}</span>
                                  </div>
                                )}

                                {/* Straight-Line vs Smart Route Comparison */}
                                {comp && (
                                  <div className="layer-comparison-box">
                                    <div className="layer-comparison-title">
                                      <span>
                                        {humanizeMetricLabel(comp.metricLabel)}
                                        {comp.includesConnectingRoads && (
                                          <span className="cmp-scope-tag"> · incl. connecting roads</span>
                                        )}
                                      </span>
                                      {typeof comp.deltaPercent === "number" && (
                                        <span
                                          className={`layer-delta-pill ${
                                            comp.deltaValue <= 0 ? "good" : "neutral"
                                          }`}
                                        >
                                          {comp.deltaValue > 0 ? "+" : ""}
                                          {comp.deltaPercent}%
                                        </span>
                                      )}
                                    </div>
                                    <div className="layer-comparison-bars">
                                      {(() => {
                                        const maxVal = Math.max(
                                          0.01,
                                          Number(comp.baselineValue) || 0,
                                          Number(comp.routeValue) || 0
                                        );
                                        const basePct = Math.min(100, ((Number(comp.baselineValue) || 0) / maxVal) * 100);
                                        const routePct = Math.min(100, ((Number(comp.routeValue) || 0) / maxVal) * 100);
                                        const displayUnit = humanizeUnit(comp.unit);
                                        return (
                                          <>
                                            <div className="cmp-bar-row">
                                              <span className="cmp-bar-label">Straight Line</span>
                                              <div className="cmp-bar-track">
                                                <div
                                                  className="cmp-bar-fill baseline"
                                                  style={{ width: `${basePct}%` }}
                                                />
                                              </div>
                                              <span className="cmp-bar-val">
                                                {comp.baselineValue} {displayUnit}
                                                {comp.baselineRateNote && (
                                                  <small className="cmp-rate-note"> ({comp.baselineRateNote})</small>
                                                )}
                                              </span>
                                            </div>
                                            <div className="cmp-bar-row">
                                              <span className="cmp-bar-label">Smart Route</span>
                                              <div className="cmp-bar-track">
                                                <div
                                                  className="cmp-bar-fill marg"
                                                  style={{
                                                    width: `${routePct}%`,
                                                    background: cfg.accentColor,
                                                  }}
                                                />
                                              </div>
                                              <span className="cmp-bar-val strong">
                                                {comp.routeValue} {displayUnit}
                                                {comp.routeRateNote && (
                                                  <small className="cmp-rate-note"> ({comp.routeRateNote})</small>
                                                )}
                                              </span>
                                            </div>
                                          </>
                                        );
                                      })()}
                                    </div>
                                    {comp.baselineHardViolationNote && (
                                      <div className="cmp-baseline-caveat">
                                        ℹ️ {comp.baselineHardViolationNote}
                                      </div>
                                    )}
                                  </div>
                                )}

                                {/* Hill Steepness Sparkline (Topic 3) */}
                                {cfg.hasSlopeSparkline && (
                                  <SlopeSparkline
                                    profile={routeLayerProfile}
                                    onFocusPoint={onFocusFeature}
                                  />
                                )}

                                {/* Breakdown Bar */}
                                {audit.exposureBreakdown &&
                                  Object.keys(audit.exposureBreakdown).length > 0 && (
                                    <div className="layer-exposure-box">
                                      <div className="layer-exposure-header">
                                        <span>
                                          {audit.includesConnectingRoads
                                            ? "What This Route Passes Through (Main + Connecting Roads)"
                                            : "What This Route Passes Through"}
                                        </span>
                                      </div>
                                      {(() => {
                                        const entries = Object.entries(audit.exposureBreakdown)
                                          .filter(([, v]) => Number(v) > 0)
                                          .sort((a, b) => Number(b[1]) - Number(a[1]));
                                        const total = entries.reduce((acc, [, v]) => acc + Number(v), 0);
                                        if (total <= 0) return null;
                                        return (
                                          <>
                                            <div className="exposure-stacked-bar">
                                              {entries.map(([k, v]) => (
                                                <div
                                                  key={k}
                                                  className="exposure-bar-seg"
                                                  style={{
                                                    width: `${(Number(v) / total) * 100}%`,
                                                    background: getSegmentColor(cfg, k),
                                                  }}
                                                  title={`${humanizeClassLabel(k)}: ${v} km`}
                                                />
                                              ))}
                                            </div>
                                            <div className="exposure-chips">
                                              {entries.slice(0, 7).map(([k, v]) => (
                                                <span key={k} className="exposure-chip">
                                                  <span
                                                    className="exposure-dot"
                                                    style={{ background: getSegmentColor(cfg, k) }}
                                                  />
                                                  <span>{humanizeClassLabel(k)}</span>
                                                  <strong>{v}</strong>
                                                </span>
                                              ))}
                                            </div>
                                          </>
                                        );
                                      })()}
                                    </div>
                                  )}

                                {/* Connecting Roads (Feeder Spurs) Per-Town Breakdown */}
                                {audit.connectingRoadsImpact &&
                                  Array.isArray(audit.connectingRoadsImpact.perSpur) &&
                                  audit.connectingRoadsImpact.perSpur.length > 0 && (
                                    <div
                                      className={`layer-spurs-impact-box ${
                                        audit.connectingRoadsImpact.hasNotableExposure ? "has-exposure" : ""
                                      }`}
                                    >
                                      <div className="layer-spurs-impact-header">
                                        <span>
                                          🟣 Connecting Roads ({audit.connectingRoadsImpact.spursCount})
                                        </span>
                                        <span className="layer-spurs-km-badge">
                                          {audit.connectingRoadsImpact.totalLengthKm} km
                                          {audit.connectingRoadsImpact.baselineLengthKm != null
                                            ? ` (Straight: ${audit.connectingRoadsImpact.baselineLengthKm} km)`
                                            : ""}
                                        </span>
                                      </div>
                                      {audit.connectingRoadsImpact.summary && (
                                        <div className="layer-spurs-impact-summary">
                                          {humanizeText(audit.connectingRoadsImpact.summary)}
                                        </div>
                                      )}
                                      <div className="layer-spurs-impact-rows">
                                        {audit.connectingRoadsImpact.perSpur.map((sp, spIdx) => (
                                          <div
                                            key={`${sp.spurId || sp.hubName || "sp"}-${spIdx}`}
                                            className={`layer-spur-row sev-${sp.severity || "ok"}`}
                                          >
                                            <div className="layer-spur-row-top">
                                              <span className={`layer-spur-dot sev-${sp.severity || "ok"}`} />
                                              <strong className="layer-spur-hub">{sp.hubName}</strong>
                                              <span className="layer-spur-len">{sp.lengthKm} km</span>
                                            </div>
                                            <div className="layer-spur-metric">
                                              {humanizeText(sp.metricSummary)}
                                            </div>
                                          </div>
                                        ))}
                                      </div>
                                    </div>
                                  )}

                                {/* Interactive Jump-to-Location List */}
                                {layerFeatures.length > 0 && (
                                  <div className="layer-features-jump">
                                    <div className="layer-jump-title">
                                      Shown on Map ({layerFeatures.length}) — click to zoom in:
                                    </div>
                                    <div className="layer-jump-list">
                                      {layerFeatures.slice(0, 8).map((feat, idx) => (
                                        <button
                                          key={`${feat.role}-${idx}`}
                                          type="button"
                                          className="layer-jump-item"
                                          onClick={() =>
                                            onFocusFeature &&
                                            onFocusFeature(feat.lat, feat.lng, feat.label)
                                          }
                                        >
                                          <span className="jump-name">📍 {feat.label}</span>
                                          {feat.sub && (
                                            <span className="jump-sub">{humanizeClassLabel(feat.sub)}</span>
                                          )}
                                        </button>
                                      ))}
                                      {layerFeatures.length > 8 && (
                                        <div className="jump-more-note">
                                          +{layerFeatures.length - 8} more spots shown on the map
                                        </div>
                                      )}
                                    </div>
                                  </div>
                                )}
                              </>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
