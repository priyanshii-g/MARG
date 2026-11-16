package in.marg.routing;

import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.model.GeoPoint;
import org.springframework.stereotype.Component;

@Component
public class ProtectedAreaCostLayer
        implements CostLayer {

    private final ProtectedAreaRasterProvider provider;

    public ProtectedAreaCostLayer(
            ProtectedAreaRasterProvider provider) {

        this.provider = provider;
    }

    @Override
    public CostAssessment assess(
            GeoPoint point) {

        Double maskValue =
                provider.sampleOrNull(
                        point.lat(),
                        point.lon());

        if (maskValue == null) {

            return CostAssessment.blocked(
                    "environment:protected-area",
                    "PROTECTED_AREA_NODATA");
        }

        long code = Math.round(maskValue);
        if (code == 2L) {

            return CostAssessment.blocked(
                    "environment:protected-area",
                    "SACRED_HERITAGE_EXCLUSION_ZONE");
        }

        if (code >= 1L) {

            return CostAssessment.blocked(
                    "environment:protected-area",
                    "PROTECTED_AREA");
        }

        return CostAssessment.free(
                "environment:protected-area");
    }

    @Override
    public boolean hasEdgeCost() {
        return true;
    }

    @Override
    public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        double[] fractions = {0.2, 0.4, 0.6, 0.8};
        for (double t : fractions) {
            double lat = from.lat() + t * (to.lat() - from.lat());
            double lon = from.lon() + t * (to.lon() - from.lon());
            Double maskValue = provider.sampleOrNull(lat, lon);
            if (maskValue != null && Math.round(maskValue) == 2L) {
                return CostAssessment.blocked(
                        "environment:protected-area",
                        "SACRED_HERITAGE_EXCLUSION_ZONE");
            }
        }
        return CostAssessment.FREE;
    }
}