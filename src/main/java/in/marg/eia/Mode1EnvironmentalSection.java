package in.marg.eia;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Structured, provenance-preserving input for the environmental part of a Mode 1 report. */
public record Mode1EnvironmentalSection(
        String sectionId,
        String title,
        String assessmentBasis,
        @JsonDeserialize(using = ObjectMapDeserializer.class) Map<String, Object> method,
        List<Finding> findings,
        List<String> notAssessed,
        List<String> limitations,
        List<ReportSection> reportSections,
        @JsonDeserialize(using = ObjectMapDeserializer.class) Map<String, Object> templateFields
) {
    public Mode1EnvironmentalSection {
        method = method == null ? Map.of() : safeMapCopy(method);
        findings = findings == null ? List.of() : List.copyOf(findings);
        notAssessed = notAssessed == null ? List.of() : List.copyOf(notAssessed);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        reportSections = reportSections == null ? List.of() : List.copyOf(reportSections);
        templateFields = templateFields == null ? Map.of() : safeMapCopy(templateFields);
    }

    private static Map<String, Object> safeMapCopy(Map<String, Object> map) {
        Map<String, Object> clean = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) clean.put(k, v);
        });
        return java.util.Collections.unmodifiableMap(clean);
    }

    /** A report-chapter instruction for downstream drafting and template assembly. */
    public record ReportSection(
            String sectionId,
            String title,
            SectionStatus status,
            List<String> templateTokens,
            List<String> evidenceIds,
            String instruction
    ) {
        public ReportSection {
            templateTokens = List.copyOf(templateTokens);
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    public enum SectionStatus {
        GIS_SCREENING_AVAILABLE,
        PARTIAL_SCREENING_ONLY,
        PROJECT_INPUT_REQUIRED,
        KNOWLEDGE_BASE_AND_PROJECT_INPUT_REQUIRED,
        FURTHER_STUDIES_REQUIRED
    }

    public record Finding(
            String evidenceId,
            String evidenceType,
            String environmentalComponent,
            String metric,
            Object value,
            String units,
            String sourceLayer,
            String method,
            List<String> limitations
    ) {
        public Finding {
            limitations = List.copyOf(limitations);
        }
    }

    /**
     * Older saved routes may contain a list where this schema expects a metadata map.
     * Preserve that payload under a named key instead of failing the whole report request.
     */
    public static final class ObjectMapDeserializer extends ValueDeserializer<Map<String, Object>> {
        @Override
        public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context)
                throws tools.jackson.core.JacksonException {
            JsonNode node = parser.readValueAsTree();
            if (node == null || node.isNull()) return Map.of();
            if (node.isObject()) {
                return context.readValue(node.traverse(context), new TypeReference<Map<String, Object>>() {});
            }
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("legacyArrayValue", context.readValue(node.traverse(context), new TypeReference<Object>() {}));
            return wrapped;
        }
    }
}
