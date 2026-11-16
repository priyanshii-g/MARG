package in.marg.district;

import in.marg.model.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * High-performance In-Memory Catalog and Spatial Index for all 780+ official
 * Indian Administrative Districts across 28 States and 8 Union Territories.
 */
@Service
public class IndianDistrictsCatalogService {

    private static final Logger log = LoggerFactory.getLogger(IndianDistrictsCatalogService.class);

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final String configuredPath;

    private boolean initialized = false;
    private final List<DistrictRecord> allDistricts = new ArrayList<>();
    private final Map<String, DistrictRecord> idIndex = new HashMap<>();
    private final Map<String, DistrictRecord> tokenIndex = new HashMap<>();

    @Autowired
    public IndianDistrictsCatalogService(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${marg.districts.catalog-path:classpath:data/india_districts.json}")
            String configuredPath) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.configuredPath = configuredPath;
    }

    public synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        try {
            InputStream in = null;
            Resource resource = resourceLoader.getResource(configuredPath);
            if (resource.exists()) {
                in = resource.getInputStream();
            } else {
                File fallback = new File("data/processed/districts/india_districts.json");
                if (fallback.exists()) {
                    in = new java.io.FileInputStream(fallback);
                }
            }

            if (in == null) {
                log.warn("Indian districts catalog file not found at {} or fallback. Initializing empty.", configuredPath);
                initialized = true;
                return;
            }

            try (InputStream stream = in) {
                JsonNode root = objectMapper.readTree(stream);
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        String id = node.path("id").asString();
                        String name = node.path("name").asString();
                        String adminLevel = node.path("adminLevel").isMissingNode() ? "DISTRICT" : node.path("adminLevel").asString("DISTRICT");
                        String parentDistrict = node.path("parentDistrict").isMissingNode() ? name : node.path("parentDistrict").asString(name);
                        String state = node.path("state").asString();
                        String stateCode = node.path("stateCode").asString();
                        String hq = node.path("headquarters").asString();
                        double lat = node.path("lat").asDouble();
                        double lon = node.path("lon").asDouble();
                        int tier = node.path("tier").asInt(3);

                        List<String> aliases = new ArrayList<>();
                        JsonNode aNode = node.path("aliases");
                        if (aNode.isArray()) {
                            for (JsonNode alias : aNode) {
                                aliases.add(alias.asString().toLowerCase(Locale.ROOT));
                            }
                        }

                        DistrictRecord rec = new DistrictRecord(
                                id, name, adminLevel, parentDistrict, state, stateCode, hq, lat, lon, tier, aliases);
                        allDistricts.add(rec);
                        idIndex.put(id.toLowerCase(Locale.ROOT), rec);

                        String normName = normalizeToken(name);
                        tokenIndex.putIfAbsent(normName, rec);
                        String normHq = normalizeToken(hq);
                        tokenIndex.putIfAbsent(normHq, rec);

                        // Also index composite tokens like "ramnagarbarabanki", "ramnagarup", "ramnagarbarabankiup"
                        if (rec.isTehsil() && parentDistrict != null) {
                            tokenIndex.putIfAbsent(normalizeToken(name + parentDistrict), rec);
                            tokenIndex.putIfAbsent(normalizeToken(name + stateCode), rec);
                            tokenIndex.putIfAbsent(normalizeToken(name + parentDistrict + stateCode), rec);
                        }

                        for (String alias : aliases) {
                            tokenIndex.putIfAbsent(normalizeToken(alias), rec);
                        }
                    }
                }
            }
            log.info("Successfully loaded {} Indian districts into Pan-India geocoding index.", allDistricts.size());
        } catch (Exception e) {
            log.error("Failed to load Indian districts catalog: {}", e.getMessage(), e);
        } finally {
            initialized = true;
        }
    }

    public List<DistrictRecord> getAllDistricts() {
        ensureInitialized();
        return Collections.unmodifiableList(allDistricts);
    }

    public Optional<DistrictRecord> lookup(String query) {
        ensureInitialized();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String clean = normalizeToken(query);

        // 1. Exact match on indexed tokens / aliases
        DistrictRecord exact = tokenIndex.get(clean);
        if (exact != null) {
            return Optional.of(exact);
        }

        // 2. ID match
        DistrictRecord byId = idIndex.get(clean);
        if (byId != null) {
            return Optional.of(byId);
        }

        // 2b. Disambiguate parenthetical query (e.g. "Ramnagar (Tehsil, Barabanki, UP)" or "Ramnagar (Barabanki, UP)")
        int parenIdx = query.indexOf('(');
        if (parenIdx > 0) {
            String basePart = query.substring(0, parenIdx).trim();
            int closeParen = query.indexOf(')', parenIdx);
            String parenPart = (closeParen > parenIdx ? query.substring(parenIdx + 1, closeParen) : query.substring(parenIdx + 1)).trim();
            String baseClean = normalizeToken(basePart);

            for (DistrictRecord rec : allDistricts) {
                String recBaseClean = cleanBaseName(rec.name());
                if (recBaseClean.equals(baseClean) || normalizeToken(rec.name()).equals(baseClean)) {
                    String parenLower = parenPart.toLowerCase(Locale.ROOT);
                    boolean stateMatch = parenLower.contains(rec.stateCode().toLowerCase(Locale.ROOT))
                            || parenLower.contains(rec.state().toLowerCase(Locale.ROOT));
                    boolean parentMatch = rec.parentDistrict() == null
                            || parenLower.contains(rec.parentDistrict().toLowerCase(Locale.ROOT));
                    if (stateMatch && parentMatch) {
                        return Optional.of(rec);
                    }
                }
            }

            DistrictRecord baseExact = tokenIndex.get(baseClean);
            if (baseExact != null) {
                return Optional.of(baseExact);
            }
        }

        // 3. Prefix match before general substring match
        for (DistrictRecord rec : allDistricts) {
            String normName = normalizeToken(rec.name());
            String normHq = normalizeToken(rec.headquarters());
            if (clean.equals(normName) || clean.equals(normHq)
                    || normName.startsWith(clean) || clean.startsWith(normName)) {
                return Optional.of(rec);
            }
        }

        // 4. Substring / partial match on district name, headquarters, or aliases
        for (DistrictRecord rec : allDistricts) {
            String normName = normalizeToken(rec.name());
            if (normName.contains(clean) || clean.contains(normName)) {
                return Optional.of(rec);
            }
            for (String alias : rec.aliases()) {
                String na = normalizeToken(alias);
                if (na.contains(clean) || clean.contains(na)) {
                    return Optional.of(rec);
                }
            }
        }
        return Optional.empty();
    }

    public List<DistrictRecord> search(String query, int limit) {
        ensureInitialized();
        if (query == null || query.isBlank()) {
            return allDistricts.stream()
                    .filter(d -> d.tier() <= 2)
                    .limit(limit > 0 ? limit : 20)
                    .toList();
        }
        String q = normalizeToken(query);
        int max = limit > 0 ? Math.min(limit, 50) : 10;

        List<DistrictRecord> results = new ArrayList<>();
        // Priority 1: exact prefix match on district name
        for (DistrictRecord d : allDistricts) {
            if (normalizeToken(d.name()).startsWith(q)) {
                results.add(d);
                if (results.size() >= max) return results;
            }
        }
        // Priority 2: prefix on headquarters or state
        for (DistrictRecord d : allDistricts) {
            if (!results.contains(d) && (normalizeToken(d.headquarters()).startsWith(q)
                    || normalizeToken(d.state()).startsWith(q))) {
                results.add(d);
                if (results.size() >= max) return results;
            }
        }
        // Priority 3: contains anywhere in name/aliases
        for (DistrictRecord d : allDistricts) {
            if (!results.contains(d)) {
                if (normalizeToken(d.name()).contains(q)
                        || normalizeToken(d.headquarters()).contains(q)
                        || d.aliases().stream().anyMatch(a -> normalizeToken(a).contains(q))) {
                    results.add(d);
                    if (results.size() >= max) return results;
                }
            }
        }
        return results;
    }

    public DistrictRecord findNearest(GeoPoint point) {
        ensureInitialized();
        if (point == null || allDistricts.isEmpty()) {
            return null;
        }
        return allDistricts.stream()
                .min(Comparator.comparingDouble(d -> Math.hypot(d.lat() - point.lat(), d.lon() - point.lon())))
                .orElse(null);
    }

    private static String normalizeToken(String input) {
        if (input == null) return "";
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "")
                .trim();
    }

    private static String cleanBaseName(String name) {
        if (name == null) return "";
        int paren = name.indexOf('(');
        String base = paren > 0 ? name.substring(0, paren) : name;
        return normalizeToken(base);
    }
}
