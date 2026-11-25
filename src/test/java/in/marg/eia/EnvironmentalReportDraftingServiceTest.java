package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

class EnvironmentalReportDraftingServiceTest {

    @Test
    void normalizesRootArrayOfLlmClaimsIntoDraftBundle() {
        var service = new EnvironmentalReportDraftingService(mock(EnvironmentalKnowledgeIndex.class),
                mock(EnvironmentalLlmClient.class), mock(EnvironmentalReportDocxWriter.class),
                new EnvironmentalReportEvidenceAssembler(), mock(EnvironmentalLegalApplicabilityService.class),
                mock(EnvironmentalReportJobService.class), new ObjectMapper());

        var draft = service.parseDraftBundle("""
                [{"sectionId":"baseline","templateToken":"{{environmental_gis_findings}}",
                  "statement":"Screening summary.","evidenceIds":["GIS-01"],"numericClaims":[]}]
                """);

        assertEquals(1, draft.claims().size());
        assertEquals("baseline", draft.claims().getFirst().sectionId());
        assertTrue(draft.tableRows().isEmpty());
    }

    @Test
    void acceptsArrayRootFromStructuredReportDraftProvider() {
        var service = new EnvironmentalReportDraftingService(mock(EnvironmentalKnowledgeIndex.class),
                mock(EnvironmentalLlmClient.class), mock(EnvironmentalReportDocxWriter.class),
                new EnvironmentalReportEvidenceAssembler(), mock(EnvironmentalLegalApplicabilityService.class),
                mock(EnvironmentalReportJobService.class), new ObjectMapper());

        var draft = service.parseDraftBundle("""
                [{"sectionId":"baseline","templateToken":"{{environmental_gis_findings}}",
                  "statement":"Screening summary.","evidenceIds":["GIS-01"],"numericClaims":[]}]
                """);

        assertEquals(1, draft.claims().size());
        assertEquals("baseline", draft.claims().getFirst().sectionId());
        assertTrue(draft.tableRows().isEmpty());
    }

    @Test
    void acceptsLegacyArrayValuesInOptionalGisMetadataMaps() throws Exception {
        String json = """
                {
                  "projectName":"Route Demo",
                  "studyArea":"Assam",
                  "reportVersion":"v1",
                  "assessmentDate":"2026-11-20",
                  "gisScreening":{
                    "sectionId":"mode1_environmental_screening",
                    "title":"GIS screening",
                    "assessmentBasis":"desktop",
                    "method":["legacy method value"],
                    "findings":[],
                    "notAssessed":[],
                    "limitations":[],
                    "reportSections":[],
                    "templateFields":["legacy template value"]
                  },
                  "projectFacts":[]
                }
                """;

        GenerateReportFromLibraryRequest request = new ObjectMapper()
                .readValue(json, GenerateReportFromLibraryRequest.class);

        assertEquals(List.of("legacy method value"), request.gisScreening().method().get("legacyArrayValue"));
        assertEquals(List.of("legacy template value"),
                request.gisScreening().templateFields().get("legacyArrayValue"));
    }

    @Test
    void infersNumericDeclarationsFromCitedEvidenceAndDropsUnsupportedNumberSentence() {
        var service = new EnvironmentalReportDraftingService(mock(EnvironmentalKnowledgeIndex.class),
                mock(EnvironmentalLlmClient.class), mock(EnvironmentalReportDocxWriter.class),
                new EnvironmentalReportEvidenceAssembler(), mock(EnvironmentalLegalApplicabilityService.class),
                mock(EnvironmentalReportJobService.class), new ObjectMapper());
        EvidenceItem evidence = new EvidenceItem("KB-NUM-01", EvidenceType.GUIDANCE_REFERENCE,
                "A 2.5 km study corridor is described.", "Highway guidance", "guide.pdf", null, null,
                "A 2.5 km study corridor is described.", "page 4", "2025", "India", "Guidance",
                null, null, null, null, null, List.of(), List.of(new NumericValue(new java.math.BigDecimal("2.5"), "km")));

        var grounded = service.groundNumbers("The source describes a 2.5 km study corridor. The project has 9 bridges.",
                List.of(), List.of(evidence));

        assertEquals("The source describes a 2.5 km study corridor.", grounded.text());
        assertEquals(List.of(new NumericValue(new java.math.BigDecimal("2.5"), "km")), grounded.numericClaims());
        GenerateReportRequest request = new GenerateReportRequest("Route Demo", "Agency", "Alt A", "Assam",
                "v1", "2026-11-20", List.of(evidence),
                List.of(new ReportClaim("baseline", "{{environmental_methodology}}", grounded.text(),
                        List.of("KB-NUM-01"), grounded.numericClaims())), null, List.of());
        assertTrue(new EnvironmentalReportValidator().validate(request, Set.of("{{environmental_methodology}}")).isEmpty());
    }

