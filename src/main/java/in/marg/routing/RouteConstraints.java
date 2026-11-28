package in.marg.routing;

import in.marg.transport.FacilityMode;

/**
 * Optional global constraints applied while generating a greenfield alignment.
 * <ul>
 *   <li>{@code maxRouteLengthKm}: physical-length budget (not a cost budget).</li>
 *   <li>{@code enforceTurnDiscipline}: enables IRC heading-aware turn penalties in A* so
 *       alignments prefer smooth multi-step sweeps over jagged 90°/135° grid kinks.</li>
 *   <li>{@code gentleTurnPenaltyKmEq}: small penalty (km-eq) for 45° bends to suppress 250 m grid staircasing.</li>
 *   <li>{@code sharpTurnPenaltyKmEq}: steep penalty (km-eq) for >= 90° bends (IRC:SP:99 / IRC:SP:84).</li>
 * </ul>
 */
public record RouteConstraints(
        Double maxRouteLengthKm,
        boolean enforceTurnDiscipline,
        double gentleTurnPenaltyKmEq,
        double sharpTurnPenaltyKmEq) {

    public RouteConstraints(Double maxRouteLengthKm) {
        this(maxRouteLengthKm, false, 0.0, 0.0);
    }

    public static RouteConstraints none() {
        return new RouteConstraints(null, false, 0.0, 0.0);
    }

    public static RouteConstraints maxLengthKm(double maxRouteLengthKm) {
        validateMaxLength(maxRouteLengthKm);
        return new RouteConstraints(maxRouteLengthKm, false, 0.0, 0.0);
    }

    /**
     * Builds IRC-aligned routing constraints for the requested highway class.
     * Expressways (IRC:SP:99-2013, 120 km/h) enforce stronger bend discipline than
     * National Highways (IRC:SP:84-2019, 100 km/h) while remaining 100% graph-complete
     * in tight terrain pockets.
     */
    public static RouteConstraints forFacilityMode(Double maxRouteLengthKm, FacilityMode mode) {
        if (maxRouteLengthKm != null) {
            validateMaxLength(maxRouteLengthKm);
        }
        if (mode == FacilityMode.EXPRESSWAY) {
            return new RouteConstraints(maxRouteLengthKm, true, 0.18, 3.0);
        }
        return new RouteConstraints(maxRouteLengthKm, true, 0.08, 1.5);
    }

    private static void validateMaxLength(double maxRouteLengthKm) {
        if (!(maxRouteLengthKm > 0.0) || !Double.isFinite(maxRouteLengthKm)) {
            throw new IllegalArgumentException("maxRouteLengthKm must be a positive finite number");
        }
    }
}
