package in.marg.config;

import in.marg.transport.FacilityMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the 2D Right-of-Way (RoW) Corridor & Impact Assessment Engine.
 * <p>
 * Grounded in Indian Roads Congress (IRC) geometric design standards:
 * <ul>
 *     <li><b>IRC:SP:84 / IRC:SP:99 (Expressways):</b> 60.0 m standard greenfield RoW width (30.0 m half-width).</li>
 *     <li><b>IRC:SP:73 / IRC:SP:84 (National Highways):</b> 45.0 m standard 4-lane NH RoW width (22.5 m half-width).</li>
 *     <li><b>IRC:73 (Dedicated Feeder / Spur Roads):</b> 30.0 m arterial/collector spur RoW width (15.0 m half-width).</li>
 *     <li><b>Grade-Separated Interchanges:</b> 150.0 m ramp terminal loop footprint radius.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "marg.routing.row")
public class RowCorridorProperties {

    private double expresswayRowWidthMeters = 60.0;
    private double nationalHighwayRowWidthMeters = 45.0;
    private double feederSpurRowWidthMeters = 30.0;
    private double interchangeFootprintRadiusMeters = 150.0;
    private double constructionZoneHalfWidthMeters = 100.0;
    private double environmentalBufferHalfWidthMeters = 250.0;
    private double crossSectionSampleStepMeters = 125.0;
    private double geoJsonSimplifyToleranceMeters = 8.0;

    public double rowWidthMetersFor(FacilityMode mode) {
        if (mode == FacilityMode.EXPRESSWAY) {
            return Math.max(20.0, expresswayRowWidthMeters);
        }
        return Math.max(15.0, nationalHighwayRowWidthMeters);
    }

    public String ircStandardFor(FacilityMode mode) {
        if (mode == FacilityMode.EXPRESSWAY) {
            return "IRC:SP:84-2019 / IRC:SP:99-2023 Greenfield Access-Controlled Expressway (60 m RoW)";
        }
        return "IRC:SP:73-2018 / IRC:SP:84-2019 4-Lane Divided National Highway (45 m RoW)";
    }

    public double getExpresswayRowWidthMeters() {
        return expresswayRowWidthMeters;
    }

    public void setExpresswayRowWidthMeters(double expresswayRowWidthMeters) {
        this.expresswayRowWidthMeters = expresswayRowWidthMeters;
    }

    public double getNationalHighwayRowWidthMeters() {
        return nationalHighwayRowWidthMeters;
    }

    public void setNationalHighwayRowWidthMeters(double nationalHighwayRowWidthMeters) {
        this.nationalHighwayRowWidthMeters = nationalHighwayRowWidthMeters;
    }

    public double getFeederSpurRowWidthMeters() {
        return feederSpurRowWidthMeters;
    }

    public void setFeederSpurRowWidthMeters(double feederSpurRowWidthMeters) {
        this.feederSpurRowWidthMeters = feederSpurRowWidthMeters;
    }

    public double getInterchangeFootprintRadiusMeters() {
        return interchangeFootprintRadiusMeters;
    }

    public void setInterchangeFootprintRadiusMeters(double interchangeFootprintRadiusMeters) {
        this.interchangeFootprintRadiusMeters = interchangeFootprintRadiusMeters;
    }

    public double getConstructionZoneHalfWidthMeters() {
        return constructionZoneHalfWidthMeters;
    }

    public void setConstructionZoneHalfWidthMeters(double constructionZoneHalfWidthMeters) {
        this.constructionZoneHalfWidthMeters = constructionZoneHalfWidthMeters;
    }

    public double getEnvironmentalBufferHalfWidthMeters() {
        return environmentalBufferHalfWidthMeters;
    }

    public void setEnvironmentalBufferHalfWidthMeters(double environmentalBufferHalfWidthMeters) {
        this.environmentalBufferHalfWidthMeters = environmentalBufferHalfWidthMeters;
    }

    public double getCrossSectionSampleStepMeters() {
        return crossSectionSampleStepMeters;
    }

    public void setCrossSectionSampleStepMeters(double crossSectionSampleStepMeters) {
        this.crossSectionSampleStepMeters = crossSectionSampleStepMeters;
    }

    public double getGeoJsonSimplifyToleranceMeters() {
        return geoJsonSimplifyToleranceMeters;
    }

    public void setGeoJsonSimplifyToleranceMeters(double geoJsonSimplifyToleranceMeters) {
        this.geoJsonSimplifyToleranceMeters = geoJsonSimplifyToleranceMeters;
    }
}
