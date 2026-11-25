package in.marg.eia;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mode1EnvironmentalSectionAssemblerTest {

    @Test
    void preservesEnvironmentalMetricValuesAndTheirGisProvenance() {
        Map<String, Object> route = Map.of("profile", "COMPOSITE_12_LAYER");
        Map<String, Object> metadata = Map.of(
                "routingResolutionMeters", 250.0,
                "coordinateReferenceSystem", "EPSG:32646",
                "searchWindow", "test-window");
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("diagnosticIsApproximate", true);
        diagnostics.put("sampleSpacingMeters", 125.0);
        diagnostics.put("diagnosticSampleCount", 42);
        diagnostics.put("routeLengthKm", 12.5);
        diagnostics.put("lulcExposureKm", Map.of("FOREST", 2.5, "AGRICULTURE", 10.0));
        diagnostics.put("protectedAreaExposureKm", 0.0);
        diagnostics.put("riverBridgeCrossingSummary", Map.of("totalRiverCrossingsCount", 3));

        Mode1EnvironmentalSection section =
                Mode1EnvironmentalSectionAssembler.assemble(route, metadata, diagnostics);

        assertEquals("mode1_environmental_screening", section.sectionId());
        assertEquals(4, section.findings().size());
        var forest = section.findings().stream()
                .filter(f -> f.metric().equals("sampledRouteLengthByClass:FOREST"))
                .findFirst().orElseThrow();
        assertEquals("GIS-M1-COMPOSITE_12_LAYER-LULCEXPOSUREKM-FOREST", forest.evidenceId());
        assertEquals("GIS_OBSERVATION", forest.evidenceType());
        assertEquals("land_cover", forest.environmentalComponent());
        assertEquals(2.5, forest.value());
        assertEquals("km", forest.units());
        assertEquals("LULC", forest.sourceLayer());
        assertTrue(forest.limitations().getFirst().contains("not a verified field condition"));
        assertEquals(125.0, section.method().get("sampleSpacingMeters"));
        assertTrue(section.templateFields().containsKey("{{environmental_gis_findings}}"));
        var baseline = section.reportSections().stream()
                .filter(s -> s.sectionId().equals("baseline_environment"))
                .findFirst().orElseThrow();
        assertEquals(Mode1EnvironmentalSection.SectionStatus.PARTIAL_SCREENING_ONLY, baseline.status());
        assertEquals(section.findings().stream().map(Mode1EnvironmentalSection.Finding::evidenceId).toList(),
                baseline.evidenceIds());
        var managementPlan = section.reportSections().stream()
                .filter(s -> s.sectionId().equals("management_plan"))
                .findFirst().orElseThrow();
        assertEquals(java.util.List.of("{{environmental_mitigation_action_plan}}", "{{environmental_monitoring_plan}}"),
                managementPlan.templateTokens());
        var finalFindings = section.reportSections().stream()
                .filter(s -> s.sectionId().equals("final_findings"))
                .findFirst().orElseThrow();
        assertEquals(java.util.List.of("{{environmental_final_findings}}", "{{environmental_recommendations}}"),
                finalFindings.templateTokens());
    }

    @Test
    void reportsUnavailableFieldTopicsAndDoesNotInventFindings() {
        Mode1EnvironmentalSection section = Mode1EnvironmentalSectionAssembler.assemble(
                Map.of("profile", "COMPOSITE_12_LAYER"),
                Map.of("routingResolutionMeters", 250.0,
                        "coordinateReferenceSystem", "EPSG:32646",
                        "searchWindow", "window"),
                Map.of("diagnosticIsApproximate", true,
                        "sampleSpacingMeters", 125.0,
                        "diagnosticSampleCount", 10,
                        "routeLengthKm", 2.0));

        assertTrue(section.findings().isEmpty());
        assertTrue(section.notAssessed().contains("Ambient air quality measurements"));
        assertTrue(section.notAssessed().contains("Seasonal biodiversity and wildlife surveys"));
        assertTrue(section.limitations().stream().anyMatch(s -> s.contains("not a legal finding")));
        assertNotNull(section.templateFields().get("{{environmental_evidence_register}}"));
        assertFalse(section.templateFields().containsKey("{{environmental_air_quality_results}}"));
        var legal = section.reportSections().stream()
                .filter(s -> s.sectionId().equals("legal_framework"))
                .findFirst().orElseThrow();
        assertEquals(Mode1EnvironmentalSection.SectionStatus.KNOWLEDGE_BASE_AND_PROJECT_INPUT_REQUIRED,
                legal.status());
        assertTrue(legal.evidenceIds().isEmpty());
        assertTrue(section.reportSections().stream()
                .filter(s -> s.sectionId().equals("further_studies"))
                .findFirst().orElseThrow().instruction().contains("not negative findings"));
    }

    @Test
    void includesDrainageBuildingAndPopulationMetricsInRouteEvidencePack() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("diagnosticIsApproximate", true);
        diagnostics.put("sampleSpacingMeters", 125.0);
        diagnostics.put("diagnosticSampleCount", 20);
        diagnostics.put("routeLengthKm", 8.0);
        diagnostics.put("drainageWetlandExposureKm", Map.of("WETLAND", 1.5));
        diagnostics.put("buildingDensityExposureKm", Map.of("HIGH", 0.4));
        diagnostics.put("populationDensityExposureKm", Map.of("MEDIUM", 2.0));
        diagnostics.put("buildingAndSettlementRowImpactSummary", Map.of(
                "totalPotentiallyAffectedStructuresWithin100mCount", 12,
                "settlementsIntersectingDirectRowCount", 2,
                "minRowEdgeToBuildingMeters", 18.5));
        diagnostics.put("populationAndDemographicExposureSummary", Map.of(
                "populationWithin250mEnvironmentalBuffer", 430));
        diagnostics.put("drainageAndWetlandSummary", Map.of(
                "waterCrossingsSummary", Map.of("totalWaterCrossingsCount", 6),
                "wetlandIntersectionsSummary", Map.of("wetlandsInDirectRowCount", 1,
                        "wetlandAreaInDirectRowHectares", 0.7)));

        Mode1EnvironmentalSection section = Mode1EnvironmentalSectionAssembler.assemble(
                Map.of("profile", "ROW_TEST"),
                Map.of("routingResolutionMeters", 250.0, "coordinateReferenceSystem", "EPSG:32646",
                        "searchWindow", "test"),
                diagnostics);

        assertFinding(section, "sampledRouteLengthByDrainageWetlandClass:WETLAND", "km", "DRAINAGE_WETLANDS");
        assertFinding(section, "sampledRouteLengthByBuildingDensityClass:HIGH", "km", "BUILDING_DENSITY");
        assertFinding(section, "sampledRouteLengthByPopulationDensityClass:MEDIUM", "km", "POPULATION_DENSITY");
        assertFinding(section, "modelledWatercourseAndDrainageCrossings", "crossings", "DRAINAGE_WETLANDS_AND_WATERCOURSES");
        assertFinding(section, "indexedWetlandAreaIntersectingModelledDirectRightOfWay", "ha", "DRAINAGE_WETLANDS_AND_WATERCOURSES");
        assertFinding(section, "modelledPopulationWithin250mEnvironmentalBuffer", "persons (dataset estimate)",
                "POPULATION_AND_DEMOGRAPHICS");
    }

    private void assertFinding(Mode1EnvironmentalSection section, String metric, String units, String layer) {
        var finding = section.findings().stream().filter(item -> item.metric().equals(metric)).findFirst().orElseThrow();
        assertEquals(units, finding.units());
        assertEquals(layer, finding.sourceLayer());
        assertFalse(finding.limitations().isEmpty());
    }

    @Test
    void summarizesRouteGisTopicCoverageWithoutTreatingMissingTopicsAsAbsent() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("lulcExposureKm", Map.of("FOREST", 1.0));
        diagnostics.put("drainageWetlandExposureKm", Map.of("WETLAND", 0.2));
        diagnostics.put("slopeExposureKm", Map.of("STEEP", 0.3));
        diagnostics.put("wildlifeZoneExposureKm", Map.of("ESZ", 0.1));
        diagnostics.put("populationDensityExposureKm", Map.of("MEDIUM", 2.0));
        diagnostics.put("diagnosticIsApproximate", true);
        diagnostics.put("sampleSpacingMeters", 125.0);
        diagnostics.put("diagnosticSampleCount", 20);
        diagnostics.put("routeLengthKm", 8.0);
        Mode1EnvironmentalSection section = Mode1EnvironmentalSectionAssembler.assemble(
                Map.of("profile", "COVERAGE_TEST"),
                Map.of("routingResolutionMeters", 250.0, "coordinateReferenceSystem", "EPSG:32646",
                        "searchWindow", "test"), diagnostics);

        var coverage = new EnvironmentalReportEvidenceAssembler().assessCoverage(section);

        assertEquals(5, coverage.findingCount());
        assertEquals(5, coverage.coveredTopics().size());
        assertTrue(coverage.missingTopics().isEmpty());
    }
}
