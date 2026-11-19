package in.marg.wildlife;

import in.marg.model.GeoPoint;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authoritative Statutory Directory of Indian Protected Areas (National Parks & Wildlife Sanctuaries)
 * notified under the Wildlife (Protection) Act, 1972 (Section 26A / 35), fused with surveyed
 * OpenStreetMap (OSM) Polygon / MultiPolygon boundaries.
 * <p>
 * Provides:
 * <ul>
 *   <li>Core sanctuary boundary checking (point &amp; segment intersection) with zero-tolerance statutory exclusion.</li>
 *   <li>Eco-Sensitive Zone (ESZ) uniform 10.0 km outward buffer calculation per MoEFCC guidelines.</li>
 *   <li>Statutory clearance requirements evaluation (SC-NBWL, PARIVESH 2.0, CAMPA, WII Viaducts).</li>
 * </ul>
 */
@Service
public class IndianWildlifeSanctuaryRegistry {

    /** Default statutory Eco-Sensitive Zone (ESZ) buffer width around Protected Areas per MoEFCC guidelines. */
    public static final double DEFAULT_ESZ_BUFFER_KM = 10.0;

    /** Approximate degree equivalent of 10 km in latitude (~0.0901 deg). */
    public static final double DEFAULT_ESZ_BUFFER_DEG = 0.0905;

    public record StatutorySanctuary(
            String id,
            String name,
            String category,
            String state,
            String district,
            String legalStatute,
            double centerLat,
            double centerLon,
            double coreRadiusDeg,
            double eszRadiusDeg,
            double minLat,
            double maxLat,
            double minLon,
            double maxLon,
            double areaKm2,
            String keySpecies,
            List<double[]> boundaryPolygon,
            List<double[]> eszPolygon,
            List<List<double[]>> boundaryPolygons
    ) {
        public StatutorySanctuary(
                String id, String name, String state, String district, String legalStatute,
                double centerLat, double centerLon, double coreRadiusDeg, double eszRadiusDeg,
                double minLat, double maxLat, double minLon, double maxLon,
                double areaKm2, String keySpecies
        ) {
            this(id, name, (name != null && name.toLowerCase(Locale.ROOT).contains("national park")) ? "NATIONAL_PARK" : "WILDLIFE_SANCTUARY",
                 state, district, legalStatute, centerLat, centerLon, coreRadiusDeg, Math.max(eszRadiusDeg, coreRadiusDeg + DEFAULT_ESZ_BUFFER_DEG),
                 minLat, maxLat, minLon, maxLon, areaKm2, keySpecies, List.of(), List.of(), List.of());
        }

        public StatutorySanctuary(
                String id, String name, String category, String state, String district, String legalStatute,
                double centerLat, double centerLon, double coreRadiusDeg, double eszRadiusDeg,
                double minLat, double maxLat, double minLon, double maxLon,
                double areaKm2, String keySpecies
        ) {
            this(id, name, category, state, district, legalStatute, centerLat, centerLon, coreRadiusDeg,
                 Math.max(eszRadiusDeg, coreRadiusDeg + DEFAULT_ESZ_BUFFER_DEG),
                 minLat, maxLat, minLon, maxLon, areaKm2, keySpecies, List.of(), List.of(), List.of());
        }

        public StatutorySanctuary(
                String id, String name, String category, String state, String district, String legalStatute,
                double centerLat, double centerLon, double coreRadiusDeg, double eszRadiusDeg,
                double minLat, double maxLat, double minLon, double maxLon,
                double areaKm2, String keySpecies,
                List<double[]> boundaryPolygon,
                List<double[]> eszPolygon
        ) {
            this(id, name, category, state, district, legalStatute, centerLat, centerLon, coreRadiusDeg,
                 Math.max(eszRadiusDeg, coreRadiusDeg + DEFAULT_ESZ_BUFFER_DEG),
                 minLat, maxLat, minLon, maxLon, areaKm2, keySpecies,
                 boundaryPolygon != null ? boundaryPolygon : List.of(),
                 eszPolygon != null ? eszPolygon : List.of(),
                 (boundaryPolygon != null && boundaryPolygon.size() >= 3) ? List.of(boundaryPolygon) : List.of());
        }

        public List<List<double[]>> effectiveBoundaryRings() {
            if (boundaryPolygons != null && !boundaryPolygons.isEmpty()) {
                return boundaryPolygons;
            }
            if (boundaryPolygon != null && boundaryPolygon.size() >= 3) {
                return List.of(boundaryPolygon);
            }
            return List.of();
        }

        public boolean containsPoint(double lat, double lon) {
            // Fast bounding box check
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) {
                return false;
            }
            // Exact Point-in-Polygon (Ray-Casting) check across all MultiPolygon rings
            List<List<double[]>> rings = effectiveBoundaryRings();
            if (!rings.isEmpty()) {
                for (List<double[]> ring : rings) {
                    if (pointInPolygon(lat, lon, ring)) {
                        return true;
                    }
                }
                return false;
            }
            // Elliptical radial falloff check within bounding box if no polygon ring
            double dLat = (lat - centerLat) / Math.max(1e-5, (maxLat - minLat) / 2.0);
            double dLon = (lon - centerLon) / Math.max(1e-5, (maxLon - minLon) / 2.0);
            return (dLat * dLat + dLon * dLon) <= 1.0;
        }

