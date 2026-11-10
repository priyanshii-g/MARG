package in.marg.bhuvan;

import in.marg.model.GeoPoint;
import in.marg.routing.LulcClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BhuvanLulcSamplerTest {

    @Test
    void skipsFailedLegacyRecordsWhenLoadingPersistentCache(@TempDir Path tempDir) throws Exception {
        Path cacheFile = tempDir.resolve("bhuvan-lulc-samples.json");
        Files.writeString(cacheFile, """
                [
                  {
                    "lat": 26.585,
                    "lon": 93.405,
                    "type": "FOREST",
                    "rawFeatureInfo": "DESCR_1 = Forest\\nDESCR_2 = Deciduous\\n",
                    "remoteSuccess": true,
                    "error": null
                  },
                  {
                    "lat": 26.595,
                    "lon": 93.405,
                    "type": "UNKNOWN",
                    "rawFeatureInfo": "",
                    "remoteSuccess": false,
                    "error": "Bhuvan GetFeatureInfo failed"
                  }
                ]
                """);

        BhuvanCatalog catalog = new BhuvanCatalog();
        BhuvanFeatureInfoClient client = new BhuvanFeatureInfoClient(null, null);
        BhuvanLulcSampler sampler = new BhuvanLulcSampler(
                catalog, client, 0.01, 0.004, 1, cacheFile.toString(), new ObjectMapper()
        );

        sampler.loadPersistentCache();

        assertEquals(1, sampler.cacheSize());
        BhuvanLulcSampler.SampleResult cached = sampler.sampleDetailed(new GeoPoint(26.585, 93.405));
        assertTrue(cached.remoteSuccess());
        assertEquals(LulcClass.FOREST, cached.type());
    }

    @Test
    void failedRequestsAreCooldownCachedInMemoryButNotAddedToPersistentCache(@TempDir Path tempDir) {
        Path cacheFile = tempDir.resolve("bhuvan-lulc-samples.json");
        BhuvanCatalog catalog = new BhuvanCatalog();
        AtomicInteger callCount = new AtomicInteger();

        BhuvanFeatureInfoClient failingClient = new BhuvanFeatureInfoClient(null, null) {
            @Override
            public String query(BhuvanLayer layer, GeoPoint point, double halfSizeDeg) {
                callCount.incrementAndGet();
                throw new IllegalStateException("Simulated timeout");
            }
        };

        BhuvanLulcSampler sampler = new BhuvanLulcSampler(
                catalog, failingClient, 0.01, 0.004, 1, cacheFile.toString(), new ObjectMapper()
        );

        GeoPoint pt = new GeoPoint(26.6300, 93.5300);
        BhuvanLulcSampler.SampleResult first = sampler.sampleDetailed(pt);
        BhuvanLulcSampler.SampleResult second = sampler.sampleDetailed(pt);

        assertFalse(first.remoteSuccess());
        assertEquals(LulcClass.UNKNOWN, first.type());
        assertFalse(second.remoteSuccess());
        // Cooldown prevents immediate duplicate remote call
        assertEquals(1, callCount.get());
        // Failed sample must not enter the persistent cache map
        assertEquals(0, sampler.cacheSize());
    }

    @Test
    void repositoryCacheLoadsOnlySuccessfulSamples() {
        Path repoCache = Path.of("data/cache/bhuvan-lulc-samples.json");
        if (!Files.exists(repoCache)) {
            return;
        }
        BhuvanCatalog catalog = new BhuvanCatalog();
        BhuvanFeatureInfoClient client = new BhuvanFeatureInfoClient(null, null);
        BhuvanLulcSampler sampler = new BhuvanLulcSampler(
                catalog, client, 0.01, 0.004, 1, repoCache.toString(), new ObjectMapper()
        );

        sampler.loadPersistentCache();

        var stats = sampler.cacheStats();
        assertEquals(sampler.cacheSize(), ((Number) stats.get("remoteSuccesses")).intValue());
        assertEquals(2L, stats.get("unknownClassifications"));
    }
}
