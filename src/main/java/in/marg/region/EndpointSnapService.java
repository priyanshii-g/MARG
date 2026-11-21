package in.marg.region;

import in.marg.building.BuildingDensityClass;
import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.building.OsmDynamicBuildingSettlementCache;
import in.marg.building.SettlementClassification;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.routing.CostAssessment;
import in.marg.routing.CostSurface;
import in.marg.settlement.SettlementExposureClass;
import in.marg.settlement.SettlementRasterProvider;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.transport.FacilityMode;
import in.marg.transport.OsmDynamicTransportNetworkCache;
import in.marg.transport.TerminalAccessPolicy;
import in.marg.transport.TransportNetworkVectorIndex;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Smart Endpoint Validation &amp; Snapping Service.
 *
 * <p>When an evaluator clicks any arbitrary start or end coordinate inside a highlighted
 * corridor region (or anywhere across India in Tier 1 mode), the exact clicked coordinate
 * or its nearest 250 m routing grid cell might fall inside a hard-blocked pixel
 * (Protected Area mask, Slope &gt; 30&deg;, active landslide scarp, reservoir body) OR inside:
 * <ul>
 *   <li>Any Hospital / Medical / Institutional / Religious compound (or within 100 m of one),</li>
 *   <li>Any Settlement / Residential Colony polygon ({@code landuse=residential} / {@code SettlementBoundaryPolygon}),</li>
 *   <li>Any Building Footprint or high-density built-up raster cell.</li>
 * </ul>
 * This service automatically snaps invalid endpoints outward in concentric metric rings
 * (250 m .. 15,000 m) to the nearest open greenfield corridor cell outside the settlement/hospital
 * boundary that has a clean, building-free tie-in to an existing highway/road.
 */
public class EndpointSnapService {

    private static final double GRID_STEP_METERS = 250.0;
    private static final double MAX_SNAP_RADIUS_METERS = 15000.0;
    private static final double MAX_EXPRESSWAY_URBAN_SNAP_RADIUS_METERS = 20000.0;
    private static final double MIN_ENDPOINT_SEPARATION_METERS = 1000.0;
    private static final int ANGULAR_SAMPLES_PER_RING = 24;
    public static final double ENDPOINT_INSTITUTIONAL_BUFFER_METERS = 250.0;
    public static final double ENDPOINT_BUILDING_BUFFER_METERS = 25.0;
    public static final double EXPRESSWAY_LOCAL_STREET_BUFFER_METERS = 30.0;
    public static final double EXPRESSWAY_MAJOR_HIGHWAY_EXEMPTION_METERS = 75.0;

    private final CoordinateProjection projection;

    @FunctionalInterface
    public interface EndpointObstacleEvaluator {
        /** Returns a machine-readable obstacle reason if the endpoint is invalid, or {@code null} if clean. */
        String findObstacleReason(GeoPoint point);

        EndpointObstacleEvaluator NONE = pt -> null;
    }

    @FunctionalInterface
    public interface CandidateTieInScorer {
        /**
         * Returns a non-negative preference cost for a candidate endpoint (lower is better).
         * Candidates with a clean, building-free tie-in to an existing road score lowest.
         */
        double scoreCandidate(GeoPoint candidate, CostSurface surface);

        CandidateTieInScorer DEFAULT = (pt, surface) -> Math.max(0.0, surface.assess(pt).penaltyMultiplier());
    }

    public EndpointSnapService(CoordinateProjection projection) {
        this.projection = projection;
    }

    /**
     * Builds an obstacle evaluator for Tier-2 Benchmark Corridors checking hospitals/institutional/religious
     * compounds (+250 m buffer), settlement/colony polygons, building footprints, and high-density rasters.
     */
    public static EndpointObstacleEvaluator forBenchmarkCorridor(
            BuildingFootprintVectorIndex buildingIndex,
            BuildingDensityRasterProvider buildingRaster) {
        return forBenchmarkCorridor(buildingIndex, buildingRaster, null, null, null);
    }

