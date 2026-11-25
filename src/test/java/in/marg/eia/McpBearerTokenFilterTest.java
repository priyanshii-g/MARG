package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpBearerTokenFilterTest {

    @Test
    void rejectsMissingOrIncorrectBearerToken() throws Exception {
        var filter = new McpBearerTokenFilter("secret-test-token");
        var request = new MockHttpServletRequest("POST", "/mcp");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertTrue(chain.getRequest() == null);
    }

    @Test
    void allowsMatchingTokenAndFailsClosedWhenSecretIsNotConfigured() throws Exception {
        var filter = new McpBearerTokenFilter("secret-test-token");
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer secret-test-token");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);

        var unconfigured = new McpBearerTokenFilter("");
        var unconfiguredResponse = new MockHttpServletResponse();
        unconfigured.doFilter(new MockHttpServletRequest("POST", "/mcp"), unconfiguredResponse, new MockFilterChain());
        assertEquals(503, unconfiguredResponse.getStatus());
    }

    @Test
    void leavesNonMcpApplicationRoutesUntouched() throws Exception {
        var filter = new McpBearerTokenFilter("");
        var request = new MockHttpServletRequest("GET", "/api/system");
        var response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> continued.set(true));

        assertTrue(continued.get());
        assertEquals(200, response.getStatus());

        for (String path : new String[]{"/api/eia/library", "/api/eia/library/documents", "/api/eia/reports/jobs/id",
                "/api/mode2/projects", "/api/mode2/projects/M2P-ABC/documents"}) {
            var untouchedResponse = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", path), untouchedResponse, new MockFilterChain());
            assertEquals(200, untouchedResponse.getStatus(), "Expected non-MCP route to pass through untouched: " + path);
        }
    }
}
