package in.marg.district;

import in.marg.model.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IndianDistrictsCatalogServiceTest {

    private IndianDistrictsCatalogService catalogService;

    @BeforeEach
    void setUp() {
        catalogService = new IndianDistrictsCatalogService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "classpath:data/india_districts.json"
        );
        catalogService.ensureInitialized();
    }

    @Test
    @DisplayName("Should load 7,100+ Indian sub-districts and districts across all 28 states and 8 UTs")
    void shouldLoadAllIndianDistricts() {
        List<DistrictRecord> all = catalogService.getAllDistricts();
        assertNotNull(all);
        assertTrue(all.size() >= 7500, "Expected at least 7,500 administrative entities, found " + all.size());
        long subDistricts = all.stream().filter(d -> !"DISTRICT".equals(d.adminLevel())).count();
        assertTrue(subDistricts >= 7000, "Expected at least 7,000 sub-districts (tehsils/taluks/mandals), found " + subDistricts);
    }

    @Test
    @DisplayName("Should lookup prominent and small districts by name and aliases")
    void shouldLookupDistrictsByNameAndAliases() {
        // Gangetic Plains: Barabanki, Ayodhya/Faizabad, Varanasi
        Optional<DistrictRecord> barabanki = catalogService.lookup("Barabanki");
        assertTrue(barabanki.isPresent());
        assertEquals("Barabanki", barabanki.get().name());
        assertEquals("UP", barabanki.get().stateCode());

        Optional<DistrictRecord> faizabad = catalogService.lookup("Faizabad");
        assertTrue(faizabad.isPresent());
        assertEquals("Ayodhya", faizabad.get().name());

        // Western Ghats: Satara District, Wai Tehsil, Pune
        Optional<DistrictRecord> satara = catalogService.lookup("Satara");
        assertTrue(satara.isPresent());
        assertEquals("Satara", satara.get().name());
        assertEquals("MH", satara.get().stateCode());

        Optional<DistrictRecord> wai = catalogService.lookup("wai");
        assertTrue(wai.isPresent());
        assertEquals("Wai", wai.get().name());
        assertEquals("Satara", wai.get().parentDistrict());
        assertEquals("TEHSIL", wai.get().adminLevel());
        assertEquals(17.952, wai.get().lat(), 0.01);
        assertEquals(73.915, wai.get().lon(), 0.01);

        // Ramnagar Tehsil (Barabanki, UP) with exact coordinates
        Optional<DistrictRecord> ramnagar = catalogService.lookup("Ramnagar");
        assertTrue(ramnagar.isPresent());
        assertEquals("Ramnagar", ramnagar.get().name());
        assertEquals("Barabanki", ramnagar.get().parentDistrict());
        assertEquals("TEHSIL", ramnagar.get().adminLevel());
        assertEquals(27.085, ramnagar.get().lat(), 0.01);
        assertEquals(81.405, ramnagar.get().lon(), 0.01);

        // Disambiguation search: "Ramnagar" presents multiple distinct settlements across states
        List<DistrictRecord> ramnagarOptions = catalogService.search("Ramnagar", 10);
        assertTrue(ramnagarOptions.size() >= 3, "Expected at least 3 Ramnagar settlements for disambiguation");
        assertTrue(ramnagarOptions.stream().anyMatch(r -> "Barabanki".equals(r.parentDistrict())));
        assertTrue(ramnagarOptions.stream().anyMatch(r -> "Nainital".equals(r.parentDistrict())));

        // Disambiguation search: "Rampur" across UP, MP, HP
        List<DistrictRecord> rampurOptions = catalogService.search("Rampur", 10);
        assertTrue(rampurOptions.size() >= 3, "Expected multiple Rampur settlements across states");
        assertTrue(rampurOptions.stream().anyMatch(r -> "UP".equals(r.stateCode())));
        assertTrue(rampurOptions.stream().anyMatch(r -> "MP".equals(r.stateCode()) || "HP".equals(r.stateCode())));

        // Multi-state tehsils across India
        Optional<DistrictRecord> manesar = catalogService.lookup("Manesar");
        assertTrue(manesar.isPresent());
        assertEquals("Gurugram", manesar.get().parentDistrict());
        assertEquals("HR", manesar.get().stateCode());

        Optional<DistrictRecord> hospet = catalogService.lookup("Hospet");
        assertTrue(hospet.isPresent());
        assertEquals("Vijayanagara", hospet.get().parentDistrict());
        assertEquals("KA", hospet.get().stateCode());

        Optional<DistrictRecord> karad = catalogService.lookup("Karad");
        assertTrue(karad.isPresent());
        assertEquals("Satara", karad.get().parentDistrict());
        assertEquals("MH", karad.get().stateCode());

        Optional<DistrictRecord> sriperumbudur = catalogService.lookup("Sriperumbudur");
        assertTrue(sriperumbudur.isPresent());
        assertEquals("Kanchipuram", sriperumbudur.get().parentDistrict());
        assertEquals("TN", sriperumbudur.get().stateCode());

        // Assam: Karbi Anglong, Diphu, Golaghat
        Optional<DistrictRecord> karbi = catalogService.lookup("Karbi Anglong");
        assertTrue(karbi.isPresent());
        assertEquals("AS", karbi.get().stateCode());

        Optional<DistrictRecord> diphu = catalogService.lookup("diphu");
        assertTrue(diphu.isPresent());
        assertEquals("Karbi Anglong", diphu.get().name());

        // Small/Remote Districts across India
        Optional<DistrictRecord> anjaw = catalogService.lookup("Anjaw");
        assertTrue(anjaw.isPresent());
        assertEquals("AR", anjaw.get().stateCode());

        Optional<DistrictRecord> wayanad = catalogService.lookup("Wayanad");
        assertTrue(wayanad.isPresent());
        assertEquals("KL", wayanad.get().stateCode());

        Optional<DistrictRecord> leh = catalogService.lookup("Leh");
        assertTrue(leh.isPresent());
        assertEquals("LA", leh.get().stateCode());
    }

    @Test
    @DisplayName("Should perform autocomplete prefix search")
    void shouldSearchDistricts() {
        List<DistrictRecord> res = catalogService.search("Bar", 5);
        assertFalse(res.isEmpty());
        assertTrue(res.stream().anyMatch(d -> d.name().toLowerCase().contains("bar")));
    }

    @Test
    @DisplayName("Should find nearest district to given coordinate")
    void shouldFindNearestDistrict() {
        // Near Lucknow (26.85, 80.95)
        DistrictRecord nearest = catalogService.findNearest(new GeoPoint(26.85, 80.95));
        assertNotNull(nearest);
        assertEquals("Lucknow", nearest.name());
    }
}
