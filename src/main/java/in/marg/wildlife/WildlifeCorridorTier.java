package in.marg.wildlife;

/**
 * Conservation & mitigation hierarchy for wildlife movement axes per Wildlife Institute of India (WII)
 * linear infrastructure mitigation guidelines.
 */
public enum WildlifeCorridorTier {
    CRITICAL_ELEPHANT_RHINO_CORRIDOR("ELEVATED_WILDLIFE_VIADUCT"),
    INTER_SANCTUARY_ELEPHANT_CORRIDOR("WIDE_ANIMAL_UNDERPASS_VIADUCT"),
    LOCAL_WILDLIFE_DISPERSAL_AXIS("WILDLIFE_BOX_UNDERPASS_OVERPASS");

    private final String passageStructureType;

    WildlifeCorridorTier(String passageStructureType) {
        this.passageStructureType = passageStructureType;
    }

    public String passageStructureType() {
        return passageStructureType;
    }
}
