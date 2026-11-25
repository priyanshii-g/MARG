package in.marg.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
public class SavedMode1RouteService {
    private static final int MAX_VERTICES = 20_000;
    private final MongoTemplate mongo;

    public SavedMode1RouteService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public SavedMode1Route save(SaveRouteRequest request) {
        if (request == null) throw new IllegalArgumentException("Route details are required.");
        String name = clean(request.projectName());
        if (name == null || name.length() > 200) {
            throw new IllegalArgumentException("Project name must contain 1 to 200 characters.");
        }
        List<List<Double>> coordinates = validateCoordinates(request.coordinates());
        String now = Instant.now().toString();
        SavedMode1Route route = new SavedMode1Route(
                "M1R-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT),
                name, clean(request.projectProponent()), clean(request.studyArea()), clean(request.projectState()),
                clean(request.facilityMode()), clean(request.regionId()), now, now, coordinates,
                request.routeSummary(), request.routeMetadata(), request.gisScreening());
        return mongo.save(route);
    }

    public List<SavedMode1Route> list() {
        return mongo.findAll(SavedMode1Route.class).stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).toList();
    }

    public SavedMode1Route find(String routeId) {
        SavedMode1Route route = mongo.findById(routeId, SavedMode1Route.class);
        if (route == null) throw new IllegalArgumentException("Saved route was not found.");
        return route;
    }

    public Map<String, Object> health() {
        Document response = mongo.getDb().runCommand(new Document("ping", 1));
        return Map.of("available", response.get("ok") instanceof Number ok && ok.doubleValue() == 1.0,
                "database", mongo.getDb().getName(), "collection", "mode1_saved_routes");
    }

    private List<List<Double>> validateCoordinates(List<List<Double>> input) {
        if (input == null || input.size() < 2 || input.size() > MAX_VERTICES) {
            throw new IllegalArgumentException("Route must contain 2 to " + MAX_VERTICES + " WGS84 coordinates.");
        }
        List<List<Double>> result = new ArrayList<>(input.size());
        boolean hasLength = false;
        double previousLon = Double.NaN;
        double previousLat = Double.NaN;
        for (List<Double> pair : input) {
            if (pair == null || pair.size() < 2 || pair.get(0) == null || pair.get(1) == null) {
                throw new IllegalArgumentException("Each route coordinate must be [longitude, latitude].");
            }
            double lon = pair.get(0);
            double lat = pair.get(1);
            if (!Double.isFinite(lon) || !Double.isFinite(lat) || lon < -180 || lon > 180 || lat < -90 || lat > 90) {
                throw new IllegalArgumentException("Route coordinates must be finite WGS84 longitude/latitude values.");
            }
            if (Double.isFinite(previousLon) && (Math.abs(previousLon - lon) > 1e-12 || Math.abs(previousLat - lat) > 1e-12)) hasLength = true;
            result.add(List.of(lon, lat));
            previousLon = lon;
            previousLat = lat;
        }
        if (!hasLength) throw new IllegalArgumentException("Route geometry must have non-zero length.");
        return List.copyOf(result);
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record SaveRouteRequest(String projectName, String projectProponent, String studyArea, String projectState,
                                   String facilityMode, String regionId, List<List<Double>> coordinates,
                                   Map<String, Object> routeSummary, Map<String, Object> routeMetadata,
                                   Map<String, Object> gisScreening) {}
}
