package in.marg.routing;

import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.wildlife.IndianWildlifeSanctuaryRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProtectedAreaCostLayer implements CostLayer {

    private static final String SOURCE = "environment:statutory-protected-area";

    /**
     * Last-resort penalty multiplier for the statutory 10 km Eco-Sensitive Zone (ESZ) buffer.
     * 1 km inside the 10 km ESZ buffer is weighted equivalent to 5,000 km of baseline highway,
     * ensuring alignments completely bypass the 10 km ESZ whenever any alternative exists,
     * and only traverse the shortest possible exit path when an endpoint physically lies inside the ESZ.
     */
    public static final double ESZ_10KM_LAST_RESORT_PENALTY = 5000.0;

    private final ProtectedAreaRasterProvider provider;
    private final IndianWildlifeSanctuaryRegistry sanctuaryRegistry;

    public ProtectedAreaCostLayer(ProtectedAreaRasterProvider provider) {
        this(provider, IndianWildlifeSanctuaryRegistry.getDefaultInstance());
    }

    @Autowired
    public ProtectedAreaCostLayer(
            ProtectedAreaRasterProvider provider,
            IndianWildlifeSanctuaryRegistry sanctuaryRegistry) {
        this.provider = provider;
        this.sanctuaryRegistry = sanctuaryRegistry != null ? sanctuaryRegistry : IndianWildlifeSanctuaryRegistry.getDefaultInstance();
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        // 1. Authoritative Statutory Protected Areas Core Directory (WPA 1972 + OSM MultiPolygon) - Hard Block
        IndianWildlifeSanctuaryRegistry.StatutorySanctuary sanctuary =
                sanctuaryRegistry.findIntersectingSanctuary(point.lat(), point.lon());
        if (sanctuary != null) {
            return CostAssessment.blocked(
                    SOURCE,
                    "STATUTORY_WILDLIFE_SANCTUARY_CORE:" + sanctuary.id() + " (" + sanctuary.name() + ")"
            );
        }

        // 2. Pre-computed Protected Area & Sacred Heritage Precinct Exclusions
        if (provider != null) {
            Double maskValue = provider.sampleOrNull(point.lat(), point.lon());
            if (maskValue != null) {
                long code = Math.round(maskValue);
                if (code >= 1L) {
                    return CostAssessment.blocked(
                            SOURCE,
                            code == 2L ? "SACRED_HERITAGE_EXCLUSION_ZONE" : "STATUTORY_PROTECTED_AREA_RASTER"
                    );
                }
            }
        }

        // 3. Statutory 10 km Eco-Sensitive Zone (ESZ) Outward Buffer (Last-Resort Ultra-High Penalty)
        if (sanctuaryRegistry.isInsideAnyEszBuffer(point.lat(), point.lon())) {
            return new CostAssessment(false, ESZ_10KM_LAST_RESORT_PENALTY, "environment:esz-buffer", "ECO_SENSITIVE_ZONE_10KM_BUFFER");
        }

        return CostAssessment.free(SOURCE);
    }

    @Override
    public boolean hasEdgeCost() {
        return true;
    }

    @Override
    public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        // 1. Exact 2D Segment-to-Polygon Core Intersection Check
        IndianWildlifeSanctuaryRegistry.StatutorySanctuary segmentHit =
                sanctuaryRegistry.findIntersectingSanctuaryForSegment(from.lat(), from.lon(), to.lat(), to.lon());
        if (segmentHit != null) {
            return CostAssessment.blocked(
                    SOURCE,
                    "STATUTORY_WILDLIFE_SANCTUARY_CORE:" + segmentHit.id() + " (" + segmentHit.name() + ")"
            );
        }

        // 2. Sub-sample edge against raster exclusions (3 interior samples along 250m step)
        double[] fractions = {0.25, 0.50, 0.75};
        if (provider != null) {
            for (double t : fractions) {
                double lat = from.lat() + t * (to.lat() - from.lat());
                double lon = from.lon() + t * (to.lon() - from.lon());
                Double maskValue = provider.sampleOrNull(lat, lon);
                if (maskValue != null && Math.round(maskValue) >= 1L) {
                    return CostAssessment.blocked(
                            SOURCE,
                            Math.round(maskValue) == 2L ? "SACRED_HERITAGE_EXCLUSION_ZONE" : "STATUTORY_PROTECTED_AREA_RASTER"
                    );
                }
            }
        }

        return CostAssessment.FREE;
    }

    public IndianWildlifeSanctuaryRegistry sanctuaryRegistry() {
        return sanctuaryRegistry;
    }
}