    /**
     * Facility-mode-aware obstacle evaluator for Tier-2 Benchmark Corridors.
     * In {@link FacilityMode#EXPRESSWAY} mode, additionally rejects {@code URBAN_CORE} and
     * {@code INNER_BUFFER} settlement raster cells, urban/industrial municipal boundaries,
     * high-density urban blocks, and direct placement on local streets per IRC:SP:99-2013 Cl. 2.12.
     */
    public static EndpointObstacleEvaluator forBenchmarkCorridor(
            BuildingFootprintVectorIndex buildingIndex,
            BuildingDensityRasterProvider buildingRaster,
            SettlementRasterProvider settlementRaster,
            FacilityMode facilityMode) {
        return forBenchmarkCorridor(buildingIndex, buildingRaster, settlementRaster, null, facilityMode);
    }

    /**
     * Full facility-mode-aware obstacle evaluator for Tier-2 Benchmark Corridors including
     * settlement raster urban-core exclusion and local-street exclusion for Expressways.
     */
    public static EndpointObstacleEvaluator forBenchmarkCorridor(
            BuildingFootprintVectorIndex buildingIndex,
            BuildingDensityRasterProvider buildingRaster,
            SettlementRasterProvider settlementRaster,
            TransportNetworkVectorIndex transportIndex,
            FacilityMode facilityMode) {
        return point -> {
            // 1. Statutory institutional / sacred / hospital exclusions always report first
            if (buildingIndex != null) {
                var instHit = buildingIndex.findContainingInstitutionalOrSacredExclusion(
                        point, ENDPOINT_INSTITUTIONAL_BUFFER_METERS);
                if (instHit != null) {
                    return instHit.category() + ":" + sanitizeLabel(instHit.name());
                }
            }

            // 2. IRC:SP:99-2013 & NHAI greenfield mandate: Expressways never terminate inside urban cores or on local streets
            if (facilityMode == FacilityMode.EXPRESSWAY) {
                if (settlementRaster != null) {
                    SettlementExposureClass sc = settlementRaster.sampleClass(point.lat(), point.lon());
                    if (sc == SettlementExposureClass.URBAN_CORE || sc == SettlementExposureClass.INNER_BUFFER) {
                        return "EXPRESSWAY_CANNOT_TERMINATE_INSIDE_URBAN_CORE:" + sc.name();
                    }
                }
                if (buildingIndex != null) {
                    var stl = buildingIndex.findContainingSettlement(point);
                    if (stl != null
                            && (stl.settlementClassification() == SettlementClassification.URBAN
                            || stl.settlementClassification() == SettlementClassification.INDUSTRIAL)) {
                        return "EXPRESSWAY_CANNOT_TERMINATE_INSIDE_URBAN_CORE:" + sanitizeLabel(stl.name());
                    }
                }
                if (buildingRaster != null) {
                    BuildingDensityClass dc = buildingRaster.sampleClass(point.lat(), point.lon());
                    if (dc == BuildingDensityClass.HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP) {
                        return "EXPRESSWAY_CANNOT_TERMINATE_INSIDE_URBAN_CORE:HIGH_DENSITY_URBAN_BUILTUP";
                    }
                }
                if (transportIndex != null) {
                    var localConflict = transportIndex.findImmediateLocalStreetConflict(
                            point, EXPRESSWAY_LOCAL_STREET_BUFFER_METERS, EXPRESSWAY_MAJOR_HIGHWAY_EXEMPTION_METERS);
                    if (localConflict != null) {
                        String ref = (localConflict.corridorRef() != null && !localConflict.corridorRef().isBlank())
                                ? localConflict.corridorRef()
                                : localConflict.corridorName();
                        return "EXPRESSWAY_CANNOT_START_ON_LOCAL_STREET:" + sanitizeLabel(ref);
                    }
                }
            }

            // 3. Standard settlement / colony / building footprint checks
            if (buildingIndex != null) {
                String vectorObstacle = buildingIndex.findEndpointBuildingOrSettlementObstacle(
                        point, ENDPOINT_INSTITUTIONAL_BUFFER_METERS, ENDPOINT_BUILDING_BUFFER_METERS);
                if (vectorObstacle != null) {
                    return vectorObstacle;
                }
            }
            if (buildingRaster != null) {
                BuildingDensityClass dc = buildingRaster.sampleClass(point.lat(), point.lon());
                if (dc == BuildingDensityClass.INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT) {
                    return "HOSPITAL_OR_INSTITUTIONAL_COMPOUND_RASTER";
                }
                if (dc == BuildingDensityClass.HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP) {
                    return "HIGH_DENSITY_URBAN_COLONY_RASTER";
                }
                if (dc == BuildingDensityClass.MEDIUM_DENSITY_VILLAGE_CLUSTER) {
                    return "SETTLEMENT_VILLAGE_CLUSTER_RASTER";
                }
            }
            return null;
        };
    }

