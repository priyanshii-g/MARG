package in.marg.region;

import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.config.BuildingRoutingProperties;
import in.marg.config.DemographicRoutingProperties;
import in.marg.config.DrainageRoutingProperties;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.GeotechRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.config.UtilityRoutingProperties;
import in.marg.config.WaterBodyRoutingProperties;
import in.marg.config.WildlifeRoutingProperties;
import in.marg.demographic.DemographicVectorIndex;
import in.marg.demographic.PopulationDensityRasterProvider;
import in.marg.drainage.DrainageWetlandRasterProvider;
import in.marg.drainage.DrainageWetlandVectorIndex;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.geotech.GeotechLineamentVectorIndex;
import in.marg.geotech.GeotechRasterProvider;
import in.marg.hydrology.RiverNetworkVectorIndex;
import in.marg.hydrology.WaterBodyRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.provider.LulcProvider;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.routing.BuildingFootprintCostLayer;
import in.marg.routing.CompositeCostSurface;
import in.marg.routing.CompositeCostSurfaceFactory;
import in.marg.routing.CompositeRouteMetricsService;
import in.marg.routing.CostLayer;
import in.marg.routing.CostSurface;
import in.marg.routing.DemographicCostLayer;
import in.marg.routing.DistanceOnlyCostSurface;
import in.marg.routing.DrainageWetlandCostLayer;
import in.marg.routing.GeotechLandslideCostLayer;
import in.marg.routing.GreenfieldAStarRouter;
import in.marg.routing.ProtectedAreaCostLayer;
import in.marg.routing.RouteConstraints;
import in.marg.routing.SettlementCostLayer;
import in.marg.routing.SlopeCostLayer;
import in.marg.routing.UtilityCorridorCostLayer;
import in.marg.routing.WaterBodyCostLayer;
import in.marg.routing.WildlifeCorridorCostLayer;
import in.marg.settlement.SettlementRasterProvider;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.Wgs84UtmProjection;
import in.marg.terrain.SlopeRasterProvider;
import in.marg.transport.FacilityMode;
import in.marg.transport.HighwayNetworkPlannerService;
import in.marg.transport.HubEvaluationService;
import in.marg.transport.TransportNetworkRasterProvider;
import in.marg.transport.TransportNetworkVectorIndex;
import in.marg.utility.UtilityNetworkVectorIndex;
import in.marg.utility.UtilityRasterProvider;
import in.marg.wildlife.WildlifeCorridorVectorIndex;
import in.marg.wildlife.WildlifeRasterProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-Tier Multi-Region Corridor Registry & Lazy-Loading Pipeline Manager.
 *
 * <p>Manages the three Tier-2 Pre-Cached Benchmark Demonstration Corridors ("Presets"):
 * <ol>
 *   <li><b>assam</b>: Kaziranga&ndash;Karbi Anglong, Assam (UTM Zone 46N / EPSG:32646)</li>
 *   <li><b>western_ghats</b>: Pune&ndash;Bhor&ndash;Satara, Maharashtra (UTM Zone 43N / EPSG:32643)</li>
 *   <li><b>gangetic_plains</b>: Barabanki&ndash;Ramsanehighat&ndash;Ayodhya, UP (UTM Zone 44N / EPSG:32644)</li>
 * </ol>
 * Each preset's 13-layer GeoTIFF rasters and GeoJSON spatial indices are lazily instantiated
 * and loaded into heap memory on first request via {@link ConcurrentHashMap#computeIfAbsent}.
 * Within any preset corridor, evaluators can specify any arbitrary start and destination points
 * (or omit them to use the corridor's default A-B endpoints). Requests outside the three presets
 * (or explicitly requesting {@code region=pan_india} / {@code tierMode=TIER_1_DYNAMIC_PAN_INDIA})
 * are routed by {@link PanIndiaDynamicCorridorService}.
 */
@Service
public class RegionalCorridorRegistry {

    private static final double ROUTING_RESOLUTION_METERS = 250.0;
    private static final double PRESET_MARGIN_DEG = 0.05;

    private final Map<String, RegionalCorridorPreset> presets = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, RegionalPipelineBundle> loadedPipelines = new ConcurrentHashMap<>();

    private final PanIndiaDynamicCorridorService panIndiaService;
    private final BhuvanFloodRasterCache sharedFloodRasterCache;
    private final ObjectMapper objectMapper;

    private final LulcRoutingProperties lulcProperties;
    private final FloodRoutingProperties floodProperties;
    private final SlopeRoutingProperties slopeProperties;
    private final SettlementRoutingProperties settlementProperties;
    private final TransportRoutingProperties transportProperties;
    private final WaterBodyRoutingProperties waterBodyProperties;
    private final WildlifeRoutingProperties wildlifeProperties;
    private final GeotechRoutingProperties geotechProperties;
    private final UtilityRoutingProperties utilityProperties;
    private final BuildingRoutingProperties buildingProperties;
    private final DemographicRoutingProperties demographicProperties;
    private final DrainageRoutingProperties drainageProperties;

    public RegionalCorridorRegistry() {
        this(
                new PanIndiaDynamicCorridorService(),
                null,
                new ObjectMapper(),
                defaultLulcProperties(),
                defaultFloodProperties(),
                new SlopeRoutingProperties(),
                new SettlementRoutingProperties(),
                new TransportRoutingProperties(),
                new WaterBodyRoutingProperties(),
                new WildlifeRoutingProperties(),
                new GeotechRoutingProperties(),
                new UtilityRoutingProperties(),
                new BuildingRoutingProperties(),
                new DemographicRoutingProperties(),
                new DrainageRoutingProperties()
        );
    }

    @Autowired
    public RegionalCorridorRegistry(
            PanIndiaDynamicCorridorService panIndiaService,
            @Autowired(required = false) BhuvanFloodRasterCache sharedFloodRasterCache,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) LulcRoutingProperties lulcProperties,
            @Autowired(required = false) FloodRoutingProperties floodProperties,
            @Autowired(required = false) SlopeRoutingProperties slopeProperties,
            @Autowired(required = false) SettlementRoutingProperties settlementProperties,
            @Autowired(required = false) TransportRoutingProperties transportProperties,
            @Autowired(required = false) WaterBodyRoutingProperties waterBodyProperties,
            @Autowired(required = false) WildlifeRoutingProperties wildlifeProperties,
            @Autowired(required = false) GeotechRoutingProperties geotechProperties,
            @Autowired(required = false) UtilityRoutingProperties utilityProperties,
            @Autowired(required = false) BuildingRoutingProperties buildingProperties,
            @Autowired(required = false) DemographicRoutingProperties demographicProperties,
            @Autowired(required = false) DrainageRoutingProperties drainageProperties) {

        this.panIndiaService = panIndiaService != null ? panIndiaService : new PanIndiaDynamicCorridorService();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.sharedFloodRasterCache = sharedFloodRasterCache != null
                ? sharedFloodRasterCache
                : createLocalFloodCache(this.objectMapper);
        this.lulcProperties = mergeDefaultLulcPenalties(lulcProperties);
        this.floodProperties = mergeDefaultFloodPenalties(floodProperties);
        this.slopeProperties = slopeProperties != null ? slopeProperties : new SlopeRoutingProperties();
        this.settlementProperties = settlementProperties != null ? settlementProperties : new SettlementRoutingProperties();
        this.transportProperties = transportProperties != null ? transportProperties : new TransportRoutingProperties();
        this.waterBodyProperties = waterBodyProperties != null ? waterBodyProperties : new WaterBodyRoutingProperties();
        this.wildlifeProperties = wildlifeProperties != null ? wildlifeProperties : new WildlifeRoutingProperties();
        this.geotechProperties = geotechProperties != null ? geotechProperties : new GeotechRoutingProperties();
        this.utilityProperties = utilityProperties != null ? utilityProperties : new UtilityRoutingProperties();
        this.buildingProperties = buildingProperties != null ? buildingProperties : new BuildingRoutingProperties();
        this.demographicProperties = demographicProperties != null ? demographicProperties : new DemographicRoutingProperties();
        this.drainageProperties = drainageProperties != null ? drainageProperties : new DrainageRoutingProperties();

        registerBuiltInPresets();
    }

    private void registerBuiltInPresets() {
        RegionalCorridorPreset assam = new RegionalCorridorPreset(
                "assam",
                "CORRIDOR-1-ASSAM",
                "Corridor 1: Northeast Hill & Eco-Sensitive Corridor (Kaziranga–Karbi Anglong, Assam)",
                "Assam (Brahmaputra Floodplain to Karbi Anglong Hills)",
                "NORTHEAST_BRAHMAPUTRA_FLOODPLAIN_AND_SUB_HIMALAYAN_HILLS",
                "Kaziranga National Park protected boundary, elephant migration corridors, Brahmaputra seasonal floodplains, and Karbi Anglong fault/landslide scarps.",
                46,
                "EPSG:32646",
                new StudyRegion(
                        "Kaziranga–Karbi Anglong, Assam",
                        25.75, 93.10, 26.65, 93.75,
                        new GeoPoint(26.58970, 93.40035),
                        new GeoPoint(25.84573, 93.43781)
                ),
                "data/cache/bhuvan-lulc-samples.json",
                "data/processed/dem/assam_slope_utm46.tif",
                "data/processed/protected-areas/assam_protected_area_mask_utm46.tif",
                "data/processed/settlements/assam_settlement_exposure_utm46.tif",
                "data/processed/settlements/assam_settlements_catalog.geojson",
                "data/processed/transport/assam_transport_network_utm46.tif",
                "data/processed/transport/assam_transport_network.geojson",
                "data/processed/transport/assam_regional_hubs.geojson",
                "data/processed/hydrology/assam_water_bodies_utm46.tif",
                "data/processed/hydrology/assam_perennial_rivers.geojson",
                "data/processed/hydrology/assam_wetlands_catalog.geojson",
                "data/processed/wildlife/assam_wildlife_esz_corridors_utm46.tif",
                "data/processed/wildlife/assam_wildlife_corridors.geojson",
                "data/processed/geotech/assam_geotech_landslide_utm46.tif",
                "data/processed/geotech/assam_fault_landslide_zones.geojson",
                "data/processed/utilities/assam_utility_corridors_utm46.tif",
                "data/processed/utilities/assam_power_transmission_lines.geojson",
                "data/processed/utilities/assam_utility_assets.geojson",
                "data/processed/buildings/assam_building_density_utm46.tif",
                "data/processed/buildings/assam_settlement_boundaries.geojson",
                "data/processed/buildings/assam_building_footprints.geojson",
                "data/processed/demographics/assam_population_density_utm46.tif",
                "data/processed/demographics/assam_population_demographics.geojson",
                "data/processed/drainage/assam_drainage_wetland_utm46.tif",
                "data/processed/drainage/assam_wetlands_polygons.geojson",
                "data/processed/drainage/assam_drainage_network.geojson",
                "data/processed/drainage/assam_watershed_basins.geojson"
        );

        RegionalCorridorPreset westernGhats = new RegionalCorridorPreset(
                "western_ghats",
                "CORRIDOR-2-WESTERN-GHATS",
                "Corridor 2: Western Ghats Steep Escarpment & High-Rainfall Corridor (Pune–Bhor–Satara, Maharashtra)",
                "Maharashtra (Sahyadri Escarpment, Nira–Krishna Basins & Deccan Basalt Trap)",
                "WESTERN_GHATS_SAHYADRI_ESCARPMENT_AND_DECCAN_PLATEAU",
                "Steep >30 deg Sahyadri basalt ghats, Sahyadri/Koyna wildlife sanctuary buffers, Bhor-Mahabaleshwar landslide scarps, and Nira-Krishna river bridge crossings.",
                43,
                "EPSG:32643",
                new StudyRegion(
                        "Western Ghats Escarpment (Pune–Bhor–Satara, Maharashtra)",
                        17.60, 73.65, 18.50, 74.30,
                        new GeoPoint(18.36002, 73.85352),
                        new GeoPoint(17.71704, 74.02648)
                ),
                "data/processed/regions/western_ghats/western_ghats_lulc_samples.json",
                "data/processed/regions/western_ghats/western_ghats_slope_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_protected_area_mask_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_settlement_exposure_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_settlement_boundaries.geojson",
                "data/processed/regions/western_ghats/western_ghats_transport_network_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_transport_network.geojson",
                "data/processed/regions/western_ghats/western_ghats_regional_hubs.geojson",
                "data/processed/regions/western_ghats/western_ghats_water_bodies_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_perennial_rivers.geojson",
                "data/processed/regions/western_ghats/western_ghats_wetlands_polygons.geojson",
                "data/processed/regions/western_ghats/western_ghats_wildlife_esz_corridors_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_wildlife_corridors.geojson",
                "data/processed/regions/western_ghats/western_ghats_geotech_landslide_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_fault_landslide_zones.geojson",
                "data/processed/regions/western_ghats/western_ghats_utility_corridors_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_power_transmission_lines.geojson",
                "data/processed/regions/western_ghats/western_ghats_utility_assets.geojson",
                "data/processed/regions/western_ghats/western_ghats_building_density_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_settlement_boundaries.geojson",
                "data/processed/regions/western_ghats/western_ghats_building_footprints.geojson",
                "data/processed/regions/western_ghats/western_ghats_population_density_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_population_demographics.geojson",
                "data/processed/regions/western_ghats/western_ghats_drainage_wetland_utm43.tif",
                "data/processed/regions/western_ghats/western_ghats_wetlands_polygons.geojson",
                "data/processed/regions/western_ghats/western_ghats_drainage_network.geojson",
                "data/processed/regions/western_ghats/western_ghats_watershed_basins.geojson"
        );

        RegionalCorridorPreset gangeticPlains = new RegionalCorridorPreset(
                "gangetic_plains",
                "CORRIDOR-3-GANGETIC-PLAINS",
                "Corridor 3: Indo-Gangetic Plains High-Density Agricultural & Canal Corridor (Barabanki–Ramsanehighat–Ayodhya, UP)",
                "Uttar Pradesh (Gomti–Kalyani–Ghaghara Alluvial Interfluve & Sharda Sahayak Canal Command)",
                "INDO_GANGETIC_ALLUVIAL_PLAINS_AND_CANAL_COMMAND",
                "Dense rural abadi settlements & building footprints, Sharda Sahayak irrigation canal network, oxbow tals/wetlands, high demographic density, and alluvial liquefaction.",
                44,
                "EPSG:32644",
                new StudyRegion(
                        "Indo-Gangetic Plains (Barabanki–Ramsanehighat–Ayodhya, UP)",
                        26.55, 81.10, 27.20, 82.25,
                        new GeoPoint(26.91500, 81.24500),
                        new GeoPoint(26.76500, 82.12500)
                ),
                "data/processed/regions/gangetic_plains/gangetic_plains_lulc_samples.json",
                "data/processed/regions/gangetic_plains/gangetic_plains_slope_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_protected_area_mask_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_settlement_exposure_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_settlement_boundaries.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_transport_network_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_transport_network.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_regional_hubs.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_water_bodies_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_perennial_rivers.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_wetlands_polygons.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_wildlife_esz_corridors_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_wildlife_corridors.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_geotech_landslide_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_fault_landslide_zones.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_utility_corridors_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_power_transmission_lines.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_utility_assets.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_building_density_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_settlement_boundaries.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_building_footprints.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_population_density_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_population_demographics.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_drainage_wetland_utm44.tif",
                "data/processed/regions/gangetic_plains/gangetic_plains_wetlands_polygons.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_drainage_network.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_watershed_basins.geojson"
        );

        presets.put(assam.id(), assam);
        presets.put(westernGhats.id(), westernGhats);
        presets.put(gangeticPlains.id(), gangeticPlains);
    }

    public List<RegionalCorridorPreset> listPresets() {
        return List.copyOf(presets.values());
    }

    public synchronized void registerCustomPreset(RegionalCorridorPreset preset) {
        if (preset == null) {
            throw new IllegalArgumentException("Preset must not be null");
        }
        presets.put(normalizeRegionId(preset.id()), preset);
    }

    public boolean hasPreset(String regionParam) {
        String normalized = normalizeRegionId(regionParam);
        return normalized != null && presets.containsKey(normalized);
    }

    public RegionalCorridorPreset getPreset(String regionParam) {
        String normalized = normalizeRegionId(regionParam);
        RegionalCorridorPreset preset = presets.get(normalized);
        if (preset == null) {
            throw new IllegalArgumentException(
                    "Unknown corridor region '" + regionParam
                            + "'. Supported presets: assam, western_ghats, gangetic_plains, or pan_india.");
        }
        return preset;
    }

    public boolean isRegionLoadedInMemory(String regionParam) {
        String normalized = normalizeRegionId(regionParam);
        return normalized != null && loadedPipelines.containsKey(normalized);
    }

    public Set<String> loadedRegionIds() {
        return Collections.unmodifiableSet(loadedPipelines.keySet());
    }

    public void evictRegionFromMemory(String regionParam) {
        String normalized = normalizeRegionId(regionParam);
        if (normalized != null) {
            loadedPipelines.remove(normalized);
        }
    }

    public void clearLoadedRegions() {
        loadedPipelines.clear();
    }

    public RegionalPipelineBundle getOrLoadPipeline(String regionParam) {
        RegionalCorridorPreset preset = getPreset(regionParam);
        return loadedPipelines.computeIfAbsent(preset.id(), id -> buildPipelineBundle(preset));
    }

    public RegionalCorridorPreset findContainingPreset(GeoPoint start, GeoPoint goal) {
        if (start == null || goal == null) {
            return presets.get("assam");
        }
        for (RegionalCorridorPreset preset : presets.values()) {
            if (preset.containsBoth(start, goal)) {
                return preset;
            }
        }
        for (RegionalCorridorPreset preset : presets.values()) {
            StudyRegion region = preset.studyRegion();
            if (containsWithMargin(region, start, PRESET_MARGIN_DEG)
                    && containsWithMargin(region, goal, PRESET_MARGIN_DEG)) {
                return preset;
            }
        }
        return null;
    }

    public Map<String, Object> describeTwoTierCatalog() {
        List<Map<String, Object>> presetList = new ArrayList<>();
        for (RegionalCorridorPreset preset : presets.values()) {
            presetList.add(preset.toSummaryMap(isRegionLoadedInMemory(preset.id())));
        }

        Map<String, Object> tier1Map = new LinkedHashMap<>();
        tier1Map.put("id", "pan_india");
        tier1Map.put("tierMode", CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name());
        tier1Map.put("displayName", PanIndiaDynamicCorridorService.PAN_INDIA_BOUNDS.name());
        tier1Map.put("bbox", Map.of(
                "minLat", PanIndiaDynamicCorridorService.PAN_INDIA_BOUNDS.minLat(),
                "minLon", PanIndiaDynamicCorridorService.PAN_INDIA_BOUNDS.minLon(),
                "maxLat", PanIndiaDynamicCorridorService.PAN_INDIA_BOUNDS.maxLat(),
                "maxLon", PanIndiaDynamicCorridorService.PAN_INDIA_BOUNDS.maxLon()
        ));
        tier1Map.put("supportedUtmZones", List.of(
                "UTM 42N (EPSG:32642)",
                "UTM 43N (EPSG:32643)",
                "UTM 44N (EPSG:32644)",
                "UTM 45N (EPSG:32645)",
                "UTM 46N (EPSG:32646)",
                "UTM 47N (EPSG:32647)"
        ));
        tier1Map.put("routingResolutionMeters", ROUTING_RESOLUTION_METERS);
        tier1Map.put("arbitraryPointSelectionSupported", true);

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("architecture", "MARG Two-Tier Hybrid Spatial Architecture (Pan-India Dynamic + Lazy-Loaded Regional Benchmark Corridors)");
        catalog.put("defaultRegionId", "assam");
        catalog.put("loadedRegionsInMemory", new ArrayList<>(loadedPipelines.keySet()));
        catalog.put("tier2PreCachedCorridors", presetList);
        catalog.put("tier1DynamicPanIndiaEngine", tier1Map);
        return catalog;
    }

    public Map<String, Object> route(
            String regionParam,
            String tierModeParam,
            Double startLat,
            Double startLon,
            Double endLat,
            Double endLon,
            Double resolutionMeters,
            Double corridorBufferDeg,
            Integer maxLulcSamples,
            Double maxDeviationPercent,
            String facilityMode,
            String targetHubs,
            Integer minHubPopulation,
            Double maxVcRatio) {
        return route(
                regionParam,
                tierModeParam,
                startLat,
                startLon,
                endLat,
                endLon,
                resolutionMeters,
                corridorBufferDeg,
                maxLulcSamples,
                maxDeviationPercent,
                facilityMode,
                targetHubs,
                minHubPopulation,
                maxVcRatio,
                true,
                null);
    }

    public Map<String, Object> route(
            String regionParam,
            String tierModeParam,
            Double startLat,
            Double startLon,
            Double endLat,
            Double endLon,
            Double resolutionMeters,
            Double corridorBufferDeg,
            Integer maxLulcSamples,
            Double maxDeviationPercent,
            String facilityMode,
            String targetHubs,
            Integer minHubPopulation,
            Double maxVcRatio,
            Boolean autoConnectHubs,
            Integer maxAutoHubs) {
        return route(
                regionParam,
                tierModeParam,
                startLat,
                startLon,
                endLat,
                endLon,
                resolutionMeters,
                corridorBufferDeg,
                maxLulcSamples,
                maxDeviationPercent,
                facilityMode,
                targetHubs,
                minHubPopulation,
                maxVcRatio,
                autoConnectHubs,
                maxAutoHubs,
                in.marg.routing.AlignmentPreference.BALANCED);
    }

    public Map<String, Object> route(
            String regionParam,
            String tierModeParam,
            Double startLat,
            Double startLon,
            Double endLat,
            Double endLon,
            Double resolutionMeters,
            Double corridorBufferDeg,
            Integer maxLulcSamples,
            Double maxDeviationPercent,
            String facilityMode,
            String targetHubs,
            Integer minHubPopulation,
            Double maxVcRatio,
            Boolean autoConnectHubs,
            Integer maxAutoHubs,
            in.marg.routing.AlignmentPreference preference) {
        return route(
                regionParam,
                tierModeParam,
                startLat,
                startLon,
                endLat,
                endLon,
                resolutionMeters,
                corridorBufferDeg,
                maxLulcSamples,
                maxDeviationPercent,
                facilityMode,
                targetHubs,
                minHubPopulation,
                maxVcRatio,
                autoConnectHubs,
                maxAutoHubs,
                preference,
                false);
    }

    public Map<String, Object> route(
            String regionParam,
            String tierModeParam,
            Double startLat,
            Double startLon,
            Double endLat,
            Double endLon,
            Double resolutionMeters,
            Double corridorBufferDeg,
            Integer maxLulcSamples,
            Double maxDeviationPercent,
            String facilityMode,
            String targetHubs,
            Integer minHubPopulation,
            Double maxVcRatio,
            Boolean autoConnectHubs,
            Integer maxAutoHubs,
            in.marg.routing.AlignmentPreference preference,
            Boolean deep) {

        double resolution = resolutionMeters == null ? ROUTING_RESOLUTION_METERS : resolutionMeters;
        if (Math.abs(resolution - ROUTING_RESOLUTION_METERS) > 1e-9) {
            throw new IllegalArgumentException("Composite routing is fixed at the canonical 250 m grid.");
        }

        in.marg.routing.AlignmentPreference pref = preference != null
                ? preference
                : in.marg.routing.AlignmentPreference.BALANCED;

        CorridorTierMode explicitTier = CorridorTierMode.fromParam(tierModeParam);
        String normalizedRegion = normalizeRegionId(regionParam);
        FacilityMode mode = FacilityMode.fromParam(
                facilityMode,
                transportProperties.getDefaultFacilityMode());

        // Case 1: Explicit Tier 1 Pan-India mode or region="pan_india"
        if (explicitTier == CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA
                || "pan_india".equals(normalizedRegion)) {
            GeoPoint start = startLat == null
                    ? PanIndiaDynamicCorridorService.PAN_INDIA_BOUNDS.origin()
                    : new GeoPoint(startLat, require("startLon", startLon));
            GeoPoint goal = endLat == null
                    ? PanIndiaDynamicCorridorService.PAN_INDIA_BOUNDS.destination()
                    : new GeoPoint(endLat, require("endLon", endLon));
            List<String> hubList = targetHubs == null || targetHubs.isBlank()
                    ? List.of()
                    : Arrays.stream(targetHubs.split(",(?![^()]*\\))"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
            return panIndiaService.routeDynamicPanIndia(
                    start, goal, mode, corridorBufferDeg, maxDeviationPercent, hubList, autoConnectHubs, maxAutoHubs, pref, deep);
        }

        // Case 2: Explicit preset region ("assam", "western_ghats", "gangetic_plains")
        if (normalizedRegion != null) {
            RegionalCorridorPreset preset = getPreset(normalizedRegion);
            GeoPoint start = startLat == null
                    ? preset.studyRegion().origin()
                    : new GeoPoint(startLat, require("startLon", startLon));
            GeoPoint goal = endLat == null
                    ? preset.studyRegion().destination()
                    : new GeoPoint(endLat, require("endLon", endLon));
            return routeInPresetRegion(
                    preset.id(),
                    start,
                    goal,
                    corridorBufferDeg,
                    maxLulcSamples,
                    maxDeviationPercent,
                    mode,
                    targetHubs,
                    minHubPopulation,
                    maxVcRatio,
                    autoConnectHubs,
                    maxAutoHubs,
                    pref);
        }

        // Case 3: No region parameter specified — auto-detect from coordinates!
        if (startLat == null && endLat == null) {
            RegionalCorridorPreset assam = presets.get("assam");
            return routeInPresetRegion(
                    assam.id(),
                    assam.studyRegion().origin(),
                    assam.studyRegion().destination(),
                    corridorBufferDeg,
                    maxLulcSamples,
                    maxDeviationPercent,
                    mode,
                    targetHubs,
                    minHubPopulation,
                    maxVcRatio,
                    autoConnectHubs,
                    maxAutoHubs,
                    pref);
        }

        RegionalCorridorPreset defaultAssam = presets.get("assam");
        GeoPoint start = startLat == null
                ? defaultAssam.studyRegion().origin()
                : new GeoPoint(startLat, require("startLon", startLon));
        GeoPoint goal = endLat == null
                ? defaultAssam.studyRegion().destination()
                : new GeoPoint(endLat, require("endLon", endLon));

        RegionalCorridorPreset matchingPreset = findContainingPreset(start, goal);
        if (matchingPreset != null) {
            return routeInPresetRegion(
                    matchingPreset.id(),
                    start,
                    goal,
                    corridorBufferDeg,
                    maxLulcSamples,
                    maxDeviationPercent,
                    mode,
                    targetHubs,
                    minHubPopulation,
                    maxVcRatio,
                    autoConnectHubs,
                    maxAutoHubs,
                    pref);
        }

        // If explicitTier was TIER_2, reject coordinates outside presets; otherwise route via Tier-1 Pan-India if inside India
        if (explicitTier != CorridorTierMode.TIER_2_PRECACHED_BENCHMARK
                && panIndiaService.isWithinIndiaBounds(start)
                && panIndiaService.isWithinIndiaBounds(goal)) {
            List<String> hubList = targetHubs == null || targetHubs.isBlank()
                    ? List.of()
                    : Arrays.stream(targetHubs.split(",(?![^()]*\\))"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
            return panIndiaService.routeDynamicPanIndia(
                    start, goal, mode, corridorBufferDeg, maxDeviationPercent, hubList, autoConnectHubs, maxAutoHubs, pref, deep);
        }

        throw new IllegalArgumentException(
                "Both endpoints must be inside the configured study region or within Pan-India bounds.");
    }

    public Map<String, Object> routeInPresetRegion(
            String regionId,
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            Double corridorBufferDeg,
            Integer maxLulcSamples,
            Double maxDeviationPercent,
            FacilityMode mode,
            String targetHubs,
            Integer minHubPopulation,
            Double maxVcRatio) {
        return routeInPresetRegion(
                regionId,
                requestedStart,
                requestedGoal,
                corridorBufferDeg,
                maxLulcSamples,
                maxDeviationPercent,
                mode,
                targetHubs,
                minHubPopulation,
                maxVcRatio,
                true,
                null);
    }

    public Map<String, Object> routeInPresetRegion(
            String regionId,
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            Double corridorBufferDeg,
            Integer maxLulcSamples,
            Double maxDeviationPercent,
            FacilityMode mode,
            String targetHubs,
            Integer minHubPopulation,
            Double maxVcRatio,
            Boolean autoConnectHubs,
            Integer maxAutoHubs) {
        return routeInPresetRegion(
                regionId,
                requestedStart,
                requestedGoal,
                corridorBufferDeg,
                maxLulcSamples,
                maxDeviationPercent,
                mode,
                targetHubs,
                minHubPopulation,
                maxVcRatio,
                autoConnectHubs,
                maxAutoHubs,
                in.marg.routing.AlignmentPreference.BALANCED);
    }

    public Map<String, Object> routeInPresetRegion(
            String regionId,
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            Double corridorBufferDeg,
            Integer maxLulcSamples,
            Double maxDeviationPercent,
            FacilityMode mode,
            String targetHubs,
            Integer minHubPopulation,
            Double maxVcRatio,
            Boolean autoConnectHubs,
            Integer maxAutoHubs,
            in.marg.routing.AlignmentPreference preference) {

        in.marg.routing.AlignmentPreference pref = preference != null
                ? preference
                : in.marg.routing.AlignmentPreference.BALANCED;

        Double effectiveMaxDev = maxDeviationPercent;
        if (effectiveMaxDev == null) {
            if (pref.environmentalWeight() >= 2.0) {
                effectiveMaxDev = 25.0;
            } else if (pref.directnessWeight() >= 2.0) {
                effectiveMaxDev = 10.0;
            }
        }

        boolean wasAlreadyLoaded = isRegionLoadedInMemory(regionId);
        RegionalPipelineBundle bundle = getOrLoadPipeline(regionId);
        RegionalCorridorPreset preset = bundle.preset();
        StudyRegion fullRegion = preset.studyRegion();

        if (!fullRegion.contains(requestedStart) || !fullRegion.contains(requestedGoal)) {
            if (containsWithMargin(fullRegion, requestedStart, PRESET_MARGIN_DEG)
                    && containsWithMargin(fullRegion, requestedGoal, PRESET_MARGIN_DEG)) {
                requestedStart = clampToRegion(fullRegion, requestedStart);
                requestedGoal = clampToRegion(fullRegion, requestedGoal);
            } else {
                // When endpoints extend beyond this preset's boundaries, gracefully route
                // via Pan-India dynamic corridor engine rather than crashing with an exception!
                List<String> hubList = targetHubs == null || targetHubs.isBlank()
                        ? List.of()
                        : Arrays.stream(targetHubs.split(",(?![^()]*\\))"))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();
                return panIndiaService.routeDynamicPanIndia(
                        requestedStart, requestedGoal, mode, corridorBufferDeg, maxDeviationPercent, hubList, autoConnectHubs, maxAutoHubs, pref);
            }
        }

        if (maxVcRatio != null
                && (!Double.isFinite(maxVcRatio) || maxVcRatio <= 0.1 || maxVcRatio > 2.0)) {
            throw new IllegalArgumentException("maxVcRatio must be between 0.1 and 2.0");
        }

        double buffer = corridorBufferDeg == null ? 0.15 : corridorBufferDeg;
        if (buffer < 0.02 || buffer > 0.30) {
            throw new IllegalArgumentException("corridorBufferDeg must be between 0.02 and 0.30");
        }

        int maxSamples = maxLulcSamples == null ? 4000 : maxLulcSamples;
        if (maxSamples < 1 || maxSamples > 10000) {
            throw new IllegalArgumentException("maxLulcSamples must be between 1 and 10000");
        }

        List<String> targetHubList = targetHubs == null || targetHubs.isBlank()
                ? List.of()
                : Arrays.stream(targetHubs.split(",(?![^()]*\\))"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();

        StudyRegion initialWindow = corridorWindow(fullRegion, requestedStart, requestedGoal, buffer);

        // Stage 1: Evaluate Hubs
        HubEvaluationService.EvaluationResult initialHubEval = bundle.hubEvaluationService().evaluate(
                requestedStart,
                requestedGoal,
                initialWindow,
                mode,
                targetHubList,
                minHubPopulation,
                maxVcRatio,
                autoConnectHubs,
                maxAutoHubs);

        // Stage 2: Build 13-Layer Cost Surface
        CompositeCostSurfaceFactory.BuildResult initialBuilt = bundle.costSurfaceFactory().build(
                initialWindow,
                maxSamples,
                mode,
                initialHubEval.approvedHubs(),
                requestedStart,
                requestedGoal,
                pref);

        // Stage 2b: Smart Endpoint Snapping off Hospitals (+100m), Settlements/Colonies, Buildings & Hard Terrain
        EndpointSnapService.EndpointObstacleEvaluator obstacleEvaluator =
                EndpointSnapService.forBenchmarkCorridor(
                        bundle.buildingVectorIndex(), bundle.buildingRasterProvider());
        EndpointSnapService.CandidateTieInScorer tieInScorer =
                EndpointSnapService.forCleanRoadTieIn(
                        fullRegion, bundle.transportIndex(), bundle.buildingVectorIndex());
        EndpointSnapService.EndpointResolution endpointResolution = bundle.endpointSnapService().resolveEndpoints(
                fullRegion,
                initialWindow,
                initialBuilt.surface(),
                requestedStart,
                requestedGoal,
                obstacleEvaluator,
                tieInScorer);

        GeoPoint start = endpointResolution.resolvedStart();
        GeoPoint goal = endpointResolution.resolvedGoal();
        StudyRegion searchWindow = corridorWindow(fullRegion, start, goal, buffer);

        HubEvaluationService.EvaluationResult hubEvaluation =
                (endpointResolution.startSnapped() || endpointResolution.goalSnapped())
                        ? bundle.hubEvaluationService().evaluate(
                                start, goal, searchWindow, mode, targetHubList, minHubPopulation, maxVcRatio, autoConnectHubs, maxAutoHubs)
                        : initialHubEval;

        CompositeCostSurfaceFactory.BuildResult built =
                (endpointResolution.startSnapped() || endpointResolution.goalSnapped())
                        ? bundle.costSurfaceFactory().build(
                                searchWindow, maxSamples, mode, hubEvaluation.approvedHubs(), start, goal, pref)
                        : initialBuilt;

        // Determine whether corridor fits in a single 250 m A* window or multi-stage 250 m windows
        double dLat = Math.abs(goal.lat() - start.lat());
        double dLon = Math.abs(goal.lon() - start.lon());
        int stages = Math.max(1, (int) Math.ceil(Math.max(dLat, dLon) / 1.20));

        List<GeoPoint> baseline = new ArrayList<>();
        List<GeoPoint> mainline = new ArrayList<>();

        if (stages <= 1) {
            baseline = bundle.router().route(
                    fullRegion,
                    start,
                    goal,
                    new DistanceOnlyCostSurface(),
                    ROUTING_RESOLUTION_METERS,
                    searchWindow);

            double baselineLengthKm = bundle.router().routeLengthKm(baseline);

            RouteConstraints constraints = RouteConstraints.none();
            if (effectiveMaxDev != null) {
                if (!Double.isFinite(effectiveMaxDev)
                        || effectiveMaxDev < 0.0
                        || effectiveMaxDev > 200.0) {
                    throw new IllegalArgumentException("maxDeviationPercent must be between 0 and 200");
                }
                double maxLengthKm = baselineLengthKm * (1.0 + effectiveMaxDev / 100.0);
                constraints = RouteConstraints.maxLengthKm(maxLengthKm);
            }

            // Stage 3: Route 13-Layer Greenfield Mainline (Hard-block dense colonies, fallback to 1000x barrier if surrounded)
            try {
                mainline = bundle.router().route(
                        fullRegion,
                        start,
                        goal,
                        built.surface(),
                        ROUTING_RESOLUTION_METERS,
                        searchWindow,
                        constraints);
            } catch (RuntimeException primaryBlocked) {
                mainline = bundle.router().route(
                        fullRegion,
                        start,
                        goal,
                        built.surface().withDenseColonyLastResortMode(),
                        ROUTING_RESOLUTION_METERS,
                        searchWindow,
                        constraints);
            }
        } else {
            // Adaptive corridor stage windows across long corridors (> 72 km) to preserve 250 m IRC grid resolution
            List<GeoPoint> stageWaypoints = buildStageWaypoints(
                    start, goal, stages, fullRegion, built.surface(), bundle.endpointSnapService());

            for (int s = 0; s < stageWaypoints.size() - 1; s++) {
                GeoPoint stageStart = stageWaypoints.get(s);
                GeoPoint stageGoal = stageWaypoints.get(s + 1);
                double stageBuffer = Math.min(Math.max(buffer, 0.16), 0.25);
                StudyRegion stageWindow = corridorWindow(fullRegion, stageStart, stageGoal, stageBuffer);

                List<GeoPoint> stageBaseline = bundle.router().route(
                        fullRegion,
                        stageStart,
                        stageGoal,
                        new DistanceOnlyCostSurface(),
                        ROUTING_RESOLUTION_METERS,
                        stageWindow);

                RouteConstraints stageConstraints = RouteConstraints.none();
                if (effectiveMaxDev != null
                        && Double.isFinite(effectiveMaxDev)
                        && effectiveMaxDev >= 0.0
                        && effectiveMaxDev <= 200.0) {
                    double maxStageKm = bundle.router().routeLengthKm(stageBaseline) * (1.0 + effectiveMaxDev / 100.0);
                    stageConstraints = RouteConstraints.maxLengthKm(maxStageKm);
                }

                List<GeoPoint> stageMainline;
                try {
                    stageMainline = bundle.router().route(
                            fullRegion,
                            stageStart,
                            stageGoal,
                            built.surface(),
                            ROUTING_RESOLUTION_METERS,
                            stageWindow,
                            stageConstraints);
                } catch (RuntimeException primaryBlocked) {
                    stageMainline = bundle.router().route(
                            fullRegion,
                            stageStart,
                            stageGoal,
                            built.surface().withDenseColonyLastResortMode(),
                            ROUTING_RESOLUTION_METERS,
                            stageWindow,
                            stageConstraints);
                }

                appendPathSegment(baseline, stageBaseline);
                appendPathSegment(mainline, stageMainline);
            }
        }

        double baselineLengthKm = bundle.router().routeLengthKm(baseline);

        // Stage 3b: Connect origin & destination to nearest building-safe existing road tie-in
        // enforcing exact 2D polygon/segment checks against hospitals, colonies, and buildings.
        var bldIdx = bundle.buildingVectorIndex();
        double instBufM = buildingProperties.getInstitutionalSafetyBufferMeters();
        java.util.function.Predicate<GeoPoint> safeTieInPred = pt -> fullRegion.contains(pt)
                && !built.surface().assess(pt).blocked()
                && (bldIdx == null
                || bldIdx.findEndpointBuildingOrSettlementObstacle(pt, instBufM, 15.0) == null);
        java.util.function.BiPredicate<GeoPoint, GeoPoint> cleanConnectorPred = (fromPt, toPt) ->
                bldIdx == null || !bldIdx.hasSegmentBuildingOrExclusionConflict(fromPt, toPt, 25.0, instBufM);

        TransportNetworkVectorIndex.NearestRoadTieIn startTieIn = bundle.transportIndex().findNearestRoadTieIn(
                start, safeTieInPred, cleanConnectorPred);
        TransportNetworkVectorIndex.NearestRoadTieIn goalTieIn = bundle.transportIndex().findNearestRoadTieIn(
                goal, safeTieInPred, cleanConnectorPred);

        List<GeoPoint> startConnectorPath = List.of();
        double startConnectorKm = 0.0;
        if (startTieIn != null) {
            if (startTieIn.distanceMeters() > 35.0) {
                startConnectorPath = routeTerminalAccessConnector(
                        fullRegion,
                        bundle.router(),
                        bundle.projection(),
                        built.surface(),
                        built.terrainAndSocialLayers(),
                        bldIdx,
                        instBufM,
                        startTieIn.tieInPoint(),
                        start);
                startConnectorKm = bundle.router().routeLengthKm(startConnectorPath);
            } else if (!mainline.isEmpty()
                    && cleanConnectorPred.test(startTieIn.tieInPoint(), mainline.get(0))) {
                mainline.set(0, startTieIn.tieInPoint());
            }
        }

        List<GeoPoint> goalConnectorPath = List.of();
        double goalConnectorKm = 0.0;
        if (goalTieIn != null) {
            if (goalTieIn.distanceMeters() > 35.0) {
                goalConnectorPath = routeTerminalAccessConnector(
                        fullRegion,
                        bundle.router(),
                        bundle.projection(),
                        built.surface(),
                        built.terrainAndSocialLayers(),
                        bldIdx,
                        instBufM,
                        goal,
                        goalTieIn.tieInPoint());
                goalConnectorKm = bundle.router().routeLengthKm(goalConnectorPath);
            } else if (!mainline.isEmpty()
                    && cleanConnectorPred.test(mainline.get(mainline.size() - 1), goalTieIn.tieInPoint())) {
                mainline.set(mainline.size() - 1, goalTieIn.tieInPoint());
            }
        }

        if (!startConnectorPath.isEmpty() || !goalConnectorPath.isEmpty()) {
            List<GeoPoint> stitched = new ArrayList<>();
            if (!startConnectorPath.isEmpty()) {
                appendPathSegment(stitched, startConnectorPath);
            }
            appendPathSegment(stitched, mainline);
            if (!goalConnectorPath.isEmpty()) {
                appendPathSegment(stitched, goalConnectorPath);
            }
            mainline = stitched;
        }

        double totalTerminalAccessRoadLengthKm = round(startConnectorKm + goalConnectorKm);
        double routeLengthKm = bundle.router().routeLengthKm(mainline);
        double deviationPercent = baselineLengthKm > 0.0
                ? ((routeLengthKm / baselineLengthKm) - 1.0) * 100.0
                : Double.NaN;

        // Stage 4: Interchange Placement, Spurs & Cross-Drainage / Structural Planning
        HighwayNetworkPlannerService.NetworkPlanResult networkPlan = bundle.networkPlannerService().planNetwork(
                fullRegion,
                mainline,
                hubEvaluation,
                built.terrainAndSocialLayers());

        Map<String, Object> routeDiagnostics = bundle.routeMetricsService().summarize(mainline, mode);
        var flood = built.floodPreload().mosaic();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("corridorTierMode", CorridorTierMode.TIER_2_PRECACHED_BENCHMARK.name());
        metadata.put("regionId", preset.id());
        metadata.put("regionCode", preset.code());
        metadata.put("regionDisplayName", preset.displayName());
        metadata.put("physiographicZone", preset.physiographicZone());
        metadata.put("utmZone", preset.utmZone());
        metadata.put("lazyLoadedOnThisRequest", !wasAlreadyLoaded);
        metadata.put("endpointResolution", endpointResolution.toMetadataMap());
        metadata.put("terminalRoadConnections", buildTerminalRoadConnectionsMetadata(
                startTieIn, startConnectorKm, start,
                goalTieIn, goalConnectorKm, goal,
                totalTerminalAccessRoadLengthKm));
        metadata.put("routeDiagnostics", routeDiagnostics);
        metadata.put("facilityMode", mode.name());
        metadata.put("accessibilityPullApplied", built.accessibilityPullApplied());
        metadata.put("routingResolutionMeters", ROUTING_RESOLUTION_METERS);
        metadata.put("coordinateReferenceSystem", bundle.projection().crs());
        metadata.put("routingDistanceUnit", "metres internally; kilometres in metrics");
        metadata.put("searchWindow", searchWindow);
        metadata.put("greenfield", true);
        metadata.put("layers", List.of(
                "LULC",
                "FLOOD_HAZARD",
                "SLOPE",
                "PROTECTED_AREA",
                "SETTLEMENT_EXPOSURE",
                "EXISTING_TRANSPORT_NETWORK",
                "HYDROLOGY_WATER_BODIES",
                "WILDLIFE_CORRIDORS_AND_ESZ",
                "GEOTECH_SOIL_AND_LANDSLIDE",
                "HIGH_VOLTAGE_UTILITY_CORRIDORS",
                "SETTLEMENT_AND_BUILDING_FOOTPRINTS",
                "POPULATION_DENSITY_AND_DEMOGRAPHICS",
                "WETLANDS_SEASONAL_STREAMS_AND_DRAINAGE"
        ));

        metadata.put("hubEvaluationPipeline", Map.of(
                "facilityMode", mode.name(),
                "minHubPopulationApplied", hubEvaluation.minHubPopulationApplied(),
                "minInterchangeSpacingKmApplied", hubEvaluation.minInterchangeSpacingKmApplied(),
                "maxVcRatioCapApplied", hubEvaluation.maxVcRatioCapApplied(),
                "autoConnectHubs", autoConnectHubs == null || Boolean.TRUE.equals(autoConnectHubs),
                "maxAutoHubsApplied", maxAutoHubs != null ? maxAutoHubs : "UNLIMITED",
                "approvedHubIds", hubEvaluation.approvedHubs().stream()
                        .map(h -> h.hubId() + " (" + h.name() + ")")
                        .toList(),
                "hubDecisions", hubEvaluation.decisions().stream()
                        .map(HubEvaluationService.HubDecision::toMap)
                        .toList()
        ));

        Map<String, Object> networkAndCapacitySummary = new LinkedHashMap<>();
        networkAndCapacitySummary.put("mainlineLengthKm", networkPlan.mainlineLengthKm());
        networkAndCapacitySummary.put("totalTerminalAccessRoadLengthKm", totalTerminalAccessRoadLengthKm);
        networkAndCapacitySummary.put("totalDedicatedFeederSpurLengthKm", networkPlan.totalFeederSpurLengthKm());
        networkAndCapacitySummary.put("totalConstructedHighwayAndSpurKm", networkPlan.totalConstructedHighwayAndSpurKm());
        networkAndCapacitySummary.put("plannedInterchangesCount", networkPlan.interchanges().size());
        networkAndCapacitySummary.put("dedicatedFeederSpursCount", networkPlan.feederSpurs().size());
        networkAndCapacitySummary.put("railwayRobCount", networkPlan.railwayRobCount());
        networkAndCapacitySummary.put("highwayFlyoverUnderpassCount", networkPlan.roadFlyoverCount());
        networkAndCapacitySummary.put("averageCrossingAngleDegrees", networkPlan.averageCrossingAngleDegrees());
        networkAndCapacitySummary.put("riverBridgesCount", networkPlan.riverBridges().size());
        networkAndCapacitySummary.put("totalEstimatedRiverBridgeSpanMeters", networkPlan.totalEstimatedRiverBridgeSpanMeters());
        networkAndCapacitySummary.put("averageRiverCrossingAngleDegrees", networkPlan.averageRiverCrossingAngleDegrees());
        networkAndCapacitySummary.put("wildlifePassagesCount", networkPlan.wildlifePassages().size());
        networkAndCapacitySummary.put("totalRecommendedWildlifePassageSpanMeters", networkPlan.totalRecommendedWildlifePassageSpanMeters());
        networkAndCapacitySummary.put("averageWildlifeCrossingAngleDegrees", networkPlan.averageWildlifeCrossingAngleDegrees());
        networkAndCapacitySummary.put("geotechMitigationZonesCount", networkPlan.geotechMitigations().size());
        networkAndCapacitySummary.put("totalRecommendedGeotechStabilizationMeters", networkPlan.totalRecommendedGeotechStabilizationMeters());
        networkAndCapacitySummary.put("averageGeotechCrossingAngleDegrees", networkPlan.averageGeotechCrossingAngleDegrees());
        networkAndCapacitySummary.put("utilityCrossingsCount", networkPlan.utilityCrossings().size());
        networkAndCapacitySummary.put("totalRecommendedUtilityProtectiveSpanMeters", networkPlan.totalRecommendedUtilityProtectiveSpanMeters());
        networkAndCapacitySummary.put("averageUtilityCrossingAngleDegrees", networkPlan.averageUtilityCrossingAngleDegrees());
        networkAndCapacitySummary.put("settlementConflictZonesCount", networkPlan.settlementConflictZones().size());
        networkAndCapacitySummary.put("buildingsInDirectRowCount", networkPlan.buildingsInDirectRowCount());
        networkAndCapacitySummary.put("settlementsIntersectingDirectRowCount", networkPlan.settlementsIntersectingDirectRowCount());
        if (bundle.buildingVectorIndex() != null && networkPlan.rowCorridorFootprint() != null) {
            var bldRowImpact = bundle.buildingVectorIndex()
                    .evaluateCorridorFootprintImpact(mainline, networkPlan.rowCorridorFootprint());
            Map<String, Object> bldSummary = bldRowImpact.toSummaryMap();
            networkAndCapacitySummary.put("buildingsInDirectRowByType", bldRowImpact.buildingsInDirectRowByType());
            networkAndCapacitySummary.put("directRowAffectedFootprintAreaSqMeters", bldRowImpact.directRowAffectedFootprintAreaSqMeters());
            networkAndCapacitySummary.put("estimatedDirectlyDisplacedHouseholds", bldSummary.get("estimatedDirectlyDisplacedHouseholds"));
        }
        networkAndCapacitySummary.put("demographicExposureZonesCount", networkPlan.demographicExposureZones().size());
        networkAndCapacitySummary.put("populationWithinDirectRow", networkPlan.populationWithinDirectRow());
        networkAndCapacitySummary.put("estimatedDirectlyDisplacedPopulation", networkPlan.estimatedDirectlyDisplacedPopulation());
        networkAndCapacitySummary.put("populationWithin250mEnvironmentalBuffer", networkPlan.populationWithin250mEnvironmentalBuffer());
        networkAndCapacitySummary.put("populationWithin500mSecondaryBuffer", networkPlan.populationWithin500mSecondaryBuffer());
        networkAndCapacitySummary.put("drainageStructuresCount", networkPlan.drainageStructures().size());
        networkAndCapacitySummary.put("wetlandMitigationZonesCount", networkPlan.wetlandMitigationZones().size());
        networkAndCapacitySummary.put("totalWaterCrossingsCount", networkPlan.totalWaterCrossingsCount());
        networkAndCapacitySummary.put("wetlandsInDirectRowCount", networkPlan.wetlandsInDirectRowCount());
        networkAndCapacitySummary.put("wetlandAreaInDirectRowHectares", networkPlan.wetlandAreaInDirectRowHectares());
        networkAndCapacitySummary.put("watershedBoundaryCrossingsCount", networkPlan.watershedBoundaryCrossingsCount());
        networkAndCapacitySummary.put("totalRecommendedDrainageSpanMeters", networkPlan.totalRecommendedDrainageSpanMeters());
        if (networkPlan.rowCorridorFootprint() != null) {
            var rowFp = networkPlan.rowCorridorFootprint();
            networkAndCapacitySummary.put("rightOfWayAcquisitionFootprint", Map.of(
                    "ircStandard", rowFp.ircStandard(),
                    "mainlineRowWidthMeters", rowFp.mainlineRowWidthMeters(),
                    "feederSpurRowWidthMeters", rowFp.feederSpurRowWidthMeters(),
                    "interchangeFootprintRadiusMeters", rowFp.interchangeRadiusMeters(),
                    "mainlineRowAreaHectares", rowFp.mainlineRowAreaHectares(),
                    "feederSpursRowAreaHectares", rowFp.feederSpursRowAreaHectares(),
                    "interchangeLoopsAdditionalHectares", rowFp.interchangeLoopsAdditionalHectares(),
                    "totalDirectRowAcquisitionHectares", rowFp.totalDirectRowAcquisitionHectares(),
                    "constructionZoneGrossHectares", rowFp.constructionZoneGrossHectares(),
                    "environmentalBufferGrossHectares", rowFp.environmentalBufferGrossHectares()
            ));
        }
        networkAndCapacitySummary.put("totalCatchmentPopulationServed", networkPlan.totalCatchmentPopulationServed());

        double designCap = networkPlan.designCapacityPcuHr();
        double projTraffic = networkPlan.projectedTrafficVolumePcuHr();
        double fourLaneVc = networkPlan.projectedVcRatio();
        double maxVcCap = hubEvaluation.maxVcRatioCapApplied();
        boolean sixLaneRecommended = fourLaneVc > maxVcCap;
        double sixLaneCap = designCap * 1.5;
        double sixLaneVc = Math.round((projTraffic / sixLaneCap) * 100.0) / 100.0;
        String capacityStandardNote = sixLaneRecommended
                ? "IRC:SP:84 Section 2.2: Projected traffic (" + Math.round(projTraffic) + " PCU/hr) exceeds standard 4-lane capacity threshold (V/C "
                        + fourLaneVc + " > " + maxVcCap + "). Road configuration upgraded to 6-Lane Expressway (7,200 PCU/hr) yielding V/C "
                        + sixLaneVc + " (LOS B)."
                : "Operates within standard 4-Lane design capacity (V/C " + fourLaneVc + " <= " + maxVcCap + ").";

        Map<String, Object> trafficAnalysis = new LinkedHashMap<>();
        trafficAnalysis.put("baseThroughTrafficPcuHr", hubEvaluation.baseThroughTrafficPcuHr());
        trafficAnalysis.put("projectedTotalPeakTrafficPcuHr", projTraffic);
        trafficAnalysis.put("designCapacityPcuHr", designCap);
        trafficAnalysis.put("sixLaneCapacityPcuHr", sixLaneCap);
        trafficAnalysis.put("projectedVcRatio", fourLaneVc);
        trafficAnalysis.put("projectedSixLaneVcRatio", sixLaneVc);
        trafficAnalysis.put("maxVcRatioCapApplied", maxVcCap);
        trafficAnalysis.put("levelOfService", networkPlan.levelOfService());
        trafficAnalysis.put("sixLaneRecommended", sixLaneRecommended);
        trafficAnalysis.put("laneConfigurationRecommended", sixLaneRecommended ? "6_LANE_EXPANSION" : "4_LANE_STANDARD");
        trafficAnalysis.put("capacityStandardNote", capacityStandardNote);
        trafficAnalysis.put("overCapacityWarning", networkPlan.overCapacityWarning());
        networkAndCapacitySummary.put("trafficCapacityAnalysis", trafficAnalysis);
        metadata.put("transportNetworkAndCapacitySummary", networkAndCapacitySummary);

        metadata.put("drainageAndWetlandPolicy", Map.of(
                "nodePenalties", drainageProperties.toPolicy().nodePenalties(),
                "crossingBaseCostsKmEq", drainageProperties.toPolicy().crossingBaseCostsKmEq(),
                "wetlandDirectRowStepPenaltyKmEq", drainageProperties.getWetlandDirectRowStepPenaltyKmEq(),
                "wetland100mBufferStepPenaltyKmEq", drainageProperties.getWetland100mBufferStepPenaltyKmEq(),
                "wetland250mBufferStepPenaltyKmEq", drainageProperties.getWetland250mBufferStepPenaltyKmEq(),
                "parallelThalwegStepPenaltyKmEq", drainageProperties.getParallelThalwegStepPenaltyKmEq(),
                "watershedDivideCrossingPenaltyKmEq", drainageProperties.getWatershedDivideCrossingPenaltyKmEq(),
                "rasterPath", preset.drainageWetlandRasterPath(),
                "wetlandsGeoJsonPath", preset.wetlandsPolygonsGeoJsonPath(),
                "drainageNetworkGeoJsonPath", preset.drainageNetworkGeoJsonPath()
        ));

        metadata.put("lulc", Map.of(
                "samplesLoaded", built.lulcSamplesLoaded(),
                "penalties", lulcProperties.toPolicy().penalties()
        ));

        metadata.put("floodRaster", Map.of(
                "rasterId", flood.id(),
                "source", flood.sourceUrl(),
                "layer", flood.layerName(),
                "sourceResolution", flood.sourceResolution(),
                "width", flood.width(),
                "height", flood.height(),
                "remoteRequestsDuringPreload", built.floodPreload().remoteRequests(),
                "reusedExistingCache", built.floodPreload().reused()
        ));

        metadata.put("distanceBaselineLengthKm", round(baselineLengthKm));
        metadata.put("routeLengthKm", round(routeLengthKm));
        metadata.put("deviationPercent", round(deviationPercent));
        metadata.put("constraintMethod",
                maxDeviationPercent == null
                        ? "none"
                        : "unconstrained-A*-if-feasible-otherwise-Lagrangian-relaxation");
        metadata.put("constraintOptimizationApproximate", maxDeviationPercent != null);

        Map<String, Object> tradeOffProfile = in.marg.routing.TradeOffEvaluationService.evaluateTradeOffs(
                routeDiagnostics,
                networkPlan,
                baselineLengthKm,
                routeLengthKm,
                deviationPercent,
                pref,
                mainline);
        metadata.put("tradeOffProfile", tradeOffProfile);
        metadata.put("statutoryClearanceReport", tradeOffProfile.get("statutoryClearanceReport"));
        metadata.put("alignmentPreference", pref.toMap());

        Map<String, Object> mainlineProperties = new LinkedHashMap<>();
        mainlineProperties.put("profile", "COMPOSITE_13_LAYER");
        mainlineProperties.put("corridorTierMode", CorridorTierMode.TIER_2_PRECACHED_BENCHMARK.name());
        mainlineProperties.put("regionId", preset.id());
        mainlineProperties.put("routeLengthKm", round(routeLengthKm));
        mainlineProperties.put("distanceBaselineKm", round(baselineLengthKm));
        mainlineProperties.put("deviationPercent", round(deviationPercent));
        mainlineProperties.put("pointCount", mainline.size());
        mainlineProperties.put("greenfield", true);
        mainlineProperties.put("dataDriven", true);

        List<Map<String, Object>> features = new ArrayList<>(bundle.networkPlannerService().buildGeoJsonFeatures(
                mainline,
                mainlineProperties,
                networkPlan));
        addTerminalRoadFeatures(
                features,
                startTieIn, startConnectorPath, startConnectorKm,
                goalTieIn, goalConnectorPath, goalConnectorKm);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "FeatureCollection");
        response.put("corridorTierMode", CorridorTierMode.TIER_2_PRECACHED_BENCHMARK.name());
        response.put("regionId", preset.id());
        response.put("studyRegion", fullRegion.name());
        response.put("start", start);
        response.put("goal", goal);
        response.put("requestedStart", requestedStart);
        response.put("requestedGoal", requestedGoal);
        response.put("mode", "COMPOSITE_13_LAYER");
        response.put("facilityMode", mode.name());
        response.put("metadata", metadata);
        response.put("statutoryClearanceReport", tradeOffProfile.get("statutoryClearanceReport"));
        response.put("warning", "Preliminary greenfield planning corridor only; not a legal land-acquisition or clearance determination.");
        response.put("features", features);
        return response;
    }

    private RegionalPipelineBundle buildPipelineBundle(RegionalCorridorPreset preset) {
        CoordinateProjection projection = new Wgs84UtmProjection(preset.utmZone());
        GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
        RightOfWayCorridorEngine rowEngine = new RightOfWayCorridorEngine(projection);
        EndpointSnapService snapService = new EndpointSnapService(projection);

        LulcProvider lulcProvider = new RegionalLulcProvider(
                preset.id(),
                preset.displayName() + " LULC 1:50,000",
                preset.lulcCachePath(),
                0.01,
                objectMapper
        );

        SlopeRasterProvider slopeProvider = new SlopeRasterProvider(preset.slopeRasterPath());
        SlopeCostLayer slopeCostLayer = new SlopeCostLayer(slopeProvider, slopeProperties);

        ProtectedAreaRasterProvider paProvider = new ProtectedAreaRasterProvider(preset.protectedAreaRasterPath());
        ProtectedAreaCostLayer paCostLayer = new ProtectedAreaCostLayer(paProvider);

        SettlementRasterProvider settlementProvider = new SettlementRasterProvider(preset.settlementRasterPath());
        SettlementCostLayer settlementCostLayer = new SettlementCostLayer(settlementProvider, settlementProperties);

        TransportNetworkRasterProvider transportRaster = new TransportNetworkRasterProvider(preset.transportRasterPath());
        TransportNetworkVectorIndex transportIndex = new TransportNetworkVectorIndex(
                projection,
                preset.transportNetworkGeoJsonPath(),
                preset.regionalHubsGeoJsonPath()
        );

        WaterBodyRasterProvider waterRaster = new WaterBodyRasterProvider(preset.waterBodiesRasterPath());
        RiverNetworkVectorIndex riverIndex = new RiverNetworkVectorIndex(
                projection,
                preset.perennialRiversGeoJsonPath()
        );
        WaterBodyCostLayer waterCostLayer = new WaterBodyCostLayer(
                waterRaster, riverIndex, projection, waterBodyProperties
        );

        WildlifeRasterProvider wildlifeRaster = new WildlifeRasterProvider(preset.wildlifeRasterPath());
        WildlifeCorridorVectorIndex wildlifeIndex = new WildlifeCorridorVectorIndex(
                projection,
                preset.wildlifeCorridorsGeoJsonPath()
        );
        WildlifeCorridorCostLayer wildlifeCostLayer = new WildlifeCorridorCostLayer(
                wildlifeRaster, wildlifeIndex, projection, wildlifeProperties
        );

        GeotechRasterProvider geotechRaster = new GeotechRasterProvider(preset.geotechRasterPath());
        GeotechLineamentVectorIndex geotechIndex = new GeotechLineamentVectorIndex(
                projection,
                preset.faultLandslideGeoJsonPath()
        );
        GeotechLandslideCostLayer geotechCostLayer = new GeotechLandslideCostLayer(
                geotechRaster, geotechIndex, projection, geotechProperties
        );

        UtilityRasterProvider utilityRaster = new UtilityRasterProvider(preset.utilityRasterPath());
        UtilityNetworkVectorIndex utilityIndex = new UtilityNetworkVectorIndex(
                projection,
                preset.powerLinesGeoJsonPath(),
                preset.utilityAssetsGeoJsonPath()
        );
        UtilityCorridorCostLayer utilityCostLayer = new UtilityCorridorCostLayer(
                utilityRaster, utilityIndex, projection, utilityProperties
        );

        BuildingDensityRasterProvider buildingRaster = new BuildingDensityRasterProvider(preset.buildingDensityRasterPath());
        BuildingFootprintVectorIndex buildingIndex = new BuildingFootprintVectorIndex(
                projection,
                preset.settlementBoundariesGeoJsonPath(),
                preset.buildingFootprintsGeoJsonPath()
        );
        BuildingFootprintCostLayer buildingCostLayer = new BuildingFootprintCostLayer(
                buildingRaster, buildingIndex, projection, buildingProperties
        );

        PopulationDensityRasterProvider demoRaster = new PopulationDensityRasterProvider(preset.populationDensityRasterPath());
        DemographicVectorIndex demoIndex = new DemographicVectorIndex(
                projection,
                preset.demographicsGeoJsonPath()
        );
        DemographicCostLayer demoCostLayer = new DemographicCostLayer(
                demoRaster, demoIndex, projection, demographicProperties
        );

        DrainageWetlandRasterProvider drainageRaster = new DrainageWetlandRasterProvider(preset.drainageWetlandRasterPath());
        DrainageWetlandVectorIndex drainageIndex = new DrainageWetlandVectorIndex(
                projection,
                preset.wetlandsPolygonsGeoJsonPath(),
                preset.drainageNetworkGeoJsonPath(),
                preset.watershedBasinsGeoJsonPath()
        );
        DrainageWetlandCostLayer drainageCostLayer = new DrainageWetlandCostLayer(
                drainageRaster, drainageIndex, projection, drainageProperties
        );

        CompositeCostSurfaceFactory costSurfaceFactory = new CompositeCostSurfaceFactory(
                lulcProvider,
                lulcProperties,
                sharedFloodRasterCache,
                floodProperties,
                slopeCostLayer,
                paCostLayer,
                settlementCostLayer,
                waterCostLayer,
                wildlifeCostLayer,
                geotechCostLayer,
                utilityCostLayer,
                buildingCostLayer,
                demoCostLayer,
                drainageCostLayer,
                transportRaster,
                transportIndex,
                transportProperties,
                projection
        );

        HubEvaluationService hubEvaluationService = new HubEvaluationService(
                transportIndex, projection, transportProperties
        );

        HighwayNetworkPlannerService networkPlannerService = new HighwayNetworkPlannerService(
                transportIndex, transportRaster, slopeProvider, paProvider, router, projection,
                transportProperties, waterRaster, riverIndex, waterBodyProperties,
                wildlifeRaster, wildlifeIndex, wildlifeProperties,
                geotechRaster, geotechIndex, geotechProperties,
                utilityRaster, utilityIndex, utilityProperties,
                buildingRaster, buildingIndex, buildingProperties,
                demoRaster, demoIndex, demographicProperties,
                drainageRaster, drainageIndex, drainageProperties,
                rowEngine
        );

        CompositeRouteMetricsService routeMetricsService = new CompositeRouteMetricsService(
                projection, lulcProvider, lulcProperties, sharedFloodRasterCache, floodProperties,
                slopeProvider, slopeProperties, paProvider, settlementProvider, settlementProperties,
                transportRaster, transportIndex, transportProperties,
                waterRaster, riverIndex, waterBodyProperties,
                wildlifeRaster, wildlifeIndex, wildlifeProperties,
                geotechRaster, geotechIndex, geotechProperties,
                utilityRaster, utilityIndex, utilityProperties,
                buildingRaster, buildingIndex, buildingProperties,
                demoRaster, demoIndex, demographicProperties,
                drainageRaster, drainageIndex, drainageProperties,
                rowEngine
        );

        return new RegionalPipelineBundle(
                preset,
                projection,
                router,
                rowEngine,
                snapService,
                costSurfaceFactory,
                hubEvaluationService,
                networkPlannerService,
                routeMetricsService,
                transportIndex,
                slopeProvider,
                paProvider,
                buildingRaster,
                buildingIndex,
                drainageRaster,
                drainageIndex
        );
    }

    private static List<GeoPoint> routeTerminalAccessConnector(
            StudyRegion fullRegion,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            CompositeCostSurface primarySurface,
            List<CostLayer> terrainAndSocialLayers,
            BuildingFootprintVectorIndex buildingIndex,
            double institutionalBufferMeters,
            GeoPoint fromPt,
            GeoPoint toPt) {
        if (fromPt == null || toPt == null) {
            return List.of();
        }
        double directMeters = projection.distanceMeters(fromPt, toPt);
        boolean directSegmentSafe = in.marg.wildlife.IndianWildlifeSanctuaryRegistry.getDefaultInstance()
                .findIntersectingSanctuaryForSegment(fromPt.lat(), fromPt.lon(), toPt.lat(), toPt.lon()) == null
                && (buildingIndex == null
                || !buildingIndex.hasSegmentBuildingOrExclusionConflict(fromPt, toPt, 25.0, institutionalBufferMeters));

        if (directMeters <= 35.0) {
            return directSegmentSafe ? List.of(fromPt, toPt) : List.of();
        }

        // 1. Fine-grid A* (100m -> 150m -> 250m) on strict primary surface so connectors skirt hospitals & buildings
        double[] resolutions = directMeters <= 4000.0
                ? new double[]{100.0, 150.0, ROUTING_RESOLUTION_METERS}
                : new double[]{150.0, ROUTING_RESOLUTION_METERS};
        for (double resM : resolutions) {
            for (double buf : new double[]{0.05, 0.12, 0.25}) {
                StudyRegion localWin = corridorWindow(fullRegion, fromPt, toPt, buf);
                try {
                    List<GeoPoint> path = new ArrayList<>(router.route(
                            fullRegion, fromPt, toPt, primarySurface, resM, localWin));
                    if (!path.isEmpty() && isConnectorPathSafe(path, buildingIndex, institutionalBufferMeters)) {
                        path.set(0, fromPt);
                        path.set(path.size() - 1, toPt);
                        return path;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }

        // 2. Last-resort pass: dense colonies drop to 1000.0x barrier while Hospitals/Institutional/Sacred stay Hard-Blocked
        CompositeCostSurface lastResortSurface = primarySurface.withDenseColonyLastResortMode();
        for (double resM : resolutions) {
            for (double buf : new double[]{0.08, 0.18, 0.35}) {
                StudyRegion localWin = corridorWindow(fullRegion, fromPt, toPt, buf);
                try {
                    List<GeoPoint> path = new ArrayList<>(router.route(
                            fullRegion, fromPt, toPt, lastResortSurface, resM, localWin));
                    if (!path.isEmpty() && isConnectorPathInstitutionalSafe(path, buildingIndex, institutionalBufferMeters)) {
                        path.set(0, fromPt);
                        path.set(path.size() - 1, toPt);
                        return path;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }

        // 3. Only permit a direct line if verified 100% free of sanctuaries, hospitals, colonies, and buildings
        return directSegmentSafe ? List.of(fromPt, toPt) : List.of();
    }

    private static boolean isConnectorPathSafe(
            List<GeoPoint> path,
            BuildingFootprintVectorIndex buildingIndex,
            double institutionalBufferMeters) {
        if (buildingIndex == null || path == null || path.size() < 2) {
            return true;
        }
        for (int i = 1; i < path.size(); i++) {
            if (buildingIndex.findIntersectingInstitutionalOrSacredExclusion(
                    path.get(i - 1), path.get(i), 25.0 + institutionalBufferMeters) != null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isConnectorPathInstitutionalSafe(
            List<GeoPoint> path,
            BuildingFootprintVectorIndex buildingIndex,
            double institutionalBufferMeters) {
        return isConnectorPathSafe(path, buildingIndex, institutionalBufferMeters);
    }

    private static Map<String, Object> buildTerminalRoadConnectionsMetadata(
            TransportNetworkVectorIndex.NearestRoadTieIn startTieIn,
            double startConnectorKm,
            GeoPoint start,
            TransportNetworkVectorIndex.NearestRoadTieIn goalTieIn,
            double goalConnectorKm,
            GeoPoint goal,
            double totalTerminalAccessRoadLengthKm) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (startTieIn != null) {
            Map<String, Object> sMap = new LinkedHashMap<>();
            sMap.put("corridorId", startTieIn.corridorId());
            sMap.put("existingRoadRef", startTieIn.corridorRef());
            sMap.put("existingRoadName", startTieIn.corridorName());
            sMap.put("category", startTieIn.category().name());
            sMap.put("tieInPoint", startTieIn.tieInPoint());
            sMap.put("userPoint", start);
            sMap.put("straightOffsetMeters", startTieIn.distanceMeters());
            sMap.put("connectorLengthKm", round(startConnectorKm));
            sMap.put("directTieInApplied", startTieIn.distanceMeters() <= 35.0);
            map.put("startRoadConnection", sMap);
        }
        if (goalTieIn != null) {
            Map<String, Object> gMap = new LinkedHashMap<>();
            gMap.put("corridorId", goalTieIn.corridorId());
            gMap.put("existingRoadRef", goalTieIn.corridorRef());
            gMap.put("existingRoadName", goalTieIn.corridorName());
            gMap.put("category", goalTieIn.category().name());
            gMap.put("tieInPoint", goalTieIn.tieInPoint());
            gMap.put("userPoint", goal);
            gMap.put("straightOffsetMeters", goalTieIn.distanceMeters());
            gMap.put("connectorLengthKm", round(goalConnectorKm));
            gMap.put("directTieInApplied", goalTieIn.distanceMeters() <= 35.0);
            map.put("goalRoadConnection", gMap);
        }
        map.put("totalTerminalAccessRoadLengthKm", totalTerminalAccessRoadLengthKm);
        map.put("includedInMainlineAndStatistics", true);
        return map;
    }

    private static void addTerminalRoadFeatures(
            List<Map<String, Object>> features,
            TransportNetworkVectorIndex.NearestRoadTieIn startTieIn,
            List<GeoPoint> startConnectorPath,
            double startConnectorKm,
            TransportNetworkVectorIndex.NearestRoadTieIn goalTieIn,
            List<GeoPoint> goalConnectorPath,
            double goalConnectorKm) {
        if (startTieIn != null) {
            Map<String, Object> tieProps = new LinkedHashMap<>();
            tieProps.put("featureRole", "ROAD_TIE_IN");
            tieProps.put("tieInRole", "ORIGIN_EXISTING_ROAD_TIE_IN");
            tieProps.put("existingRoadRef", startTieIn.corridorRef());
            tieProps.put("tieInJunctionName", startTieIn.corridorName());
            tieProps.put("roadCategory", startTieIn.category().name());
            tieProps.put("connectorLengthKm", round(startConnectorKm));
            tieProps.put("continuityRule", "Connects user origin to existing navigable road network");
            features.add(Map.of(
                    "type", "Feature",
                    "properties", tieProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(startTieIn.tieInPoint().lon(), startTieIn.tieInPoint().lat()))));
        }
        if (goalTieIn != null) {
            Map<String, Object> tieProps = new LinkedHashMap<>();
            tieProps.put("featureRole", "ROAD_TIE_IN");
            tieProps.put("tieInRole", "DESTINATION_EXISTING_ROAD_TIE_IN");
            tieProps.put("existingRoadRef", goalTieIn.corridorRef());
            tieProps.put("tieInJunctionName", goalTieIn.corridorName());
            tieProps.put("roadCategory", goalTieIn.category().name());
            tieProps.put("connectorLengthKm", round(goalConnectorKm));
            tieProps.put("continuityRule", "Connects user destination to existing navigable road network");
            features.add(Map.of(
                    "type", "Feature",
                    "properties", tieProps,
                    "geometry", Map.of(
                            "type", "Point",
                            "coordinates", List.of(goalTieIn.tieInPoint().lon(), goalTieIn.tieInPoint().lat()))));
        }
        if (startTieIn != null && startConnectorPath.size() >= 2 && startConnectorKm > 0.035) {
            Map<String, Object> cProps = new LinkedHashMap<>();
            cProps.put("featureRole", "TERMINAL_ACCESS_ROAD");
            cProps.put("connectorRole", "ORIGIN_ROAD_CONNECTOR");
            cProps.put("existingRoadRef", startTieIn.corridorRef());
            cProps.put("existingRoadName", startTieIn.corridorName());
            cProps.put("connectorLengthKm", round(startConnectorKm));
            features.add(Map.of(
                    "type", "Feature",
                    "properties", cProps,
                    "geometry", Map.of(
                            "type", "LineString",
                            "coordinates", startConnectorPath.stream().map(p -> List.of(p.lon(), p.lat())).toList())));
        }
        if (goalTieIn != null && goalConnectorPath.size() >= 2 && goalConnectorKm > 0.035) {
            Map<String, Object> cProps = new LinkedHashMap<>();
            cProps.put("featureRole", "TERMINAL_ACCESS_ROAD");
            cProps.put("connectorRole", "DESTINATION_ROAD_CONNECTOR");
            cProps.put("existingRoadRef", goalTieIn.corridorRef());
            cProps.put("existingRoadName", goalTieIn.corridorName());
            cProps.put("connectorLengthKm", round(goalConnectorKm));
            features.add(Map.of(
                    "type", "Feature",
                    "properties", cProps,
                    "geometry", Map.of(
                            "type", "LineString",
                            "coordinates", goalConnectorPath.stream().map(p -> List.of(p.lon(), p.lat())).toList())));
        }
    }

    public static String normalizeRegionId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (clean) {
            case "assam", "corridor_1", "corridor1", "kaziranga", "karbi_anglong", "northeast" -> "assam";
            case "western_ghats", "westernghats", "corridor_2", "corridor2", "maharashtra", "pune_satara", "sahyadri" ->
                    "western_ghats";
            case "gangetic_plains", "gangeticplains", "indo_gangetic", "corridor_3", "corridor3", "up", "uttar_pradesh", "ayodhya", "barabanki_ayodhya" ->
                    "gangetic_plains";
            case "pan_india", "panindia", "india", "dynamic", "tier_1", "tier1" -> "pan_india";
            default -> clean;
        };
    }

    private static StudyRegion corridorWindow(
            StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double midLat = 0.5 * (a.lat() + b.lat());
        double midLon = 0.5 * (a.lon() + b.lon());
        var reg = in.marg.wildlife.IndianWildlifeSanctuaryRegistry.getDefaultInstance();
        boolean nearProtectedOrEsz = reg.matchPoint(midLat, midLon) != null
                || reg.matchPoint(a.lat(), a.lon()) != null
                || reg.matchPoint(b.lat(), b.lon()) != null
                || reg.findIntersectingSanctuaryForSegment(a.lat(), a.lon(), b.lat(), b.lon()) != null;
        double effBuf = nearProtectedOrEsz ? Math.max(bufferDeg, 0.22) : bufferDeg;

        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - effBuf);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + effBuf);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - effBuf);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + effBuf);
        return new StudyRegion(
                full.name() + " / composite A-B corridor",
                minLat, minLon, maxLat, maxLon, a, b);
    }

    private List<GeoPoint> buildStageWaypoints(
            GeoPoint start,
            GeoPoint goal,
            int stages,
            StudyRegion region,
            CostSurface surface,
            EndpointSnapService snapService) {
        if (stages <= 1) {
            return List.of(start, goal);
        }
        List<GeoPoint> waypoints = new ArrayList<>(stages + 1);
        waypoints.add(start);
        for (int i = 1; i < stages; i++) {
            double frac = (double) i / stages;
            GeoPoint rawMid = new GeoPoint(
                    start.lat() + frac * (goal.lat() - start.lat()),
                    start.lon() + frac * (goal.lon() - start.lon()));
            try {
                EndpointSnapService.SnapPointResult snapped = snapService.snapPoint(
                        region, surface, rawMid);
                waypoints.add(snapped.resolvedPoint());
            } catch (Exception ex) {
                waypoints.add(rawMid);
            }
        }
        waypoints.add(goal);
        return waypoints;
    }

    private static void appendPathSegment(List<GeoPoint> destination, List<GeoPoint> segment) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        if (destination.isEmpty()) {
            destination.addAll(segment);
            return;
        }
        int startIndex = segment.get(0).equals(destination.get(destination.size() - 1)) ? 1 : 0;
        for (int i = startIndex; i < segment.size(); i++) {
            destination.add(segment.get(i));
        }
    }

    private static BhuvanFloodRasterCache createLocalFloodCache(ObjectMapper mapper) {
        BhuvanFloodRasterCache cache = new BhuvanFloodRasterCache(
                null, "data/cache/bhuvan-flood-wms", 512, 1000, 28, 1, mapper);
        try {
            Method load = BhuvanFloodRasterCache.class.getDeclaredMethod("load");
            load.setAccessible(true);
            load.invoke(cache);
        } catch (Exception ignored) {
        }
        return cache;
    }

    private static LulcRoutingProperties defaultLulcProperties() {
        return mergeDefaultLulcPenalties(new LulcRoutingProperties());
    }

    private static LulcRoutingProperties mergeDefaultLulcPenalties(LulcRoutingProperties props) {
        LulcRoutingProperties out = props != null ? props : new LulcRoutingProperties();
        if (out.getPenalties() == null || out.getPenalties().isEmpty()) {
            Map<String, Double> lulcPen = new LinkedHashMap<>();
            lulcPen.put("BUILT_UP", 1.5);
            lulcPen.put("FOREST", 0.8);
            lulcPen.put("WETLAND_WATER", 2.0);
            lulcPen.put("AGRICULTURE", 0.25);
            lulcPen.put("GRASS_GRAZING", 0.10);
            lulcPen.put("BARREN_WASTELAND", 0.0);
            lulcPen.put("SNOW", 2.5);
            lulcPen.put("UNKNOWN", 1.0);
            out.setPenalties(lulcPen);
        }
        return out;
    }

    private static FloodRoutingProperties defaultFloodProperties() {
        return mergeDefaultFloodPenalties(new FloodRoutingProperties());
    }

    private static FloodRoutingProperties mergeDefaultFloodPenalties(FloodRoutingProperties props) {
        FloodRoutingProperties out = props != null ? props : new FloodRoutingProperties();
        if (out.getPenalties() == null || out.getPenalties().isEmpty()) {
            Map<String, Double> floodPen = new LinkedHashMap<>();
            floodPen.put("VERY_LOW", 0.0);
            floodPen.put("LOW", 0.20);
            floodPen.put("MODERATE", 0.60);
            floodPen.put("HIGH", 1.50);
            floodPen.put("VERY_HIGH", 3.00);
            floodPen.put("NOT_IN_HAZARD_LAYER", 0.0);
            floodPen.put("UNKNOWN", 2.00);
            out.setPenalties(floodPen);
        }
        return out;
    }

    private static double require(String name, Double value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    name + " is required when the paired coordinate is provided");
        }
        return value;
    }

    private static boolean containsWithMargin(StudyRegion region, GeoPoint point, double marginDeg) {
        return point.lat() >= region.minLat() - marginDeg
                && point.lat() <= region.maxLat() + marginDeg
                && point.lon() >= region.minLon() - marginDeg
                && point.lon() <= region.maxLon() + marginDeg;
    }

    private static GeoPoint clampToRegion(StudyRegion region, GeoPoint point) {
        double pad = 0.005;
        double lat = Math.max(region.minLat() + pad, Math.min(region.maxLat() - pad, point.lat()));
        double lon = Math.max(region.minLon() + pad, Math.min(region.maxLon() - pad, point.lon()));
        return new GeoPoint(lat, lon);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record RegionalPipelineBundle(
            RegionalCorridorPreset preset,
            CoordinateProjection projection,
            GreenfieldAStarRouter router,
            RightOfWayCorridorEngine rowEngine,
            EndpointSnapService endpointSnapService,
            CompositeCostSurfaceFactory costSurfaceFactory,
            HubEvaluationService hubEvaluationService,
            HighwayNetworkPlannerService networkPlannerService,
            CompositeRouteMetricsService routeMetricsService,
            TransportNetworkVectorIndex transportIndex,
            SlopeRasterProvider slopeProvider,
            ProtectedAreaRasterProvider protectedAreaProvider,
            BuildingDensityRasterProvider buildingRasterProvider,
            BuildingFootprintVectorIndex buildingVectorIndex,
            DrainageWetlandRasterProvider drainageRasterProvider,
            DrainageWetlandVectorIndex drainageVectorIndex
    ) {}
}
