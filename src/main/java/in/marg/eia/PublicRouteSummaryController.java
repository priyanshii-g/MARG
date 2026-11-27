package in.marg.eia;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generates a plain-English, zero-jargon public summary card for any planned MARG route,
 * reusing the shared {@link EnvironmentalLlmClient} when configured.
 */
@RestController
@RequestMapping("/api/routes")
public class PublicRouteSummaryController {

    private final PublicRouteSummaryService summaryService;

    public PublicRouteSummaryController(PublicRouteSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @PostMapping("/public-summary")
    public ResponseEntity<Map<String, Object>> generatePublicSummary(
            @RequestBody(required = false) Map<String, Object> routeFacts) {
        Map<String, Object> summary = summaryService.summarize(routeFacts);
        return ResponseEntity.ok(summary);
    }
}
