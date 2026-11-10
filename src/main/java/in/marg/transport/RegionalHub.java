package in.marg.transport;

import in.marg.model.GeoPoint;
import in.marg.spatial.MetricPoint;

/**
 * Regional city, market, or industrial hub together with its verified
 * existing bypass / ring-road tie-in junction outside the congested urban core.
 */
public record RegionalHub(
        String hubId,
        String name,
        String district,
        GeoPoint center,
        MetricPoint centerMetric,
        GeoPoint bypassTieIn,
        MetricPoint bypassTieInMetric,
        String tieInRoadRef,
        String tieInName,
        int urbanPopulation,
        int catchmentPopulation,
        double industrialOutputFactor
) {
}