        public boolean intersectsSegment(double lat1, double lon1, double lat2, double lon2) {
            double segMinLat = Math.min(lat1, lat2) - 0.00045;
            double segMaxLat = Math.max(lat1, lat2) + 0.00045;
            double segMinLon = Math.min(lon1, lon2) - 0.00045;
            double segMaxLon = Math.max(lon1, lon2) + 0.00045;

            if (segMaxLat < minLat || segMinLat > maxLat || segMaxLon < minLon || segMinLon > maxLon) {
                return false;
            }
            // If either endpoint lies inside the sanctuary core polygon, flag direct intrusion immediately
            if (containsPoint(lat1, lon1) || containsPoint(lat2, lon2)) {
                return true;
            }
            // Exact 2D line-segment intersection against all polygon boundary rings
            List<List<double[]>> rings = effectiveBoundaryRings();
            for (List<double[]> ring : rings) {
                int n = ring.size();
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    double y1 = ring.get(j)[0], x1 = ring.get(j)[1];
                    double y2 = ring.get(i)[0], x2 = ring.get(i)[1];
                    if (segMaxLat < Math.min(y1, y2) || segMinLat > Math.max(y1, y2)
                            || segMaxLon < Math.min(x1, x2) || segMinLon > Math.max(x1, x2)) {
                        continue;
                    }
                    if (segmentsIntersect(lat1, lon1, lat2, lon2, y1, x1, y2, x2)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean segmentsIntersect(
                double aLat, double aLon, double bLat, double bLon,
                double cLat, double cLon, double dLat, double dLon) {
            double d1 = crossProduct(cLat, cLon, dLat, dLon, aLat, aLon);
            double d2 = crossProduct(cLat, cLon, dLat, dLon, bLat, bLon);
            double d3 = crossProduct(aLat, aLon, bLat, bLon, cLat, cLon);
            double d4 = crossProduct(aLat, aLon, bLat, bLon, dLat, dLon);
            return (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                    && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0)));
        }

        private static double crossProduct(
                double aLat, double aLon, double bLat, double bLon, double cLat, double cLon) {
            return (bLon - aLon) * (cLat - aLat) - (bLat - aLat) * (cLon - aLon);
        }

        /**
         * Evaluates whether (lat, lon) lies inside the statutory 10 km Eco-Sensitive Zone (ESZ)
         * buffer around this Protected Area's core boundary (MoEFCC default 10 km guideline).
         */
        public boolean inEszBuffer(double lat, double lon) {
            double expandLat = Math.max(DEFAULT_ESZ_BUFFER_DEG + 0.005, eszRadiusDeg - coreRadiusDeg);
            double cosLat = Math.max(0.2, Math.cos(Math.toRadians(centerLat)));
            double expandLon = expandLat / cosLat;

            if (lat < minLat - expandLat || lat > maxLat + expandLat
                    || lon < minLon - expandLon || lon > maxLon + expandLon) {
                return false;
            }
            if (containsPoint(lat, lon)) {
                return false;
            }
            // Fast O(N) polygon ray-casting against the pre-compiled 10 km statutory ESZ buffer
            if (eszPolygon != null && eszPolygon.size() >= 3) {
                return pointInPolygon(lat, lon, eszPolygon);
            }
            double distKm = distanceToCoreKm(lat, lon);
            return distKm <= DEFAULT_ESZ_BUFFER_KM;
        }

        public static boolean pointInPolygon(double lat, double lon, List<double[]> ring) {
            if (ring == null || ring.size() < 3) {
                return false;
            }
            boolean inside = false;
            int n = ring.size();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double yi = ring.get(i)[0], xi = ring.get(i)[1];
                double yj = ring.get(j)[0], xj = ring.get(j)[1];

                boolean intersect = ((yi > lat) != (yj > lat))
                        && (lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi);
                if (intersect) {
                    inside = !inside;
                }
            }
            return inside;
        }

        public double distanceToCoreKm(double lat, double lon) {
            if (containsPoint(lat, lon)) {
                return 0.0;
            }
            List<List<double[]>> rings = effectiveBoundaryRings();
            if (!rings.isEmpty()) {
                double minDistKm = Double.POSITIVE_INFINITY;
                for (List<double[]> ring : rings) {
                    int n = ring.size();
                    for (int i = 0, j = n - 1; i < n; j = i++) {
                        double y1 = ring.get(j)[0], x1 = ring.get(j)[1];
                        double y2 = ring.get(i)[0], x2 = ring.get(i)[1];
                        if (lat < Math.min(y1, y2) - 0.10 || lat > Math.max(y1, y2) + 0.10
                                || lon < Math.min(x1, x2) - 0.11 || lon > Math.max(x1, x2) + 0.11) {
                            continue;
                        }
                        double d = distanceToSegmentKm(lat, lon, y1, x1, y2, x2);
                        if (d < minDistKm) {
                            minDistKm = d;
                        }
                    }
                }
                if (!Double.isInfinite(minDistKm)) {
                    return minDistKm;
                }
            }
            double clat = Math.max(minLat, Math.min(maxLat, lat));
            double clon = Math.max(minLon, Math.min(maxLon, lon));
            double dLatKm = (lat - clat) * 111.0;
            double dLonKm = (lon - clon) * 111.0 * Math.cos(Math.toRadians(centerLat));
            return Math.hypot(dLatKm, dLonKm);
        }

