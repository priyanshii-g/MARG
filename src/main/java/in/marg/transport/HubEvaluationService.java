package in.marg.transport;

import in.marg.config.TransportRoutingProperties;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Three-Tier Hub Evaluation Pipeline:
 * <ol>
 *   <li>Step 1: Force User Overrides ({@code targetHubs}) as Immutable Nodes.</li>
 *   <li>Step 2: Autonomous Candidate Suggestion inside the A–B corridor scored by
 *       Hub Viability Index {@code HVI = (Population * IndustrialFactor) / DistanceToCenterline^2},
 *       filtered by facility-mode minimum population threshold.</li>
 *   <li>Step 3: Spacing Gate (15 km Expressways / 8 km National Highways) and
 *       Carrying Capacity Gate ({@code V/C <= 0.75} by default) to prevent highway over-trafficking.</li>
 * </ol>
 */
@Service
public class HubEvaluationService {

    private static final double TERMINAL_EXCLUSION_RADIUS_KM = 3.5;
    private static final double MAX_AUTONOMOUS_LATERAL_CORRIDOR_KM = 25.0;

    private final TransportNetworkVectorIndex vectorIndex;
    private final CoordinateProjection projection;
    private final TransportRoutingProperties properties;

    public HubEvaluationService(
            TransportNetworkVectorIndex vectorIndex,
            CoordinateProjection projection,
            TransportRoutingProperties properties) {
        this.vectorIndex = vectorIndex;
        this.projection = projection;
        this.properties = properties;
    }

    public EvaluationResult evaluate(
            GeoPoint start,
            GeoPoint goal,
            StudyRegion searchWindow,
            FacilityMode mode,
            List<String> userTargetHubTokens,
            Integer minHubPopulationOverride,
            Double maxVcRatioOverride) {
        return evaluate(
                start,
                goal,
                searchWindow,
                mode,
                userTargetHubTokens,
                minHubPopulationOverride,
                maxVcRatioOverride,
                true,
                null);
    }

