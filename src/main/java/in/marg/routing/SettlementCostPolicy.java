package in.marg.routing;

import in.marg.settlement.SettlementExposureClass;

import java.util.EnumMap;
import java.util.Map;

/** Configurable MARG settlement and built-up proximity routing policy. */
public class SettlementCostPolicy {
    private final Map<SettlementExposureClass, Double> penalties;
    private final boolean blockUrbanCore;
    private final boolean blockNoData;

    public SettlementCostPolicy(
            Map<SettlementExposureClass, Double> penalties,
            boolean blockUrbanCore,
            boolean blockNoData) {
        this.penalties = new EnumMap<>(SettlementExposureClass.class);
        if (penalties != null) {
            this.penalties.putAll(penalties);
        }
        this.blockUrbanCore = blockUrbanCore;
        this.blockNoData = blockNoData;
    }

    public double penalty(SettlementExposureClass exposureClass) {
        return penalties.getOrDefault(exposureClass, 0.0);
    }

    public boolean blockUrbanCore() {
        return blockUrbanCore;
    }

    public boolean blockNoData() {
        return blockNoData;
    }

    public Map<SettlementExposureClass, Double> penalties() {
        return Map.copyOf(penalties);
    }
}
