package in.marg.eia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Converts existing composite-route diagnostics into a writer-ready environmental section input. */
public final class Mode1EnvironmentalSectionAssembler {
    private static final List<String> NOT_ASSESSED = List.of(
            "Ambient air quality measurements",
            "Ambient noise measurements",
            "Surface-water and groundwater quality measurements",
            "Soil sampling and laboratory results",
            "Field-based flora and fauna inventories",
            "Seasonal biodiversity and wildlife surveys",
            "Construction-phase monitoring results",
            "Project-specific cumulative impact assessment"
    );

    private static final List<String> LIMITATIONS = List.of(
            "This output is preliminary desktop GIS screening for a Mode 1 greenfield route.",
            "Route exposure diagnostics are approximate and sampled at the spacing reported by the backend.",
            "Mapped layer presence or route exposure is not a legal finding, impact determination, or clearance decision.",
            "Zero exposure or missing data in these diagnostics does not establish absence of environmental receptors or impacts.",
            "Field surveys, authoritative dataset review, and qualified environmental assessment remain necessary."
    );

    private static final List<MetricSpec> EXPOSURE_MAPS = List.of(
            new MetricSpec("lulcExposureKm", "land_cover", "sampledRouteLengthByClass", "km", "LULC"),
            new MetricSpec("floodExposureKm", "flood_hazard", "sampledRouteLengthByHazardClass", "km", "FLOOD_HAZARD"),
            new MetricSpec("slopeExposureKm", "terrain", "sampledRouteLengthBySlopeBand", "km", "SLOPE"),
            new MetricSpec("settlementExposureKm", "settlement_receptors", "sampledRouteLengthByExposureClass", "km", "SETTLEMENT_EXPOSURE"),
            new MetricSpec("waterBodyExposureKm", "water_resources", "sampledRouteLengthByWaterClass", "km", "HYDROLOGY_WATER_BODIES"),
            new MetricSpec("wildlifeZoneExposureKm", "wildlife_and_sensitive_areas", "sampledRouteLengthByZoneClass", "km", "WILDLIFE_CORRIDORS_AND_ESZ"),
            new MetricSpec("geotechHazardExposureKm", "terrain_and_geohazards", "sampledRouteLengthByHazardClass", "km", "GEOTECH_SOIL_AND_LANDSLIDE"),
            new MetricSpec("drainageWetlandExposureKm", "water_resources_and_wetlands", "sampledRouteLengthByDrainageWetlandClass", "km", "DRAINAGE_WETLANDS"),
            new MetricSpec("buildingDensityExposureKm", "settlements_and_built_receptors", "sampledRouteLengthByBuildingDensityClass", "km", "BUILDING_DENSITY"),
            new MetricSpec("populationDensityExposureKm", "population_and_community_receptors", "sampledRouteLengthByPopulationDensityClass", "km", "POPULATION_DENSITY")
    );

