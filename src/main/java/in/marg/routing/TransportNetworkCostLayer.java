package in.marg.routing;

import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.transport.FacilityMode;
import in.marg.transport.RegionalHub;
import in.marg.transport.TransportCategory;
import in.marg.transport.TransportCorridorClass;
import in.marg.transport.TransportNetworkRasterProvider;
import in.marg.transport.TransportNetworkVectorIndex;

import java.util.List;

/**
 * Spatial cost layer for existing roads & railways:
 * <ul>
 *   <li>Node assessment: penalizes 2D ROW overlap and parallel buffer encroachment, plus
 *       (in NATIONAL_HIGHWAY mode when {@code accessibilityPull > 0}) applies an admissible
 *       collector-ring remoteness deficit pulling the mainline within 1.5–5.0 km of approved hubs.</li>
 *   <li>Edge assessment: detects exact 2D intersections with railways/highways and applies
 *       ROB / Flyover grade-separation bridge penalties scaled by approach skew angle (90 deg optimal),
 *       while heavily penalizing acute-angle parallel overlaps.</li>
 * </ul>
 */
public class TransportNetworkCostLayer implements CostLayer {

    private static final String SOURCE = "infrastructure:transport-network";
    private static final double PARALLEL_COS_THRESHOLD = 0.70710678; // cos(45 deg)

    private final TransportNetworkRasterProvider rasterProvider;
    private final TransportNetworkVectorIndex vectorIndex;
    private final CoordinateProjection projection;
    private final TransportCostPolicy policy;
    private final FacilityMode facilityMode;

    // Mode-dependent collector pull settings (0.0 for EXPRESSWAY; >0.0 for NATIONAL_HIGHWAY)
    private final double accessibilityPull;
    private final List<HubCollectorTarget> collectorTargets;
    private final double sweetSpotMaxMeters;
    private final double longitudinalBandMeters;

    // Corridor axis unit vector (start -> goal) for longitudinal band projection
    private final double originX;
    private final double originY;
    private final double axisUx;
    private final double axisUy;

    public TransportNetworkCostLayer(
            TransportNetworkRasterProvider rasterProvider,
            TransportNetworkVectorIndex vectorIndex,
            CoordinateProjection projection,
            TransportCostPolicy policy) {
        this(
                rasterProvider,
                vectorIndex,
                projection,
                policy,
                0.0,
                List.of(),
                null,
                null,
                5.0,
                10.0,
                FacilityMode.EXPRESSWAY);
    }

    public TransportNetworkCostLayer(
            TransportNetworkRasterProvider rasterProvider,
            TransportNetworkVectorIndex vectorIndex,
            CoordinateProjection projection,
            TransportCostPolicy policy,
            double accessibilityPull,
            List<RegionalHub> approvedHubs,
            GeoPoint corridorStart,
            GeoPoint corridorGoal,
            double sweetSpotMaxKm,
            double longitudinalBandKm) {
        this(
                rasterProvider,
                vectorIndex,
                projection,
                policy,
                accessibilityPull,
                approvedHubs,
                corridorStart,
                corridorGoal,
                sweetSpotMaxKm,
                longitudinalBandKm,
                accessibilityPull > 0.0 ? FacilityMode.NATIONAL_HIGHWAY : FacilityMode.EXPRESSWAY);
    }

    public TransportNetworkCostLayer(
            TransportNetworkRasterProvider rasterProvider,
            TransportNetworkVectorIndex vectorIndex,
            CoordinateProjection projection,
            TransportCostPolicy policy,
            double accessibilityPull,
            List<RegionalHub> approvedHubs,
            GeoPoint corridorStart,
            GeoPoint corridorGoal,
            double sweetSpotMaxKm,
            double longitudinalBandKm,
            FacilityMode facilityMode) {
        this.rasterProvider = rasterProvider;
        this.vectorIndex = vectorIndex;
        this.projection = projection;
        this.policy = policy;
        this.accessibilityPull = Math.max(0.0, accessibilityPull);
        this.sweetSpotMaxMeters = Math.max(1000.0, sweetSpotMaxKm * 1000.0);
        this.longitudinalBandMeters = Math.max(2000.0, longitudinalBandKm * 1000.0);
        this.facilityMode = facilityMode != null ? facilityMode : FacilityMode.EXPRESSWAY;

        if (this.accessibilityPull > 0.0
                && approvedHubs != null
                && !approvedHubs.isEmpty()
                && corridorStart != null
                && corridorGoal != null) {
            MetricPoint a = projection.forward(corridorStart);
            MetricPoint b = projection.forward(corridorGoal);
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double len = Math.max(1.0, Math.hypot(dx, dy));
            this.originX = a.x();
            this.originY = a.y();
            this.axisUx = dx / len;
            this.axisUy = dy / len;
            this.collectorTargets = approvedHubs.stream()
                    .map(h -> {
                        MetricPoint tie = h.bypassTieInMetric();
                        double s = (tie.x() - originX) * axisUx + (tie.y() - originY) * axisUy;
                        return new HubCollectorTarget(h, tie.x(), tie.y(), s);
                    })
                    .toList();
        } else {
            this.originX = 0.0;
            this.originY = 0.0;
            this.axisUx = 0.0;
            this.axisUy = 1.0;
            this.collectorTargets = List.of();
        }
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        TransportCorridorClass corridorClass =
                rasterProvider.sampleClass(point.lat(), point.lon());

        if (corridorClass == TransportCorridorClass.NODATA && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "TRANSPORT_NODATA");
        }

