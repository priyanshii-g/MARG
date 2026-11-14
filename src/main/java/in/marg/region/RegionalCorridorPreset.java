package in.marg.region;

import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata and file path descriptor for a Tier-2 Pre-Cached Benchmark Demonstration Corridor.
 *
 * <p>Holding this record in memory costs only a few hundred bytes of Strings and primitives;
 * the underlying 13-layer GeoTIFF rasters and GeoJSON spatial indices are lazily loaded
 * only when a routing request targets this region.
 */
public record RegionalCorridorPreset(
        String id,
        String code,
        String displayName,
        String stateAndGeography,
        String physiographicZone,
        String engineeringChallengeSummary,
        int utmZone,
        String epsgCode,
        StudyRegion studyRegion,
        String lulcCachePath,
        String slopeRasterPath,
        String protectedAreaRasterPath,
        String settlementRasterPath,
        String settlementCatalogPath,
        String transportRasterPath,
        String transportNetworkGeoJsonPath,
        String regionalHubsGeoJsonPath,
        String waterBodiesRasterPath,
        String perennialRiversGeoJsonPath,
        String wetlandsCatalogGeoJsonPath,
        String wildlifeRasterPath,
        String wildlifeCorridorsGeoJsonPath,
        String geotechRasterPath,
        String faultLandslideGeoJsonPath,
        String utilityRasterPath,
        String powerLinesGeoJsonPath,
        String utilityAssetsGeoJsonPath,
        String buildingDensityRasterPath,
        String settlementBoundariesGeoJsonPath,
        String buildingFootprintsGeoJsonPath,
        String populationDensityRasterPath,
        String demographicsGeoJsonPath,
        String drainageWetlandRasterPath,
        String wetlandsPolygonsGeoJsonPath,
        String drainageNetworkGeoJsonPath,
        String watershedBasinsGeoJsonPath
) {

    public boolean contains(GeoPoint point) {
        return studyRegion.contains(point);
    }

    public boolean containsBoth(GeoPoint start, GeoPoint goal) {
        return studyRegion.contains(start) && studyRegion.contains(goal);
    }

    public Map<String, Object> toSummaryMap(boolean loadedInMemory) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("code", code);
        map.put("tierMode", CorridorTierMode.TIER_2_PRECACHED_BENCHMARK.name());
        map.put("displayName", displayName);
        map.put("name", studyRegion.name());
        map.put("stateAndGeography", stateAndGeography);
        map.put("physiographicZone", physiographicZone);
        map.put("engineeringChallengeSummary", engineeringChallengeSummary);
        map.put("utmZone", utmZone);
        map.put("epsgCode", epsgCode);
        map.put("bbox", Map.of(
                "minLat", studyRegion.minLat(),
                "minLon", studyRegion.minLon(),
                "maxLat", studyRegion.maxLat(),
                "maxLon", studyRegion.maxLon()
        ));
        map.put("defaultOrigin", studyRegion.origin());
        map.put("defaultDestination", studyRegion.destination());
        map.put("arbitraryPointSelectionSupported", true);
        map.put("lazyLoadedInMemory", loadedInMemory);
        map.put("layersCount", 13);
        return map;
    }
}
