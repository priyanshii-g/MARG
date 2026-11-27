package in.marg.routing;

import in.marg.model.GeoPoint;

/**
 * Request-scoped decorator that multiplies a CostLayer's soft penalties by a specific factor
 * while strictly preserving blocked hard constraints (zero-tolerance exclusions).
 * <p>
 * Ensures that per-request user preferences (e.g. Eco-Shield, Community Safeguard)
 * are applied on-the-fly without mutating the global underlying properties or singleton state.
 */
public class ScaledCostLayer implements CostLayer {

    private final CostLayer delegate;
    private final double multiplier;

    public ScaledCostLayer(CostLayer delegate, double multiplier) {
        this.delegate = delegate;
        this.multiplier = multiplier;
    }

    public static CostLayer wrap(CostLayer delegate, double multiplier) {
        if (delegate == null) {
            return null;
        }
        if (Math.abs(multiplier - 1.0) < 1e-4) {
            return delegate;
        }
        return new ScaledCostLayer(delegate, multiplier);
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        CostAssessment ca = delegate.assess(point);
        if (ca.blocked() || Math.abs(multiplier - 1.0) < 1e-4) {
            return ca;
        }
        return new CostAssessment(false, ca.penaltyMultiplier() * multiplier, ca.source(), ca.className());
    }

    @Override
    public boolean hasEdgeCost() {
        return delegate.hasEdgeCost();
    }

    @Override
    public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        CostAssessment ca = delegate.assessEdge(from, to);
        if (ca.blocked() || Math.abs(multiplier - 1.0) < 1e-4) {
            return ca;
        }
        return new CostAssessment(false, ca.penaltyMultiplier() * multiplier, ca.source(), ca.className());
    }

    public CostLayer delegate() {
        return delegate;
    }

    public double multiplier() {
        return multiplier;
    }
}
