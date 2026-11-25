package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;

import in.marg.eia.EnvironmentalReportModels.GenerateReportRequest;
import in.marg.eia.EnvironmentalReportModels.JobReceipt;
import in.marg.eia.EnvironmentalReportModels.JobStatus;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.NoSuchElementException;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** MCP surface for validating and generating evidence-grounded environmental report DOCX files. */
@Component
public class EnvironmentalReportMcpTools {

    private final EnvironmentalReportJobService jobs;
    private final EnvironmentalKnowledgeIndex knowledge;
    private final EnvironmentalReportDraftingService drafting;
    private final EnvironmentalLegalApplicabilityService legalApplicability;
    private final ObjectMapper objectMapper;

    public EnvironmentalReportMcpTools(EnvironmentalReportJobService jobs, EnvironmentalKnowledgeIndex knowledge,
                                       EnvironmentalReportDraftingService drafting,
                                       EnvironmentalLegalApplicabilityService legalApplicability,
                                       ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.knowledge = knowledge;
        this.drafting = drafting;
        this.legalApplicability = legalApplicability;
        this.objectMapper = objectMapper;
    }

    @McpTool(
            name = "generate_environmental_eia_report",
            title = "Generate environmental assessment DOCX",
            description = "Arguments are a JSON object with projectName, projectProponent, routeAlternativeName, studyArea, reportVersion, assessmentDate (ISO YYYY-MM-DD), evidence (typed provenance records), claims (statement, templateToken, evidenceIds, numericClaims with value and units), and optional gisScreening (the existing environmentalSectionInput object from /api/routes/composite). Validate supplied evidence and then asynchronously populate the MARG DOCX template. Every factual claim must cite evidence IDs. This tool does not retrieve evidence, draft unsupported facts, determine legal applicability, or issue statutory clearance.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false)
    )
    public CallToolResult generateEnvironmentalEiaReport(CallToolRequest request) {
        try {
            GenerateReportRequest payload = objectMapper.convertValue(request.arguments(), GenerateReportRequest.class);
            JobReceipt receipt = jobs.start(payload);
            return CallToolResult.builder()
                    .structuredContent(receipt)
                    .addTextContent("Report job " + receipt.jobId() + " is " + receipt.state() + ". " + receipt.message())
                    .build();
        } catch (IllegalArgumentException exception) {
            return CallToolResult.builder().isError(true)
                    .addTextContent("Invalid report request JSON object: " + exception.getMessage()).build();
        }
    }

    @McpTool(
            name = "get_environmental_eia_report_status",
            title = "Get environmental report status",
            description = "Return the current state, validation issues, PDF page count, and DOCX/PDF download paths for an environmental report job.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)
    )
    public CallToolResult getEnvironmentalEiaReportStatus(CallToolRequest request) {
        Object rawJobId = request.arguments().get("jobId");
        String jobId = rawJobId == null ? "" : rawJobId.toString();
        try {
            JobStatus status = jobs.status(jobId)
                    .orElseThrow(() -> new NoSuchElementException("No environmental report job found for that ID."));
            return CallToolResult.builder()
                    .structuredContent(status)
                    .addTextContent("Report job " + status.jobId() + " is " + status.state() + ". " + status.message())
                    .build();
        } catch (NoSuchElementException exception) {
            return CallToolResult.builder().isError(true).addTextContent(exception.getMessage()).build();
        }
    }

    @McpTool(name = "search_environmental_evidence", title = "Search environmental source library",
            description = "Searches indexed local PDFs with page and chunk provenance. Sample EIAs are excluded from factual retrieval. Returns legal-source currency/applicability caveats.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult searchEnvironmentalEvidence(CallToolRequest request) {
        String query = stringArg(request, "query");
        int limit = intArg(request, "limit", 8);
        var results = knowledge.search(query, limit, false);
        return CallToolResult.builder().structuredContent(Map.of("query", query, "results", results,
                        "library", knowledge.status()))
                .addTextContent("Retrieved " + results.size() + " source passages with page/chunk references.").build();
    }

