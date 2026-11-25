package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for the browser UI; it reuses the same retrieval, drafting and validation pipeline as MCP. */
@RestController
@RequestMapping("/api/eia/reports")
public class EnvironmentalReportHttpController {

    private final EnvironmentalReportDraftingService drafting;

    public EnvironmentalReportHttpController(EnvironmentalReportDraftingService drafting) {
        this.drafting = drafting;
    }

    @PostMapping("/from-library")
    public ResponseEntity<?> generateFromLibrary(@RequestBody GenerateReportFromLibraryRequest request) {
        try {
            JobReceipt receipt = drafting.generate(request);
            return ResponseEntity.accepted().body(receipt);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", safeMessage(exception)));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", safeMessage(exception)));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Environmental report generation could not be started.",
                    "detail", safeMessage(exception)));
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
