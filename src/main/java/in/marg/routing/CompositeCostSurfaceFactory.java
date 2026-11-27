package in.marg.routing;

import in.marg.config.FloodRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.provider.LulcProvider;
import in.marg.spatial.CoordinateProjection;
import in.marg.transport.FacilityMode;
import in.marg.transport.RegionalHub;
import in.marg.transport.TransportNetworkRasterProvider;
import in.marg.transport.TransportNetworkVectorIndex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CompositeCostSurfaceFactory {

    private final LulcProvider lulcProvider;
    private final LulcRoutingProperties lulcProperties;

    private final BhuvanFloodRasterCache floodRasterCache;
    private final FloodRoutingProperties floodProperties;

    private final SlopeCostLayer slopeCostLayer;
    private final ProtectedAreaCostLayer protectedAreaCostLayer;
    private final SettlementCostLayer settlementCostLayer;
    private final WaterBodyCostLayer waterBodyCostLayer;
    private final WildlifeCorridorCostLayer wildlifeCorridorCostLayer;
    private final GeotechLandslideCostLayer geotechLandslideCostLayer;
    private final UtilityCorridorCostLayer utilityCorridorCostLayer;
    private final BuildingFootprintCostLayer buildingFootprintCostLayer;
    private final DemographicCostLayer demographicCostLayer;
    private final DrainageWetlandCostLayer drainageWetlandCostLayer;

    private final TransportNetworkRasterProvider transportRasterProvider;
    private final TransportNetworkVectorIndex transportVectorIndex;
    private final TransportRoutingProperties transportProperties;
    private final CoordinateProjection projection;

    public CompositeCostSurfaceFactory(
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeCostLayer slopeCostLayer,
            ProtectedAreaCostLayer protectedAreaCostLayer,
            SettlementCostLayer settlementCostLayer,
            WaterBodyCostLayer waterBodyCostLayer,
            WildlifeCorridorCostLayer wildlifeCorridorCostLayer,
            GeotechLandslideCostLayer geotechLandslideCostLayer,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
            TransportRoutingProperties transportProperties,
            CoordinateProjection projection) {
        this(
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeCostLayer,
                protectedAreaCostLayer,
                settlementCostLayer,
                waterBodyCostLayer,
                wildlifeCorridorCostLayer,
                geotechLandslideCostLayer,
                null,
                null,
                null,
                null,
                transportRasterProvider,
                transportVectorIndex,
                transportProperties,
                projection);
    }

    public CompositeCostSurfaceFactory(
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeCostLayer slopeCostLayer,
            ProtectedAreaCostLayer protectedAreaCostLayer,
            SettlementCostLayer settlementCostLayer,
            WaterBodyCostLayer waterBodyCostLayer,
            WildlifeCorridorCostLayer wildlifeCorridorCostLayer,
            GeotechLandslideCostLayer geotechLandslideCostLayer,
            UtilityCorridorCostLayer utilityCorridorCostLayer,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
            TransportRoutingProperties transportProperties,
            CoordinateProjection projection) {
        this(
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeCostLayer,
                protectedAreaCostLayer,
                settlementCostLayer,
                waterBodyCostLayer,
                wildlifeCorridorCostLayer,
                geotechLandslideCostLayer,
                utilityCorridorCostLayer,
                null,
                null,
                null,
                transportRasterProvider,
                transportVectorIndex,
                transportProperties,
                projection);
    }

    public CompositeCostSurfaceFactory(
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeCostLayer slopeCostLayer,
            ProtectedAreaCostLayer protectedAreaCostLayer,
            SettlementCostLayer settlementCostLayer,
            WaterBodyCostLayer waterBodyCostLayer,
            WildlifeCorridorCostLayer wildlifeCorridorCostLayer,
            GeotechLandslideCostLayer geotechLandslideCostLayer,
            UtilityCorridorCostLayer utilityCorridorCostLayer,
            BuildingFootprintCostLayer buildingFootprintCostLayer,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
            TransportRoutingProperties transportProperties,
            CoordinateProjection projection) {
        this(
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeCostLayer,
                protectedAreaCostLayer,
                settlementCostLayer,
                waterBodyCostLayer,
                wildlifeCorridorCostLayer,
                geotechLandslideCostLayer,
                utilityCorridorCostLayer,
                buildingFootprintCostLayer,
                null,
                null,
                transportRasterProvider,
                transportVectorIndex,
                transportProperties,
                projection);
    }

    public CompositeCostSurfaceFactory(
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeCostLayer slopeCostLayer,
            ProtectedAreaCostLayer protectedAreaCostLayer,
            SettlementCostLayer settlementCostLayer,
            WaterBodyCostLayer waterBodyCostLayer,
            WildlifeCorridorCostLayer wildlifeCorridorCostLayer,
            GeotechLandslideCostLayer geotechLandslideCostLayer,
            UtilityCorridorCostLayer utilityCorridorCostLayer,
            BuildingFootprintCostLayer buildingFootprintCostLayer,
            DemographicCostLayer demographicCostLayer,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
            TransportRoutingProperties transportProperties,
            CoordinateProjection projection) {
        this(
                lulcProvider,
                lulcProperties,
                floodRasterCache,
                floodProperties,
                slopeCostLayer,
                protectedAreaCostLayer,
                settlementCostLayer,
                waterBodyCostLayer,
                wildlifeCorridorCostLayer,
                geotechLandslideCostLayer,
                utilityCorridorCostLayer,
                buildingFootprintCostLayer,
                demographicCostLayer,
                null,
                transportRasterProvider,
                transportVectorIndex,
                transportProperties,
                projection);
    }

    @Autowired
    public CompositeCostSurfaceFactory(
            LulcProvider lulcProvider,
            LulcRoutingProperties lulcProperties,
            BhuvanFloodRasterCache floodRasterCache,
            FloodRoutingProperties floodProperties,
            SlopeCostLayer slopeCostLayer,
            ProtectedAreaCostLayer protectedAreaCostLayer,
            SettlementCostLayer settlementCostLayer,
            WaterBodyCostLayer waterBodyCostLayer,
            WildlifeCorridorCostLayer wildlifeCorridorCostLayer,
            GeotechLandslideCostLayer geotechLandslideCostLayer,
            UtilityCorridorCostLayer utilityCorridorCostLayer,
            BuildingFootprintCostLayer buildingFootprintCostLayer,
            DemographicCostLayer demographicCostLayer,
            DrainageWetlandCostLayer drainageWetlandCostLayer,
            TransportNetworkRasterProvider transportRasterProvider,
            TransportNetworkVectorIndex transportVectorIndex,
            TransportRoutingProperties transportProperties,
            CoordinateProjection projection) {

        this.lulcProvider = lulcProvider;
        this.lulcProperties = lulcProperties;
        this.floodRasterCache = floodRasterCache;
        this.floodProperties = floodProperties;
        this.slopeCostLayer = slopeCostLayer;
        this.protectedAreaCostLayer = protectedAreaCostLayer;
        this.settlementCostLayer = settlementCostLayer;
        this.waterBodyCostLayer = waterBodyCostLayer;
        this.wildlifeCorridorCostLayer = wildlifeCorridorCostLayer;
        this.geotechLandslideCostLayer = geotechLandslideCostLayer;
        this.utilityCorridorCostLayer = utilityCorridorCostLayer;
        this.buildingFootprintCostLayer = buildingFootprintCostLayer;
        this.demographicCostLayer = demographicCostLayer;
        this.drainageWetlandCostLayer = drainageWetlandCostLayer;
        this.transportRasterProvider = transportRasterProvider;
        this.transportVectorIndex = transportVectorIndex;
        this.transportProperties = transportProperties;
        this.projection = projection;
    }

    public BuildResult build(
            StudyRegion searchWindow,
            int maxLulcSamples) {
        return build(
                searchWindow,
                maxLulcSamples,
                FacilityMode.EXPRESSWAY,
                List.of(),
                searchWindow.origin(),
                searchWindow.destination());
    }

    public BuildResult build(
            StudyRegion searchWindow,
            int maxLulcSamples,
            FacilityMode facilityMode,
            List<RegionalHub> approvedHubs,
            GeoPoint start,
            GeoPoint goal) {
        return build(
                searchWindow,
                maxLulcSamples,
                facilityMode,
                approvedHubs,
                start,
                goal,
                AlignmentPreference.BALANCED);
    }

    public BuildResult build(
            StudyRegion searchWindow,
            int maxLulcSamples,
            FacilityMode facilityMode,
            List<RegionalHub> approvedHubs,
            GeoPoint start,
            GeoPoint goal,
            AlignmentPreference preference) {

        AlignmentPreference pref = preference != null ? preference : AlignmentPreference.BALANCED;
        double envMult = pref.environmentalWeight();
        double socialMult = pref.socialWeight();
        double directnessMult = pref.directnessWeight();

        // Explicitly prepare LULC before A*.
        var lulcSamples =
                lulcProvider.preload(
                        searchWindow,
                        maxLulcSamples);

        // Explicitly prepare flood raster before A*.
        double rasterMarginDeg = 0.01;

        double floodMinLat =
                searchWindow.minLat() - rasterMarginDeg;

        double floodMinLon =
                searchWindow.minLon() - rasterMarginDeg;

        double floodMaxLat =
                searchWindow.maxLat() + rasterMarginDeg;

        double floodMaxLon =
                searchWindow.maxLon() + rasterMarginDeg;

        var floodPreload =
                floodRasterCache.preload(
                        floodMinLat,
                        floodMinLon,
                        floodMaxLat,
                        floodMaxLon,
                        null,
                        null);

        CostLayer lulc =
                new LulcCostLayer(
                        lulcProvider,
                        lulcProperties.toPolicy());

        CostLayer flood =
                new FloodRasterCostLayer(
                        floodRasterCache,
                        floodProperties.toPolicy());

        // Apply request-scoped multi-objective preference scaling without mutating global properties
        CostLayer lulcEff = ScaledCostLayer.wrap(lulc, envMult);
        CostLayer floodEff = ScaledCostLayer.wrap(flood, envMult);
        CostLayer waterEff = ScaledCostLayer.wrap(waterBodyCostLayer, envMult);
        CostLayer wildlifeEff = ScaledCostLayer.wrap(wildlifeCorridorCostLayer, envMult);
        CostLayer drainageEff = ScaledCostLayer.wrap(drainageWetlandCostLayer, envMult);

        CostLayer settlementEff = ScaledCostLayer.wrap(settlementCostLayer, socialMult);
        CostLayer buildingEff = ScaledCostLayer.wrap(buildingFootprintCostLayer, socialMult);
        CostLayer demographicEff = ScaledCostLayer.wrap(demographicCostLayer, socialMult);

        CostLayer slopeEff = ScaledCostLayer.wrap(slopeCostLayer, directnessMult);
        CostLayer geotechEff = ScaledCostLayer.wrap(geotechLandslideCostLayer, directnessMult);
        CostLayer utilityEff = ScaledCostLayer.wrap(utilityCorridorCostLayer, directnessMult);

        List<CostLayer> baseTerrainList = new ArrayList<>(List.of(
                lulcEff,
                floodEff,
                slopeEff,
                protectedAreaCostLayer,
                settlementEff,
                waterEff,
                wildlifeEff,
                geotechEff));
        if (utilityEff != null) {
            baseTerrainList.add(utilityEff);
        }
        if (buildingEff != null) {
            baseTerrainList.add(buildingEff);
        }
        if (demographicEff != null) {
            baseTerrainList.add(demographicEff);
        }
        if (drainageEff != null) {
            baseTerrainList.add(drainageEff);
        }
        List<CostLayer> terrainAndSocialLayers = List.copyOf(baseTerrainList);

        double accessibilityPull =
                transportProperties.accessibilityPullFor(facilityMode);

        CostLayer transport =
                new TransportNetworkCostLayer(
                        transportRasterProvider,
                        transportVectorIndex,
                        projection,
                        transportProperties.toPolicy(),
                        accessibilityPull,
                        approvedHubs,
                        start,
                        goal,
                        transportProperties.getCollectorSweetSpotMaxKm(),
                        transportProperties.getCollectorLongitudinalBandKm(),
                        facilityMode);

        List<CostLayer> surfaceLayers = new ArrayList<>(List.of(
                lulcEff,
                floodEff,
                slopeEff,
                protectedAreaCostLayer,
                settlementEff,
                transport,
                waterEff,
                wildlifeEff,
                geotechEff));
        if (utilityEff != null) {
            surfaceLayers.add(utilityEff);
        }
        if (buildingEff != null) {
            surfaceLayers.add(buildingEff);
        }
        if (demographicEff != null) {
            surfaceLayers.add(demographicEff);
        }
        if (drainageEff != null) {
            surfaceLayers.add(drainageEff);
        }
        CompositeCostSurface surface = new CompositeCostSurface(surfaceLayers);

        return new BuildResult(
                surface,
                terrainAndSocialLayers,
                lulcSamples.size(),
                floodPreload,
                accessibilityPull);
    }

    public record BuildResult(
            CompositeCostSurface surface,
            List<CostLayer> terrainAndSocialLayers,
            int lulcSamplesLoaded,
            BhuvanFloodRasterCache.PreloadResult floodPreload,
            double accessibilityPullApplied) {
    }
}
