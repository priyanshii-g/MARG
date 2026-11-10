package in.marg.routing;

/**
 * Result of evaluating one geographic grid cell or grid-step transition.
 * penaltyMultiplier is dimensionless and multiplies the movement cost of the step.
 */
public record CostAssessment(
        boolean blocked,
        double penaltyMultiplier,
        String source,
        String className
) {
    public static final CostAssessment FREE = new CostAssessment(false, 0.0, "", null);

    public static CostAssessment free(String source) {
        return new CostAssessment(false, 0.0, source, null);
    }

    public static CostAssessment blocked(String source, String className) {
        return new CostAssessment(true, 0.0, source, className);
    }
}
