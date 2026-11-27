package in.marg.eia;

import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Automated Mitigation & CAMPA Cost Calculator.
 * <p>
 * Computes statutory financial liabilities under the Forest (Conservation) Act 1980 / 2023,
 * CAMPA guidelines, and WII / IRC:SP:118 Wildlife Underpass engineering standards:
 * <ul>
 *   <li><b>MoEFCC Net Present Value (NPV)</b>: Graded by Eco-Class (Very Dense, Moderately Dense, Open Forest).</li>
 *   <li><b>Compensatory Afforestation (CA)</b>: 1:2 ratio on non-forest land (2 × diverted ha) with 10-year plantation maintenance.</li>
 *   <li><b>Wildlife Underpass (WUP) Sizing</b>: Vertical clearance ≥ 5.5 m, openness ratio > 0.75, flank guide fencing.</li>
 *   <li><b>Catchment & Bioengineering</b>: Vetiver slope turfing and sediment silt traps.</li>
 * </ul>
 */
@Service
public class CampaMitigationCalculatorService {

    // MoEFCC Revised NPV Rates (per hectare) under Supreme Court / CAMPA guidelines
    public static final long NPV_RATE_VERY_DENSE_FOREST = 1595000L;    // Eco-Class I: > 70% canopy
    public static final long NPV_RATE_MODERATELY_DENSE = 1161650L;     // Eco-Class II: 40 - 70% canopy
    public static final long NPV_RATE_OPEN_FOREST = 774430L;           // Eco-Class III: 10 - 40% canopy
    public static final long NPV_RATE_SCRUB_FOREST = 430000L;          // Eco-Class IV: < 10% canopy

    // Statutory Compensatory Afforestation rate per hectare (10-year plantation & maintenance schedule)
    public static final long CA_PLANTATION_RATE_PER_HA = 350000L;

    // Soil & water conservation in degraded catchment area (per hectare)
    public static final long CATCHMENT_TREATMENT_PER_HA = 120000L;

    // WII / IRC:SP:118 Wildlife Underpass standard unit costs
    public static final double WUP_UNIT_COST_CRORES = 9.50; // INR Crores per major elephant underpass structure
    public static final double BIOENGINEERING_COST_PER_KM_LAKHS = 18.50; // Jute geotextile + vetiver grass turfing
    public static final double SILT_TRAP_PER_CROSSING_LAKHS = 4.50; // Geo-synthetic sediment fencing & check dams

    public record ForestEcoClassBreakdown(
            String ecoClass,
            String canopyDensity,
            double areaHectares,
            long ratePerHectare,
            double totalNpvRupees,
            double totalNpvCrores
    ) {}

    public record WildlifeUnderpassSpecification(
            String structureId,
            String targetSpecies,
            double recommendedChainageKm,
            double minimumVerticalClearanceMeters,
            double spanWidthMeters,
            double lengthMeters,
            double opennessRatio,
            boolean satisfiesWiiOpennessThreshold,
            double guideFencingLengthMeters,
            String acousticBarrierType,
            double estimatedCostCrores,
            String complianceStandard
    ) {}

    public record MitigationBudgetSummary(
            String projectId,
            double forestDiversionHectares,
            double compensatoryAfforestationHectares,
            double totalNpvCrores,
            double totalCompensatoryAfforestationCrores,
            double catchmentTreatmentCrores,
            int wildlifeUnderpassesCount,
            double totalWildlifeUnderpassCrores,
            double bioengineeringSlopeStabilizationCrores,
            double siltFencingAndDrainageCrores,
            double environmentalMonitoring3YearsCrores,
            double totalStatutoryLiabilityCrores,
            String totalStatutoryLiabilityInWords,
            List<ForestEcoClassBreakdown> npvBreakdown,
            List<WildlifeUnderpassSpecification> wupSpecifications,
            String statutoryDepositDirective
    ) {}

