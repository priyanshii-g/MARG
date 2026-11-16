package in.marg;

import in.marg.api.CompositeRoutingController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MargApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void contextLoads() {
        assertNotNull(context);
        assertNotNull(handlerMapping);
    }

    @Test
    void testRequestCompositeWithIntermediateHub() throws Exception {
        org.springframework.web.context.WebApplicationContext wac =
                (org.springframework.web.context.WebApplicationContext) context;
        org.springframework.test.web.servlet.MockMvc mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(wac).build();

        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/routes/composite")
                        .param("startLat", "26.85")
                        .param("startLon", "81.0")
                        .param("endLat", "26.7")
                        .param("endLon", "82.2")
                        .param("region", "gangetic_plains")
                        .param("facilityMode", "EXPRESSWAY")
                        .param("targetHubs", "ramnagar"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("GREENFIELD_MAINLINE"));
        assertTrue(body.contains("Ramnagar"));

        // Also verify preset-region routing with disambiguated tehsil token "Ramnagar (Barabanki, UP)"
        var presetResult = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/routes/composite")
                        .param("startLat", "26.915")
                        .param("startLon", "81.245")
                        .param("endLat", "26.765")
                        .param("endLon", "82.125")
                        .param("region", "gangetic_plains")
                        .param("facilityMode", "EXPRESSWAY")
                        .param("targetHubs", "Ramnagar (Barabanki, UP)"))
                .andReturn();

        assertEquals(200, presetResult.getResponse().getStatus());
        String presetBody = presetResult.getResponse().getContentAsString();
        assertTrue(presetBody.contains("COMPOSITE_13_LAYER"));
        assertTrue(presetBody.contains("Ramnagar"));
        assertTrue(presetBody.contains("NH-927"));

        // 3. Verify Pan-India Dynamic Engine connecting Manesar Tehsil (Haryana)
        var dynamicResult = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/routes/composite")
                        .param("startLat", "28.55")
                        .param("startLon", "77.10")
                        .param("endLat", "28.00")
                        .param("endLon", "76.40")
                        .param("facilityMode", "EXPRESSWAY")
                        .param("targetHubs", "Manesar (Gurugram - HR)"))
                .andReturn();

        assertEquals(200, dynamicResult.getResponse().getStatus());
        String dynBody = dynamicResult.getResponse().getContentAsString();
        assertTrue(dynBody.contains("GREENFIELD_MAINLINE"));
        assertTrue(dynBody.contains("INTERCHANGE_JUNCTION"));
        assertTrue(dynBody.contains("FEEDER_SPUR_HIGHWAY"));
        assertTrue(dynBody.contains("Manesar"));
    }
}
