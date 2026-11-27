package in.marg.eia;

import in.marg.eia.CampaMitigationCalculatorService.MitigationBudgetSummary;
import in.marg.eia.CampaMitigationCalculatorService.WildlifeUnderpassSpecification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CampaMitigationCalculatorServiceTest {

    private final CampaMitigationCalculatorService calculator = new CampaMitigationCalculatorService();

    @Test
    void testCampaAndNpvCalculationForStatutoryForestDiversion() {
        // 14.56 ha forest diversion, 5.43 km corridor, 2.45 km wildlife exposure, 16 water crossings
        MitigationBudgetSummary budget = calculator.calculate("test-proj", 14.56, 5.43, 2.45, 16);

        assertNotNull(budget);
        assertEquals(14.56, budget.forestDiversionHectares(), 0.001);

        // 1:2 Compensatory Afforestation land requirement
        assertEquals(29.12, budget.compensatoryAfforestationHectares(), 0.01,
                "Statutory CA land requirement must be exactly 2 x forest area (2 x 14.56 = 29.12 ha)");

        // Net Present Value check
        assertTrue(budget.totalNpvCrores() > 1.5 && budget.totalNpvCrores() < 2.0,
                "NPV for 14.56 ha across Eco-Classes I-III should be approx ₹1.71 Cr, was: " + budget.totalNpvCrores());

        // Wildlife Underpass sizing
        assertTrue(budget.wildlifeUnderpassesCount() >= 2, "2.45 km corridor exposure must mandate at least 2 WUPs");
        assertFalse(budget.wupSpecifications().isEmpty());

        for (WildlifeUnderpassSpecification wup : budget.wupSpecifications()) {
            assertTrue(wup.minimumVerticalClearanceMeters() >= 4.5, "WUP clearance must meet statutory minimum 4.5 m (5.5 m for elephants)");
            assertTrue(wup.opennessRatio() > 0.75, "WUP openness ratio must exceed WII minimum threshold of 0.75");
            assertTrue(wup.satisfiesWiiOpennessThreshold());
            assertEquals(750.0, wup.guideFencingLengthMeters(), 0.1);
        }

        // Total mitigation budget
        assertTrue(budget.totalStatutoryLiabilityCrores() > 20.0,
                "Total liability including NPV + CA + 2 WUPs + Bioengineering should exceed ₹20 Crore, was: " + budget.totalStatutoryLiabilityCrores());
        assertTrue(budget.statutoryDepositDirective().contains("State CAMPA Account"));
    }
}
