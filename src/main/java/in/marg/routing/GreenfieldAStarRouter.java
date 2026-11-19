package in.marg.routing;

import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Greenfield alignment router over an artificial geographic grid.
 *
 * The search grid is constructed in a local metric CRS (UTM), while external
 * spatial providers still receive/return WGS84 latitude/longitude.
 */
@Service
public class GreenfieldAStarRouter {
    private static final double EPS = 1e-9;
    private static final int[] DIR = {-1, 0, 1};
    private static final int MAX_GRID_CELLS = 500_000;

    private final CoordinateProjection projection;

    public GreenfieldAStarRouter(CoordinateProjection projection) {
        this.projection = projection;
    }

    public List<GeoPoint> route(StudyRegion region, GeoPoint start, GeoPoint goal,
                                CostSurface surface, double resolutionMeters) {
        return route(region, start, goal, surface, resolutionMeters, region, RouteConstraints.none());
    }

    public List<GeoPoint> route(StudyRegion fullRegion, GeoPoint start, GeoPoint goal,
                                CostSurface surface, double resolutionMeters, StudyRegion searchWindow) {
        return route(fullRegion, start, goal, surface, resolutionMeters, searchWindow, RouteConstraints.none());
    }

    public List<GeoPoint> route(StudyRegion fullRegion, GeoPoint start, GeoPoint goal,
                                CostSurface surface, double resolutionMeters,
                                StudyRegion searchWindow, RouteConstraints constraints) {
        validateResolution(resolutionMeters);
        if (constraints == null || constraints.maxRouteLengthKm() == null) {
            return unconstrainedAStar(start, goal, surface, resolutionMeters, searchWindow);
        }
        return constrainedLagrangianAStar(start, goal, surface, resolutionMeters, searchWindow,
                constraints.maxRouteLengthKm());
    }

    private List<GeoPoint> unconstrainedAStar(GeoPoint start, GeoPoint goal,
                                               CostSurface surface, double resolutionMeters,
                                               StudyRegion searchWindow) {
        Grid grid = buildGrid(searchWindow, resolutionMeters);
        Node startNode = nearestNavigableNode(start, grid, surface);
        Node goalNode = nearestNavigableNode(goal, grid, surface);

        PriorityQueue<OpenEntry> open = new PriorityQueue<>(Comparator.comparingDouble(OpenEntry::f));
        Map<Node, Double> gScore = new HashMap<>();
        Map<Node, Node> cameFrom = new HashMap<>();
        Map<Node, CostAssessment> costCache = new HashMap<>();
        Map<Long, CostAssessment> edgeCostCache = new HashMap<>();
        boolean hasEdgeCost = surface.hasEdgeCost();

        ensureEndpointAccessible(surface, start, goal);
        gScore.put(startNode, 0.0);
        open.add(new OpenEntry(startNode, heuristicKm(startNode, goalNode, grid), 0.0));

        while (!open.isEmpty()) {
            OpenEntry currentEntry = open.poll();
            Node current = currentEntry.node();
            double bestKnown = gScore.getOrDefault(current, Double.POSITIVE_INFINITY);
            if (currentEntry.g() > bestKnown + EPS) continue;
            if (current.equals(goalNode)) {
                return reconstructNodePath(cameFrom, current, start, goal, grid);
            }

            GeoPoint currentPoint = point(current, grid);
            for (Node next : neighbors(current, grid.rows(), grid.cols())) {
                GeoPoint nextPoint = point(next, grid);
                if (!searchWindow.contains(nextPoint)) {
                    continue;
                }
                CostAssessment assessment = costCache.computeIfAbsent(next, n -> surface.assess(nextPoint));
                if (assessment.blocked()) continue;
                CostAssessment edgeAssessment;
                if (!hasEdgeCost) {
                    edgeAssessment = CostAssessment.free("grid-edge");
                } else {
                    long eKey = edgeKey(current, next, grid.cols());
                    edgeAssessment = edgeCostCache.computeIfAbsent(eKey,
                            k -> surface.assessEdge(currentPoint, nextPoint));
                }
                if (edgeAssessment.blocked()) continue;

                double stepKm = projection.distanceMeters(currentPoint, nextPoint) / 1000.0;
                double multiplier = 1.0
                        + Math.max(0.0, assessment.penaltyMultiplier())
                        + Math.max(0.0, edgeAssessment.penaltyMultiplier());
                double nextG = currentEntry.g() + stepKm * multiplier;
                if (nextG + EPS < gScore.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    gScore.put(next, nextG);
                    cameFrom.put(next, current);
                    double f = nextG + heuristicKm(next, goalNode, grid);
                    open.add(new OpenEntry(next, f, nextG));
                }
            }
        }
        throw new IllegalStateException("A* did not find a route inside the configured study corridor");
    }