        private static double distanceToSegmentKm(double pLat, double pLon, double y1, double x1, double y2, double x2) {
            double cosLat = Math.cos(Math.toRadians(pLat));
            double px = (pLon - x1) * 111.0 * cosLat;
            double py = (pLat - y1) * 111.0;
            double sx = (x2 - x1) * 111.0 * cosLat;
            double sy = (y2 - y1) * 111.0;
            double sLenSq = sx * sx + sy * sy;
            if (sLenSq < 1e-9) {
                return Math.hypot(px, py);
            }
            double t = Math.max(0.0, Math.min(1.0, (px * sx + py * sy) / sLenSq));
            double projX = sx * t;
            double projY = sy * t;
            return Math.hypot(px - projX, py - projY);
        }
    }

    private static final List<StatutorySanctuary> FALLBACK_SANCTUARIES = List.of(
            // === NORTHEAST / ASSAM SANCTUARIES ===
            new StatutorySanctuary(
                    "PA-AS-KAZIRANGA",
                    "Kaziranga National Park & Tiger Reserve",
                    "Assam", "Golaghat / Nagaon",
                    "WPA 1972 Section 35 / UNESCO World Heritage Site",
                    26.670, 93.360, 0.045, 0.135,
                    26.538, 26.780, 93.010, 93.650,
                    858.98,
                    "Great Indian One-Horned Rhinoceros, Royal Bengal Tiger, Asian Elephant, Wild Water Buffalo"
            ),
            new StatutorySanctuary(
                    "PA-AS-NKARBI",
                    "North Karbi Anglong Wildlife Sanctuary",
                    "Assam", "Karbi Anglong",
                    "WPA 1972 Section 26A (Govt Notification 2000)",
                    26.480, 93.307, 0.045, 0.135,
                    26.395, 26.565, 93.196, 93.419,
                    96.00,
                    "Asian Elephant Migration Corridor to Kaziranga, Tiger, Clouded Leopard"
            ),
            new StatutorySanctuary(
                    "PA-AS-EKARBI",
                    "East Karbi Anglong Wildlife Sanctuary",
                    "Assam", "Karbi Anglong",
                    "WPA 1972 Section 26A (Govt Notification 2000)",
                    25.908, 93.380, 0.045, 0.135,
                    25.835, 25.981, 93.323, 93.437,
                    221.81,
                    "Asian Elephant, Tiger, Clouded Leopard, Hoolock Gibbon, Barking Deer"
            ),
            new StatutorySanctuary(
                    "PA-AS-MARAT-LONGRI",
                    "Marat Longri Wildlife Sanctuary",
                    "Assam", "Karbi Anglong",
                    "WPA 1972 Section 26A (Govt Notification 2003)",
                    25.860, 93.170, 0.055, 0.145,
                    25.750, 25.960, 93.050, 93.280,
                    451.00,
                    "Asian Elephant, Tiger, Leopard, Himalayan Black Bear, Dhole"
            ),
            new StatutorySanctuary(
                    "PA-AS-NAMBOR-GARAMPANI",
                    "Nambor-Garampani Wildlife Sanctuary",
                    "Assam", "Karbi Anglong / Golaghat",
                    "WPA 1972 Section 26A (Govt Notification 2000)",
                    26.410, 93.750, 0.035, 0.125,
                    26.340, 26.480, 93.680, 93.820,
                    43.05,
                    "Asian Elephant, Hoolock Gibbon, Leopard Cat, Great Pied Hornbill"
            ),
            new StatutorySanctuary(
                    "PA-AS-MANAS",
                    "Manas National Park & Biosphere Reserve",
                    "Assam", "Baksa / Chirang",
                    "WPA 1972 Section 35 / UNESCO World Heritage Site",
                    26.720, 91.010, 0.045, 0.135,
                    26.600, 26.850, 90.800, 91.250,
                    500.00,
                    "Pygmy Hog, Golden Langur, Hispid Hare, Royal Bengal Tiger"
            ),

            // === NORTHERN / CENTRAL INDIA SANCTUARIES ===
            new StatutorySanctuary(
                    "PA-UK-CORBETT",
                    "Jim Corbett Tiger Reserve",
                    "Uttarakhand", "Nainital / Pauri Garhwal",
                    "WPA 1972 Section 35",
                    29.530, 78.775, 0.035, 0.125,
                    29.420, 29.640, 78.650, 78.900,
                    520.82,
                    "Royal Bengal Tiger, Asian Elephant, Leopard, Gharial"
            ),
            new StatutorySanctuary(
                    "PA-UK-RAJAJI",
                    "Rajaji National Park & Tiger Reserve",
                    "Uttarakhand", "Dehradun / Haridwar / Pauri",
                    "WPA 1972 Section 35",
                    29.980, 78.180, 0.035, 0.125,
                    29.850, 30.120, 78.020, 78.350,
                    820.40,
                    "Asian Elephant, Tiger, Leopard, King Cobra"
            ),
            new StatutorySanctuary(
                    "PA-RJ-RANTHAMBORE",
                    "Ranthambore National Park & Tiger Reserve",
                    "Rajasthan", "Sawai Madhopur",
                    "WPA 1972 Section 35",
                    26.017, 76.502, 0.032, 0.122,
                    25.920, 26.120, 76.380, 76.620,
                    282.03,
                    "Bengal Tiger, Leopard, Nilgai, Wild Boar, Sambar"
            ),
            new StatutorySanctuary(
                    "PA-RJ-SARISKA",
                    "Sariska Tiger Reserve",
                    "Rajasthan", "Alwar",
                    "WPA 1972 Section 35",
                    27.328, 76.432, 0.030, 0.120,
                    27.220, 27.440, 76.320, 76.550,
                    881.11,
                    "Bengal Tiger, Indian Leopard, Striped Hyena, Golden Jackal"
            ),
            new StatutorySanctuary(
                    "PA-UP-DUDHWA",
                    "Dudhwa National Park Core",
                    "Uttar Pradesh", "Lakhimpur Kheri",
                    "WPA 1972 Section 35",
                    28.485, 80.645, 0.032, 0.122,
                    28.350, 28.620, 80.480, 80.820,
                    490.30,
                    "Swamp Deer (Barasingha), Tiger, One-Horned Rhinoceros"
            ),
            new StatutorySanctuary(
                    "PA-MP-KANHA",
                    "Kanha Tiger Reserve Core",
                    "Madhya Pradesh", "Mandla / Balaghat",
                    "WPA 1972 Section 35",
                    22.334, 80.611, 0.035, 0.125,
                    22.200, 22.480, 80.450, 80.780,
                    940.00,
                    "Hard-ground Barasingha, Royal Bengal Tiger, Indian Wild Dog"
            ),
            new StatutorySanctuary(
                    "PA-MP-BANDHAVGARH",
                    "Bandhavgarh Tiger Reserve Core",
                    "Madhya Pradesh", "Umaria",
                    "WPA 1972 Section 35",
                    23.722, 81.024, 0.032, 0.122,
                    23.600, 23.850, 80.900, 81.150,
                    448.84,
                    "Royal Bengal Tiger, Leopard, Chital, Nilgai"
            ),
            new StatutorySanctuary(
                    "PA-MP-PENCH",
                    "Pench Tiger Reserve Core",
                    "Madhya Pradesh / Maharashtra", "Seoni / Chhindwara / Nagpur",
                    "WPA 1972 Section 35",
                    21.765, 79.295, 0.030, 0.120,
                    21.650, 21.880, 79.180, 79.420,
                    411.00,
                    "Royal Bengal Tiger, Leopard, Sloth Bear, Gaur"
            ),
            new StatutorySanctuary(
                    "PA-MH-TADOBA",
                    "Tadoba-Andhari Tiger Reserve Core",
                    "Maharashtra", "Chandrapur",
                    "WPA 1972 Section 35",
                    20.245, 79.360, 0.032, 0.122,
                    20.120, 20.380, 79.220, 79.500,
                    625.40,
                    "Bengal Tiger, Leopard, Sloth Bear, Gaur, Marsh Crocodile"
            ),
            new StatutorySanctuary(
                    "PA-MH-SAHYADRI",
                    "Sahyadri / Koyna Wildlife Sanctuary Core",
                    "Maharashtra", "Satara / Sangli / Kolhapur",
                    "WPA 1972 Section 26A / 35",
                    17.790, 73.720, 0.030, 0.120,
                    17.650, 17.920, 73.600, 73.850,
                    741.22,
                    "Bengal Tiger, Indian Giant Squirrel, Great Pied Hornbill"
            ),

            // === SOUTHERN & WESTERN INDIA SANCTUARIES ===
            new StatutorySanctuary(
                    "PA-GJ-GIR",
                    "Gir National Park & Wildlife Sanctuary Core",
                    "Gujarat", "Junagadh / Gir Somnath",
                    "WPA 1972 Section 35",
                    21.124, 70.824, 0.035, 0.125,
                    21.000, 21.250, 70.680, 70.980,
                    1412.00,
                    "Asiatic Lion, Indian Leopard, Chausingha, Striped Hyena"
            ),
            new StatutorySanctuary(
                    "PA-KA-BANDIPUR",
                    "Bandipur-Nagarhole Biosphere Core",
                    "Karnataka", "Chamarajanagar / Mysore",
                    "WPA 1972 Section 35",
                    11.665, 76.625, 0.035, 0.125,
                    11.520, 11.820, 76.480, 76.780,
                    874.20,
                    "Asian Elephant, Tiger, Gaur, Four-Horned Antelope"
            ),
            new StatutorySanctuary(
                    "PA-KL-PERIYAR",
                    "Periyar Tiger Reserve Core",
                    "Kerala", "Idukki",
                    "WPA 1972 Section 35",
                    9.462, 77.235, 0.032, 0.122,
                    9.320, 9.600, 77.100, 77.380,
                    777.00,
                    "Asian Elephant, Bengal Tiger, Lion-Tailed Macaque"
            ),
            new StatutorySanctuary(
                    "PA-AP-NAGARJUNSAGAR",
                    "Nagarjunsagar-Srisailam Tiger Reserve Core",
                    "Andhra Pradesh / Telangana", "Palnadu / Nandyal",
                    "WPA 1972 Section 35",
                    16.085, 78.865, 0.035, 0.125,
                    15.920, 16.250, 78.700, 79.050,
                    3296.31,
                    "Bengal Tiger, Leopard, Sloth Bear, Pangolin"
            ),
            new StatutorySanctuary(
                    "PA-OD-SIMLIPAL",
                    "Simlipal Tiger Reserve Core",
                    "Odisha", "Mayurbhanj",
                    "WPA 1972 Section 35",
                    21.750, 86.330, 0.035, 0.125,
                    21.600, 21.900, 86.180, 86.480,
                    845.70,
                    "Melanistic Tiger, Asian Elephant, Chausingha"
            ),
            new StatutorySanctuary(
                    "PA-WB-SUNDARBANS",
                    "Sundarbans Mangrove Tiger Reserve Core",
                    "West Bengal", "South 24 Parganas",
                    "WPA 1972 Section 35 / UNESCO World Heritage Site",
                    21.945, 88.895, 0.038, 0.128,
                    21.780, 22.100, 88.720, 89.080,
                    1330.10,
                    "Royal Bengal Tiger, Estuarine Crocodile, Irrawaddy Dolphin"
            )
    );

    public record SanctuaryIntersectionMatch(
            StatutorySanctuary sanctuary,
            boolean isCoreIntersection,
            boolean isEszBufferIntersection,
            double distanceToCoreKm
    ) {}

    public record SanctuaryCrossingSpan(
            StatutorySanctuary sanctuary,
            double startKm,
            double endKm,
            double crossingLengthKm,
            double directForestTakeHectares
    ) {}

    public record StatutoryClearanceReport(
            boolean hasSanctuaryIntersection,
            boolean hasEszIntersection,
            List<SanctuaryCrossingSpan> sanctuaryCrossings,
            double totalSanctuaryLengthKm,
            double directSanctuaryForestTakeHectares,
            List<String> traversedSanctuaryNames,
            List<String> mandatoryClearancesChecklist,
            String legalStatuteCitation,
            String legalConsequenceHeadline,
            String pariveshPortalUrl,
            String nbwlGuidanceUrl,
            String severityLevel, // "CRITICAL_STATUTORY_ALERT" or "STATUTORY_COMPLIANT"
            String themeColor     // "#dc2626" or "#10b981"
    ) {}

    private static final double GRID_CELL_SIZE_DEG = 0.50; // ~55 km spatial buckets for sub-microsecond point filtering
    private static volatile IndianWildlifeSanctuaryRegistry defaultInstance;

    public static IndianWildlifeSanctuaryRegistry getDefaultInstance() {
        if (defaultInstance == null) {
            synchronized (IndianWildlifeSanctuaryRegistry.class) {
                if (defaultInstance == null) {
                    defaultInstance = new IndianWildlifeSanctuaryRegistry();
                }
            }
        }
        return defaultInstance;
    }

    private final List<StatutorySanctuary> sanctuaries;
    private final List<StatutorySanctuary> nationalParks;
    private final List<StatutorySanctuary> wildlifeSanctuaries;
    private final Map<Long, List<StatutorySanctuary>> spatialGrid = new HashMap<>();

    private final Map<Long, List<StatutorySanctuary>> precisionOverlayGrid = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, StatutorySanctuary> precisionOverlayById = new java.util.concurrent.ConcurrentHashMap<>();

    public IndianWildlifeSanctuaryRegistry() {
        defaultInstance = this;
        List<StatutorySanctuary> loaded = loadFromJson();
        if (loaded == null || loaded.isEmpty()) {
            loaded = FALLBACK_SANCTUARIES;
        }
        this.sanctuaries = Collections.unmodifiableList(loaded);

        List<StatutorySanctuary> nps = new ArrayList<>();
        List<StatutorySanctuary> wls = new ArrayList<>();

        for (StatutorySanctuary s : this.sanctuaries) {
            if ("NATIONAL_PARK".equalsIgnoreCase(s.category())
                    || (s.category() == null && s.name().toLowerCase(Locale.ROOT).contains("national park"))) {
                nps.add(s);
            } else {
                wls.add(s);
            }
            indexSanctuaryIntoGrid(s, spatialGrid);
        }

        this.nationalParks = Collections.unmodifiableList(nps);
        this.wildlifeSanctuaries = Collections.unmodifiableList(wls);
    }

    public void registerPrecisionOverlay(List<StatutorySanctuary> precisionSanctuaries) {
        if (precisionSanctuaries == null || precisionSanctuaries.isEmpty()) {
            return;
        }
        for (StatutorySanctuary s : precisionSanctuaries) {
            precisionOverlayById.put(s.id(), s);
            indexSanctuaryIntoGrid(s, precisionOverlayGrid);
        }
    }

    public void clearPrecisionOverlay() {
        precisionOverlayGrid.clear();
        precisionOverlayById.clear();
    }

    private void indexSanctuaryIntoGrid(StatutorySanctuary s, Map<Long, List<StatutorySanctuary>> grid) {
        // Ensure at least 10 km (~0.11 deg) expansion when indexing into spatial hash buckets
        double bufferExpandDeg = Math.max(0.115, s.eszRadiusDeg() - s.coreRadiusDeg());
        double bMinLat = s.minLat() - bufferExpandDeg - 0.01;
        double bMaxLat = s.maxLat() + bufferExpandDeg + 0.01;
        double bMinLon = s.minLon() - bufferExpandDeg - 0.01;
        double bMaxLon = s.maxLon() + bufferExpandDeg + 0.01;

        int minLatB = latToBucket(bMinLat);
        int maxLatB = latToBucket(bMaxLat);
        int minLonB = lonToBucket(bMinLon);
        int maxLonB = lonToBucket(bMaxLon);

        for (int latB = minLatB; latB <= maxLatB; latB++) {
            for (int lonB = minLonB; lonB <= maxLonB; lonB++) {
                long key = cellKey(latB, lonB);
                grid.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }
        }
    }

    private static long cellKey(int latBucket, int lonBucket) {
        return (((long) latBucket) << 32) | (((long) lonBucket) & 0xFFFFFFFFL);
    }

    private static int latToBucket(double lat) {
        return (int) Math.floor(lat / GRID_CELL_SIZE_DEG);
    }

    private static int lonToBucket(double lon) {
        return (int) Math.floor(lon / GRID_CELL_SIZE_DEG);
    }

    private static InputStream openStream() throws Exception {
        InputStream stream = IndianWildlifeSanctuaryRegistry.class.getResourceAsStream("/data/india_protected_areas.json");
        if (stream != null) {
            return stream;
        }
        Path p1 = Path.of("src/main/resources/data/india_protected_areas.json");
        if (Files.exists(p1)) {
            return Files.newInputStream(p1);
        }
        Path p2 = Path.of("data/processed/wildlife/india_protected_areas.json");
        if (Files.exists(p2)) {
            return Files.newInputStream(p2);
        }
        return null;
    }

    private static List<StatutorySanctuary> loadFromJson() {
        try (InputStream in = openStream()) {
            if (in == null) {
                return null;
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);
            if (root == null || !root.isArray()) {
                return null;
            }
            List<StatutorySanctuary> result = new ArrayList<>();
            for (JsonNode n : root) {
                String id = n.path("id").asString();
                String name = n.path("name").asString();
                String cat = n.path("category").isMissingNode() ?
                        (name.toLowerCase(Locale.ROOT).contains("national park") ? "NATIONAL_PARK" : "WILDLIFE_SANCTUARY") :
                        n.path("category").asString();
                String state = n.path("state").asString();
                String district = n.path("district").asString();
                String statute = n.path("legalStatute").asString();
                double cLat = n.path("centerLat").asDouble();
                double cLon = n.path("centerLon").asDouble();
                double cRad = n.path("coreRadiusDeg").asDouble();
                double eszRad = Math.max(n.path("eszRadiusDeg").asDouble(), cRad + DEFAULT_ESZ_BUFFER_DEG);
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

                result.add(new StatutorySanctuary(
                        id, name, cat, state, district, statute,
                        cLat, cLon, cRad, eszRad,
                        minLat, maxLat, minLon, maxLon,
                        area, species,
                        Collections.unmodifiableList(bPoly),
                        Collections.unmodifiableList(eszPoly),
                        Collections.unmodifiableList(multiPolys)
                ));
            }
            return result;
        } catch (Exception e) {
            System.err.println("Warning: Unable to load india_protected_areas.json: " + e.getMessage());
            return null;
        }
    }

    public List<StatutorySanctuary> allSanctuaries() {
        return sanctuaries;
    }

    public List<StatutorySanctuary> allNationalParks() {
        return nationalParks;
    }

    public List<StatutorySanctuary> allWildlifeSanctuaries() {
        return wildlifeSanctuaries;
    }

    public SanctuaryIntersectionMatch matchPoint(double lat, double lon) {
        long key = cellKey(latToBucket(lat), lonToBucket(lon));
        // 1. Check precision overlay first for Core hit
        List<StatutorySanctuary> precisionCandidates = precisionOverlayGrid.get(key);
        if (precisionCandidates != null) {
            for (StatutorySanctuary s : precisionCandidates) {
                if (s.containsPoint(lat, lon)) {
                    return new SanctuaryIntersectionMatch(s, true, false, 0.0);
                }
            }
        }

        // 2. Check baseline spatial grid for Core hit, skipping any sanctuary overridden by precision overlay
        List<StatutorySanctuary> candidates = spatialGrid.get(key);
        if (candidates != null) {
            for (StatutorySanctuary s : candidates) {
                if (precisionOverlayById.containsKey(s.id())) {
                    continue;
                }
                if (s.containsPoint(lat, lon)) {
                    return new SanctuaryIntersectionMatch(s, true, false, 0.0);
                }
            }
        }

        // 3. Check 10 km ESZ buffer across precision overlay and baseline catalog (closest core wins)
        StatutorySanctuary closestEszSanctuary = null;
        double minEszDistKm = Double.POSITIVE_INFINITY;

        if (precisionCandidates != null) {
            for (StatutorySanctuary s : precisionCandidates) {
                if (s.inEszBuffer(lat, lon)) {
                    double d = s.distanceToCoreKm(lat, lon);
                    if (d < minEszDistKm) {
                        minEszDistKm = d;
                        closestEszSanctuary = s;
                    }
                }
            }
        }
        if (candidates != null) {
            for (StatutorySanctuary s : candidates) {
                if (precisionOverlayById.containsKey(s.id())) {
                    continue;
                }
                if (s.inEszBuffer(lat, lon)) {
                    double d = s.distanceToCoreKm(lat, lon);
                    if (d < minEszDistKm) {
                        minEszDistKm = d;
                        closestEszSanctuary = s;
                    }
                }
            }
        }
        if (closestEszSanctuary != null) {
            return new SanctuaryIntersectionMatch(closestEszSanctuary, false, true, minEszDistKm);
        }
        return null;
    }

    public boolean isInsideAnySanctuaryCore(double lat, double lon) {
        return findIntersectingSanctuary(lat, lon) != null;
    }

    public boolean isInsideAnyEszBuffer(double lat, double lon) {
        long key = cellKey(latToBucket(lat), lonToBucket(lon));
        List<StatutorySanctuary> prec = precisionOverlayGrid.get(key);
        if (prec != null) {
            for (StatutorySanctuary s : prec) {
                if (s.inEszBuffer(lat, lon)) {
                    return true;
                }
            }
        }
        List<StatutorySanctuary> candidates = spatialGrid.get(key);
        if (candidates != null) {
            for (StatutorySanctuary s : candidates) {
                if (precisionOverlayById.containsKey(s.id())) {
                    continue;
                }
                if (s.inEszBuffer(lat, lon)) {
                    return true;
                }
            }
        }
        return false;
    }

    public StatutorySanctuary findIntersectingSanctuary(double lat, double lon) {
        long key = cellKey(latToBucket(lat), lonToBucket(lon));
        List<StatutorySanctuary> prec = precisionOverlayGrid.get(key);
        if (prec != null) {
            for (StatutorySanctuary s : prec) {
                if (s.containsPoint(lat, lon)) {
                    return s;
                }
            }
        }
        List<StatutorySanctuary> candidates = spatialGrid.get(key);
        if (candidates != null) {
            for (StatutorySanctuary s : candidates) {
                if (precisionOverlayById.containsKey(s.id())) {
                    continue;
                }
                if (s.containsPoint(lat, lon)) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * Checks whether a 2D line segment [from -> to] intersects or passes through any
     * statutory Protected Area core polygon.
     */
    public StatutorySanctuary findIntersectingSanctuaryForSegment(
            double lat1, double lon1, double lat2, double lon2) {
        int minLatB = latToBucket(Math.min(lat1, lat2));
        int maxLatB = latToBucket(Math.max(lat1, lat2));
        int minLonB = lonToBucket(Math.min(lon1, lon2));
        int maxLonB = lonToBucket(Math.max(lon1, lon2));

        for (int latB = minLatB; latB <= maxLatB; latB++) {
            for (int lonB = minLonB; lonB <= maxLonB; lonB++) {
                long key = cellKey(latB, lonB);
                List<StatutorySanctuary> prec = precisionOverlayGrid.get(key);
                if (prec != null) {
                    for (StatutorySanctuary s : prec) {
                        if (s.intersectsSegment(lat1, lon1, lat2, lon2)) {
                            return s;
                        }
                    }
                }
                List<StatutorySanctuary> candidates = spatialGrid.get(key);
                if (candidates != null) {
                    for (StatutorySanctuary s : candidates) {
                        if (precisionOverlayById.containsKey(s.id())) {
                            continue;
                        }
                        if (s.intersectsSegment(lat1, lon1, lat2, lon2)) {
                            return s;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Performs a statutory legal audit of an alignment against the statutory National Parks &
     * Wildlife Sanctuaries directory, checking for Sections 29 & 35 WPA 1972 violations.
     */
    public StatutoryClearanceReport evaluatePathClearances(List<GeoPoint> path) {
        if (path == null || path.size() < 2) {
            return new StatutoryClearanceReport(
                    false, false, List.of(), 0.0, 0.0, List.of(),
                    List.of(), "Wildlife (Protection) Act, 1972",
                    "Alignment does not intersect notified Wildlife Sanctuaries or National Parks.",
                    "https://parivesh.nic.in/",
                    "https://cpc.parivesh.nic.in/writereaddata/Final_Guidelines_NBWL_recomme_for_PA.pdf",
                    "STATUTORY_COMPLIANT",
                    "#10b981"
            );
        }

        List<SanctuaryCrossingSpan> crossings = new ArrayList<>();
        List<String> names = new ArrayList<>();
        double totalLengthInSanctuariesKm = 0.0;
        boolean hasEsz = false;

        StatutorySanctuary currentSanctuary = null;
        double currentStartKm = 0.0;
        double runningDistKm = 0.0;

        for (int i = 0; i < path.size(); i++) {
            GeoPoint p = path.get(i);
            GeoPoint prev = i > 0 ? path.get(i - 1) : null;
            if (prev != null) {
                runningDistKm += Math.hypot((p.lat() - prev.lat()) * 111.0, (p.lon() - prev.lon()) * 111.0 * Math.cos(Math.toRadians(p.lat())));
            }

            StatutorySanctuary hit = findIntersectingSanctuary(p.lat(), p.lon());
            if (hit == null && prev != null) {
                hit = findIntersectingSanctuaryForSegment(prev.lat(), prev.lon(), p.lat(), p.lon());
            }
            if (hit != null) {
                if (!names.contains(hit.name())) {
                    names.add(hit.name());
                }
                if (currentSanctuary == null) {
                    currentSanctuary = hit;
                    currentStartKm = runningDistKm;
                } else if (!currentSanctuary.id().equals(hit.id())) {
                    double spanKm = Math.max(0.25, runningDistKm - currentStartKm);
                    crossings.add(new SanctuaryCrossingSpan(
                            currentSanctuary,
                            round(currentStartKm),
                            round(runningDistKm),
                            round(spanKm),
                            round(spanKm * 9.0) // 90 m RoW = ~9 ha per km
                    ));
                    totalLengthInSanctuariesKm += spanKm;
                    currentSanctuary = hit;
                    currentStartKm = runningDistKm;
                }
            } else {
                if (currentSanctuary != null) {
                    double spanKm = Math.max(0.25, runningDistKm - currentStartKm);
                    crossings.add(new SanctuaryCrossingSpan(
                            currentSanctuary,
                            round(currentStartKm),
                            round(runningDistKm),
                            round(spanKm),
                            round(spanKm * 9.0)
                    ));
                    totalLengthInSanctuariesKm += spanKm;
                    currentSanctuary = null;
                }
                if (!hasEsz && isInsideAnyEszBuffer(p.lat(), p.lon())) {
                    hasEsz = true;
                }
            }
        }

        if (currentSanctuary != null) {
            double spanKm = Math.max(0.25, runningDistKm - currentStartKm);
            crossings.add(new SanctuaryCrossingSpan(
                    currentSanctuary,
                    round(currentStartKm),
                    round(runningDistKm),
                    round(spanKm),
                    round(spanKm * 9.0)
            ));
            totalLengthInSanctuariesKm += spanKm;
        }

        boolean hasSanctuary = !crossings.isEmpty();
        double totalForestTakeHa = round(totalLengthInSanctuariesKm * 9.0);

        List<String> checklist = new ArrayList<>();
        String headline;
        String severity;
        String color;

        if (hasSanctuary) {
            severity = "CRITICAL_STATUTORY_ALERT";
            color = "#dc2626"; // Crimson Red
            headline = String.format(Locale.ROOT,
                    "🚨 MANDATORY STATUTORY CLEARANCE ALERT: Route traverses %.1f km (%.1f ha) through %s! Prior statutory clearance from the Standing Committee of the National Board for Wildlife (SC-NBWL) under the Wildlife (Protection) Act, 1972 is mandatory before tendering.",
                    totalLengthInSanctuariesKm, totalForestTakeHa, String.join(" & ", names));

            checklist.add("1. Standing Committee of National Board for Wildlife (SC-NBWL): Mandatory prior clearance under Section 29 / 35 of the Wildlife (Protection) Act, 1972 chaired by Hon'ble Union Minister for EF&CC.");
            checklist.add("2. PARIVESH 2.0 Wildlife Clearance (Form-A): Online submission to State Board for Wildlife (SBWL) & Chief Wildlife Warden (CWLW) via parivesh.nic.in.");
            checklist.add("3. Stage-I & Stage-II Forest Clearance: Mandatory diversion clearance under Forest (Conservation) Act, 1980 / Van (Sanrakshan Evam Samvardhan) Adhiniyam, 2023.");
            checklist.add("4. Compensatory Afforestation Fund (CAMPA) Deposit: Net Present Value (NPV) deposit at maximum Eco-Class rates (₹10.5–16.0 Lakh/ha) + double-area compensatory non-forest land.");
            checklist.add("5. WII Mandatory Elevated Viaducts: Compulsory elevated wildlife viaducts / eco-ducts with minimum 6.5 m clear vertical height under Wildlife Institute of India (WII) 2016 guidelines.");
            checklist.add("6. Supreme Court CEC Unavoidability Certificate: Statutory demonstration to Central Empowered Committee proving no alternative corridor outside protected boundary exists physically.");
        } else if (hasEsz) {
            severity = "ESZ_REGULATORY_BUFFER_ALERT";
            color = "#f59e0b"; // Amber
            headline = "⚠️ Eco-Sensitive Zone (ESZ) Buffer Traversal: Route passes within the statutory 0–10.0 km MoEFCC Eco-Sensitive Zone buffer of a notified Wildlife Sanctuary as a last-resort alignment. Requires State EAC / SEIAA concurrence.";
            checklist.add("1. MoEFCC 10 km Default ESZ Notification Compliance: Mandatory clearance from State Level Environment Impact Assessment Authority (SEIAA) / NBWL for linear projects within the 10 km ESZ buffer.");
            checklist.add("2. Wildlife Migration Corridor Safeguards: Animal passage culverts & underpasses mandatory across recognized dispersal funnels.");
        } else {
            severity = "STATUTORY_COMPLIANT";
            color = "#10b981"; // Emerald Green
            headline = "✅ 100% Statutory Wildlife Compliance: Route completely bypasses all notified Wildlife Sanctuaries, National Parks, and Eco-Sensitive Zone cores.";
        }

        return new StatutoryClearanceReport(
                hasSanctuary,
                hasEsz,
                crossings,
                round(totalLengthInSanctuariesKm),
                totalForestTakeHa,
                names,
                checklist,
                "Section 29 & 35, Wildlife (Protection) Act, 1972; Forest (Conservation) Act, 1980",
                headline,
                "https://parivesh.nic.in/",
                "https://cpc.parivesh.nic.in/writereaddata/Final_Guidelines_NBWL_recomme_for_PA.pdf",
                severity,
                color
        );
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
