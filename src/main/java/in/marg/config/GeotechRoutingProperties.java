package in.marg.config;

import in.marg.geotech.GeotechHazardClass;
import in.marg.geotech.GeotechHazardTier;
import in.marg.routing.GeotechCostPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "marg.routing.geotech")
public class GeotechRoutingProperties {

    private boolean blockActiveLandslideZones = false;
    private boolean blockNoData = false;
    private double scarpSkewPenaltyFactor = 1.40;
    private double parallelScarpUndercutPenalty = 2.50;
    private double parallelScarpRadiusMeters = 260.0;

    private Map<GeotechHazardClass, Double> nodePenalties =
            new EnumMap<>(GeotechCostPolicy.defaultPolicy().nodePenalties());

    private Map<GeotechHazardTier, Double> mitigationBaseCostKmEq =
            new EnumMap<>(GeotechCostPolicy.defaultPolicy().mitigationBaseCostKmEq());

    public GeotechCostPolicy toPolicy() {
        Map<GeotechHazardClass, Double> mergedNodes =
                new EnumMap<>(GeotechCostPolicy.defaultPolicy().nodePenalties());
        mergedNodes.putAll(nodePenalties);

        Map<GeotechHazardTier, Double> mergedMitigation =
                new EnumMap<>(GeotechCostPolicy.defaultPolicy().mitigationBaseCostKmEq());
        mergedMitigation.putAll(mitigationBaseCostKmEq);

        return new GeotechCostPolicy(
                mergedNodes,
                mergedMitigation,
                scarpSkewPenaltyFactor,
                parallelScarpUndercutPenalty,
                parallelScarpRadiusMeters,
                blockActiveLandslideZones,
                blockNoData);
    }

    public boolean isBlockActiveLandslideZones() {
        return blockActiveLandslideZones;
    }

    public void setBlockActiveLandslideZones(boolean blockActiveLandslideZones) {
        this.blockActiveLandslideZones = blockActiveLandslideZones;
    }

    public boolean isBlockNoData() {
        return blockNoData;
    }

    public void setBlockNoData(boolean blockNoData) {
        this.blockNoData = blockNoData;
    }

    public double getScarpSkewPenaltyFactor() {
        return scarpSkewPenaltyFactor;
    }

    public void setScarpSkewPenaltyFactor(double scarpSkewPenaltyFactor) {
        this.scarpSkewPenaltyFactor = scarpSkewPenaltyFactor;
    }

    public double getParallelScarpUndercutPenalty() {
        return parallelScarpUndercutPenalty;
    }

    public void setParallelScarpUndercutPenalty(double parallelScarpUndercutPenalty) {
        this.parallelScarpUndercutPenalty = parallelScarpUndercutPenalty;
    }

    public double getParallelScarpRadiusMeters() {
        return parallelScarpRadiusMeters;
    }

    public void setParallelScarpRadiusMeters(double parallelScarpRadiusMeters) {
        this.parallelScarpRadiusMeters = parallelScarpRadiusMeters;
    }

    public Map<GeotechHazardClass, Double> getNodePenalties() {
        return nodePenalties;
    }

    public void setNodePenalties(Map<GeotechHazardClass, Double> nodePenalties) {
        this.nodePenalties = nodePenalties;
    }

    public Map<GeotechHazardTier, Double> getMitigationBaseCostKmEq() {
        return mitigationBaseCostKmEq;
    }

    public void setMitigationBaseCostKmEq(Map<GeotechHazardTier, Double> mitigationBaseCostKmEq) {
        this.mitigationBaseCostKmEq = mitigationBaseCostKmEq;
    }
}