    /**
     * Builds an obstacle evaluator for Tier-1 Pan-India Dynamic Corridors using the disk-cached OSM index.
     */
    public static EndpointObstacleEvaluator forDynamicOsmIndex(
            OsmDynamicBuildingSettlementCache.OsmCorridorIndex osmIndex) {
        return forDynamicOsmIndex(osmIndex, null, null);
    }

    /**
     * Facility-mode-aware obstacle evaluator for Tier-1 Pan-India Dynamic Corridors.
     * In {@link FacilityMode#EXPRESSWAY} mode, enforces urban-core exclusion across major Indian
     * cities and prevents expressways from starting directly on OSM local streets.
     */
    public static EndpointObstacleEvaluator forDynamicOsmIndex(
            OsmDynamicBuildingSettlementCache.OsmCorridorIndex osmIndex,
            OsmDynamicTransportNetworkCache.OsmTransportIndex osmTransportIndex,
            FacilityMode facilityMode) {
        boolean hasOsm = osmIndex != null && !osmIndex.isEmpty();
        boolean hasTransport = osmTransportIndex != null && !osmTransportIndex.isEmpty();
        if (!hasOsm && !hasTransport && facilityMode != FacilityMode.EXPRESSWAY) {
            return EndpointObstacleEvaluator.NONE;
        }
        return point -> {
            // Let PanIndiaDynamicCostLayer surface hard-blocks (Sacred Heritage / Sanctuaries) report directly
            if (PanIndiaDynamicCostLayer.isStatutoryHardBlock(point)) {
                return null;
            }
            if (hasOsm) {
                var inst = osmIndex.findInstitutionalExclusion(point, ENDPOINT_INSTITUTIONAL_BUFFER_METERS);
                if (inst != null) {
                    return inst.category() + ":" + sanitizeLabel(inst.name());
                }
            }
            if (facilityMode == FacilityMode.EXPRESSWAY) {
                var urbanCore = PanIndiaDynamicCostLayer.findContainingUrbanCore(point);
                if (urbanCore != null) {
                    return "EXPRESSWAY_CANNOT_TERMINATE_INSIDE_URBAN_CORE:" + urbanCore.id();
                }
                if (hasTransport) {
                    var localConflict = osmTransportIndex.findImmediateLocalStreetConflict(
                            point, EXPRESSWAY_LOCAL_STREET_BUFFER_METERS, EXPRESSWAY_MAJOR_HIGHWAY_EXEMPTION_METERS);
                    if (localConflict != null) {
                        String ref = (localConflict.corridorRef() != null && !localConflict.corridorRef().isBlank())
                                ? localConflict.corridorRef()
                                : localConflict.corridorName();
                        return "EXPRESSWAY_CANNOT_START_ON_LOCAL_STREET:" + sanitizeLabel(ref);
                    }
                }
            }
            if (hasOsm) {
                return osmIndex.findEndpointObstacleReason(
                        point, ENDPOINT_INSTITUTIONAL_BUFFER_METERS, ENDPOINT_BUILDING_BUFFER_METERS);
            }
            return null;
        };
    }