    private static final List<SummaryMetricSpec> ROW_SUMMARY_METRICS = List.of(
            new SummaryMetricSpec("buildingAndSettlementRowImpactSummary", "totalPotentiallyAffectedStructuresWithin100mCount",
                    "settlements_and_built_receptors", "potentiallyAffectedIndexedStructuresWithinModelledCorridor", "count",
                    "BUILDINGS_AND_SETTLEMENTS", "Modeled right-of-way and construction-zone overlap/proximity analysis.",
                    "Indexed building and settlement records only; not a cadastral survey, confirmed displacement count, or land-acquisition determination."),
            new SummaryMetricSpec("buildingAndSettlementRowImpactSummary", "settlementsIntersectingDirectRowCount",
                    "settlements_and_built_receptors", "indexedSettlementsIntersectingModelledDirectRightOfWay", "settlements",
                    "BUILDINGS_AND_SETTLEMENTS", "Modeled right-of-way footprint intersection.",
                    "Indexed settlement boundaries only; verify with current cadastral and field data."),
            new SummaryMetricSpec("buildingAndSettlementRowImpactSummary", "settlementsIntersectingEnvironmentalBufferCount",
                    "settlements_and_built_receptors", "indexedSettlementsIntersectingModelledEnvironmentalBuffer", "settlements",
                    "BUILDINGS_AND_SETTLEMENTS", "Modeled right-of-way buffer intersection.",
                    "Indexed settlement boundaries only; buffer exposure is not a confirmed impact or displacement."),
            new SummaryMetricSpec("buildingAndSettlementRowImpactSummary", "settlementAreaInDirectRowHectares",
                    "settlements_and_built_receptors", "indexedSettlementAreaIntersectingModelledDirectRightOfWay", "ha",
                    "BUILDINGS_AND_SETTLEMENTS", "Modeled right-of-way footprint intersection.",
                    "Indexed settlement polygons only; verify with current cadastral and field data."),
            new SummaryMetricSpec("buildingAndSettlementRowImpactSummary", "minRowEdgeToBuildingMeters",
                    "settlements_and_built_receptors", "minimumDistanceFromModelledRightOfWayEdgeToIndexedBuilding", "m",
                    "BUILDINGS_AND_SETTLEMENTS", "Nearest-feature distance to indexed building footprints.",
                    "Distance is derived from available mapped building data and is not a site survey."),
            new SummaryMetricSpec("buildingAndSettlementRowImpactSummary", "minRowEdgeToSettlementMeters",
                    "settlements_and_built_receptors", "minimumDistanceFromModelledRightOfWayEdgeToIndexedSettlement", "m",
                    "BUILDINGS_AND_SETTLEMENTS", "Nearest-feature distance to indexed settlement polygons.",
                    "Distance is derived from available mapped settlement data and is not a site survey."),
            new SummaryMetricSpec("populationAndDemographicExposureSummary", "populationWithin250mEnvironmentalBuffer",
                    "population_and_community_receptors", "modelledPopulationWithin250mEnvironmentalBuffer", "persons (dataset estimate)",
                    "POPULATION_AND_DEMOGRAPHICS", "Overlay of modelled route buffer with available population/demographic data.",
                    "Dataset-based estimate only; not a household enumeration, displacement estimate, or social-impact survey."),
            new SummaryMetricSpec("populationAndDemographicExposureSummary", "populationWithin500mSecondaryBuffer",
                    "population_and_community_receptors", "modelledPopulationWithin500mSecondaryBuffer", "persons (dataset estimate)",
                    "POPULATION_AND_DEMOGRAPHICS", "Overlay of modelled route buffer with available population/demographic data.",
                    "Dataset-based estimate only; not a household enumeration, displacement estimate, or social-impact survey."),
            new SummaryMetricSpec("populationAndDemographicExposureSummary", "ruralPopulationWithin250mEnvironmentalBuffer",
                    "population_and_community_receptors", "modelledRuralPopulationWithin250mEnvironmentalBuffer", "persons (dataset estimate)",
                    "POPULATION_AND_DEMOGRAPHICS", "Overlay of modelled route buffer with available population/demographic data.",
                    "Dataset-based estimate only; not a household enumeration, displacement estimate, or social-impact survey."),
            new SummaryMetricSpec("populationAndDemographicExposureSummary", "urbanPopulationWithin250mEnvironmentalBuffer",
                    "population_and_community_receptors", "modelledUrbanPopulationWithin250mEnvironmentalBuffer", "persons (dataset estimate)",
                    "POPULATION_AND_DEMOGRAPHICS", "Overlay of modelled route buffer with available population/demographic data.",
                    "Dataset-based estimate only; not a household enumeration, displacement estimate, or social-impact survey."),
            new SummaryMetricSpec("drainageAndWetlandSummary.waterCrossingsSummary", "totalWaterCrossingsCount",
                    "water_resources_and_wetlands", "modelledWatercourseAndDrainageCrossings", "crossings",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Mapped route/corridor crossing analysis.",
                    "Mapped crossing count only; verify watercourse locations, seasonality, and structures in detailed design and field review."),
            new SummaryMetricSpec("drainageAndWetlandSummary.wetlandIntersectionsSummary", "wetlandsInDirectRowCount",
                    "water_resources_and_wetlands", "indexedWetlandsIntersectingModelledDirectRightOfWay", "wetlands",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Mapped wetland polygon and route right-of-way intersection.",
                    "Based on indexed wetland polygons; field boundary, legal status and seasonal extent require verification."),
            new SummaryMetricSpec("drainageAndWetlandSummary.wetlandIntersectionsSummary", "wetlandsIn100mBufferCount",
                    "water_resources_and_wetlands", "indexedWetlandsWithin100mModelledBuffer", "wetlands",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Mapped wetland polygon and route buffer intersection.",
                    "Based on indexed wetland polygons; field boundary, legal status and seasonal extent require verification."),
            new SummaryMetricSpec("drainageAndWetlandSummary.wetlandIntersectionsSummary", "wetlandsIn250mBufferCount",
                    "water_resources_and_wetlands", "indexedWetlandsWithin250mModelledBuffer", "wetlands",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Mapped wetland polygon and route buffer intersection.",
                    "Based on indexed wetland polygons; field boundary, legal status and seasonal extent require verification."),
            new SummaryMetricSpec("drainageAndWetlandSummary.wetlandIntersectionsSummary", "wetlandAreaInDirectRowHectares",
                    "water_resources_and_wetlands", "indexedWetlandAreaIntersectingModelledDirectRightOfWay", "ha",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Mapped wetland polygon and route right-of-way intersection.",
                    "Area is calculated from indexed wetland polygons; field boundary and legal status require verification."),
            new SummaryMetricSpec("drainageAndWetlandSummary.wetlandIntersectionsSummary", "wetlandAreaIn100mBufferHectares",
                    "water_resources_and_wetlands", "indexedWetlandAreaWithin100mModelledBuffer", "ha",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Mapped wetland polygon and route buffer intersection.",
                    "Area is calculated from indexed wetland polygons; field boundary and legal status require verification."),
            new SummaryMetricSpec("drainageAndWetlandSummary.wetlandIntersectionsSummary", "wetlandAreaIn250mBufferHectares",
                    "water_resources_and_wetlands", "indexedWetlandAreaWithin250mModelledBuffer", "ha",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Mapped wetland polygon and route buffer intersection.",
                    "Area is calculated from indexed wetland polygons; field boundary and legal status require verification."),
            new SummaryMetricSpec("drainageAndWetlandSummary.drainageObstructionRisksSummary", "highOrCriticalObstructionZonesCount",
                    "water_resources_and_wetlands", "modelledHighOrCriticalDrainageObstructionZones", "zones",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Modelled drainage obstruction risk-zone analysis.",
                    "Desktop risk-screening zones only; confirm hydrology, flow paths and engineering design through field studies."),
            new SummaryMetricSpec("drainageAndWetlandSummary.drainageObstructionRisksSummary", "parallelThalwegEncroachmentLengthKm",
                    "water_resources_and_wetlands", "modelledParallelThalwegEncroachmentLength", "km",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Modelled route alignment proximity/overlap with mapped drainage thalwegs.",
                    "Desktop drainage screening only; does not establish flow, flood level or hydraulic performance."),
            new SummaryMetricSpec("drainageAndWetlandSummary.watershedCrossingsSummary", "watershedBoundaryCrossingsCount",
                    "water_resources_and_wetlands", "modelledWatershedBoundaryCrossings", "crossings",
                    "DRAINAGE_WETLANDS_AND_WATERCOURSES", "Mapped watershed boundary intersection.",
                    "Regional watershed screening only; not a project drainage design or hydrological study.")
    );

