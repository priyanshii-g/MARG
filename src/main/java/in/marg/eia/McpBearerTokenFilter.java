package in.marg.eia;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Protects the MCP transport with a deployment-provided bearer secret. */
@Component
public class McpBearerTokenFilter extends OncePerRequestFilter {

    private final String expectedToken;

    public McpBearerTokenFilter(@Value("${marg.mcp.bearer-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/mcp") || path.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (expectedToken.isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "MARG_MCP_BEARER_TOKEN must be configured to enable MCP access.");
            return;
        }
        String authorization = request.getHeader("Authorization");
        String suppliedToken = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : "";
        boolean authorized = MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8));
        if (!authorized) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Bearer token required.");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