    /**
     * Builds a tie-in cleanliness scorer that favors open greenfield candidates with a short,
     * building-free, hospital-free tie-in to an existing highway or road.
     */
    public static CandidateTieInScorer forCleanRoadTieIn(
            StudyRegion fullRegion,
            TransportNetworkVectorIndex transportIndex,
            BuildingFootprintVectorIndex buildingIndex) {
        return forCleanRoadTieIn(fullRegion, transportIndex, buildingIndex, null);
    }

    /**
     * Facility-mode-aware tie-in cleanliness scorer.
     * For {@link FacilityMode#EXPRESSWAY}, scores candidates strictly by proximity to clean
     * {@code NH}/{@code SH} arterial highway tie-ins (rejecting {@code LOCAL_ROAD}).
     * For {@link FacilityMode#NATIONAL_HIGHWAY}, prefers {@code NH > SH > MDR} while penalizing
     * {@code LOCAL_ROAD} fallbacks.
     */
    public static CandidateTieInScorer forCleanRoadTieIn(
            StudyRegion fullRegion,
            TransportNetworkVectorIndex transportIndex,
            BuildingFootprintVectorIndex buildingIndex,
            FacilityMode facilityMode) {
        if (transportIndex == null) {
            return CandidateTieInScorer.DEFAULT;
        }
        return (candidate, surface) -> {
            double basePenalty = Math.max(0.0, surface.assess(candidate).penaltyMultiplier());
            TransportNetworkVectorIndex.NearestRoadTieIn tieIn = transportIndex.findNearestRoadTieIn(
                    candidate,
                    pt -> fullRegion.contains(pt)
                            && !surface.assess(pt).blocked()
                            && (buildingIndex == null
                            || buildingIndex.findEndpointBuildingOrSettlementObstacle(
                                    pt, ENDPOINT_INSTITUTIONAL_BUFFER_METERS, ENDPOINT_BUILDING_BUFFER_METERS) == null),
                    (fromPt, toPt) -> buildingIndex == null
                            || !buildingIndex.hasSegmentBuildingOrExclusionConflict(fromPt, toPt, 25.0, 75.0),
                    facilityMode);
            if (tieIn == null) {
                return basePenalty + 80.0;
            }
            boolean cleanDirect = buildingIndex == null
                    || !buildingIndex.hasSegmentBuildingOrExclusionConflict(
                            candidate, tieIn.tieInPoint(), 25.0, 75.0);
            double categoryPenalty = TerminalAccessPolicy.candidateTieInCategoryPenalty(tieIn.category(), facilityMode);
            double distScale = facilityMode == FacilityMode.EXPRESSWAY ? 400.0 : 500.0;
            double distScore = Math.min(30.0, tieIn.distanceMeters() / distScale);
            return basePenalty + categoryPenalty + distScore + (cleanDirect ? 0.0 : 40.0);
        };
    }

