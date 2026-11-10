package in.marg.routing;

/** Coarse planning groups derived from Bhuvan's LULC feature description. */
public enum LulcClass {
    BUILT_UP,
    AGRICULTURE,
    FOREST,
    GRASS_GRAZING,
    BARREN_WASTELAND,
    WETLAND_WATER,
    SNOW,
    UNKNOWN;

private static final java.util.regex.Pattern DESCRIPTION_FIELD =
    java.util.regex.Pattern.compile(
            "(?im)^\\s*DESCR_[12]\\s*=\\s*(.*?)\\s*$"
    );

public static LulcClass fromFeatureInfo(String raw) {
    if (raw == null || raw.isBlank()) {
        return UNKNOWN;
    }

    String trimmed = raw.trim();

    // A successful WMS response does not guarantee a feature was found.
    if (trimmed.matches("(?is)^no\\s+features?\\s+were\\s+found\\.?$")) {
        return UNKNOWN;
    }

    // Parse only the land-cover description fields.
    java.util.regex.Matcher matcher =
            DESCRIPTION_FIELD.matcher(raw);

    StringBuilder descriptions = new StringBuilder();

    while (matcher.find()) {
        descriptions.append(' ')
                .append(matcher.group(1));
    }

    if (descriptions.isEmpty()) {
        return UNKNOWN;
    }

    String text = descriptions.toString()
            .toLowerCase(java.util.Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("\\s+", " ")
            .trim();

    // Check forest before agriculture so forest plantations
    // are not classified as agricultural plantations.
    if (containsAny(text,
            "evergreen", "deciduous", "scrub forest",
            "forest plantation", "tree clad", "forest")) {
        return FOREST;
    }

    if (containsAny(text,
            "water body", "water bodies", "waterbodies",
            "river", "stream", "canal", "lake", "pond",
            "reservoir", "wetland", "mangrove", "swamp",
            "waterlogged")) {
        return WETLAND_WATER;
    }

    if (containsAny(text,
            "built up", "builtup", "urban", "industrial",
            "settlement", "mining", "quarry")) {
        return BUILT_UP;
    }

    if (containsAny(text,
            "crop land", "cropland", "kharif", "rabi",
            "zaid", "fallow", "agriculture",
            "shifting cultivation", "plantation", "orchard")) {
        return AGRICULTURE;
    }

    if (containsAny(text,
            "grassland", "grass grazing", "grazing")) {
        return GRASS_GRAZING;
    }

    if (containsAny(text,
            "barren", "wasteland", "waste land",
            "sandy area", "scrub land", "ravinous",
            "gullied", "unculturable")) {
        return BARREN_WASTELAND;
    }

    if (containsAny(text, "snow", "glacial")) {
        return SNOW;
    }

    return UNKNOWN;
}

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }
}
