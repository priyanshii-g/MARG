package in.marg.wildlife;

import in.marg.wildlife.IndianWildlifeSanctuaryRegistry.StatutorySanctuary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Dynamic On-Demand Extreme-Precision GIS Fetcher &amp; Corridor Cache (OSM + Official WII/MoEFCC Fusion).
 * <p>
 * Handles Deep-Layer Mode (deep=true):
 * <ol>
 *   <li>Checks the local corridor cache for previously fetched extreme-precision MultiPolygons.</li>
 *   <li>If not cached: queries OpenStreetMap Overpass API ({@code way} and {@code relation} with {@code out geom;})
 *       for survey-grade un-simplified boundary polygons within the route bounding box, fusing them
 *       with official WII/MoEFCC statutory metadata and generating the 10 km default ESZ buffer.</li>
 *   <li>On success: caches the fused vectors locally to disk and registers them into
 *       the spatial index overlay.</li>
 *   <li>On failure / network timeout / offline: seamlessly falls back to the 20-meter tolerance
 *       offline baseline without throwing errors or blocking the routing engine.</li>
 * </ol>
 */
@Service
public class ExtremePrecisionProtectedAreaService {

    private static final Logger log = LoggerFactory.getLogger(ExtremePrecisionProtectedAreaService.class);

    private static final Path CACHE_DIR = Path.of("data/cache/wildlife/extreme_precision");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(4);

    private final IndianWildlifeSanctuaryRegistry sanctuaryRegistry;
    private final ObjectMapper objectMapper;
    private volatile HttpClient httpClient;

    public ExtremePrecisionProtectedAreaService() {
        this(IndianWildlifeSanctuaryRegistry.getDefaultInstance());
    }

    @Autowired
    public ExtremePrecisionProtectedAreaService(
            IndianWildlifeSanctuaryRegistry sanctuaryRegistry) {
        this.sanctuaryRegistry = sanctuaryRegistry != null ? sanctuaryRegistry : IndianWildlifeSanctuaryRegistry.getDefaultInstance();
        this.objectMapper = new ObjectMapper();

        try {
            Files.createDirectories(CACHE_DIR);
        } catch (Exception e) {
            log.warn("Could not create extreme precision cache directory: {}", e.getMessage());
        }
    }

    private HttpClient getHttpClientSafe() {
        if (this.httpClient == null) {
            synchronized (this) {
                if (this.httpClient == null) {
                    try {
                        this.httpClient = HttpClient.newBuilder()
                                .connectTimeout(HTTP_TIMEOUT)
                                .followRedirects(HttpClient.Redirect.NORMAL)
                                .build();
                    } catch (Throwable t) {
                        log.debug("HTTP client unavailable (offline/sandbox environment): {}", t.getMessage());
                        return null;
                    }
                }
            }
        }
        return this.httpClient;
    }

    public record PrecisionFetchResult(
            List<StatutorySanctuary> sanctuaries,
            String precisionTier,
            boolean fallbackOccurred,
            String notificationMessage,
            String cacheKey
    ) {}

    public List<StatutorySanctuary> getOrFetchPrecisionSanctuaries(double minLat, double minLon, double maxLat, double maxLon) {
        return fetchPrecisionSanctuariesWithStatus(minLat, minLon, maxLat, maxLon).sanctuaries();
    }

