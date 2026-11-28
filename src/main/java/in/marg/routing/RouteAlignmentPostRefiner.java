package in.marg.routing;

import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-A* alignment refinement and straight-line baseline spur construction helpers.
 *
 * <ol>
 *   <li><b>Building Lateral Micro-Shift</b>: On a 250 m routing grid, an alignment cannot shift
 *       40–75 m sideways to step around an isolated roadside building. This pass checks only
 *       interior vertices whose adjacent segments hit Direct-RoW buildings and applies the smallest
 *       perpendicular shift ({@code ±35 m..±95 m}) that strictly reduces building hits while
 *       remaining 100% unblocked on all 13 cost layers (Protected Areas, >30° slopes, Active
 *       Landslides, Hospitals/Schools/Temples, and Dense Colonies).</li>
 *   <li><b>Straight-Line Feeder Spur Builder</b>: Constructs an apples-to-apples straight-line
 *       connecting road from the nearest point on the Straight-Line baseline mainline to each
 *       connected hub so baseline vs. smart-route comparisons include connecting roads on both sides.</li>
 * </ol>
 */
public final class RouteAlignmentPostRefiner {

    private static final double[] LATERAL_OFFSETS_METERS = {35.0, -35.0, 55.0, -55.0, 75.0, -75.0, 95.0, -95.0};

    private RouteAlignmentPostRefiner() {
    }

    /**
     * Nudges interior mainline vertices slightly off isolated buildings inside the Direct RoW
     * whenever an unblocked, lower-impact lateral shift exists within 95 m.
     */
    public static List<GeoPoint> refineAroundIsolatedBuildings(
            List<GeoPoint> route,
            BuildingFootprintVectorIndex buildingIndex,
            CostSurface surface,
            CoordinateProjection projection,
            StudyRegion searchWindow,
            double rowHalfWidthMeters) {
        if (route == null || route.size() < 3 || buildingIndex == null || surface == null || projection == null) {
            return route;
        }
        double halfW = Math.max(15.0, rowHalfWidthMeters);
        double proxBuf = 60.0;
        List<GeoPoint> refined = new ArrayList<>(route);

        for (int i = 1; i < refined.size() - 1; i++) {
            GeoPoint prev = refined.get(i - 1);
            GeoPoint curr = refined.get(i);
            GeoPoint next = refined.get(i + 1);

            int currentHits = countDirectRowBuildingHits(prev, curr, next, buildingIndex, projection, halfW, proxBuf);
            if (currentHits == 0) {
                continue;
            }

            MetricPoint mPrev = projection.forward(prev);
            MetricPoint mCurr = projection.forward(curr);
            MetricPoint mNext = projection.forward(next);
            double tx = mNext.x() - mPrev.x();
            double ty = mNext.y() - mPrev.y();
            double tLen = Math.hypot(tx, ty);
            if (tLen <= 1e-6) {
                continue;
            }
            // Unit perpendicular vector to local corridor tangent
            double nx = -ty / tLen;
            double ny = tx / tLen;

            GeoPoint bestCandidate = null;
            int bestHits = currentHits;
            double bestCost = Double.POSITIVE_INFINITY;

            for (double offsetM : LATERAL_OFFSETS_METERS) {
                GeoPoint cand = projection.inverse(new MetricPoint(
                        mCurr.x() + nx * offsetM,
                        mCurr.y() + ny * offsetM));
                if (searchWindow != null && !searchWindow.contains(cand)) {
                    continue;
                }
                CostAssessment nodeAssess = surface.assess(cand);
                if (nodeAssess.blocked()) {
                    continue;
                }
                if (surface.hasEdgeCost()) {
                    if (surface.assessEdge(prev, cand).blocked() || surface.assessEdge(cand, next).blocked()) {
                        continue;
                    }
                }
                int candHits = countDirectRowBuildingHits(prev, cand, next, buildingIndex, projection, halfW, proxBuf);
                if (candHits < bestHits || (candHits == bestHits && candHits < currentHits && nodeAssess.penaltyMultiplier() < bestCost)) {
                    bestHits = candHits;
                    bestCost = nodeAssess.penaltyMultiplier();
                    bestCandidate = cand;
                    if (bestHits == 0) {
                        // Smallest clean lateral shift that eliminates all hits on this vertex pair
                        break;
                    }
                }
            }

            if (bestCandidate != null && bestHits < currentHits) {
                refined.set(i, bestCandidate);
            }
        }
        return refined;
    }