    /**
     * Facility-mode-aware tie-in scorer for Tier-1 Pan-India dynamic corridors when an OSM
     * arterial transport index is available.
     */
    public static CandidateTieInScorer forDynamicRoadTieIn(
            OsmDynamicTransportNetworkCache.OsmTransportIndex osmTransportIndex,
            OsmDynamicBuildingSettlementCache.OsmCorridorIndex osmIndex,
            FacilityMode facilityMode) {
        if (osmTransportIndex == null || osmTransportIndex.isEmpty()) {
            return CandidateTieInScorer.DEFAULT;
        }
        boolean hasOsm = osmIndex != null && !osmIndex.isEmpty();
        return (candidate, surface) -> {
            double basePenalty = Math.max(0.0, surface.assess(candidate).penaltyMultiplier());
            TransportNetworkVectorIndex.NearestRoadTieIn tieIn = osmTransportIndex.findNearestRoadTieIn(
                    candidate,
                    pt -> !surface.assess(pt).blocked()
                            && (!hasOsm || osmIndex.findEndpointObstacleReason(
                                    pt, ENDPOINT_INSTITUTIONAL_BUFFER_METERS, ENDPOINT_BUILDING_BUFFER_METERS) == null),
                    (fromPt, toPt) -> !hasOsm
                            || !osmIndex.hasSegmentBuildingOrExclusionConflict(fromPt, toPt, 25.0, 75.0),
                    facilityMode);
            if (tieIn == null) {
                return basePenalty + 80.0;
            }
            boolean cleanDirect = !hasOsm
                    || !osmIndex.hasSegmentBuildingOrExclusionConflict(candidate, tieIn.tieInPoint(), 25.0, 75.0);
            double categoryPenalty = TerminalAccessPolicy.candidateTieInCategoryPenalty(tieIn.category(), facilityMode);
            double distScale = facilityMode == FacilityMode.EXPRESSWAY ? 400.0 : 500.0;
            double distScore = Math.min(30.0, tieIn.distanceMeters() / distScale);
            return basePenalty + categoryPenalty + distScore + (cleanDirect ? 0.0 : 40.0);
        };
    }

    public EndpointResolution resolveEndpoints(
            StudyRegion fullRegion,
            StudyRegion searchWindow,
            CostSurface surface,
            GeoPoint requestedStart,
            GeoPoint requestedGoal) {
        return resolveEndpoints(
                fullRegion,
                searchWindow,
                surface,
                requestedStart,
                requestedGoal,
                EndpointObstacleEvaluator.NONE,
                CandidateTieInScorer.DEFAULT);
    }

    public EndpointResolution resolveEndpoints(
            StudyRegion fullRegion,
            StudyRegion searchWindow,
            CostSurface surface,
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            EndpointObstacleEvaluator obstacleEvaluator,
            CandidateTieInScorer tieInScorer) {

        if (!fullRegion.contains(requestedStart) || !fullRegion.contains(requestedGoal)) {
            throw new IllegalArgumentException(
                    "Both endpoints must lie inside the active corridor region (" + fullRegion.name() + ").");
        }

        double directDistanceMeters = projection.distanceMeters(requestedStart, requestedGoal);
        if (directDistanceMeters < MIN_ENDPOINT_SEPARATION_METERS) {
            throw new IllegalArgumentException(
                    "Start and destination points must be separated by at least "
                            + (MIN_ENDPOINT_SEPARATION_METERS / 1000.0) + " km.");
        }

        EndpointObstacleEvaluator eval = obstacleEvaluator != null
                ? obstacleEvaluator
                : EndpointObstacleEvaluator.NONE;
        CandidateTieInScorer scorer = tieInScorer != null
                ? tieInScorer
                : CandidateTieInScorer.DEFAULT;

        SnapPointResult startRes = snapIfBlocked(fullRegion, null, surface, requestedStart, eval, scorer);
        SnapPointResult goalRes = snapIfBlocked(fullRegion, null, surface, requestedGoal, eval, scorer);

        return new EndpointResolution(
                requestedStart,
                startRes.resolvedPoint(),
                startRes.snapped(),
                round(startRes.snapDistanceMeters()),
                startRes.reason(),
                requestedGoal,
                goalRes.resolvedPoint(),
                goalRes.snapped(),
                round(goalRes.snapDistanceMeters()),
                goalRes.reason()
        );
    }

    public SnapPointResult snapPoint(
            StudyRegion fullRegion,
            CostSurface surface,
            GeoPoint original) {
        return snapPoint(fullRegion, surface, original, EndpointObstacleEvaluator.NONE, CandidateTieInScorer.DEFAULT);
    }

