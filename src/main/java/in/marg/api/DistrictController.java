package in.marg.api;

import in.marg.district.DistrictRecord;
import in.marg.district.IndianDistrictsCatalogService;
import in.marg.model.GeoPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/districts")
public class DistrictController {

    private final IndianDistrictsCatalogService catalogService;

    @Autowired
    public DistrictController(IndianDistrictsCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/search")
    public List<DistrictRecord> search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "15") int limit) {
        return catalogService.search(q, limit);
    }

    @GetMapping("/all")
    public List<DistrictRecord> all() {
        return catalogService.getAllDistricts();
    }

    @GetMapping("/nearest")
    public DistrictRecord nearest(
            @RequestParam double lat,
            @RequestParam double lon) {
        return catalogService.findNearest(new GeoPoint(lat, lon));
    }
}
