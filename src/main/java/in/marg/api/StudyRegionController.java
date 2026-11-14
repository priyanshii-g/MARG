package in.marg.api;

import in.marg.model.StudyRegion;
import in.marg.region.CorridorIngestionService;
import in.marg.region.CorridorTierMode;
import in.marg.region.PanIndiaDynamicCorridorService;
import in.marg.region.RegionalCorridorPreset;
import in.marg.region.RegionalCorridorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/study-region")
public class StudyRegionController {

    private final StudyRegion region;
    private final RegionalCorridorRegistry corridorRegistry;
    private final CorridorIngestionService ingestionService;

    public StudyRegionController(StudyRegion region) {
        this(region, new RegionalCorridorRegistry(), null);
    }

    public StudyRegionController(
            StudyRegion region,
            RegionalCorridorRegistry corridorRegistry) {
        this(region, corridorRegistry, null);
    }

    @Autowired
    public StudyRegionController(
            StudyRegion region,
            @Autowired(required = false) RegionalCorridorRegistry corridorRegistry,
            @Autowired(required = false) CorridorIngestionService ingestionService) {
        this.region = region;
        this.corridorRegistry = corridorRegistry != null ? corridorRegistry : new RegionalCorridorRegistry();
        this.ingestionService = ingestionService != null ? ingestionService : new CorridorIngestionService(this.corridorRegistry);
    }

    @GetMapping
    public Object get(@RequestParam(required = false) String region) {
        if (region != null && !region.isBlank()) {
            String normalized = RegionalCorridorRegistry.normalizeRegionId(region);
            if ("pan_india".equals(normalized)) {
                StudyRegion pan = PanIndiaDynamicCorridorService.PAN_INDIA_BOUNDS;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", "pan_india");
                out.put("corridorTierMode", CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name());
                out.put("name", pan.name());
                out.put("bbox", Map.of(
                        "minLat", pan.minLat(),
                        "minLon", pan.minLon(),
                        "maxLat", pan.maxLat(),
                        "maxLon", pan.maxLon()
                ));
                out.put("origin", pan.origin());
                out.put("destination", pan.destination());
                out.put("arbitraryPointSelectionSupported", true);
                out.put("prototypeNote", "Dynamic On-Demand Pan-India Greenfield Engine (UTM Zones 42N-47N).");
                return out;
            }
            RegionalCorridorPreset preset = corridorRegistry.getPreset(normalized);
            StudyRegion sr = preset.studyRegion();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", preset.id());
            out.put("code", preset.code());
            out.put("corridorTierMode", CorridorTierMode.TIER_2_PRECACHED_BENCHMARK.name());
            out.put("name", sr.name());
            out.put("displayName", preset.displayName());
            out.put("utmZone", preset.utmZone());
            out.put("epsgCode", preset.epsgCode());
            out.put("bbox", Map.of(
                    "minLat", sr.minLat(),
                    "minLon", sr.minLon(),
                    "maxLat", sr.maxLat(),
                    "maxLon", sr.maxLon()
            ));
            out.put("origin", sr.origin());
            out.put("destination", sr.destination());
            out.put("arbitraryPointSelectionSupported", true);
            out.put("lazyLoadedInMemory", corridorRegistry.isRegionLoadedInMemory(preset.id()));
            out.put("prototypeNote", "A/B defaults and this highlighted corridor region allow any random start/end point selection.");
            return out;
        }

        Map<String, Object> defaultOut = new LinkedHashMap<>();
        defaultOut.put("id", "assam");
        defaultOut.put("corridorTierMode", CorridorTierMode.TIER_2_PRECACHED_BENCHMARK.name());
        defaultOut.put("name", this.region.name());
        defaultOut.put("bbox", Map.of(
                "minLat", this.region.minLat(),
                "minLon", this.region.minLon(),
                "maxLat", this.region.maxLat(),
                "maxLon", this.region.maxLon()
        ));
        defaultOut.put("origin", this.region.origin());
        defaultOut.put("destination", this.region.destination());
        defaultOut.put("arbitraryPointSelectionSupported", true);
        defaultOut.put("prototypeNote", "A/B and this window are a research prototype, not a construction proposal.");
        return defaultOut;
    }

    @GetMapping("/corridors")
    public Map<String, Object> listCorridors() {
        return corridorRegistry.describeTwoTierCatalog();
    }

    @PostMapping("/ingest")
    public CorridorIngestionService.IngestionResult ingestCorridor(
            @RequestBody CorridorIngestionService.IngestionRequest request) {
        return this.ingestionService.ingestCorridor(request);
    }
}