        double penalty = policy.nodePenalty(corridorClass);

        if (accessibilityPull > 0.0 && !collectorTargets.isEmpty()) {
            penalty += computeCollectorRemotenessDeficit(point);
        }

        return new CostAssessment(false, penalty, SOURCE, corridorClass.name());
    }

    @Override
    public boolean hasEdgeCost() {
        return true;
    }

    @Override
    public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        MetricPoint a = projection.forward(from);
        MetricPoint b = projection.forward(to);
        double stepMeters = Math.hypot(b.x() - a.x(), b.y() - a.y());
        if (stepMeters <= 1e-6) {
            return CostAssessment.FREE;
        }
        double stepKm = Math.max(0.05, stepMeters / 1000.0);

        List<TransportNetworkVectorIndex.SegmentCrossing> crossings =
                vectorIndex.findStepCrossingsMetric(a, b);

        double totalCrossingCostKmEq = 0.0;
        String primaryCrossingClass = null;

        if (!crossings.isEmpty()) {
            for (TransportNetworkVectorIndex.SegmentCrossing sc : crossings) {
                // In NATIONAL_HIGHWAY mode, road crossings are at-grade intersections (no elevated bridge structure cost).
                // Only RAILWAY crossings require mandatory grade-separated ROB bridge structures.
                // In EXPRESSWAY mode, ALL crossings (railways + highways + district + local roads) require elevated bridges.
                if (facilityMode == FacilityMode.NATIONAL_HIGHWAY && sc.category() != TransportCategory.RAILWAY) {
                    continue;
                }
                totalCrossingCostKmEq +=
                        policy.crossingStructureCostKmEq(sc.category(), sc.sinAngle());
                if (primaryCrossingClass == null) {
                    primaryCrossingClass = sc.category().defaultCrossingStructureType();
                }
            }
        }

        double parallelMultiplierPenalty = 0.0;
        if (policy.parallelAcutePenalty() > 0.0) {
            TransportNetworkVectorIndex.ParallelProximityMatch prox =
                    vectorIndex.findParallelProximity(a, b, policy.parallelProximityRadiusMeters());
            if (prox != null && prox.cosAlignment() > PARALLEL_COS_THRESHOLD) {
                double acuteRatio =
                        (prox.cosAlignment() - PARALLEL_COS_THRESHOLD)
                                / (1.0 - PARALLEL_COS_THRESHOLD);
                double catFactor = switch (prox.category()) {
                    case RAILWAY -> 1.50;
                    case NATIONAL_HIGHWAY -> 1.15;
                    case STATE_HIGHWAY -> 0.85;
                    case MAJOR_DISTRICT_ROAD -> 0.60;
                    case LOCAL_ROAD -> 0.40;
                };
                parallelMultiplierPenalty =
                        policy.parallelAcutePenalty() * acuteRatio * acuteRatio * catFactor;
            }
        }

        double totalMultiplier = (totalCrossingCostKmEq / stepKm) + parallelMultiplierPenalty;
        if (totalMultiplier <= 0.0) {
            return CostAssessment.FREE;
        }
        return new CostAssessment(
                false,
                totalMultiplier,
                SOURCE + ":edge",
                primaryCrossingClass != null ? primaryCrossingClass : "PARALLEL_ROW_OVERLAP");
    }

    /**
     * Admissible (>= 0) collector-ring remoteness penalty active only in NATIONAL_HIGHWAY mode.
     * Drops to 0.0 when passing within the 1.5–5.0 km collector ring of an approved hub,
     * gently pulling the regional highway closer to approved hubs without entering settlement buffers.
     */
    private double computeCollectorRemotenessDeficit(GeoPoint point) {
        MetricPoint p = projection.forward(point);
        double s = (p.x() - originX) * axisUx + (p.y() - originY) * axisUy;

        double deficit = 0.0;
        for (HubCollectorTarget target : collectorTargets) {
            double ds = Math.abs(s - target.axisProjectionMeters());
            if (ds >= longitudinalBandMeters) {
                continue;
            }
            double distMeters = Math.hypot(p.x() - target.x(), p.y() - target.y());
            if (distMeters <= sweetSpotMaxMeters) {
                // Inside the 0 - 5.0 km envelope: 0 deficit here (SettlementCostLayer protects 0-1 km)
                continue;
            }
            double longitudinalWeight =
                    Math.cos(0.5 * Math.PI * (ds / longitudinalBandMeters));
            longitudinalWeight *= longitudinalWeight;
            double lateralRamp =
                    Math.min(1.0, (distMeters - sweetSpotMaxMeters) / 10_000.0);
            deficit += accessibilityPull * longitudinalWeight * lateralRamp;
        }
        return deficit;
    }

    private record HubCollectorTarget(
            RegionalHub hub,
            double x,
            double y,
            double axisProjectionMeters
    ) {}
}