    @McpTool(name = "ask_environmental_question", title = "Answer from environmental evidence",
            description = "Retrieves relevant indexed PDF passages and, when MARG_EIA_LLM_ENABLED is configured, drafts a concise answer citing only returned stable evidence IDs. Sample EIAs are excluded from factual answering.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult askEnvironmentalQuestion(CallToolRequest request) {
        try {
            var answer = drafting.answer(stringArg(request, "question"), intArg(request, "limit", 8));
            return CallToolResult.builder().structuredContent(answer)
                    .addTextContent(answer.answer() + " Evidence IDs: " + String.join(", ", answer.evidenceIds())).build();
        } catch (Exception exception) {
            return CallToolResult.builder().isError(true).addTextContent("Environmental QA failed: " + exception.getMessage()).build();
        }
    }

    @McpTool(name = "generate_environmental_eia_report_from_library", title = "Draft and generate environmental report",
            description = "Retrieves the configured local evidence library, drafts evidence-linked structured sections using the configured LLM, validates citations/numbers, incorporates optional existing Mode 1 GIS screening, and populates the controlled DOCX template. Accepts projectState separately from studyArea for candidate legal screening. Requires MARG_EIA_LLM_ENABLED, MARG_EIA_LLM_API_KEY and MARG_EIA_LLM_MODEL. Candidate legal applicability remains subject to official-source and professional review.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public CallToolResult generateEnvironmentalEiaReportFromLibrary(CallToolRequest request) {
        try {
            var payload = objectMapper.convertValue(request.arguments(), GenerateReportFromLibraryRequest.class);
            var receipt = drafting.generate(payload);
            return CallToolResult.builder().structuredContent(receipt)
                    .addTextContent("Evidence-grounded report job " + receipt.jobId() + " is " + receipt.state() + ". " + receipt.message()).build();
        } catch (Exception exception) {
            return CallToolResult.builder().isError(true).addTextContent("Report drafting failed: " + exception.getMessage()).build();
        }
    }

    @McpTool(name = "refresh_environmental_document_library", title = "Refresh indexed environmental PDFs",
            description = "Re-reads PDF files directly inside MARG_EIA_KNOWLEDGE_DIRECTORY and rebuilds the page-preserving retrieval index.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult refreshEnvironmentalDocumentLibrary(CallToolRequest request) {
        try {
            var status = knowledge.reindex();
            return CallToolResult.builder().structuredContent(status)
                    .addTextContent("Indexed " + status.documentCount() + " PDFs and " + status.chunkCount() + " text chunks.").build();
        } catch (Exception exception) {
            return CallToolResult.builder().isError(true).addTextContent("PDF library indexing failed: " + exception.getMessage()).build();
        }
    }

    @McpTool(name = "screen_environmental_legal_applicability", title = "Screen candidate environmental laws",
            description = "Builds a deterministic source-cited register of retrieved legal documents and missing/outdated knowledge-base references. Applicability is returned as INSUFFICIENT_INFORMATION unless separately confirmed by qualified review; this tool makes no LLM call and never declares compliance, exemption, or clearance.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult screenEnvironmentalLegalApplicability(CallToolRequest request) {
        try {
            var payload = objectMapper.convertValue(request.arguments(), LegalApplicabilityRequest.class);
            var assessment = legalApplicability.assess(payload);
            return CallToolResult.builder().structuredContent(assessment)
                    .addTextContent("Built a source-cited legal reference register for " + assessment.instruments().size()
                            + " instruments. Project-specific applicability remains unresolved and requires current official-source and qualified professional review.").build();
        } catch (Exception exception) {
            return CallToolResult.builder().isError(true).addTextContent("Legal source-register preparation failed: " + exception.getMessage()).build();
        }
    }

    private String stringArg(CallToolRequest request, String field) {
        Object value = request.arguments().get(field);
        return value == null ? "" : value.toString();
    }

    private int intArg(CallToolRequest request, String field, int fallback) {
        Object value = request.arguments().get(field);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException exception) { return fallback; }
    }
}