    public SnapPointResult snapPoint(
            StudyRegion fullRegion,
            CostSurface surface,
            GeoPoint original,
            EndpointObstacleEvaluator obstacleEvaluator,
            CandidateTieInScorer tieInScorer) {
        return snapIfBlocked(
                fullRegion,
                null,
                surface,
                original,
                obstacleEvaluator != null ? obstacleEvaluator : EndpointObstacleEvaluator.NONE,
                tieInScorer != null ? tieInScorer : CandidateTieInScorer.DEFAULT);
    }

    private SnapPointResult snapIfBlocked(
            StudyRegion fullRegion,
            StudyRegion searchWindow,
            CostSurface surface,
            GeoPoint original,
            EndpointObstacleEvaluator obstacleEvaluator,
            CandidateTieInScorer tieInScorer) {

        String initialObstacle = obstacleEvaluator.findObstacleReason(original);
        if (initialObstacle == null && isNavigableEndpoint(fullRegion, searchWindow, surface, original, obstacleEvaluator)) {
            return new SnapPointResult(original, false, 0.0, "EXACT_COORDINATE_ACCESSIBLE");
        }

        CostAssessment initialAssessment = surface.assess(original);
        String blockedReason;
        if (initialAssessment.blocked()
                && initialAssessment.className() != null
                && (initialAssessment.className().toUpperCase(Locale.ROOT).contains("SACRED")
                || initialAssessment.className().toUpperCase(Locale.ROOT).contains("PROTECTED_AREA"))) {
            blockedReason = initialAssessment.className();
        } else if (initialObstacle != null && !initialObstacle.isBlank()) {
            blockedReason = initialObstacle;
        } else if (initialAssessment.className() != null && !initialAssessment.className().isBlank()) {
            blockedReason = initialAssessment.className();
        } else {
            blockedReason = "HARD_CONSTRAINT_OR_ISOLATED_GRID_NODE";
        }

        boolean isExpresswayUrbanSnap = blockedReason.startsWith("EXPRESSWAY_CANNOT_TERMINATE_INSIDE_URBAN_CORE");
        boolean isExpresswayLocalStreetSnap = blockedReason.startsWith("EXPRESSWAY_CANNOT_START_ON_LOCAL_STREET");
        double maxSnapRadius = isExpresswayUrbanSnap
                ? MAX_EXPRESSWAY_URBAN_SNAP_RADIUS_METERS
                : MAX_SNAP_RADIUS_METERS;

        MetricPoint originMetric = projection.forward(original);
        GeoPoint bestCandidate = null;
        double bestRawScore = Double.POSITIVE_INFINITY;
        double bestEffectiveScore = Double.POSITIVE_INFINITY;
        double bestDistance = 0.0;
        double firstFeasibleRadius = -1.0;

        for (double radius = GRID_STEP_METERS; radius <= maxSnapRadius; radius += GRID_STEP_METERS) {
            int samples = radius > 4000.0 ? 36 : ANGULAR_SAMPLES_PER_RING;
            for (int i = 0; i < samples; i++) {
                double theta = (2.0 * Math.PI * i) / samples;
                double cx = originMetric.x() + radius * Math.cos(theta);
                double cy = originMetric.y() + radius * Math.sin(theta);
                GeoPoint candidate = projection.inverse(new MetricPoint(cx, cy));

                if (isNavigableEndpoint(fullRegion, searchWindow, surface, candidate, obstacleEvaluator)) {
                    double score = tieInScorer.scoreCandidate(candidate, surface);
                    double dist = projection.distanceMeters(original, candidate);
                    double effectiveScore = firstFeasibleRadius < 0.0
                            ? score
                            : score + 0.004 * Math.max(0.0, dist - firstFeasibleRadius);
                    if (effectiveScore < bestEffectiveScore) {
                        bestEffectiveScore = effectiveScore;
                        bestRawScore = score;
                        bestCandidate = candidate;
                        bestDistance = dist;
                    }
                }
            }
            if (bestCandidate != null) {
                if (firstFeasibleRadius < 0.0) {
                    firstFeasibleRadius = radius;
                }
                // Stop immediately if we found a clean, highway-adjacent candidate, or after bounded lookahead
                if (bestRawScore <= 12.0 || radius >= firstFeasibleRadius + 3.0 * GRID_STEP_METERS) {
                    String reason;
                    if (isExpresswayUrbanSnap) {
                        reason = "SNAPPED_EXPRESSWAY_OUTSIDE_CITY_CORE_TO_MAJOR_HIGHWAY_TIE_IN:" + blockedReason;
                    } else if (isExpresswayLocalStreetSnap) {
                        reason = "SNAPPED_EXPRESSWAY_OFF_LOCAL_STREET_TO_MAJOR_HIGHWAY_TIE_IN:" + blockedReason;
                    } else {
                        reason = "SNAPPED_FROM_" + blockedReason + "_TO_NEAREST_FEASIBLE_GREENFIELD_ROAD_EDGE";
                    }
                    return new SnapPointResult(bestCandidate, true, bestDistance, reason);
                }
            }
        }

        if (bestCandidate != null) {
            String reason = isExpresswayUrbanSnap
                    ? "SNAPPED_EXPRESSWAY_OUTSIDE_CITY_CORE_TO_MAJOR_HIGHWAY_TIE_IN:" + blockedReason
                    : isExpresswayLocalStreetSnap
                            ? "SNAPPED_EXPRESSWAY_OFF_LOCAL_STREET_TO_MAJOR_HIGHWAY_TIE_IN:" + blockedReason
                            : "SNAPPED_FROM_" + blockedReason + "_TO_NEAREST_FEASIBLE_GREENFIELD_ROAD_EDGE";
            return new SnapPointResult(bestCandidate, true, bestDistance, reason);
        }

        throw new IllegalStateException(
                "Endpoint (" + original.lat() + ", " + original.lon()
                        + ") is inside an extensive exclusion zone (" + blockedReason
                        + ") and no navigable greenfield cell was found within " + (maxSnapRadius / 1000.0) + " km.");
    }

