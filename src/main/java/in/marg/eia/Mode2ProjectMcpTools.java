package in.marg.eia;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/** MCP tools that expose Mode 2 project context without mixing it with curated guidance retrieval. */
@Component
public class Mode2ProjectMcpTools {

    private final Mode2ProjectDocumentService projects;
    private final Mode2ProjectRagService rag;

    public Mode2ProjectMcpTools(Mode2ProjectDocumentService projects, Mode2ProjectRagService rag) {
        this.projects = projects;
        this.rag = rag;
    }

    @McpTool(name = "create_mode2_project", title = "Create existing-project workspace",
            description = "Create a persistent Mode 2 workspace for an existing highway or infrastructure project. Returns a projectId used to scope all project-document retrieval.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public CallToolResult createProject(CallToolRequest request) {
        try {
            Object name = request.arguments().get("name");
            var project = projects.createProject(name == null ? "" : name.toString());
            return CallToolResult.builder().structuredContent(project)
                    .addTextContent("Created Mode 2 project " + project.projectId() + ".").build();
        } catch (Exception exception) {
            return error("Could not create Mode 2 project: ", exception);
        }
    }

    @McpTool(name = "list_mode2_projects", title = "List existing-project workspaces",
            description = "List persistent Mode 2 project workspaces. Curated environmental knowledge documents are stored and searched separately.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult listProjects(CallToolRequest request) {
        try {
            var result = projects.listProjects();
            return CallToolResult.builder().structuredContent(Map.of("projects", result))
                    .addTextContent("Found " + result.size() + " Mode 2 project workspaces.").build();
        } catch (Exception exception) {
            return error("Could not list Mode 2 projects: ", exception);
        }
    }

    @McpTool(name = "list_mode2_project_documents", title = "List project source documents",
            description = "List documents uploaded to one Mode 2 project workspace, including extraction status and page/chunk counts.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult listDocuments(CallToolRequest request) {
        try {
            var documents = projects.listDocuments(stringArg(request, "projectId"));
            return CallToolResult.builder().structuredContent(Map.of("documents", documents))
                    .addTextContent("Found " + documents.size() + " documents in the requested project.").build();
        } catch (Exception exception) {
            return error("Could not list Mode 2 project documents: ", exception);
        }
    }

    @McpTool(name = "search_mode2_project_documents", title = "Search project evidence",
            description = "Search only the named Mode 2 project's indexed PDFs using deterministic BM25. Returns stable evidence IDs and exact document, page, chunk, and excerpt provenance. This searches project documents, not the curated environmental knowledge base.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult searchProjectDocuments(CallToolRequest request) {
        try {
            String projectId = stringArg(request, "projectId");
            String query = stringArg(request, "query");
            int limit = intArg(request, "limit", 8);
            var result = projects.searchResponse(projectId, query, limit);
            return CallToolResult.builder().structuredContent(result)
                    .addTextContent("Retrieved " + result.results().size() + " cited passages from project " + projectId + ".")
                    .build();
        } catch (Exception exception) {
            return error("Mode 2 project-document search failed: ", exception);
        }
    }

    @McpTool(name = "ask_mode2_project_question", title = "Answer from project documents",
            description = "Retrieve passages only from the specified Mode 2 project's PDFs and draft an answer using the configured LLM. Returns exact project evidence IDs and page references. If the LLM is not configured, returns retrieved passages without inventing an answer.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult askProjectQuestion(CallToolRequest request) {
        try {
            var result = rag.answer(stringArg(request, "projectId"), stringArg(request, "question"), intArg(request, "limit", 8));
            return CallToolResult.builder().structuredContent(result)
                    .addTextContent(result.answer() + " Evidence IDs: " + String.join(", ", result.evidenceIds())).build();
        } catch (Exception exception) {
            return error("Mode 2 project question failed: ", exception);
        }
    }

    @McpTool(name = "extract_mode2_project_facts", title = "Extract cited project facts",
            description = "Retrieve project-specific passages and extract structured claims for GIS preparation, including route description, length, ROW, land/forest, ecological receptors, water crossings and clearances. Each fact must carry an exact supporting excerpt and retrieved evidence IDs; absent fields are returned as missing. Values are document claims pending verification.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult extractProjectFacts(CallToolRequest request) {
        try {
            var result = rag.extractFacts(stringArg(request, "projectId"), stringArg(request, "query"), intArg(request, "limit", 20));
            return CallToolResult.builder().structuredContent(result)
                    .addTextContent("Extracted " + result.facts().size() + " cited project claims; "
                            + result.missingFields().size() + " fields remain unreported.").build();
        } catch (Exception exception) {
            return error("Mode 2 project fact extraction failed: ", exception);
        }
    }

    private String stringArg(CallToolRequest request, String name) {
        Object value = request.arguments().get(name);
        return value == null ? "" : value.toString();
    }

    private int intArg(CallToolRequest request, String name, int fallback) {
        Object value = request.arguments().get(name);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException exception) { return fallback; }
    }

    private CallToolResult error(String prefix, Exception exception) {
        return CallToolResult.builder().isError(true).addTextContent(prefix + exception.getMessage()).build();
    }
}
