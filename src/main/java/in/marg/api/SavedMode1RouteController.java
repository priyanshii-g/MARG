package in.marg.api;

import in.marg.persistence.SavedMode1Route;
import in.marg.persistence.SavedMode1RouteService;
import in.marg.persistence.SavedMode1RouteService.SaveRouteRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/routes/saved")
public class SavedMode1RouteController {
    private final SavedMode1RouteService routes;

    public SavedMode1RouteController(SavedMode1RouteService routes) { this.routes = routes; }

    @GetMapping("/health")
    public Map<String, Object> health() { return routes.health(); }

    @PostMapping
    public SavedMode1Route save(@RequestBody SaveRouteRequest request) {
        try { return routes.save(request); }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping
    public List<SavedMode1Route> list() { return routes.list(); }

    @GetMapping("/{routeId}")
    public SavedMode1Route get(@PathVariable String routeId) {
        try { return routes.find(routeId); }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}
