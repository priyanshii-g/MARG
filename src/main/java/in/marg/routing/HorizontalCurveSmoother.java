package in.marg.routing;

import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.transport.FacilityMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IRC-compliant horizontal alignment smoother and geometry auditor.
 *
 * <p>Eliminates 250 m A* grid staircasing and sharp 90°/135° corners in two constraint-verified passes:
 * <ol>
 *   <li><b>Constrained Line-of-Sight String-Pulling</b>: Collapses short grid zig-zags into clean straight
 *       tangents only when every 125 m sample point and sub-edge is 100% unblocked on all 13 cost layers,
 *       adds zero Direct-RoW building hits, respects institutional exclusions, and stays within 1.06× of the
 *       original sub-path cost.</li>
 *   <li><b>IRC Horizontal Circular Arc Filleting</b>: Replaces every interior deflection vertex
 *       ({@code Δ > 8°}) with a tangent-continuous circular arc using the highway class's IRC radius ladder
 *       (IRC:SP:99-2013 for Expressways, IRC:SP:84-2019 / IRC:73 for National Highways &amp; Feeder Spurs),
 *       verifying every sampled arc point and edge against all hard constraints and falling back down the
 *       radius ladder if terrain or buildings constrain the sweep.</li>
 * </ol>
 */
public final class HorizontalCurveSmoother {

    private static final double EPS = 1e-9;
    private static final double STRING_PULL_SAMPLE_STEP_METERS = 125.0;
    private static final double MAX_STRING_PULL_CHORD_METERS = 2500.0;
    private static final int MAX_STRING_PULL_LOOKAHEAD = 10;
    private static final double MAX_STRING_PULL_COST_RATIO = 1.06;
    private static final double MAX_ARC_COST_RATIO = 1.35;
    private static final double MIN_DEFLECTION_TO_FILLET_DEG = 8.0;
    private static final double TARGET_ARC_STEP_DEG = 7.5;
    private static final double TARGET_ARC_CHORD_METERS = 100.0;
    private static final double INSTITUTIONAL_BUFFER_METERS = 60.0;

    private HorizontalCurveSmoother() {
    }

    public enum CurveProfile {
        EXPRESSWAY(
                "IRC:SP:99-2013 §2.9 (Expressway, 120 km/h)",
                1000.0,
                670.0,
                45.0,
                30.0,
                new double[]{1000.0, 670.0, 450.0, 300.0, 200.0, 120.0}
        ),
        NATIONAL_HIGHWAY(
                "IRC:SP:84-2019 / IRC:73 (National Highway, 100 km/h)",
                400.0,
                250.0,
                60.0,
                22.5,
                new double[]{450.0, 400.0, 250.0, 150.0, 100.0, 75.0}
        ),
        FEEDER_SPUR(
                "IRC:73 / IRC:SP:84-2019 (Divided Feeder Spur, 80 km/h)",
                250.0,
                150.0,
                60.0,
                20.0,
                new double[]{300.0, 220.0, 150.0, 100.0, 60.0}
        );

        private final String ircStandard;
        private final double rulingMinRadiusMeters;
        private final double absoluteMinRadiusMeters;
        private final double maxAllowedVertexDeflectionDeg;
        private final double rowHalfWidthMeters;
        private final double[] radiusLadderMeters;

        CurveProfile(
                String ircStandard,
                double rulingMinRadiusMeters,
                double absoluteMinRadiusMeters,
                double maxAllowedVertexDeflectionDeg,
                double rowHalfWidthMeters,
                double[] radiusLadderMeters) {
            this.ircStandard = ircStandard;
            this.rulingMinRadiusMeters = rulingMinRadiusMeters;
            this.absoluteMinRadiusMeters = absoluteMinRadiusMeters;
            this.maxAllowedVertexDeflectionDeg = maxAllowedVertexDeflectionDeg;
            this.rowHalfWidthMeters = rowHalfWidthMeters;
            this.radiusLadderMeters = radiusLadderMeters;
        }

        public static CurveProfile forFacilityMode(FacilityMode mode) {
            return mode == FacilityMode.EXPRESSWAY ? EXPRESSWAY : NATIONAL_HIGHWAY;
        }

        public String ircStandard() { return ircStandard; }
        public double rulingMinRadiusMeters() { return rulingMinRadiusMeters; }
        public double absoluteMinRadiusMeters() { return absoluteMinRadiusMeters; }
        public double maxAllowedVertexDeflectionDeg() { return maxAllowedVertexDeflectionDeg; }
        public double rowHalfWidthMeters() { return rowHalfWidthMeters; }
        public double[] radiusLadderMeters() { return radiusLadderMeters; }
    }

