package in.marg.eia;

import in.marg.model.GeoPoint;
import in.marg.transport.FacilityMode;
import java.util.List;
import java.util.Map;

/** Provider boundary between Mode 2 alignment screening and configured regional GIS pipelines. */
public interface Mode2GisMetricsProvider {
    Map<String, Object> summarize(String regionId, List<GeoPoint> route, FacilityMode facilityMode);
}
