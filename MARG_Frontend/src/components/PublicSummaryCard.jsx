import { useEffect, useMemo, useRef, useState } from "react";
import { humanizeTrafficFlow } from "../utils/plainLanguage";

const summaryCache = new Map();

export default function PublicSummaryCard({
  corridorName,
  facilityMode,
  routeStyleLabel,
  routeInfo,
  networkSummary,
  tradeOffProfile,
  statutoryClearanceReport,
  routeDiagnostics,
  connectedHubs,
  trafficCapacity,
  layerAudit,
  snapNotice,
}) {
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState("");
  const abortRef = useRef(null);

  const factsPayload = useMemo(() => {
    if (!routeInfo) return null;

    const forestHa = tradeOffProfile?.environmentalSummary?.forestHectaresDirectRow ?? 0;
    const wetlandHa = tradeOffProfile?.environmentalSummary?.wetlandHectaresDirectRow ?? 0;
    const demoSummary = routeDiagnostics?.populationAndDemographicExposureSummary || {};
    const lulcHa = routeDiagnostics?.rightOfWayCorridorSummary?.lulcAcquisitionHectares || {};
    const flowInfo = humanizeTrafficFlow(trafficCapacity?.projectedVcRatio, trafficCapacity?.levelOfService);

    const layerHighlights = (layerAudit || []).map((item) => ({
      topic: item.name,
      status: item.verdict,
      summary: item.headlineProof,
    }));

    const snapNotes = [];
    if (snapNotice?.start) {
      snapNotes.push(`Starting point moved ${snapNotice.start.distanceMeters} m due to ${snapNotice.start.reason}`);
    }
    if (snapNotice?.end) {
      snapNotes.push(`Ending point moved ${snapNotice.end.distanceMeters} m due to ${snapNotice.end.reason}`);
    }

    return {
      corridorName: corridorName || "Selected Highway Route",
      facilityMode: facilityMode || "EXPRESSWAY",
      routeStyle: routeStyleLabel || "Balanced",
      routeLengthKm: Number(routeInfo.routeLengthKm) || 0,
      baselineLengthKm: Number(routeInfo.baselineLengthKm) || 0,
      deviationPercent: Number(routeInfo.deviationPercent) || 0,
      forestHectares: Number(forestHa) || 0,
      wetlandHectares: Number(wetlandHa) || 0,
      totalLandHectares: Number(networkSummary?.rowHectares) || 0,
      roadWidthMeters: Number(networkSummary?.rowWidthM) || (facilityMode === "EXPRESSWAY" ? 60 : 45),
      sanctuaryIntersection: Boolean(statutoryClearanceReport?.hasSanctuaryIntersection),
      sanctuaryLengthKm: Number(statutoryClearanceReport?.totalSanctuaryLengthKm) || 0,
      sanctuaryNames: statutoryClearanceReport?.traversedSanctuaryNames || [],
      riverBridges: Number(networkSummary?.riverBridges) || 0,
      riverBridgeSpanMeters: Number(networkSummary?.riverBridgeSpanM) || 0,
      wildlifeBridges: Number(networkSummary?.wildlifePassages) || 0,
      wildlifeBridgeSpanMeters: Number(networkSummary?.wildlifeSpanM) || 0,
      railBridges: Number(networkSummary?.railwayRobs) || 0,
      roadFlyovers: Number(networkSummary?.roadFlyovers) || 0,
      drainageCulverts: Number(networkSummary?.drainageStructures) || 0,
      buildingsAffected: Number(networkSummary?.buildingsInRow) || 0,
      familiesRelocated: Number(networkSummary?.displacedHouseholds) || 0,
      peopleRelocated: Number(networkSummary?.displacedPop) || 0,
      peopleWithin250m: Number(demoSummary.populationWithin250mEnvironmentalBuffer) || 0,
      peopleWithin500m: Number(demoSummary.populationWithin500mSecondaryBuffer) || 0,
      connectedTowns: (connectedHubs || []).map((h) => h.name).filter(Boolean),
      connectingRoadLengthKm: Number(networkSummary?.feederSpurKm) || 0,
      trafficFlowLabel: flowInfo.label,
      trafficLoadPercent: Number.isFinite(Number(trafficCapacity?.projectedVcRatio))
        ? Math.round(Number(trafficCapacity.projectedVcRatio) * 100)
        : null,
      landBreakdownHectares: lulcHa,
      safetyAdjustments: snapNotes,
      layerHighlights,
    };
  }, [
    corridorName,
    facilityMode,
    routeStyleLabel,
    routeInfo,
    networkSummary,
    tradeOffProfile,
    statutoryClearanceReport,
    routeDiagnostics,
    connectedHubs,
    trafficCapacity,
    layerAudit,
    snapNotice,
  ]);

  const cacheKey = useMemo(() => {
    if (!factsPayload) return "";
    return [
      factsPayload.corridorName,
      factsPayload.facilityMode,
      factsPayload.routeStyle,
      factsPayload.routeLengthKm,
      factsPayload.forestHectares,
      factsPayload.buildingsAffected,
      factsPayload.connectedTowns.join(","),
      factsPayload.sanctuaryIntersection,
    ].join("|");
  }, [factsPayload]);

  async function fetchSummary(forceRefresh = false) {
    if (!factsPayload) return;

    if (!forceRefresh && summaryCache.has(cacheKey)) {
      setSummary(summaryCache.get(cacheKey));
      setErrorMsg("");
      return;
    }

    if (abortRef.current) {
      abortRef.current.abort();
    }
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setErrorMsg("");
    try {
      const response = await fetch("/api/routes/public-summary", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(factsPayload),
        signal: controller.signal,
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const data = await response.json();
      summaryCache.set(cacheKey, data);
      setSummary(data);
    } catch (err) {
      if (err?.name === "AbortError") return;
      setErrorMsg("Could not refresh the plain-English summary right now.");
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false);
      }
    }
  }

  useEffect(() => {
    if (!factsPayload) {
      setSummary(null);
      return;
    }
    fetchSummary(false);
    return () => {
      if (abortRef.current) {
        abortRef.current.abort();
      }
    };
  }, [cacheKey]);

  if (!routeInfo) return null;

  const isAiGenerated = summary?.source === "AI_NATURAL_LANGUAGE";

  return (
    <section className="public-summary-card" aria-label="Plain-English Summary for the Public">
      <div className="psc-topbar">
        <div className="psc-eyebrow-group">
          <span className="psc-eyebrow">IN PLAIN ENGLISH · CITIZEN&apos;S BRIEF</span>
          {summary && (
            <span className={`psc-source-pill ${isAiGenerated ? "ai" : "local"}`}>
              {isAiGenerated ? "✨ AI Natural-Language Brief" : "Plain-Language Brief"}
            </span>
          )}
        </div>
        <button
          type="button"
          className="psc-refresh-btn"
          onClick={() => fetchSummary(true)}
          disabled={loading}
          title="Rewrite this plain-English summary"
        >
          {loading ? "Writing summary…" : "↻ Rewrite Summary"}
        </button>
      </div>

      {loading && !summary ? (
        <div className="psc-loading-state">
          <div className="psc-shimmer psc-shimmer-title" />
          <div className="psc-shimmer psc-shimmer-line" />
          <div className="psc-shimmer psc-shimmer-line short" />
          <p className="psc-loading-note">
            Preparing a clear, everyday summary of how this road affects nature, homes, and daily travel…
          </p>
        </div>
      ) : summary ? (
        <div className={`psc-content ${loading ? "updating" : ""}`}>
          <h2 className="psc-headline">{summary.headline}</h2>

          {/* Primary Spotlight: Environmental Impact */}
          <div className="psc-env-spotlight">
            <div className="psc-section-label">
              <span aria-hidden="true">🌿</span>
              <span>Impact on Nature &amp; Environment</span>
            </div>
            <p className="psc-lead-prose">{summary.environmentalImpact}</p>
          </div>

          {/* Secondary Story Pillars: Community & Travel */}
          <div className="psc-two-col">
            <div className="psc-story-block">
              <div className="psc-section-label">
                <span aria-hidden="true">🏡</span>
                <span>Homes, Land &amp; Local People</span>
              </div>
              <p className="psc-prose">{summary.communityAndLand}</p>
            </div>

            <div className="psc-story-block">
              <div className="psc-section-label">
                <span aria-hidden="true">🛣️</span>
                <span>Travel, Bridges &amp; Nearby Towns</span>
              </div>
              <p className="psc-prose">{summary.travelAndConnectivity}</p>
            </div>
          </div>

          {/* Calm Citizen Takeaway */}
          <div className="psc-takeaway">
            <span className="psc-takeaway-label">Bottom Line for Citizens</span>
            <p className="psc-takeaway-text">{summary.keyTakeaway}</p>
          </div>
        </div>
      ) : errorMsg ? (
        <p className="psc-error-note">{errorMsg}</p>
      ) : null}
    </section>
  );
}
