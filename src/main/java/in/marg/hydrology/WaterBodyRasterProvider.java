package in.marg.hydrology;

import org.geotools.api.coverage.PointOutsideCoverageException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.Position2D;
import org.geotools.referencing.CRS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class WaterBodyRasterProvider {

    private final String rasterPath;

    private GridCoverage2D coverage;
    private MathTransform wgs84ToRaster;
    private CoordinateReferenceSystem wgs84;

    public WaterBodyRasterProvider(
            @Value("${marg.hydrology.raster-path:data/processed/hydrology/assam_water_bodies_utm46.tif}")
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
                    "Hydrology water-bodies raster does not exist: " + file.getAbsolutePath());
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
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to initialize hydrology water-bodies raster: " + rasterPath, e);
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
                        "Unexpected hydrology water-body code: " + value
                                + " at lat=" + latitude + ", lon=" + longitude);
            }

            return (double) code;
        } catch (PointOutsideCoverageException e) {
            return null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to sample hydrology water-bodies raster at lat="
                            + latitude + ", lon=" + longitude, e);
        }
    }

    public WaterBodyClass sampleClass(double latitude, double longitude) {
        return WaterBodyClass.fromRasterValue(sampleOrNull(latitude, longitude));
    }

    public String rasterPath() {
        return rasterPath;
    }
}
