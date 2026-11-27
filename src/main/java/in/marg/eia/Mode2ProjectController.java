package in.marg.eia;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP surface for persistent Mode 2 project workspaces, source PDFs, RAG and GIS comparisons. */
@RestController
@RequestMapping("/api/mode2")
public class Mode2ProjectController {

    private static final Logger LOGGER = LoggerFactory.getLogger(Mode2ProjectController.class);

    private final Mode2ProjectDocumentService projects;
    private final Mode2AlignmentScreeningService alignmentScreening;
    private final Mode2ProjectEvidenceComparisonService evidenceComparison;
    private final Mode2AlternativeRouteService alternatives;
    private final Mode2ProjectRagService rag;

    public Mode2ProjectController(Mode2ProjectDocumentService projects,
                                  Mode2AlignmentScreeningService alignmentScreening,
                                  Mode2ProjectEvidenceComparisonService evidenceComparison,
                                  Mode2AlternativeRouteService alternatives,
                                  Mode2ProjectRagService rag) {
        this.projects = projects;
        this.alignmentScreening = alignmentScreening;
        this.evidenceComparison = evidenceComparison;
        this.alternatives = alternatives;
        this.rag = rag;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleMode2RequestError(ResponseStatusException exception) {
        String detail = exception.getReason() == null || exception.getReason().isBlank()
                ? "The Mode 2 request did not pass validation." : exception.getReason();
        LOGGER.warn("Mode 2 request rejected ({}): {}", exception.getStatusCode().value(), detail);
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "status", exception.getStatusCode().value(),
                "error", HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase(),
                "detail", detail));
    }

    @PostMapping("/projects")
    public Mode2ProjectDocumentService.ProjectRecord createProject(@RequestBody CreateProjectRequest request) throws IOException {
        try {
            return projects.createProject(request == null ? null : request.name());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/projects")
    public List<Mode2ProjectDocumentService.ProjectRecord> listProjects() throws IOException {
        return projects.listProjects();
    }

    @PostMapping("/projects/{projectId}/documents")
    public ResponseEntity<Mode2ProjectDocumentService.ProjectDocumentRecord> uploadDocument(
            @PathVariable("projectId") String projectId,
            @RequestParam("file") MultipartFile file) throws IOException {
        try {
            return ResponseEntity.ok(projects.uploadDocument(projectId, file));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage() != null && exception.getMessage().contains("not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    @PostMapping("/projects/{projectId}/attach-sample-document")
    public ResponseEntity<Mode2ProjectDocumentService.ProjectDocumentRecord> attachSampleDocument(
            @PathVariable("projectId") String projectId,
            @RequestParam(value = "type", required = false) String type) throws IOException {
        java.nio.file.Path repoSample;
        String fileName;

        if ("nh66".equalsIgnoreCase(type) || "esia".equalsIgnoreCase(type)) {
            repoSample = java.nio.file.Path.of("../Environmental and Social Impact Assessment (Esia) Of Nh66.pdf").toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(repoSample)) {
                repoSample = java.nio.file.Path.of("/Users/abhinavmishra/Desktop/MARG/Environmental and Social Impact Assessment (Esia) Of Nh66.pdf").toAbsolutePath().normalize();
            }
            fileName = "NH-66 Highway Environmental and Social Impact Assessment (ESIA) Report.pdf";
        } else if ("pulicat".equalsIgnoreCase(type) || "bridge".equalsIgnoreCase(type)) {
            repoSample = java.nio.file.Path.of("../sample1_EIAReport.pdf").toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(repoSample)) {
                repoSample = java.nio.file.Path.of("/Users/abhinavmishra/Desktop/MARG/sample1_EIAReport.pdf").toAbsolutePath().normalize();
            }
            fileName = "EIA Report - High Level Bridge Pulicat Lake.pdf";
        } else {
            // Default: Full-Length Statutory Comprehensive EIA & EMP Report (matches Sildharampur RF / Kaziranga-Karbi Anglong Corridor KML)
            repoSample = java.nio.file.Path.of("../NH29_Sildharampur_Comprehensive_EIA_Report.pdf").toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(repoSample)) {
                repoSample = java.nio.file.Path.of("/Users/abhinavmishra/Desktop/MARG/NH29_Sildharampur_Comprehensive_EIA_Report.pdf").toAbsolutePath().normalize();
            }
            if (!java.nio.file.Files.isRegularFile(repoSample)) {
                repoSample = java.nio.file.Path.of("../NH29_Daboka_Lahorijan_Clearance_Dossier.pdf").toAbsolutePath().normalize();
            }
            fileName = "NH-29 Sildharampur RF Corridor — Comprehensive EIA & EMP Report.pdf";
        }

        if (!java.nio.file.Files.isRegularFile(repoSample)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sample environmental report PDF was not found in repository.");
        }
        try {
            return ResponseEntity.ok(projects.uploadDocumentFromPath(projectId, repoSample, fileName));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage() != null && exception.getMessage().contains("not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    @GetMapping("/projects/{projectId}/documents")
    public List<Mode2ProjectDocumentService.ProjectDocumentRecord> listDocuments(@PathVariable("projectId") String projectId) throws IOException {
        try {
            return projects.listDocuments(projectId);
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage() != null && exception.getMessage().contains("not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    @PostMapping("/projects/{projectId}/search")
    public Mode2ProjectDocumentService.ProjectSearchResponse search(
            @PathVariable("projectId") String projectId,
            @RequestBody ProjectSearchRequest request) throws IOException {
        try {
            return projects.searchResponse(projectId, request == null ? null : request.query(),
                    request == null ? 8 : request.limit());
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage() != null && exception.getMessage().contains("not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    @PostMapping("/projects/{projectId}/answer")
    public Mode2ProjectRagService.ProjectAnswer answer(
            @PathVariable("projectId") String projectId, @RequestBody ProjectQuestionRequest request) throws IOException {
        try {
            if (request == null) throw new IllegalArgumentException("Question is required.");
            var screening = alignmentScreening.getLatestScreening(projectId);
            if (screening == null) {
                return new Mode2ProjectRagService.ProjectAnswer(projectId, request.question(),
                        "Question answering is locked until alignment GIS screening is performed. MARG Q&A cross-examines document claims against spatial GIS ground-truth rather than performing unverified document summarization. Please screen the project alignment first.",
                        List.of(), List.of(), "GIS_ANALYSIS_REQUIRED",
                        "Screen the project alignment geometry in Step 02 to unlock cross-verified question answering.");
            }
            return rag.answer(projectId, request.question(), request.limit(), screening.evidence());
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage() != null && exception.getMessage().contains("not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            LOGGER.warn("LLM answer validation failed for project {}: {}; falling back to grounded synthesis",
                    projectId, exception.getMessage());
            var screening = alignmentScreening.getLatestScreening(projectId);
            return rag.fallbackAnswer(projectId, request.question(), request.limit(), exception.getMessage(),
                    screening != null ? screening.evidence() : null);
        }
    }

    @PostMapping("/projects/{projectId}/extract-facts")
    public Mode2ProjectRagService.ProjectFactExtraction extractFacts(
            @PathVariable("projectId") String projectId, @RequestBody ProjectSearchRequest request) throws IOException {
        try {
            if (request == null) throw new IllegalArgumentException("A project topic or extraction query is required.");
            return rag.extractFacts(projectId, request.query(), request.limit());
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage() != null && exception.getMessage().contains("not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            LOGGER.warn("LLM fact extraction validation failed for project {}: {}", projectId, exception.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception);
        }
    }

    @PostMapping("/projects/{projectId}/screen-alignment")
    public Mode2AlignmentScreeningService.AlignmentScreeningResult screenAlignment(
            @PathVariable("projectId") String projectId,
            @RequestBody Mode2AlignmentScreeningService.AlignmentScreeningRequest request) throws IOException {
        try {
            return alignmentScreening.screen(projectId, request);
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage() != null && exception.getMessage().contains("project was not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    @PostMapping("/projects/{projectId}/compare-alignment")
    public Mode2ProjectEvidenceComparisonService.ComparisonResult compareAlignment(
            @PathVariable("projectId") String projectId, @RequestBody CompareAlignmentRequest request) throws IOException {
        try {
            if (request == null) throw new IllegalArgumentException("Comparison request is required.");
            return evidenceComparison.compare(projectId, request.factQuery(), request.factLimit(),
                    new Mode2AlignmentScreeningService.AlignmentScreeningRequest(request.alignmentName(),
                            request.regionId(), request.facilityMode(), request.coordinates()));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage() != null && exception.getMessage().contains("project was not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    @PostMapping("/projects/{projectId}/recommend-alternative")
    public Mode2AlternativeRouteService.AlternativeRecommendation recommendAlternative(
            @PathVariable("projectId") String projectId,
            @RequestBody Mode2AlignmentScreeningService.AlignmentScreeningRequest request) throws IOException {
        try {
            return alternatives.recommend(projectId, request);
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage() != null && exception.getMessage().contains("project was not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    @GetMapping("/demo-alignments")
    public List<DemoAlignment> listDemoAlignments() {
        return List.of(
            new DemoAlignment(
                "sildharampur-rf",
                "Sildharampur RF – NH-29 Corridor (Nagaon, Assam)",
                "FP-AS-ROAD-125122-2021-segment-sildharampur.kml",
                "assam",
                List.of(
                    List.of(92.9999, 26.2208), List.of(93.0018, 26.2212), List.of(93.0039, 26.2209),
                    List.of(93.0063, 26.2200), List.of(93.0086, 26.2196), List.of(93.0110, 26.2191),
                    List.of(93.0128, 26.2185), List.of(93.0137, 26.2184), List.of(93.0159, 26.2185),
                    List.of(93.0172, 26.2185), List.of(93.0185, 26.2183), List.of(93.0199, 26.2180),
                    List.of(93.0208, 26.2175), List.of(93.0215, 26.2168), List.of(93.0235, 26.2150),
                    List.of(93.0241, 26.2149), List.of(93.0248, 26.2147), List.of(93.0256, 26.2145),
                    List.of(93.0261, 26.2144), List.of(93.0269, 26.2142), List.of(93.0275, 26.2141),
                    List.of(93.0343, 26.2120), List.of(93.0349, 26.2119), List.of(93.0353, 26.2117),
                    List.of(93.0357, 26.2116), List.of(93.0353, 26.2120), List.of(93.0376, 26.2116),
                    List.of(93.0392, 26.2131), List.of(93.0400, 26.2145), List.of(93.0428, 26.2172),
                    List.of(93.0448, 26.2183), List.of(93.0474, 26.2188)
                ),
                "Continuous NH-29 road alignment extracted from PARIVESH forest-diversion RoW polygon patches (FP-AS-ROAD-125122-2021). Perfectly traces the existing highway."
            ),
            new DemoAlignment(
                "langlit-rf",
                "Langlit RF – Forest Diversion Corridor (Karbi Anglong, Assam)",
                "411261230122213R69LanglitRF.kml",
                "assam",
                List.of(
                    List.of(93.2752, 25.9984), List.of(93.2782, 25.9985), List.of(93.2801, 25.9969),
                    List.of(93.2812, 25.9953), List.of(93.2835, 25.9937), List.of(93.2876, 25.9922),
                    List.of(93.2884, 25.9923), List.of(93.2905, 25.9919), List.of(93.2925, 25.9911),
                    List.of(93.2937, 25.9908), List.of(93.2946, 25.9910), List.of(93.2952, 25.9909),
                    List.of(93.2964, 25.9912), List.of(93.2971, 25.9916), List.of(93.2978, 25.9920),
                    List.of(93.2983, 25.9919), List.of(93.2988, 25.9921), List.of(93.2994, 25.9925),
                    List.of(93.3007, 25.9926), List.of(93.3020, 25.9926), List.of(93.3038, 25.9933),
                    List.of(93.3048, 25.9935), List.of(93.3057, 25.9936), List.of(93.3068, 25.9945),
                    List.of(93.3073, 25.9949), List.of(93.3078, 25.9951), List.of(93.3080, 25.9959),
                    List.of(93.3084, 25.9966), List.of(93.3093, 25.9978), List.of(93.3104, 25.9984),
                    List.of(93.3115, 25.9990), List.of(93.3124, 25.9999), List.of(93.3130, 26.0011),
                    List.of(93.3153, 26.0015), List.of(93.3177, 26.0014), List.of(93.3193, 26.0007),
                    List.of(93.3214, 25.9999), List.of(93.3229, 25.9998)
                ),
                "PARIVESH road corridor in Karbi Anglong East Division, traversing Langlit Reserve Forest (5.2 km alignment, 18 diversion patches)."
            )
        );
    }

    public record DemoAlignment(
        String id,
        String displayName,
        String sourceFile,
        String regionId,
        List<List<Double>> coordinates,
        String description
    ) {}

    public record CreateProjectRequest(String name) {}
    public record ProjectSearchRequest(String query, int limit) {}
    public record ProjectQuestionRequest(String question, int limit) {}
    public record CompareAlignmentRequest(String factQuery, int factLimit, String alignmentName,
                                          String regionId, String facilityMode, List<List<Double>> coordinates) {}
}