    private static final Pattern TOKEN_PARTS = Pattern.compile("[^A-Z0-9]+");

    private Mode1EnvironmentalSectionAssembler() {}

    public static Mode1EnvironmentalSection assemble(
            Map<String, Object> routeProperties,
            Map<String, Object> metadata,
            Map<String, Object> diagnostics
    ) {
        if (routeProperties == null || metadata == null || diagnostics == null) {
            throw new IllegalArgumentException("route properties, metadata, and diagnostics are required");
        }

        List<String> findingLimitations = List.of(
                "GIS_OBSERVATION only; interpret as route screening exposure, not a verified field condition.",
                "Approximate sampling; see method.sampleSpacingMeters and method.diagnosticSampleCount."
        );
        List<Mode1EnvironmentalSection.Finding> findings = new ArrayList<>();
        String routeId = stringValue(routeProperties.get("profile"), "MODE1_ROUTE");

        addScalar(findings, routeId, diagnostics, "protectedAreaExposureKm",
                "protected_and_conserved_areas", "sampledRouteLengthWithinMappedProtectedArea",
                "km", "PROTECTED_AREA", findingLimitations);

        for (MetricSpec spec : EXPOSURE_MAPS) {
            Object raw = diagnostics.get(spec.diagnosticKey());
            if (!(raw instanceof Map<?, ?> values)) continue;
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getValue() instanceof Number)) continue;
                findings.add(new Mode1EnvironmentalSection.Finding(
                        evidenceId(routeId, spec.diagnosticKey(), entry.getKey().toString()),
                        "GIS_OBSERVATION",
                        spec.component(),
                        spec.metric() + ":" + entry.getKey(),
                        entry.getValue(),
                        spec.units(),
                        spec.layer(),
                        "Route sampling and class-length accumulation by CompositeRouteMetricsService.",
                        findingLimitations
                ));
            }
        }

        addCrossingCount(findings, routeId, diagnostics, "riverBridgeCrossingSummary",
                "totalRiverCrossingsCount", "water_resources", "mappedRiverAndStreamCrossings",
                "HYDROLOGY_WATER_BODIES", "crossings");
        addCrossingCount(findings, routeId, diagnostics, "wildlifePassageCrossingSummary",
                "totalWildlifeCrossingsCount", "wildlife_and_sensitive_areas", "modelledWildlifePassageCrossings",
                "WILDLIFE_CORRIDORS_AND_ESZ", "crossings");
        for (SummaryMetricSpec spec : ROW_SUMMARY_METRICS) {
            addSummaryMetric(findings, routeId, diagnostics, spec);
        }

        Map<String, Object> method = new LinkedHashMap<>();
        method.put("routeProfile", routeId);
        putIfPresent(method, "routingResolutionMeters", metadata.get("routingResolutionMeters"));
        putIfPresent(method, "coordinateReferenceSystem", metadata.get("coordinateReferenceSystem"));
        putIfPresent(method, "searchWindow", metadata.get("searchWindow"));
        Map<String, Object> routeSummary = new LinkedHashMap<>();
        putIfPresent(routeSummary, "routeVariant", routeProperties.get("profile"));
        putIfPresent(routeSummary, "routeLengthKm", routeProperties.get("routeLengthKm"));
        putIfPresent(routeSummary, "distanceBaselineKm", routeProperties.get("distanceBaselineKm"));
        putIfPresent(routeSummary, "deviationPercent", routeProperties.get("deviationPercent"));
        method.put("routeSummary", routeSummary);
        putIfPresent(method, "diagnosticIsApproximate", diagnostics.get("diagnosticIsApproximate"));
        putIfPresent(method, "sampleSpacingMeters", diagnostics.get("sampleSpacingMeters"));
        putIfPresent(method, "diagnosticSampleCount", diagnostics.get("diagnosticSampleCount"));
        putIfPresent(method, "routeLengthKm", diagnostics.get("routeLengthKm"));

        Map<String, Object> templateFields = new LinkedHashMap<>();
        templateFields.put("{{environmental_methodology}}", method);
        templateFields.put("{{environmental_gis_findings}}", findings);
        templateFields.put("{{environmental_not_assessed}}", NOT_ASSESSED);
        templateFields.put("{{environmental_limitations}}", LIMITATIONS);
        templateFields.put("{{environmental_evidence_register}}", findings);

        List<String> findingIds = findings.stream()
                .map(Mode1EnvironmentalSection.Finding::evidenceId)
                .toList();
        List<Mode1EnvironmentalSection.ReportSection> reportSections = List.of(
                new Mode1EnvironmentalSection.ReportSection(
                        "project_overview", "General and Project Overview",
                        Mode1EnvironmentalSection.SectionStatus.PROJECT_INPUT_REQUIRED,
                        List.of("{{project_and_route_overview}}"), List.of(),
                        "Populate only from verified project metadata and supplied proposal documents."),
                new Mode1EnvironmentalSection.ReportSection(
                        "legal_framework", "Legal and Regulatory Applicability",
                        Mode1EnvironmentalSection.SectionStatus.KNOWLEDGE_BASE_AND_PROJECT_INPUT_REQUIRED,
                        List.of("{{legal_applicability_register}}"), List.of(),
                        "Resolve current jurisdiction-specific applicability from approved legal sources; do not infer clearances."),
                new Mode1EnvironmentalSection.ReportSection(
                        "methods_and_sources", "Assessment Scope, Methods and Sources",
                        Mode1EnvironmentalSection.SectionStatus.GIS_SCREENING_AVAILABLE,
                        List.of("{{environmental_methodology}}"), findingIds,
                        "Preserve CRS, route sampling, diagnostic spacing, sample count and approximation status."),
                new Mode1EnvironmentalSection.ReportSection(
                        "baseline_environment", "Existing Environmental Status and Baseline",
                        Mode1EnvironmentalSection.SectionStatus.PARTIAL_SCREENING_ONLY,
                        List.of("{{environmental_gis_findings}}", "{{environmental_not_assessed}}", "{{environmental_limitations}}"),
                        findingIds,
                        "Use GIS as mapped screening context only; report field measurements and surveys as unavailable unless supplied."),
                new Mode1EnvironmentalSection.ReportSection(
                        "impacts_and_mitigation", "Anticipated Impacts and Mitigation Measures",
                        Mode1EnvironmentalSection.SectionStatus.PARTIAL_SCREENING_ONLY,
                        List.of("{{environmental_impact_assessment_matrix}}"), findingIds,
                        "Describe conditional pathways supported by evidence; distinguish potential impacts from verified effects."),
                new Mode1EnvironmentalSection.ReportSection(
                        "management_plan", "Environmental Mitigation and Management Plan",
                        Mode1EnvironmentalSection.SectionStatus.PROJECT_INPUT_REQUIRED,
                        List.of("{{environmental_mitigation_action_plan}}", "{{environmental_monitoring_plan}}"), List.of(),
                        "Populate actions, owners, timing and costs only from approved project commitments or applicable requirements."),
                new Mode1EnvironmentalSection.ReportSection(
                        "further_studies", "Further Studies and Data Gaps",
                        Mode1EnvironmentalSection.SectionStatus.FURTHER_STUDIES_REQUIRED,
                        List.of("{{environmental_not_assessed}}", "{{environmental_limitations}}"), findingIds,
                        "Convert missing evidence into explicit verification and study needs, not negative findings."),
                new Mode1EnvironmentalSection.ReportSection(
                        "evidence_register", "Environmental Evidence Register",
                        Mode1EnvironmentalSection.SectionStatus.GIS_SCREENING_AVAILABLE,
                        List.of("{{environmental_evidence_register}}"), findingIds,
                        "Retain stable evidence IDs and separate GIS observations from future document claims and guidance."),
                new Mode1EnvironmentalSection.ReportSection(
                        "final_findings", "Findings and Recommendations",
                        Mode1EnvironmentalSection.SectionStatus.PARTIAL_SCREENING_ONLY,
                        List.of("{{environmental_final_findings}}", "{{environmental_recommendations}}"), findingIds,
                        "Conclude only on the supplied evidence and state that this is preliminary screening, not statutory EIA or clearance.")
        );

        return new Mode1EnvironmentalSection(
                "mode1_environmental_screening",
                "GIS-Based Environmental Screening",
                "Preliminary desktop screening of a MARG-generated greenfield route using available GIS layers.",
                method,
                findings,
                NOT_ASSESSED,
                LIMITATIONS,
                reportSections,
                templateFields
        );
    }

    private static void addScalar(
            List<Mode1EnvironmentalSection.Finding> findings,
            String routeId,
            Map<String, Object> diagnostics,
            String key,
            String component,
            String metric,
            String units,
            String layer,
            List<String> limitations
    ) {
        Object value = diagnostics.get(key);
        if (value instanceof Number) {
            findings.add(new Mode1EnvironmentalSection.Finding(
                    evidenceId(routeId, key, "TOTAL"), "GIS_OBSERVATION", component, metric,
                    value, units, layer,
                    "Route sampling and exposure accumulation by CompositeRouteMetricsService.", limitations));
        }
    }

    private static void addCrossingCount(
            List<Mode1EnvironmentalSection.Finding> findings,
            String routeId,
            Map<String, Object> diagnostics,
            String summaryKey,
            String countKey,
            String component,
            String metric,
            String layer,
            String units
    ) {
        Object raw = diagnostics.get(summaryKey);
        if (!(raw instanceof Map<?, ?> summary)) return;
        Object value = summary.get(countKey);
        if (!(value instanceof Number)) return;
        findings.add(new Mode1EnvironmentalSection.Finding(
                evidenceId(routeId, summaryKey, countKey), "GIS_OBSERVATION", component,
                metric, value, units, layer,
                "Crossings inferred by the backend vector-index analysis; verify during detailed design and field review.",
                List.of("Modelled crossing count, not a field-verified impact or final structure schedule.")));
    }

    private static void addSummaryMetric(List<Mode1EnvironmentalSection.Finding> findings, String routeId,
                                         Map<String, Object> diagnostics, SummaryMetricSpec spec) {
        Object current = diagnostics;
        for (String pathPart : spec.summaryPath().split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return;
            current = map.get(pathPart);
        }
        if (!(current instanceof Map<?, ?> summary)) return;
        Object value = summary.get(spec.valueKey());
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) return;
        List<String> limitations = List.of(spec.limitation(),
                "GIS_OBSERVATION only; it is a desktop model output and not a field-verified condition.");
        findings.add(new Mode1EnvironmentalSection.Finding(
                evidenceId(routeId, spec.summaryPath().replace('.', '_') + "_" + spec.valueKey(), "TOTAL"),
                "GIS_OBSERVATION", spec.component(), spec.metric(), value, spec.units(), spec.layer(),
                spec.operation(), limitations));
    }

    private static String evidenceId(String routeId, String metricKey, String className) {
        return "GIS-M1-" + tokenPart(routeId) + "-" + tokenPart(metricKey) + "-" + tokenPart(className);
    }

    private static String tokenPart(String value) {
        return TOKEN_PARTS.matcher(value.toUpperCase()).replaceAll("_").replaceAll("^_+|_+$", "");
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private record MetricSpec(String diagnosticKey, String component, String metric, String units, String layer) {}
    private record SummaryMetricSpec(String summaryPath, String valueKey, String component, String metric,
                                     String units, String layer, String operation, String limitation) {}
}