    @Test
    void suppliesMode1GisEvidenceToDraftingAndCarriesItIntoReportJob() throws Exception {
        EnvironmentalKnowledgeIndex index = mock(EnvironmentalKnowledgeIndex.class);
        EnvironmentalLlmClient llm = mock(EnvironmentalLlmClient.class);
        EnvironmentalReportDocxWriter writer = mock(EnvironmentalReportDocxWriter.class);
        EnvironmentalLegalApplicabilityService legal = mock(EnvironmentalLegalApplicabilityService.class);
        EnvironmentalReportJobService jobs = mock(EnvironmentalReportJobService.class);

        var libraryHit = new EnvironmentalKnowledgeIndex.SearchHit("KB-GUIDANCE-01", "DOC-01",
                "Environmental guidance", "guidance.pdf", 3, "DOC-01-P3-C0", "General baseline guidance.",
                EnvironmentalKnowledgeIndex.DocumentClass.INDIAN_GUIDANCE, "2025", "India",
                "General reference", 1.0, List.of());
        var unrelatedProjectHit = new EnvironmentalKnowledgeIndex.SearchHit("KB-PROJECT-01", "DOC-PROJECT-01",
                "Unrelated proposal", "View Form A Part 1.pdf", 2, "DOC-PROJECT-01-P2-C0", "Another project's route details.",
                EnvironmentalKnowledgeIndex.DocumentClass.PROJECT_DOCUMENT, "2025", "India",
                "Project application", 0.99, List.of());
        when(index.search(anyString(), anyInt(), anyBoolean())).thenReturn(List.of(libraryHit, unrelatedProjectHit));
        when(writer.templateTokens()).thenReturn(Set.of("{{environmental_gis_findings}}", "{{environmental_methodology}}",
                "{{project_need_and_assessment_objectives}}", "{{project_and_route_overview}}",
                "{{baseline_status_summary}}"));
        when(llm.isConfigured()).thenReturn(true);
        when(llm.completeJson(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String promptText = invocation.getArgument(2);
            var matcher = java.util.regex.Pattern.compile("USER-M1-PROJECTNEED-[A-F0-9]+").matcher(promptText);
            assertTrue(matcher.find(), "The drafting prompt must include a stable ID for user-provided context.");
            var metadataMatcher = java.util.regex.Pattern.compile("MARG-M1-REPORT-METADATA-[A-F0-9]+").matcher(promptText);
            var gapMatcher = java.util.regex.Pattern.compile("MARG-M1-GAP-[A-F0-9]+").matcher(promptText);
            var gisMatcher = java.util.regex.Pattern.compile("GIS-M1-[A-Z0-9_-]+").matcher(promptText);
            assertTrue(metadataMatcher.find());
            assertTrue(gapMatcher.find());
            assertTrue(gisMatcher.find());
            return "{\"claims\":[{\"sectionId\":\"project_need\",\"templateToken\":\"{{project_need_and_assessment_objectives}}\","
                    + "\"statement\":\"The project need supplied for this assessment is to improve regional access between settlements.\","
                    + "\"evidenceIds\":[\"" + matcher.group() + "\"],\"numericClaims\":[]},"
                    + "{\"sectionId\":\"project_overview\",\"templateToken\":\"{{project_and_route_overview}}\","
                    + "\"statement\":\"This assessment concerns the supplied route and study area.\",\"evidenceIds\":[\""
                    + metadataMatcher.group() + "\"],\"numericClaims\":[]},"
                    + "{\"sectionId\":\"baseline_gaps\",\"templateToken\":\"{{baseline_status_summary}}\","
                    + "\"statement\":\"The submitted package lacks field measurements for listed topics; this does not establish that receptors or impacts are absent.\",\"evidenceIds\":[\""
                    + gapMatcher.group() + "\"],\"numericClaims\":[]},"
                    + "{\"sectionId\":\"baseline_environment\",\"templateToken\":\"{{environmental_gis_findings}}\","
                    + "\"statement\":\"The saved route screening contains a mapped land-cover exposure observation for review.\","
                    + "\"evidenceIds\":[\"" + gisMatcher.group() + "\"],\"numericClaims\":[]}],\"tableRows\":[]}";
        });
        when(legal.assess(any(LegalApplicabilityRequest.class))).thenReturn(
                new LegalApplicabilityAssessment("Route Demo", "2026-11-18", List.of(), List.of(), "Screening only."));
        when(jobs.start(any(GenerateReportRequest.class))).thenReturn(new JobReceipt("job-1", JobState.QUEUED,
                "/status/job-1", null, null, null, List.of(), "queued"));

        EnvironmentalReportDraftingService service = new EnvironmentalReportDraftingService(index, llm, writer,
                new EnvironmentalReportEvidenceAssembler(), legal, jobs, new ObjectMapper());
        Mode1EnvironmentalSection gis = Mode1EnvironmentalSectionAssembler.assemble(
                Map.of("profile", "DEMO_ROUTE", "routeLengthKm", 5.0, "distanceBaselineKm", 4.8,
                        "deviationPercent", 4.2),
                Map.of("routingResolutionMeters", 250.0, "coordinateReferenceSystem", "EPSG:32646",
                        "searchWindow", "demo-region"),
                Map.of("diagnosticIsApproximate", true, "sampleSpacingMeters", 125.0,
                        "diagnosticSampleCount", 42, "routeLengthKm", 5.0,
                        "lulcExposureKm", Map.of("FOREST", 2.5)));
        String findingId = gis.findings().getFirst().evidenceId();

        service.generate(new GenerateReportFromLibraryRequest("Route Demo", "Agency", "Alternative A", "Assam",
                "Assam", "v1", "2026-11-18", gis, List.of(),
                Map.of("projectNeed", "Improve regional access between settlements.")));

        InOrder callOrder = inOrder(jobs, llm);
        callOrder.verify(jobs).verifyPdfRendererAvailable();
        callOrder.verify(llm).completeJson(anyString(), anyString(), anyString());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(eq("environmental-report-draft"), anyString(), prompt.capture());
        assertTrue(prompt.getValue().contains(findingId));
        assertTrue(prompt.getValue().contains("GIS_OBSERVATION"));
        assertTrue(prompt.getValue().contains("not a verified field condition"));
        assertTrue(prompt.getValue().contains("USER-M1-PROJECTNEED-"));
        assertTrue(prompt.getValue().contains("Improve regional access between settlements."));
        assertTrue(prompt.getValue().contains("MARG-M1-REPORT-METADATA-"));
        assertTrue(prompt.getValue().contains("MARG-M1-GAP-"));
        assertTrue(prompt.getValue().contains("does not establish that the receptor, condition or impact is absent"));
        assertTrue(prompt.getValue().contains("MARG-M1-GAP-GIS-COVERAGE-"));
        assertTrue(prompt.getValue().contains("No route-screening observation is included"));
        assertFalse(prompt.getValue().contains("KB-PROJECT-01"),
                "Mode 1 library reports must not retrieve project documents from unrelated proposals.");
        assertFalse(prompt.getValue().contains("View Form A Part 1.pdf"));

        ArgumentCaptor<GenerateReportRequest> report = ArgumentCaptor.forClass(GenerateReportRequest.class);
        verify(jobs).start(report.capture());
        GenerateReportRequest submitted = report.getValue();
        assertNull(submitted.gisScreening(), "GIS must not be assembled a second time by the job service.");
        assertTrue(submitted.evidence().stream().anyMatch(item -> findingId.equals(item.evidenceId())
                && item.type() == EvidenceType.GIS_OBSERVATION));
        assertTrue(submitted.evidence().stream().anyMatch(item -> item.type() == EvidenceType.MARG_INTERPRETATION
                && item.evidenceId().startsWith("MARG-M1-REPORT-METADATA-")));
        assertTrue(submitted.evidence().stream().anyMatch(item -> item.type() == EvidenceType.MARG_INTERPRETATION
                && item.evidenceId().startsWith("MARG-M1-GAP-")));
        assertFalse(submitted.evidence().stream().anyMatch(item -> "KB-GUIDANCE-01".equals(item.evidenceId())),
                "Retrieved but uncited passages must not inflate the final evidence register.");
        assertFalse(submitted.evidence().stream().anyMatch(item -> "KB-PROJECT-01".equals(item.evidenceId())));
        assertTrue(submitted.claims().stream().anyMatch(claim -> claim.evidenceIds().contains(findingId)));
        EvidenceItem userInput = submitted.evidence().stream()
                .filter(item -> item.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT).findFirst().orElseThrow();
        assertTrue(userInput.applicability().contains("not independently verified"));
        assertFalse(submitted.evidence().stream().filter(item -> findingId.equals(item.evidenceId())).findFirst()
                .orElseThrow().limitations().isEmpty());
    }
    @Test
    void rejectsInvalidEvidenceBeforeAnyPaidModelRequest() throws Exception {
        EnvironmentalKnowledgeIndex index = mock(EnvironmentalKnowledgeIndex.class);
        EnvironmentalLlmClient llm = mock(EnvironmentalLlmClient.class);
        EnvironmentalReportDocxWriter writer = mock(EnvironmentalReportDocxWriter.class);
        EnvironmentalLegalApplicabilityService legal = mock(EnvironmentalLegalApplicabilityService.class);
        EnvironmentalReportJobService jobs = mock(EnvironmentalReportJobService.class);
        var invalidHit = new EnvironmentalKnowledgeIndex.SearchHit("invalid evidence id", "DOC-01",
                "Environmental guidance", "guidance.pdf", 3, "DOC-01-P3-C0", "General baseline guidance.",
                EnvironmentalKnowledgeIndex.DocumentClass.INDIAN_GUIDANCE, "2025", "India",
                "General reference", 1.0, List.of());
        when(index.search(anyString(), anyInt(), anyBoolean())).thenReturn(List.of(invalidHit));
        when(writer.templateTokens()).thenReturn(Set.of("{{environmental_gis_findings}}"));
        when(legal.assess(any(LegalApplicabilityRequest.class))).thenReturn(
                new LegalApplicabilityAssessment("Route Demo", "2026-11-18", List.of(), List.of(), "Inventory only."));

        var service = new EnvironmentalReportDraftingService(index, llm, writer,
                new EnvironmentalReportEvidenceAssembler(), legal, jobs, new ObjectMapper());

        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.generate(new GenerateReportFromLibraryRequest("Route Demo", "Agency", "Alt A", "Assam",
                        "v1", "2026-11-18", null, List.of())));