    public record HorizontalGeometryReport(
            String ircStandard,
            double rulingMinRadiusMeters,
            double absoluteMinRadiusMeters,
            double maxAllowedLocalDeflectionDegrees,
            double maxLocalDeflectionDegrees,
            int sharpCornerViolationsCount,
            double estimatedMinCurveRadiusMeters,
            int smoothCurvesFittedCount,
            int originalVertexCount,
            int smoothedVertexCount,
            boolean compliant) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ircStandard", ircStandard);
            m.put("rulingMinRadiusMeters", round1(rulingMinRadiusMeters));
            m.put("absoluteMinRadiusMeters", round1(absoluteMinRadiusMeters));
            m.put("maxAllowedLocalDeflectionDegrees", round1(maxAllowedLocalDeflectionDegrees));
            m.put("maxLocalDeflectionDegrees", round1(maxLocalDeflectionDegrees));
            m.put("sharpCornerViolationsCount", sharpCornerViolationsCount);
            m.put("estimatedMinCurveRadiusMeters", Double.isFinite(estimatedMinCurveRadiusMeters)
                    ? round1(estimatedMinCurveRadiusMeters) : null);
            m.put("smoothCurvesFittedCount", smoothCurvesFittedCount);
            m.put("originalVertexCount", originalVertexCount);
            m.put("smoothedVertexCount", smoothedVertexCount);
            m.put("compliant", compliant);
            return m;
        }
    }

    public record SmoothingResult(List<GeoPoint> smoothedPath, HorizontalGeometryReport report) {
    }

    /**
     * Smooths {@code route} with constrained string-pulling and IRC circular arc filleting,
     * preserving exact start/end coordinates and verifying all 13 cost layers and building constraints.
     */
    public static SmoothingResult smoothAndAudit(
            List<GeoPoint> route,
            CostSurface surface,
            BuildingFootprintVectorIndex buildingIndex,
            CoordinateProjection projection,
            StudyRegion searchWindow,
            CurveProfile profile) {
        CurveProfile effProfile = profile != null ? profile : CurveProfile.NATIONAL_HIGHWAY;
        if (route == null || route.size() < 3 || projection == null) {
            List<GeoPoint> copy = route == null ? List.of() : List.copyOf(route);
            HorizontalGeometryReport report = auditGeometry(copy, projection, effProfile, 0, copy.size());
            return new SmoothingResult(copy, report);
        }

        int origCount = route.size();
        List<GeoPoint> working = deduplicateNearIdentical(route, projection, 2.0);
        if (working.size() < 3) {
            HorizontalGeometryReport report = auditGeometry(working, projection, effProfile, 0, origCount);
            return new SmoothingResult(working, report);
        }

        // Stage 0: Prune folded hairpin backtrack spikes (> 105° U-turns at terminal tie-in stitches or grid snaps)
        working = pruneHairpinBacktrackSpikes(working, surface, buildingIndex, projection, searchWindow, effProfile);

        // Stage A: Safe constrained line-of-sight string-pulling (removes 250 m grid staircasing)
        working = constrainedStringPull(working, surface, buildingIndex, projection, searchWindow, effProfile);

        // Re-check for any short hairpin created by stitch geometry before arc filleting
        working = pruneHairpinBacktrackSpikes(working, surface, buildingIndex, projection, searchWindow, effProfile);

        // Stage B: 2 passes of IRC circular arc filleting (Pass 1 fits full-radius arcs; Pass 2 cleans any residual >18° vertex)
        int totalCurvesFitted = 0;
        ArcPassResult pass1 = fitCircularArcs(
                working, surface, buildingIndex, projection, searchWindow, effProfile, MIN_DEFLECTION_TO_FILLET_DEG);
        working = pass1.points();
        totalCurvesFitted += pass1.curvesFitted();

        ArcPassResult pass2 = fitCircularArcs(
                working, surface, buildingIndex, projection, searchWindow, effProfile, 18.0);
        working = pass2.points();
        totalCurvesFitted += pass2.curvesFitted();

        // Preserve exact endpoints unconditionally
        List<GeoPoint> finalPath = new ArrayList<>(working);
        finalPath.set(0, route.get(0));
        finalPath.set(finalPath.size() - 1, route.get(route.size() - 1));

        HorizontalGeometryReport report = auditGeometry(finalPath, projection, effProfile, totalCurvesFitted, origCount);
        return new SmoothingResult(finalPath, report);
    }

    /**
     * Audits horizontal curvature and local deflection angles on any route without modifying it.
     */
    public static HorizontalGeometryReport auditGeometry(
            List<GeoPoint> route,
            CoordinateProjection projection,
            CurveProfile profile,
            int smoothCurvesFittedCount,
            int originalVertexCount) {
        CurveProfile effProfile = profile != null ? profile : CurveProfile.NATIONAL_HIGHWAY;
        if (route == null || route.size() < 3 || projection == null) {
            int sz = route == null ? 0 : route.size();
            return new HorizontalGeometryReport(
                    effProfile.ircStandard(),
                    effProfile.rulingMinRadiusMeters(),
                    effProfile.absoluteMinRadiusMeters(),
                    effProfile.maxAllowedVertexDeflectionDeg(),
                    0.0,
                    0,
                    Double.POSITIVE_INFINITY,
                    smoothCurvesFittedCount,
                    originalVertexCount,
                    sz,
                    true);
        }

        List<MetricPoint> pts = new ArrayList<>(route.size());
        for (GeoPoint p : route) {
            pts.add(projection.forward(p));
        }

        double maxDeflectionDeg = 0.0;
        int sharpViolations = 0;
        double minCurveRadiusM = Double.POSITIVE_INFINITY;

        for (int i = 1; i < pts.size() - 1; i++) {
            MetricPoint a = pts.get(i - 1);
            MetricPoint b = pts.get(i);
            MetricPoint c = pts.get(i + 1);

            double vx1 = b.x() - a.x();
            double vy1 = b.y() - a.y();
            double vx2 = c.x() - b.x();
            double vy2 = c.y() - b.y();
            double l1 = Math.hypot(vx1, vy1);
            double l2 = Math.hypot(vx2, vy2);
            if (l1 < 5.0 || l2 < 5.0) {
                continue;
            }

            double cos = clamp((vx1 * vx2 + vy1 * vy2) / (l1 * l2), -1.0, 1.0);
            double deflDeg = Math.toDegrees(Math.acos(cos));
            if (deflDeg > maxDeflectionDeg) {
                maxDeflectionDeg = deflDeg;
            }
            if (deflDeg > effProfile.maxAllowedVertexDeflectionDeg() + 0.5) {
                sharpViolations++;
            }

            // Circumcircle radius of triangle (a, b, c) for genuine bends (>= 5 deg)
            if (deflDeg >= 5.0 && Math.min(l1, l2) >= 20.0) {
                double chordAC = Math.hypot(c.x() - a.x(), c.y() - a.y());
                double cross = Math.abs(vx1 * vy2 - vy1 * vx2);
                double area2 = cross; // 2 * triangle area
                if (area2 > 1e-3) {
                    double r = (l1 * l2 * chordAC) / (2.0 * area2);
                    if (Double.isFinite(r) && r < minCurveRadiusM) {
                        minCurveRadiusM = r;
                    }
                }
            }
        }

        boolean compliant = sharpViolations == 0;
        return new HorizontalGeometryReport(
                effProfile.ircStandard(),
                effProfile.rulingMinRadiusMeters(),
                effProfile.absoluteMinRadiusMeters(),
                effProfile.maxAllowedVertexDeflectionDeg(),
                maxDeflectionDeg,
                sharpViolations,
                minCurveRadiusM,
                smoothCurvesFittedCount,
                originalVertexCount,
                route.size(),
                compliant);
    }

    /**
     * Removes folded hairpin backtrack spikes ({@code Δ > 105°}) that can occur where a terminal
     * road tie-in connector or snapped endpoint stitches back across the first/last mainline segment.
     * A shortcut chord is accepted only if it is strictly shorter and 100% free of hard-constraint
     * and Direct-RoW building violations.
     */
    private static List<GeoPoint> pruneHairpinBacktrackSpikes(
            List<GeoPoint> route,
            CostSurface surface,
            BuildingFootprintVectorIndex buildingIndex,
            CoordinateProjection projection,
            StudyRegion searchWindow,
            CurveProfile profile) {
        List<GeoPoint> current = new ArrayList<>(route);
        for (int pass = 0; pass < 4 && current.size() >= 3; pass++) {
            boolean changed = false;
            List<MetricPoint> metric = new ArrayList<>(current.size());
            for (GeoPoint gp : current) {
                metric.add(projection.forward(gp));
            }
            List<GeoPoint> next = new ArrayList<>(current.size());
            next.add(current.get(0));

            int i = 1;
            while (i < current.size() - 1) {
                MetricPoint mPrev = projection.forward(next.get(next.size() - 1));
                MetricPoint mCurr = metric.get(i);
                MetricPoint mNext = metric.get(i + 1);

                double vx1 = mCurr.x() - mPrev.x();
                double vy1 = mCurr.y() - mPrev.y();
                double vx2 = mNext.x() - mCurr.x();
                double vy2 = mNext.y() - mCurr.y();
                double l1 = Math.hypot(vx1, vy1);
                double l2 = Math.hypot(vx2, vy2);

                double deflDeg = 0.0;
                if (l1 > 1.0 && l2 > 1.0) {
                    double cos = clamp((vx1 * vx2 + vy1 * vy2) / (l1 * l2), -1.0, 1.0);
                    deflDeg = Math.toDegrees(Math.acos(cos));
                }

                if (deflDeg > 105.0) {
                    // Try bypassing the folded spike vertex (or small 2-4 vertex U-turn fold)
                    int maxJ = Math.min(current.size() - 1, i + 4);
                    int bestJ = -1;
                    GeoPoint fromGeo = next.get(next.size() - 1);
                    for (int j = maxJ; j >= i + 1; j--) {
                        double chordM = dist(mPrev, metric.get(j));
                        if (chordM > 3500.0) {
                            continue;
                        }
                        if (isChordHardSafe(fromGeo, mPrev, current.get(j), metric.get(j),
                                surface, buildingIndex, projection, searchWindow, profile)) {
                            bestJ = j;
                            break;
                        }
                    }
                    if (bestJ != -1) {
                        // Skip the hairpin spike vertex(es); jump directly to bestJ
                        if (bestJ < current.size() - 1) {
                            next.add(current.get(bestJ));
                        }
                        i = bestJ + 1;
                        changed = true;
                        continue;
                    }
                }

                next.add(current.get(i));
                i++;
            }

            GeoPoint endPt = current.get(current.size() - 1);
            if (next.isEmpty() || projection.distanceMeters(next.get(next.size() - 1), endPt) > 0.5) {
                next.add(endPt);
            } else {
                next.set(next.size() - 1, endPt);
            }
            current = next;
            if (!changed) {
                break;
            }
        }
        return current;
    }

    private static boolean isChordHardSafe(
            GeoPoint gA, MetricPoint mA,
            GeoPoint gB, MetricPoint mB,
            CostSurface surface,
            BuildingFootprintVectorIndex buildingIndex,
            CoordinateProjection projection,
            StudyRegion searchWindow,
            CurveProfile profile) {
        double chordM = dist(mA, mB);
        int steps = Math.max(1, (int) Math.ceil(chordM / STRING_PULL_SAMPLE_STEP_METERS));
        GeoPoint prevG = gA;
        MetricPoint prevM = mA;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            MetricPoint currM = (s == steps)
                    ? mB
                    : new MetricPoint(mA.x() + t * (mB.x() - mA.x()), mA.y() + t * (mB.y() - mA.y()));
            GeoPoint currG = (s == steps) ? gB : projection.inverse(currM);
            if (searchWindow != null && !searchWindow.contains(currG)) {
                return false;
            }
            if (surface != null) {
                if (surface.assess(currG).blocked()) {
                    return false;
                }
                if (surface.hasEdgeCost() && surface.assessEdge(prevG, currG).blocked()) {
                    return false;
                }
            }
            if (buildingIndex != null) {
                if (buildingIndex.findIntersectingInstitutionalOrSacredExclusion(
                        prevG, currG, profile.rowHalfWidthMeters() + INSTITUTIONAL_BUFFER_METERS) != null) {
                    return false;
                }
                if (countDirectRowBuildingHits(prevM, currM, buildingIndex, profile.rowHalfWidthMeters()) > 0) {
                    return false;
                }
            }
            prevG = currG;
            prevM = currM;
        }
        if (surface != null && surface.hasEdgeCost() && surface.assessEdge(gA, gB).blocked()) {
            return false;
        }
        return true;
    }

    private static List<GeoPoint> constrainedStringPull(
            List<GeoPoint> route,
            CostSurface surface,
            BuildingFootprintVectorIndex buildingIndex,
            CoordinateProjection projection,
            StudyRegion searchWindow,
            CurveProfile profile) {
        int n = route.size();
        if (n < 3) {
            return route;
        }

        List<MetricPoint> metric = new ArrayList<>(n);
        for (GeoPoint gp : route) {
            metric.add(projection.forward(gp));
        }

        List<GeoPoint> out = new ArrayList<>();
        out.add(route.get(0));

        int i = 0;
        while (i < n - 1) {
            int maxJ = Math.min(n - 1, i + MAX_STRING_PULL_LOOKAHEAD);
            int chosenJ = i + 1;

            for (int j = maxJ; j >= i + 2; j--) {
                double chordM = dist(metric.get(i), metric.get(j));
                if (chordM > MAX_STRING_PULL_CHORD_METERS || chordM < 20.0) {
                    continue;
                }
                if (isChordSafeAndCostPreserving(
                        route, metric, i, j, surface, buildingIndex, projection, searchWindow, profile)) {
                    chosenJ = j;
                    break;
                }
            }

            out.add(route.get(chosenJ));
            i = chosenJ;
        }
        return out;
    }

    private static boolean isChordSafeAndCostPreserving(
            List<GeoPoint> route,
            List<MetricPoint> metric,
            int fromIdx,
            int toIdx,
            CostSurface surface,
            BuildingFootprintVectorIndex buildingIndex,
            CoordinateProjection projection,
            StudyRegion searchWindow,
            CurveProfile profile) {
        // Compute original sub-path cost and baseline building hits
        double origCost = 0.0;
        int origBuildingHits = 0;
        for (int k = fromIdx; k < toIdx; k++) {
            double segCost = evaluateSegmentCost(
                    route.get(k), route.get(k + 1), metric.get(k), metric.get(k + 1), surface);
            if (!Double.isFinite(segCost)) {
                origCost = Double.POSITIVE_INFINITY;
                break;
            }
            origCost += segCost;
            if (buildingIndex != null) {
                origBuildingHits += countDirectRowBuildingHits(
                        metric.get(k), metric.get(k + 1), buildingIndex, profile.rowHalfWidthMeters());
            }
        }

        // Sample candidate direct chord at <= 125 m steps
        MetricPoint mA = metric.get(fromIdx);
        MetricPoint mB = metric.get(toIdx);
        double chordM = dist(mA, mB);
        int steps = Math.max(1, (int) Math.ceil(chordM / STRING_PULL_SAMPLE_STEP_METERS));

        double chordCost = 0.0;
        int chordBuildingHits = 0;
        GeoPoint prevGeo = route.get(fromIdx);
        MetricPoint prevMetric = mA;

        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            MetricPoint currMetric = (s == steps)
                    ? mB
                    : new MetricPoint(mA.x() + t * (mB.x() - mA.x()), mA.y() + t * (mB.y() - mA.y()));
            GeoPoint currGeo = (s == steps) ? route.get(toIdx) : projection.inverse(currMetric);

            if (searchWindow != null && !searchWindow.contains(currGeo)) {
                return false;
            }
            if (surface != null) {
                CostAssessment nodeA = surface.assess(currGeo);
                if (nodeA.blocked()) {
                    return false;
                }
                CostAssessment edgeA = surface.hasEdgeCost()
                        ? surface.assessEdge(prevGeo, currGeo)
                        : CostAssessment.free("grid-edge");
                if (edgeA.blocked()) {
                    return false;
                }
                double stepKm = dist(prevMetric, currMetric) / 1000.0;
                chordCost += stepKm * (1.0
                        + Math.max(0.0, nodeA.penaltyMultiplier())
                        + Math.max(0.0, edgeA.penaltyMultiplier()));
            } else {
                chordCost += dist(prevMetric, currMetric) / 1000.0;
            }

            if (buildingIndex != null) {
                if (buildingIndex.findIntersectingInstitutionalOrSacredExclusion(
                        prevGeo, currGeo, profile.rowHalfWidthMeters() + INSTITUTIONAL_BUFFER_METERS) != null) {
                    return false;
                }
                chordBuildingHits += countDirectRowBuildingHits(
                        prevMetric, currMetric, buildingIndex, profile.rowHalfWidthMeters());
                if (chordBuildingHits > origBuildingHits) {
                    return false;
                }
            }

            prevGeo = currGeo;
            prevMetric = currMetric;
        }

        if (surface != null && surface.hasEdgeCost()
                && surface.assessEdge(route.get(fromIdx), route.get(toIdx)).blocked()) {
            return false;
        }
        if (Double.isFinite(origCost) && chordCost > origCost * MAX_STRING_PULL_COST_RATIO + 1e-6) {
            return false;
        }
        return true;
    }

    private record ArcPassResult(List<GeoPoint> points, int curvesFitted) {
    }

    private static ArcPassResult fitCircularArcs(
            List<GeoPoint> route,
            CostSurface surface,
            BuildingFootprintVectorIndex buildingIndex,
            CoordinateProjection projection,
            StudyRegion searchWindow,
            CurveProfile profile,
            double minDeflectionDeg) {
        int n = route.size();
        if (n < 3) {
            return new ArcPassResult(route, 0);
        }

        List<MetricPoint> metric = new ArrayList<>(n);
        for (GeoPoint p : route) {
            metric.add(projection.forward(p));
        }

        List<GeoPoint> out = new ArrayList<>(n * 2);
        out.add(route.get(0));
        int curvesFitted = 0;

        for (int i = 1; i < n - 1; i++) {
            MetricPoint mA = metric.get(i - 1);
            MetricPoint mB = metric.get(i);
            MetricPoint mC = metric.get(i + 1);

            double vx1 = mB.x() - mA.x();
            double vy1 = mB.y() - mA.y();
            double vx2 = mC.x() - mB.x();
            double vy2 = mC.y() - mB.y();
            double l1 = Math.hypot(vx1, vy1);
            double l2 = Math.hypot(vx2, vy2);

            if (l1 < 30.0 || l2 < 30.0) {
                out.add(route.get(i));
                continue;
            }

            double ux1 = vx1 / l1;
            double uy1 = vy1 / l1;
            double ux2 = vx2 / l2;
            double uy2 = vy2 / l2;
            double cos = clamp(ux1 * ux2 + uy1 * uy2, -1.0, 1.0);
            double deltaRad = Math.acos(cos);
            double deltaDeg = Math.toDegrees(deltaRad);

            if (deltaDeg <= minDeflectionDeg || deltaDeg >= 175.0) {
                out.add(route.get(i));
                continue;
            }

            double tanHalf = Math.tan(deltaRad / 2.0);
            if (tanHalf <= 1e-6) {
                out.add(route.get(i));
                continue;
            }

            // Cap tangent pullback at 47% of the shorter adjacent leg so consecutive fillets never overlap
            double maxTangentM = 0.47 * Math.min(l1, l2);
            double cross = ux1 * uy2 - uy1 * ux2;
            double turnSign = cross >= 0.0 ? 1.0 : -1.0;
            // Inward unit normal to incoming tangent pointing toward the circle center
            double nx1 = -turnSign * uy1;
            double ny1 = turnSign * ux1;

            GeoPoint prevEmittedGeo = out.get(out.size() - 1);
            GeoPoint nextOriginalGeo = route.get(i + 1);
            List<GeoPoint> acceptedArc = tryFitArcFromLadder(
                    mB, route.get(i), prevEmittedGeo, nextOriginalGeo, ux1, uy1, ux2, uy2, nx1, ny1,
                    deltaRad, deltaDeg, tanHalf, maxTangentM, turnSign,
                    surface, buildingIndex, projection, searchWindow, profile, true);
            if (acceptedArc == null && deltaDeg > profile.maxAllowedVertexDeflectionDeg()) {
                // Sharp prohibited corner (>45° Expressway / >60° NH): retry checking strictly hard constraints
                // (zero blocked layers, zero institutional exclusions, zero new Direct-RoW buildings) without soft cost-ratio cap.
                acceptedArc = tryFitArcFromLadder(
                        mB, route.get(i), prevEmittedGeo, nextOriginalGeo, ux1, uy1, ux2, uy2, nx1, ny1,
                        deltaRad, deltaDeg, tanHalf, maxTangentM, turnSign,
                        surface, buildingIndex, projection, searchWindow, profile, false);
            }

            if (acceptedArc != null) {
                for (GeoPoint gp : acceptedArc) {
                    if (out.isEmpty() || projection.distanceMeters(out.get(out.size() - 1), gp) > 2.0) {
                        out.add(gp);
                    }
                }
                curvesFitted++;
            } else {
                out.add(route.get(i));
            }
        }

        GeoPoint last = route.get(n - 1);
        if (out.isEmpty() || projection.distanceMeters(out.get(out.size() - 1), last) > 0.5) {
            out.add(last);
        } else {
            out.set(out.size() - 1, last);
        }
        return new ArcPassResult(out, curvesFitted);
    }

    private static List<GeoPoint> tryFitArcFromLadder(
            MetricPoint mB,
            GeoPoint gCorner,
            GeoPoint prevEmittedGeo,
            GeoPoint nextOriginalGeo,
            double ux1, double uy1, double ux2, double uy2,
            double nx1, double ny1,
            double deltaRad, double deltaDeg, double tanHalf, double maxTangentM, double turnSign,
            CostSurface surface,
            BuildingFootprintVectorIndex buildingIndex,
            CoordinateProjection projection,
            StudyRegion searchWindow,
            CurveProfile profile,
            boolean enforceSoftCostRatio) {
        double lastTriedEffectiveRadius = -1.0;

        for (double targetRadiusM : profile.radiusLadderMeters()) {
            double tangentM = Math.min(targetRadiusM * tanHalf, maxTangentM);
            if (tangentM < 12.0) {
                continue;
            }
            double effectiveRadiusM = tangentM / tanHalf;
            if (effectiveRadiusM < 35.0) {
                continue;
            }
            if (lastTriedEffectiveRadius > 0.0
                    && Math.abs(effectiveRadiusM - lastTriedEffectiveRadius) < 1.0) {
                continue;
            }
            lastTriedEffectiveRadius = effectiveRadiusM;

            MetricPoint pc = new MetricPoint(mB.x() - ux1 * tangentM, mB.y() - uy1 * tangentM);
            MetricPoint pt = new MetricPoint(mB.x() + ux2 * tangentM, mB.y() + uy2 * tangentM);
            MetricPoint center = new MetricPoint(
                    pc.x() + nx1 * effectiveRadiusM,
                    pc.y() + ny1 * effectiveRadiusM);

            double theta0 = Math.atan2(pc.y() - center.y(), pc.x() - center.x());
            double sweep = turnSign * deltaRad;
            double arcLengthM = effectiveRadiusM * deltaRad;
            int segs = Math.max(4, Math.max(
                    (int) Math.ceil(deltaDeg / TARGET_ARC_STEP_DEG),
                    (int) Math.ceil(arcLengthM / TARGET_ARC_CHORD_METERS)));
            segs = Math.min(segs, 24);

            List<MetricPoint> arcMetric = new ArrayList<>(segs + 1);
            List<GeoPoint> arcGeo = new ArrayList<>(segs + 1);
            arcMetric.add(pc);
            arcGeo.add(projection.inverse(pc));
            for (int k = 1; k < segs; k++) {
                double theta = theta0 + sweep * ((double) k / segs);
                MetricPoint mp = new MetricPoint(
                        center.x() + effectiveRadiusM * Math.cos(theta),
                        center.y() + effectiveRadiusM * Math.sin(theta));
                arcMetric.add(mp);
                arcGeo.add(projection.inverse(mp));
            }
            arcMetric.add(pt);
            arcGeo.add(projection.inverse(pt));

            if (isArcSafe(
                    pc, mB, pt,
                    arcGeo.get(0), gCorner, arcGeo.get(arcGeo.size() - 1),
                    prevEmittedGeo, nextOriginalGeo,
                    arcGeo, arcMetric,
                    surface, buildingIndex, searchWindow, profile, enforceSoftCostRatio)) {
                return arcGeo;
            }
        }
        return null;
    }

    private static boolean isArcSafe(
            MetricPoint mPc, MetricPoint mCorner, MetricPoint mPt,
            GeoPoint gPc, GeoPoint gCorner, GeoPoint gPt,
            GeoPoint prevEmittedGeo, GeoPoint nextOriginalGeo,
            List<GeoPoint> arcGeo, List<MetricPoint> arcMetric,
            CostSurface surface,
            BuildingFootprintVectorIndex buildingIndex,
            StudyRegion searchWindow,
            CurveProfile profile,
            boolean enforceSoftCostRatio) {
        if (surface != null && surface.hasEdgeCost()) {
            if (prevEmittedGeo != null && surface.assessEdge(prevEmittedGeo, gPc).blocked()) {
                return false;
            }
            if (nextOriginalGeo != null && surface.assessEdge(gPt, nextOriginalGeo).blocked()) {
                return false;
            }
        }
        double origWedgeCost = 0.0;
        if (surface != null) {
            double c1 = evaluateSegmentCost(gPc, gCorner, mPc, mCorner, surface);
            double c2 = evaluateSegmentCost(gCorner, gPt, mCorner, mPt, surface);
            origWedgeCost = (Double.isFinite(c1) && Double.isFinite(c2))
                    ? (c1 + c2)
                    : Double.POSITIVE_INFINITY;
        }

        int origWedgeBldHits = 0;
        if (buildingIndex != null) {
            origWedgeBldHits = countDirectRowBuildingHits(mPc, mCorner, buildingIndex, profile.rowHalfWidthMeters())
                    + countDirectRowBuildingHits(mCorner, mPt, buildingIndex, profile.rowHalfWidthMeters());
        }

        double arcCost = 0.0;
        int arcBldHits = 0;
        for (int k = 0; k < arcGeo.size(); k++) {
            GeoPoint gp = arcGeo.get(k);
            if (searchWindow != null && !searchWindow.contains(gp)) {
                return false;
            }
            if (surface != null && surface.assess(gp).blocked()) {
                return false;
            }
            if (k > 0) {
                GeoPoint prevG = arcGeo.get(k - 1);
                MetricPoint prevM = arcMetric.get(k - 1);
                MetricPoint currM = arcMetric.get(k);
                if (surface != null) {
                    CostAssessment edgeA = surface.hasEdgeCost()
                            ? surface.assessEdge(prevG, gp)
                            : CostAssessment.free("arc-edge");
                    if (edgeA.blocked()) {
                        return false;
                    }
                    CostAssessment nodeA = surface.assess(gp);
                    double stepKm = dist(prevM, currM) / 1000.0;
                    arcCost += stepKm * (1.0
                            + Math.max(0.0, nodeA.penaltyMultiplier())
                            + Math.max(0.0, edgeA.penaltyMultiplier()));
                }
                if (buildingIndex != null) {
                    if (buildingIndex.findIntersectingInstitutionalOrSacredExclusion(
                            prevG, gp, profile.rowHalfWidthMeters() + INSTITUTIONAL_BUFFER_METERS) != null) {
                        return false;
                    }
                    arcBldHits += countDirectRowBuildingHits(
                            prevM, currM, buildingIndex, profile.rowHalfWidthMeters());
                    if (arcBldHits > origWedgeBldHits) {
                        return false;
                    }
                }
            }
        }

        if (enforceSoftCostRatio && surface != null && Double.isFinite(origWedgeCost)
                && arcCost > origWedgeCost * MAX_ARC_COST_RATIO + 0.05) {
            return false;
        }
        return true;
    }

    private static double evaluateSegmentCost(
            GeoPoint gA, GeoPoint gB, MetricPoint mA, MetricPoint mB, CostSurface surface) {
        if (surface == null) {
            return dist(mA, mB) / 1000.0;
        }
        CostAssessment nodeB = surface.assess(gB);
        if (nodeB.blocked()) {
            return Double.POSITIVE_INFINITY;
        }
        CostAssessment edge = surface.hasEdgeCost()
                ? surface.assessEdge(gA, gB)
                : CostAssessment.free("seg-edge");
        if (edge.blocked()) {
            return Double.POSITIVE_INFINITY;
        }
        double stepKm = dist(mA, mB) / 1000.0;
        return stepKm * (1.0
                + Math.max(0.0, nodeB.penaltyMultiplier())
                + Math.max(0.0, edge.penaltyMultiplier()));
    }

    private static int countDirectRowBuildingHits(
            MetricPoint mA,
            MetricPoint mB,
            BuildingFootprintVectorIndex buildingIndex,
            double rowHalfWidthMeters) {
        return (int) buildingIndex.evaluateStepConflictsMetric(mA, mB, rowHalfWidthMeters, INSTITUTIONAL_BUFFER_METERS)
                .buildingHits()
                .stream()
                .filter(BuildingFootprintVectorIndex.BuildingHit::insideDirectRow)
                .count();
    }

    private static List<GeoPoint> deduplicateNearIdentical(
            List<GeoPoint> pts, CoordinateProjection projection, double minSpacingM) {
        List<GeoPoint> clean = new ArrayList<>(pts.size());
        clean.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            if (projection.distanceMeters(clean.get(clean.size() - 1), pts.get(i)) >= minSpacingM) {
                clean.add(pts.get(i));
            }
        }
        clean.add(pts.get(pts.size() - 1));
        return clean;
    }

    private static double dist(MetricPoint a, MetricPoint b) {
        return Math.hypot(b.x() - a.x(), b.y() - a.y());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