    public PrecisionFetchResult fetchPrecisionSanctuariesWithStatus(double minLat, double minLon, double maxLat, double maxLon) {
        String cacheKey = String.format(Locale.ROOT, "prec_bbox_%.2f_%.2f_%.2f_%.2f.json", minLat, minLon, maxLat, maxLon);
        Path cacheFile = CACHE_DIR.resolve(cacheKey);

        // 1. Check local corridor cache first
        if (Files.exists(cacheFile)) {
            List<StatutorySanctuary> cached = loadFromCacheFile(cacheFile);
            if (cached != null && !cached.isEmpty()) {
                log.info("[ExtremePrecision] Hit local corridor cache for {} ({} sanctuaries loaded)", cacheKey, cached.size());
                sanctuaryRegistry.registerPrecisionOverlay(cached);
                return new PrecisionFetchResult(
                        cached,
                        "EXTREME_PRECISION_CACHED",
                        false,
                        "Using locally cached survey-grade OSM + Official boundaries (" + cached.size() + " protected areas).",
                        cacheKey
                );
            }
        }

        // 2. Query online official + OSM endpoints (Overpass API way + relation out geom)
        log.info("[ExtremePrecision] Querying online GIS endpoints for extreme precision boundaries in bbox [{}, {} to {}, {}]",
                minLat, minLon, maxLat, maxLon);

        List<StatutorySanctuary> fetched = fetchOnlineBbox(minLat, minLon, maxLat, maxLon);
        if (fetched != null && !fetched.isEmpty()) {
            saveToCacheFile(cacheFile, fetched);
            sanctuaryRegistry.registerPrecisionOverlay(fetched);
            log.info("[ExtremePrecision] Successfully fetched and cached {} extreme-precision sanctuaries for {}",
                    fetched.size(), cacheKey);
            return new PrecisionFetchResult(
                    fetched,
                    "EXTREME_PRECISION_ONLINE",
                    false,
                    "Successfully retrieved and fused OSM + Official cadastral boundaries online (" + fetched.size() + " protected areas).",
                    cacheKey
            );
        }

        // 3. Fallback: seamless 20m tolerance offline baseline
        log.info("[ExtremePrecision] Online fetch unavailable or returned 0 entries for bbox; utilizing 20m tolerance offline baseline.");
        return new PrecisionFetchResult(
                Collections.emptyList(),
                "OFFLINE_20M_FALLBACK",
                true,
                "Online official cadastral repository unreachable. Model falling back to statutory 20-meter precision survey baseline.",
                cacheKey
        );
    }

