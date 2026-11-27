package in.marg.drainage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authoritative Pan-India Wetlands Registry (Ramsar Sites &amp; Major National Wetland Atlas
 * Inland Beels, Floodplain Jheels/Taals, Reservoir Backwaters, Lagoons, Estuaries &amp; High-Altitude Lakes).
 *
 * <p>Loaded from {@code /data/india_wetlands.json} on the classpath so both Tier-1 Pan-India dynamic
 * corridors and Tier-2 benchmark corridors have offline, zero-latency wetland awareness and GIS map polygons.
 */
public final class IndianWetlandRegistry {

    private static final String RESOURCE_PATH = "/data/india_wetlands.json";
    private static final double METERS_PER_DEG_LAT = 110_574.0;

    private static volatile IndianWetlandRegistry defaultInstance;

    private final List<MappedWetland> wetlands;

    public record MappedWetland(
            String id,
            String name,
            String state,
            String district,
            String basin,
            String wetlandCategory,
            double centerLat,
            double centerLon,
            double minLat,
            double maxLat,
            double minLon,
            double maxLon,
            double areaHectares,
            double equivalentRadiusMeters,
            boolean isRamsar,
            boolean isProtectedWetland,
            String legalStatute,
            List<double[]> polygonLonLat
    ) {
        public boolean containsPoint(double lat, double lon) {
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) {
                return false;
            }
            return pointInRing(lon, lat, polygonLonLat);
        }

        public double approximateDistanceMeters(double lat, double lon) {
            if (containsPoint(lat, lon)) {
                return 0.0;
            }
            double cosLat = Math.max(0.2, Math.cos(Math.toRadians(centerLat)));
            double metersPerDegLon = 111_320.0 * cosLat;
            double best = Double.POSITIVE_INFINITY;
            int n = polygonLonLat.size();
            for (int i = 0; i < n - 1; i++) {
                double[] a = polygonLonLat.get(i);
                double[] b = polygonLonLat.get(i + 1);
                double ax = (a[0] - lon) * metersPerDegLon;
                double ay = (a[1] - lat) * METERS_PER_DEG_LAT;
                double bx = (b[0] - lon) * metersPerDegLon;
                double by = (b[1] - lat) * METERS_PER_DEG_LAT;
                double d = distPointToSegOrigin(ax, ay, bx, by);
                if (d < best) {
                    best = d;
                }
            }
            return best;
        }
    }

    private IndianWetlandRegistry(List<MappedWetland> wetlands) {
        this.wetlands = Collections.unmodifiableList(wetlands);
    }

    public static IndianWetlandRegistry getDefaultInstance() {
        IndianWetlandRegistry inst = defaultInstance;
        if (inst == null) {
            synchronized (IndianWetlandRegistry.class) {
                inst = defaultInstance;
                if (inst == null) {
                    inst = loadFromClasspath();
                    defaultInstance = inst;
                }
            }
        }
        return inst;
    }

    private static IndianWetlandRegistry loadFromClasspath() {
        try (InputStream is = IndianWetlandRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                return new IndianWetlandRegistry(List.of());
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);
            JsonNode arr = root.get("wetlands");
            if (arr == null || !arr.isArray()) {
                return new IndianWetlandRegistry(List.of());
            }
            List<MappedWetland> loaded = new ArrayList<>(arr.size());
            for (JsonNode w : arr) {
                JsonNode polyNode = w.get("polygon");
                List<double[]> ring = new ArrayList<>();
                if (polyNode != null && polyNode.isArray()) {
                    for (JsonNode pt : polyNode) {
                        if (pt.isArray() && pt.size() >= 2) {
                            ring.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
                        }
                    }
                }
                if (ring.size() < 4) {
                    continue;
                }
                loaded.add(new MappedWetland(
                        w.path("id").asString("WET-IND"),
                        w.path("name").asString("Indian Wetland"),
                        w.path("state").asString("India"),
                        w.path("district").asString(""),
                        w.path("basin").asString(""),
                        w.path("wetlandCategory").asString("OXBOW_FLOODPLAIN_BEEL"),
                        w.path("centerLat").asDouble(),
                        w.path("centerLon").asDouble(),
                        w.path("minLat").asDouble(),
                        w.path("maxLat").asDouble(),
                        w.path("minLon").asDouble(),
                        w.path("maxLon").asDouble(),
                        w.path("areaHectares").asDouble(50.0),
                        w.path("equivalentRadiusMeters").asDouble(350.0),
                        w.path("isRamsar").asBoolean(false),
                        w.path("isProtectedWetland").asBoolean(false),
                        w.path("legalStatute").asString("Wetlands (Conservation & Management) Rules, 2017"),
                        Collections.unmodifiableList(ring)
                ));
            }
            return new IndianWetlandRegistry(loaded);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Pan-India wetlands registry from " + RESOURCE_PATH, e);
        }
    }

    public List<MappedWetland> allWetlands() {
        return wetlands;
    }

    public MappedWetland findContainingWetland(double lat, double lon) {
        for (MappedWetland w : wetlands) {
            if (w.containsPoint(lat, lon)) {
                return w;
            }
        }
        return null;
    }

    public MappedWetland findWetlandWithinBuffer(double lat, double lon, double bufferMeters) {
        double degPad = (bufferMeters / METERS_PER_DEG_LAT) + 0.004;
        MappedWetland nearest = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (MappedWetland w : wetlands) {
            if (lat < w.minLat() - degPad || lat > w.maxLat() + degPad
                    || lon < w.minLon() - degPad * 1.4 || lon > w.maxLon() + degPad * 1.4) {
                continue;
            }
            double d = w.approximateDistanceMeters(lat, lon);
            if (d <= bufferMeters && d < bestDist) {
                bestDist = d;
                nearest = w;
            }
        }
        return nearest;
    }

    public MappedWetland findIntersectingWetlandForSegment(double lat1, double lon1, double lat2, double lon2) {
        double sMinLat = Math.min(lat1, lat2);
        double sMaxLat = Math.max(lat1, lat2);
        double sMinLon = Math.min(lon1, lon2);
        double sMaxLon = Math.max(lon1, lon2);
        for (MappedWetland w : wetlands) {
            if (sMaxLat < w.minLat() || sMinLat > w.maxLat()
                    || sMaxLon < w.minLon() || sMinLon > w.maxLon()) {
                continue;
            }
            double[] fracs = {0.25, 0.50, 0.75};
            for (double f : fracs) {
                double lat = lat1 + f * (lat2 - lat1);
                double lon = lon1 + f * (lon2 - lon1);
                if (w.containsPoint(lat, lon)) {
                    return w;
                }
            }
        }
        return null;
    }

    public List<MappedWetland> findWetlandsInWindow(double minLat, double minLon, double maxLat, double maxLon) {
        List<MappedWetland> out = new ArrayList<>();
        for (MappedWetland w : wetlands) {
            if (w.maxLat() < minLat || w.minLat() > maxLat
                    || w.maxLon() < minLon || w.minLon() > maxLon) {
                continue;
            }
            out.add(w);
        }
        return out;
    }

    private static boolean pointInRing(double x, double y, List<double[]> ring) {
        boolean inside = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i)[0], yi = ring.get(i)[1];
            double xj = ring.get(j)[0], yj = ring.get(j)[1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / ((yj - yi) == 0.0 ? 1e-12 : (yj - yi)) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double distPointToSegOrigin(double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq <= 1e-9) {
            return Math.hypot(ax, ay);
        }
        double t = Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / lenSq));
        double px = ax + t * dx;
        double py = ay + t * dy;
        return Math.hypot(px, py);
    }
}