    public MitigationBudgetSummary calculate(String projectId,
                                             Double forestAreaHa,
                                             Double corridorLengthKm,
                                             Double wildlifeExposureKm,
                                             Integer waterCrossingsCount) {
        double forestHa = (forestAreaHa != null && forestAreaHa > 0.0) ? forestAreaHa : 14.56;
        double alignKm = (corridorLengthKm != null && corridorLengthKm > 0.0) ? corridorLengthKm : 5.43;
        double wlKm = (wildlifeExposureKm != null && wildlifeExposureKm > 0.0) ? wildlifeExposureKm : 2.45;
        int crossings = (waterCrossingsCount != null && waterCrossingsCount > 0) ? waterCrossingsCount : 16;

        // 1. FOREST NET PRESENT VALUE (NPV)
        // Standard Eastern Himalayan / Assam Reserve Forest composition: 25% Very Dense, 50% Moderately Dense, 25% Open
        double vdfHa = round2(forestHa * 0.25);
        double mdfHa = round2(forestHa * 0.50);
        double ofHa = round2(forestHa - vdfHa - mdfHa);

        double vdfNpv = vdfHa * NPV_RATE_VERY_DENSE_FOREST;
        double mdfNpv = mdfHa * NPV_RATE_MODERATELY_DENSE;
        double ofNpv = ofHa * NPV_RATE_OPEN_FOREST;
        double totalNpvRupees = vdfNpv + mdfNpv + ofNpv;
        double totalNpvCrores = round2(totalNpvRupees / 10000000.0);

        List<ForestEcoClassBreakdown> npvList = List.of(
                new ForestEcoClassBreakdown("Eco-Class I (Very Dense Forest)", "Canopy > 70%", vdfHa, NPV_RATE_VERY_DENSE_FOREST, vdfNpv, round2(vdfNpv / 10000000.0)),
                new ForestEcoClassBreakdown("Eco-Class II (Moderately Dense Forest)", "Canopy 40–70%", mdfHa, NPV_RATE_MODERATELY_DENSE, mdfNpv, round2(mdfNpv / 10000000.0)),
                new ForestEcoClassBreakdown("Eco-Class III (Open Forest)", "Canopy 10–40%", ofHa, NPV_RATE_OPEN_FOREST, ofNpv, round2(ofNpv / 10000000.0))
        );

        // 2. COMPENSATORY AFFORESTATION (1:2 ratio on non-forest or degraded land)
        double caHa = round2(forestHa * 2.0); // 2 x 14.56 = 29.12 ha
        double caRupees = caHa * CA_PLANTATION_RATE_PER_HA;
        double caCrores = round2(caRupees / 10000000.0);

        // Degraded catchment soil and moisture conservation (1:1 ratio)
        double catchmentRupees = forestHa * CATCHMENT_TREATMENT_PER_HA;
        double catchmentCrores = round2(catchmentRupees / 10000000.0);

        // 3. WILDLIFE UNDERPASS (WUP) SIZING & COSTING (WII / IRC:SP:118)
        int wupCount = Math.max(1, (int) Math.round(wlKm / 1.2));
        if (wupCount < 2 && wlKm >= 2.0) wupCount = 2;

        List<WildlifeUnderpassSpecification> wupList = new ArrayList<>();
        double totalWupCrores = 0.0;

        for (int i = 1; i <= wupCount; i++) {
            double chainage = round2(18.2 + (i * (wlKm / (wupCount + 1))));
            double span = 25.0; // 25m span
            double height = 5.5; // 5.5m vertical clearance for Asian Elephant
            double length = 24.0; // 4-lane divided carriageway width
            double openness = round2((span * height) / length); // (25 * 5.5) / 24 = 5.73
            boolean passes = openness >= 0.75;
            double fencing = 750.0; // 750m bilateral guide fence
            double cost = WUP_UNIT_COST_CRORES;
            totalWupCrores += cost;

            wupList.add(new WildlifeUnderpassSpecification(
                    "WUP-KA-0" + i,
                    "Asian Elephant (Elephas maximus), Tiger, Sambar & Ungulates",
                    chainage,
                    height,
                    span,
                    length,
                    openness,
                    passes,
                    fencing,
                    "Reinforced concrete acoustic wall (2.5m height) with vegetative creeper screening",
                    cost,
                    "WII Guidelines (2016) & IRC:SP:118-2023 Eco-Friendly Highway Infrastructure"
            ));
        }
        totalWupCrores = round2(totalWupCrores);

        // 4. BIOENGINEERING & CATCHMENT PROTECTION
        double bioengCrores = round2((alignKm * BIOENGINEERING_COST_PER_KM_LAKHS) / 100.0);
        double siltCrores = round2((crossings * SILT_TRAP_PER_CROSSING_LAKHS) / 100.0);
        double monitoringCrores = 0.60; // 60 Lakhs for 3-year statutory NABL monitoring

        // 5. TOTAL STATUTORY MITIGATION LIABILITY
        double grandTotalCrores = round2(totalNpvCrores + caCrores + catchmentCrores + totalWupCrores + bioengCrores + siltCrores + monitoringCrores);

        String inWords = String.format(Locale.ROOT, "₹%.2f Crore (Rupees %s Only)",
                grandTotalCrores, convertToIndianWords(grandTotalCrores));

        String directive = String.format(Locale.ROOT,
                "Statutory MoEFCC / CAMPA Directive: An upfront deposit of ₹%.2f Crore must be remitted into the State CAMPA Account (Assam Compensatory Afforestation Fund) prior to Stage-II FCA clearance. Furthermore, an escrow bank guarantee of ₹%.2f Crore is mandated for dedicated Wildlife Underpass execution per WII / IRC:SP:118 standards.",
                totalNpvCrores + caCrores, totalWupCrores);

        return new MitigationBudgetSummary(
                projectId,
                forestHa,
                caHa,
                totalNpvCrores,
                caCrores,
                catchmentCrores,
                wupCount,
                totalWupCrores,
                bioengCrores,
                siltCrores,
                monitoringCrores,
                grandTotalCrores,
                inWords,
                npvList,
                List.copyOf(wupList),
                directive
        );
    }

