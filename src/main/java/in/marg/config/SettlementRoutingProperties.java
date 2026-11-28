package in.marg.config;

import in.marg.routing.SettlementCostPolicy;
import in.marg.settlement.SettlementExposureClass;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "marg.routing.settlement")
public class SettlementRoutingProperties {
    private Map<String, Double> penalties = new LinkedHashMap<>();
    private boolean blockUrbanCore = true;
    private boolean blockNoData = false;

    public SettlementRoutingProperties() {
        penalties.put("NONE", 0.0);
        penalties.put("OUTER_BUFFER", 0.50);
        penalties.put("INNER_BUFFER", 2.20);
        penalties.put("RURAL_CORE", 10.00);
        penalties.put("URBAN_CORE", 1000.00);
        penalties.put("NODATA", 0.0);
    }

    public Map<String, Double> getPenalties() {
        return penalties;
    }

    public void setPenalties(Map<String, Double> penalties) {
        this.penalties = penalties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(penalties);
    }

    public boolean isBlockUrbanCore() {
        return blockUrbanCore;
    }

    public void setBlockUrbanCore(boolean blockUrbanCore) {
        this.blockUrbanCore = blockUrbanCore;
    }

    public boolean isBlockNoData() {
        return blockNoData;
    }

    public void setBlockNoData(boolean blockNoData) {
        this.blockNoData = blockNoData;
    }

    public SettlementCostPolicy toPolicy() {
        Map<SettlementExposureClass, Double> values = new EnumMap<>(SettlementExposureClass.class);
        for (Map.Entry<String, Double> entry : penalties.entrySet()) {
            final SettlementExposureClass type;
            try {
                type = SettlementExposureClass.valueOf(entry.getKey().trim().toUpperCase());
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(
                        "Unknown settlement exposure class in configuration: " + entry.getKey(), ex);
            }
            double value = entry.getValue() == null ? 0.0 : entry.getValue();
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException(
                        "Settlement penalty must be finite and >= 0 for " + entry.getKey());
            }
            values.put(type, value);
        }
        return new SettlementCostPolicy(values, blockUrbanCore, blockNoData);
    }
}