    /**
     * Length-constrained routing.
     *
     * The ordinary LULC optimum is returned immediately when it already satisfies
     * the physical length budget. Otherwise a Lagrangian relaxation adds lambda
     * times physical distance to the scalarized routing objective. This keeps the
     * search tractable on a fine metric grid while exposing the approximation in
     * the API rather than presenting it as an exact constrained optimum.
     */
    private List<GeoPoint> constrainedLagrangianAStar(GeoPoint start, GeoPoint goal,
                                                       CostSurface surface, double resolutionMeters,
                                                       StudyRegion searchWindow, double maxRouteLengthKm) {
        Grid grid = buildGrid(searchWindow, resolutionMeters);
        Node startNode = nearestNavigableNode(start, grid, surface);
        Node goalNode = nearestNavigableNode(goal, grid, surface);
        ensureEndpointAccessible(surface, start, goal);

        Map<Node, CostAssessment> costCache = new HashMap<>();
        Map<Long, CostAssessment> edgeCostCache = new HashMap<>();

        List<GeoPoint> unconstrained = weightedAStar(start, goal, surface, grid,
                startNode, goalNode, 0.0, costCache, edgeCostCache, searchWindow);
        if (routeLengthKm(unconstrained) <= maxRouteLengthKm + 1e-7) {
            return unconstrained;
        }

        double low = 0.0;
        double high = 0.25;
        double previousDist = Double.POSITIVE_INFINITY;
        WeightedPath bestFeasible = null;
        WeightedPath shortestHardFeasible = evaluatePath(unconstrained, surface);
        final int maxBracketRuns = 6;
        for (int i = 0; i < maxBracketRuns; i++) {
            List<GeoPoint> path = weightedAStar(start, goal, surface, grid,
                    startNode, goalNode, high, costCache, edgeCostCache, searchWindow);
            WeightedPath candidate = evaluatePath(path, surface);
            if (Double.isFinite(candidate.originalCostKm())
                    && (shortestHardFeasible == null
                    || candidate.physicalDistanceKm() < shortestHardFeasible.physicalDistanceKm() - EPS)) {
                shortestHardFeasible = candidate;
            }
            if (candidate.physicalDistanceKm() <= maxRouteLengthKm + 1e-7) {
                bestFeasible = candidate;
                break;
            }
            if (i >= 2 && Math.abs(candidate.physicalDistanceKm() - previousDist) < 0.05) {
                // Converged to the shortest hard-constraint compliant bypass; further lambda scaling cannot shorten it
                break;
            }
            previousDist = candidate.physicalDistanceKm();
            low = high;
            high *= 2.0;
            if (high > 64.0) break;
        }

        if (bestFeasible == null) {
            List<GeoPoint> distanceBaseline = weightedAStar(start, goal,
                    new DistanceOnlyCostSurface(), grid,
                    startNode, goalNode, 0.0, new HashMap<>(), new HashMap<>(), searchWindow);
            if (routeLengthKm(distanceBaseline) <= maxRouteLengthKm + 1e-7) {
                WeightedPath evaluatedDistanceBaseline = evaluatePath(distanceBaseline, surface);
                if (Double.isFinite(evaluatedDistanceBaseline.originalCostKm())
                        && evaluatedDistanceBaseline.originalCostKm() < ProtectedAreaCostLayer.ESZ_10KM_LAST_RESORT_PENALTY * 0.1) {
                    return distanceBaseline;
                }
                if (shortestHardFeasible != null && Double.isFinite(shortestHardFeasible.originalCostKm())) {
                    return shortestHardFeasible.points();
                }
            }
            throw new IllegalStateException("No route satisfies the requested maximum route length of "
                    + round(maxRouteLengthKm) + " km inside the configured study corridor");
        }

        final int binaryIterations = 8;
        for (int i = 0; i < binaryIterations; i++) {
            double mid = (low + high) / 2.0;
            List<GeoPoint> path = weightedAStar(start, goal, surface, grid,
                    startNode, goalNode, mid, costCache, edgeCostCache, searchWindow);
            WeightedPath candidate = evaluatePath(path, surface);
            if (candidate.physicalDistanceKm() <= maxRouteLengthKm + 1e-7) {
                if (bestFeasible == null
                        || candidate.originalCostKm() < bestFeasible.originalCostKm() - EPS
                        || (Math.abs(candidate.originalCostKm() - bestFeasible.originalCostKm()) <= EPS
                        && candidate.physicalDistanceKm() < bestFeasible.physicalDistanceKm())) {
                    bestFeasible = candidate;
                }
                high = mid;
            } else {
                low = mid;
            }
        }

        return bestFeasible.points();
    }

