package in.marg.transport;

import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.building.SettlementClassification;
import in.marg.config.BuildingRoutingProperties;
import in.marg.config.DemographicRoutingProperties;
import in.marg.config.DrainageRoutingProperties;
import in.marg.config.GeotechRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.config.UtilityRoutingProperties;
import in.marg.config.WaterBodyRoutingProperties;
import in.marg.config.WildlifeRoutingProperties;
import in.marg.demographic.DemographicVectorIndex;
import in.marg.demographic.DemographicVulnerabilityTier;
import in.marg.demographic.PopulationDensityRasterProvider;
import in.marg.drainage.DrainageSublayerType;
import in.marg.drainage.DrainageWetlandRasterProvider;
import in.marg.drainage.DrainageWetlandVectorIndex;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.geotech.GeotechHazardTier;
import in.marg.geotech.GeotechLineamentVectorIndex;
import in.marg.geotech.GeotechRasterProvider;
import in.marg.hydrology.RiverHierarchyClass;
import in.marg.hydrology.RiverNetworkVectorIndex;
import in.marg.hydrology.WaterBodyClass;
import in.marg.hydrology.WaterBodyRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.routing.BuildingFootprintCostLayer;
import in.marg.routing.CompositeCostSurface;
import in.marg.routing.CostAssessment;
import in.marg.routing.CostLayer;
import in.marg.routing.CostSurface;
import in.marg.routing.GeotechCostPolicy;
import in.marg.routing.GreenfieldAStarRouter;
import in.marg.routing.SettlementCostLayer;
import in.marg.routing.TransportCostPolicy;
import in.marg.routing.UtilityCostPolicy;
import in.marg.routing.WaterBodyCostPolicy;
import in.marg.routing.WildlifeCostPolicy;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.terrain.SlopeRasterProvider;
import in.marg.utility.UtilityCorridorClass;
import in.marg.utility.UtilityCorridorTier;
import in.marg.utility.UtilityNetworkVectorIndex;
import in.marg.utility.UtilityRasterProvider;
import in.marg.wildlife.WildlifeCorridorTier;
import in.marg.wildlife.WildlifeCorridorVectorIndex;
import in.marg.wildlife.WildlifeRasterProvider;
import in.marg.wildlife.WildlifeZoneClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-mainline interchange placement, dedicated A* feeder/spur road router,
 * grade-separated crossing (ROB / Flyover / VUP) analyzer, and multi-feature GeoJSON builder.
 */
@Service
public class HighwayNetworkPlannerService {

    private static final double SPUR_ROUTING_RESOLUTION_METERS = 250.0;
    private static final double CHAINAGE_DENSIFY_STEP_METERS = 125.0;
    private static final double MAX_INTERCHANGE_SLOPE_DEGREES = 12.0;

    private final TransportNetworkVectorIndex vectorIndex;
    private final TransportNetworkRasterProvider transportRasterProvider;
    private final SlopeRasterProvider slopeRasterProvider;
    private final ProtectedAreaRasterProvider protectedAreaRasterProvider;
    private final GreenfieldAStarRouter router;
    private final CoordinateProjection projection;
    private final TransportRoutingProperties transportProperties;
    private final WaterBodyRasterProvider waterBodyRasterProvider;
    private final RiverNetworkVectorIndex riverVectorIndex;
    private final WaterBodyRoutingProperties waterBodyProperties;
    private final WildlifeRasterProvider wildlifeRasterProvider;
    private final WildlifeCorridorVectorIndex wildlifeVectorIndex;
    private final WildlifeRoutingProperties wildlifeProperties;
    private final GeotechRasterProvider geotechRasterProvider;
    private final GeotechLineamentVectorIndex geotechVectorIndex;
    private final GeotechRoutingProperties geotechProperties;
    private final UtilityRasterProvider utilityRasterProvider;
    private final UtilityNetworkVectorIndex utilityVectorIndex;
    private final UtilityRoutingProperties utilityProperties;
    private final BuildingDensityRasterProvider buildingRasterProvider;
    private final BuildingFootprintVectorIndex buildingVectorIndex;
    private final BuildingRoutingProperties buildingProperties;
    private final PopulationDensityRasterProvider demographicRasterProvider;
    private final DemographicVectorIndex demographicVectorIndex;
    private final DemographicRoutingProperties demographicProperties;
    private final DrainageWetlandRasterProvider drainageRasterProvider;
    private final DrainageWetlandVectorIndex drainageVectorIndex;
    private final DrainageRoutingProperties drainageProperties;
    private final RightOfWayCorridorEngine rowCorridorEngine;

