package in.marg.terrain;

import jakarta.annotation.PostConstruct;
import org.geotools.api.geometry.Position;
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
import org.geotools.api.coverage.PointOutsideCoverageException;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.File;

@Component
public class SlopeRasterProvider {

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

    public record SegmentSlopeSummary(
            int totalSamples,
            int validSamples,
            int noDataSamples,
            int overLimitSamples,
            double maxSlopeDegrees) {
        public static final SegmentSlopeSummary EMPTY =
                new SegmentSlopeSummary(0, 0, 0, 0, 0.0);
    }

    public SlopeRasterProvider(
            @Value("${marg.terrain.slope-path:data/processed/dem/assam_slope_utm46.tif}")
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
                    "Slope raster not found: " + file.getAbsolutePath());
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
                        "GeoTIFF reader returned no coverage: "
                                + file.getAbsolutePath());
            }

            CoordinateReferenceSystem rasterCrs =
                    coverage.getCoordinateReferenceSystem2D();

            wgs84 = CRS.decode("EPSG:4326", true);

            wgs84ToRaster = CRS.findMathTransform(
                    wgs84,
                    rasterCrs,
                    true);

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
                    "Failed to load slope raster: "
                            + file.getAbsolutePath(),
                    e);
        }
    }

    public double sample(
            double latitude,
            double longitude) {

        Double value = sampleOrNull(latitude, longitude);

        if (value == null) {
            throw new IllegalStateException(
                    "NoData slope value at "
                            + latitude + ", " + longitude);
        }

        return value;
    }

    public Double sampleOrNull(
            double latitude,
            double longitude) {

        ensureInitialized();

        try {
            Position2D source =
                    new Position2D(
                            wgs84,
                            longitude,
                            latitude);

            Position2D target =
                    new Position2D();

            wgs84ToRaster.transform(
                    source,
                    target);

            Position position = target;

            double[] values =
                    coverage.evaluate(
                            position,
                            new double[1]);

            if (values == null
                    || values.length == 0) {
                return null;
            }

            double slopeDegrees = values[0];

            if (slopeDegrees == -9999.0
                    || Double.isNaN(slopeDegrees)
                    || Double.isInfinite(slopeDegrees)) {
                return null;
            }

            if (slopeDegrees < 0.0
                    || slopeDegrees > 90.0) {
                throw new IllegalStateException(
                        "Invalid slope value "
                                + slopeDegrees
                                + " at "
                                + latitude
                                + ", "
                                + longitude);
            }

            return slopeDegrees;

        } catch (PointOutsideCoverageException e) {
            return null;
        } catch (IllegalStateException e) {
            throw e;

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to sample slope at "
                            + latitude
                            + ", "
                            + longitude,
                    e);
        }
    }

    /**
     * Sub-samples the interior of a geographic segment directly in raster pixel coordinates at
     * sub-grid spacing (~30 m, matching the DEM resolution and aligned with 125 m diagnostic midpoints).
     * Endpoints are excluded because A* already evaluates grid vertices via {@link #sampleOrNull}.
     */
    public SegmentSlopeSummary inspectSegmentInterior(
            double lat1,
            double lon1,
            double lat2,
            double lon2,
            double limitDegrees) {

        ensureInitialized();

        try {
            double[] wgsPts = {lon1, lat1, lon2, lat2};
            double[] rasterPts = new double[4];
            wgs84ToRaster.transform(wgsPts, 0, rasterPts, 0, 2);

            double dx = rasterPts[2] - rasterPts[0];
            double dy = rasterPts[3] - rasterPts[1];
            double distMeters = Math.hypot(dx, dy);
            if (!Double.isFinite(distMeters) || distMeters <= 1e-3) {
                return SegmentSlopeSummary.EMPTY;
            }

            double[] gridPts = new double[4];
            crsToGrid.transform(rasterPts, 0, gridPts, 0, 2);
            double gx1 = gridPts[0];
            double gy1 = gridPts[1];
            double dgx = gridPts[2] - gx1;
            double dgy = gridPts[3] - gy1;

            // Choose a multiple-of-4 subdivision at <= ~31.5 m spacing so:
            //  - 250 m cardinal step -> 8 intervals (31.25 m), hitting exact 0.25/0.50/0.75 midpoints
            //  - 353.6 m diagonal step -> 12 intervals (29.5 m), hitting exact 1/6, 1/4, 1/2, 3/4, 5/6
            double targetSpacing = Math.max(10.0, Math.min(31.5, pixelSizeMeters));
            int raw = Math.max(4, (int) Math.ceil(distMeters / targetSpacing));
            int segments = Math.min(2048, ((raw + 3) / 4) * 4);

            int total = 0;
            int valid = 0;
            int noData = 0;
            int overLimit = 0;
            double maxSlope = 0.0;

            for (int i = 1; i < segments; i++) {
                double t = (double) i / segments;
                int col = (int) Math.floor(gx1 + t * dgx + 0.5);
                int row = (int) Math.floor(gy1 + t * dgy + 0.5);
                total++;

                if (col < minCol || col > maxCol || row < minRow || row > maxRow) {
                    noData++;
                    continue;
                }

                double slopeDegrees = rasterData.getSampleDouble(col, row, 0);
                if (slopeDegrees == -9999.0
                        || Double.isNaN(slopeDegrees)
                        || Double.isInfinite(slopeDegrees)
                        || slopeDegrees < 0.0
                        || slopeDegrees > 90.0) {
                    noData++;
                    continue;
                }

                valid++;
                if (slopeDegrees > maxSlope) {
                    maxSlope = slopeDegrees;
                }
                if (slopeDegrees > limitDegrees) {
                    overLimit++;
                }
            }

            return new SegmentSlopeSummary(total, valid, noData, overLimit, maxSlope);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to inspect slope along segment ("
                            + lat1 + ", " + lon1 + ") -> (" + lat2 + ", " + lon2 + ")",
                    e);
        }
    }

    public String rasterPath() {
        return rasterPath;
    }
}