    public EvaluationResult evaluate(
            GeoPoint start,
            GeoPoint goal,
            StudyRegion searchWindow,
            FacilityMode mode,
            List<String> userTargetHubTokens,
            Integer minHubPopulationOverride,
            Double maxVcRatioOverride,
            Boolean autoConnectHubs,
            Integer maxAutoHubs) {

        MetricPoint a = projection.forward(start);
        MetricPoint b = projection.forward(goal);
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double corridorLengthKm = Math.max(0.1, Math.hypot(dx, dy) / 1000.0);
        double ux = (dx / 1000.0) / corridorLengthKm;
        double uy = (dy / 1000.0) / corridorLengthKm;

        boolean enableAuto = autoConnectHubs == null || Boolean.TRUE.equals(autoConnectHubs);
        int autoLimit = (maxAutoHubs != null && maxAutoHubs >= 0) ? maxAutoHubs : Integer.MAX_VALUE;

        int effectiveMinPop = minHubPopulationOverride != null
                ? Math.max(0, minHubPopulationOverride)
                : (enableAuto && maxAutoHubs != null
                        ? Math.min(20000, properties.minHubPopulationFor(mode))
                        : properties.minHubPopulationFor(mode));
        double minSpacingKm = properties.minInterchangeSpacingKmFor(mode);
        double effectiveMaxVc = maxVcRatioOverride != null
                ? maxVcRatioOverride
                : properties.getMaxVcRatio();
        double designCapacityPcuHr = properties.designCapacityPcuHrFor(mode);
        double baseThroughTrafficPcuHr = properties.baseThroughTrafficPcuHrFor(mode);
        double tripRatePer1000 = properties.getPeakTripGenerationPcuPer1000Pop();

        // Step 1: Resolve immutable user overrides
        List<RegionalHub> immutableHubs = vectorIndex.resolveUserHubs(userTargetHubTokens);
        Set<String> immutableIds = new LinkedHashSet<>();
        for (RegionalHub h : immutableHubs) {
            immutableIds.add(h.hubId());
        }

        // Pre-score all regional hubs plus any dynamically synthesized user hubs
        List<ScoredCandidate> allScored = new ArrayList<>();
        Set<String> scoredIds = new LinkedHashSet<>();
        for (RegionalHub hub : vectorIndex.allHubs()) {
            allScored.add(scoreHub(hub, a, b, ux, uy, corridorLengthKm, tripRatePer1000, immutableIds.contains(hub.hubId())));
            scoredIds.add(hub.hubId());
        }
        for (RegionalHub imm : immutableHubs) {
            if (!scoredIds.contains(imm.hubId())) {
                allScored.add(scoreHub(imm, a, b, ux, uy, corridorLengthKm, tripRatePer1000, true));
                scoredIds.add(imm.hubId());
            }
        }

        List<HubDecision> decisions = new ArrayList<>();
        List<RegionalHub> approvedHubs = new ArrayList<>();
        List<Double> lockedAxisPositionsKm = new ArrayList<>();

        // Terminals A (0 km) and B (corridorLengthKm) act as end-interchange anchors for autonomous spacing
        double currentVolumePcuHr = baseThroughTrafficPcuHr;

        // Lock in Step 1 Immutable Overrides first (in user-specified order)
        for (RegionalHub imm : immutableHubs) {
            ScoredCandidate sc = allScored.stream()
                    .filter(c -> c.hub().hubId().equals(imm.hubId()))
                    .findFirst()
                    .orElseThrow();
            currentVolumePcuHr += sc.inducedPcuHr();
            double vcAfter = currentVolumePcuHr / designCapacityPcuHr;
            approvedHubs.add(imm);
            lockedAxisPositionsKm.add(sc.axisProjectionKm());
            decisions.add(new HubDecision(
                    imm,
                    true,
                    true,
                    "USER_IMMUTABLE_OVERRIDE",
                    "Forced by user targetHubs override (bypasses automatic population/spacing/V-C filters).",
                    round(sc.hvi()),
                    round(sc.distCenterlineKm()),
                    round(sc.axisProjectionKm()),
                    round(sc.inducedPcuHr()),
                    round(vcAfter)));
        }

        // Step 2 & Step 3: Evaluate remaining autonomous hubs sorted by descending HVI
        List<ScoredCandidate> autonomousPool = allScored.stream()
                .filter(c -> !c.immutable())
                .sorted(Comparator.comparingDouble(ScoredCandidate::hvi).reversed())
                .toList();

        int autonomousApprovedCount = 0;
        for (ScoredCandidate sc : autonomousPool) {
            RegionalHub hub = sc.hub();

            if (!enableAuto) {
                decisions.add(new HubDecision(
                        hub,
                        false,
                        false,
                        "AUTONOMOUS_HUB_DISABLED",
                        "Auto-connect intermediate hubs feature is turned off by user.",
                        round(sc.hvi()),
                        round(sc.distCenterlineKm()),
                        round(sc.axisProjectionKm()),
                        round(sc.inducedPcuHr()),
                        round(currentVolumePcuHr / designCapacityPcuHr)));
                continue;
            }

            if (autonomousApprovedCount >= autoLimit) {
                decisions.add(new HubDecision(
                        hub,
                        false,
                        false,
                        "AUTONOMOUS_CAP_REACHED",
                        "Autonomous hub connection cap reached (" + autoLimit + " cities maximum).",
                        round(sc.hvi()),
                        round(sc.distCenterlineKm()),
                        round(sc.axisProjectionKm()),
                        round(sc.inducedPcuHr()),
                        round(currentVolumePcuHr / designCapacityPcuHr)));
                continue;
            }

            // Exclude hubs coinciding with origin/destination terminals
            if (sc.distToStartKm() <= TERMINAL_EXCLUSION_RADIUS_KM
                    || sc.distToGoalKm() <= TERMINAL_EXCLUSION_RADIUS_KM) {
                decisions.add(new HubDecision(
                        hub,
                        false,
                        false,
                        "TERMINAL_ENDPOINT_EXCLUDED",
                        "Within " + TERMINAL_EXCLUSION_RADIUS_KM + " km of corridor start/destination terminal.",
                        round(sc.hvi()),
                        round(sc.distCenterlineKm()),
                        round(sc.axisProjectionKm()),
                        round(sc.inducedPcuHr()),
                        round(currentVolumePcuHr / designCapacityPcuHr)));
                continue;
            }

            // Corridor bounding / lateral buffer check
            boolean insideCorridor = (searchWindow == null || searchWindow.contains(hub.bypassTieIn())
                    || sc.distCenterlineKm() <= MAX_AUTONOMOUS_LATERAL_CORRIDOR_KM)
                    && sc.axisProjectionKm() >= -5.0
                    && sc.axisProjectionKm() <= corridorLengthKm + 5.0
                    && sc.distCenterlineKm() <= MAX_AUTONOMOUS_LATERAL_CORRIDOR_KM;

            if (!insideCorridor) {
                decisions.add(new HubDecision(
                        hub,
                        false,
                        false,
                        "OUTSIDE_CORRIDOR_BUFFER",
                        "Outside the A-B spatial corridor buffer (" + round(sc.distCenterlineKm()) + " km lateral).",
                        round(sc.hvi()),
                        round(sc.distCenterlineKm()),
                        round(sc.axisProjectionKm()),
                        round(sc.inducedPcuHr()),
                        round(currentVolumePcuHr / designCapacityPcuHr)));
                continue;
            }

            // Step 2: Mode-tiered population threshold
            if (hub.catchmentPopulation() < effectiveMinPop) {
                decisions.add(new HubDecision(
                        hub,
                        false,
                        false,
                        "BELOW_MODE_POPULATION_THRESHOLD",
                        "Catchment population " + hub.catchmentPopulation()
                                + " < " + mode.name() + " threshold " + effectiveMinPop + ".",
                        round(sc.hvi()),
                        round(sc.distCenterlineKm()),
                        round(sc.axisProjectionKm()),
                        round(sc.inducedPcuHr()),
                        round(currentVolumePcuHr / designCapacityPcuHr)));
                continue;
            }

            // Step 3 Gate A: Carrying Capacity Gate (V/C Ratio <= maxVcRatio)
            double projectedVolume = currentVolumePcuHr + sc.inducedPcuHr();
            double projectedVc = projectedVolume / designCapacityPcuHr;
            boolean capacityExceeded = projectedVc > effectiveMaxVc + 1e-9;
            if (capacityExceeded) {
                // IRC:SP:84 Section 2.2 Standard: When regional connectivity is requested,
                // expressway corridors accommodate high traffic through 6-lane design capacity (7,200 PCU/hr)
                // or tolerance up to physical capacity (V/C <= 1.05) with an over-capacity alert,
                // rather than refusing grade-separated feeder spur access to major economic hubs.
                double sixLaneCapacity = designCapacityPcuHr * 1.5;
                double sixLaneVc = projectedVolume / sixLaneCapacity;
                boolean sixLaneFeasible = mode == FacilityMode.EXPRESSWAY && (sixLaneVc <= 0.85 || projectedVc <= 1.05);

                if (!enableAuto || !sixLaneFeasible) {
                    decisions.add(new HubDecision(
                            hub,
                            false,
                            false,
                            "CAPACITY_VC_GATE_REJECTED",
                            "Adding " + round(sc.inducedPcuHr()) + " PCU/hr would push V/C to "
                                    + round(projectedVc) + " (> " + round(effectiveMaxVc) + " cap).",
                            round(sc.hvi()),
                            round(sc.distCenterlineKm()),
                            round(sc.axisProjectionKm()),
                            round(sc.inducedPcuHr()),
                            round(projectedVc)));
                    continue;
                }
            }

            // Step 3 Gate B: Spacing Gate (against terminals and already-locked interchanges)
            double minDistToTerminal = Math.min(
                    Math.abs(sc.axisProjectionKm()),
                    Math.abs(corridorLengthKm - sc.axisProjectionKm()));
            double minDistToLocked = minDistToTerminal;
            for (double lockedS : lockedAxisPositionsKm) {
                minDistToLocked = Math.min(minDistToLocked, Math.abs(sc.axisProjectionKm() - lockedS));
            }
            if (minDistToLocked < minSpacingKm) {
                decisions.add(new HubDecision(
                        hub,
                        false,
                        false,
                        "SPACING_GATE_REJECTED",
                        "Projected interchange spacing " + round(minDistToLocked)
                                + " km < " + mode.name() + " minimum " + round(minSpacingKm) + " km.",
                        round(sc.hvi()),
                        round(sc.distCenterlineKm()),
                        round(sc.axisProjectionKm()),
                        round(sc.inducedPcuHr()),
                        round(currentVolumePcuHr / designCapacityPcuHr)));
                continue;
            }

            // Passed all gates!
            currentVolumePcuHr = projectedVolume;
            approvedHubs.add(hub);
            lockedAxisPositionsKm.add(sc.axisProjectionKm());
            autonomousApprovedCount++;
            String decisionReason = capacityExceeded
                    ? "Selected via HVI ranking (IRC:SP:84 Section 2.2: 6-lane capacity recommended: 4-lane V/C "
                            + round(projectedVc) + ", 6-lane V/C " + round(projectedVolume / (designCapacityPcuHr * 1.5)) + ")."
                    : "Selected via HVI ranking within " + round(minSpacingKm)
                            + " km spacing and V/C <= " + round(effectiveMaxVc) + ".";
            decisions.add(new HubDecision(
                    hub,
                    true,
                    false,
                    "AUTONOMOUS_HVI_SELECTED",
                    decisionReason,
                    round(sc.hvi()),
                    round(sc.distCenterlineKm()),
                    round(sc.axisProjectionKm()),
                    round(sc.inducedPcuHr()),
                    round(projectedVc)));
        }

        double finalVc = currentVolumePcuHr / designCapacityPcuHr;
        int totalServedPop = approvedHubs.stream().mapToInt(RegionalHub::catchmentPopulation).sum();

        return new EvaluationResult(
                mode,
                effectiveMinPop,
                minSpacingKm,
                effectiveMaxVc,
                designCapacityPcuHr,
                baseThroughTrafficPcuHr,
                round(currentVolumePcuHr),
                round(finalVc),
                classifyLevelOfService(finalVc),
                finalVc > effectiveMaxVc + 1e-9,
                totalServedPop,
                List.copyOf(approvedHubs),
                List.copyOf(decisions));
    }

