package in.marg.routing;

import in.marg.config.FloodRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.flood.FloodHazardClass;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.floodraster.FloodRasterSample;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.spatial.Wgs84UtmProjection;
import in.marg.terrain.SlopeRasterProvider;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.GeneralBounds;
import org.geotools.geometry.Position2D;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.awt.image.Raster;
import java.io.File;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerValidationPipelineTest {

    private final CoordinateProjection projection = new Wgs84UtmProjection(46);
    private final GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
    private final StudyRegion fullRegion = new StudyRegion(
            "Kaziranga–Karbi Anglong, Assam",
            25.75, 93.10, 26.65, 93.75,
            new GeoPoint(26.58970, 93.40035),
            new GeoPoint(25.84573, 93.43781)
    );

    @Test
    void step2FloodResolutionSensitivityAndValidation() throws Exception {
        BhuvanFloodRasterCache cache = new BhuvanFloodRasterCache(
                null,
                "data/cache/bhuvan-flood-wms",
                512,
                1000,
                28,
                1,
                new ObjectMapper()
        );
        Method loadMethod = BhuvanFloodRasterCache.class.getDeclaredMethod("load");
        loadMethod.setAccessible(true);
        loadMethod.invoke(cache);

        Map<String, Object> stats = cache.stats();
        System.out.println("=== STEP 2: FLOOD CACHE STATS ===");
        System.out.println(stats);
        assertEquals(0L, stats.get("unknownPixels"), "All cached flood rasters must have 0 unknown pixels");
        assertTrue(((Number) stats.get("cachedMosaics")).intValue() > 0);

        // Verify reference points
        FloodRasterSample s1 = cache.sample(new GeoPoint(26.6300, 93.5300));
        FloodRasterSample s2 = cache.sample(new GeoPoint(26.6100, 93.4500));
        FloodRasterSample s3 = cache.sample(new GeoPoint(26.5897, 93.40035));
        System.out.println("Sample (26.63, 93.53): " + s1.hazardClass() + " [" + s1.classificationStatus() + "] observed=" + s1.observedHazardClasses() + " raster=" + s1.rasterId());
        System.out.println("Sample (26.61, 93.45): " + s2.hazardClass() + " [" + s2.classificationStatus() + "] observed=" + s2.observedHazardClasses() + " raster=" + s2.rasterId());
        System.out.println("Sample (26.5897, 93.40035): " + s3.hazardClass() + " [" + s3.classificationStatus() + "] observed=" + s3.observedHazardClasses() + " raster=" + s3.rasterId());

        Map<FloodHazardClass, Double> penalties = new EnumMap<>(FloodHazardClass.class);
        penalties.put(FloodHazardClass.VERY_LOW, 0.0);
        penalties.put(FloodHazardClass.LOW, 0.20);
        penalties.put(FloodHazardClass.MODERATE, 0.60);
        penalties.put(FloodHazardClass.HIGH, 1.50);
        penalties.put(FloodHazardClass.VERY_HIGH, 3.00);
        penalties.put(FloodHazardClass.NOT_IN_HAZARD_LAYER, 0.0);
        penalties.put(FloodHazardClass.UNKNOWN, 2.00);

        FloodCostPolicy policy = new FloodCostPolicy(penalties);
        FloodRasterCostLayer floodLayer = new FloodRasterCostLayer(cache, policy);
        CostSurface floodSurface = new CompositeCostSurface(List.of(floodLayer));

        // Corridor 1: 26.61, 93.45 -> 26.63, 93.53
        runFloodCorridorComparison("Corridor 1 (26.61,93.45 -> 26.63,93.53)",
                new GeoPoint(26.61000, 93.45000),
                new GeoPoint(26.63000, 93.53000),
                cache, policy, floodSurface);

        // Corridor 2: 26.61, 93.45 -> 26.64, 93.55
        runFloodCorridorComparison("Corridor 2 (26.61,93.45 -> 26.64,93.55)",
                new GeoPoint(26.61000, 93.45000),
                new GeoPoint(26.64000, 93.55000),
                cache, policy, floodSurface);
    }

    private void runFloodCorridorComparison(
            String label,
            GeoPoint start,
            GeoPoint goal,
            BhuvanFloodRasterCache cache,
            FloodCostPolicy policy,
            CostSurface floodSurface) {

        System.out.println("\n=== FLOOD SENSITIVITY: " + label + " ===");
        StudyRegion window = corridorWindow(fullRegion, start, goal, 0.05);

        // Direct straight-line profile at 100 m sampling
        Map<String, Object> directProfile = evaluateFloodTransect(List.of(start, goal), 100.0, cache, policy);
        System.out.println("Direct Line (100m transect): " + directProfile);

        for (double res : new double[]{250.0, 100.0}) {
            long t0 = System.nanoTime();
            List<GeoPoint> distRoute = router.route(fullRegion, start, goal, new DistanceOnlyCostSurface(), res, window);
            double distKm = router.routeLengthKm(distRoute);
            Map<String, Object> distMetrics = evaluateFloodTransect(distRoute, 100.0, cache, policy);

            long t1 = System.nanoTime();
            List<GeoPoint> floodRoute = router.route(fullRegion, start, goal, floodSurface, res, window);
            long t2 = System.nanoTime();
            double floodKm = router.routeLengthKm(floodRoute);
            double devPct = ((floodKm / distKm) - 1.0) * 100.0;
            Map<String, Object> floodMetrics = evaluateFloodTransect(floodRoute, 100.0, cache, policy);

            System.out.printf("Resolution %.0fm | DistBaseline=%.2f km (vertices=%d) | FloodAware=%.2f km (dev=%.2f%%, vertices=%d, time=%.1f ms)%n",
                    res, distKm, distRoute.size(), floodKm, devPct, floodRoute.size(), (t2 - t1) / 1e6);
            System.out.println("  DistBaseline exposure (100m transect): " + distMetrics);
            System.out.println("  FloodAware exposure   (100m transect): " + floodMetrics);
        }
    }

    private Map<String, Object> evaluateFloodTransect(
            List<GeoPoint> route,
            double stepMeters,
            BhuvanFloodRasterCache cache,
            FloodCostPolicy policy) {

        EnumMap<FloodHazardClass, Double> kmByClass = new EnumMap<>(FloodHazardClass.class);
        double totalKm = 0.0;
        double penaltyKm = 0.0;
        double weightedCategorySum = 0.0;
        int samples = 0;

        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double segM = Math.hypot(dx, dy);
            if (segM <= 0) continue;
            int parts = Math.max(1, (int) Math.ceil(segM / stepMeters));
            double partKm = (segM / parts) / 1000.0;
            for (int j = 0; j < parts; j++) {
                double t = (j + 0.5) / parts;
                GeoPoint p = projection.inverse(new MetricPoint(a.x() + t * dx, a.y() + t * dy));
                FloodRasterSample s = cache.sample(p);
                FloodHazardClass c = s.hazardClass();
                kmByClass.merge(c, partKm, Double::sum);
                totalKm += partKm;
                penaltyKm += partKm * policy.penalty(c);
                weightedCategorySum += partKm * c.publishedCategoryWeight();
                samples++;
            }
        }
        Map<String, Double> rounded = new LinkedHashMap<>();
        for (var e : kmByClass.entrySet()) {
            rounded.put(e.getKey().name(), Math.round(e.getValue() * 100.0) / 100.0);
        }
        double whi = totalKm > 0 ? Math.round((weightedCategorySum / totalKm) * 100.0) / 100.0 : 0.0;
        return Map.of(
                "lengthKm", Math.round(totalKm * 100.0) / 100.0,
                "samples", samples,
                "exposureKm", rounded,
                "penaltyKmEq", Math.round(penaltyKm * 100.0) / 100.0,
                "weightedHazardIndex", whi
        );
    }

    @Test
    void step3And4SlopeAndProtectedAreaRasterValidation() throws Exception {
        // --- STEP 3: SLOPE RASTER METADATA & STATS ---
        File slopeFile = new File("data/processed/dem/assam_slope_utm46.tif");
        assertTrue(slopeFile.exists());
        GeoTiffReader slopeReader = new GeoTiffReader(slopeFile);
        GridCoverage2D slopeCov = slopeReader.read();
        slopeReader.dispose();

        CoordinateReferenceSystem slopeCrs = slopeCov.getCoordinateReferenceSystem2D();
        GridGeometry2D slopeGeom = slopeCov.getGridGeometry();
        GridEnvelope2D slopeGridEnv = slopeGeom.getGridRange2D();
        var slopeBounds = slopeCov.getEnvelope2D();

        double slopeResX = slopeBounds.getSpan(0) / slopeGridEnv.getWidth();
        double slopeResY = slopeBounds.getSpan(1) / slopeGridEnv.getHeight();

        MathTransform slopeToWgs84 = CRS.findMathTransform(slopeCrs, CRS.decode("EPSG:4326", true), true);
        Position2D slopeSwWgs = new Position2D();
        Position2D slopeNeWgs = new Position2D();
        slopeToWgs84.transform(new Position2D(slopeBounds.getMinimum(0), slopeBounds.getMinimum(1)), slopeSwWgs);
        slopeToWgs84.transform(new Position2D(slopeBounds.getMaximum(0), slopeBounds.getMaximum(1)), slopeNeWgs);

        Raster slopeRaster = slopeCov.getRenderedImage().getData();
        int sW = slopeRaster.getWidth();
        int sH = slopeRaster.getHeight();
        long sValid = 0, sNoData = 0;
        long sFree = 0, sLow = 0, sMod = 0, sSteep = 0, sVerySteep = 0, sOver30 = 0;
        double sMin = Double.POSITIVE_INFINITY, sMax = Double.NEGATIVE_INFINITY, sSum = 0.0;

        for (int y = 0; y < sH; y += 16) {
            for (int x = 0; x < sW; x += 16) {
                double v = slopeRaster.getSampleDouble(x, y, 0);
                if (v == -9999.0 || Double.isNaN(v) || Double.isInfinite(v) || v < 0.0) {
                    sNoData++;
                } else {
                    sValid++;
                    if (v < sMin) sMin = v;
                    if (v > sMax) sMax = v;
                    sSum += v;
                    if (v <= 5.0) sFree++;
                    else if (v <= 10.0) sLow++;
                    else if (v <= 15.0) sMod++;
                    else if (v <= 25.0) sSteep++;
                    else if (v <= 30.0) sVerySteep++;
                    else sOver30++;
                }
            }
        }

        System.out.println("\n=== STEP 3: SLOPE GEOTIFF METADATA & PIXEL STATS ===");
        System.out.println("CRS Name: " + slopeCrs.getName());
        System.out.printf("Grid Size: %d x %d pixels (resX=%.2f m, resY=%.2f m)%n", sW, sH, slopeResX, slopeResY);
        System.out.printf("UTM46N Bounds: X=[%.1f, %.1f], Y=[%.1f, %.1f]%n",
                slopeBounds.getMinimum(0), slopeBounds.getMaximum(0), slopeBounds.getMinimum(1), slopeBounds.getMaximum(1));
        System.out.printf("WGS84 Bounds: Lon=[%.5f, %.5f], Lat=[%.5f, %.5f]%n",
                slopeSwWgs.x, slopeNeWgs.x, slopeSwWgs.y, slopeNeWgs.y);
        System.out.printf("Pixels: valid=%d, noData=%d | min=%.3f deg, max=%.3f deg, mean=%.3f deg%n",
                sValid, sNoData, sMin, sMax, sSum / sValid);
        System.out.printf("Slope Band Pixel Counts: FREE(<=5)=%d (%.1f%%), LOW(5-10)=%d (%.1f%%), MOD(10-15)=%d (%.1f%%), STEEP(15-25)=%d (%.1f%%), VERY_STEEP(25-30)=%d (%.1f%%), OVER_30(>30)=%d (%.2f%%)%n",
                sFree, sFree * 100.0 / sValid,
                sLow, sLow * 100.0 / sValid,
                sMod, sMod * 100.0 / sValid,
                sSteep, sSteep * 100.0 / sValid,
                sVerySteep, sVerySteep * 100.0 / sValid,
                sOver30, sOver30 * 100.0 / sValid);

        // --- STEP 4: PROTECTED AREA RASTER METADATA & STATS ---
        File paFile = new File("data/processed/protected-areas/assam_protected_area_mask_utm46.tif");
        assertTrue(paFile.exists());
        GeoTiffReader paReader = new GeoTiffReader(paFile);
        GridCoverage2D paCov = paReader.read();
        paReader.dispose();

        CoordinateReferenceSystem paCrs = paCov.getCoordinateReferenceSystem2D();
        GridGeometry2D paGeom = paCov.getGridGeometry();
        GridEnvelope2D paGridEnv = paGeom.getGridRange2D();
        var paBounds = paCov.getEnvelope2D();

        double paResX = paBounds.getSpan(0) / paGridEnv.getWidth();
        double paResY = paBounds.getSpan(1) / paGridEnv.getHeight();

        MathTransform paToWgs84 = CRS.findMathTransform(paCrs, CRS.decode("EPSG:4326", true), true);
        Position2D paSwWgs = new Position2D();
        Position2D paNeWgs = new Position2D();
        paToWgs84.transform(new Position2D(paBounds.getMinimum(0), paBounds.getMinimum(1)), paSwWgs);
        paToWgs84.transform(new Position2D(paBounds.getMaximum(0), paBounds.getMaximum(1)), paNeWgs);

        Raster paRaster = paCov.getRenderedImage().getData();
        int pW = paRaster.getWidth();
        int pH = paRaster.getHeight();
        long pZero = 0, pOne = 0, pNoData255 = 0, pOther = 0;

        for (int y = 0; y < pH; y += 16) {
            for (int x = 0; x < pW; x += 16) {
                double v = paRaster.getSampleDouble(x, y, 0);
                if (v == 0.0) pZero++;
                else if (v == 1.0) pOne++;
                else if (v == 255.0 || Double.isNaN(v)) pNoData255++;
                else pOther++;
            }
        }
        double pixelAreaKm2 = (paResX * paResY) / 1_000_000.0;

        System.out.println("\n=== STEP 4: PROTECTED AREA GEOTIFF METADATA & PIXEL STATS ===");
        System.out.println("CRS Name: " + paCrs.getName());
        System.out.printf("Grid Size: %d x %d pixels (resX=%.2f m, resY=%.2f m)%n", pW, pH, paResX, paResY);
        System.out.printf("UTM46N Bounds: X=[%.1f, %.1f], Y=[%.1f, %.1f]%n",
                paBounds.getMinimum(0), paBounds.getMaximum(0), paBounds.getMinimum(1), paBounds.getMaximum(1));
        System.out.printf("WGS84 Bounds: Lon=[%.5f, %.5f], Lat=[%.5f, %.5f]%n",
                paSwWgs.x, paNeWgs.x, paSwWgs.y, paNeWgs.y);
        System.out.printf("Mask Counts: allowed(0)=%d (%.1f km2), protected(1)=%d (%.1f km2), noData(255)=%d (%.1f km2), unexpected=%d%n",
                pZero, pZero * pixelAreaKm2, pOne, pOne * pixelAreaKm2, pNoData255, pNoData255 * pixelAreaKm2, pOther);
        assertEquals(0L, pOther, "Protected area raster must contain only 0, 1, or 255");

        // --- ROUTING ISOLATION TESTS FOR SLOPE & PROTECTED AREA ---
        SlopeRasterProvider slopeProvider = new SlopeRasterProvider("data/processed/dem/assam_slope_utm46.tif");
        SlopeRoutingProperties slopeProps = new SlopeRoutingProperties();
        SlopeCostLayer slopeLayer = new SlopeCostLayer(slopeProvider, slopeProps);

        ProtectedAreaRasterProvider paProvider = new ProtectedAreaRasterProvider("data/processed/protected-areas/assam_protected_area_mask_utm46.tif");
        ProtectedAreaCostLayer paLayer = new ProtectedAreaCostLayer(paProvider);

        System.out.println("\n=== POINT SAMPLES (SLOPE & PROTECTED AREA) ===");
        double[][] testPts = {
                {26.58970, 93.40035}, // Kohora (Origin)
                {25.84573, 93.43781}, // Diphu (Destination)
                {26.62000, 93.38000}, // Inside Kaziranga NP
                {26.35000, 93.42000}, // Karbi Anglong hills
                {26.15000, 93.42000}  // Central corridor
        };
        for (double[] pt : testPts) {
            GeoPoint gp = new GeoPoint(pt[0], pt[1]);
            Double slp = slopeProvider.sampleOrNull(pt[0], pt[1]);
            Double pa = paProvider.sampleOrNull(pt[0], pt[1]);
            CostAssessment slpA = slopeLayer.assess(gp);
            CostAssessment paA = paLayer.assess(gp);
            System.out.printf("Point (%.5f, %.5f): slope=%.2f deg [%s, pen=%.2f, blocked=%s] | PA_mask=%s [%s, blocked=%s]%n",
                    pt[0], pt[1], slp, slpA.className(), slpA.penaltyMultiplier(), slpA.blocked(),
                    pa, paA.className(), paA.blocked());
        }

        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);
        List<GeoPoint> baseline = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                new DistanceOnlyCostSurface(), 250.0, corridor);
        List<GeoPoint> slopeOnly = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(slopeLayer)), 250.0, corridor);
        List<GeoPoint> paOnly = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(paLayer)), 250.0, corridor);
        List<GeoPoint> slopeAndPa = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(slopeLayer, paLayer)), 250.0, corridor);

        System.out.println("\n=== ISOLATED ROUTING IMPACT (KOHORA -> DIPHU, 250m GRID) ===");
        printRouteLayerSummary("DISTANCE_BASELINE", baseline, slopeProvider, paProvider);
        printRouteLayerSummary("SLOPE_ONLY", slopeOnly, slopeProvider, paProvider);
        printRouteLayerSummary("PROTECTED_AREA_ONLY", paOnly, slopeProvider, paProvider);
        printRouteLayerSummary("SLOPE_AND_PA", slopeAndPa, slopeProvider, paProvider);

        assertNotNull(slopeCov);
        assertNotNull(paCov);
    }

    private void printRouteLayerSummary(
            String name,
            List<GeoPoint> route,
            SlopeRasterProvider slopeProvider,
            ProtectedAreaRasterProvider paProvider) {

        double km = router.routeLengthKm(route);
        int vertexViolationsPa = 0;
        int vertexViolationsSlope = 0;
        for (GeoPoint v : route) {
            Double pa = paProvider.sampleOrNull(v.lat(), v.lon());
            if (pa == null || Math.round(pa) == 1L) vertexViolationsPa++;
            Double slp = slopeProvider.sampleOrNull(v.lat(), v.lon());
            if (slp == null || slp > 30.0) vertexViolationsSlope++;
        }

        // Also do 125m transect exposure
        double paKm = 0.0, over30Km = 0.0, steepKm = 0.0;
        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            double segM = Math.hypot(b.x() - a.x(), b.y() - a.y());
            if (segM <= 0) continue;
            int parts = Math.max(1, (int) Math.ceil(segM / 125.0));
            double partKm = (segM / parts) / 1000.0;
            for (int j = 0; j < parts; j++) {
                double t = (j + 0.5) / parts;
                GeoPoint p = projection.inverse(new MetricPoint(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y())));
                Double pa = paProvider.sampleOrNull(p.lat(), p.lon());
                if (pa != null && Math.round(pa) == 1L) paKm += partKm;
                Double slp = slopeProvider.sampleOrNull(p.lat(), p.lon());
                if (slp != null && slp > 30.0) over30Km += partKm;
                else if (slp != null && slp > 15.0) steepKm += partKm;
            }
        }
        System.out.printf("%-20s: length=%.2f km (vertices=%d) | VertexViolations(PA=%d, Slope>30=%d) | 125mTransect(PA=%.2f km, Slope>30=%.2f km, Slope15-30=%.2f km)%n",
                name, km, route.size(), vertexViolationsPa, vertexViolationsSlope, paKm, over30Km, steepKm);
    }

    private static StudyRegion corridorWindow(StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / validation window", minLat, minLon, maxLat, maxLon, a, b);
    }
}