    private static long edgeKey(Node u, Node v, int cols) {
        long idU = (long) u.r() * cols + u.c();
        long idV = (long) v.r() * cols + v.c();
        long min = Math.min(idU, idV);
        long max = Math.max(idU, idV);
        return (min << 32) | (max & 0xFFFFFFFFL);
    }

    private List<GeoPoint> weightedAStar(GeoPoint start, GeoPoint goal,
        CostSurface surface, Grid grid, Node startNode, Node goalNode, 
        double lambda, Map<Node, CostAssessment> sharedCostCache,
        Map<Long, CostAssessment> sharedEdgeCostCache, StudyRegion searchWindow) {
        PriorityQueue<OpenEntry> open = new PriorityQueue<>(Comparator.comparingDouble(OpenEntry::f));
        Map<Node, Double> gScore = new HashMap<>();
        Map<Node, Node> cameFrom = new HashMap<>();
        boolean hasEdgeCost = surface.hasEdgeCost();

        double startHeuristic = heuristicKm(startNode, goalNode, grid) * (1.0 + lambda);
        gScore.put(startNode, 0.0);
        open.add(new OpenEntry(startNode, startHeuristic, 0.0));

        while (!open.isEmpty()) {
            OpenEntry currentEntry = open.poll();
            Node current = currentEntry.node();
            double bestKnown = gScore.getOrDefault(current, Double.POSITIVE_INFINITY);
            if (currentEntry.g() > bestKnown + EPS) continue;
            if (current.equals(goalNode)) {
                return reconstructNodePath(cameFrom, current, start, goal, grid);
            }

            GeoPoint currentPoint = point(current, grid);
            for (Node next : neighbors(current, grid.rows(), grid.cols())) {
                GeoPoint nextPoint = point(next, grid);
                if (!searchWindow.contains(nextPoint)) {
                    continue;
                }
                CostAssessment assessment = sharedCostCache.computeIfAbsent(next,
                        n -> surface.assess(nextPoint));
                if (assessment.blocked()) continue;
                CostAssessment edgeAssessment;
                if (!hasEdgeCost) {
                    edgeAssessment = CostAssessment.free("grid-edge");
                } else {
                    long eKey = edgeKey(current, next, grid.cols());
                    edgeAssessment = sharedEdgeCostCache.computeIfAbsent(eKey,
                            k -> surface.assessEdge(currentPoint, nextPoint));
                }
                if (edgeAssessment.blocked()) continue;

                double stepKm = projection.distanceMeters(currentPoint, nextPoint) / 1000.0;
                double multiplier = 1.0
                        + Math.max(0.0, assessment.penaltyMultiplier())
                        + Math.max(0.0, edgeAssessment.penaltyMultiplier())
                        + lambda;
                double nextG = currentEntry.g() + stepKm * multiplier;
                if (nextG + EPS < gScore.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    gScore.put(next, nextG);
                    cameFrom.put(next, current);
                    double f = nextG + heuristicKm(next, goalNode, grid) * (1.0 + lambda);
                    open.add(new OpenEntry(next, f, nextG));
                }
            }
        }
        throw new IllegalStateException("A* did not find a route inside the configured study corridor");
    }

