package in.marg.eia;

import in.marg.model.GeoPoint;
import in.marg.region.RegionalCorridorRegistry;
import in.marg.transport.FacilityMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Adapts a selected registered corridor's existing deterministic route-metrics service for Mode 2. */
@Component
public class RegionalMode2GisMetricsProvider implements Mode2GisMetricsProvider {

    private final RegionalCorridorRegistry corridors;

    public RegionalMode2GisMetricsProvider(RegionalCorridorRegistry corridors) {
        this.corridors = corridors;
    }

    @Override
    public Map<String, Object> summarize(String regionId, List<GeoPoint> route, FacilityMode facilityMode) {
        var pipeline = corridors.getOrLoadPipeline(regionId);
        if (pipeline.routeMetricsService() == null) {
            throw new IllegalStateException("The selected GIS region has no route-metrics service.");
        }
        return pipeline.routeMetricsService().summarize(route, facilityMode);
    }
}
