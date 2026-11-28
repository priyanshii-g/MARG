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
                ? Math.min(1.0, maxVcRatioOverride)
                : Math.min(1.0, properties.getMaxVcRatio());
        int maxVcPercent = (int) Math.round(effectiveMaxVc * 100.0);
        String comfortProfileName = describeComfortProfile(effectiveMaxVc);
        double designCapacityPcuHr = properties.designCapacityPcuHrFor(mode);
        double baseThroughTrafficPcuHr = properties.baseThroughTrafficPcuHrFor(mode);
        double tripRatePer1000 = properties.getPeakTripGenerationPcuPer1000Pop();

        // Step 1: Resolve user-added priority hubs
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
        List<String> droppedUserHubNames = new ArrayList<>();
        List<String> capacityRejectedAutoHubNames = new ArrayList<>();
        double peakRejectedUserVc = 0.0;

        // Terminals A (0 km) and B (corridorLengthKm) act as end-interchange anchors for autonomous spacing
        double currentVolumePcuHr = baseThroughTrafficPcuHr;

        // Step 1: Evaluate user-added hubs FIRST (highest priority), strictly respecting the traffic comfort V/C cap
        for (RegionalHub imm : immutableHubs) {
            ScoredCandidate sc = allScored.stream()
                    .filter(c -> c.hub().hubId().equals(imm.hubId()))
                    .findFirst()
                    .orElseThrow();
            double projectedVolume = currentVolumePcuHr + sc.inducedPcuHr();
            double vcAfter = projectedVolume / designCapacityPcuHr;
            if (vcAfter > effectiveMaxVc + 1e-9) {
                droppedUserHubNames.add(imm.name());
                peakRejectedUserVc = Math.max(peakRejectedUserVc, vcAfter);
                decisions.add(new HubDecision(
                        imm,
                        false,
                        true,
                        "CAPACITY_VC_USER_HUB_DROPPED",
                        "Removed " + imm.name() + " because adding " + round(sc.inducedPcuHr())
                                + " PCU/hr would push road usage to " + Math.round(vcAfter * 100.0)
                                + "% (exceeds your " + maxVcPercent + "% " + comfortProfileName + " limit).",
                        round(sc.hvi()),
                        round(sc.distCenterlineKm()),
                        round(sc.axisProjectionKm()),
                        round(sc.inducedPcuHr()),
                        round(vcAfter)));
                continue;
            }
            currentVolumePcuHr = projectedVolume;
            approvedHubs.add(imm);
            lockedAxisPositionsKm.add(sc.axisProjectionKm());
            decisions.add(new HubDecision(
                    imm,
                    true,
                    true,
                    "USER_IMMUTABLE_OVERRIDE",
                    "Priority city added by user (within " + maxVcPercent + "% road capacity limit).",
                    round(sc.hvi()),
                    round(sc.distCenterlineKm()),
                    round(sc.axisProjectionKm()),
                    round(sc.inducedPcuHr()),
                    round(vcAfter)));
        }

        // Step 2 & Step 3: Fill remaining city slots (total cap = autoLimit, prioritizing user-added hubs)
        List<ScoredCandidate> autonomousPool = allScored.stream()
                .filter(c -> !c.immutable())
                .sorted(Comparator.comparingDouble(ScoredCandidate::hvi).reversed())
                .toList();

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

            if (approvedHubs.size() >= autoLimit) {
                decisions.add(new HubDecision(
                        hub,
                        false,
                        false,
                        "AUTONOMOUS_CAP_REACHED",
                        "Total connected city cap reached (" + autoLimit + " cities maximum, prioritizing user-added cities).",
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

            // Step 3 Gate A: Strict Carrying Capacity Gate (V/C Ratio <= effectiveMaxVc; never allow over-capacity)
            double projectedVolume = currentVolumePcuHr + sc.inducedPcuHr();
            double projectedVc = projectedVolume / designCapacityPcuHr;
            if (projectedVc > effectiveMaxVc + 1e-9) {
                capacityRejectedAutoHubNames.add(hub.name());
                decisions.add(new HubDecision(
                        hub,
                        false,
                        false,
                        "CAPACITY_VC_GATE_REJECTED",
                        "Skipped " + hub.name() + " because adding " + round(sc.inducedPcuHr())
                                + " PCU/hr would push road usage to " + Math.round(projectedVc * 100.0)
                                + "% (> " + maxVcPercent + "% " + comfortProfileName + " limit).",
                        round(sc.hvi()),
                        round(sc.distCenterlineKm()),
                        round(sc.axisProjectionKm()),
                        round(sc.inducedPcuHr()),
                        round(projectedVc)));
                continue;
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
            String decisionReason = "Selected via HVI ranking within " + round(minSpacingKm)
                    + " km spacing and " + maxVcPercent + "% road capacity limit.";
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
        int finalVcPercent = (int) Math.round(finalVc * 100.0);
        int totalServedPop = approvedHubs.stream().mapToInt(RegionalHub::catchmentPopulation).sum();

        List<String> keptHubNames = approvedHubs.stream().map(RegionalHub::name).toList();
        List<String> keptUserHubNames = approvedHubs.stream()
                .filter(h -> immutableIds.contains(h.hubId()))
                .map(RegionalHub::name)
                .toList();

        List<CapacityAlert> capacityAlerts = new ArrayList<>();
        if (!droppedUserHubNames.isEmpty()) {
            String droppedList = String.join(", ", droppedUserHubNames);
            String hint = effectiveMaxVc < 0.84
                    ? " Switch to 'Connect More Towns' (85% capacity) or remove another city to include it."
                    : " Try Expressway mode (higher capacity) or select fewer major cities.";
            capacityAlerts.add(new CapacityAlert(
                    "USER_CITY_REMOVED_FOR_CAPACITY",
                    "warning",
                    "City Removed to Respect Traffic Comfort Limit",
                    "Removed " + droppedList + " because connecting "
                            + (droppedUserHubNames.size() == 1 ? "it" : "them")
                            + " would push road usage to " + Math.round(peakRejectedUserVc * 100.0)
                            + "%, exceeding your " + maxVcPercent + "% (" + comfortProfileName
                            + ") limit. Road usage is kept at " + finalVcPercent + "%." + hint,
                    List.copyOf(droppedUserHubNames),
                    keptHubNames,
                    maxVcPercent,
                    finalVcPercent));
        }

        boolean requestedMoreThanConnected = enableAuto && maxAutoHubs != null
                && approvedHubs.size() < autoLimit
                && !capacityRejectedAutoHubNames.isEmpty();
        if (requestedMoreThanConnected) {
            List<String> skippedPreview = capacityRejectedAutoHubNames.size() > 3
                    ? capacityRejectedAutoHubNames.subList(0, 3)
                    : capacityRejectedAutoHubNames;
            String priorityNote = !keptUserHubNames.isEmpty()
                    ? " prioritizing your added " + (keptUserHubNames.size() == 1 ? "city (" : "cities (")
                            + String.join(", ", keptUserHubNames) + ")"
                    : "";
            String hint = effectiveMaxVc < 0.84
                    ? " Switch to 'Connect More Towns' (85% capacity) if you want more towns connected."
                    : "";
            capacityAlerts.add(new CapacityAlert(
                    "AUTO_CITIES_REDUCED_FOR_CAPACITY",
                    "warning",
                    "Connected Cities Reduced to Prevent Road Overcrowding",
                    "Connected " + approvedHubs.size() + " of " + autoLimit + " requested "
                            + (autoLimit == 1 ? "city" : "cities")
                            + (priorityNote.isEmpty() ? "" : "," + priorityNote)
                            + " (skipped " + String.join(", ", skippedPreview)
                            + ") so road usage stays at " + finalVcPercent + "% — within your "
                            + maxVcPercent + "% (" + comfortProfileName + ") traffic limit." + hint,
                    List.copyOf(capacityRejectedAutoHubNames),
                    keptHubNames,
                    maxVcPercent,
                    finalVcPercent));
        }

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
                List.copyOf(decisions),
                List.copyOf(capacityAlerts));
    }

    public static String describeComfortProfile(double maxVcRatio) {
        if (maxVcRatio <= 0.72) {
            return "Smooth & Fast";
        }
        if (maxVcRatio <= 0.87) {
            return "Connect More Towns";
        }
        return "Custom Capacity Cap";
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

    public record CapacityAlert(
            String code,
            String severity,
            String title,
            String message,
            List<String> droppedHubs,
            List<String> keptHubs,
            int maxVcPercent,
            int finalVcPercent
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("severity", severity);
            m.put("title", title);
            m.put("message", message);
            m.put("droppedHubs", droppedHubs);
            m.put("keptHubs", keptHubs);
            m.put("maxVcPercent", maxVcPercent);
            m.put("finalVcPercent", finalVcPercent);
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
            List<HubDecision> decisions,
            List<CapacityAlert> capacityAlerts
    ) {}
}
