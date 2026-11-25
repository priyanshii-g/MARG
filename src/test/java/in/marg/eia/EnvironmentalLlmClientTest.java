package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

class EnvironmentalLlmClientTest {

    private static final String ENDPOINT = "https://llm.test/v1/chat/completions";
    private static final String SUCCESS = """
            {"choices":[{"message":{"content":"{\\"ok\\":true}"}}]}
            """;

    @Test
    void usesPrimaryModelWithoutRetryWhenItSucceeds() {
        Fixture fixture = fixture("primary", "backup-one,backup-two,backup-three", 0);
        expectModel(fixture.server(), "primary").andRespond(withSuccess(SUCCESS, MediaType.APPLICATION_JSON));

        assertEquals("{\"ok\":true}", fixture.client().completeJson("report-draft", "system", "user"));
        fixture.server().verify();
    }

    @Test
    void retriesPrimaryOnceThenUsesFirstBackupAfter503() {
        Fixture fixture = fixture("primary", "backup-one,backup-two,backup-three", 0);
        expectModel(fixture.server(), "primary").andRespond(unavailable());
        expectModel(fixture.server(), "primary").andRespond(unavailable());
        expectModel(fixture.server(), "backup-one").andRespond(withSuccess(SUCCESS, MediaType.APPLICATION_JSON));

        assertEquals("{\"ok\":true}", fixture.client().completeJson("report-draft", "system", "user"));
        fixture.server().verify();
    }

    @Test
    void stopsAfterConfiguredModelsAllReturn503() {
        Fixture fixture = fixture("primary", "backup-one,backup-two,backup-three,ignored-four", 0);
        expectModel(fixture.server(), "primary").andRespond(unavailable());
        expectModel(fixture.server(), "primary").andRespond(unavailable());
        expectModel(fixture.server(), "backup-one").andRespond(unavailable());
        expectModel(fixture.server(), "backup-two").andRespond(unavailable());
        expectModel(fixture.server(), "backup-three").andRespond(unavailable());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fixture.client().completeJson("environmental-report-review", "system", "user"));

        assertTrue(error.getMessage().contains("HTTP 503"));
        assertTrue(error.getMessage().contains("primary, backup-one, backup-two, backup-three"));
        fixture.server().verify();
    }

    @Test
    void doesNotRetryAuthenticationOrOtherNon503Errors() {
        Fixture fixture = fixture("primary", "backup-one,backup-two", 0);
        expectModel(fixture.server(), "primary").andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(HttpStatusCodeException.class,
                () -> fixture.client().completeJson("mode2-question-answer", "system", "user"));
        fixture.server().verify();
    }

    @Test
    void modelCandidatesAreDistinctAndLimitedToPrimaryPlusThreeBackups() {
        EnvironmentalLlmProperties properties = properties("primary", "primary, backup-one, backup-two, backup-three, ignored-four", 0);

        assertEquals(List.of("primary", "backup-one", "backup-two", "backup-three"), properties.modelCandidates());
    }

    private Fixture fixture(String primary, String fallbacks, int delayMillis) {
        EnvironmentalLlmProperties properties = properties(primary, fallbacks, delayMillis);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.baseUrl("https://llm.test/v1").build();
        return new Fixture(new EnvironmentalLlmClient(properties, client), server);
    }

    private EnvironmentalLlmProperties properties(String primary, String fallbacks, int delayMillis) {
        return new EnvironmentalLlmProperties(true, "https://llm.test/v1", "test-key", primary, 2,
                fallbacks, delayMillis);
    }

    private ResponseActions expectModel(MockRestServiceServer server, String model) {
        return server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"model\":\"" + model + "\"}", false));
    }

    private ResponseCreator unavailable() {
        return withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"temporarily unavailable\"}}");
    }

    private record Fixture(EnvironmentalLlmClient client, MockRestServiceServer server) {}
}
