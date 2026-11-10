package in.marg.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LulcClassTest {

    @Test
    void classifiesObservedBhuvanDescriptionsFromDescrFields() {
        assertEquals(LulcClass.BUILT_UP, LulcClass.fromFeatureInfo("""
                Results for FeatureType 'http://bhuvan-vec2/lulc:AS_LULC50K_1516':
                --------------------------------------------
                DIST_OFF = Nagaon
                LU_Webcode = 1
                DESCR_1 = Builtup
                DESCR_2 = Urban
                --------------------------------------------
                """));

        assertEquals(LulcClass.AGRICULTURE, LulcClass.fromFeatureInfo("""
                Results for FeatureType 'http://bhuvan-vec2/lulc:AS_LULC50K_1516':
                --------------------------------------------
                DESCR_1 = Agriculture
                DESCR_2 = Crop land
                --------------------------------------------
                """));

        assertEquals(LulcClass.FOREST, LulcClass.fromFeatureInfo("""
                Results for FeatureType 'http://bhuvan-vec2/lulc:AS_LULC50K_1516':
                --------------------------------------------
                DESCR_1 = Forest
                DESCR_2 = Deciduous
                --------------------------------------------
                """));

        assertEquals(LulcClass.FOREST, LulcClass.fromFeatureInfo("""
                DESCR_1 = Forest
                DESCR_2 = Forest Plantation
                """));

        assertEquals(LulcClass.BARREN_WASTELAND, LulcClass.fromFeatureInfo("""
                DESCR_1 = Barren / Unculturable wastelands
                DESCR_2 = Scrub land
                """));

        assertEquals(LulcClass.WETLAND_WATER, LulcClass.fromFeatureInfo("""
                DESCR_1 = Wetland / Waterbodies
                DESCR_2 = River / Stream / Canals
                """));

        assertEquals(LulcClass.GRASS_GRAZING, LulcClass.fromFeatureInfo("""
                DESCR_1 = Grass / Grazing land
                DESCR_2 = Grass / Grazing land
                """));
    }

    @Test
    void returnsUnknownForNoFeaturesOrMissingDescrFields() {
        assertEquals(LulcClass.UNKNOWN, LulcClass.fromFeatureInfo("no features were found"));
        assertEquals(LulcClass.UNKNOWN, LulcClass.fromFeatureInfo("No features were found."));
        assertEquals(LulcClass.UNKNOWN, LulcClass.fromFeatureInfo(""));
        assertEquals(LulcClass.UNKNOWN, LulcClass.fromFeatureInfo(null));

        // Response with no DESCR_1 or DESCR_2 fields must return UNKNOWN even if other text mentions keywords
        assertEquals(LulcClass.UNKNOWN, LulcClass.fromFeatureInfo("""
                Results for FeatureType 'http://bhuvan-vec2/lulc:AS_LULC50K_1516':
                --------------------------------------------
                DIST_OFF = Forest Division
                LU_Webcode = 9
                area_norm = 0.83682212232
                --------------------------------------------
                """));
    }
}
