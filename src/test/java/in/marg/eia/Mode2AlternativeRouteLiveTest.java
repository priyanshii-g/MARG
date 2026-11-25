package in.marg.eia;

import in.marg.region.RegionalCorridorRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Mode2AlternativeRouteLiveTest {

    @Test
    void testSildharampurAlternativeRouting() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        Map<String, Object> res = registry.route("assam", "TIER_2_PRECACHED_BENCHMARK",
                26.2208, 92.9999, 26.2188, 93.0474, 250.0, 0.08, 4000, null,
                "NATIONAL_HIGHWAY", null, null, null, false, null);
        
        assertNotNull(res);
        Map<?, ?> metadata = (Map<?, ?>) res.get("metadata");
        double routeLengthKm = ((Number) metadata.get("routeLengthKm")).doubleValue();
        assertTrue(routeLengthKm < 10.0, "Alternative route length should be under 10 km, was " + routeLengthKm);
        assertTrue(routeLengthKm > 4.0, "Alternative route length should be at least 4 km, was " + routeLengthKm);
    }

    @Test
    void testLanglitAlternativeRouting() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        Map<String, Object> res = registry.route("assam", "TIER_2_PRECACHED_BENCHMARK",
                25.9984, 93.2752, 25.9998, 93.3229, 250.0, 0.08, 4000, null,
                "NATIONAL_HIGHWAY", null, null, null, false, null);
        
        assertNotNull(res);
        Map<?, ?> metadata = (Map<?, ?>) res.get("metadata");
        double routeLengthKm = ((Number) metadata.get("routeLengthKm")).doubleValue();
        assertTrue(routeLengthKm < 10.0, "Alternative route length should be under 10 km, was " + routeLengthKm);
        assertTrue(routeLengthKm > 4.0, "Alternative route length should be at least 4 km, was " + routeLengthKm);
    }
}
