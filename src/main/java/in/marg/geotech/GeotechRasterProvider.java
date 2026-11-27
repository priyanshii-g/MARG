package in.marg.geotech;

import org.geotools.api.coverage.PointOutsideCoverageException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.MathTransform2D;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.Position2D;
import org.geotools.referencing.CRS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.File;

@Component
public class GeotechRasterProvider {

    private final String rasterPath;

    private GridCoverage2D coverage;
    private MathTransform wgs84ToRaster;
    private MathTransform2D crsToGrid;
    private CoordinateReferenceSystem wgs84;
    private Raster rasterData;
    private int minCol;
    private int maxCol;
    private int minRow;
    private int maxRow;
    private double pixelSizeMeters = 30.0;

    public record SegmentGeotechSummary(
            int totalSamples,
            int activeShearCoreSamples,
            int highRiskScarpSamples,
            int noDataSamples) {
        public static final SegmentGeotechSummary EMPTY =
                new SegmentGeotechSummary(0, 0, 0, 0);
    }

    public GeotechRasterProvider(
            @Value("${marg.geotech.raster-path:data/processed/geotech/assam_geotech_landslide_utm46.tif}")
            String rasterPath) {
        this.rasterPath = rasterPath;
    }

    private synchronized void ensureInitialized() {
        if (coverage != null && wgs84ToRaster != null) {
            return;
        }

        File file = new File(rasterPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Geotechnical & landslide raster does not exist: " + file.getAbsolutePath());
        }

        try {
            GeoTiffReader reader = new GeoTiffReader(file);
            try {
                coverage = reader.read();
            } finally {
                reader.dispose();
            }

            if (coverage == null) {
                throw new IllegalStateException(
                        "GeoTIFF reader returned no coverage: " + file.getAbsolutePath());
            }

            wgs84 = CRS.decode("EPSG:4326", true);
            CoordinateReferenceSystem rasterCrs = coverage.getCoordinateReferenceSystem2D();
            wgs84ToRaster = CRS.findMathTransform(wgs84, rasterCrs, true);

            GridGeometry2D gridGeometry = coverage.getGridGeometry();
            crsToGrid = gridGeometry.getCRSToGrid2D();

            RenderedImage renderedImage = coverage.getRenderedImage();
            rasterData = renderedImage instanceof BufferedImage bi
                    ? bi.getRaster()
                    : renderedImage.getData();
            minCol = rasterData.getMinX();
            minRow = rasterData.getMinY();
            maxCol = minCol + rasterData.getWidth() - 1;
            maxRow = minRow + rasterData.getHeight() - 1;

            GridEnvelope2D gridRange = gridGeometry.getGridRange2D();
            var envelope = coverage.getEnvelope2D();
            if (gridRange != null && envelope != null
                    && gridRange.getWidth() > 0 && gridRange.getHeight() > 0) {
                double resX = envelope.getSpan(0) / gridRange.getWidth();
                double resY = envelope.getSpan(1) / gridRange.getHeight();
                double minRes = Math.min(Math.abs(resX), Math.abs(resY));
                if (Double.isFinite(minRes) && minRes >= 5.0 && minRes <= 250.0) {
                    pixelSizeMeters = minRes;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to initialize geotechnical & landslide raster: " + rasterPath, e);
        }
    }

    public Double sampleOrNull(double latitude, double longitude) {
        ensureInitialized();

        try {
            Position2D source = new Position2D(wgs84, longitude, latitude);
            var target = wgs84ToRaster.transform(source, null);
            double[] values = coverage.evaluate(target, new double[1]);

            if (values == null || values.length == 0) {
                return null;
            }

            double value = values[0];
            if (Double.isNaN(value) || Double.isInfinite(value) || value == 255.0) {
                return null;
            }

            int code = (int) Math.round(value);
            if (code < 0 || code > 6) {
                throw new IllegalStateException(
                        "Unexpected geotechnical & landslide raster code: " + value
                                + " at lat=" + latitude + ", lon=" + longitude);
            }

            return (double) code;
        } catch (PointOutsideCoverageException e) {
            return null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to sample geotechnical & landslide raster at lat="
                            + latitude + ", lon=" + longitude, e);
        }
    }

    public GeotechHazardClass sampleClass(double latitude, double longitude) {
        return GeotechHazardClass.fromRasterValue(sampleOrNull(latitude, longitude));
    }

    /**
     * Sub-samples the interior of a geographic segment directly in raster pixel coordinates (~30 m
     * spacing aligned with the DEM/geotech raster and 125 m diagnostic midpoints) so narrow
     * active tectonic landslide shear cores cannot be stepped over on a 250 m routing grid.
     */
    public SegmentGeotechSummary inspectSegmentInterior(
            double lat1,
            double lon1,
            double lat2,
            double lon2) {

        ensureInitialized();

        try {
            double[] wgsPts = {lon1, lat1, lon2, lat2};
            double[] rasterPts = new double[4];
            wgs84ToRaster.transform(wgsPts, 0, rasterPts, 0, 2);

            double dx = rasterPts[2] - rasterPts[0];
            double dy = rasterPts[3] - rasterPts[1];
            double distMeters = Math.hypot(dx, dy);
            if (!Double.isFinite(distMeters) || distMeters <= 1e-3) {
                return SegmentGeotechSummary.EMPTY;
            }

            double[] gridPts = new double[4];
            crsToGrid.transform(rasterPts, 0, gridPts, 0, 2);
            double gx1 = gridPts[0];
            double gy1 = gridPts[1];
            double dgx = gridPts[2] - gx1;
            double dgy = gridPts[3] - gy1;

            double targetSpacing = Math.max(15.0, Math.min(30.0, pixelSizeMeters));
            int raw = Math.max(4, (int) Math.ceil(distMeters / targetSpacing));
            int segments = Math.min(2048, ((raw + 3) / 4) * 4);

            int total = 0;
            int activeCore = 0;
            int highScarp = 0;
            int noData = 0;

            for (int i = 0; i <= segments; i++) {
                double t = (double) i / segments;
                int col = (int) Math.floor(gx1 + t * dgx + 0.5);
                int row = (int) Math.floor(gy1 + t * dgy + 0.5);
                total++;

                if (col < minCol || col > maxCol || row < minRow || row > maxRow) {
                    noData++;
                    continue;
                }

                double rawVal = rasterData.getSampleDouble(col, row, 0);
                if (Double.isNaN(rawVal) || Double.isInfinite(rawVal) || rawVal == 255.0) {
                    noData++;
                    continue;
                }

                int code = (int) Math.round(rawVal);
                if (code == GeotechHazardClass.ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE.code()) {
                    activeCore++;
                } else if (code == GeotechHazardClass.HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP.code()) {
                    highScarp++;
                }
            }

            return new SegmentGeotechSummary(total, activeCore, highScarp, noData);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to inspect geotechnical hazard along segment ("
                            + lat1 + ", " + lon1 + ") -> (" + lat2 + ", " + lon2 + ")",
                    e);
        }
    }

    public String rasterPath() {
        return rasterPath;
    }
}
