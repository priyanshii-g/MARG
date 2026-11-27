package in.marg.routing;

import in.marg.model.GeoPoint;

/** Composite spatial cost surface used by the greenfield A* router. */
@FunctionalInterface
public interface CostSurface {
    CostAssessment assess(GeoPoint point);

    /** Optional transition assessment across step [from -> to] (for linear crossings & skew angles). */
    default CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        return CostAssessment.FREE;
    }

    /** Whether this cost surface assesses edge transitions between adjacent grid nodes. */
    default boolean hasEdgeCost() {
        return false;
    }
}
