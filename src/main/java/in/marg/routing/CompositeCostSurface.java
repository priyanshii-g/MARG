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
     * Returns a fallback copy of this surface where only sub-grid {@code > 30°} slope edges drop
     * from Hard-Block to a 500.0x barrier penalty. Active Tectonic Landslide Shear Cores, Severe
     * Flood zones, Dense Urban Colonies, Hospitals/Institutional/Sacred Precincts, Protected
     * Sanctuaries, and all 250 m routing vertices {@code > 30°} remain 100% Hard-Blocked.
     */
    public CompositeCostSurface withSlopeEdgeLastResortMode() {
        List<CostLayer> relaxed = new ArrayList<>(layers.size());
        for (CostLayer layer : layers) {
            relaxed.add(relaxLayerSlopeEdge(layer));
        }
        return new CompositeCostSurface(relaxed);
    }

    /**
     * Returns a fallback copy of this surface where dense urban / residential colonies drop from
     * Hard-Block to a 1000.0x barrier penalty (and sub-grid {@code > 30°} slope edges drop to
     * 500.0x), while Active Tectonic Landslide Shear Cores, Severe Flood zones, Hospitals,
     * Institutional/Religious Campuses, Sacred Precincts, Protected Sanctuaries, and {@code > 30°}
     * vertices remain 100% Hard-Blocked.
     */
    public CompositeCostSurface withDenseColonyLastResortMode() {
        List<CostLayer> relaxed = new ArrayList<>(layers.size());
        for (CostLayer layer : layers) {
            relaxed.add(relaxLayerDenseColony(layer));
        }
        return new CompositeCostSurface(relaxed);
    }

    /**
     * Absolute last-resort Tier-4 fallback used ONLY when strict hard-blocks on Active Landslide
     * Shear Cores or Severe Flood zones seal off every path across the study corridor: converts
     * Active Landslides to a 2500x barrier and Severe Flood zones to a 150x barrier while keeping
     * Protected Sanctuaries, Hospitals/Schools/Temples, and {@code > 30°} vertices Hard-Blocked.
     */
    public CompositeCostSurface withAllGeoHazardLastResortMode() {
        List<CostLayer> relaxed = new ArrayList<>(layers.size());
        for (CostLayer layer : layers) {
            relaxed.add(relaxLayerAllGeoHazards(layer));
        }
        return new CompositeCostSurface(relaxed);
    }

    private static CostLayer relaxLayerSlopeEdge(CostLayer layer) {
        if (layer instanceof SlopeCostLayer slope) {
            return slope.withEdgeBarrierLastResortMode();
        }
        if (layer instanceof ScaledCostLayer scaled) {
            CostLayer inner = relaxLayerSlopeEdge(scaled.delegate());
            return inner == scaled.delegate() ? scaled : ScaledCostLayer.wrap(inner, scaled.multiplier());
        }
        return layer;
    }

    private static CostLayer relaxLayerDenseColony(CostLayer layer) {
        if (layer instanceof BuildingFootprintCostLayer bcl) {
            return bcl.withDenseColonyLastResortMode();
        }
        if (layer instanceof SettlementCostLayer scl) {
            return scl.withUrbanCoreLastResortMode();
        }
        if (layer instanceof SlopeCostLayer slope) {
            return slope.withEdgeBarrierLastResortMode();
        }
        if (layer instanceof ScaledCostLayer scaled) {
            CostLayer inner = relaxLayerDenseColony(scaled.delegate());
            return inner == scaled.delegate() ? scaled : ScaledCostLayer.wrap(inner, scaled.multiplier());
        }
        return layer;
    }

    private static CostLayer relaxLayerAllGeoHazards(CostLayer layer) {
        CostLayer base = relaxLayerDenseColony(layer);
        if (base instanceof GeotechLandslideCostLayer geo) {
            return geo.withActiveLandslideLastResortMode();
        }
        if (base instanceof FloodRasterCostLayer flood) {
            return flood.withSevereFloodLastResortMode();
        }
        if (base instanceof ScaledCostLayer scaled) {
            CostLayer inner = relaxLayerAllGeoHazards(scaled.delegate());
            return inner == scaled.delegate() ? scaled : ScaledCostLayer.wrap(inner, scaled.multiplier());
        }
        return base;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        double penalty = 0.0;
        // Deduplicate overlapping coarse settlement-exposure (L5) and building-density (L11)
        // raster halos on the same node by taking max(L5, L11) rather than summing both.
        double settlementHaloPenalty = 0.0;
        String source = "";
        String className = null;
        for (CostLayer layer : layers) {
            CostAssessment a = layer.assess(point);
            if (a.blocked()) {
                return a;
            }
            double p = Math.max(0.0, a.penaltyMultiplier());
            String src = a.source() != null ? a.source() : "";
            if (src.startsWith("social:settlement-exposure") || src.startsWith("building:footprints-and-settlements")) {
                settlementHaloPenalty = Math.max(settlementHaloPenalty, p);
            } else {
                penalty += p;
            }
            if (className == null && a.className() != null) {
                className = a.className();
            }
            if (!src.isBlank()) {
                source = source.isBlank() ? src : source + "," + src;
            }
        }
        penalty += settlementHaloPenalty;
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
