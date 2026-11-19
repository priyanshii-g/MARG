package in.marg.api;

import in.marg.sync.GovDataAutoSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Controller exposing status and controls for periodic and on-demand synchronization
 * of official government spatial datasets (Protected Areas, Highways, Drainage, Settlements).
 */
@RestController
@RequestMapping("/api/sync")
public class GovDataSyncController {

    private final GovDataAutoSyncService syncService;

    @Autowired
    public GovDataSyncController(GovDataAutoSyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/status")
    public GovDataAutoSyncService.GovSyncSummary getStatus() {
        return syncService.getSyncStatus();
    }

    @PostMapping("/check-updates")
    public GovDataAutoSyncService.GovSyncSummary triggerSyncCheck(
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        return syncService.checkAndSyncAll(force);
    }
}
