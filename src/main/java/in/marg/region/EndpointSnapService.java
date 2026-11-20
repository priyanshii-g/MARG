package in.marg.region;

import in.marg.building.BuildingDensityClass;
import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.building.OsmDynamicBuildingSettlementCache;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.routing.CostAssessment;
import in.marg.routing.CostSurface;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.transport.TransportNetworkVectorIndex;

import java.util.LinkedHashMap;
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
    private static final double MIN_ENDPOINT_SEPARATION_METERS = 1000.0;
    private static final int ANGULAR_SAMPLES_PER_RING = 24;
    public static final double ENDPOINT_INSTITUTIONAL_BUFFER_METERS = 100.0;
    public static final double ENDPOINT_BUILDING_BUFFER_METERS = 25.0;

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
     * compounds (+100 m buffer), settlement/colony polygons, building footprints, and high-density rasters.
     */
    public static EndpointObstacleEvaluator forBenchmarkCorridor(
            BuildingFootprintVectorIndex buildingIndex,
            BuildingDensityRasterProvider buildingRaster) {
        return point -> {
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
        if (osmIndex == null || osmIndex.isEmpty()) {
            return EndpointObstacleEvaluator.NONE;
        }
        return point -> osmIndex.findEndpointObstacleReason(
                point, ENDPOINT_INSTITUTIONAL_BUFFER_METERS, ENDPOINT_BUILDING_BUFFER_METERS);
    }

    /**
     * Builds a tie-in cleanliness scorer that favors open greenfield candidates with a short,
     * building-free, hospital-free tie-in to an existing highway or road.
     */
    public static CandidateTieInScorer forCleanRoadTieIn(
            StudyRegion fullRegion,
            TransportNetworkVectorIndex transportIndex,
            BuildingFootprintVectorIndex buildingIndex) {
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
                                    pt, ENDPOINT_INSTITUTIONAL_BUFFER_METERS, ENDPOINT_BUILDING_BUFFER_METERS) == null));
            if (tieIn == null) {
                return basePenalty + 50.0;
            }
            boolean cleanDirect = buildingIndex == null
                    || !buildingIndex.hasSegmentBuildingOrExclusionConflict(
                            candidate, tieIn.tieInPoint(), 25.0, 75.0);
            double distScore = Math.min(25.0, tieIn.distanceMeters() / 500.0);
            return basePenalty + distScore + (cleanDirect ? 0.0 : 40.0);
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
        if (initialObstacle != null && !initialObstacle.isBlank()) {
            blockedReason = initialObstacle;
        } else if (initialAssessment.className() != null && !initialAssessment.className().isBlank()) {
            blockedReason = initialAssessment.className();
        } else {
            blockedReason = "HARD_CONSTRAINT_OR_ISOLATED_GRID_NODE";
        }

        MetricPoint originMetric = projection.forward(original);
        GeoPoint bestCandidate = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double bestDistance = 0.0;

        for (double radius = GRID_STEP_METERS; radius <= MAX_SNAP_RADIUS_METERS; radius += GRID_STEP_METERS) {
            int samples = radius > 4000.0 ? 36 : ANGULAR_SAMPLES_PER_RING;
            for (int i = 0; i < samples; i++) {
                double theta = (2.0 * Math.PI * i) / samples;
                double cx = originMetric.x() + radius * Math.cos(theta);
                double cy = originMetric.y() + radius * Math.sin(theta);
                GeoPoint candidate = projection.inverse(new MetricPoint(cx, cy));

                if (isNavigableEndpoint(fullRegion, searchWindow, surface, candidate, obstacleEvaluator)) {
                    double score = tieInScorer.scoreCandidate(candidate, surface);
                    if (score < bestScore) {
                        bestScore = score;
                        bestCandidate = candidate;
                        bestDistance = projection.distanceMeters(original, candidate);
                    }
                }
            }
            if (bestCandidate != null) {
                return new SnapPointResult(
                        bestCandidate,
                        true,
                        bestDistance,
                        "SNAPPED_FROM_" + blockedReason + "_TO_NEAREST_FEASIBLE_GREENFIELD_ROAD_EDGE"
                );
            }
        }

        throw new IllegalStateException(
                "Endpoint (" + original.lat() + ", " + original.lon()
                        + ") is inside an extensive exclusion zone (" + blockedReason
                        + ") and no navigable greenfield cell was found within " + (MAX_SNAP_RADIUS_METERS / 1000.0) + " km.");
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
