package in.marg.region;

import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.routing.CostAssessment;
import in.marg.routing.CostSurface;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Smart Endpoint Validation & Snapping Service.
 *
 * <p>When an evaluator clicks any arbitrary start or end coordinate inside a highlighted
 * corridor region (or anywhere across India in Tier 1 mode), the exact clicked coordinate
 * or its nearest 250 m routing grid cell might fall inside a hard-blocked pixel
 * (e.g. Protected Area mask, Slope &gt; 30&deg;, active landslide scarp, or reservoir body).
 * This service automatically snaps blocked endpoints outward in concentric metric rings
 * (250 m .. 6,000 m) to the nearest navigable corridor cell with open neighbors.
 */
public class EndpointSnapService {

    private static final double GRID_STEP_METERS = 250.0;
    private static final double MAX_SNAP_RADIUS_METERS = 15000.0;
    private static final double MIN_ENDPOINT_SEPARATION_METERS = 1000.0;
    private static final int ANGULAR_SAMPLES_PER_RING = 24;

    private final CoordinateProjection projection;

    public EndpointSnapService(CoordinateProjection projection) {
        this.projection = projection;
    }

    public EndpointResolution resolveEndpoints(
            StudyRegion fullRegion,
            StudyRegion searchWindow,
            CostSurface surface,
            GeoPoint requestedStart,
            GeoPoint requestedGoal) {

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

        SnapPointResult startRes = snapIfBlocked(fullRegion, null, surface, requestedStart);
        SnapPointResult goalRes = snapIfBlocked(fullRegion, null, surface, requestedGoal);

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
        return snapIfBlocked(fullRegion, null, surface, original);
    }

    private SnapPointResult snapIfBlocked(
            StudyRegion fullRegion,
            StudyRegion searchWindow,
            CostSurface surface,
            GeoPoint original) {

        if (isNavigableEndpoint(fullRegion, searchWindow, surface, original)) {
            return new SnapPointResult(original, false, 0.0, "EXACT_COORDINATE_ACCESSIBLE");
        }

        CostAssessment initialAssessment = surface.assess(original);
        String blockedReason = initialAssessment.className() != null && !initialAssessment.className().isBlank()
                ? initialAssessment.className()
                : "HARD_CONSTRAINT_OR_ISOLATED_GRID_NODE";

        MetricPoint originMetric = projection.forward(original);
        GeoPoint bestCandidate = null;
        double bestPenalty = Double.POSITIVE_INFINITY;
        double bestDistance = 0.0;

        for (double radius = GRID_STEP_METERS; radius <= MAX_SNAP_RADIUS_METERS; radius += GRID_STEP_METERS) {
            int samples = radius > 4000.0 ? 36 : ANGULAR_SAMPLES_PER_RING;
            for (int i = 0; i < samples; i++) {
                double theta = (2.0 * Math.PI * i) / samples;
                double cx = originMetric.x() + radius * Math.cos(theta);
                double cy = originMetric.y() + radius * Math.sin(theta);
                GeoPoint candidate = projection.inverse(new MetricPoint(cx, cy));

                if (isNavigableEndpoint(fullRegion, searchWindow, surface, candidate)) {
                    CostAssessment assessment = surface.assess(candidate);
                    double penalty = Math.max(0.0, assessment.penaltyMultiplier());
                    if (penalty < bestPenalty) {
                        bestPenalty = penalty;
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
                        "SNAPPED_FROM_" + blockedReason + "_TO_NEAREST_FEASIBLE_GRID_CELL"
                );
            }
        }

        throw new IllegalStateException(
                "Endpoint (" + original.lat() + ", " + original.lon()
                        + ") is inside an extensive hard-constraint exclusion zone (" + blockedReason
                        + ") and no navigable grid cell was found within " + (MAX_SNAP_RADIUS_METERS / 1000.0) + " km.");
    }

    /**
     * Verifies that the candidate point and its snapped 250 m grid node are both unblocked
     * and have at least 3 open 250 m neighbors so A* never starts or terminates in a 1-cell trap.
     */
    private boolean isNavigableEndpoint(
            StudyRegion fullRegion,
            StudyRegion searchWindow,
            CostSurface surface,
            GeoPoint point) {

        if (!fullRegion.contains(point)) {
            return false;
        }
        if (searchWindow != null && !searchWindow.contains(point)) {
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
        // trapped in an isolated mountain valley pocket or narrow border strip behind a sanctuary.
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