    public HighwayNetworkPlannerService(
            TransportNetworkVectorIndex vectorIndex,
            TransportNetworkRasterProvider transportRasterProvider,
            SlopeRasterProvider slopeRasterProvider,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            TransportRoutingProperties transportProperties) {
        this(
                vectorIndex,
                transportRasterProvider,
                slopeRasterProvider,
                protectedAreaRasterProvider,
                router,
                projection,
                transportProperties,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public HighwayNetworkPlannerService(
            TransportNetworkVectorIndex vectorIndex,
            TransportNetworkRasterProvider transportRasterProvider,
            SlopeRasterProvider slopeRasterProvider,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties) {
        this(
                vectorIndex,
                transportRasterProvider,
                slopeRasterProvider,
                protectedAreaRasterProvider,
                router,
                projection,
                transportProperties,
                waterBodyRasterProvider,
                riverVectorIndex,
                waterBodyProperties,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public HighwayNetworkPlannerService(
            TransportNetworkVectorIndex vectorIndex,
            TransportNetworkRasterProvider transportRasterProvider,
            SlopeRasterProvider slopeRasterProvider,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRasterProvider wildlifeRasterProvider,
            WildlifeCorridorVectorIndex wildlifeVectorIndex,
            WildlifeRoutingProperties wildlifeProperties) {
        this(
                vectorIndex,
                transportRasterProvider,
                slopeRasterProvider,
                protectedAreaRasterProvider,
                router,
                projection,
                transportProperties,
                waterBodyRasterProvider,
                riverVectorIndex,
                waterBodyProperties,
                wildlifeRasterProvider,
                wildlifeVectorIndex,
                wildlifeProperties,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public HighwayNetworkPlannerService(
            TransportNetworkVectorIndex vectorIndex,
            TransportNetworkRasterProvider transportRasterProvider,
            SlopeRasterProvider slopeRasterProvider,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRasterProvider wildlifeRasterProvider,
            WildlifeCorridorVectorIndex wildlifeVectorIndex,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRasterProvider geotechRasterProvider,
            GeotechLineamentVectorIndex geotechVectorIndex,
            GeotechRoutingProperties geotechProperties) {
        this(
                vectorIndex,
                transportRasterProvider,
                slopeRasterProvider,
                protectedAreaRasterProvider,
                router,
                projection,
                transportProperties,
                waterBodyRasterProvider,
                riverVectorIndex,
                waterBodyProperties,
                wildlifeRasterProvider,
                wildlifeVectorIndex,
                wildlifeProperties,
                geotechRasterProvider,
                geotechVectorIndex,
                geotechProperties,
                null,
                null,
                null);
    }

    public HighwayNetworkPlannerService(
            TransportNetworkVectorIndex vectorIndex,
            TransportNetworkRasterProvider transportRasterProvider,
            SlopeRasterProvider slopeRasterProvider,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRasterProvider wildlifeRasterProvider,
            WildlifeCorridorVectorIndex wildlifeVectorIndex,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRasterProvider geotechRasterProvider,
            GeotechLineamentVectorIndex geotechVectorIndex,
            GeotechRoutingProperties geotechProperties,
            UtilityRasterProvider utilityRasterProvider,
            UtilityNetworkVectorIndex utilityVectorIndex,
            UtilityRoutingProperties utilityProperties) {
        this(
                vectorIndex,
                transportRasterProvider,
                slopeRasterProvider,
                protectedAreaRasterProvider,
                router,
                projection,
                transportProperties,
                waterBodyRasterProvider,
                riverVectorIndex,
                waterBodyProperties,
                wildlifeRasterProvider,
                wildlifeCorridorVectorIndex(wildlifeVectorIndex),
                wildlifeProperties,
                geotechRasterProvider,
                geotechVectorIndex,
                geotechProperties,
                utilityRasterProvider,
                utilityVectorIndex,
                utilityProperties,
                new RightOfWayCorridorEngine(projection));
    }

    private static WildlifeCorridorVectorIndex wildlifeCorridorVectorIndex(WildlifeCorridorVectorIndex w) {
        return w;
    }

    public HighwayNetworkPlannerService(
            TransportNetworkVectorIndex vectorIndex,
            TransportNetworkRasterProvider transportRasterProvider,
            SlopeRasterProvider slopeRasterProvider,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRasterProvider wildlifeRasterProvider,
            WildlifeCorridorVectorIndex wildlifeVectorIndex,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRasterProvider geotechRasterProvider,
            GeotechLineamentVectorIndex geotechVectorIndex,
            GeotechRoutingProperties geotechProperties,
            UtilityRasterProvider utilityRasterProvider,
            UtilityNetworkVectorIndex utilityVectorIndex,
            UtilityRoutingProperties utilityProperties,
            RightOfWayCorridorEngine rowCorridorEngine) {
        this(
                vectorIndex,
                transportRasterProvider,
                slopeRasterProvider,
                protectedAreaRasterProvider,
                router,
                projection,
                transportProperties,
                waterBodyRasterProvider,
                riverVectorIndex,
                waterBodyProperties,
                wildlifeRasterProvider,
                wildlifeVectorIndex,
                wildlifeProperties,
                geotechRasterProvider,
                geotechVectorIndex,
                geotechProperties,
                utilityRasterProvider,
                utilityVectorIndex,
                utilityProperties,
                null,
                null,
                null,
                rowCorridorEngine);
    }

    public HighwayNetworkPlannerService(
            TransportNetworkVectorIndex vectorIndex,
            TransportNetworkRasterProvider transportRasterProvider,
            SlopeRasterProvider slopeRasterProvider,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRasterProvider wildlifeRasterProvider,
            WildlifeCorridorVectorIndex wildlifeVectorIndex,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRasterProvider geotechRasterProvider,
            GeotechLineamentVectorIndex geotechVectorIndex,
            GeotechRoutingProperties geotechProperties,
            UtilityRasterProvider utilityRasterProvider,
            UtilityNetworkVectorIndex utilityVectorIndex,
            UtilityRoutingProperties utilityProperties,
            BuildingDensityRasterProvider buildingRasterProvider,
            BuildingFootprintVectorIndex buildingVectorIndex,
            BuildingRoutingProperties buildingProperties,
            RightOfWayCorridorEngine rowCorridorEngine) {
        this(
                vectorIndex,
                transportRasterProvider,
                slopeRasterProvider,
                protectedAreaRasterProvider,
                router,
                projection,
                transportProperties,
                waterBodyRasterProvider,
                riverVectorIndex,
                waterBodyProperties,
                wildlifeRasterProvider,
                wildlifeVectorIndex,
                wildlifeProperties,
                geotechRasterProvider,
                geotechVectorIndex,
                geotechProperties,
                utilityRasterProvider,
                utilityVectorIndex,
                utilityProperties,
                buildingRasterProvider,
                buildingVectorIndex,
                buildingProperties,
                null,
                null,
                null,
                rowCorridorEngine);
    }

    public HighwayNetworkPlannerService(
            TransportNetworkVectorIndex vectorIndex,
            TransportNetworkRasterProvider transportRasterProvider,
            SlopeRasterProvider slopeRasterProvider,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRasterProvider wildlifeRasterProvider,
            WildlifeCorridorVectorIndex wildlifeVectorIndex,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRasterProvider geotechRasterProvider,
            GeotechLineamentVectorIndex geotechVectorIndex,
            GeotechRoutingProperties geotechProperties,
            UtilityRasterProvider utilityRasterProvider,
            UtilityNetworkVectorIndex utilityVectorIndex,
            UtilityRoutingProperties utilityProperties,
            BuildingDensityRasterProvider buildingRasterProvider,
            BuildingFootprintVectorIndex buildingVectorIndex,
            BuildingRoutingProperties buildingProperties,
            PopulationDensityRasterProvider demographicRasterProvider,
            DemographicVectorIndex demographicVectorIndex,
            DemographicRoutingProperties demographicProperties,
            RightOfWayCorridorEngine rowCorridorEngine) {
        this(
                vectorIndex,
                transportRasterProvider,
                slopeRasterProvider,
                protectedAreaRasterProvider,
                router,
                projection,
                transportProperties,
                waterBodyRasterProvider,
                riverVectorIndex,
                waterBodyProperties,
                wildlifeRasterProvider,
                wildlifeVectorIndex,
                wildlifeProperties,
                geotechRasterProvider,
                geotechVectorIndex,
                geotechProperties,
                utilityRasterProvider,
                utilityVectorIndex,
                utilityProperties,
                buildingRasterProvider,
                buildingVectorIndex,
                buildingProperties,
                demographicRasterProvider,
                demographicVectorIndex,
                demographicProperties,
                null,
                null,
                null,
                rowCorridorEngine);
    }

    @Autowired
    public HighwayNetworkPlannerService(
            TransportNetworkVectorIndex vectorIndex,
            TransportNetworkRasterProvider transportRasterProvider,
            SlopeRasterProvider slopeRasterProvider,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRasterProvider wildlifeRasterProvider,
            WildlifeCorridorVectorIndex wildlifeVectorIndex,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRasterProvider geotechRasterProvider,
            GeotechLineamentVectorIndex geotechVectorIndex,
            GeotechRoutingProperties geotechProperties,
            UtilityRasterProvider utilityRasterProvider,
            UtilityNetworkVectorIndex utilityVectorIndex,
            UtilityRoutingProperties utilityProperties,
            BuildingDensityRasterProvider buildingRasterProvider,
            BuildingFootprintVectorIndex buildingVectorIndex,
            BuildingRoutingProperties buildingProperties,
            PopulationDensityRasterProvider demographicRasterProvider,
            DemographicVectorIndex demographicVectorIndex,
            DemographicRoutingProperties demographicProperties,
            DrainageWetlandRasterProvider drainageRasterProvider,
            DrainageWetlandVectorIndex drainageVectorIndex,
            DrainageRoutingProperties drainageProperties,
            RightOfWayCorridorEngine rowCorridorEngine) {
        this.vectorIndex = vectorIndex;
        this.transportRasterProvider = transportRasterProvider;
        this.slopeRasterProvider = slopeRasterProvider;
        this.protectedAreaRasterProvider = protectedAreaRasterProvider;
        this.router = router;
        this.projection = projection;
        this.transportProperties = transportProperties;
        this.waterBodyRasterProvider = waterBodyRasterProvider;
        this.riverVectorIndex = riverVectorIndex;
        this.waterBodyProperties = waterBodyProperties;
        this.wildlifeRasterProvider = wildlifeRasterProvider;
        this.wildlifeVectorIndex = wildlifeVectorIndex;
        this.wildlifeProperties = wildlifeProperties;
        this.geotechRasterProvider = geotechRasterProvider;
        this.geotechVectorIndex = geotechVectorIndex;
        this.geotechProperties = geotechProperties;
        this.utilityRasterProvider = utilityRasterProvider;
        this.utilityVectorIndex = utilityVectorIndex;
        this.utilityProperties = utilityProperties;
        this.buildingRasterProvider = buildingRasterProvider;
        this.buildingVectorIndex = buildingVectorIndex;
        this.buildingProperties = buildingProperties;
        this.demographicRasterProvider = demographicRasterProvider;
        this.demographicVectorIndex = demographicVectorIndex;
        this.demographicProperties = demographicProperties;
        this.drainageRasterProvider = drainageRasterProvider;
        this.drainageVectorIndex = drainageVectorIndex;
        this.drainageProperties = drainageProperties;
        this.rowCorridorEngine = rowCorridorEngine != null
                ? rowCorridorEngine
                : new RightOfWayCorridorEngine(projection);
    }

    public RightOfWayCorridorEngine rowCorridorEngine() {
        return rowCorridorEngine;
    }

    public NetworkPlanResult planNetwork(
            StudyRegion fullRegion,
            List<GeoPoint> mainline,
            HubEvaluationService.EvaluationResult hubEvaluation,
            List<CostLayer> terrainAndSocialSpurLayers) {

        double mainlineLengthKm = router.routeLengthKm(mainline);
        List<TransportNetworkVectorIndex.RouteCrossing> allCrossings =
                vectorIndex.findRouteCrossings(mainline);
        List<ChainageSample> chainageSamples = densifyMainline(mainline);

        FacilityMode mode = hubEvaluation.facilityMode();
        double minSpacingKm = hubEvaluation.minInterchangeSpacingKmApplied();
        TransportCostPolicy policy = transportProperties.toPolicy();

        // Build spur cost surface: respects Protected Areas, Slope <= 30 deg, Flood, LULC,
        // Settlement buffers/cores, and Railway ROB crossings, while permitting tie-in onto target roads.
        List<CostLayer> spurLayers = new ArrayList<>(terrainAndSocialSpurLayers);
        spurLayers.add(buildRailwayOnlySpurLayer(policy));
        CompositeCostSurface spurSurface = new CompositeCostSurface(spurLayers);
        CostSurface denseColonyFallbackSpurSurface = spurSurface.withDenseColonyLastResortMode();
        CostSurface geoHazardFallbackSpurSurface = spurSurface.withAllGeoHazardLastResortMode();

        List<PlannedInterchange> interchanges = new ArrayList<>();
        List<PlannedFeederSpur> feederSpurs = new ArrayList<>();
        Set<Integer> crossingIndicesPromotedToInterchange = new LinkedHashSet<>();

        double activeTrafficPcuHr = hubEvaluation.baseThroughTrafficPcuHr();
        int activeServedPopulation = 0;

        // Process accepted hub decisions in order (Immutable Overrides first, then HVI-ranked autonomous)
        List<HubEvaluationService.HubDecision> acceptedDecisions = hubEvaluation.decisions().stream()
                .filter(HubEvaluationService.HubDecision::accepted)
                .toList();

        for (HubEvaluationService.HubDecision decision : acceptedDecisions) {
            RegionalHub hub = decision.hub();
            boolean immutable = decision.immutableOverride();

            InterchangeCandidate chosen = selectInterchangeLocation(
                    hub,
                    immutable,
                    minSpacingKm,
                    mainlineLengthKm,
                    allCrossings,
                    chainageSamples,
                    interchanges);

            if (chosen == null) {
                continue;
            }

            if (chosen.crossingIndex() != null) {
                crossingIndicesPromotedToInterchange.add(chosen.crossingIndex());
            }

            String interchangeId = "IC-" + (interchanges.size() + 1) + "-" + hub.hubId().replace("HUB-", "");
            PlannedInterchange ic = new PlannedInterchange(
                    interchangeId,
                    chosen.point(),
                    round(chosen.chainageKm()),
                    chosen.interchangeType(),
                    chosen.placementRule(),
                    chosen.connectedRoadRef(),
                    hub.hubId(),
                    hub.name(),
                    immutable);
            interchanges.add(ic);

            GeoPoint spurTargetTieIn = resolveDryStableHubTieIn(fullRegion, hub, spurSurface, mode);
            List<GeoPoint> spurPath = routeDedicatedSpur(
                    fullRegion,
                    chosen.point(),
                    spurTargetTieIn,
                    spurSurface,
                    denseColonyFallbackSpurSurface,
                    geoHazardFallbackSpurSurface);
            if (!spurPath.isEmpty()) {
                double spurLengthKm = router.routeLengthKm(spurPath);
                feederSpurs.add(new PlannedFeederSpur(
                        "SPUR-" + hub.hubId().replace("HUB-", ""),
                        interchangeId,
                        hub,
                        spurPath,
                        round(spurLengthKm),
                        decision.inducedPeakTrafficPcuHr(),
                        decision.hvi(),
                        immutable,
                        decision.status(),
                        decision.reason()));
            }

            activeTrafficPcuHr += decision.inducedPeakTrafficPcuHr();
            activeServedPopulation += hub.catchmentPopulation();
        }

        // Remaining crossings (railways + non-interchange roads) require ROB / Flyover / VUP without ramps
        List<GradeSeparatedCrossingStructure> gradeSeparatedStructures = new ArrayList<>();
        int railwayRobCount = 0;
        int roadFlyoverCount = 0;
        double sumCrossingAnglesDeg = 0.0;

        for (int idx = 0; idx < allCrossings.size(); idx++) {
            TransportNetworkVectorIndex.RouteCrossing rc = allCrossings.get(idx);
            sumCrossingAnglesDeg += rc.crossingAngleDegrees();

            if (crossingIndicesPromotedToInterchange.contains(idx)
                    && rc.category() != TransportCategory.RAILWAY) {
                continue;
            }

            // In NATIONAL_HIGHWAY mode, road crossings are at-grade intersections (no elevated bridge required).
            // Only RAILWAY crossings require mandatory grade-separated ROB.
            // In EXPRESSWAY mode, ALL non-interchange crossings (railway + highways + district + local roads)
            // strictly require an elevated grade-separated structure (ROB / Flyover / VUP / LVUP).
            if (mode == FacilityMode.NATIONAL_HIGHWAY && rc.category() != TransportCategory.RAILWAY) {
                continue;
            }

            if (rc.category() == TransportCategory.RAILWAY) {
                railwayRobCount++;
            } else {
                roadFlyoverCount++;
            }

            double skewMultiplier = policy.skewBridgeMultiplier(rc.sinAngle());
            double structureCostKmEq = policy.crossingStructureCostKmEq(rc.category(), rc.sinAngle());

            gradeSeparatedStructures.add(new GradeSeparatedCrossingStructure(
                    "GS-" + (gradeSeparatedStructures.size() + 1) + "-" + rc.corridorRef(),
                    rc.point(),
                    round(rc.chainageKm()),
                    rc.corridorId(),
                    rc.corridorRef(),
                    rc.corridorName(),
                    rc.category(),
                    rc.category().defaultCrossingStructureType(),
                    round(rc.crossingAngleDegrees()),
                    round(skewMultiplier),
                    round(structureCostKmEq)));
        }

        double avgCrossingAngleDeg = allCrossings.isEmpty()
                ? 90.0
                : round(sumCrossingAnglesDeg / allCrossings.size());

        // Perennial River & Stream Bridge Structures along mainline
        List<PlannedRiverBridge> riverBridges = new ArrayList<>();
        double totalRiverBridgeSpanMeters = 0.0;
        double avgRiverCrossingAngleDeg = 90.0;
        if (riverVectorIndex != null) {
            WaterBodyCostPolicy waterPolicy = waterBodyProperties != null
                    ? waterBodyProperties.toPolicy()
                    : WaterBodyCostPolicy.defaultPolicy();
            List<RiverNetworkVectorIndex.RouteRiverCrossing> riverCrossings =
                    riverVectorIndex.findRouteRiverCrossings(mainline);
            double sumRiverAngles = 0.0;
            for (RiverNetworkVectorIndex.RouteRiverCrossing rrc : riverCrossings) {
                sumRiverAngles += rrc.crossingAngleDegrees();
                totalRiverBridgeSpanMeters += rrc.effectiveSkewSpanMeters();
                double hydraulicSkewMult = waterPolicy.hydraulicSkewMultiplier(rrc.sinAngle());
                double bridgeCostKmEq = waterPolicy.riverBridgeCostKmEq(rrc.riverClass(), rrc.sinAngle());
                riverBridges.add(new PlannedRiverBridge(
                        "RB-" + (riverBridges.size() + 1) + "-" + rrc.riverId().replace("RIV-", ""),
                        rrc.point(),
                        round(rrc.chainageKm()),
                        rrc.riverId(),
                        rrc.riverName(),
                        rrc.riverClass(),
                        rrc.basin(),
                        rrc.riverClass().bridgeStructureType(),
                        round(rrc.designChannelWidthMeters()),
                        round(rrc.effectiveSkewSpanMeters()),
                        round(rrc.crossingAngleDegrees()),
                        round(hydraulicSkewMult),
                        round(bridgeCostKmEq)));
            }
            if (!riverCrossings.isEmpty()) {
                avgRiverCrossingAngleDeg = round(sumRiverAngles / riverCrossings.size());
            }
        }

        // Wildlife Migration Corridor Passages (Elevated Wildlife Viaducts & Animal Underpasses)
        List<PlannedWildlifePassage> wildlifePassages = new ArrayList<>();
        double totalWildlifePassageSpanMeters = 0.0;
        double avgWildlifeCrossingAngleDeg = 90.0;
        if (wildlifeVectorIndex != null) {
            WildlifeCostPolicy wildlifePolicy = wildlifeProperties != null
                    ? wildlifeProperties.toPolicy()
                    : WildlifeCostPolicy.defaultPolicy();
            List<WildlifeCorridorVectorIndex.RouteWildlifeCrossing> wildlifeCrossings =
                    wildlifeVectorIndex.findRouteCorridorCrossings(mainline);
            double sumWildlifeAngles = 0.0;
            for (WildlifeCorridorVectorIndex.RouteWildlifeCrossing rwc : wildlifeCrossings) {
                sumWildlifeAngles += rwc.crossingAngleDegrees();
                totalWildlifePassageSpanMeters += rwc.recommendedPassageSpanMeters();
                double barrierSkewMult = wildlifePolicy.barrierSkewMultiplier(rwc.sinAngle());
                double passageCostKmEq = wildlifePolicy.wildlifePassageCostKmEq(
                        rwc.corridorTier(), rwc.sinAngle());
                wildlifePassages.add(new PlannedWildlifePassage(
                        "WP-" + (wildlifePassages.size() + 1) + "-" + rwc.corridorId().replace("WLC-", ""),
                        rwc.point(),
                        round(rwc.chainageKm()),
                        rwc.corridorId(),
                        rwc.corridorName(),
                        rwc.corridorTier(),
                        rwc.targetSpecies(),
                        rwc.corridorTier().passageStructureType(),
                        round(rwc.designCorridorWidthMeters()),
                        round(rwc.recommendedPassageSpanMeters()),
                        round(rwc.minimumClearHeightMeters()),
                        round(rwc.crossingAngleDegrees()),
                        round(barrierSkewMult),
                        round(passageCostKmEq)));
            }
            if (!wildlifeCrossings.isEmpty()) {
                avgWildlifeCrossingAngleDeg = round(sumWildlifeAngles / wildlifeCrossings.size());
            }
        }

        // Geotechnical Fault/Landslide Scarp & Liquefaction Trough Mitigation Works
        List<PlannedGeotechMitigation> geotechMitigations = new ArrayList<>();
        double totalGeotechStabilizationMeters = 0.0;
        double avgGeotechCrossingAngleDeg = 90.0;
        if (geotechVectorIndex != null) {
            GeotechCostPolicy geotechPolicy = geotechProperties != null
                    ? geotechProperties.toPolicy()
                    : GeotechCostPolicy.defaultPolicy();
            List<GeotechLineamentVectorIndex.RouteGeotechCrossing> geotechCrossings =
                    geotechVectorIndex.findRouteLineamentCrossings(mainline);
            double sumGeotechAngles = 0.0;
            for (GeotechLineamentVectorIndex.RouteGeotechCrossing rgc : geotechCrossings) {
                sumGeotechAngles += rgc.crossingAngleDegrees();
                totalGeotechStabilizationMeters += rgc.recommendedStabilizationLengthMeters();
                double scarpSkewMult = geotechPolicy.scarpSkewMultiplier(rgc.sinAngle());
                double mitigationCostKmEq = geotechPolicy.geotechMitigationCostKmEq(
                        rgc.hazardTier(), rgc.sinAngle());
                geotechMitigations.add(new PlannedGeotechMitigation(
                        "GM-" + (geotechMitigations.size() + 1) + "-" + rgc.lineamentId().replace("GEO-", ""),
                        rgc.point(),
                        round(rgc.chainageKm()),
                        rgc.lineamentId(),
                        rgc.lineamentName(),
                        rgc.hazardTier(),
                        rgc.lithologyAndSoil(),
                        rgc.engineeringHazard(),
                        rgc.hazardTier().mitigationStructureType(),
                        round(rgc.designHazardWidthMeters()),
                        round(rgc.recommendedStabilizationLengthMeters()),
                        round(rgc.crossingAngleDegrees()),
                        round(scarpSkewMult),
                        round(mitigationCostKmEq)));
            }
            if (!geotechCrossings.isEmpty()) {
                avgGeotechCrossingAngleDeg = round(sumGeotechAngles / geotechCrossings.size());
            }
        }

        // Overhead High-Voltage Power Transmission Line Crossings & Protective Works
        List<PlannedUtilityCrossing> utilityCrossings = new ArrayList<>();
        double totalUtilityProtectiveSpanMeters = 0.0;
        double avgUtilityCrossingAngleDeg = 90.0;
        if (utilityVectorIndex != null) {
            UtilityCostPolicy utilityPolicy = utilityProperties != null
                    ? utilityProperties.toPolicy()
                    : UtilityCostPolicy.defaultPolicy();
            List<UtilityNetworkVectorIndex.RouteUtilityCrossing> rucList =
                    utilityVectorIndex.findRouteUtilityCrossings(mainline);
            double sumUtilityAngles = 0.0;
            for (UtilityNetworkVectorIndex.RouteUtilityCrossing ruc : rucList) {
                sumUtilityAngles += ruc.crossingAngleDegrees();
                totalUtilityProtectiveSpanMeters += ruc.recommendedProtectiveSpanMeters();
                double skewMult = utilityPolicy.crossingSkewMultiplier(ruc.sinAngle());
                double crossingCostKmEq = utilityPolicy.utilityCrossingProtectionCostKmEq(
                        ruc.tier(), ruc.sinAngle(), ruc.nearestTowerDistanceMeters());
                utilityCrossings.add(new PlannedUtilityCrossing(
                        "UC-" + (utilityCrossings.size() + 1) + "-" + ruc.lineId().replace("PWR-", ""),
                        ruc.point(),
                        round(ruc.chainageKm()),
                        ruc.lineId(),
                        ruc.lineName(),
                        ruc.tier(),
                        ruc.voltageKv(),
                        ruc.circuit(),
                        ruc.tier().crossingProtectionStructureType(),
                        round(ruc.designRowWidthMeters()),
                        round(ruc.minimumConductorClearanceMeters()),
                        round(ruc.recommendedProtectiveSpanMeters()),
                        round(ruc.crossingAngleDegrees()),
                        round(skewMult),
                        round(ruc.nearestTowerDistanceMeters()),
                        round(crossingCostKmEq)));
            }
            if (!rucList.isEmpty()) {
                avgUtilityCrossingAngleDeg = round(sumUtilityAngles / rucList.size());
            }
        }

        double totalSpurLengthKm = round(
                feederSpurs.stream().mapToDouble(PlannedFeederSpur::spurLengthKm).sum());
        double finalVcRatio = round(activeTrafficPcuHr / hubEvaluation.designCapacityPcuHr());
        String finalLos = HubEvaluationService.classifyLevelOfService(finalVcRatio);
        boolean overCapacity = finalVcRatio > hubEvaluation.maxVcRatioCapApplied() + 1e-9;

        List<List<GeoPoint>> spurPaths = feederSpurs.stream()
                .map(PlannedFeederSpur::path)
                .toList();
        List<GeoPoint> icPoints = interchanges.stream()
                .map(PlannedInterchange::point)
                .toList();
        RightOfWayCorridorEngine.CorridorFootprint rowCorridorFootprint =
                rowCorridorEngine.buildCorridorFootprint(mainline, mode, spurPaths, icPoints);

        List<PlannedSettlementConflictZone> settlementConflictZones = new ArrayList<>();
        int buildingsInDirectRowCount = 0;
        int settlementsIntersectingDirectRowCount = 0;
        if (buildingVectorIndex != null) {
            BuildingFootprintVectorIndex.RouteBuildingAndSettlementImpact rowImpact =
                    buildingVectorIndex.evaluateCorridorFootprintImpact(mainline, rowCorridorFootprint);
            buildingsInDirectRowCount = rowImpact.buildingsInDirectRowCount();
            settlementsIntersectingDirectRowCount = rowImpact.settlementsIntersectingDirectRowCount();
            for (BuildingFootprintVectorIndex.RouteSettlementConflict rsc : rowImpact.settlementConflicts()) {
                settlementConflictZones.add(new PlannedSettlementConflictZone(
                        "SCZ-" + (settlementConflictZones.size() + 1) + "-" + rsc.settlementId(),
                        rsc.point(),
                        rsc.chainageKm(),
                        rsc.settlementId(),
                        rsc.settlementName(),
                        rsc.settlementClassification(),
                        rsc.settlementSubtype(),
                        rsc.district(),
                        rsc.builtUpDensityBuildingsPerHa(),
                        rsc.builtUpDensityClass(),
                        rsc.buildingsInDirectRowCount(),
                        rsc.buildingsInConstructionZoneCount(),
                        rsc.buildingsInDirectRowByType(),
                        rsc.affectedDirectRowFootprintSqMeters(),
                        rsc.areaInDirectRowHectares(),
                        rsc.areaInConstructionZoneHectares(),
                        rsc.distanceToCenterlineMeters(),
                        rsc.distanceToRowEdgeMeters(),
                        rsc.recommendedEngineeringMitigation(),
                        rsc.conflictCostKmEquivalent()));
            }
        }

        List<PlannedDemographicExposureZone> demographicExposureZones = new ArrayList<>();
        int populationWithinDirectRow = 0;
        int estimatedDirectlyDisplacedPopulation = 0;
        int populationWithin250mEnvironmentalBuffer = 0;
        int populationWithin500mSecondaryBuffer = 0;
        if (demographicVectorIndex != null) {
            DemographicVectorIndex.RouteDemographicImpact demoImpact =
                    demographicVectorIndex.evaluateCorridorFootprintDemographicImpact(
                            mainline, rowCorridorFootprint, buildingVectorIndex);
            populationWithinDirectRow = demoImpact.populationWithinDirectRow();
            estimatedDirectlyDisplacedPopulation = demoImpact.estimatedDirectlyDisplacedPopulation();
            populationWithin250mEnvironmentalBuffer = demoImpact.populationWithin250mEnvironmentalBuffer();
            populationWithin500mSecondaryBuffer = demoImpact.populationWithin500mSecondaryBuffer();
            for (DemographicVectorIndex.RouteDemographicExposureZone rdez : demoImpact.exposureZones()) {
                demographicExposureZones.add(new PlannedDemographicExposureZone(
                        "DEZ-" + (demographicExposureZones.size() + 1) + "-" + rdez.tractId(),
                        rdez.point(),
                        rdez.chainageKm(),
                        rdez.tractId(),
                        rdez.settlementId(),
                        rdez.settlementName(),
                        rdez.district(),
                        rdez.settlementClassification().name(),
                        rdez.settlementClassification().name(),
                        rdez.totalSettlementPopulation(),
                        0,
                        0,
                        rdez.populationDensityPerSqKm(),
                        rdez.populationDensityClass().name(),
                        rdez.vulnerabilityTier(),
                        rdez.socialVulnerabilityIndex(),
                        rdez.populationWithinDirectRow(),
                        rdez.populationWithin100mBuffer(),
                        rdez.populationWithin250mBuffer(),
                        rdez.populationWithin500mBuffer(),
                        rdez.estimatedDirectlyDisplacedPopulation(),
                        0,
                        Math.max(0, rdez.populationWithin250mBuffer() - rdez.estimatedDirectlyDisplacedPopulation()),
                        rdez.vulnerableAgeExposedWithin250m(),
                        rdez.scheduledTribeAndCastesExposedWithin250m(),
                        rdez.distanceToCenterlineMeters(),
                        rdez.distanceToRowEdgeMeters(),
                        rdez.recommendedSocialMitigationMeasure(),
                        rdez.exposureCostIndex()));
            }
        }

        List<PlannedDrainageStructure> drainageStructures = new ArrayList<>();
        List<PlannedWetlandMitigationZone> wetlandMitigationZones = new ArrayList<>();
        int totalWaterCrossingsCount = 0;
        int wetlandsInDirectRowCount = 0;
        double wetlandAreaInDirectRowHectares = 0.0;
        int watershedBoundaryCrossingsCount = 0;
        double totalDrainageSpanMeters = 0.0;
        if (drainageVectorIndex != null) {
            DrainageWetlandVectorIndex.RouteDrainageWetlandImpact dwImpact =
                    drainageVectorIndex.evaluateCorridorDrainageAndWetlandImpact(mainline, rowCorridorFootprint);
            totalWaterCrossingsCount = dwImpact.totalWaterCrossingsCount();
            wetlandsInDirectRowCount = dwImpact.wetlandsInDirectRowCount();
            wetlandAreaInDirectRowHectares = dwImpact.wetlandAreaInDirectRowHectares();
            watershedBoundaryCrossingsCount = dwImpact.watershedBoundaryCrossingsCount();
            totalDrainageSpanMeters = dwImpact.totalRecommendedDrainageSpanMeters();
            for (DrainageWetlandVectorIndex.DrainageInfrastructureRequirement req :
                    dwImpact.drainageInfrastructureRequirements()) {
                drainageStructures.add(new PlannedDrainageStructure(
                        req.requirementId(),
                        req.location(),
                        req.chainageKm(),
                        req.targetFeatureId(),
                        req.targetFeatureName(),
                        req.sublayerType(),
                        req.structureType(),
                        req.recommendedClearSpanOrWidthMeters(),
                        req.designDischargeM3PerSec(),
                        req.engineeringSpecification()));
            }
            for (DrainageWetlandVectorIndex.WetlandIntersectionSummary wi : dwImpact.affectedWetlands()) {
                DrainageWetlandVectorIndex.WetlandPolygon wp = drainageVectorIndex.allWetlands().stream()
                        .filter(w -> w.id().equals(wi.wetlandId()))
                        .findFirst()
                        .orElse(null);
                GeoPoint pt = wp != null ? wp.centroidGeo() : mainline.get(mainline.size() / 2);
                wetlandMitigationZones.add(new PlannedWetlandMitigationZone(
                        "WMZ-" + (wetlandMitigationZones.size() + 1) + "-" + wi.wetlandId(),
                        pt,
                        wi.wetlandId(),
                        wi.wetlandName(),
                        wi.wetlandCategory(),
                        wi.district(),
                        wi.watershedId(),
                        wi.isProtectedBeel(),
                        wi.highestExposureZone(),
                        wi.directRowAreaSqMeters(),
                        wi.directRowAreaHectares(),
                        wi.buffer100mAreaHectares(),
                        wi.buffer250mAreaHectares(),
                        wi.clearanceFromDirectRowEdgeMeters(),
                        wi.recommendedMitigation()));
            }
        }

        return new NetworkPlanResult(
                mode,
                round(mainlineLengthKm),
                totalSpurLengthKm,
                round(mainlineLengthKm + totalSpurLengthKm),
                interchanges,
                feederSpurs,
                gradeSeparatedStructures,
                List.copyOf(riverBridges),
                List.copyOf(wildlifePassages),
                List.copyOf(geotechMitigations),
                List.copyOf(utilityCrossings),
                List.copyOf(settlementConflictZones),
                List.copyOf(demographicExposureZones),
                List.copyOf(drainageStructures),
                List.copyOf(wetlandMitigationZones),
                allCrossings.size(),
                railwayRobCount,
                roadFlyoverCount,
                avgCrossingAngleDeg,
                round(totalRiverBridgeSpanMeters),
                avgRiverCrossingAngleDeg,
                round(totalWildlifePassageSpanMeters),
                avgWildlifeCrossingAngleDeg,
                round(totalGeotechStabilizationMeters),
                avgGeotechCrossingAngleDeg,
                round(totalUtilityProtectiveSpanMeters),
                avgUtilityCrossingAngleDeg,
                buildingsInDirectRowCount,
                settlementsIntersectingDirectRowCount,
                populationWithinDirectRow,
                estimatedDirectlyDisplacedPopulation,
                populationWithin250mEnvironmentalBuffer,
                populationWithin500mSecondaryBuffer,
                totalWaterCrossingsCount,
                wetlandsInDirectRowCount,
                wetlandAreaInDirectRowHectares,
                watershedBoundaryCrossingsCount,
                round(totalDrainageSpanMeters),
                round(activeTrafficPcuHr),
                hubEvaluation.designCapacityPcuHr(),
                finalVcRatio,
                finalLos,
                overCapacity,
                activeServedPopulation,
                rowCorridorFootprint);
    }

    private InterchangeCandidate selectInterchangeLocation(
            RegionalHub hub,
            boolean immutable,
            double minSpacingKm,
            double mainlineLengthKm,
            List<TransportNetworkVectorIndex.RouteCrossing> allCrossings,
            List<ChainageSample> chainageSamples,
            List<PlannedInterchange> existingInterchanges) {

        MetricPoint tieMetric = hub.bypassTieInMetric();
        double maxNhShReachKm = immutable ? 30.0 : 18.0;
        double maxMdrReachKm = immutable ? 20.0 : 12.0;

        // 1. Primary Rule: Spawn interchange where mainline intersects an existing NH or SH corridor
        InterchangeCandidate bestNhSh = null;
        double bestNhShDistKm = Double.POSITIVE_INFINITY;
        for (int i = 0; i < allCrossings.size(); i++) {
            TransportNetworkVectorIndex.RouteCrossing rc = allCrossings.get(i);
            if (!rc.category().isHighCapacityInterchangeEligible()) {
                continue;
            }
            if (isInsideWaterChannelOrWetland(rc.point())
                    || isInsideWildlifeInterchangeExclusionZone(rc.point())
                    || isInsideUnsafeGeotechInterchangeSubgrade(rc.point())
                    || isInsideUtilityInterchangeExclusionZone(rc.point())
                    || isInsideBuildingInterchangeExclusionZone(rc.point())
                    || isInsideDemographicInterchangeExclusionZone(rc.point())
                    || isInsideDrainageWetlandInterchangeExclusionZone(rc.point())) {
                continue;
            }
            if (!immutable && !passesChainageSpacing(
                    rc.chainageKm(), minSpacingKm, mainlineLengthKm, existingInterchanges)) {
                continue;
            }
            double distKm = Math.hypot(
                    rc.metricPoint().x() - tieMetric.x(),
                    rc.metricPoint().y() - tieMetric.y()) / 1000.0;
            if (distKm <= maxNhShReachKm && distKm < bestNhShDistKm) {
                bestNhShDistKm = distKm;
                bestNhSh = new InterchangeCandidate(
                        rc.point(),
                        rc.chainageKm(),
                        i,
                        "GRADE_SEPARATED_CORRIDOR_INTERCHANGE",
                        "HIGH_CAPACITY_NH_SH_INTERSECTION",
                        rc.corridorRef());
            }
        }
        if (bestNhSh != null) {
            return bestNhSh;
        }

        // 2. Secondary Rule: Arterial MDR intersection upgrade if one crosses near the hub
        InterchangeCandidate bestMdr = null;
        double bestMdrDistKm = Double.POSITIVE_INFINITY;
        for (int i = 0; i < allCrossings.size(); i++) {
            TransportNetworkVectorIndex.RouteCrossing rc = allCrossings.get(i);
            if (rc.category() != TransportCategory.MAJOR_DISTRICT_ROAD) {
                continue;
            }
            if (isInsideWaterChannelOrWetland(rc.point())
                    || isInsideWildlifeInterchangeExclusionZone(rc.point())
                    || isInsideUnsafeGeotechInterchangeSubgrade(rc.point())
                    || isInsideUtilityInterchangeExclusionZone(rc.point())
                    || isInsideBuildingInterchangeExclusionZone(rc.point())
                    || isInsideDemographicInterchangeExclusionZone(rc.point())
                    || isInsideDrainageWetlandInterchangeExclusionZone(rc.point())) {
                continue;
            }
            if (!immutable && !passesChainageSpacing(
                    rc.chainageKm(), minSpacingKm, mainlineLengthKm, existingInterchanges)) {
                continue;
            }
            double distKm = Math.hypot(
                    rc.metricPoint().x() - tieMetric.x(),
                    rc.metricPoint().y() - tieMetric.y()) / 1000.0;
            if (distKm <= maxMdrReachKm && distKm < bestMdrDistKm) {
                bestMdrDistKm = distKm;
                bestMdr = new InterchangeCandidate(
                        rc.point(),
                        rc.chainageKm(),
                        i,
                        "UPGRADED_ARTERIAL_DIAMOND_INTERCHANGE",
                        "ARTERIAL_ROAD_INTERSECTION",
                        rc.corridorRef());
            }
        }
        if (bestMdr != null) {
            return bestMdr;
        }

        // 3. Fallback for parallel-corridor bypassed hubs: nearest terrain-safe Trumpet Interchange on mainline
        ChainageSample bestSample = null;
        double bestSampleDistKm = Double.POSITIVE_INFINITY;
        for (ChainageSample sample : chainageSamples) {
            if (!immutable && !passesChainageSpacing(
                    sample.chainageKm(), minSpacingKm, mainlineLengthKm, existingInterchanges)) {
                continue;
            }
            if (!isTerrainSafeForInterchange(sample.point())) {
                continue;
            }
            double distKm = Math.hypot(
                    sample.metric().x() - tieMetric.x(),
                    sample.metric().y() - tieMetric.y()) / 1000.0;
            if (distKm < bestSampleDistKm) {
                bestSampleDistKm = distKm;
                bestSample = sample;
            }
        }
        if (bestSample == null) {
            return null;
        }
        return new InterchangeCandidate(
                bestSample.point(),
                bestSample.chainageKm(),
                null,
                "TRUMPET_SPUR_INTERCHANGE",
                "BYPASSED_PARALLEL_CORRIDOR_SHORTEST_SPUR",
                hub.tieInRoadRef());
    }

    private boolean passesChainageSpacing(
            double candidateChainageKm,
            double minSpacingKm,
            double mainlineLengthKm,
            List<PlannedInterchange> existing) {
        if (candidateChainageKm < minSpacingKm
                || (mainlineLengthKm - candidateChainageKm) < minSpacingKm) {
            return false;
        }
        for (PlannedInterchange ic : existing) {
            if (Math.abs(candidateChainageKm - ic.chainageKm()) < minSpacingKm) {
                return false;
            }
        }
        return true;
    }

    private boolean isInsideWaterChannelOrWetland(GeoPoint pt) {
        if (waterBodyRasterProvider == null) {
            return false;
        }
        WaterBodyClass wc = waterBodyRasterProvider.sampleClass(pt.lat(), pt.lon());
        return wc == WaterBodyClass.MINOR_PERENNIAL_STREAM
                || wc == WaterBodyClass.MEDIUM_PERENNIAL_RIVER
                || wc == WaterBodyClass.MAJOR_PERENNIAL_RIVER
                || wc == WaterBodyClass.INLAND_WETLAND_WATERBODY;
    }

    private boolean isInsideWildlifeInterchangeExclusionZone(GeoPoint pt) {
        if (in.marg.wildlife.IndianWildlifeSanctuaryRegistry.getDefaultInstance()
                .isInsideAnySanctuaryCore(pt.lat(), pt.lon())) {
            return true;
        }
        if (wildlifeRasterProvider == null) {
            return false;
        }
        return wildlifeRasterProvider.sampleClass(pt.lat(), pt.lon()).isInterchangeExcludedZone();
    }

    private boolean isInsideUnsafeGeotechInterchangeSubgrade(GeoPoint pt) {
        if (geotechRasterProvider == null) {
            return false;
        }
        return geotechRasterProvider.sampleClass(pt.lat(), pt.lon()).isInterchangeUnsafeSubgrade();
    }

    private boolean isInsideUtilityInterchangeExclusionZone(GeoPoint pt) {
        if (utilityRasterProvider == null) {
            return false;
        }
        return utilityRasterProvider.sampleClass(pt.lat(), pt.lon()).isInterchangeExcludedZone();
    }

    private boolean isInsideBuildingInterchangeExclusionZone(GeoPoint pt) {
        if (buildingVectorIndex != null) {
            if (buildingVectorIndex.findContainingInstitutionalOrSacredExclusion(pt, 100.0) != null
                    || buildingVectorIndex.findContainingDenseColony(pt) != null) {
                return true;
            }
        }
        if (buildingRasterProvider == null) {
            return false;
        }
        return buildingRasterProvider.sampleClass(pt.lat(), pt.lon()).isInterchangeExcludedZone();
    }

    private boolean isInsideDemographicInterchangeExclusionZone(GeoPoint pt) {
        if (demographicRasterProvider == null) {
            return false;
        }
        return demographicRasterProvider.sampleClass(pt.lat(), pt.lon()).isInterchangeExcludedZone();
    }

    private boolean isInsideDrainageWetlandInterchangeExclusionZone(GeoPoint pt) {
        if (drainageRasterProvider == null) {
            return false;
        }
        return drainageRasterProvider.sampleClass(pt.lat(), pt.lon()).isInterchangeExcludedZone();
    }

    private boolean isTerrainSafeForInterchange(GeoPoint pt) {
        if (in.marg.wildlife.IndianWildlifeSanctuaryRegistry.getDefaultInstance()
                .isInsideAnySanctuaryCore(pt.lat(), pt.lon())) {
            return false;
        }
        Double pa = protectedAreaRasterProvider.sampleOrNull(pt.lat(), pt.lon());
        if (pa == null || Math.round(pa) >= 1L) {
            return false;
        }
        Double slope = slopeRasterProvider.sampleOrNull(pt.lat(), pt.lon());
        if (slope == null || slope > MAX_INTERCHANGE_SLOPE_DEGREES) {
            return false;
        }
        if (waterBodyRasterProvider != null
                && waterBodyRasterProvider.sampleClass(pt.lat(), pt.lon())
                        .isChannelOrWetlandOrInnerScourZone()) {
            return false;
        }
        if (isInsideWildlifeInterchangeExclusionZone(pt)
                || isInsideUnsafeGeotechInterchangeSubgrade(pt)
                || isInsideUtilityInterchangeExclusionZone(pt)
                || isInsideBuildingInterchangeExclusionZone(pt)
                || isInsideDemographicInterchangeExclusionZone(pt)
                || isInsideDrainageWetlandInterchangeExclusionZone(pt)) {
            return false;
        }
        TransportCorridorClass tc = transportRasterProvider.sampleClass(pt.lat(), pt.lon());
        return tc != TransportCorridorClass.RAILWAY_CORRIDOR
                && tc != TransportCorridorClass.RAILWAY_BUFFER;
    }

    /**
     * If a hub's default road tie-in lies inside a High/Severe flood zone or Active/High landslide
     * hazard (penalty >= 25.0 or blocked on spurSurface), shifts the spur tie-in onto the nearest
     * dry, stable stretch of existing arterial road within 3.5 km so connecting roads avoid
     * floodplains and unstable scarps.
     */
    private GeoPoint resolveDryStableHubTieIn(
            StudyRegion fullRegion,
            RegionalHub hub,
            CostSurface spurSurface,
            FacilityMode mode) {
        GeoPoint defaultTie = hub.bypassTieIn();
        if (defaultTie == null || spurSurface == null || vectorIndex == null) {
            return defaultTie;
        }
        CostAssessment curr = spurSurface.assess(defaultTie);
        if (!curr.blocked() && curr.penaltyMultiplier() < 15.0) {
            return defaultTie;
        }
        TransportNetworkVectorIndex.NearestRoadTieIn dryTie = vectorIndex.findNearestRoadTieIn(
                hub.center(),
                pt -> fullRegion.contains(pt)
                        && projection.distanceMeters(defaultTie, pt) <= 5000.0
                        && !spurSurface.assess(pt).blocked()
                        && spurSurface.assess(pt).penaltyMultiplier() < 15.0,
                mode);
        if (dryTie != null && dryTie.tieInPoint() != null) {
            return dryTie.tieInPoint();
        }
        return defaultTie;
    }

    private List<GeoPoint> routeDedicatedSpur(
            StudyRegion fullRegion,
            GeoPoint interchangePoint,
            GeoPoint hubBypassTieIn,
            CostSurface spurSurface,
            CostSurface denseColonyFallbackSpurSurface,
            CostSurface geoHazardFallbackSpurSurface) {

        double directMeters = projection.distanceMeters(interchangePoint, hubBypassTieIn);
        if (directMeters <= 300.0) {
            boolean directSafe = (buildingVectorIndex == null
                    || !buildingVectorIndex.hasSegmentBuildingOrExclusionConflict(
                            interchangePoint, hubBypassTieIn, 20.0, 75.0))
                    && (spurSurface == null
                    || (!spurSurface.assess(hubBypassTieIn).blocked()
                    && !spurSurface.assessEdge(interchangePoint, hubBypassTieIn).blocked()));
            if (directSafe) {
                return List.of(interchangePoint, hubBypassTieIn);
            }
        }

        in.marg.routing.RouteConstraints spurConstraints =
                in.marg.routing.RouteConstraints.forFacilityMode(null, FacilityMode.NATIONAL_HIGHWAY);
        for (CostSurface activeSurface : new CostSurface[]{spurSurface, denseColonyFallbackSpurSurface, geoHazardFallbackSpurSurface}) {
            if (activeSurface == null) continue;
            for (double bufferDeg : new double[]{0.06, 0.12, 0.20, 0.35}) {
                StudyRegion localWindow = localWindow(fullRegion, interchangePoint, hubBypassTieIn, bufferDeg);
                try {
                    List<GeoPoint> rawSpur = router.route(
                            fullRegion,
                            interchangePoint,
                            hubBypassTieIn,
                            activeSurface,
                            SPUR_ROUTING_RESOLUTION_METERS,
                            localWindow,
                            spurConstraints);
                    return in.marg.routing.HorizontalCurveSmoother.smoothAndAudit(
                            rawSpur,
                            activeSurface,
                            buildingVectorIndex,
                            projection,
                            localWindow,
                            in.marg.routing.HorizontalCurveSmoother.CurveProfile.FEEDER_SPUR).smoothedPath();
                } catch (RuntimeException ignored) {
                    // Expand local spur window on steep ridges or exclusion detours
                }
            }
            try {
                List<GeoPoint> rawSpur = router.route(
                        fullRegion,
                        interchangePoint,
                        hubBypassTieIn,
                        activeSurface,
                        SPUR_ROUTING_RESOLUTION_METERS,
                        fullRegion,
                        spurConstraints);
                return in.marg.routing.HorizontalCurveSmoother.smoothAndAudit(
                        rawSpur,
                        activeSurface,
                        buildingVectorIndex,
                        projection,
                        fullRegion,
                        in.marg.routing.HorizontalCurveSmoother.CurveProfile.FEEDER_SPUR).smoothedPath();
            } catch (RuntimeException ignored) {
            }
        }
        // Never allow an unchecked straight-line fallback across hospitals, institutional compounds, or buildings
        if (buildingVectorIndex != null
                && buildingVectorIndex.hasSegmentBuildingOrExclusionConflict(
                        interchangePoint, hubBypassTieIn, 20.0, 75.0)) {
            return List.of();
        }
        return List.of(interchangePoint, hubBypassTieIn);
    }

    private CostLayer buildRailwayOnlySpurLayer(TransportCostPolicy policy) {
        return new CostLayer() {
            @Override
            public CostAssessment assess(GeoPoint point) {
                TransportCorridorClass c =
                        transportRasterProvider.sampleClass(point.lat(), point.lon());
                if (c == TransportCorridorClass.RAILWAY_CORRIDOR
                        || c == TransportCorridorClass.RAILWAY_BUFFER) {
                    return new CostAssessment(
                            false,
                            policy.nodePenalty(c),
                            "infrastructure:spur-railway-avoidance",
                            c.name());
                }
                return CostAssessment.FREE;
            }

            @Override
            public boolean hasEdgeCost() {
                return true;
            }

            @Override
            public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
                List<TransportNetworkVectorIndex.SegmentCrossing> crossings =
                        vectorIndex.findStepCrossings(from, to);
                double railCostKmEq = 0.0;
                for (TransportNetworkVectorIndex.SegmentCrossing sc : crossings) {
                    if (sc.category() == TransportCategory.RAILWAY) {
                        railCostKmEq += policy.crossingStructureCostKmEq(sc.category(), sc.sinAngle());
                    }
                }
                if (railCostKmEq <= 0.0) {
                    return CostAssessment.FREE;
                }
                double stepKm = Math.max(0.05, projection.distanceMeters(from, to) / 1000.0);
                return new CostAssessment(
                        false,
                        railCostKmEq / stepKm,
                        "infrastructure:spur-railway-rob",
                        "RAIL_OVER_BRIDGE_ROB");
            }
        };
    }

    private List<ChainageSample> densifyMainline(List<GeoPoint> mainline) {
        List<ChainageSample> samples = new ArrayList<>();
        if (mainline.isEmpty()) {
            return samples;
        }
        double cumulativeKm = 0.0;
        MetricPoint firstM = projection.forward(mainline.get(0));
        samples.add(new ChainageSample(mainline.get(0), firstM, 0.0));

        for (int i = 1; i < mainline.size(); i++) {
            MetricPoint a = projection.forward(mainline.get(i - 1));
            MetricPoint b = projection.forward(mainline.get(i));
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double segMeters = Math.hypot(dx, dy);
            if (segMeters <= 0.0) {
                continue;
            }
            int parts = Math.max(1, (int) Math.ceil(segMeters / CHAINAGE_DENSIFY_STEP_METERS));
            for (int j = 1; j <= parts; j++) {
                double t = (double) j / parts;
                MetricPoint mp = new MetricPoint(a.x() + t * dx, a.y() + t * dy);
                GeoPoint gp = j == parts ? mainline.get(i) : projection.inverse(mp);
                double chKm = cumulativeKm + (t * segMeters) / 1000.0;
                samples.add(new ChainageSample(gp, mp, chKm));
            }
            cumulativeKm += segMeters / 1000.0;
        }
        return samples;
    }

    private static StudyRegion localWindow(
            StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / spur window", minLat, minLon, maxLat, maxLon, a, b);
    }

    /**
     * Builds the rich multi-feature GeoJSON list:
     * MAINLINE + DEDICATED_FEEDER_SPURs + INTERCHANGEs + ROAD_TIE_INs + GRADE_SEPARATED_CROSSINGs.
     */
    public List<Map<String, Object>> buildGeoJsonFeatures(
            List<GeoPoint> mainline,
            Map<String, Object> mainlineProperties,
            NetworkPlanResult plan) {

        List<Map<String, Object>> features = new ArrayList<>();

        // 1. Mainline alignment feature
        Map<String, Object> mlProps = new LinkedHashMap<>(mainlineProperties);
        mlProps.put("featureRole", "MAINLINE");
        mlProps.put("facilityMode", plan.facilityMode().name());
        mlProps.put("plannedInterchangeCount", plan.interchanges().size());
        mlProps.put("totalFeederSpurLengthKm", plan.totalFeederSpurLengthKm());
        mlProps.put("railwayRobCount", plan.railwayRobCount());
        mlProps.put("highwayFlyoverVupCount", plan.roadFlyoverCount());
        mlProps.put("riverBridgeCount", plan.riverBridges().size());
        mlProps.put("totalRiverBridgeSpanMeters", plan.totalEstimatedRiverBridgeSpanMeters());
        mlProps.put("wildlifePassageCount", plan.wildlifePassages().size());
        mlProps.put("totalWildlifePassageSpanMeters", plan.totalRecommendedWildlifePassageSpanMeters());
        mlProps.put("geotechMitigationZoneCount", plan.geotechMitigations().size());
        mlProps.put("totalGeotechStabilizationMeters", plan.totalRecommendedGeotechStabilizationMeters());
        mlProps.put("utilityCrossingCount", plan.utilityCrossings().size());
        mlProps.put("totalUtilityProtectiveSpanMeters", plan.totalRecommendedUtilityProtectiveSpanMeters());
        mlProps.put("settlementConflictZonesCount", plan.settlementConflictZones().size());
        mlProps.put("buildingsInDirectRowCount", plan.buildingsInDirectRowCount());
        mlProps.put("settlementsIntersectingDirectRowCount", plan.settlementsIntersectingDirectRowCount());
        mlProps.put("demographicExposureZonesCount", plan.demographicExposureZones().size());
        mlProps.put("populationWithinDirectRow", plan.populationWithinDirectRow());
        mlProps.put("estimatedDirectlyDisplacedPopulation", plan.estimatedDirectlyDisplacedPopulation());
        mlProps.put("populationWithin250mEnvironmentalBuffer", plan.populationWithin250mEnvironmentalBuffer());
        mlProps.put("populationWithin500mSecondaryBuffer", plan.populationWithin500mSecondaryBuffer());
        mlProps.put("drainageStructuresCount", plan.drainageStructures().size());
        mlProps.put("wetlandMitigationZonesCount", plan.wetlandMitigationZones().size());
        mlProps.put("totalWaterCrossingsCount", plan.totalWaterCrossingsCount());
        mlProps.put("wetlandsInDirectRowCount", plan.wetlandsInDirectRowCount());
        mlProps.put("wetlandAreaInDirectRowHectares", plan.wetlandAreaInDirectRowHectares());
        mlProps.put("watershedBoundaryCrossingsCount", plan.watershedBoundaryCrossingsCount());
        mlProps.put("totalRecommendedDrainageSpanMeters", plan.totalRecommendedDrainageSpanMeters());
        if (plan.rowCorridorFootprint() != null) {
            mlProps.put("designRowWidthMeters", plan.rowCorridorFootprint().mainlineRowWidthMeters());
            mlProps.put("ircRowStandard", plan.rowCorridorFootprint().ircStandard());
            mlProps.put("mainlineRowAreaHectares", plan.rowCorridorFootprint().mainlineRowAreaHectares());
            mlProps.put("totalDirectRowAcquisitionHectares", plan.rowCorridorFootprint().totalDirectRowAcquisitionHectares());
        }
        mlProps.put("projectedVcRatio", plan.projectedVcRatio());
        mlProps.put("levelOfService", plan.levelOfService());

        features.add(Map.of(
                "type", "Feature",
                "properties", mlProps,
                "geometry", Map.of(
                        "type", "LineString",
                        "coordinates", toLonLatList(mainline))));

        // 2. Dedicated Feeder / Spur Road LineStrings
        for (PlannedFeederSpur spur : plan.feederSpurs()) {
            Map<String, Object> spProps = new LinkedHashMap<>();
            spProps.put("featureRole", "DEDICATED_FEEDER_SPUR");
            spProps.put("spurId", spur.spurId());
            spProps.put("interchangeId", spur.interchangeId());
            spProps.put("servedHubId", spur.hub().hubId());
            spProps.put("servedHubName", spur.hub().name());
            spProps.put("tieInRoadRef", spur.hub().tieInRoadRef());
            spProps.put("tieInJunctionName", spur.hub().tieInName());
            spProps.put("spurLengthKm", spur.spurLengthKm());
            spProps.put("catchmentPopulationServed", spur.hub().catchmentPopulation());
            spProps.put("inducedPeakTrafficPcuHr", round(spur.inducedPeakTrafficPcuHr()));
            spProps.put("hviScore", round(spur.hviScore()));
            spProps.put("immutableUserOverride", spur.immutableOverride());
            spProps.put("decisionStatus", spur.decisionStatus());
            spProps.put("decisionReason", spur.decisionReason());
            boolean sixLaneReq = spur.decisionReason() != null && spur.decisionReason().contains("6-lane");
            spProps.put("sixLaneExpansionRecommended", sixLaneReq);

            features.add(Map.of(
                    "type", "Feature",
                    "properties", spProps,
                    "geometry", Map.of(
                            "type", "LineString",
                            "coordinates", toLonLatList(spur.path()))));
        }

        // 3. Grade-Separated Mainline Interchange Points
        for (PlannedInterchange ic : plan.interchanges()) {
            Map<String, Object> icProps = new LinkedHashMap<>();
            icProps.put("featureRole", "INTERCHANGE");
            icProps.put("interchangeId", ic.interchangeId());
            icProps.put("chainageKm", ic.chainageKm());
            icProps.put("interchangeType", ic.interchangeType());
            icProps.put("placementRule", ic.placementRule());
            icProps.put("connectedCorridorRef", ic.connectedRoadRef());
            icProps.put("servedHubId", ic.servedHubId());
            icProps.put("servedHubName", ic.servedHubName());
            icProps.put("immutableUserOverride", ic.immutableOverride());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", icProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(ic.point().lon(), ic.point().lat()))));
        }

        // 4. Existing Ring-Road / Major Bypass Tie-In Points (Rule of Continuity endpoints)
        for (PlannedFeederSpur spur : plan.feederSpurs()) {
            RegionalHub hub = spur.hub();
            Map<String, Object> tieProps = new LinkedHashMap<>();
            tieProps.put("featureRole", "ROAD_TIE_IN");
            tieProps.put("spurId", spur.spurId());
            tieProps.put("hubId", hub.hubId());
            tieProps.put("hubName", hub.name());
            tieProps.put("existingRoadRef", hub.tieInRoadRef());
            tieProps.put("tieInJunctionName", hub.tieInName());
            tieProps.put("continuityRule", "Ties into existing bypass/arterial outside URBAN_CORE");

            features.add(Map.of(
                    "type", "Feature",
                    "properties", tieProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(hub.bypassTieIn().lon(), hub.bypassTieIn().lat()))));
        }

        // 5. Non-Interchange Grade-Separated Crossings (ROBs & Flyovers/Underpasses)
        for (GradeSeparatedCrossingStructure gs : plan.gradeSeparatedCrossings()) {
            Map<String, Object> gsProps = new LinkedHashMap<>();
            gsProps.put("featureRole", "GRADE_SEPARATED_CROSSING");
            gsProps.put("structureId", gs.structureId());
            gsProps.put("structureType", gs.structureType());
            gsProps.put("crossedCategory", gs.category().name());
            gsProps.put("crossedCorridorRef", gs.corridorRef());
            gsProps.put("crossedCorridorName", gs.corridorName());
            gsProps.put("chainageKm", gs.chainageKm());
            gsProps.put("crossingAngleDegrees", gs.crossingAngleDegrees());
            gsProps.put("skewBridgeMultiplier", gs.skewBridgeMultiplier());
            gsProps.put("structureCostKmEquivalent", gs.structureCostKmEquivalent());
            gsProps.put("hasAccessRamps", false);

            features.add(Map.of(
                    "type", "Feature",
                    "properties", gsProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(gs.point().lon(), gs.point().lat()))));
        }

        // 6. Perennial River & Stream Bridge Structures (High-Level Bridges & Box Culverts)
        for (PlannedRiverBridge rb : plan.riverBridges()) {
            Map<String, Object> rbProps = new LinkedHashMap<>();
            rbProps.put("featureRole", "RIVER_BRIDGE");
            rbProps.put("bridgeId", rb.bridgeId());
            rbProps.put("structureType", rb.structureType());
            rbProps.put("riverId", rb.riverId());
            rbProps.put("riverName", rb.riverName());
            rbProps.put("riverClass", rb.riverClass().name());
            rbProps.put("basin", rb.basin());
            rbProps.put("chainageKm", rb.chainageKm());
            rbProps.put("designChannelWidthMeters", rb.designChannelWidthMeters());
            rbProps.put("estimatedSkewSpanMeters", rb.estimatedSkewSpanMeters());
            rbProps.put("effectiveSkewSpanMeters", rb.estimatedSkewSpanMeters());
            rbProps.put("crossingAngleDegrees", rb.crossingAngleDegrees());
            rbProps.put("hydraulicSkewMultiplier", rb.hydraulicSkewMultiplier());
            rbProps.put("bridgeCostKmEquivalent", rb.bridgeCostKmEquivalent());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", rbProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(rb.point().lon(), rb.point().lat()))));
        }

        // Also emit Corridor River Channels so GIS map can visually display the underlying waterways
        if (riverVectorIndex != null) {
            for (RiverNetworkVectorIndex.RiverCorridor river : riverVectorIndex.allRivers()) {
                Map<String, Object> rivProps = new LinkedHashMap<>();
                rivProps.put("featureRole", "CORRIDOR_RIVER_CHANNEL");
                rivProps.put("riverId", river.id());
                rivProps.put("riverName", river.name());
                rivProps.put("riverClass", river.riverClass().name());
                rivProps.put("designChannelWidthMeters", river.designChannelWidthMeters());

                List<List<Double>> coords = river.points().stream()
                        .map(p -> List.of(p.lon(), p.lat()))
                        .toList();

                features.add(Map.of(
                        "type", "Feature",
                        "properties", rivProps,
                        "geometry", Map.of(
                                "type", "LineString",
                                "coordinates", coords)));
            }
        }

        // Statutory National Parks & Wildlife Sanctuaries (Wildlife Protection Act, 1972)
        in.marg.wildlife.IndianWildlifeSanctuaryRegistry sanctuaryReg = in.marg.wildlife.IndianWildlifeSanctuaryRegistry.getDefaultInstance();
        double sMinLat = Double.POSITIVE_INFINITY, sMaxLat = Double.NEGATIVE_INFINITY;
        double sMinLon = Double.POSITIVE_INFINITY, sMaxLon = Double.NEGATIVE_INFINITY;
        for (GeoPoint p : mainline) {
            sMinLat = Math.min(sMinLat, p.lat());
            sMaxLat = Math.max(sMaxLat, p.lat());
            sMinLon = Math.min(sMinLon, p.lon());
            sMaxLon = Math.max(sMaxLon, p.lon());
        }
        for (in.marg.wildlife.IndianWildlifeSanctuaryRegistry.StatutorySanctuary s : sanctuaryReg.allSanctuaries()) {
            if (!mainline.isEmpty() && (s.maxLat() < sMinLat - 0.25 || s.minLat() > sMaxLat + 0.25
                    || s.maxLon() < sMinLon - 0.25 || s.minLon() > sMaxLon + 0.25)) {
                continue;
            }
            Map<String, Object> sProps = new LinkedHashMap<>();
            sProps.put("featureRole", "WILDLIFE_SANCTUARY_BOUNDARY");
            sProps.put("sanctuaryId", s.id());
            sProps.put("name", s.name());
            sProps.put("state", s.state());
            sProps.put("district", s.district());
            sProps.put("legalStatute", s.legalStatute());
            sProps.put("areaKm2", s.areaKm2());
            sProps.put("keySpecies", s.keySpecies());
            sProps.put("requiresNbwlClearance", true);

            List<List<double[]>> effectiveRings = s.effectiveBoundaryRings();
            Map<String, Object> geomMap;
            if (effectiveRings.size() > 1) {
                List<List<List<List<Double>>>> multiCoords = new ArrayList<>(effectiveRings.size());
                for (List<double[]> r : effectiveRings) {
                    List<List<Double>> ring = new ArrayList<>(r.size());
                    for (double[] pt : r) {
                        ring.add(List.of(Math.round(pt[1] * 100000.0) / 100000.0, Math.round(pt[0] * 100000.0) / 100000.0));
                    }
                    multiCoords.add(List.of(ring));
                }
                geomMap = Map.of("type", "MultiPolygon", "coordinates", multiCoords);
            } else {
                List<List<Double>> ring = new ArrayList<>();
                if (!effectiveRings.isEmpty()) {
                    for (double[] pt : effectiveRings.get(0)) {
                        ring.add(List.of(Math.round(pt[1] * 100000.0) / 100000.0, Math.round(pt[0] * 100000.0) / 100000.0));
                    }
                } else {
                    int n = 24;
                    double latR = (s.maxLat() - s.minLat()) / 2.0;
                    double lonR = (s.maxLon() - s.minLon()) / 2.0;
                    for (int k = 0; k <= n; k++) {
                        double a = 2.0 * Math.PI * k / n;
                        double ptLat = s.centerLat() + latR * Math.sin(a);
                        double ptLon = s.centerLon() + lonR * Math.cos(a);
                        ring.add(List.of(Math.round(ptLon * 10000.0) / 10000.0, Math.round(ptLat * 10000.0) / 10000.0));
                    }
                }
                geomMap = Map.of("type", "Polygon", "coordinates", List.of(ring));
            }
            features.add(Map.of(
                    "type", "Feature",
                    "properties", sProps,
                    "geometry", geomMap
            ));

            if (s.eszPolygon() != null && s.eszPolygon().size() >= 3) {
                List<List<Double>> eszRing = new ArrayList<>();
                for (double[] pt : s.eszPolygon()) {
                    eszRing.add(List.of(Math.round(pt[1] * 100000.0) / 100000.0, Math.round(pt[0] * 100000.0) / 100000.0));
                }
                Map<String, Object> eszProps = new LinkedHashMap<>();
                eszProps.put("featureRole", "ECO_SENSITIVE_ZONE_BOUNDARY");
                eszProps.put("sanctuaryId", s.id());
                eszProps.put("name", s.name() + " (MoEFCC 10 km Default ESZ)");
                eszProps.put("eszRadiusKm", in.marg.wildlife.IndianWildlifeSanctuaryRegistry.DEFAULT_ESZ_BUFFER_KM);
                eszProps.put("state", s.state());
                features.add(Map.of(
                        "type", "Feature",
                        "properties", eszProps,
                        "geometry", Map.of(
                                "type", "Polygon",
                                "coordinates", List.of(eszRing)
                        )
                ));
            }
        }

        // 7. Wildlife Migration Corridor Passages (Elevated Wildlife Viaducts & Animal Underpasses)
        for (PlannedWildlifePassage wp : plan.wildlifePassages()) {
            Map<String, Object> wpProps = new LinkedHashMap<>();
            wpProps.put("featureRole", "WILDLIFE_PASSAGE");
            wpProps.put("passageId", wp.passageId());
            wpProps.put("structureType", wp.structureType());
            wpProps.put("corridorId", wp.corridorId());
            wpProps.put("corridorName", wp.corridorName());
            wpProps.put("corridorTier", wp.corridorTier().name());
            wpProps.put("targetSpecies", wp.targetSpecies());
            wpProps.put("chainageKm", wp.chainageKm());
            wpProps.put("designCorridorWidthMeters", wp.designCorridorWidthMeters());
            wpProps.put("recommendedClearSpanMeters", wp.recommendedClearSpanMeters());
            wpProps.put("minimumClearHeightMeters", wp.minimumClearHeightMeters());
            wpProps.put("crossingAngleDegrees", wp.crossingAngleDegrees());
            wpProps.put("barrierSkewMultiplier", wp.barrierSkewMultiplier());
            wpProps.put("passageCostKmEquivalent", wp.passageCostKmEquivalent());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", wpProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(wp.point().lon(), wp.point().lat()))));
        }

        // 8. Geotechnical Fault/Landslide Scarp & Liquefaction Trough Mitigation Zones
        for (PlannedGeotechMitigation gm : plan.geotechMitigations()) {
            Map<String, Object> gmProps = new LinkedHashMap<>();
            gmProps.put("featureRole", "GEOTECH_MITIGATION_ZONE");
            gmProps.put("mitigationId", gm.mitigationId());
            gmProps.put("structureType", gm.structureType());
            gmProps.put("lineamentId", gm.lineamentId());
            gmProps.put("lineamentName", gm.lineamentName());
            gmProps.put("hazardTier", gm.hazardTier().name());
            gmProps.put("lithologyAndSoil", gm.lithologyAndSoil());
            gmProps.put("engineeringHazard", gm.engineeringHazard());
            gmProps.put("chainageKm", gm.chainageKm());
            gmProps.put("designHazardWidthMeters", gm.designHazardWidthMeters());
            gmProps.put("recommendedStabilizationLengthMeters", gm.recommendedStabilizationLengthMeters());
            gmProps.put("crossingAngleDegrees", gm.crossingAngleDegrees());
            gmProps.put("scarpSkewMultiplier", gm.scarpSkewMultiplier());
            gmProps.put("mitigationCostKmEquivalent", gm.mitigationCostKmEquivalent());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", gmProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(gm.point().lon(), gm.point().lat()))));
        }

        // 9. Overhead Power Transmission Line Crossings & Protection Works
        for (PlannedUtilityCrossing uc : plan.utilityCrossings()) {
            Map<String, Object> ucProps = new LinkedHashMap<>();
            ucProps.put("featureRole", "UTILITY_CROSSING_PROTECTION");
            ucProps.put("crossingId", uc.crossingId());
            ucProps.put("lineId", uc.lineId());
            ucProps.put("lineName", uc.lineName());
            ucProps.put("utilityTier", uc.tier().name());
            ucProps.put("voltageKv", uc.voltageKv());
            ucProps.put("circuitConfiguration", uc.circuit());
            ucProps.put("protectionStructureType", uc.protectionStructureType());
            ucProps.put("chainageKm", uc.chainageKm());
            ucProps.put("designRowWidthMeters", uc.designRowWidthMeters());
            ucProps.put("minimumConductorClearanceMeters", uc.minimumConductorClearanceMeters());
            ucProps.put("recommendedProtectiveSpanMeters", uc.recommendedProtectiveSpanMeters());
            ucProps.put("crossingAngleDegrees", uc.crossingAngleDegrees());
            ucProps.put("skewMultiplier", uc.skewMultiplier());
            ucProps.put("nearestTowerDistanceMeters", uc.nearestTowerDistanceMeters());
            ucProps.put("crossingCostKmEquivalent", uc.crossingCostKmEquivalent());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", ucProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(uc.point().lon(), uc.point().lat()))));
        }

        // 10. 2D Right-of-Way (RoW) Corridor Polygons (Mainline & Dedicated Feeder Spurs)
        if (plan.rowCorridorFootprint() != null) {
            RightOfWayCorridorEngine.CorridorFootprint rowFp = plan.rowCorridorFootprint();
            Map<String, Object> rowProps = new LinkedHashMap<>();
            rowProps.put("featureRole", "MAINLINE_ROW_CORRIDOR");
            rowProps.put("facilityMode", plan.facilityMode().name());
            rowProps.put("ircStandard", rowFp.ircStandard());
            rowProps.put("designRowWidthMeters", rowFp.mainlineRowWidthMeters());
            rowProps.put("mainlineRowAreaHectares", rowFp.mainlineRowAreaHectares());
            rowProps.put("feederSpursRowAreaHectares", rowFp.feederSpursRowAreaHectares());
            rowProps.put("interchangeLoopsAdditionalHectares", rowFp.interchangeLoopsAdditionalHectares());
            rowProps.put("totalDirectRowAcquisitionHectares", rowFp.totalDirectRowAcquisitionHectares());
            rowProps.put("constructionZoneGrossHectares", rowFp.constructionZoneGrossHectares());
            rowProps.put("environmentalBufferGrossHectares", rowFp.environmentalBufferGrossHectares());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", rowProps,
                    "geometry", rowCorridorEngine.toGeoJsonGeometry(rowFp.mainlineRowMetric())));

            int spurCount = Math.min(plan.feederSpurs().size(), rowFp.spurRowsMetric().size());
            for (int i = 0; i < spurCount; i++) {
                PlannedFeederSpur spur = plan.feederSpurs().get(i);
                var spurGeom = rowFp.spurRowsMetric().get(i);
                Map<String, Object> spurRowProps = new LinkedHashMap<>();
                spurRowProps.put("featureRole", "FEEDER_SPUR_ROW_CORRIDOR");
                spurRowProps.put("spurId", spur.spurId());
                spurRowProps.put("interchangeId", spur.interchangeId());
                spurRowProps.put("servedHubId", spur.hub().hubId());
                spurRowProps.put("servedHubName", spur.hub().name());
                spurRowProps.put("designRowWidthMeters", rowFp.feederSpurRowWidthMeters());
                spurRowProps.put("spurRowAreaHectares", round(spurGeom.getArea() / 10_000.0));

                features.add(Map.of(
                        "type", "Feature",
                        "properties", spurRowProps,
                        "geometry", rowCorridorEngine.toGeoJsonGeometry(spurGeom)));
            }
        }

        // 11. Settlement & Building Footprint Conflict Zones along the corridor
        for (PlannedSettlementConflictZone scz : plan.settlementConflictZones()) {
            Map<String, Object> sczProps = new LinkedHashMap<>();
            sczProps.put("featureRole", "SETTLEMENT_BUILDING_CONFLICT_ZONE");
            sczProps.put("conflictId", scz.conflictId());
            sczProps.put("settlementId", scz.settlementId());
            sczProps.put("settlementName", scz.settlementName());
            sczProps.put("settlementClassification", scz.settlementClassification().name());
            sczProps.put("settlementSubtype", scz.settlementSubtype());
            sczProps.put("district", scz.district());
            sczProps.put("chainageKm", scz.chainageKm());
            sczProps.put("builtUpDensityBuildingsPerHa", scz.builtUpDensityBuildingsPerHa());
            sczProps.put("builtUpDensityClass", scz.builtUpDensityClass());
            sczProps.put("buildingsInDirectRowCount", scz.buildingsInDirectRowCount());
            sczProps.put("buildingsInConstructionZoneCount", scz.buildingsInConstructionZoneCount());
            sczProps.put("buildingsInDirectRowByType", scz.buildingsInDirectRowByType());
            sczProps.put("affectedDirectRowFootprintSqMeters", scz.affectedDirectRowFootprintSqMeters());
            sczProps.put("areaInDirectRowHectares", scz.areaInDirectRowHectares());
            sczProps.put("areaInConstructionZoneHectares", scz.areaInConstructionZoneHectares());
            sczProps.put("distanceToCenterlineMeters", scz.distanceToCenterlineMeters());
            sczProps.put("distanceToRowEdgeMeters", scz.distanceToRowEdgeMeters());
            sczProps.put("recommendedEngineeringMitigation", scz.recommendedEngineeringMitigation());
            sczProps.put("conflictCostKmEquivalent", scz.conflictCostKmEquivalent());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", sczProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(scz.point().lon(), scz.point().lat()))));
        }

        // 12. Population Density & Demographic Exposure Mitigation Zones along the corridor
        for (PlannedDemographicExposureZone dez : plan.demographicExposureZones()) {
            Map<String, Object> dezProps = new LinkedHashMap<>();
            dezProps.put("featureRole", "DEMOGRAPHIC_EXPOSURE_MITIGATION_ZONE");
            dezProps.put("exposureZoneId", dez.exposureZoneId());
            dezProps.put("tractId", dez.tractId());
            dezProps.put("settlementId", dez.settlementId());
            dezProps.put("settlementName", dez.settlementName());
            dezProps.put("district", dez.district());
            dezProps.put("settlementClassification", dez.settlementClassification());
            dezProps.put("ruralOrUrbanCategory", dez.ruralOrUrbanCategory());
            dezProps.put("chainageKm", dez.chainageKm());
            dezProps.put("totalTractPopulation", dez.totalPopulation());
            dezProps.put("ruralPopulation", dez.ruralPopulation());
            dezProps.put("urbanPopulation", dez.urbanPopulation());
            dezProps.put("populationDensityPerSqKm", dez.populationDensityPerSqKm());
            dezProps.put("populationDensityClass", dez.populationDensityClass());
            dezProps.put("vulnerabilityTier", dez.vulnerabilityTier().name());
            dezProps.put("socialVulnerabilityIndex", dez.socialVulnerabilityIndex());
            dezProps.put("populationInDirectRow", dez.populationInDirectRow());
            dezProps.put("populationWithin100mBuffer", dez.populationWithin100mBuffer());
            dezProps.put("populationWithin250mBuffer", dez.populationWithin250mBuffer());
            dezProps.put("populationWithin500mBuffer", dez.populationWithin500mBuffer());
            dezProps.put("estimatedDirectlyDisplacedPopulation", dez.estimatedDirectlyDisplacedPopulation());
            dezProps.put("estimatedDirectlyDisplacedHouseholds", dez.estimatedDirectlyDisplacedHouseholds());
            dezProps.put("nonDisplacedBufferExposedWithin250m", dez.nonDisplacedBufferExposedWithin250m());
            dezProps.put("vulnerableAgePopulationWithin250m", dez.vulnerableAgePopulationWithin250m());
            dezProps.put("scheduledTribeAndCastesWithin250m", dez.scheduledTribeAndCastesWithin250m());
            dezProps.put("distanceToCenterlineMeters", dez.distanceToCenterlineMeters());
            dezProps.put("distanceToRowEdgeMeters", dez.distanceToRowEdgeMeters());
            dezProps.put("recommendedSocialMitigation", dez.recommendedSocialMitigation());
            dezProps.put("demographicExposureScoreKmEq", dez.demographicExposureScoreKmEq());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", dezProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(dez.point().lon(), dez.point().lat()))));
        }

        // 13. Cross-Drainage Infrastructure Structures (Culverts, Minor Bridges, Canal Syphon Aqueducts, Viaducts)
        for (PlannedDrainageStructure ds : plan.drainageStructures()) {
            Map<String, Object> dsProps = new LinkedHashMap<>();
            dsProps.put("featureRole", "DRAINAGE_CROSSING_STRUCTURE");
            dsProps.put("structureId", ds.structureId());
            dsProps.put("targetFeatureId", ds.targetFeatureId());
            dsProps.put("targetFeatureName", ds.targetFeatureName());
            dsProps.put("sublayerType", ds.sublayerType().name());
            dsProps.put("structureType", ds.structureType());
            dsProps.put("chainageKm", ds.chainageKm());
            dsProps.put("recommendedClearSpanOrWidthMeters", ds.recommendedClearSpanOrWidthMeters());
            dsProps.put("designDischargeM3PerSec", ds.designDischargeM3PerSec());
            dsProps.put("engineeringSpecification", ds.engineeringSpecification());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", dsProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(ds.point().lon(), ds.point().lat()))));
        }

        // 14. Wetland Hydrological Mitigation Zones along the corridor
        java.util.Set<String> emittedWetlandIds = new java.util.HashSet<>();
        for (PlannedWetlandMitigationZone wmz : plan.wetlandMitigationZones()) {
            emittedWetlandIds.add(wmz.wetlandId());
            Map<String, Object> wmzProps = new LinkedHashMap<>();
            wmzProps.put("featureRole", "WETLAND_HYDROLOGIC_MITIGATION_ZONE");
            wmzProps.put("zoneId", wmz.zoneId());
            wmzProps.put("wetlandId", wmz.wetlandId());
            wmzProps.put("wetlandName", wmz.wetlandName());
            wmzProps.put("wetlandCategory", wmz.wetlandCategory());
            wmzProps.put("district", wmz.district());
            wmzProps.put("watershedId", wmz.watershedId());
            wmzProps.put("isProtectedBeel", wmz.isProtectedBeel());
            wmzProps.put("highestExposureZone", wmz.highestExposureZone());
            wmzProps.put("directRowAreaSqMeters", wmz.directRowAreaSqMeters());
            wmzProps.put("directRowAreaHectares", wmz.directRowAreaHectares());
            wmzProps.put("buffer100mAreaHectares", wmz.buffer100mAreaHectares());
            wmzProps.put("buffer250mAreaHectares", wmz.buffer250mAreaHectares());
            wmzProps.put("clearanceFromDirectRowEdgeMeters", wmz.clearanceFromDirectRowEdgeMeters());
            wmzProps.put("recommendedMitigation", wmz.recommendedMitigation());
            wmzProps.put("recommendedEngineeringMitigation", wmz.recommendedMitigation());
            wmzProps.put("centroidLat", wmz.point().lat());
            wmzProps.put("centroidLon", wmz.point().lon());

            features.add(Map.of(
                    "type", "Feature",
                    "properties", wmzProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(wmz.point().lon(), wmz.point().lat()))));
        }

        // 15. Corridor Wetlands Polygons & Micro-Drainage Channels (L13 Map Context Overlay)
        if (drainageVectorIndex != null) {
            double cMinLat = sMinLat - 0.22;
            double cMaxLat = sMaxLat + 0.22;
            double cMinLon = sMinLon - 0.22;
            double cMaxLon = sMaxLon + 0.22;
            boolean hasMainlineBbox = !mainline.isEmpty() && Double.isFinite(sMinLat);

            for (DrainageWetlandVectorIndex.WetlandPolygon wp : drainageVectorIndex.allWetlands()) {
                GeoPoint cg = wp.centroidGeo();
                if (hasMainlineBbox && (cg.lat() < cMinLat || cg.lat() > cMaxLat
                        || cg.lon() < cMinLon || cg.lon() > cMaxLon)) {
                    continue;
                }
                List<GeoPoint> ringPts = wp.boundaryRing();
                if (ringPts == null || ringPts.size() < 4) {
                    continue;
                }
                List<List<Double>> ringCoords = new ArrayList<>(ringPts.size() + 1);
                for (GeoPoint rp : ringPts) {
                    ringCoords.add(List.of(
                            Math.round(rp.lon() * 100000.0) / 100000.0,
                            Math.round(rp.lat() * 100000.0) / 100000.0));
                }
                if (!ringCoords.get(0).equals(ringCoords.get(ringCoords.size() - 1))) {
                    ringCoords.add(ringCoords.get(0));
                }
                Map<String, Object> wpProps = new LinkedHashMap<>();
                wpProps.put("featureRole", "CORRIDOR_WETLAND_POLYGON");
                wpProps.put("wetlandId", wp.id());
                wpProps.put("wetlandName", wp.name());
                wpProps.put("wetlandCategory", wp.wetlandCategory());
                wpProps.put("district", wp.district());
                wpProps.put("watershedId", wp.watershedId());
                wpProps.put("areaHectares", round(wp.areaHectares()));
                wpProps.put("isProtectedBeel", wp.isProtectedBeel());
                wpProps.put("isMitigatedNearRoute", emittedWetlandIds.contains(wp.id()));
                wpProps.put("ecologicalAndHydrologicalSensitivity", wp.ecologicalAndHydrologicalSensitivity());
                wpProps.put("recommendedMitigation", wp.recommendedMitigation());
                wpProps.put("centroidLat", round(cg.lat()));
                wpProps.put("centroidLon", round(cg.lon()));
                features.add(Map.of(
                        "type", "Feature",
                        "properties", wpProps,
                        "geometry", Map.of(
                                "type", "Polygon",
                                "coordinates", List.of(ringCoords))));
            }

            // Also include any authoritative Pan-India Wetlands Registry polygons within the corridor window
            // that are not already covered by a local polygon centroid within ~1.2 km
            if (hasMainlineBbox) {
                for (in.marg.drainage.IndianWetlandRegistry.MappedWetland mw :
                        in.marg.drainage.IndianWetlandRegistry.getDefaultInstance()
                                .findWetlandsInWindow(cMinLat, cMinLon, cMaxLat, cMaxLon)) {
                    boolean duplicate = false;
                    for (DrainageWetlandVectorIndex.WetlandPolygon localWp : drainageVectorIndex.allWetlands()) {
                        double dLat = localWp.centroidGeo().lat() - mw.centerLat();
                        double dLon = localWp.centroidGeo().lon() - mw.centerLon();
                        if (Math.hypot(dLat, dLon) < 0.012) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (duplicate) {
                        continue;
                    }
                    List<List<Double>> ringCoords = new ArrayList<>(mw.polygonLonLat().size());
                    for (double[] pt : mw.polygonLonLat()) {
                        ringCoords.add(List.of(
                                Math.round(pt[0] * 100000.0) / 100000.0,
                                Math.round(pt[1] * 100000.0) / 100000.0));
                    }
                    Map<String, Object> mwProps = new LinkedHashMap<>();
                    mwProps.put("featureRole", "CORRIDOR_WETLAND_POLYGON");
                    mwProps.put("wetlandId", mw.id());
                    mwProps.put("wetlandName", mw.name());
                    mwProps.put("wetlandCategory", mw.wetlandCategory());
                    mwProps.put("district", mw.district());
                    mwProps.put("state", mw.state());
                    mwProps.put("basin", mw.basin());
                    mwProps.put("areaHectares", round(mw.areaHectares()));
                    mwProps.put("isRamsar", mw.isRamsar());
                    mwProps.put("isProtectedBeel", mw.isProtectedWetland());
                    mwProps.put("legalStatute", mw.legalStatute());
                    mwProps.put("recommendedMitigation", "WETLAND_AVOIDANCE_OR_PILE_VIADUCT_EQUALIZER_CAUSEWAY");
                    mwProps.put("centroidLat", mw.centerLat());
                    mwProps.put("centroidLon", mw.centerLon());
                    features.add(Map.of(
                            "type", "Feature",
                            "properties", mwProps,
                            "geometry", Map.of(
                                    "type", "Polygon",
                                    "coordinates", List.of(ringCoords))));
                }
            }

            // Emit local seasonal streams, irrigation canals & drainage channels inside corridor bbox
            double dMinLat = sMinLat - 0.16;
            double dMaxLat = sMaxLat + 0.16;
            double dMinLon = sMinLon - 0.16;
            double dMaxLon = sMaxLon + 0.16;
            int emittedChannels = 0;
            for (DrainageWetlandVectorIndex.DrainageChannelLine dl : drainageVectorIndex.allDrainageLines()) {
                if (emittedChannels >= 200) {
                    break;
                }
                List<GeoPoint> pts = dl.coordinates();
                if (pts == null || pts.size() < 2) {
                    continue;
                }
                if (hasMainlineBbox) {
                    boolean anyInside = false;
                    for (GeoPoint p : pts) {
                        if (p.lat() >= dMinLat && p.lat() <= dMaxLat
                                && p.lon() >= dMinLon && p.lon() <= dMaxLon) {
                            anyInside = true;
                            break;
                        }
                    }
                    if (!anyInside) {
                        continue;
                    }
                }
                List<List<Double>> lineCoords = new ArrayList<>(pts.size());
                for (GeoPoint p : pts) {
                    lineCoords.add(List.of(
                            Math.round(p.lon() * 100000.0) / 100000.0,
                            Math.round(p.lat() * 100000.0) / 100000.0));
                }
                Map<String, Object> dlProps = new LinkedHashMap<>();
                dlProps.put("featureRole", "CORRIDOR_DRAINAGE_CHANNEL");
                dlProps.put("channelId", dl.id());
                dlProps.put("channelName", dl.name());
                dlProps.put("sublayerType", dl.sublayerType().name());
                dlProps.put("strahlerOrder", dl.strahlerOrder());
                dlProps.put("watershedId", dl.watershedId());
                dlProps.put("designWidthMeters", round(dl.designWidthMeters()));
                dlProps.put("recommendedStructureType", dl.recommendedStructureType());
                features.add(Map.of(
                        "type", "Feature",
                        "properties", dlProps,
                        "geometry", Map.of(
                                "type", "LineString",
                                "coordinates", lineCoords)));
                emittedChannels++;
            }
        }

        return List.copyOf(features);
    }

    /**
     * Evaluates 13-layer engineering crossing structures (Railway ROBs, River Bridges, Wildlife
     * Passages, Geotechnical Mitigation Zones, EHV Utility Crossings, and Cross-Drainage Culverts)
     * along every dedicated feeder spur path (30 m IRC spur RoW), tagging each GeoJSON feature
     * with {@code segmentRole = "FEEDER_SPUR"} so mainline KPIs and existing assertions remain intact.
     */
    public record SpurEngineeringAnalysis(
            List<Map<String, Object>> features,
            Map<String, Object> summary) {}

    public SpurEngineeringAnalysis analyzeFeederSpurStructures(NetworkPlanResult plan) {
        List<Map<String, Object>> spurFeatures = new ArrayList<>();
        int railwayRobs = 0;
        int riverBridges = 0;
        int wildlifePassages = 0;
        int geotechZones = 0;
        int utilityCrossings = 0;
        int drainageStructures = 0;

        List<PlannedFeederSpur> spurs = plan != null && plan.feederSpurs() != null
                ? plan.feederSpurs()
                : List.of();

        for (PlannedFeederSpur spur : spurs) {
            List<GeoPoint> path = spur.path();
            if (path == null || path.size() < 2) {
                continue;
            }
            String spurId = spur.spurId();
            String hubName = spur.hub() != null ? spur.hub().name() : "Regional Hub";

            // L6: Mandatory Railway ROBs along feeder spur (road tie-ins are at-grade on spurs)
            if (vectorIndex != null) {
                for (TransportNetworkVectorIndex.RouteCrossing rc : vectorIndex.findRouteCrossings(path)) {
                    if (rc.category() != TransportCategory.RAILWAY) {
                        continue;
                    }
                    railwayRobs++;
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("featureRole", "GRADE_SEPARATED_CROSSING");
                    props.put("segmentRole", "FEEDER_SPUR");
                    props.put("parentSpurId", spurId);
                    props.put("servedHubName", hubName);
                    props.put("crossingId", "SPUR-ROB-" + railwayRobs + "-" + rc.corridorRef());
                    props.put("crossedCorridorRef", rc.corridorRef());
                    props.put("crossedCorridorName", rc.corridorName());
                    props.put("crossedCategory", rc.category().name());
                    props.put("structureType", rc.category().defaultCrossingStructureType());
                    props.put("chainageKm", round(rc.chainageKm()));
                    props.put("crossingAngleDegrees", round(rc.crossingAngleDegrees()));
                    spurFeatures.add(Map.of(
                            "type", "Feature",
                            "properties", props,
                            "geometry", Map.of("type", "Point", "coordinates", List.of(rc.point().lon(), rc.point().lat()))));
                }
            }

            // L7: Perennial River Bridges along feeder spur
            if (riverVectorIndex != null) {
                for (RiverNetworkVectorIndex.RouteRiverCrossing rrc : riverVectorIndex.findRouteRiverCrossings(path)) {
                    riverBridges++;
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("featureRole", "RIVER_BRIDGE");
                    props.put("segmentRole", "FEEDER_SPUR");
                    props.put("parentSpurId", spurId);
                    props.put("servedHubName", hubName);
                    props.put("bridgeId", "SPUR-RB-" + riverBridges + "-" + rrc.riverId().replace("RIV-", ""));
                    props.put("riverName", rrc.riverName());
                    props.put("riverClass", rrc.riverClass().name());
                    props.put("structureType", rrc.riverClass().bridgeStructureType());
                    props.put("estimatedSkewSpanMeters", round(rrc.effectiveSkewSpanMeters()));
                    props.put("effectiveSkewSpanMeters", round(rrc.effectiveSkewSpanMeters()));
                    props.put("crossingAngleDegrees", round(rrc.crossingAngleDegrees()));
                    props.put("chainageKm", round(rrc.chainageKm()));
                    spurFeatures.add(Map.of(
                            "type", "Feature",
                            "properties", props,
                            "geometry", Map.of("type", "Point", "coordinates", List.of(rrc.point().lon(), rrc.point().lat()))));
                }
            }

            // L8: Wildlife Corridor Passages along feeder spur
            if (wildlifeVectorIndex != null) {
                for (WildlifeCorridorVectorIndex.RouteWildlifeCrossing rwc : wildlifeVectorIndex.findRouteCorridorCrossings(path)) {
                    wildlifePassages++;
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("featureRole", "WILDLIFE_PASSAGE");
                    props.put("segmentRole", "FEEDER_SPUR");
                    props.put("parentSpurId", spurId);
                    props.put("servedHubName", hubName);
                    props.put("passageId", "SPUR-WP-" + wildlifePassages + "-" + rwc.corridorId().replace("WLC-", ""));
                    props.put("corridorName", rwc.corridorName());
                    props.put("corridorTier", rwc.corridorTier().name());
                    props.put("mitigationStructure", rwc.corridorTier().passageStructureType());
                    props.put("recommendedPassageSpanMeters", round(rwc.recommendedPassageSpanMeters()));
                    props.put("crossingAngleDegrees", round(rwc.crossingAngleDegrees()));
                    props.put("chainageKm", round(rwc.chainageKm()));
                    spurFeatures.add(Map.of(
                            "type", "Feature",
                            "properties", props,
                            "geometry", Map.of("type", "Point", "coordinates", List.of(rwc.point().lon(), rwc.point().lat()))));
                }
            }

            // L9: Geotechnical & Landslide Stabilization Zones along feeder spur
            if (geotechVectorIndex != null) {
                for (GeotechLineamentVectorIndex.RouteGeotechCrossing rgc : geotechVectorIndex.findRouteLineamentCrossings(path)) {
                    geotechZones++;
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("featureRole", "GEOTECH_MITIGATION_ZONE");
                    props.put("segmentRole", "FEEDER_SPUR");
                    props.put("parentSpurId", spurId);
                    props.put("servedHubName", hubName);
                    props.put("mitigationId", "SPUR-GM-" + geotechZones + "-" + rgc.lineamentId().replace("GEO-", ""));
                    props.put("name", rgc.lineamentName());
                    props.put("featureName", rgc.lineamentName());
                    props.put("hazardTier", rgc.hazardTier().name());
                    props.put("mitigationStructure", rgc.hazardTier().mitigationStructureType());
                    props.put("recommendedStabilizationLengthMeters", round(rgc.recommendedStabilizationLengthMeters()));
                    props.put("crossingAngleDegrees", round(rgc.crossingAngleDegrees()));
                    props.put("chainageKm", round(rgc.chainageKm()));
                    spurFeatures.add(Map.of(
                            "type", "Feature",
                            "properties", props,
                            "geometry", Map.of("type", "Point", "coordinates", List.of(rgc.point().lon(), rgc.point().lat()))));
                }
            }

            // L10: High-Voltage Utility Crossings along feeder spur
            if (utilityVectorIndex != null) {
                for (UtilityNetworkVectorIndex.RouteUtilityCrossing ruc : utilityVectorIndex.findRouteUtilityCrossings(path)) {
                    utilityCrossings++;
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("featureRole", "UTILITY_CROSSING_PROTECTION");
                    props.put("segmentRole", "FEEDER_SPUR");
                    props.put("parentSpurId", spurId);
                    props.put("servedHubName", hubName);
                    props.put("crossingId", "SPUR-UC-" + utilityCrossings + "-" + ruc.lineId().replace("PWR-", ""));
                    props.put("lineName", ruc.lineName());
                    props.put("utilityTier", ruc.tier().name());
                    props.put("voltageKv", ruc.voltageKv());
                    props.put("protectionType", ruc.tier().crossingProtectionStructureType());
                    props.put("recommendedProtectiveSpanMeters", round(ruc.recommendedProtectiveSpanMeters()));
                    props.put("crossingAngleDegrees", round(ruc.crossingAngleDegrees()));
                    props.put("chainageKm", round(ruc.chainageKm()));
                    spurFeatures.add(Map.of(
                            "type", "Feature",
                            "properties", props,
                            "geometry", Map.of("type", "Point", "coordinates", List.of(ruc.point().lon(), ruc.point().lat()))));
                }
            }

            // L13: Cross-Drainage Culverts / Aqueducts along feeder spur (30 m IRC spur footprint)
            if (drainageVectorIndex != null && rowCorridorEngine != null) {
                RightOfWayCorridorEngine.CorridorFootprint spurFp =
                        rowCorridorEngine.buildCorridorFootprint(path, FacilityMode.NATIONAL_HIGHWAY);
                DrainageWetlandVectorIndex.RouteDrainageWetlandImpact dwImpact =
                        drainageVectorIndex.evaluateCorridorDrainageAndWetlandImpact(path, spurFp);
                for (DrainageWetlandVectorIndex.DrainageInfrastructureRequirement req :
                        dwImpact.drainageInfrastructureRequirements()) {
                    drainageStructures++;
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("featureRole", "DRAINAGE_CROSSING_STRUCTURE");
                    props.put("segmentRole", "FEEDER_SPUR");
                    props.put("parentSpurId", spurId);
                    props.put("servedHubName", hubName);
                    props.put("structureId", "SPUR-" + req.requirementId());
                    props.put("targetFeatureName", req.targetFeatureName());
                    props.put("sublayerType", req.sublayerType().name());
                    props.put("structureType", req.structureType());
                    props.put("chainageKm", req.chainageKm());
                    props.put("recommendedClearSpanOrWidthMeters", req.recommendedClearSpanOrWidthMeters());
                    props.put("designDischargeM3PerSec", req.designDischargeM3PerSec());
                    spurFeatures.add(Map.of(
                            "type", "Feature",
                            "properties", props,
                            "geometry", Map.of("type", "Point", "coordinates", List.of(req.location().lon(), req.location().lat()))));
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("spursEvaluated", spurs.size());
        summary.put("spurRailwayRobsCount", railwayRobs);
        summary.put("spurRiverBridgesCount", riverBridges);
        summary.put("spurWildlifePassagesCount", wildlifePassages);
        summary.put("spurGeotechZonesCount", geotechZones);
        summary.put("spurUtilityCrossingsCount", utilityCrossings);
        summary.put("spurDrainageStructuresCount", drainageStructures);
        summary.put("totalSpurEngineeringStructuresCount",
                railwayRobs + riverBridges + wildlifePassages + geotechZones + utilityCrossings + drainageStructures);
        return new SpurEngineeringAnalysis(List.copyOf(spurFeatures), summary);
    }

    private static List<List<Double>> toLonLatList(List<GeoPoint> points) {
        return points.stream()
                .map(p -> List.of(p.lon(), p.lat()))
                .toList();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record ChainageSample(GeoPoint point, MetricPoint metric, double chainageKm) {}

    private record InterchangeCandidate(
            GeoPoint point,
            double chainageKm,
            Integer crossingIndex,
            String interchangeType,
            String placementRule,
            String connectedRoadRef
    ) {}

    public record PlannedInterchange(
            String interchangeId,
            GeoPoint point,
            double chainageKm,
            String interchangeType,
            String placementRule,
            String connectedRoadRef,
            String servedHubId,
            String servedHubName,
            boolean immutableOverride
    ) {}

    public record PlannedFeederSpur(
            String spurId,
            String interchangeId,
            RegionalHub hub,
            List<GeoPoint> path,
            double spurLengthKm,
            double inducedPeakTrafficPcuHr,
            double hviScore,
            boolean immutableOverride,
            String decisionStatus,
            String decisionReason
    ) {
        public PlannedFeederSpur(
                String spurId,
                String interchangeId,
                RegionalHub hub,
                List<GeoPoint> path,
                double spurLengthKm,
                double inducedPeakTrafficPcuHr,
                double hviScore,
                boolean immutableOverride) {
            this(spurId, interchangeId, hub, path, spurLengthKm, inducedPeakTrafficPcuHr, hviScore, immutableOverride, null, null);
        }
    }

    public record GradeSeparatedCrossingStructure(
            String structureId,
            GeoPoint point,
            double chainageKm,
            String corridorId,
            String corridorRef,
            String corridorName,
            TransportCategory category,
            String structureType,
            double crossingAngleDegrees,
            double skewBridgeMultiplier,
            double structureCostKmEquivalent
    ) {}

    public record PlannedRiverBridge(
            String bridgeId,
            GeoPoint point,
            double chainageKm,
            String riverId,
            String riverName,
            RiverHierarchyClass riverClass,
            String basin,
            String structureType,
            double designChannelWidthMeters,
            double estimatedSkewSpanMeters,
            double crossingAngleDegrees,
            double hydraulicSkewMultiplier,
            double bridgeCostKmEquivalent
    ) {}

    public record PlannedWildlifePassage(
            String passageId,
            GeoPoint point,
            double chainageKm,
            String corridorId,
            String corridorName,
            WildlifeCorridorTier corridorTier,
            String targetSpecies,
            String structureType,
            double designCorridorWidthMeters,
            double recommendedClearSpanMeters,
            double minimumClearHeightMeters,
            double crossingAngleDegrees,
            double barrierSkewMultiplier,
            double passageCostKmEquivalent
    ) {}

    public record PlannedGeotechMitigation(
            String mitigationId,
            GeoPoint point,
            double chainageKm,
            String lineamentId,
            String lineamentName,
            GeotechHazardTier hazardTier,
            String lithologyAndSoil,
            String engineeringHazard,
            String structureType,
            double designHazardWidthMeters,
            double recommendedStabilizationLengthMeters,
            double crossingAngleDegrees,
            double scarpSkewMultiplier,
            double mitigationCostKmEquivalent
    ) {}

    public record PlannedUtilityCrossing(
            String crossingId,
            GeoPoint point,
            double chainageKm,
            String lineId,
            String lineName,
            UtilityCorridorTier tier,
            Integer voltageKv,
            String circuit,
            String protectionStructureType,
            double designRowWidthMeters,
            double minimumConductorClearanceMeters,
            double recommendedProtectiveSpanMeters,
            double crossingAngleDegrees,
            double skewMultiplier,
            double nearestTowerDistanceMeters,
            double crossingCostKmEquivalent
    ) {}

    public record PlannedSettlementConflictZone(
            String conflictId,
            GeoPoint point,
            double chainageKm,
            String settlementId,
            String settlementName,
            SettlementClassification settlementClassification,
            String settlementSubtype,
            String district,
            double builtUpDensityBuildingsPerHa,
            String builtUpDensityClass,
            int buildingsInDirectRowCount,
            int buildingsInConstructionZoneCount,
            Map<String, Integer> buildingsInDirectRowByType,
            double affectedDirectRowFootprintSqMeters,
            double areaInDirectRowHectares,
            double areaInConstructionZoneHectares,
            double distanceToCenterlineMeters,
            double distanceToRowEdgeMeters,
            String recommendedEngineeringMitigation,
            double conflictCostKmEquivalent
    ) {}

    public record PlannedDemographicExposureZone(
            String exposureZoneId,
            GeoPoint point,
            double chainageKm,
            String tractId,
            String settlementId,
            String settlementName,
            String district,
            String settlementClassification,
            String ruralOrUrbanCategory,
            int totalPopulation,
            int ruralPopulation,
            int urbanPopulation,
            double populationDensityPerSqKm,
            String populationDensityClass,
            DemographicVulnerabilityTier vulnerabilityTier,
            double socialVulnerabilityIndex,
            int populationInDirectRow,
            int populationWithin100mBuffer,
            int populationWithin250mBuffer,
            int populationWithin500mBuffer,
            int estimatedDirectlyDisplacedPopulation,
            int estimatedDirectlyDisplacedHouseholds,
            int nonDisplacedBufferExposedWithin250m,
            int vulnerableAgePopulationWithin250m,
            int scheduledTribeAndCastesWithin250m,
            double distanceToCenterlineMeters,
            double distanceToRowEdgeMeters,
            String recommendedSocialMitigation,
            double demographicExposureScoreKmEq
    ) {}

    public record PlannedDrainageStructure(
            String structureId,
            GeoPoint point,
            double chainageKm,
            String targetFeatureId,
            String targetFeatureName,
            DrainageSublayerType sublayerType,
            String structureType,
            double recommendedClearSpanOrWidthMeters,
            double designDischargeM3PerSec,
            String engineeringSpecification
    ) {}

    public record PlannedWetlandMitigationZone(
            String zoneId,
            GeoPoint point,
            String wetlandId,
            String wetlandName,
            String wetlandCategory,
            String district,
            String watershedId,
            boolean isProtectedBeel,
            String highestExposureZone,
            double directRowAreaSqMeters,
            double directRowAreaHectares,
            double buffer100mAreaHectares,
            double buffer250mAreaHectares,
            double clearanceFromDirectRowEdgeMeters,
            String recommendedMitigation
    ) {}

    public record NetworkPlanResult(
            FacilityMode facilityMode,
            double mainlineLengthKm,
            double totalFeederSpurLengthKm,
            double totalConstructedHighwayAndSpurKm,
            List<PlannedInterchange> interchanges,
            List<PlannedFeederSpur> feederSpurs,
            List<GradeSeparatedCrossingStructure> gradeSeparatedCrossings,
            List<PlannedRiverBridge> riverBridges,
            List<PlannedWildlifePassage> wildlifePassages,
            List<PlannedGeotechMitigation> geotechMitigations,
            List<PlannedUtilityCrossing> utilityCrossings,
            List<PlannedSettlementConflictZone> settlementConflictZones,
            List<PlannedDemographicExposureZone> demographicExposureZones,
            List<PlannedDrainageStructure> drainageStructures,
            List<PlannedWetlandMitigationZone> wetlandMitigationZones,
            int totalInfrastructureCrossingsCount,
            int railwayRobCount,
            int roadFlyoverCount,
            double averageCrossingAngleDegrees,
            double totalEstimatedRiverBridgeSpanMeters,
            double averageRiverCrossingAngleDegrees,
            double totalRecommendedWildlifePassageSpanMeters,
            double averageWildlifeCrossingAngleDegrees,
            double totalRecommendedGeotechStabilizationMeters,
            double averageGeotechCrossingAngleDegrees,
            double totalRecommendedUtilityProtectiveSpanMeters,
            double averageUtilityCrossingAngleDegrees,
            int buildingsInDirectRowCount,
            int settlementsIntersectingDirectRowCount,
            int populationWithinDirectRow,
            int estimatedDirectlyDisplacedPopulation,
            int populationWithin250mEnvironmentalBuffer,
            int populationWithin500mSecondaryBuffer,
            int totalWaterCrossingsCount,
            int wetlandsInDirectRowCount,
            double wetlandAreaInDirectRowHectares,
            int watershedBoundaryCrossingsCount,
            double totalRecommendedDrainageSpanMeters,
            double projectedTrafficVolumePcuHr,
            double designCapacityPcuHr,
            double projectedVcRatio,
            String levelOfService,
            boolean overCapacityWarning,
            int totalCatchmentPopulationServed,
            RightOfWayCorridorEngine.CorridorFootprint rowCorridorFootprint
    ) {}
}
