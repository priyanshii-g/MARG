package in.marg.routing;

import in.marg.building.BuildingDensityClass;
import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.config.BuildingRoutingProperties;
import in.marg.config.DemographicRoutingProperties;
import in.marg.config.DrainageRoutingProperties;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.config.UtilityRoutingProperties;
import in.marg.config.WaterBodyRoutingProperties;
import in.marg.config.WildlifeRoutingProperties;
import in.marg.config.GeotechRoutingProperties;
import in.marg.demographic.DemographicVectorIndex;
import in.marg.demographic.PopulationDensityClass;
import in.marg.demographic.PopulationDensityRasterProvider;
import in.marg.drainage.DrainageWetlandClass;
import in.marg.drainage.DrainageWetlandRasterProvider;
import in.marg.drainage.DrainageWetlandVectorIndex;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.flood.FloodHazardClass;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.floodraster.FloodRasterSample;
import in.marg.geotech.GeotechHazardClass;
import in.marg.geotech.GeotechHazardTier;
import in.marg.geotech.GeotechLineamentVectorIndex;
import in.marg.geotech.GeotechRasterProvider;
import in.marg.hydrology.RiverHierarchyClass;
import in.marg.hydrology.RiverNetworkVectorIndex;
import in.marg.hydrology.WaterBodyClass;
import in.marg.hydrology.WaterBodyRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.provider.LulcProvider;
import in.marg.provider.LulcSample;
import in.marg.settlement.SettlementExposureClass;
import in.marg.settlement.SettlementRasterProvider;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.terrain.SlopeRasterProvider;
import in.marg.transport.FacilityMode;
import in.marg.transport.TransportCategory;
import in.marg.transport.TransportCorridorClass;
import in.marg.transport.TransportNetworkRasterProvider;
import in.marg.transport.TransportNetworkVectorIndex;
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

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CompositeRouteMetricsService {

    /*
     * Diagnostic sampling spacing.
     *
     * The actual routing grid is 250 m. We sample diagnostics
     * every <=125 m along the returned geometry so that long
     * collinear segments do not hide intermediate raster classes.
     */
    private static final double DIAGNOSTIC_SAMPLE_SPACING_METERS = 125.0;

    private final CoordinateProjection projection;
    private final LulcProvider lulcProvider;
    private final LulcRoutingProperties lulcProperties;
    private final BhuvanFloodRasterCache floodRasterCache;
    private final FloodRoutingProperties floodProperties;
    private final SlopeRasterProvider slopeRasterProvider;
    private final SlopeRoutingProperties slopeProperties;
    private final ProtectedAreaRasterProvider protectedAreaRasterProvider;
    private final SettlementRasterProvider settlementRasterProvider;
    private final SettlementRoutingProperties settlementProperties;
    private final TransportNetworkRasterProvider transportRasterProvider;
    private final TransportNetworkVectorIndex transportVectorIndex;
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

    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties) {
        this(
                projection,
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeRasterProvider,
                slopeProperties,
                protectedAreaRasterProvider,
                settlementRasterProvider,
                settlementProperties,
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
                null,
                null,
                null,
                null);
    }

    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
            TransportRoutingProperties transportProperties) {
        this(
                projection,
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeRasterProvider,
                slopeProperties,
                protectedAreaRasterProvider,
                settlementRasterProvider,
                settlementProperties,
                transportRasterProvider,
                transportVectorIndex,
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

    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties) {
        this(
                projection,
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeRasterProvider,
                slopeProperties,
                protectedAreaRasterProvider,
                settlementRasterProvider,
                settlementProperties,
                transportRasterProvider,
                transportVectorIndex,
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

    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
            TransportRoutingProperties transportProperties,
            WaterBodyRasterProvider waterBodyRasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRasterProvider wildlifeRasterProvider,
            WildlifeCorridorVectorIndex wildlifeVectorIndex,
            WildlifeRoutingProperties wildlifeProperties) {
        this(
                projection,
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeRasterProvider,
                slopeProperties,
                protectedAreaRasterProvider,
                settlementRasterProvider,
                settlementProperties,
                transportRasterProvider,
                transportVectorIndex,
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

    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
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
                projection,
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeRasterProvider,
                slopeProperties,
                protectedAreaRasterProvider,
                settlementRasterProvider,
                settlementProperties,
                transportRasterProvider,
                transportVectorIndex,
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

    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
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
                projection,
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeRasterProvider,
                slopeProperties,
                protectedAreaRasterProvider,
                settlementRasterProvider,
                settlementProperties,
                transportRasterProvider,
                transportVectorIndex,
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
                new RightOfWayCorridorEngine(projection));
    }

    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
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
                projection,
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeRasterProvider,
                slopeProperties,
                protectedAreaRasterProvider,
                settlementRasterProvider,
                settlementProperties,
                transportRasterProvider,
                transportVectorIndex,
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

    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
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
                projection,
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeRasterProvider,
                slopeProperties,
                protectedAreaRasterProvider,
                settlementRasterProvider,
                settlementProperties,
                transportRasterProvider,
                transportVectorIndex,
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

    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
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
                projection,
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeRasterProvider,
                slopeProperties,
                protectedAreaRasterProvider,
                settlementRasterProvider,
                settlementProperties,
                transportRasterProvider,
                transportVectorIndex,
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
    public CompositeRouteMetricsService(
            CoordinateProjection projection,
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties slopeProperties,
            ProtectedAreaRasterProvider protectedAreaRasterProvider,
            SettlementRasterProvider settlementRasterProvider,
            SettlementRoutingProperties settlementProperties,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
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

        this.projection = projection;
        this.lulcProvider = lulcProvider;
        this.lulcProperties = lulcProperties;
        this.floodRasterCache = floodRasterCache;
        this.floodProperties = floodProperties;
        this.slopeRasterProvider = slopeRasterProvider;
        this.slopeProperties = slopeProperties;
        this.protectedAreaRasterProvider = protectedAreaRasterProvider;
        this.settlementRasterProvider = settlementRasterProvider;
        this.settlementProperties = settlementProperties;
        this.transportRasterProvider = transportRasterProvider;
        this.transportVectorIndex = transportVectorIndex;
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

    public Map<String, Object> summarize(List<GeoPoint> route) {
        return summarize(route, FacilityMode.NATIONAL_HIGHWAY, true);
    }

    public Map<String, Object> summarize(List<GeoPoint> route, FacilityMode facilityMode) {
        return summarize(route, facilityMode, true);
    }

    public Map<String, Object> summarize(List<GeoPoint> route, FacilityMode facilityMode, boolean includeSwathAudit) {

        EnumMap<LulcClass, Double> lulcKm = new EnumMap<>(LulcClass.class);
        EnumMap<FloodHazardClass, Double> floodKm = new EnumMap<>(FloodHazardClass.class);
        LinkedHashMap<String, Double> slopeKm = new LinkedHashMap<>();
        EnumMap<SettlementExposureClass, Double> settlementKm =
                new EnumMap<>(SettlementExposureClass.class);
        EnumMap<TransportCorridorClass, Double> transportKm =
                new EnumMap<>(TransportCorridorClass.class);
        EnumMap<WaterBodyClass, Double> waterKm =
                new EnumMap<>(WaterBodyClass.class);
        EnumMap<WildlifeZoneClass, Double> wildlifeKm =
                new EnumMap<>(WildlifeZoneClass.class);
        EnumMap<GeotechHazardClass, Double> geotechKm =
                new EnumMap<>(GeotechHazardClass.class);
        EnumMap<UtilityCorridorClass, Double> utilityKm =
                new EnumMap<>(UtilityCorridorClass.class);
        EnumMap<BuildingDensityClass, Double> buildingKm =
                new EnumMap<>(BuildingDensityClass.class);
        EnumMap<PopulationDensityClass, Double> demographicKm =
                new EnumMap<>(PopulationDensityClass.class);
        EnumMap<DrainageWetlandClass, Double> drainageKm =
                new EnumMap<>(DrainageWetlandClass.class);

        double totalKm = 0.0;

        double lulcPenaltyKm = 0.0;
        double floodPenaltyKm = 0.0;
        double slopePenaltyKm = 0.0;
        double settlementPenaltyKm = 0.0;
        double transportNodePenaltyKm = 0.0;
        double transportCrossingPenaltyKm = 0.0;
        double waterNodePenaltyKm = 0.0;
        double waterBridgePenaltyKm = 0.0;
        double wildlifeNodePenaltyKm = 0.0;
        double wildlifePassagePenaltyKm = 0.0;
        double geotechNodePenaltyKm = 0.0;
        double geotechMitigationPenaltyKm = 0.0;
        double utilityNodePenaltyKm = 0.0;
        double utilityCrossingPenaltyKm = 0.0;
        double buildingNodePenaltyKm = 0.0;
        double buildingConflictPenaltyKm = 0.0;
        double demographicNodePenaltyKm = 0.0;
        double demographicExposurePenaltyKm = 0.0;
        double drainageNodePenaltyKm = 0.0;
        double drainageEdgePenaltyKm = 0.0;
        double protectedAreaKm = 0.0;

        int diagnosticSamples = 0;
        int floodCacheMisses = 0;
        int protectedAreaSamples = 0;
        int protectedAreaNoDataSamples = 0;
        int slopeOverLimitSamples = 0;
        int slopeNoDataSamples = 0;
        int wetlandViolationSamples = 0;
        int criticalCorridorViolationSamples = 0;
        int activeLandslideViolationSamples = 0;
        int substationTowerViolationSamples = 0;
        int institutionalReligiousViolationSamples = 0;
        int veryHighUrbanDensityViolationSamples = 0;

        TransportCostPolicy transportPolicy =
                transportProperties != null
                        ? transportProperties.toPolicy()
                        : TransportCostPolicy.defaultPolicy();

        WaterBodyCostPolicy waterPolicy =
                waterBodyProperties != null
                        ? waterBodyProperties.toPolicy()
                        : WaterBodyCostPolicy.defaultPolicy();

        WildlifeCostPolicy wildlifePolicy =
                wildlifeProperties != null
                        ? wildlifeProperties.toPolicy()
                        : WildlifeCostPolicy.defaultPolicy();

        GeotechCostPolicy geotechPolicy =
                geotechProperties != null
                        ? geotechProperties.toPolicy()
                        : GeotechCostPolicy.defaultPolicy();

        UtilityCostPolicy utilityPolicy =
                utilityProperties != null
                        ? utilityProperties.toPolicy()
                        : UtilityCostPolicy.defaultPolicy();

        BuildingCostPolicy buildingPolicy =
                buildingProperties != null
                        ? buildingProperties.toPolicy()
                        : BuildingCostPolicy.defaultPolicy();

        DemographicCostPolicy demographicPolicy =
                demographicProperties != null
                        ? demographicProperties.toPolicy()
                        : DemographicCostPolicy.defaultPolicy();

        DrainageWetlandCostPolicy drainagePolicy =
                drainageProperties != null
                        ? drainageProperties.toPolicy()
                        : DrainageWetlandCostPolicy.defaultPolicy();

        for (int i = 1; i < route.size(); i++) {
            GeoPoint from = route.get(i - 1);
            GeoPoint to = route.get(i);

            MetricPoint a = projection.forward(from);
            MetricPoint b = projection.forward(to);

            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double segmentMeters = Math.hypot(dx, dy);

            if (segmentMeters <= 0.0) {
                continue;
            }

            int parts = Math.max(
                    1,
                    (int) Math.ceil(segmentMeters / DIAGNOSTIC_SAMPLE_SPACING_METERS));

            double sampleLengthKm = segmentMeters / parts / 1000.0;

            for (int j = 0; j < parts; j++) {
                double t = (j + 0.5) / parts;
                MetricPoint sampleMetric = new MetricPoint(a.x() + t * dx, a.y() + t * dy);
                GeoPoint point = projection.inverse(sampleMetric);

                diagnosticSamples++;
                totalKm += sampleLengthKm;

                // LULC
                LulcSample lulcSample = lulcProvider.sample(point);
                LulcClass lulcClass = lulcSample.type();
                lulcKm.merge(lulcClass, sampleLengthKm, Double::sum);
                lulcPenaltyKm += sampleLengthKm * lulcProperties.toPolicy().penalty(lulcClass);

                // FLOOD
                FloodRasterSample floodSample = floodRasterCache.sample(point);
                if (!floodSample.cacheAvailable()) {
                    floodCacheMisses++;
                } else {
                    FloodHazardClass hazard = floodSample.hazardClass();
                    floodKm.merge(hazard, sampleLengthKm, Double::sum);
                    floodPenaltyKm += sampleLengthKm * floodProperties.toPolicy().penalty(hazard);
                }

                // SLOPE
                Double slopeDegrees = slopeRasterProvider.sampleOrNull(point.lat(), point.lon());
                String slopeBand = classifySlope(slopeDegrees);
                slopeKm.merge(slopeBand, sampleLengthKm, Double::sum);
                if (slopeDegrees == null) {
                    slopeNoDataSamples++;
                } else if (slopeDegrees > slopeProperties.getVerySteepMaxDegrees()) {
                    slopeOverLimitSamples++;
                }
                slopePenaltyKm += sampleLengthKm * slopePenalty(slopeDegrees);

                // PROTECTED AREA
                Double protectedMask =
                        protectedAreaRasterProvider.sampleOrNull(point.lat(), point.lon());
                if (protectedMask == null) {
                    protectedAreaNoDataSamples++;
                } else if (Math.round(protectedMask) >= 1L) {
                    protectedAreaSamples++;
                    protectedAreaKm += sampleLengthKm;
                }

                // SETTLEMENT EXPOSURE
                SettlementExposureClass settlementClass =
                        settlementRasterProvider.sampleClass(point.lat(), point.lon());
                settlementKm.merge(settlementClass, sampleLengthKm, Double::sum);
                settlementPenaltyKm +=
                        sampleLengthKm * settlementProperties.toPolicy().penalty(settlementClass);

                // TRANSPORT CORRIDOR RASTER EXPOSURE
                if (transportRasterProvider != null) {
                    TransportCorridorClass tc =
                            transportRasterProvider.sampleClass(point.lat(), point.lon());
                    transportKm.merge(tc, sampleLengthKm, Double::sum);
                    transportNodePenaltyKm += sampleLengthKm * transportPolicy.nodePenalty(tc);
                }

                // HYDROLOGY & WATER-BODY RASTER EXPOSURE
                if (waterBodyRasterProvider != null) {
                    WaterBodyClass wc =
                            waterBodyRasterProvider.sampleClass(point.lat(), point.lon());
                    waterKm.merge(wc, sampleLengthKm, Double::sum);
                    if (wc == WaterBodyClass.INLAND_WETLAND_WATERBODY && waterPolicy.blockWetlands()) {
                        wetlandViolationSamples++;
                    }
                    waterNodePenaltyKm += sampleLengthKm * waterPolicy.nodePenalty(wc);
                }

                // WILDLIFE CORRIDOR & ESZ RASTER EXPOSURE
                if (wildlifeRasterProvider != null) {
                    WildlifeZoneClass wzc =
                            wildlifeRasterProvider.sampleClass(point.lat(), point.lon());
                    wildlifeKm.merge(wzc, sampleLengthKm, Double::sum);
                    if (wzc == WildlifeZoneClass.CRITICAL_MIGRATION_CORRIDOR_CORE
                            && wildlifePolicy.blockCriticalCorridors()) {
                        criticalCorridorViolationSamples++;
                    }
                    wildlifeNodePenaltyKm += sampleLengthKm * wildlifePolicy.nodePenalty(wzc);
                }

                // GEOTECHNICAL, SOIL BEARING & LANDSLIDE RASTER EXPOSURE
                if (geotechRasterProvider != null) {
                    GeotechHazardClass ghc =
                            geotechRasterProvider.sampleClass(point.lat(), point.lon());
                    geotechKm.merge(ghc, sampleLengthKm, Double::sum);
                    if (ghc == GeotechHazardClass.ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE
                            && geotechPolicy.blockActiveLandslideZones()) {
                        activeLandslideViolationSamples++;
                    }
                    geotechNodePenaltyKm += sampleLengthKm * geotechPolicy.nodePenalty(ghc);
                }

                // UTILITY CORRIDOR RASTER EXPOSURE
                if (utilityRasterProvider != null) {
                    UtilityCorridorClass ucc =
                            utilityRasterProvider.sampleClass(point.lat(), point.lon());
                    utilityKm.merge(ucc, sampleLengthKm, Double::sum);
                    if (ucc == UtilityCorridorClass.SUBSTATION_OR_TOWER_FOOTPRINT
                            && utilityPolicy.blockSubstationsAndTowers()) {
                        substationTowerViolationSamples++;
                    }
                    utilityNodePenaltyKm += sampleLengthKm * utilityPolicy.nodePenalty(ucc);
                }

                // BUILDING DENSITY & SETTLEMENT FOOTPRINT RASTER EXPOSURE
                if (buildingRasterProvider != null) {
                    BuildingDensityClass bdc =
                            buildingRasterProvider.sampleClass(point.lat(), point.lon());
                    buildingKm.merge(bdc, sampleLengthKm, Double::sum);
                    if (bdc == BuildingDensityClass.INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT
                            && buildingPolicy.blockInstitutionalAndReligiousCompounds()) {
                        institutionalReligiousViolationSamples++;
                    }
                    buildingNodePenaltyKm += sampleLengthKm * buildingPolicy.nodePenalty(bdc);
                }

                // POPULATION DENSITY & DEMOGRAPHIC RASTER EXPOSURE
                if (demographicRasterProvider != null) {
                    PopulationDensityClass pdc =
                            demographicRasterProvider.sampleClass(point.lat(), point.lon());
                    demographicKm.merge(pdc, sampleLengthKm, Double::sum);
                    if (pdc == PopulationDensityClass.VERY_HIGH_URBAN_DENSITY_CORE
                            && demographicPolicy.blockVeryHighUrbanDensityCore()) {
                        veryHighUrbanDensityViolationSamples++;
                    }
                    demographicNodePenaltyKm += sampleLengthKm * demographicPolicy.nodePenalty(pdc);
                }

                // DRAINAGE, STREAMS & WETLANDS RASTER EXPOSURE (LAYER 13)
                if (drainageRasterProvider != null) {
                    DrainageWetlandClass dwc =
                            drainageRasterProvider.sampleClass(point.lat(), point.lon());
                    drainageKm.merge(dwc, sampleLengthKm, Double::sum);
                    drainageNodePenaltyKm += sampleLengthKm * drainagePolicy.nodePenalty(dwc);
                }
            }
        }

        // Exact 2D crossings along route (ROBs & Highway Flyovers)
        int railwayCrossings = 0;
        int roadCrossings = 0;
        double avgCrossingAngleDeg = 90.0;
        if (transportVectorIndex != null) {
            List<TransportNetworkVectorIndex.RouteCrossing> crossings =
                    transportVectorIndex.findRouteCrossings(route);
            double angleSum = 0.0;
            for (TransportNetworkVectorIndex.RouteCrossing rc : crossings) {
                if (rc.category() == TransportCategory.RAILWAY) {
                    railwayCrossings++;
                } else {
                    roadCrossings++;
                }
                angleSum += rc.crossingAngleDegrees();
                transportCrossingPenaltyKm +=
                        transportPolicy.crossingStructureCostKmEq(rc.category(), rc.sinAngle());
            }
            if (!crossings.isEmpty()) {
                avgCrossingAngleDeg = round(angleSum / crossings.size());
            }
        }

        double totalTransportPenaltyKm = transportNodePenaltyKm + transportCrossingPenaltyKm;

        // Exact 2D Perennial River & Stream Bridge Crossings along route
        int majorRiverBridges = 0;
        int mediumRiverBridges = 0;
        int minorStreamCulverts = 0;
        double totalEstimatedBridgeSpanMeters = 0.0;
        double avgRiverCrossingAngleDeg = 90.0;
        if (riverVectorIndex != null) {
            List<RiverNetworkVectorIndex.RouteRiverCrossing> riverCrossings =
                    riverVectorIndex.findRouteRiverCrossings(route);
            double riverAngleSum = 0.0;
            for (RiverNetworkVectorIndex.RouteRiverCrossing rrc : riverCrossings) {
                if (rrc.riverClass() == RiverHierarchyClass.MAJOR_PERENNIAL_RIVER) {
                    majorRiverBridges++;
                } else if (rrc.riverClass() == RiverHierarchyClass.MEDIUM_PERENNIAL_RIVER) {
                    mediumRiverBridges++;
                } else {
                    minorStreamCulverts++;
                }
                riverAngleSum += rrc.crossingAngleDegrees();
                totalEstimatedBridgeSpanMeters += rrc.effectiveSkewSpanMeters();
                waterBridgePenaltyKm +=
                        waterPolicy.riverBridgeCostKmEq(rrc.riverClass(), rrc.sinAngle());
            }
            if (!riverCrossings.isEmpty()) {
                avgRiverCrossingAngleDeg = round(riverAngleSum / riverCrossings.size());
            }
        }

        double totalHydrologyPenaltyKm = waterNodePenaltyKm + waterBridgePenaltyKm;

        // Exact 2D Wildlife Migration Corridor Crossings & Elevated Animal Passages along route
        int elevatedWildlifeViaducts = 0;
        int wideAnimalUnderpasses = 0;
        int localWildlifePassages = 0;
        double totalRecommendedWildlifePassageSpanMeters = 0.0;
        double avgWildlifeCrossingAngleDeg = 90.0;
        if (wildlifeVectorIndex != null) {
            List<WildlifeCorridorVectorIndex.RouteWildlifeCrossing> wildlifeCrossings =
                    wildlifeVectorIndex.findRouteCorridorCrossings(route);
            double wildlifeAngleSum = 0.0;
            for (WildlifeCorridorVectorIndex.RouteWildlifeCrossing rwc : wildlifeCrossings) {
                if (rwc.corridorTier() == WildlifeCorridorTier.CRITICAL_ELEPHANT_RHINO_CORRIDOR) {
                    elevatedWildlifeViaducts++;
                } else if (rwc.corridorTier() == WildlifeCorridorTier.INTER_SANCTUARY_ELEPHANT_CORRIDOR) {
                    wideAnimalUnderpasses++;
                } else {
                    localWildlifePassages++;
                }
                wildlifeAngleSum += rwc.crossingAngleDegrees();
                totalRecommendedWildlifePassageSpanMeters += rwc.recommendedPassageSpanMeters();
                wildlifePassagePenaltyKm +=
                        wildlifePolicy.wildlifePassageCostKmEq(rwc.corridorTier(), rwc.sinAngle());
            }
            if (!wildlifeCrossings.isEmpty()) {
                avgWildlifeCrossingAngleDeg = round(wildlifeAngleSum / wildlifeCrossings.size());
            }
        }

        double totalWildlifePenaltyKm = wildlifeNodePenaltyKm + wildlifePassagePenaltyKm;

        // Exact 2D Geotechnical Fault/Shear, Landslide Scarp & Liquefaction Trough Crossings
        int activeFaultShearCrossings = 0;
        int colluvialLandslideScarpCrossings = 0;
        int liquefiableTroughCrossings = 0;
        double totalRecommendedGeotechStabilizationMeters = 0.0;
        double avgGeotechCrossingAngleDeg = 90.0;
        if (geotechVectorIndex != null) {
            List<GeotechLineamentVectorIndex.RouteGeotechCrossing> geotechCrossings =
                    geotechVectorIndex.findRouteLineamentCrossings(route);
            double geotechAngleSum = 0.0;
            for (GeotechLineamentVectorIndex.RouteGeotechCrossing rgc : geotechCrossings) {
                if (rgc.hazardTier() == GeotechHazardTier.ACTIVE_FAULT_SHEAR_ZONE) {
                    activeFaultShearCrossings++;
                } else if (rgc.hazardTier() == GeotechHazardTier.UNSTABLE_COLLUVIAL_LANDSLIDE_SCARP) {
                    colluvialLandslideScarpCrossings++;
                } else {
                    liquefiableTroughCrossings++;
                }
                geotechAngleSum += rgc.crossingAngleDegrees();
                totalRecommendedGeotechStabilizationMeters +=
                        rgc.recommendedStabilizationLengthMeters();
                geotechMitigationPenaltyKm +=
                        geotechPolicy.geotechMitigationCostKmEq(rgc.hazardTier(), rgc.sinAngle());
            }
            if (!geotechCrossings.isEmpty()) {
                avgGeotechCrossingAngleDeg = round(geotechAngleSum / geotechCrossings.size());
            }
        }

        double totalGeotechPenaltyKm = geotechNodePenaltyKm + geotechMitigationPenaltyKm;

        // Exact 2D Overhead Power Transmission Line Crossings
        int ehv400kvCrossings = 0;
        int ehv220kvCrossings = 0;
        int subtransmission132kvCrossings = 0;
        int distribution66kvCrossings = 0;
        double totalRecommendedUtilityProtectiveSpanMeters = 0.0;
        double avgUtilityCrossingAngleDeg = 90.0;
        if (utilityVectorIndex != null) {
            List<UtilityNetworkVectorIndex.RouteUtilityCrossing> utilityCrossings =
                    utilityVectorIndex.findRouteUtilityCrossings(route);
            double utilityAngleSum = 0.0;
            for (UtilityNetworkVectorIndex.RouteUtilityCrossing ruc : utilityCrossings) {
                switch (ruc.tier()) {
                    case EHV_400KV_TRANSMISSION -> ehv400kvCrossings++;
                    case EHV_220KV_TRANSMISSION -> ehv220kvCrossings++;
                    case SUBTRANSMISSION_132KV -> subtransmission132kvCrossings++;
                    case DISTRIBUTION_66KV_AND_BELOW -> distribution66kvCrossings++;
                }
                utilityAngleSum += ruc.crossingAngleDegrees();
                totalRecommendedUtilityProtectiveSpanMeters +=
                        ruc.recommendedProtectiveSpanMeters();
                utilityCrossingPenaltyKm +=
                        utilityPolicy.utilityCrossingProtectionCostKmEq(
                                ruc.tier(), ruc.sinAngle(), ruc.nearestTowerDistanceMeters());
            }
            if (!utilityCrossings.isEmpty()) {
                avgUtilityCrossingAngleDeg = round(utilityAngleSum / utilityCrossings.size());
            }
        }

        double totalUtilityPenaltyKm = utilityNodePenaltyKm + utilityCrossingPenaltyKm;

        BuildingFootprintVectorIndex.RouteBuildingAndSettlementImpact buildingRowImpact = null;
        if (buildingVectorIndex != null && rowCorridorEngine != null && route.size() >= 2) {
            FacilityMode effectiveModeForBld =
                    facilityMode != null ? facilityMode : FacilityMode.NATIONAL_HIGHWAY;
            buildingRowImpact =
                    buildingVectorIndex.evaluateRouteRowImpact(route, rowCorridorEngine, effectiveModeForBld);
            for (BuildingFootprintVectorIndex.RouteSettlementConflict rsc :
                    buildingRowImpact.settlementConflicts()) {
                buildingConflictPenaltyKm += rsc.conflictCostKmEquivalent();
            }
        }
        double totalBuildingPenaltyKm = buildingNodePenaltyKm + buildingConflictPenaltyKm;

        DemographicVectorIndex.RouteDemographicImpact demographicRowImpact = null;
        if (demographicVectorIndex != null && rowCorridorEngine != null && route.size() >= 2) {
            FacilityMode effectiveModeForDemo =
                    facilityMode != null ? facilityMode : FacilityMode.NATIONAL_HIGHWAY;
            demographicRowImpact =
                    demographicVectorIndex.evaluateRouteDemographicImpact(
                            route, rowCorridorEngine, effectiveModeForDemo, buildingVectorIndex);
            for (DemographicVectorIndex.AffectedDemographicTract adt : demographicRowImpact.affectedTracts()) {
                demographicExposurePenaltyKm += adt.exposureScore();
            }
        }
        double totalDemographicPenaltyKm = demographicNodePenaltyKm + demographicExposurePenaltyKm;

        DrainageWetlandVectorIndex.RouteDrainageWetlandImpact drainageRowImpact = null;
        if (drainageVectorIndex != null && rowCorridorEngine != null && route.size() >= 2) {
            FacilityMode effectiveModeForDrainage =
                    facilityMode != null ? facilityMode : FacilityMode.NATIONAL_HIGHWAY;
            RightOfWayCorridorEngine.CorridorFootprint drainageFp =
                    rowCorridorEngine.buildCorridorFootprint(route, effectiveModeForDrainage);
            drainageRowImpact =
                    drainageVectorIndex.evaluateCorridorDrainageAndWetlandImpact(route, drainageFp);
            drainageEdgePenaltyKm =
                    drainageRowImpact.drainageObstructionRiskIndex()
                            + (drainageRowImpact.totalWaterCrossingsCount() * 0.65);
        }
        double totalDrainagePenaltyKm = drainageNodePenaltyKm + drainageEdgePenaltyKm;

        Map<String, Double> lulcExposure = new LinkedHashMap<>();
        for (var entry : lulcKm.entrySet()) {
            lulcExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> floodExposure = new LinkedHashMap<>();
        for (var entry : floodKm.entrySet()) {
            floodExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> slopeExposure = new LinkedHashMap<>();
        for (var entry : slopeKm.entrySet()) {
            slopeExposure.put(entry.getKey(), round(entry.getValue()));
        }

        Map<String, Double> settlementExposure = new LinkedHashMap<>();
        for (var entry : settlementKm.entrySet()) {
            settlementExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> transportExposure = new LinkedHashMap<>();
        for (var entry : transportKm.entrySet()) {
            transportExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> waterBodyExposure = new LinkedHashMap<>();
        for (var entry : waterKm.entrySet()) {
            waterBodyExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> wildlifeZoneExposure = new LinkedHashMap<>();
        for (var entry : wildlifeKm.entrySet()) {
            wildlifeZoneExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> geotechHazardExposure = new LinkedHashMap<>();
        for (var entry : geotechKm.entrySet()) {
            geotechHazardExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> utilityHazardExposure = new LinkedHashMap<>();
        for (var entry : utilityKm.entrySet()) {
            utilityHazardExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> buildingDensityExposure = new LinkedHashMap<>();
        for (var entry : buildingKm.entrySet()) {
            buildingDensityExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> populationDensityExposure = new LinkedHashMap<>();
        for (var entry : demographicKm.entrySet()) {
            populationDensityExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        Map<String, Double> drainageWetlandExposure = new LinkedHashMap<>();
        for (var entry : drainageKm.entrySet()) {
            drainageWetlandExposure.put(entry.getKey().name(), round(entry.getValue()));
        }

        double approximateCompositeCostKm =
                totalKm
                        + lulcPenaltyKm
                        + floodPenaltyKm
                        + slopePenaltyKm
                        + settlementPenaltyKm
                        + totalTransportPenaltyKm
                        + totalHydrologyPenaltyKm
                        + totalWildlifePenaltyKm
                        + totalGeotechPenaltyKm
                        + totalUtilityPenaltyKm
                        + totalBuildingPenaltyKm
                        + totalDemographicPenaltyKm
                        + totalDrainagePenaltyKm;

        Map<String, Object> hardConstraints = new LinkedHashMap<>();
        hardConstraints.put("protectedAreaViolationSamples", protectedAreaSamples);
        hardConstraints.put("protectedAreaNoDataSamples", protectedAreaNoDataSamples);
        hardConstraints.put("slopeOver30ViolationSamples", slopeOverLimitSamples);
        hardConstraints.put("slopeNoDataViolationSamples", slopeNoDataSamples);
        if (waterBodyRasterProvider != null && waterPolicy.blockWetlands()) {
            hardConstraints.put("protectedWetlandViolationSamples", wetlandViolationSamples);
        }
        if (wildlifeRasterProvider != null && wildlifePolicy.blockCriticalCorridors()) {
            hardConstraints.put("criticalWildlifeCorridorViolationSamples", criticalCorridorViolationSamples);
        }
        if (geotechRasterProvider != null && geotechPolicy.blockActiveLandslideZones()) {
            hardConstraints.put("activeLandslideShearCoreViolationSamples", activeLandslideViolationSamples);
        }
        if (utilityRasterProvider != null && utilityPolicy.blockSubstationsAndTowers()) {
            hardConstraints.put("substationTowerFootprintViolationSamples", substationTowerViolationSamples);
        }
        if (buildingRasterProvider != null && buildingPolicy.blockInstitutionalAndReligiousCompounds()) {
            hardConstraints.put(
                    "institutionalReligiousCompoundViolationSamples",
                    institutionalReligiousViolationSamples);
        }
        if (demographicRasterProvider != null && demographicPolicy.blockVeryHighUrbanDensityCore()) {
            hardConstraints.put(
                    "veryHighUrbanDensityCoreViolationSamples",
                    veryHighUrbanDensityViolationSamples);
        }
        hardConstraints.put(
                "allDiagnosticSamplesPass",
                protectedAreaSamples == 0
                        && protectedAreaNoDataSamples == 0
                        && slopeOverLimitSamples == 0
                        && slopeNoDataSamples == 0
                        && wetlandViolationSamples == 0
                        && criticalCorridorViolationSamples == 0
                        && activeLandslideViolationSamples == 0
                        && substationTowerViolationSamples == 0
                        && institutionalReligiousViolationSamples == 0
                        && veryHighUrbanDensityViolationSamples == 0);

        Map<String, Double> penaltiesMap = new LinkedHashMap<>();
        penaltiesMap.put("LULC", round(lulcPenaltyKm));
        penaltiesMap.put("FLOOD", round(floodPenaltyKm));
        penaltiesMap.put("SLOPE", round(slopePenaltyKm));
        penaltiesMap.put("SETTLEMENT", round(settlementPenaltyKm));
        if (transportRasterProvider != null || transportVectorIndex != null) {
            penaltiesMap.put("TRANSPORT_NETWORK", round(totalTransportPenaltyKm));
        }
        if (waterBodyRasterProvider != null || riverVectorIndex != null) {
            penaltiesMap.put("HYDROLOGY_WATER", round(totalHydrologyPenaltyKm));
        }
        if (wildlifeRasterProvider != null || wildlifeVectorIndex != null) {
            penaltiesMap.put("WILDLIFE_CORRIDORS", round(totalWildlifePenaltyKm));
        }
        if (geotechRasterProvider != null || geotechVectorIndex != null) {
            penaltiesMap.put("GEOTECH_LANDSLIDE", round(totalGeotechPenaltyKm));
        }
        if (utilityRasterProvider != null || utilityVectorIndex != null) {
            penaltiesMap.put("UTILITY_CORRIDORS", round(totalUtilityPenaltyKm));
        }
        if (buildingRasterProvider != null || buildingVectorIndex != null) {
            penaltiesMap.put("BUILDING_FOOTPRINTS", round(totalBuildingPenaltyKm));
        }
        if (demographicRasterProvider != null || demographicVectorIndex != null) {
            penaltiesMap.put("POPULATION_DEMOGRAPHICS", round(totalDemographicPenaltyKm));
        }
        if (drainageRasterProvider != null || drainageVectorIndex != null) {
            penaltiesMap.put("DRAINAGE_AND_WETLANDS", round(totalDrainagePenaltyKm));
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("diagnosticIsApproximate", true);
        diagnostics.put("sampleSpacingMeters", DIAGNOSTIC_SAMPLE_SPACING_METERS);
        diagnostics.put("diagnosticSampleCount", diagnosticSamples);
        diagnostics.put("routeLengthKm", round(totalKm));
        diagnostics.put("lulcExposureKm", lulcExposure);
        diagnostics.put("floodExposureKm", floodExposure);
        diagnostics.put("slopeExposureKm", slopeExposure);
        diagnostics.put("settlementExposureKm", settlementExposure);
        if (transportRasterProvider != null) {
            diagnostics.put("transportCorridorExposureKm", transportExposure);
        }
        if (transportVectorIndex != null) {
            diagnostics.put("transportCrossingSummary", Map.of(
                    "railwayCrossingsCount", railwayCrossings,
                    "existingRoadCrossingsCount", roadCrossings,
                    "averageCrossingAngleDegrees", avgCrossingAngleDeg,
                    "crossingStructurePenaltyKmEquivalent", round(transportCrossingPenaltyKm),
                    "corridorOverlapPenaltyKmEquivalent", round(transportNodePenaltyKm)));
        }
        if (waterBodyRasterProvider != null) {
            diagnostics.put("waterBodyExposureKm", waterBodyExposure);
        }
        if (riverVectorIndex != null) {
            Map<String, Object> riverSummary = new LinkedHashMap<>();
            riverSummary.put("majorRiverBridgesCount", majorRiverBridges);
            riverSummary.put("mediumRiverBridgesCount", mediumRiverBridges);
            riverSummary.put("minorStreamCulvertsCount", minorStreamCulverts);
            riverSummary.put(
                    "totalRiverCrossingsCount",
                    majorRiverBridges + mediumRiverBridges + minorStreamCulverts);
            riverSummary.put("totalEstimatedBridgeSpanMeters", round(totalEstimatedBridgeSpanMeters));
            riverSummary.put("averageRiverCrossingAngleDegrees", avgRiverCrossingAngleDeg);
            riverSummary.put("bridgeStructurePenaltyKmEquivalent", round(waterBridgePenaltyKm));
            riverSummary.put("riparianAndWetlandOverlapPenaltyKmEquivalent", round(waterNodePenaltyKm));
            diagnostics.put("riverBridgeCrossingSummary", riverSummary);
        }
        if (wildlifeRasterProvider != null) {
            diagnostics.put("wildlifeZoneExposureKm", wildlifeZoneExposure);
        }
        if (wildlifeVectorIndex != null) {
            Map<String, Object> wildlifeSummary = new LinkedHashMap<>();
            wildlifeSummary.put("elevatedWildlifeViaductsCount", elevatedWildlifeViaducts);
            wildlifeSummary.put("wideAnimalUnderpassesCount", wideAnimalUnderpasses);
            wildlifeSummary.put("localWildlifePassagesCount", localWildlifePassages);
            wildlifeSummary.put(
                    "totalWildlifeCrossingsCount",
                    elevatedWildlifeViaducts + wideAnimalUnderpasses + localWildlifePassages);
            wildlifeSummary.put(
                    "totalRecommendedWildlifePassageSpanMeters",
                    round(totalRecommendedWildlifePassageSpanMeters));
            wildlifeSummary.put("averageWildlifeCrossingAngleDegrees", avgWildlifeCrossingAngleDeg);
            wildlifeSummary.put(
                    "animalPassageStructurePenaltyKmEquivalent",
                    round(wildlifePassagePenaltyKm));
            wildlifeSummary.put(
                    "eszAndCorridorOverlapPenaltyKmEquivalent",
                    round(wildlifeNodePenaltyKm));
            diagnostics.put("wildlifePassageCrossingSummary", wildlifeSummary);
        }
        if (geotechRasterProvider != null) {
            diagnostics.put("geotechHazardExposureKm", geotechHazardExposure);
        }
        if (geotechVectorIndex != null) {
            Map<String, Object> geotechSummary = new LinkedHashMap<>();
            geotechSummary.put("activeFaultShearCrossingsCount", activeFaultShearCrossings);
            geotechSummary.put(
                    "colluvialLandslideScarpCrossingsCount",
                    colluvialLandslideScarpCrossings);
            geotechSummary.put("liquefiableTroughCrossingsCount", liquefiableTroughCrossings);
            geotechSummary.put(
                    "totalGeotechHazardCrossingsCount",
                    activeFaultShearCrossings
                            + colluvialLandslideScarpCrossings
                            + liquefiableTroughCrossings);
            geotechSummary.put(
                    "totalRecommendedStabilizationLengthMeters",
                    round(totalRecommendedGeotechStabilizationMeters));
            geotechSummary.put(
                    "averageGeotechCrossingAngleDegrees",
                    avgGeotechCrossingAngleDeg);
            geotechSummary.put(
                    "mitigationStructurePenaltyKmEquivalent",
                    round(geotechMitigationPenaltyKm));
            geotechSummary.put(
                    "weakSoilAndLandslideSlopePenaltyKmEquivalent",
                    round(geotechNodePenaltyKm));
            diagnostics.put("geotechMitigationCrossingSummary", geotechSummary);
        }
        if (utilityRasterProvider != null) {
            diagnostics.put("utilityCorridorExposureKm", utilityHazardExposure);
        }
        if (utilityVectorIndex != null) {
            Map<String, Object> utilitySummary = new LinkedHashMap<>();
            utilitySummary.put("ehv400kvCrossingsCount", ehv400kvCrossings);
            utilitySummary.put("ehv220kvCrossingsCount", ehv220kvCrossings);
            utilitySummary.put("subtransmission132kvCrossingsCount", subtransmission132kvCrossings);
            utilitySummary.put("distribution66kvCrossingsCount", distribution66kvCrossings);
            utilitySummary.put(
                    "totalUtilityCrossingsCount",
                    ehv400kvCrossings
                            + ehv220kvCrossings
                            + subtransmission132kvCrossings
                            + distribution66kvCrossings);
            utilitySummary.put(
                    "totalRecommendedUtilityProtectiveSpanMeters",
                    round(totalRecommendedUtilityProtectiveSpanMeters));
            utilitySummary.put(
                    "averageUtilityCrossingAngleDegrees",
                    avgUtilityCrossingAngleDeg);
            utilitySummary.put(
                    "crossingProtectionPenaltyKmEquivalent",
                    round(utilityCrossingPenaltyKm));
            utilitySummary.put(
                    "corridorSetbackAndRowPenaltyKmEquivalent",
                    round(utilityNodePenaltyKm));
            diagnostics.put("utilityCrossingSummary", utilitySummary);
        }
        if (buildingRasterProvider != null) {
            diagnostics.put("buildingDensityExposureKm", buildingDensityExposure);
        }
        if (buildingRowImpact != null) {
            Map<String, Object> buildingSummary = new LinkedHashMap<>(buildingRowImpact.toSummaryMap());
            buildingSummary.put(
                    "structureAndSettlementConflictPenaltyKmEquivalent",
                    round(buildingConflictPenaltyKm));
            buildingSummary.put(
                    "builtUpDensityRasterPenaltyKmEquivalent",
                    round(buildingNodePenaltyKm));
            diagnostics.put("buildingAndSettlementRowImpactSummary", buildingSummary);
        }
        if (demographicRasterProvider != null) {
            diagnostics.put("populationDensityExposureKm", populationDensityExposure);
        }
        if (demographicRowImpact != null) {
            Map<String, Object> demographicSummary = new LinkedHashMap<>(demographicRowImpact.toSummaryMap());
            demographicSummary.put(
                    "tractDemographicExposurePenaltyKmEquivalent",
                    round(demographicExposurePenaltyKm));
            demographicSummary.put(
                    "populationDensityRasterPenaltyKmEquivalent",
                    round(demographicNodePenaltyKm));
            diagnostics.put("populationAndDemographicExposureSummary", demographicSummary);
        }
        if (drainageRasterProvider != null) {
            diagnostics.put("drainageWetlandExposureKm", drainageWetlandExposure);
        }
        if (drainageRowImpact != null) {
            Map<String, Object> drainageSummary = new LinkedHashMap<>(drainageRowImpact.toSummaryMap());
            // Top-level convenience fields mirroring nested summaries so audit cards & clients can read them directly
            drainageSummary.put("wetlandsInDirectRowCount", drainageRowImpact.wetlandsInDirectRowCount());
            drainageSummary.put("wetlandsIn100mBufferCount", drainageRowImpact.wetlandsIn100mBufferCount());
            drainageSummary.put("wetlandsIn250mBufferCount", drainageRowImpact.wetlandsIn250mBufferCount());
            drainageSummary.put("wetlandAreaInDirectRowHectares", drainageRowImpact.wetlandAreaInDirectRowHectares());
            drainageSummary.put("minWetlandClearanceFromRowEdgeMeters", drainageRowImpact.minWetlandClearanceFromRowEdgeMeters());
            drainageSummary.put("drainageObstructionRiskIndex", drainageRowImpact.drainageObstructionRiskIndex());
            drainageSummary.put("totalWaterCrossingsCount", drainageRowImpact.totalWaterCrossingsCount());
            drainageSummary.put("totalDrainageStructuresRequiredCount", drainageRowImpact.totalDrainageStructuresRequiredCount());
            drainageSummary.put("totalIndexedWetlandsCount", drainageVectorIndex.allWetlands().size());
            drainageSummary.put("totalIndexedDrainageChannelsCount", drainageVectorIndex.allDrainageLines().size());
            drainageSummary.put(
                    "drainageAndWetlandEdgePenaltyKmEquivalent",
                    round(drainageEdgePenaltyKm));
            drainageSummary.put(
                    "drainageAndWetlandRasterPenaltyKmEquivalent",
                    round(drainageNodePenaltyKm));
            diagnostics.put("drainageAndWetlandSummary", drainageSummary);
        }
        diagnostics.put("protectedAreaExposureKm", round(protectedAreaKm));
        diagnostics.put("approximatePenaltyContributionKm", penaltiesMap);
        diagnostics.put("approximateCompositeCostKmEquivalent", round(approximateCompositeCostKm));
        diagnostics.put("floodCacheMissSamples", floodCacheMisses);
        diagnostics.put("hardConstraintValidation", hardConstraints);

        if (includeSwathAudit && rowCorridorEngine != null && route.size() >= 2) {
            FacilityMode effectiveMode =
                    facilityMode != null ? facilityMode : FacilityMode.NATIONAL_HIGHWAY;
            RightOfWayCorridorEngine.CorridorFootprint activeFootprint =
                    rowCorridorEngine.buildCorridorFootprint(route, effectiveMode);
            RightOfWayCorridorEngine.CorridorFootprint expFootprint =
                    effectiveMode == FacilityMode.EXPRESSWAY
                            ? activeFootprint
                            : rowCorridorEngine.buildCorridorFootprint(route, FacilityMode.EXPRESSWAY);
            RightOfWayCorridorEngine.CorridorFootprint nhFootprint =
                    effectiveMode == FacilityMode.NATIONAL_HIGHWAY
                            ? activeFootprint
                            : rowCorridorEngine.buildCorridorFootprint(route, FacilityMode.NATIONAL_HIGHWAY);

            RightOfWayCorridorEngine.SwathCorridorAudit swathAudit =
                    rowCorridorEngine.evaluateSwathAudit(
                            route,
                            List.of(),
                            activeFootprint,
                            lulcProvider,
                            slopeRasterProvider,
                            protectedAreaRasterProvider,
                            settlementRasterProvider,
                            waterBodyRasterProvider,
                            wildlifeRasterProvider,
                            geotechRasterProvider,
                            utilityRasterProvider,
                            utilityVectorIndex,
                            buildingRasterProvider,
                            buildingVectorIndex,
                            demographicRasterProvider,
                            demographicVectorIndex,
                            drainageRasterProvider,
                            drainageVectorIndex);

            Map<String, Object> rowSummary = new LinkedHashMap<>();
            rowSummary.put("activeFacilityMode", effectiveMode.name());
            rowSummary.put("ircRowStandard", activeFootprint.ircStandard());
            rowSummary.put("designRowWidthMeters", activeFootprint.mainlineRowWidthMeters());
            rowSummary.put("activeMainlineRowAreaHectares", activeFootprint.mainlineRowAreaHectares());
            rowSummary.put("expressway60mRowAreaHectares", expFootprint.mainlineRowAreaHectares());
            rowSummary.put("nationalHighway45mRowAreaHectares", nhFootprint.mainlineRowAreaHectares());
            rowSummary.put(
                    "constructionClearanceZoneHectares",
                    activeFootprint.constructionZoneGrossHectares());
            rowSummary.put(
                    "environmentalInfluenceBufferHectares",
                    activeFootprint.environmentalBufferGrossHectares());
            rowSummary.putAll(swathAudit.toMap());
            diagnostics.put("rightOfWayCorridorSummary", rowSummary);
        }

        return diagnostics;
    }

    /**
     * Samples a compact, evenly-spaced raster profile along the generated mainline so the
     * frontend 13-Layer Lens Explorer can colorize route segments by active raster layer
     * (LULC, Flood, DEM/Slope, Settlement, Geotech, Population, Drainage) and draw a DEM slope chart.
     */
    public List<Map<String, Object>> sampleRouteLayerProfile(List<GeoPoint> route) {
        if (route == null || route.size() < 2) {
            return List.of();
        }
        double totalMeters = 0.0;
        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            totalMeters += Math.hypot(b.x() - a.x(), b.y() - a.y());
        }
        if (totalMeters <= 1.0) {
            return List.of();
        }
        // Target ~200 profile samples (min 400 m spacing so payload stays small and Leaflet renders fast)
        double stepMeters = Math.max(400.0, totalMeters / 200.0);

        List<Map<String, Object>> profile = new java.util.ArrayList<>();
        double accumulatedMeters = 0.0;
        double nextSampleMeters = 0.0;

        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double segMeters = Math.hypot(dx, dy);
            if (segMeters <= 0.0) {
                continue;
            }
            while (nextSampleMeters <= accumulatedMeters + segMeters) {
                double t = Math.max(0.0, Math.min(1.0, (nextSampleMeters - accumulatedMeters) / segMeters));
                GeoPoint pt = projection.inverse(new MetricPoint(a.x() + t * dx, a.y() + t * dy));
                profile.add(buildProfileSample(pt, nextSampleMeters / 1000.0));
                nextSampleMeters += stepMeters;
            }
            accumulatedMeters += segMeters;
        }
        // Always include the exact final endpoint
        GeoPoint lastPt = route.get(route.size() - 1);
        if (profile.isEmpty() || Math.abs(((Number) profile.get(profile.size() - 1).get("chKm")).doubleValue() - (totalMeters / 1000.0)) > 0.15) {
            profile.add(buildProfileSample(lastPt, totalMeters / 1000.0));
        }
        return profile;
    }

    private Map<String, Object> buildProfileSample(GeoPoint point, double chainageKm) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("lat", Math.round(point.lat() * 100000.0) / 100000.0);
        sample.put("lon", Math.round(point.lon() * 100000.0) / 100000.0);
        sample.put("chKm", round(chainageKm));

        if (lulcProvider != null) {
            LulcSample ls = lulcProvider.sample(point);
            sample.put("lulc", ls != null && ls.type() != null ? ls.type().name() : "UNKNOWN");
        }
        if (floodRasterCache != null) {
            FloodRasterSample fs = floodRasterCache.sample(point);
            sample.put("flood", fs != null && fs.cacheAvailable() && fs.hazardClass() != null
                    ? fs.hazardClass().name() : "NOT_IN_HAZARD_LAYER");
        }
        if (slopeRasterProvider != null) {
            Double slopeDeg = slopeRasterProvider.sampleOrNull(point.lat(), point.lon());
            sample.put("slopeDeg", slopeDeg != null ? round(slopeDeg) : null);
            sample.put("slopeBand", classifySlope(slopeDeg));
        }
        if (settlementRasterProvider != null) {
            SettlementExposureClass sc = settlementRasterProvider.sampleClass(point.lat(), point.lon());
            sample.put("settlement", sc != null ? sc.name() : "NONE");
        }
        if (geotechRasterProvider != null) {
            GeotechHazardClass gc = geotechRasterProvider.sampleClass(point.lat(), point.lon());
            sample.put("geotech", gc != null ? gc.name() : "COMPETENT_BEDROCK_STABLE_SUBGRADE");
        }
        if (demographicRasterProvider != null) {
            PopulationDensityClass pc = demographicRasterProvider.sampleClass(point.lat(), point.lon());
            sample.put("population", pc != null ? pc.name() : "UNINHABITED_OR_BACKGROUND");
        }
        if (drainageRasterProvider != null) {
            DrainageWetlandClass dc = drainageRasterProvider.sampleClass(point.lat(), point.lon());
            sample.put("drainage", dc != null ? dc.name() : "NONE");
        }
        return sample;
    }

    private String classifySlope(Double slopeDegrees) {
        if (slopeDegrees == null) return "SLOPE_NODATA";
        if (slopeDegrees <= slopeProperties.getFreeMaxDegrees()) return "SLOPE_FREE";
        if (slopeDegrees <= slopeProperties.getLowMaxDegrees()) return "SLOPE_LOW";
        if (slopeDegrees <= slopeProperties.getModerateMaxDegrees()) return "SLOPE_MODERATE";
        if (slopeDegrees <= slopeProperties.getSteepMaxDegrees()) return "SLOPE_STEEP";
        if (slopeDegrees <= slopeProperties.getVerySteepMaxDegrees()) return "SLOPE_VERY_STEEP";
        return "SLOPE_OVER_LIMIT";
    }

    private double slopePenalty(Double slopeDegrees) {
        if (slopeDegrees == null) return 0.0;
        if (slopeDegrees <= slopeProperties.getFreeMaxDegrees()) return 0.0;
        if (slopeDegrees <= slopeProperties.getLowMaxDegrees()) return slopeProperties.getLowPenalty();
        if (slopeDegrees <= slopeProperties.getModerateMaxDegrees()) return slopeProperties.getModeratePenalty();
        if (slopeDegrees <= slopeProperties.getSteepMaxDegrees()) return slopeProperties.getSteepPenalty();
        if (slopeDegrees <= slopeProperties.getVerySteepMaxDegrees()) return slopeProperties.getVerySteepPenalty();
        return 0.0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