    /**
     * Verifies that the candidate point and its snapped 250 m grid node are both unblocked,
     * outside hospitals/settlements/buildings, and have at least 3 open 250 m neighbors
     * connected to a >= 35-cell open greenfield component.
     */
    private boolean isNavigableEndpoint(
            StudyRegion fullRegion,
            StudyRegion searchWindow,
            CostSurface surface,
            GeoPoint point,
            EndpointObstacleEvaluator obstacleEvaluator) {

        if (!fullRegion.contains(point)) {
            return false;
        }
        if (searchWindow != null && !searchWindow.contains(point)) {
            return false;
        }
        if (obstacleEvaluator.findObstacleReason(point) != null) {
            return false;
        }
        CostAssessment direct = surface.assess(point);
        if (direct.blocked()) {
            return false;
        }

        // Also check the nearest 250 m grid-aligned neighborhood around the point
        MetricPoint sw = projection.forward(new GeoPoint(
                (searchWindow != null ? searchWindow : fullRegion).minLat(),
                (searchWindow != null ? searchWindow : fullRegion).minLon()));
        MetricPoint m = projection.forward(point);
        double gridX = sw.x() + Math.round((m.x() - sw.x()) / GRID_STEP_METERS) * GRID_STEP_METERS;
        double gridY = sw.y() + Math.round((m.y() - sw.y()) / GRID_STEP_METERS) * GRID_STEP_METERS;
        GeoPoint snappedGridNode = projection.inverse(new MetricPoint(gridX, gridY));

        if (!fullRegion.contains(snappedGridNode)
                || (searchWindow != null && !searchWindow.contains(snappedGridNode))) {
            return false;
        }
        if (obstacleEvaluator.findObstacleReason(snappedGridNode) != null) {
            return false;
        }
        if (surface.assess(snappedGridNode).blocked()) {
            return false;
        }

        int openNeighbors = 0;
        int[] dirs = {-1, 0, 1};
        for (int dr : dirs) {
            for (int dc : dirs) {
                if (dr == 0 && dc == 0) {
                    continue;
                }
                GeoPoint neighbor = projection.inverse(new MetricPoint(
                        gridX + dc * GRID_STEP_METERS,
                        gridY + dr * GRID_STEP_METERS));
                if (!fullRegion.contains(neighbor)) {
                    continue;
                }
                if (searchWindow != null && !searchWindow.contains(neighbor)) {
                    continue;
                }
                if (!surface.assess(neighbor).blocked()
                        && !surface.assessEdge(snappedGridNode, neighbor).blocked()) {
                    openNeighbors++;
                }
            }
        }
        if (openNeighbors < 3) {
            return false;
        }

        // Fast bounded local BFS (up to 35 grid cells) to guarantee the snapped point is not
        // trapped in an isolated mountain valley pocket or enclosed inside an urban ring.
        int minConnectedCells = 35;
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> visited = new java.util.HashSet<>();
        queue.add(new int[]{0, 0});
        visited.add(0L);
        while (!queue.isEmpty() && visited.size() < minConnectedCells) {
            int[] cur = queue.poll();
            GeoPoint curPt = projection.inverse(new MetricPoint(
                    gridX + cur[1] * GRID_STEP_METERS,
                    gridY + cur[0] * GRID_STEP_METERS));
            for (int dr : dirs) {
                for (int dc : dirs) {
                    if (dr == 0 && dc == 0) continue;
                    int nr = cur[0] + dr;
                    int nc = cur[1] + dc;
                    long key = (((long) nr) << 32) ^ (nc & 0xffffffffL);
                    if (visited.contains(key)) continue;
                    GeoPoint nextPt = projection.inverse(new MetricPoint(
                            gridX + nc * GRID_STEP_METERS,
                            gridY + nr * GRID_STEP_METERS));
                    if (!fullRegion.contains(nextPt)) continue;
                    if (searchWindow != null && !searchWindow.contains(nextPt)) continue;
                    if (!surface.assess(nextPt).blocked()
                            && !surface.assessEdge(curPt, nextPt).blocked()) {
                        visited.add(key);
                        if (visited.size() >= minConnectedCells) {
                            return true;
                        }
                        queue.add(new int[]{nr, nc});
                    }
                }
            }
        }
        return visited.size() >= minConnectedCells;
    }

    private static String sanitizeLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNNAMED";
        }
        return raw.trim().replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record SnapPointResult(
            GeoPoint resolvedPoint,
            boolean snapped,
            double snapDistanceMeters,
            String reason) {}

    public record EndpointResolution(
            GeoPoint requestedStart,
            GeoPoint resolvedStart,
            boolean startSnapped,
            double startSnapDistanceMeters,
            String startSnapReason,
            GeoPoint requestedGoal,
            GeoPoint resolvedGoal,
            boolean goalSnapped,
            double goalSnapDistanceMeters,
            String goalSnapReason
    ) {
        public Map<String, Object> toMetadataMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("requestedStart", requestedStart);
            map.put("resolvedStart", resolvedStart);
            map.put("startSnapped", startSnapped);
            map.put("startSnapDistanceMeters", startSnapDistanceMeters);
            map.put("startSnapReason", startSnapReason);
            map.put("requestedGoal", requestedGoal);
            map.put("resolvedGoal", resolvedGoal);
            map.put("goalSnapped", goalSnapped);
            map.put("goalSnapDistanceMeters", goalSnapDistanceMeters);
            map.put("goalSnapReason", goalSnapReason);
            return map;
        }
    }
}
