package in.marg.routing;

import in.marg.model.GeoPoint;

/** One spatial factor contributing to the greenfield alignment cost surface. */
@FunctionalInterface
public interface CostLayer {
    CostAssessment assess(GeoPoint point);

    /** Whether this layer also evaluates direction/intersection-dependent edge transitions. */
    default boolean hasEdgeCost() {
        return false;
    }

    /** Optional edge-level transition cost between two adjacent grid points (e.g., road/rail crossing angle). */
    default CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        return CostAssessment.FREE;
    }
}