    private WeightedPath evaluatePath(List<GeoPoint> path, CostSurface surface) {
        double physical = routeLengthKm(path);
        double originalCost = 0.0;
        for (int i = 1; i < path.size(); i++) {
            CostAssessment assessment = surface.assess(path.get(i));
            CostAssessment edgeAssessment = surface.assessEdge(path.get(i - 1), path.get(i));
            if (assessment.blocked() || edgeAssessment.blocked()) {
                return new WeightedPath(path, Double.POSITIVE_INFINITY, physical);
            }
            double stepKm = projection.distanceMeters(path.get(i - 1), path.get(i)) / 1000.0;
            originalCost += stepKm * (1.0
                    + Math.max(0.0, assessment.penaltyMultiplier())
                    + Math.max(0.0, edgeAssessment.penaltyMultiplier()));
        }
        return new WeightedPath(path, originalCost, physical);
    }

    private List<GeoPoint> reconstructNodePath(Map<Node, Node> cameFrom, Node current,
                                                GeoPoint exactStart, GeoPoint exactGoal,
                                                Grid grid) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            nodes.add(current);
        }
        Collections.reverse(nodes);
        List<GeoPoint> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) result.add(point(node, grid));
        result.set(0, exactStart);
        result.set(result.size() - 1, exactGoal);
        return removeRedundantCollinearPoints(result);
    }

    private List<GeoPoint> removeRedundantCollinearPoints(List<GeoPoint> points) {
        if (points.size() < 3) return points;
        List<GeoPoint> out = new ArrayList<>();
        out.add(points.get(0));
        for (int i = 1; i < points.size() - 1; i++) {
            MetricPoint a = projection.forward(points.get(i - 1));
            MetricPoint b = projection.forward(points.get(i));
            MetricPoint c = projection.forward(points.get(i + 1));
            double dx1 = b.x() - a.x();
            double dy1 = b.y() - a.y();
            double dx2 = c.x() - b.x();
            double dy2 = c.y() - b.y();
            if (Math.abs(dx1 * dy2 - dy1 * dx2) > 1e-5) out.add(points.get(i));
        }
        out.add(points.get(points.size() - 1));
        return out;
    }

    private void ensureEndpointAccessible(CostSurface surface, GeoPoint start, GeoPoint goal) {
        CostAssessment startAssessment = surface.assess(start);
        if (startAssessment.blocked()) throw new IllegalStateException("Start point is blocked by a hard constraint");
        CostAssessment goalAssessment = surface.assess(goal);
        if (goalAssessment.blocked()) throw new IllegalStateException("Destination point is blocked by a hard constraint");
    }

    private Grid buildGrid(StudyRegion region, double resolutionMeters) {
        MetricPoint sw = projection.forward(new GeoPoint(region.minLat(), region.minLon()));
        MetricPoint se = projection.forward(new GeoPoint(region.minLat(), region.maxLon()));
        MetricPoint nw = projection.forward(new GeoPoint(region.maxLat(), region.minLon()));
        MetricPoint ne = projection.forward(new GeoPoint(region.maxLat(), region.maxLon()));

        double minX = Math.min(Math.min(sw.x(), se.x()), Math.min(nw.x(), ne.x()));
        double maxX = Math.max(Math.max(sw.x(), se.x()), Math.max(nw.x(), ne.x()));
        double minY = Math.min(Math.min(sw.y(), se.y()), Math.min(nw.y(), ne.y()));
        double maxY = Math.max(Math.max(sw.y(), se.y()), Math.max(nw.y(), ne.y()));

        double effRes = resolutionMeters;
        int cols = (int) Math.ceil((maxX - minX) / effRes) + 1;
        int rows = (int) Math.ceil((maxY - minY) / effRes) + 1;
        long cells = (long) rows * cols;
        if (cells > MAX_GRID_CELLS) {
            effRes = Math.ceil(Math.sqrt(((maxX - minX) * (maxY - minY)) / (double) (MAX_GRID_CELLS - 1000)));
            effRes = Math.max(resolutionMeters, effRes);
            cols = (int) Math.ceil((maxX - minX) / effRes) + 1;
            rows = (int) Math.ceil((maxY - minY) / effRes) + 1;
        }
        return new Grid(minX, minY, rows, cols, effRes);
    }

    private List<Node> neighbors(Node current, int rows, int cols) {
        List<Node> result = new ArrayList<>(8);
        for (int dr : DIR) {
            for (int dc : DIR) {
                if (dr == 0 && dc == 0) continue;
                int nr = current.r() + dr;
                int nc = current.c() + dc;
                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) result.add(new Node(nr, nc));
            }
        }
        return result;
    }

    private Node nearestNode(GeoPoint point, Grid grid) {
        MetricPoint metric = projection.forward(point);
        int r = (int) Math.round((metric.y() - grid.minY()) / grid.resolutionMeters());
        int c = (int) Math.round((metric.x() - grid.minX()) / grid.resolutionMeters());
        r = Math.max(0, Math.min(grid.rows() - 1, r));
        c = Math.max(0, Math.min(grid.cols() - 1, c));
        return new Node(r, c);
    }

    private Node nearestNavigableNode(GeoPoint point, Grid grid, CostSurface surface) {
        Node base = nearestNode(point, grid);
        if (!surface.assess(point(base, grid)).blocked()) {
            return base;
        }
        for (int radius = 1; radius <= 4; radius++) {
            Node best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (int dr = -radius; dr <= radius; dr++) {
                for (int dc = -radius; dc <= radius; dc++) {
                    if (Math.abs(dr) != radius && Math.abs(dc) != radius) continue;
                    int nr = base.r() + dr;
                    int nc = base.c() + dc;
                    if (nr >= 0 && nr < grid.rows() && nc >= 0 && nc < grid.cols()) {
                        Node candidate = new Node(nr, nc);
                        if (!surface.assess(point(candidate, grid)).blocked()) {
                            double dist = Math.hypot(dr, dc);
                            if (dist < bestDist) {
                                bestDist = dist;
                                best = candidate;
                            }
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return base;
    }

    private double heuristicKm(Node a, Node b, Grid grid) {
        return grid.metricDistanceMeters(a, b) / 1000.0;
    }

    private GeoPoint point(Node node, Grid grid) {
        return projection.inverse(new MetricPoint(
                grid.minX() + node.c() * grid.resolutionMeters(),
                grid.minY() + node.r() * grid.resolutionMeters()));
    }

    private void validateResolution(double resolutionMeters) {
        if (!(resolutionMeters >= 50.0 && resolutionMeters <= 5000.0) || !Double.isFinite(resolutionMeters)) {
            throw new IllegalArgumentException("resolutionMeters must be between 50 and 5000");
        }
    }

    public double routeLengthKm(List<GeoPoint> route) {
        double total = 0.0;
        for (int i = 1; i < route.size(); i++) {
            total += projection.distanceMeters(route.get(i - 1), route.get(i)) / 1000.0;
        }
        return total;
    }

    /** Retained for diagnostics; routing distances now use the configured metric projection. */
    public double haversineKm(GeoPoint a, GeoPoint b) {
        final double earthRadiusKm = 6371.0088;
        double lat1 = Math.toRadians(a.lat());
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double s = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(lat1) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        return 2.0 * earthRadiusKm * Math.atan2(Math.sqrt(s), Math.sqrt(1.0 - s));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Node(int r, int c) {}

    private record OpenEntry(Node node, double f, double g) {}

    private record WeightedPath(List<GeoPoint> points, double originalCostKm, double physicalDistanceKm) {}

    private record Grid(double minX, double minY, int rows, int cols, double resolutionMeters) {
        double metricDistanceMeters(Node a, Node b) {
            double dx = (b.c() - a.c()) * resolutionMeters;
            double dy = (b.r() - a.r()) * resolutionMeters;
            return Math.hypot(dx, dy);
        }
    }
}
