package in.marg.persistence;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Persisted Mode 1 route input and provenance for later EIA and review workflows. */
@Document(collection = "mode1_saved_routes")
public record SavedMode1Route(
        @Id String routeId,
        String projectName,
        String projectProponent,
        String studyArea,
        String projectState,
        String facilityMode,
        String regionId,
        String createdAt,
        String updatedAt,
        List<List<Double>> coordinates,
        Map<String, Object> routeSummary,
        Map<String, Object> routeMetadata,
        Map<String, Object> gisScreening) {

    public SavedMode1Route {
        coordinates = coordinates == null ? List.of() : coordinates.stream().map(List::copyOf).toList();
        routeSummary = routeSummary == null ? Map.of() : new LinkedHashMap<>(routeSummary);
        routeMetadata = routeMetadata == null ? Map.of() : new LinkedHashMap<>(routeMetadata);
        gisScreening = gisScreening == null ? Map.of() : new LinkedHashMap<>(gisScreening);
    }
}