    public static String classifyLevelOfService(double vcRatio) {
        if (vcRatio <= 0.35) return "LOS_A_FREE_FLOW";
        if (vcRatio <= 0.55) return "LOS_B_REASONABLY_FREE_FLOW";
        if (vcRatio <= 0.75) return "LOS_C_STABLE_FLOW";
        if (vcRatio <= 0.90) return "LOS_D_APPROACHING_UNSTABLE";
        return "LOS_E_OVER_CAPACITY";
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record ScoredCandidate(
            RegionalHub hub,
            boolean immutable,
            double axisProjectionKm,
            double distCenterlineKm,
            double distToStartKm,
            double distToGoalKm,
            double hvi,
            double inducedPcuHr
    ) {}

    private static ScoredCandidate scoreHub(
            RegionalHub hub,
            MetricPoint a,
            MetricPoint b,
            double ux,
            double uy,
            double corridorLengthKm,
            double tripRatePer1000,
            boolean immutable) {
        MetricPoint p = hub.centerMetric();
        double relXKm = (p.x() - a.x()) / 1000.0;
        double relYKm = (p.y() - a.y()) / 1000.0;

        double projKm = relXKm * ux + relYKm * uy;
        double clampedProjKm = Math.max(0.0, Math.min(corridorLengthKm, projKm));
        double closestXKm = clampedProjKm * ux;
        double closestYKm = clampedProjKm * uy;
        double distCenterlineKm = Math.hypot(relXKm - closestXKm, relYKm - closestYKm);

        double distToStartKm = Math.hypot(p.x() - a.x(), p.y() - a.y()) / 1000.0;
        double distToGoalKm = Math.hypot(p.x() - b.x(), p.y() - b.y()) / 1000.0;

        double effectiveDistForHvi = Math.max(1.0, distCenterlineKm);
        double hvi = (hub.catchmentPopulation() * hub.industrialOutputFactor())
                / (effectiveDistForHvi * effectiveDistForHvi);

        double inducedPcuHr = (hub.catchmentPopulation() / 1000.0)
                * tripRatePer1000
                * hub.industrialOutputFactor();

        return new ScoredCandidate(
                hub,
                immutable,
                projKm,
                distCenterlineKm,
                distToStartKm,
                distToGoalKm,
                hvi,
                inducedPcuHr);
    }

    public record HubDecision(
            RegionalHub hub,
            boolean accepted,
            boolean immutableOverride,
            String status,
            String reason,
            double hvi,
            double distanceToCorridorCenterlineKm,
            double corridorAxisProjectionKm,
            double inducedPeakTrafficPcuHr,
            double projectedVcRatioAtEvaluation
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hubId", hub.hubId());
            m.put("name", hub.name());
            m.put("district", hub.district());
            m.put("catchmentPopulation", hub.catchmentPopulation());
            m.put("urbanPopulation", hub.urbanPopulation());
            m.put("industrialOutputFactor", hub.industrialOutputFactor());
            m.put("tieInRoadRef", hub.tieInRoadRef());
            m.put("tieInName", hub.tieInName());
            m.put("accepted", accepted);
            m.put("immutableOverride", immutableOverride);
            m.put("status", status);
            m.put("reason", reason);
            m.put("hviScore", hvi);
            m.put("distanceToCenterlineKm", distanceToCorridorCenterlineKm);
            m.put("inducedPeakTrafficPcuHr", inducedPeakTrafficPcuHr);
            m.put("projectedVcRatio", projectedVcRatioAtEvaluation);
            return m;
        }
    }

    public record EvaluationResult(
            FacilityMode facilityMode,
            int minHubPopulationApplied,
            double minInterchangeSpacingKmApplied,
            double maxVcRatioCapApplied,
            double designCapacityPcuHr,
            double baseThroughTrafficPcuHr,
            double projectedTotalTrafficPcuHr,
            double projectedVcRatio,
            String levelOfService,
            boolean overCapacityWarning,
            int totalIntermediateCatchmentPopulationServed,
            List<RegionalHub> approvedHubs,
            List<HubDecision> decisions
    ) {}
}
