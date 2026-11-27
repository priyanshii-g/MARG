package in.marg.routing;

import in.marg.model.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/** Combines independent spatial layers without coupling the A* implementation to GIS details. */
public class CompositeCostSurface implements CostSurface {
    private final List<CostLayer> layers;
    private final List<CostLayer> edgeLayers;

    public CompositeCostSurface(List<CostLayer> layers) {
        this.layers = List.copyOf(layers);
        this.edgeLayers = this.layers.stream().filter(CostLayer::hasEdgeCost).toList();
    }

    /**
     * Returns a last-resort copy of this surface where dense urban / residential colonies drop
     * from Hard-Block to a 1000.0x extreme barrier penalty, while Hospitals, Institutional/Religious
     * Campuses, Sacred Precincts, and Protected Sanctuaries remain 100% Hard-Blocked.
     */
    public CompositeCostSurface withDenseColonyLastResortMode() {
        List<CostLayer> relaxed = new ArrayList<>(layers.size());
        for (CostLayer layer : layers) {
            relaxed.add(relaxLayerDenseColony(layer));
        }
        return new CompositeCostSurface(relaxed);
    }

    private static CostLayer relaxLayerDenseColony(CostLayer layer) {
        if (layer instanceof BuildingFootprintCostLayer bcl) {
            return bcl.withDenseColonyLastResortMode();
        }
        if (layer instanceof SettlementCostLayer scl) {
            return scl.withUrbanCoreLastResortMode();
        }
        if (layer instanceof ScaledCostLayer scaled) {
            CostLayer inner = relaxLayerDenseColony(scaled.delegate());
            return inner == scaled.delegate() ? scaled : ScaledCostLayer.wrap(inner, scaled.multiplier());
        }
        return layer;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        double penalty = 0.0;
        String source = "";
        String className = null;
        for (CostLayer layer : layers) {
            CostAssessment a = layer.assess(point);
            if (a.blocked()) {
                return a;
            }
            penalty += Math.max(0.0, a.penaltyMultiplier());
            if (className == null && a.className() != null) {
                className = a.className();
            }
            if (!a.source().isBlank()) {
                source = source.isBlank() ? a.source() : source + "," + a.source();
            }
        }
        return new CostAssessment(false, penalty, source, className);
    }

    @Override
    public boolean hasEdgeCost() {
        return !edgeLayers.isEmpty();
    }

    @Override
    public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        if (edgeLayers.isEmpty()) {
            return CostAssessment.FREE;
        }
        double penalty = 0.0;
        String source = "";
        String className = null;
        for (CostLayer layer : edgeLayers) {
            CostAssessment a = layer.assessEdge(from, to);
            if (a.blocked()) {
                return a;
            }
            penalty += Math.max(0.0, a.penaltyMultiplier());
            if (className == null && a.className() != null) {
                className = a.className();
            }
            if (!a.source().isBlank()) {
                source = source.isBlank() ? a.source() : source + "," + a.source();
            }
        }
        if (penalty == 0.0 && className == null) {
            return CostAssessment.FREE;
        }
        return new CostAssessment(false, penalty, source, className);
    }
}