        assertTrue(error.getMessage().contains("no LLM request was made"));
        verify(llm, never()).completeJson(anyString(), anyString(), anyString());
        verify(jobs, never()).verifyPdfRendererAvailable();
    }

    @Test
    void rejectsLibraryOnlyEvidenceBeforeAnyPaidModelRequest() throws Exception {
        EnvironmentalKnowledgeIndex index = mock(EnvironmentalKnowledgeIndex.class);
        EnvironmentalLlmClient llm = mock(EnvironmentalLlmClient.class);
        EnvironmentalReportDocxWriter writer = mock(EnvironmentalReportDocxWriter.class);
        EnvironmentalLegalApplicabilityService legal = mock(EnvironmentalLegalApplicabilityService.class);
        EnvironmentalReportJobService jobs = mock(EnvironmentalReportJobService.class);
        var referenceHit = new EnvironmentalKnowledgeIndex.SearchHit("KB-GUIDANCE-01", "DOC-01",
                "Environmental guidance", "guidance.pdf", 3, "DOC-01-P3-C0", "General baseline guidance.",
                EnvironmentalKnowledgeIndex.DocumentClass.INDIAN_GUIDANCE, "2025", "India",
                "General reference", 1.0, List.of());
        when(index.search(anyString(), anyInt(), anyBoolean())).thenReturn(List.of(referenceHit));
        when(writer.templateTokens()).thenReturn(Set.of("{{environmental_gis_findings}}"));
        when(legal.assess(any(LegalApplicabilityRequest.class))).thenReturn(
                new LegalApplicabilityAssessment("Route Demo", "2026-11-18", List.of(), List.of(), "Inventory only."));

        var service = new EnvironmentalReportDraftingService(index, llm, writer,
                new EnvironmentalReportEvidenceAssembler(), legal, jobs, new ObjectMapper());

        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.generate(new GenerateReportFromLibraryRequest("Route Demo", "Agency", "Alt A", "Assam",
                        "v1", "2026-11-18", null, List.of())));

        assertTrue(error.getMessage().contains("no LLM request was made"));
        assertTrue(error.getMessage().contains("concrete GIS finding"));
        verify(llm, never()).completeJson(anyString(), anyString(), anyString());
        verify(jobs, never()).verifyPdfRendererAvailable();
        verify(jobs, never()).start(any(GenerateReportRequest.class));
    }

    @Test
    void rejectsOperatorContextWithoutSavedRouteGisBeforeAnyPaidModelRequest() throws Exception {
        EnvironmentalKnowledgeIndex index = mock(EnvironmentalKnowledgeIndex.class);
        EnvironmentalLlmClient llm = mock(EnvironmentalLlmClient.class);
        EnvironmentalReportDocxWriter writer = mock(EnvironmentalReportDocxWriter.class);
        EnvironmentalLegalApplicabilityService legal = mock(EnvironmentalLegalApplicabilityService.class);
        EnvironmentalReportJobService jobs = mock(EnvironmentalReportJobService.class);
        when(llm.isConfigured()).thenReturn(true);
        when(index.search(anyString(), anyInt(), anyBoolean())).thenReturn(List.of());
        when(writer.templateTokens()).thenReturn(Set.of("{{environmental_gis_findings}}"));
        when(legal.assess(any(LegalApplicabilityRequest.class))).thenReturn(
                new LegalApplicabilityAssessment("Route Demo", "2026-11-18", List.of(), List.of(), "Inventory only."));

        var service = new EnvironmentalReportDraftingService(index, llm, writer,
                new EnvironmentalReportEvidenceAssembler(), legal, jobs, new ObjectMapper());
        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.generate(new GenerateReportFromLibraryRequest("Route Demo", "Agency", "Alt A", "Assam",
                        "Assam", "v1", "2026-11-18", null, List.of(),
                        Map.of("projectNeed", "Improve regional access."))));

        assertTrue(error.getMessage().contains("no LLM request was made"));
        assertTrue(error.getMessage().contains("no concrete GIS_OBSERVATION findings"));
        verify(llm, never()).completeJson(anyString(), anyString(), anyString());
        verify(jobs, never()).verifyPdfRendererAvailable();
        verify(jobs, never()).start(any(GenerateReportRequest.class));
    }

    @Test
    void rebuildsGisScreeningFromSavedRouteDiagnosticsWhenSnapshotIsMissing() throws Exception {
        EnvironmentalKnowledgeIndex index = mock(EnvironmentalKnowledgeIndex.class);
        EnvironmentalLlmClient llm = mock(EnvironmentalLlmClient.class);
        EnvironmentalReportDocxWriter writer = mock(EnvironmentalReportDocxWriter.class);
        EnvironmentalLegalApplicabilityService legal = mock(EnvironmentalLegalApplicabilityService.class);
        EnvironmentalReportJobService jobs = mock(EnvironmentalReportJobService.class);
        when(llm.isConfigured()).thenReturn(true);
        when(index.search(anyString(), anyInt(), anyBoolean())).thenReturn(List.of());
        when(writer.templateTokens()).thenReturn(Set.of("{{environmental_gis_findings}}"));
        when(legal.assess(any(LegalApplicabilityRequest.class))).thenReturn(
                new LegalApplicabilityAssessment("Route Demo", "2026-11-18", List.of(), List.of(), "Inventory only."));
        when(llm.completeJson(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "claims": [
                    {
                      "templateToken": "{{environmental_gis_findings}}",
                      "statement": "Sampled route crosses 2.5 km of forest land.",
                      "evidenceIds": ["GIS-M1-ALT_A-LULCEXPOSUREKM-FOREST"]
                    }
                  ],
                  "tableRows": []
                }
                """);
        when(jobs.start(any(GenerateReportRequest.class))).thenReturn(new JobReceipt("job-rebuild", JobState.QUEUED,
                "/status", "/docx", "/pdf", null, List.of(), "Queued"));

        var service = new EnvironmentalReportDraftingService(index, llm, writer,
                new EnvironmentalReportEvidenceAssembler(), legal, jobs, new ObjectMapper());

        Map<String, Object> routeMeta = Map.of(
                "routingResolutionMeters", 250.0,
                "coordinateReferenceSystem", "EPSG:32646",
                "routeDiagnostics", Map.of(
                        "diagnosticIsApproximate", true,
                        "sampleSpacingMeters", 125.0,
                        "diagnosticSampleCount", 40,
                        "routeLengthKm", 10.0,
                        "lulcExposureKm", Map.of("FOREST", 2.5)
                )
        );

        JobReceipt receipt = service.generate(new GenerateReportFromLibraryRequest("Route Demo", "Agency", "Alt A", "Assam",
                "Assam", "v1", "2026-11-18", null, List.of(), Map.of(), routeMeta));

        assertEquals("job-rebuild", receipt.jobId());
        verify(jobs).verifyPdfRendererAvailable();
        verify(jobs).start(any(GenerateReportRequest.class));
    }
}