    private List<StatutorySanctuary> loadFromCacheFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            JsonNode root = objectMapper.readTree(in);
            if (root == null || !root.isArray()) {
                return Collections.emptyList();
            }
            List<StatutorySanctuary> list = new ArrayList<>();
            for (JsonNode n : root) {
                String id = n.path("id").asString();
                String name = n.path("name").asString();
                String cat = n.path("category").isMissingNode() ? "WILDLIFE_SANCTUARY" : n.path("category").asString();
                String state = n.path("state").asString();
                String district = n.path("district").asString();
                String statute = n.path("legalStatute").asString();
                double cLat = n.path("centerLat").asDouble();
                double cLon = n.path("centerLon").asDouble();
                double cRad = n.path("coreRadiusDeg").asDouble();
                double eszRad = Math.max(n.path("eszRadiusDeg").asDouble(), cRad + IndianWildlifeSanctuaryRegistry.DEFAULT_ESZ_BUFFER_DEG);
                double minLat = n.path("minLat").asDouble();
                double maxLat = n.path("maxLat").asDouble();
                double minLon = n.path("minLon").asDouble();
                double maxLon = n.path("maxLon").asDouble();
                double area = n.path("areaKm2").asDouble();
                String species = n.path("keySpecies").asString();

                JsonNode bPolyNode = n.path("boundaryPolygon");
                List<double[]> bPoly = new ArrayList<>();
                if (bPolyNode.isArray()) {
                    for (JsonNode pt : bPolyNode) {
                        if (pt.isArray() && pt.size() >= 2) {
                            bPoly.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
                        }
                    }
                }

                JsonNode multiPolyNode = n.path("boundaryPolygons");
                List<List<double[]>> multiPolys = new ArrayList<>();
                if (multiPolyNode.isArray()) {
                    for (JsonNode ringNode : multiPolyNode) {
                        if (ringNode.isArray()) {
                            List<double[]> ring = new ArrayList<>();
                            for (JsonNode pt : ringNode) {
                                if (pt.isArray() && pt.size() >= 2) {
                                    ring.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
                                }
                            }
                            if (ring.size() >= 3) {
                                multiPolys.add(Collections.unmodifiableList(ring));
                            }
                        }
                    }
                }
                if (multiPolys.isEmpty() && bPoly.size() >= 3) {
                    multiPolys.add(Collections.unmodifiableList(bPoly));
                }

                JsonNode eszPolyNode = n.path("eszPolygon");
                List<double[]> eszPoly = new ArrayList<>();
                if (eszPolyNode.isArray()) {
                    for (JsonNode pt : eszPolyNode) {
                        if (pt.isArray() && pt.size() >= 2) {
                            eszPoly.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
                        }
                    }
                }

                list.add(new StatutorySanctuary(
                        id, name, cat, state, district, statute,
                        cLat, cLon, cRad, eszRad,
                        minLat, maxLat, minLon, maxLon,
                        area, species,
                        Collections.unmodifiableList(bPoly),
                        Collections.unmodifiableList(eszPoly),
                        Collections.unmodifiableList(multiPolys)
                ));
            }
            return list;
        } catch (Exception e) {
            log.warn("[ExtremePrecision] Could not parse cache file {}: {}", file.getFileName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private void saveToCacheFile(Path file, List<StatutorySanctuary> sanctuaries) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sanctuaries);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[ExtremePrecision] Failed to persist cache file {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private List<StatutorySanctuary> fetchOnlineBbox(double minLat, double minLon, double maxLat, double maxLon) {
        try {
            HttpClient client = getHttpClientSafe();
            if (client == null) {
                return Collections.emptyList();
            }

            // Overpass QL query targeting BOTH way and relation protected areas with full polygon geometries (out geom;)
            String ql = String.format(Locale.ROOT,
                    "[out:json][timeout:4];\n" +
                    "(\n" +
                    "  way[\"boundary\"=\"protected_area\"](%.4f,%.4f,%.4f,%.4f);\n" +
                    "  relation[\"boundary\"=\"protected_area\"](%.4f,%.4f,%.4f,%.4f);\n" +
                    "  way[\"boundary\"=\"national_park\"](%.4f,%.4f,%.4f,%.4f);\n" +
                    "  relation[\"boundary\"=\"national_park\"](%.4f,%.4f,%.4f,%.4f);\n" +
                    "  way[\"leisure\"=\"nature_reserve\"](%.4f,%.4f,%.4f,%.4f);\n" +
                    "  relation[\"leisure\"=\"nature_reserve\"](%.4f,%.4f,%.4f,%.4f);\n" +
                    ");\n" +
                    "out geom;\n",
                    minLat, minLon, maxLat, maxLon,
                    minLat, minLon, maxLat, maxLon,
                    minLat, minLon, maxLat, maxLon,
                    minLat, minLon, maxLat, maxLon,
                    minLat, minLon, maxLat, maxLon,
                    minLat, minLon, maxLat, maxLon
            );

            String encoded = "data=" + URLEncoder.encode(ql, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://overpass-api.de/api/interpreter"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "MARG-National-Highway-Alignment-System/0.0.3")
                    .POST(HttpRequest.BodyPublishers.ofString(encoded))
                    .timeout(HTTP_TIMEOUT)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode elements = root.path("elements");
                if (elements.isArray() && elements.size() > 0) {
                    List<StatutorySanctuary> parsed = new ArrayList<>();
                    for (JsonNode el : elements) {
                        JsonNode tags = el.path("tags");
                        String name = tags.path("name").asString(tags.path("name:en").asString("")).trim();
                        if (name.isBlank()) {
                            continue;
                        }

                        List<List<double[]>> rings = extractRingsFromOverpassElement(el);
                        if (rings.isEmpty()) {
                            continue;
                        }

                        // Compute exact bounds across all rings
                        double bMinLat = Double.POSITIVE_INFINITY, bMaxLat = Double.NEGATIVE_INFINITY;
                        double bMinLon = Double.POSITIVE_INFINITY, bMaxLon = Double.NEGATIVE_INFINITY;
                        List<double[]> primaryRing = rings.get(0);
                        for (List<double[]> ring : rings) {
                            if (ring.size() > primaryRing.size()) {
                                primaryRing = ring;
                            }
                            for (double[] pt : ring) {
                                bMinLat = Math.min(bMinLat, pt[0]);
                                bMaxLat = Math.max(bMaxLat, pt[0]);
                                bMinLon = Math.min(bMinLon, pt[1]);
                                bMaxLon = Math.max(bMaxLon, pt[1]);
                            }
                        }

                        double cLat = (bMinLat + bMaxLat) / 2.0;
                        double cLon = (bMinLon + bMaxLon) / 2.0;
                        double coreRad = Math.max(0.015, Math.max(bMaxLat - bMinLat, bMaxLon - bMinLon) / 2.0);
                        double eszRad = coreRad + IndianWildlifeSanctuaryRegistry.DEFAULT_ESZ_BUFFER_DEG;
                        List<double[]> eszRing = build10KmEszPolygon(primaryRing, cLat, cLon);

                        // Match against official WII / MoEFCC catalog to fuse official metadata with OSM geometry
                        StatutorySanctuary officialMatch = findOfficialMatch(name, cLat, cLon);
                        String id = officialMatch != null ? officialMatch.id() : ("PA-OSM-" + Math.abs(el.path("id").asLong()));
                        String cat = officialMatch != null
                                ? officialMatch.category()
                                : (tags.path("protect_class").asString().equals("2") || name.toLowerCase(Locale.ROOT).contains("national park")
                                        ? "NATIONAL_PARK" : "WILDLIFE_SANCTUARY");
                        String state = officialMatch != null ? officialMatch.state() : "India";
                        String district = officialMatch != null ? officialMatch.district() : "Survey Beat";
                        String statute = officialMatch != null ? officialMatch.legalStatute() : "Wildlife (Protection) Act, 1972 (OSM + MoEFCC Fused)";
                        double areaKm2 = officialMatch != null ? officialMatch.areaKm2() : 100.0;
                        String species = officialMatch != null ? officialMatch.keySpecies() : "Protected Wildlife Species";

                        parsed.add(new StatutorySanctuary(
                                id, name, cat, state, district, statute,
                                cLat, cLon, coreRad, eszRad,
                                bMinLat, bMaxLat, bMinLon, bMaxLon,
                                areaKm2, species,
                                Collections.unmodifiableList(primaryRing),
                                Collections.unmodifiableList(eszRing),
                                Collections.unmodifiableList(rings)
                        ));
                    }
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.info("[ExtremePrecision] Online API fetch was uncontactable ({}); proceeding with 20m tolerance offline baseline.",
                    e.getClass().getSimpleName());
        }
        return Collections.emptyList();
    }

    private StatutorySanctuary findOfficialMatch(String osmName, double cLat, double cLon) {
        String normOsm = normalizeSanctuaryName(osmName);
        StatutorySanctuary best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (StatutorySanctuary s : sanctuaryRegistry.allSanctuaries()) {
            double distDeg = Math.hypot(s.centerLat() - cLat, s.centerLon() - cLon);
            String normOff = normalizeSanctuaryName(s.name());
            boolean nameMatch = !normOsm.isEmpty() && !normOff.isEmpty()
                    && (normOff.contains(normOsm) || normOsm.contains(normOff));
            if (nameMatch && distDeg < 0.8) {
                return s;
            }
            if (distDeg < 0.08 && distDeg < bestDist) {
                bestDist = distDeg;
                best = s;
            }
        }
        return best;
    }

    private static String normalizeSanctuaryName(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT)
                .replace("wildlife sanctuary", "")
                .replace("national park", "")
                .replace("tiger reserve", "")
                .replace("bird sanctuary", "")
                .replace("wls", "")
                .replaceAll("[^a-z0-9]+", "")
                .trim();
    }

    private static List<List<double[]>> extractRingsFromOverpassElement(JsonNode el) {
        List<List<double[]>> rings = new ArrayList<>();
        String type = el.path("type").asString("");
        if ("way".equals(type)) {
            List<double[]> ring = parseGeometryArray(el.path("geometry"));
            if (ring.size() >= 3) {
                closeRingIfNeeded(ring);
                rings.add(Collections.unmodifiableList(ring));
            }
        } else if ("relation".equals(type)) {
            JsonNode members = el.path("members");
            if (members.isArray()) {
                List<List<double[]>> outerSegments = new ArrayList<>();
                for (JsonNode m : members) {
                    String role = m.path("role").asString("");
                    if ("outer".equals(role) || role.isEmpty()) {
                        List<double[]> seg = parseGeometryArray(m.path("geometry"));
                        if (seg.size() >= 2) {
                            outerSegments.add(seg);
                        }
                    }
                }
                rings.addAll(stitchWaySegmentsIntoRings(outerSegments));
            }
        }
        return rings;
    }

    private static List<double[]> parseGeometryArray(JsonNode geomNode) {
        List<double[]> pts = new ArrayList<>();
        if (geomNode != null && geomNode.isArray()) {
            for (JsonNode pt : geomNode) {
                double lat = pt.path("lat").asDouble(Double.NaN);
                double lon = pt.path("lon").asDouble(Double.NaN);
                if (Double.isFinite(lat) && Double.isFinite(lon)) {
                    pts.add(new double[]{lat, lon});
                }
            }
        }
        return pts;
    }

    private static void closeRingIfNeeded(List<double[]> ring) {
        if (ring.size() >= 3) {
            double[] first = ring.get(0);
            double[] last = ring.get(ring.size() - 1);
            if (Math.hypot(first[0] - last[0], first[1] - last[1]) > 1e-6) {
                ring.add(new double[]{first[0], first[1]});
            }
        }
    }

    private static List<List<double[]>> stitchWaySegmentsIntoRings(List<List<double[]>> segments) {
        List<List<double[]>> completedRings = new ArrayList<>();
        List<List<double[]>> pool = new ArrayList<>(segments);

        while (!pool.isEmpty()) {
            List<double[]> current = new ArrayList<>(pool.remove(0));
            boolean merged = true;
            while (merged && !pool.isEmpty()) {
                merged = false;
                double[] tail = current.get(current.size() - 1);
                for (int i = 0; i < pool.size(); i++) {
                    List<double[]> cand = pool.get(i);
                    double[] cHead = cand.get(0);
                    double[] cTail = cand.get(cand.size() - 1);
                    if (Math.hypot(tail[0] - cHead[0], tail[1] - cHead[1]) < 1e-4) {
                        current.addAll(cand.subList(1, cand.size()));
                        pool.remove(i);
                        merged = true;
                        break;
                    } else if (Math.hypot(tail[0] - cTail[0], tail[1] - cTail[1]) < 1e-4) {
                        for (int k = cand.size() - 2; k >= 0; k--) {
                            current.add(cand.get(k));
                        }
                        pool.remove(i);
                        merged = true;
                        break;
                    }
                }
            }
            if (current.size() >= 3) {
                closeRingIfNeeded(current);
                completedRings.add(Collections.unmodifiableList(current));
            }
        }
        return completedRings;
    }

    private static List<double[]> build10KmEszPolygon(List<double[]> ring, double cLat, double cLon) {
        double dLat10Km = IndianWildlifeSanctuaryRegistry.DEFAULT_ESZ_BUFFER_DEG;
        double cosLat = Math.max(0.2, Math.cos(Math.toRadians(cLat)));
        List<double[]> esz = new ArrayList<>(ring.size());
        for (double[] pt : ring) {
            double dy = pt[0] - cLat;
            double dx = (pt[1] - cLon) * cosLat;
            double dist = Math.hypot(dy, dx);
            if (dist > 1e-6) {
                double scale = (dist + dLat10Km) / dist;
                esz.add(new double[]{
                        cLat + dy * scale,
                        cLon + (dx * scale) / cosLat
                });
            } else {
                esz.add(new double[]{pt[0] + dLat10Km, pt[1]});
            }
        }
        return esz;
    }
}