    private static int countDirectRowBuildingHits(
            GeoPoint prev,
            GeoPoint mid,
            GeoPoint next,
            BuildingFootprintVectorIndex buildingIndex,
            CoordinateProjection projection,
            double halfW,
            double proxBuf) {
        MetricPoint mPrev = projection.forward(prev);
        MetricPoint mMid = projection.forward(mid);
        MetricPoint mNext = projection.forward(next);
        int hits1 = (int) buildingIndex.evaluateStepConflictsMetric(mPrev, mMid, halfW, proxBuf)
                .buildingHits().stream().filter(BuildingFootprintVectorIndex.BuildingHit::insideDirectRow).count();
        int hits2 = (int) buildingIndex.evaluateStepConflictsMetric(mMid, mNext, halfW, proxBuf)
                .buildingHits().stream().filter(BuildingFootprintVectorIndex.BuildingHit::insideDirectRow).count();
        return hits1 + hits2;
    }

    /**
     * Builds a sampled straight-line baseline feeder spur connecting {@code hubPoint} to the
     * nearest point on {@code baselineMainline}.
     */
    public static List<GeoPoint> buildStraightLineSpurPath(
            List<GeoPoint> baselineMainline,
            GeoPoint hubPoint,
            CoordinateProjection projection,
            double stepMeters) {
        if (baselineMainline == null || baselineMainline.isEmpty() || hubPoint == null || projection == null) {
            return List.of();
        }
        MetricPoint h = projection.forward(hubPoint);
        MetricPoint bestProj = projection.forward(baselineMainline.get(0));
        double bestDistSq = distSq(bestProj, h);

        for (int i = 1; i < baselineMainline.size(); i++) {
            MetricPoint a = projection.forward(baselineMainline.get(i - 1));
            MetricPoint b = projection.forward(baselineMainline.get(i));
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double lenSq = dx * dx + dy * dy;
            double t = lenSq <= 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((h.x() - a.x()) * dx + (h.y() - a.y()) * dy) / lenSq));
            MetricPoint proj = new MetricPoint(a.x() + t * dx, a.y() + t * dy);
            double dSq = distSq(proj, h);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestProj = proj;
            }
        }

        double distMeters = Math.sqrt(bestDistSq);
        if (distMeters <= 10.0) {
            return List.of(projection.inverse(bestProj), hubPoint);
        }
        double effectiveStep = Math.max(100.0, stepMeters);
        int segments = Math.max(2, (int) Math.ceil(distMeters / effectiveStep));
        List<GeoPoint> path = new ArrayList<>(segments + 1);
        for (int s = 0; s <= segments; s++) {
            double frac = (double) s / segments;
            path.add(projection.inverse(new MetricPoint(
                    bestProj.x() + frac * (h.x() - bestProj.x()),
                    bestProj.y() + frac * (h.y() - bestProj.y()))));
        }
        return path;
    }

    /**
     * Checks whether a path crosses any hard-blocked Protected Area cells.
     */
    public static boolean crossesProtectedArea(List<GeoPoint> path, CostLayer protectedAreaLayer) {
        if (path == null || protectedAreaLayer == null) {
            return false;
        }
        for (GeoPoint pt : path) {
            if (protectedAreaLayer.assess(pt).blocked()) {
                return true;
            }
        }
        return false;
    }

    private static double distSq(MetricPoint a, MetricPoint b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return dx * dx + dy * dy;
    }
}