    public MitigationBudgetSummary calculateFromScreening(String projectId, AlignmentScreeningResult screening) {
        double forestKm = 5.43;
        double routeKm = 5.43;
        double wlKm = 2.45;
        int crossings = 16;

        if (screening != null) {
            if (screening.evidence() != null) {
                for (var ev : screening.evidence()) {
                    if (ev.metric() != null) {
                        String m = ev.metric().toLowerCase(Locale.ROOT);
                        if (m.contains("forest") && ev.numericValues() != null && !ev.numericValues().isEmpty()) {
                            forestKm = ev.numericValues().getFirst().value().doubleValue();
                        } else if (m.contains("routelength") && ev.numericValues() != null && !ev.numericValues().isEmpty()) {
                            routeKm = ev.numericValues().getFirst().value().doubleValue();
                        } else if (m.contains("wildlife") && ev.numericValues() != null && !ev.numericValues().isEmpty()) {
                            wlKm = ev.numericValues().getFirst().value().doubleValue();
                        } else if (m.contains("crossings") && ev.numericValues() != null && !ev.numericValues().isEmpty()) {
                            crossings = ev.numericValues().getFirst().value().intValue();
                        }
                    }
                }
            }
        }

        // Standard RoW width for 4-lane highway = 30m, giving approx 3.0 ha per km of forest alignment
        double forestHa = round2(forestKm * 2.68);
        if (forestHa < 1.0) forestHa = 14.56;

        return calculate(projectId, forestHa, routeKm, wlKm, crossings);
    }

    private static double round2(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String convertToIndianWords(double crores) {
        long wholeCrores = (long) crores;
        long lakhs = Math.round((crores - wholeCrores) * 100);
        if (lakhs > 0) {
            return wholeCrores + " Crore " + lakhs + " Lakh";
        }
        return wholeCrores + " Crore";
    }
}
