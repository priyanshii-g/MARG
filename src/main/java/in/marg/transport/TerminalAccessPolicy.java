package in.marg.transport;

import in.marg.model.GeoPoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IRC:SP:99-2013 (Expressways) &amp; IRC:SP:84-2019 (National Highways) terminal access
 * classification, scoring, metadata, and GeoJSON feature policy.
 *
 * <p>Key engineering mandates enforced:
 * <ul>
 *   <li><b>EXPRESSWAY (IRC:SP:99-2013 Cl. 2.12 — Full Access Control)</b>:
 *       Strictly prohibits terminal tie-ins on {@link TransportCategory#LOCAL_ROAD}.
 *       Terminals must connect outside urban cores to high-capacity arterial highways
 *       ({@link TransportCategory#NATIONAL_HIGHWAY} / {@link TransportCategory#STATE_HIGHWAY})
 *       via grade-separated {@code TRUMPET_TERMINAL_INTERCHANGE}s with Barrier Toll Plazas
 *       and dedicated 4-Lane Divided {@code TERMINAL_FEEDER_SPUR} connectors.</li>
 *   <li><b>NATIONAL_HIGHWAY (IRC:SP:84-2019 — Partial Access Control)</b>:
 *       Prioritizes NH &gt; SH &gt; MDR arterial tie-ins and strongly penalizes local streets,
 *       while retaining {@link TransportCategory#LOCAL_ROAD} as a practical fallback when no
 *       arterial highway is reasonably accessible.</li>
 * </ul>
 */
public final class TerminalAccessPolicy {

    public static final String CONNECTOR_TYPE_EXPRESSWAY_SPUR = "TERMINAL_FEEDER_SPUR";
    public static final String CONNECTOR_TYPE_HIGHWAY_ACCESS = "TERMINAL_ACCESS_CONNECTOR";

    public static final String JUNCTION_TYPE_EXPRESSWAY_TRUMPET = "TRUMPET_TERMINAL_INTERCHANGE";
    public static final String JUNCTION_TYPE_HIGHWAY_AT_GRADE = "AT_GRADE_CHANNELIZED_JUNCTION";

    public static final double TOLL_PLAZA_MIN_ROW_WIDTH_METERS = 100.0;
    public static final double DIRECT_TIE_IN_THRESHOLD_METERS = 35.0;

    private TerminalAccessPolicy() {}

    /**
     * Returns {@code true} if an existing road of {@code category} is legally permitted as a
     * terminal tie-in for the given {@code facilityMode}.
     */
    public static boolean isCategoryAllowedForTieIn(TransportCategory category, FacilityMode mode) {
        if (category == null || category == TransportCategory.RAILWAY) {
            return false;
        }
        if (mode == FacilityMode.EXPRESSWAY) {
            // IRC:SP:99-2013 Cl. 2.12: Local streets / residential lanes are strictly prohibited.
            return category != TransportCategory.LOCAL_ROAD;
        }
        return true;
    }

    /**
     * Returns {@code true} for primary high-capacity arterial highways (NH / SH) eligible for
     * grade-separated Expressway Service / Terminal Interchanges per IRC:SP:99-2013 Cl. 2.12.2.
     */
    public static boolean isPreferredExpresswayArterial(TransportCategory category) {
        return category != null && category.isHighCapacityInterchangeEligible();
    }

    /**
     * Computes an effective hierarchy-weighted tie-in distance (in meters, lower is better)
     * so tie-in selection prefers higher-order highways over lower-order roads while preserving
     * actual Euclidean distance in the returned tie-in record.
     */
    public static double weightedTieInDistanceMeters(
            double rawDistanceMeters,
            TransportCategory category,
            FacilityMode mode) {
        if (!isCategoryAllowedForTieIn(category, mode)) {
            return Double.POSITIVE_INFINITY;
        }
        double dist = Math.max(0.0, rawDistanceMeters);
        if (mode == FacilityMode.EXPRESSWAY) {
            return switch (category) {
                case NATIONAL_HIGHWAY -> dist;
                case STATE_HIGHWAY -> dist * 1.08 + 150.0;
                // MDR is only an emergency fallback if zero NH/SH corridors exist anywhere nearby
                case MAJOR_DISTRICT_ROAD -> dist * 2.50 + 25_000.0;
                case LOCAL_ROAD, RAILWAY -> Double.POSITIVE_INFINITY;
            };
        }
        if (mode == FacilityMode.NATIONAL_HIGHWAY) {
            // Preference hierarchy: NH > SH > MDR >> LOCAL_ROAD (penalized, fallback only)
            return switch (category) {
                case NATIONAL_HIGHWAY -> dist;
                case STATE_HIGHWAY -> dist * 1.10 + 150.0;
                case MAJOR_DISTRICT_ROAD -> dist * 1.35 + 500.0;
                case LOCAL_ROAD -> dist * 3.00 + 2500.0;
                case RAILWAY -> Double.POSITIVE_INFINITY;
            };
        }
        return dist;
    }

    /**
     * Non-negative preference score penalty for candidate endpoints during outward ring snapping
     * in {@code EndpointSnapService.forCleanRoadTieIn(...)}.
     */
    public static double candidateTieInCategoryPenalty(TransportCategory category, FacilityMode mode) {
        if (category == null || category == TransportCategory.RAILWAY) {
            return 100.0;
        }
        if (mode == FacilityMode.EXPRESSWAY) {
            return switch (category) {
                case NATIONAL_HIGHWAY -> 0.0;
                case STATE_HIGHWAY -> 1.5;
                case MAJOR_DISTRICT_ROAD -> 18.0;
                case LOCAL_ROAD, RAILWAY -> 100.0;
            };
        }
        if (mode == FacilityMode.NATIONAL_HIGHWAY) {
            return switch (category) {
                case NATIONAL_HIGHWAY -> 0.0;
                case STATE_HIGHWAY -> 1.0;
                case MAJOR_DISTRICT_ROAD -> 3.0;
                case LOCAL_ROAD -> 12.0;
                case RAILWAY -> 100.0;
            };
        }
        return 0.0;
    }

    public static String connectorDesignation(FacilityMode mode) {
        return mode == FacilityMode.EXPRESSWAY
                ? CONNECTOR_TYPE_EXPRESSWAY_SPUR
                : CONNECTOR_TYPE_HIGHWAY_ACCESS;
    }

    public static String terminalJunctionType(FacilityMode mode) {
        return mode == FacilityMode.EXPRESSWAY
                ? JUNCTION_TYPE_EXPRESSWAY_TRUMPET
                : JUNCTION_TYPE_HIGHWAY_AT_GRADE;
    }

    public static String ircStandardReference(FacilityMode mode) {
        return mode == FacilityMode.EXPRESSWAY
                ? "IRC:SP:99-2013 Cl. 2.12 (Full Access Control)"
                : "IRC:SP:84-2019 / IRC:SP:73-2018 (Partial Access Control)";
    }

    public static boolean isSnappedOutsideUrbanCore(String snapReason) {
        if (snapReason == null || snapReason.isBlank()) {
            return false;
        }
        String upper = snapReason.toUpperCase(Locale.ROOT);
        return upper.contains("URBAN_CORE")
                || upper.contains("OUTSIDE_CITY_CORE")
                || upper.contains("RESIDENTIAL_SETTLEMENT_COLONY")
                || upper.contains("SETTLEMENT_BOUNDARY");
    }

    public static String formatJunctionLabel(FacilityMode mode, String roadRef, String roadName) {
        String ref = (roadRef != null && !roadRef.isBlank()) ? roadRef.trim() : "Major Highway";
        String name = (roadName != null && !roadName.isBlank()) ? roadName.trim() : "";
        String roadDisplay = (!name.isEmpty() && !name.equalsIgnoreCase(ref))
                ? ref + " (" + name + ")"
                : ref;
        if (mode == FacilityMode.EXPRESSWAY) {
            return "Trumpet Interchange with " + roadDisplay;
        }
        return "Channelized Tie-In with " + roadDisplay;
    }

    public static String formatFeederCorridorSummary(FacilityMode mode, double connectorKm, boolean directTieIn) {
        double roundedKm = round(connectorKm);
        if (mode == FacilityMode.EXPRESSWAY) {
            if (directTieIn || roundedKm <= 0.035) {
                return "Direct Grade-Separated Trumpet Interchange Tie-In (0.0 km spur)";
            }
            return "4-Lane Divided Feeder Spur (Length: " + roundedKm + " km)";
        }
        if (directTieIn || roundedKm <= 0.035) {
            return "Direct Arterial Highway Tie-In (0.0 km connector)";
        }
        return "2/4-Lane Arterial Terminal Access Connector (Length: " + roundedKm + " km)";
    }

    public static Map<String, Object> buildTerminalConnectionEntry(
            String corridorId,
            String roadRef,
            String roadName,
            TransportCategory category,
            GeoPoint tieInPoint,
            GeoPoint userPoint,
            double straightOffsetMeters,
            double connectorKm,
            double directTieInThresholdMeters,
            FacilityMode mode,
            boolean snappedOutsideUrbanCore) {

        FacilityMode effectiveMode = mode != null ? mode : FacilityMode.EXPRESSWAY;
        TransportCategory effectiveCategory = category != null ? category : TransportCategory.NATIONAL_HIGHWAY;
        boolean directTieIn = straightOffsetMeters <= directTieInThresholdMeters;
        boolean isExpressway = effectiveMode == FacilityMode.EXPRESSWAY;
        boolean localStreetViolation = isExpressway && effectiveCategory == TransportCategory.LOCAL_ROAD;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("corridorId", corridorId);
        entry.put("existingRoadRef", roadRef);
        entry.put("existingRoadName", roadName);
        entry.put("category", effectiveCategory.name());
        entry.put("roadCategory", effectiveCategory.name());
        entry.put("tieInPoint", tieInPoint);
        entry.put("userPoint", userPoint);
        entry.put("straightOffsetMeters", round(straightOffsetMeters));
        entry.put("connectorLengthKm", round(connectorKm));
        entry.put("directTieInApplied", directTieIn);
        entry.put("facilityMode", effectiveMode.name());
        entry.put("connectorDesignation", connectorDesignation(effectiveMode));
        entry.put("connectorSpecification", formatFeederCorridorSummary(effectiveMode, connectorKm, directTieIn));
        entry.put("terminalJunctionType", terminalJunctionType(effectiveMode));
        entry.put("terminalJunctionLabel", formatJunctionLabel(effectiveMode, roadRef, roadName));
        entry.put("requiresTollBarrierPlaza", isExpressway);
        if (isExpressway) {
            entry.put("tollPlazaMinRowWidthMeters", TOLL_PLAZA_MIN_ROW_WIDTH_METERS);
            entry.put("tollPlazaSpecification",
                    "Mainline / Trumpet Barrier Toll Plaza (80-120 m RoW, 6-14 lanes, 150-300 m flared approaches per IRC:SP:99-2013)");
        }
        entry.put("ircStandard", ircStandardReference(effectiveMode));
        entry.put("localStreetTieInAllowed", !isExpressway);
        entry.put("localStreetViolation", localStreetViolation);
        entry.put("snappedOutsideUrbanCore", snappedOutsideUrbanCore);
        return entry;
    }

    public static Map<String, Object> buildTerminalConnectionsMetadata(
            Map<String, Object> startConnection,
            Map<String, Object> goalConnection,
            double totalTerminalAccessRoadLengthKm,
            FacilityMode mode) {

        FacilityMode effectiveMode = mode != null ? mode : FacilityMode.EXPRESSWAY;
        boolean isExpressway = effectiveMode == FacilityMode.EXPRESSWAY;

        int localStreetViolations = 0;
        int terminalInterchanges = 0;

        Map<String, Object> map = new LinkedHashMap<>();
        if (startConnection != null) {
            map.put("startRoadConnection", startConnection);
            if (Boolean.TRUE.equals(startConnection.get("localStreetViolation"))) {
                localStreetViolations++;
            }
            if (isExpressway) {
                terminalInterchanges++;
            }
        }
        if (goalConnection != null) {
            map.put("goalRoadConnection", goalConnection);
            if (Boolean.TRUE.equals(goalConnection.get("localStreetViolation"))) {
                localStreetViolations++;
            }
            if (isExpressway) {
                terminalInterchanges++;
            }
        }

        map.put("totalTerminalAccessRoadLengthKm", round(totalTerminalAccessRoadLengthKm));
        map.put("includedInMainlineAndStatistics", true);
        map.put("facilityMode", effectiveMode.name());
        map.put("terminalConnectionPolicy", isExpressway
                ? "IRC:SP:99-2013 Full Access Control: Expressway terminals are excluded from urban cores and local streets; "
                + "tied strictly to NH/SH arterial highways via Trumpet Terminal Interchanges, Barrier Toll Plazas, and 4-Lane Divided Feeder Spurs."
                : "IRC:SP:84-2019 Partial Access Control: National Highway terminals prioritize NH/SH/MDR arterial highways "
                + "while penalizing local streets as fallback only.");
        map.put("localStreetViolationsCount", localStreetViolations);
        map.put("terminalInterchangesCount", terminalInterchanges);
        map.put("expresswayComplianceVerified", !isExpressway || localStreetViolations == 0);
        return map;
    }

    public static void appendTerminalRoadGeoJsonFeatures(
            List<Map<String, Object>> features,
            FacilityMode mode,
            double routeLengthKm,
            String terminalInterchangeFeatureRole,
            TerminalEndpointSpec originSpec,
            TerminalEndpointSpec destinationSpec) {

        FacilityMode effectiveMode = mode != null ? mode : FacilityMode.EXPRESSWAY;
        boolean isExpressway = effectiveMode == FacilityMode.EXPRESSWAY;
        String icRole = (terminalInterchangeFeatureRole != null && !terminalInterchangeFeatureRole.isBlank())
                ? terminalInterchangeFeatureRole
                : "INTERCHANGE";

        if (originSpec != null && originSpec.tieInPoint() != null) {
            appendSingleTerminalFeatures(features, effectiveMode, isExpressway, icRole, true, 0.0, originSpec);
        }
        if (destinationSpec != null && destinationSpec.tieInPoint() != null) {
            appendSingleTerminalFeatures(features, effectiveMode, isExpressway, icRole, false, round(routeLengthKm), destinationSpec);
        }
    }

    private static void appendSingleTerminalFeatures(
            List<Map<String, Object>> features,
            FacilityMode mode,
            boolean isExpressway,
            String icRole,
            boolean isOrigin,
            double chainageKm,
            TerminalEndpointSpec spec) {

        TransportCategory cat = spec.category() != null ? spec.category() : TransportCategory.NATIONAL_HIGHWAY;
        String junctionLabel = formatJunctionLabel(mode, spec.roadRef(), spec.roadName());
        boolean directTieIn = spec.connectorKm() <= 0.035;
        String feederSummary = formatFeederCorridorSummary(mode, spec.connectorKm(), directTieIn);

        // 1. Existing road tie-in marker (ROAD_TIE_IN preserved for frontend & test compatibility)
        Map<String, Object> tieProps = new LinkedHashMap<>();
        tieProps.put("featureRole", "ROAD_TIE_IN");
        tieProps.put("tieInRole", isOrigin ? "ORIGIN_EXISTING_ROAD_TIE_IN" : "DESTINATION_EXISTING_ROAD_TIE_IN");
        tieProps.put("facilityMode", mode.name());
        tieProps.put("existingRoadRef", spec.roadRef());
        tieProps.put("tieInJunctionName", spec.roadName());
        tieProps.put("roadCategory", cat.name());
        tieProps.put("connectorLengthKm", round(spec.connectorKm()));
        tieProps.put("connectorDesignation", connectorDesignation(mode));
        tieProps.put("terminalJunctionType", terminalJunctionType(mode));
        tieProps.put("terminalJunctionLabel", junctionLabel);
        tieProps.put("requiresTollBarrierPlaza", isExpressway);
        tieProps.put("ircStandard", ircStandardReference(mode));
        tieProps.put("continuityRule", isExpressway
                ? "IRC:SP:99-2013: Grade-separated Trumpet Terminal Interchange & Barrier Toll Plaza tying expressway outside urban core to " + cat.name()
                : "IRC:SP:84-2019: Connects National Highway terminal to existing navigable road network (" + cat.name() + ")");
        features.add(Map.of(
                "type", "Feature",
                "properties", tieProps,
                "geometry", Map.of(
                        "type", "Point",
                        "coordinates", List.of(spec.tieInPoint().lon(), spec.tieInPoint().lat()))));

        // 2. For EXPRESSWAY: explicit Trumpet Terminal Interchange + Barrier Toll Plaza marker
        if (isExpressway) {
            Map<String, Object> icProps = new LinkedHashMap<>();
            icProps.put("featureRole", icRole);
            icProps.put("interchangeId", isOrigin ? "IC-TERM-ORIGIN" : "IC-TERM-DESTINATION");
            icProps.put("junctionId", isOrigin ? "INT-TERM-ORIGIN" : "INT-TERM-DESTINATION");
            icProps.put("chainageKm", chainageKm);
            icProps.put("interchangeType", JUNCTION_TYPE_EXPRESSWAY_TRUMPET);
            icProps.put("type", JUNCTION_TYPE_EXPRESSWAY_TRUMPET);
            icProps.put("placementRule", isOrigin
                    ? "EXPRESSWAY_ORIGIN_HIGHWAY_TIE_IN_IRC_SP_99"
                    : "EXPRESSWAY_DESTINATION_HIGHWAY_TIE_IN_IRC_SP_99");
            icProps.put("terminalRole", isOrigin ? "ORIGIN_TERMINAL" : "DESTINATION_TERMINAL");
            icProps.put("terminalInterchange", true);
            icProps.put("connectedCorridorRef", spec.roadRef());
            icProps.put("connectedRoadName", spec.roadName());
            icProps.put("connectedRoadCategory", cat.name());
            icProps.put("servedHubName", junctionLabel);
            icProps.put("connectingHub", junctionLabel);
            icProps.put("requiresTollBarrierPlaza", true);
            icProps.put("tollPlazaMinRowWidthMeters", TOLL_PLAZA_MIN_ROW_WIDTH_METERS);
            icProps.put("designSpeedKmph", 80.0);
            icProps.put("ircStandard", "IRC:SP:99-2013 Cl. 2.12");
            icProps.put("immutableUserOverride", true);
            features.add(Map.of(
                    "type", "Feature",
                    "properties", icProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(spec.tieInPoint().lon(), spec.tieInPoint().lat()))));
        }

        // 3. Terminal Access Connector / Dedicated Expressway Feeder Spur LineString
        // featureRole = TERMINAL_ACCESS_ROAD & connectorRole = ORIGIN_ROAD_CONNECTOR/DESTINATION_ROAD_CONNECTOR
        // preserved for frontend & test compatibility, enriched with IRC Feeder Spur metadata.
        if (spec.connectorPath() != null && spec.connectorPath().size() >= 2 && spec.connectorKm() > 0.035) {
            Map<String, Object> cProps = new LinkedHashMap<>();
            cProps.put("featureRole", "TERMINAL_ACCESS_ROAD");
            cProps.put("connectorRole", isOrigin ? "ORIGIN_ROAD_CONNECTOR" : "DESTINATION_ROAD_CONNECTOR");
            cProps.put("connectorDesignation", connectorDesignation(mode));
            cProps.put("connectorType", connectorDesignation(mode));
            cProps.put("facilityMode", mode.name());
            cProps.put("laneConfiguration", isExpressway
                    ? "4_LANE_DIVIDED_FEEDER_SPUR"
                    : "2_TO_4_LANE_ARTERIAL_CONNECTOR");
            cProps.put("feederCorridorSummary", feederSummary);
            cProps.put("terminalJunctionType", terminalJunctionType(mode));
            cProps.put("terminalJunctionLabel", junctionLabel);
            cProps.put("existingRoadRef", spec.roadRef());
            cProps.put("existingRoadName", spec.roadName());
            cProps.put("existingRoadCategory", cat.name());
            cProps.put("connectorLengthKm", round(spec.connectorKm()));
            cProps.put("ircStandard", ircStandardReference(mode));
            features.add(Map.of(
                    "type", "Feature",
                    "properties", cProps,
                    "geometry", Map.of(
                            "type", "LineString",
                            "coordinates", spec.connectorPath().stream()
                                    .map(p -> List.of(p.lon(), p.lat()))
                                    .toList())));
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record TerminalEndpointSpec(
            String corridorId,
            String roadRef,
            String roadName,
            TransportCategory category,
            GeoPoint tieInPoint,
            List<GeoPoint> connectorPath,
            double connectorKm
    ) {}
}
