package in.marg.row;

/**
 * Concentric 2D spatial impact zones around a proposed highway alignment and its feeder spurs.
 */
public enum RowImpactZone {

    /**
     * Direct physical Right-of-Way (RoW) acquisition strip (e.g., 60 m for Expressway per IRC:SP:84/99,
     * 45 m for National Highway per IRC:SP:73, 30 m for Feeder Spurs per IRC:73, plus interchange ramp loops).
     * Any structure or land parcel inside this polygon requires direct physical acquisition or relocation.
     */
    DIRECT_ROW_FOOTPRINT("Direct Right-of-Way Acquisition Footprint"),

    /**
     * Construction staging, cut/fill earthwork toe slopes, borrow/spoil haulage, and blasting/vibration
     * safety buffer (default 100 m half-width / 200 m total corridor width).
     */
    CONSTRUCTION_CLEARANCE_ZONE("Construction & Earthwork Clearance Zone"),

    /**
     * Environmental Impact Assessment (EIA), traffic noise contour, air-quality dispersion, and
     * community access severance buffer (default 250 m half-width / 500 m total corridor width).
     */
    ENVIRONMENTAL_INFLUENCE_BUFFER("Environmental, Noise & Community Influence Buffer"),

    /**
     * Outside the outer environmental influence buffer.
     */
    OUTSIDE_CORRIDOR("Outside Corridor Influence Zone");

    private final String description;

    RowImpactZone(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
