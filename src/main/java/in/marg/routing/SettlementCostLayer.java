package in.marg.routing;

import in.marg.config.SettlementRoutingProperties;
import in.marg.model.GeoPoint;
import in.marg.settlement.SettlementExposureClass;
import in.marg.settlement.SettlementRasterProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SettlementCostLayer implements CostLayer {

    private static final String SOURCE = "social:settlement-exposure";

    private final SettlementRasterProvider provider;
    private final SettlementRoutingProperties properties;
    private final boolean forceUrbanCoreSoftBarrier;

    @Autowired
    public SettlementCostLayer(
            SettlementRasterProvider provider,
            SettlementRoutingProperties properties) {
        this(provider, properties, false);
    }

    private SettlementCostLayer(
            SettlementRasterProvider provider,
            SettlementRoutingProperties properties,
            boolean forceUrbanCoreSoftBarrier) {
        this.provider = provider;
        this.properties = properties;
        this.forceUrbanCoreSoftBarrier = forceUrbanCoreSoftBarrier;
    }

    public SettlementCostLayer withUrbanCoreLastResortMode() {
        return new SettlementCostLayer(provider, properties, true);
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        SettlementCostPolicy policy = properties.toPolicy();
        SettlementExposureClass exposure = provider.sampleClass(point.lat(), point.lon());

        if (exposure == SettlementExposureClass.NODATA) {
            if (policy.blockNoData()) {
                return CostAssessment.blocked(SOURCE, "SETTLEMENT_NODATA");
            }
            return new CostAssessment(false, policy.penalty(SettlementExposureClass.NODATA), SOURCE, "SETTLEMENT_NODATA");
        }

        if (exposure == SettlementExposureClass.URBAN_CORE && policy.blockUrbanCore() && !forceUrbanCoreSoftBarrier) {
            return CostAssessment.blocked(SOURCE, "SETTLEMENT_URBAN_CORE");
        }

        if (exposure == SettlementExposureClass.NONE) {
            return CostAssessment.free(SOURCE);
        }

        double pen = policy.penalty(exposure);
        if (exposure == SettlementExposureClass.URBAN_CORE && forceUrbanCoreSoftBarrier) {
            pen = Math.max(pen, BuildingCostPolicy.DEFAULT_DENSE_COLONY_LAST_RESORT_PENALTY);
        }

        return new CostAssessment(
                false,
                pen,
                SOURCE,
                "SETTLEMENT_" + exposure.name());
    }
}
