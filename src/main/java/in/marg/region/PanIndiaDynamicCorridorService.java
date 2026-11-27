package in.marg.region;

import in.marg.building.OsmDynamicBuildingSettlementCache;
import in.marg.district.DistrictRecord;
import in.marg.district.IndianDistrictsCatalogService;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.routing.DistanceOnlyCostSurface;
import in.marg.routing.GreenfieldAStarRouter;
import in.marg.routing.RouteConstraints;
import in.marg.spatial.Wgs84UtmProjection;
import in.marg.transport.FacilityMode;
import in.marg.transport.TerminalAccessPolicy;
import in.marg.transport.TransportCategory;
import in.marg.transport.TransportNetworkVectorIndex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tier 1 — Dynamic On-Demand Pan-India Routing Service.
 *
 * <p>Routes greenfield highway/expressway corridors between any arbitrary start and goal
 * coordinates across the Indian mainland (6.5&deg;N..37.5&deg;N, 68.0&deg;E..97.5&deg;E),
 * dynamically selecting the local UTM Zone (42N..47N, EPSG:32642..32647) and computing
 * alignments on the canonical 250 m metric grid.
 */
@Service
public class PanIndiaDynamicCorridorService {

    public static final double ROUTING_RESOLUTION_METERS = 250.0;
    private static final double MAX_SINGLE_STAGE_SPAN_DEG = 0.65; // ~72 km per 250 m A* stage window
    private static final double DIRECT_ROAD_TIE_IN_THRESHOLD_METERS = 35.0;

    public record ClassifiedRoadAxis(
            String id,
            String ref,
            String name,
            TransportCategory category,
            String state,
            List<GeoPoint> points
    ) {}

    public record HighwayTieInResult(
            String corridorId,
            String roadRef,
            String roadName,
            TransportCategory category,
            GeoPoint tieInPoint,
            double distanceMeters
    ) {}

    private static final List<ClassifiedRoadAxis> NATIONAL_HIGHWAY_AXES = List.of(
            new ClassifiedRoadAxis("NH-44", "NH-44", "North-South Corridor (Srinagar-Delhi-Nagpur-Hyderabad-Bengaluru-Kanyakumari)", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(34.0837, 74.7973), new GeoPoint(32.7266, 74.8570), new GeoPoint(30.9010, 75.8573),
                    new GeoPoint(28.6139, 77.2090), new GeoPoint(27.1767, 78.0081), new GeoPoint(26.2183, 78.1828),
                    new GeoPoint(25.4484, 78.5685), new GeoPoint(23.8388, 78.7378), new GeoPoint(21.1458, 79.0882),
                    new GeoPoint(19.6710, 78.5320), new GeoPoint(17.3850, 78.4867), new GeoPoint(15.8281, 78.0373),
                    new GeoPoint(12.9716, 77.5946), new GeoPoint(11.6643, 78.1460), new GeoPoint(9.9252, 78.1198),
                    new GeoPoint(8.0883, 77.5385))),
            new ClassifiedRoadAxis("NH-48", "NH-48", "Delhi-Jaipur-Mumbai-Pune-Bengaluru-Chennai Golden Quadrilateral", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(28.6139, 77.2090), new GeoPoint(26.9124, 75.7873), new GeoPoint(26.4499, 74.6399),
                    new GeoPoint(24.5854, 73.7125), new GeoPoint(23.0225, 72.5714), new GeoPoint(22.3072, 73.1812),
                    new GeoPoint(21.1702, 72.8311), new GeoPoint(19.0760, 72.8777), new GeoPoint(18.5204, 73.8567),
                    new GeoPoint(17.6805, 74.0183), new GeoPoint(16.7050, 74.2433), new GeoPoint(15.3647, 75.1240),
                    new GeoPoint(12.9716, 77.5946), new GeoPoint(12.9165, 79.1325), new GeoPoint(13.0827, 80.2707))),
            new ClassifiedRoadAxis("NH-27", "NH-27", "East-West Corridor (Porbandar-Udaipur-Kanpur-Lucknow-Ayodhya-Guwahati-Silchar)", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(21.6417, 69.6293), new GeoPoint(22.3039, 70.8022), new GeoPoint(24.1751, 72.4320),
                    new GeoPoint(24.5854, 73.7125), new GeoPoint(25.2138, 75.8648), new GeoPoint(25.4484, 78.5685),
                    new GeoPoint(26.4499, 80.3319), new GeoPoint(26.8467, 80.9462), new GeoPoint(26.9202, 81.2218),
                    new GeoPoint(26.7630, 82.1155), new GeoPoint(26.7606, 83.3732), new GeoPoint(26.1197, 85.3910),
                    new GeoPoint(26.7271, 88.3953), new GeoPoint(26.1445, 91.7362), new GeoPoint(26.3452, 92.6840),
                    new GeoPoint(24.8333, 92.7789))),
            new ClassifiedRoadAxis("NH-19", "NH-19", "Grand Trunk National Highway (Agra-Kanpur-Varanasi-Dhanbad-Kolkata)", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(27.1767, 78.0081), new GeoPoint(26.4499, 80.3319), new GeoPoint(25.4358, 81.8463),
                    new GeoPoint(25.3176, 82.9739), new GeoPoint(24.9490, 84.0314), new GeoPoint(23.7957, 86.4304),
                    new GeoPoint(23.6889, 86.9661), new GeoPoint(22.5726, 88.3639))),
            new ClassifiedRoadAxis("NH-16", "NH-16", "East Coast Golden Quadrilateral (Kolkata-Bhubaneswar-Visakhapatnam-Vijayawada-Chennai)", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(22.5726, 88.3639), new GeoPoint(21.4934, 86.9135), new GeoPoint(20.2961, 85.8245),
                    new GeoPoint(19.3150, 84.7941), new GeoPoint(17.6868, 83.2185), new GeoPoint(17.0005, 81.8040),
                    new GeoPoint(16.5062, 80.6480), new GeoPoint(14.4426, 79.9865), new GeoPoint(13.0827, 80.2707))),
            new ClassifiedRoadAxis("NH-52", "NH-52", "Sangrur-Jaipur-Kota-Indore-Solapur-Hubballi-Ankola Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(30.2458, 75.8421), new GeoPoint(29.1492, 75.7217), new GeoPoint(26.9124, 75.7873),
                    new GeoPoint(25.2138, 75.8648), new GeoPoint(22.7196, 75.8577), new GeoPoint(20.9042, 74.7749),
                    new GeoPoint(19.8762, 75.3433), new GeoPoint(17.6599, 75.9064), new GeoPoint(15.3647, 75.1240),
                    new GeoPoint(14.6615, 74.3046))),
            new ClassifiedRoadAxis("NH-46", "NH-46", "Gwalior-Guna-Bhopal-Betul Central Spine", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(26.2183, 78.1828), new GeoPoint(24.6408, 77.3111), new GeoPoint(23.2599, 77.4126),
                    new GeoPoint(22.7533, 77.7289), new GeoPoint(21.9006, 77.8990))),
            new ClassifiedRoadAxis("NH-30", "NH-30", "Lucknow-Prayagraj-Jabalpur-Raipur-Vijayawada Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(26.8467, 80.9462), new GeoPoint(25.4358, 81.8463), new GeoPoint(24.5362, 81.3037),
                    new GeoPoint(23.1815, 79.9864), new GeoPoint(21.2514, 81.6296), new GeoPoint(19.0782, 82.0217),
                    new GeoPoint(16.5062, 80.6480))),
            new ClassifiedRoadAxis("NH-53", "NH-53", "Surat-Jalgaon-Akola-Nagpur-Raipur-Sambalpur-Paradip Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(21.1702, 72.8311), new GeoPoint(21.0077, 75.5626), new GeoPoint(20.7002, 77.0082),
                    new GeoPoint(20.9320, 77.7523), new GeoPoint(21.1458, 79.0882), new GeoPoint(21.2514, 81.6296),
                    new GeoPoint(21.4669, 83.9812), new GeoPoint(20.2625, 86.6688))),
            new ClassifiedRoadAxis("NH-65", "NH-65", "Pune-Solapur-Hyderabad-Vijayawada Corridor", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(18.5204, 73.8567), new GeoPoint(17.6599, 75.9064), new GeoPoint(17.3850, 78.4867),
                    new GeoPoint(16.5062, 80.6480))),
            new ClassifiedRoadAxis("NH-66", "NH-66", "West Coast Highway (Mumbai-Goa-Mangaluru-Kochi-Kanyakumari)", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(18.9894, 73.1175), new GeoPoint(17.5300, 73.5100), new GeoPoint(15.4909, 73.8278),
                    new GeoPoint(12.9141, 74.8560), new GeoPoint(11.2588, 75.7804), new GeoPoint(9.9312, 76.2673),
                    new GeoPoint(8.5241, 76.9366), new GeoPoint(8.0883, 77.5385))),
            new ClassifiedRoadAxis("NH-15", "NH-15", "Western Rajasthan & Upper Brahmaputra Highway Axes", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(31.6340, 74.8723), new GeoPoint(28.0229, 73.3119), new GeoPoint(26.9157, 70.9083),
                    new GeoPoint(25.7463, 71.3967), new GeoPoint(23.2500, 70.6100))),
            new ClassifiedRoadAxis("NH-29", "NH-29", "Assam-Nagaland-Manipur Highway Corridor", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(26.1100, 92.8700), new GeoPoint(25.9091, 93.7266), new GeoPoint(25.6751, 94.1086),
                    new GeoPoint(24.8170, 93.9368))),
            new ClassifiedRoadAxis("NH-9", "NH-9", "Fazilka-Delhi-Moradabad-Pithoragarh Northern Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(30.4038, 74.0254), new GeoPoint(29.1539, 75.7229), new GeoPoint(28.8955, 76.6066),
                    new GeoPoint(28.6139, 77.2090), new GeoPoint(28.8386, 78.7733), new GeoPoint(29.5828, 80.2182))),
            new ClassifiedRoadAxis("NH-34", "NH-34", "Kolkata-Berhampore-Malda-Siliguri North-South Bengal Spine", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(22.5726, 88.3639), new GeoPoint(23.4013, 88.4960), new GeoPoint(24.0988, 88.2679),
                    new GeoPoint(25.0108, 88.1411), new GeoPoint(26.7271, 88.3953))),
            new ClassifiedRoadAxis("NH-31", "NH-31", "Unnao-Raebareli-Varanasi-Patna-Siliguri East-West Gateway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(26.5400, 80.4900), new GeoPoint(26.2200, 81.2400), new GeoPoint(25.3176, 82.9739),
                    new GeoPoint(25.6800, 85.1800), new GeoPoint(25.7800, 87.4700), new GeoPoint(26.7271, 88.3953))),
            new ClassifiedRoadAxis("NH-33", "NH-33", "Barhi-Ranchi-Jamshedpur-Baharagora Jharkhand Spine", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(24.3000, 85.4200), new GeoPoint(23.3441, 85.3096), new GeoPoint(22.8046, 86.2029),
                    new GeoPoint(22.2800, 86.7200))),
            new ClassifiedRoadAxis("NH-544", "NH-544", "Salem-Coimbatore-Palakkad-Kochi Southern Industrial Arc", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(11.6643, 78.1460), new GeoPoint(11.0168, 76.9558), new GeoPoint(10.7867, 76.6548),
                    new GeoPoint(10.5276, 76.2144), new GeoPoint(9.9312, 76.2673))),
            new ClassifiedRoadAxis("NH-75", "NH-75", "Bantwal-Mangaluru-Hassan-Bengaluru-Kolar-Vellore Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(12.8700, 74.8800), new GeoPoint(13.0072, 76.1029), new GeoPoint(12.9716, 77.5946),
                    new GeoPoint(13.1367, 78.1340), new GeoPoint(12.9165, 79.1325))),
            new ClassifiedRoadAxis("NH-50", "NH-50", "Bidar-Kalaburagi-Hospet-Chitradurga Karnataka Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(17.9100, 77.5200), new GeoPoint(17.3297, 76.8343), new GeoPoint(15.2689, 76.3909),
                    new GeoPoint(14.2251, 76.4007))),
            new ClassifiedRoadAxis("NH-927", "NH-927", "Barabanki-Ramnagar-Bahraich-Nanpara-Nepalganj Highway", TransportCategory.NATIONAL_HIGHWAY, "UP", List.of(
                    new GeoPoint(26.9250, 81.1850), new GeoPoint(27.0850, 81.4050), new GeoPoint(27.5750, 81.6000),
                    new GeoPoint(27.8650, 81.5000), new GeoPoint(27.9750, 81.6150))),
            new ClassifiedRoadAxis("NH-1", "NH-1/NH-44", "Delhi-Ambala-Ludhiana-Jalandhar-Amritsar Grand Trunk North", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(28.6139, 77.2090), new GeoPoint(28.9931, 77.0151), new GeoPoint(29.3909, 76.9635),
                    new GeoPoint(29.6857, 76.9905), new GeoPoint(30.3752, 76.7821), new GeoPoint(30.9010, 75.8573),
                    new GeoPoint(31.3260, 75.5762), new GeoPoint(31.6340, 74.8723), new GeoPoint(31.6042, 74.6042))),
            new ClassifiedRoadAxis("NH-28", "NH-28", "Lucknow-Ayodhya-Basti-Gorakhpur-Muzaffarpur Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(26.8467, 80.9462), new GeoPoint(26.9250, 81.1850), new GeoPoint(26.7850, 82.1700),
                    new GeoPoint(26.7950, 82.7600), new GeoPoint(26.7606, 83.3732), new GeoPoint(26.4700, 84.4400),
                    new GeoPoint(26.1197, 85.3910), new GeoPoint(25.4700, 85.9700))),
            new ClassifiedRoadAxis("NH-21", "NH-21", "Jaipur-Dausa-Bharatpur-Agra Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(26.9124, 75.7873), new GeoPoint(26.8900, 76.3300), new GeoPoint(27.2200, 77.4900),
                    new GeoPoint(27.1767, 78.0081))),
            new ClassifiedRoadAxis("NH-43", "NH-43", "Katni-Shahdol-Ambikapur-Gumla-Ranchi Central-East Spine", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(23.8300, 80.4000), new GeoPoint(23.2900, 81.3500), new GeoPoint(23.1200, 83.2000),
                    new GeoPoint(23.0400, 84.5400), new GeoPoint(23.3441, 85.3096))),
            new ClassifiedRoadAxis("NH-20", "NH-20", "Bakhtiyarpur-Ranchi-Chaibasa-Panikoili Mineral Corridor", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(25.4500, 85.5300), new GeoPoint(24.8800, 85.5400), new GeoPoint(23.3441, 85.3096),
                    new GeoPoint(22.5500, 85.8100), new GeoPoint(21.5600, 85.6200), new GeoPoint(20.9700, 86.0200))),
            new ClassifiedRoadAxis("NH-37", "NH-37", "Guwahati-Nagaon-Jorhat-Dibrugarh Brahmaputra South Trunk", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(26.1445, 91.7362), new GeoPoint(26.3452, 92.6840), new GeoPoint(26.5890, 93.4000),
                    new GeoPoint(26.7509, 94.2037), new GeoPoint(27.4728, 94.9120), new GeoPoint(27.5000, 95.3667))),
            new ClassifiedRoadAxis("NH-67", "NH-67", "Nagapattinam-Thanjavur-Tiruchirappalli-Coimbatore Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(10.7600, 79.8400), new GeoPoint(10.7800, 79.1300), new GeoPoint(10.7905, 78.7047),
                    new GeoPoint(10.9500, 78.0800), new GeoPoint(11.0168, 76.9558), new GeoPoint(11.7000, 76.7000))),
            new ClassifiedRoadAxis("NH-85", "NH-85", "Kochi-Munnar-Theni-Madurai-Tondi Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(9.9312, 76.2673), new GeoPoint(10.0800, 77.0600), new GeoPoint(10.0100, 77.4800),
                    new GeoPoint(9.9252, 78.1198), new GeoPoint(9.7400, 79.0200))),
            new ClassifiedRoadAxis("NH-68", "NH-68", "Jaisalmer-Barmer-Sanchore-Radhanpur Border Highway", TransportCategory.NATIONAL_HIGHWAY, "PAN_INDIA", List.of(
                    new GeoPoint(26.9157, 70.9083), new GeoPoint(25.7463, 71.3967), new GeoPoint(24.7500, 71.7700),
                    new GeoPoint(23.8300, 71.6000)))
    );

    private static final List<ClassifiedRoadAxis> STATE_HIGHWAY_AXES = List.of(
            // Uttar Pradesh State Highways & MDRs
            new ClassifiedRoadAxis("SH-UP-30", "NH-927 / SH-30", "Uttar Pradesh State Highway 30 / NH-927 (Barabanki-Ramnagar-Bahraich Corridor)", TransportCategory.STATE_HIGHWAY, "UP", List.of(
                    new GeoPoint(26.9250, 81.1850), new GeoPoint(27.0850, 81.4050), new GeoPoint(27.1700, 81.2200), new GeoPoint(27.5750, 81.6000))),
            new ClassifiedRoadAxis("SH-UP-13", "SH-13", "Uttar Pradesh State Highway 13 (Barabanki-Haidergarh-Raebareli Line)", TransportCategory.STATE_HIGHWAY, "UP", List.of(
                    new GeoPoint(26.9250, 81.1850), new GeoPoint(26.5800, 81.3300), new GeoPoint(26.2300, 81.2400))),
            new ClassifiedRoadAxis("SH-UP-25", "SH-25", "Uttar Pradesh State Highway 25 (Lucknow-Malihabad-Hardoi-Shahjahanpur)", TransportCategory.STATE_HIGHWAY, "UP", List.of(
                    new GeoPoint(26.8467, 80.9462), new GeoPoint(26.9200, 80.7100), new GeoPoint(27.0700, 80.5100), new GeoPoint(27.3900, 80.1300), new GeoPoint(27.8800, 79.9100))),
            new ClassifiedRoadAxis("SH-UP-40", "SH-40", "Uttar Pradesh State Highway 40 (Mohanlalganj-Mauranipur Line)", TransportCategory.STATE_HIGHWAY, "UP", List.of(
                    new GeoPoint(26.6800, 80.9800), new GeoPoint(26.2200, 80.5000), new GeoPoint(25.4500, 79.1500))),
            new ClassifiedRoadAxis("SH-UP-57", "SH-57", "Delhi-Baghpat-Baraut-Shamli-Saharanpur Highway", TransportCategory.STATE_HIGHWAY, "UP", List.of(
                    new GeoPoint(28.7000, 77.2500), new GeoPoint(28.9500, 77.2200), new GeoPoint(29.1000, 77.2600), new GeoPoint(29.4500, 77.3100), new GeoPoint(29.9700, 77.5500))),
            new ClassifiedRoadAxis("MDR-UP-108W", "MDR-108W", "Barabanki-Rudauli Agro Processing Corridor", TransportCategory.MAJOR_DISTRICT_ROAD, "UP", List.of(
                    new GeoPoint(26.9250, 81.1850), new GeoPoint(26.7800, 81.5500), new GeoPoint(26.7500, 81.7500))),
            new ClassifiedRoadAxis("MDR-UP-115W", "MDR-115W", "Ramsanehighat Mandi Feeder Link", TransportCategory.MAJOR_DISTRICT_ROAD, "UP", List.of(
                    new GeoPoint(26.8000, 81.5500), new GeoPoint(26.6500, 81.6000))),

            // Maharashtra State Highways & MDRs
            new ClassifiedRoadAxis("SH-MH-70", "SH-70", "Maharashtra State Highway 70 (Wai-Surur-Shirwal-Bhor)", TransportCategory.STATE_HIGHWAY, "MH", List.of(
                    new GeoPoint(17.9520, 73.9150), new GeoPoint(18.0100, 73.9900), new GeoPoint(18.1520, 73.9780), new GeoPoint(18.1500, 73.8450))),
            new ClassifiedRoadAxis("SH-MH-58", "SH-58", "Maharashtra State Highway 58 (Satara-Khandala-Shirwal-Pune)", TransportCategory.STATE_HIGHWAY, "MH", List.of(
                    new GeoPoint(17.6800, 74.0100), new GeoPoint(18.0500, 74.0200), new GeoPoint(18.1520, 73.9780), new GeoPoint(18.5204, 73.8567))),
            new ClassifiedRoadAxis("SH-MH-65", "SH-65", "Maharashtra State Highway 65 (Mahad-Varandha Ghat-Bhor-Shirwal)", TransportCategory.STATE_HIGHWAY, "MH", List.of(
                    new GeoPoint(18.0800, 73.4200), new GeoPoint(18.1500, 73.8450), new GeoPoint(18.1520, 73.9780))),
            new ClassifiedRoadAxis("SH-MH-10", "SH-10", "Daund-Baramati-Phaltan-Lonand Agro Corridor", TransportCategory.STATE_HIGHWAY, "MH", List.of(
                    new GeoPoint(18.4600, 74.6000), new GeoPoint(18.1500, 74.5800), new GeoPoint(17.9850, 74.4300), new GeoPoint(17.9500, 74.1900))),
            new ClassifiedRoadAxis("SH-MH-27", "SH-27", "Pune-Chakan-Manchar-Narayangaon Highway", TransportCategory.STATE_HIGHWAY, "MH", List.of(
                    new GeoPoint(18.5204, 73.8567), new GeoPoint(18.7600, 73.8600), new GeoPoint(19.0000, 73.9400), new GeoPoint(19.1200, 73.9800))),
            new ClassifiedRoadAxis("MDR-MH-44", "MDR-44", "Karad-Koregaon Mandi Feeder", TransportCategory.MAJOR_DISTRICT_ROAD, "MH", List.of(
                    new GeoPoint(17.2850, 74.1850), new GeoPoint(17.5000, 74.1500), new GeoPoint(17.7000, 74.1800))),

            // Haryana & Delhi NCR State Highways
            new ClassifiedRoadAxis("SH-HR-26", "SH-26", "Haryana State Highway 26 (Gurugram-Manesar-Pataudi-Rewari)", TransportCategory.STATE_HIGHWAY, "HR", List.of(
                    new GeoPoint(28.4500, 77.0200), new GeoPoint(28.3550, 76.9400), new GeoPoint(28.3200, 76.7800), new GeoPoint(28.1800, 76.6200))),
            new ClassifiedRoadAxis("SH-HR-15", "SH-15", "Haryana State Highway 15 (Gurugram-Sohna-Nuh-Alwar)", TransportCategory.STATE_HIGHWAY, "HR", List.of(
                    new GeoPoint(28.4500, 77.0200), new GeoPoint(28.2500, 77.0600), new GeoPoint(28.1000, 77.0000), new GeoPoint(27.5600, 76.6000))),
            new ClassifiedRoadAxis("SH-HR-28", "SH-28", "Ballabhgarh-Sohna-Tauru Highway", TransportCategory.STATE_HIGHWAY, "HR", List.of(
                    new GeoPoint(28.3400, 77.3200), new GeoPoint(28.2500, 77.0600), new GeoPoint(28.2100, 76.9500))),
            new ClassifiedRoadAxis("MDR-HR-132", "MDR-132", "Dharuhera-Bawal Industrial Connector", TransportCategory.MAJOR_DISTRICT_ROAD, "HR", List.of(
                    new GeoPoint(28.2000, 76.7900), new GeoPoint(28.1500, 76.6500), new GeoPoint(28.0800, 76.5800))),

            // Rajasthan State Highways
            new ClassifiedRoadAxis("SH-RJ-19", "SH-19", "Rajasthan State Highway 19 (Bhiwadi-Tijara-Kishangarh-Alwar)", TransportCategory.STATE_HIGHWAY, "RJ", List.of(
                    new GeoPoint(28.2100, 76.8600), new GeoPoint(28.0300, 76.8500), new GeoPoint(27.8200, 76.7200), new GeoPoint(27.5600, 76.6000))),
            new ClassifiedRoadAxis("SH-RJ-37", "SH-37", "Rajasthan State Highway 37 (Kotputli-Neemrana-Behror-Narnaul)", TransportCategory.STATE_HIGHWAY, "RJ", List.of(
                    new GeoPoint(27.7000, 76.2000), new GeoPoint(27.9900, 76.3800), new GeoPoint(27.8900, 76.2800), new GeoPoint(28.0400, 76.1100))),
            new ClassifiedRoadAxis("SH-RJ-8", "SH-8", "Jaipur-Jobner-Kuchaman-Nagaur Highway", TransportCategory.STATE_HIGHWAY, "RJ", List.of(
                    new GeoPoint(26.9124, 75.7873), new GeoPoint(26.9700, 75.3800), new GeoPoint(27.1500, 74.8600), new GeoPoint(27.2000, 73.7400))),
            new ClassifiedRoadAxis("MDR-RJ-85", "MDR-85", "Kishangarh Marble-Rupangarh Agro Feeder", TransportCategory.MAJOR_DISTRICT_ROAD, "RJ", List.of(
                    new GeoPoint(26.5700, 74.8600), new GeoPoint(26.7900, 74.9600))),

            // Gujarat State Highways
            new ClassifiedRoadAxis("SH-GJ-6", "SH-6", "Gujarat State Highway 6 (Ahmedabad-Sanand-Viramgam)", TransportCategory.STATE_HIGHWAY, "GJ", List.of(
                    new GeoPoint(23.0200, 72.5700), new GeoPoint(22.9850, 72.3800), new GeoPoint(23.1200, 72.0300))),
            new ClassifiedRoadAxis("SH-GJ-17", "SH-17", "Gujarat State Highway 17 (Ahmedabad-Sarkhej-Dholka)", TransportCategory.STATE_HIGHWAY, "GJ", List.of(
                    new GeoPoint(23.0200, 72.5700), new GeoPoint(22.9800, 72.5000), new GeoPoint(22.7200, 72.4400))),
            new ClassifiedRoadAxis("SH-GJ-5", "SH-5", "Bharuch-Ankleshwar-Dahej Port Highway", TransportCategory.STATE_HIGHWAY, "GJ", List.of(
                    new GeoPoint(21.7000, 72.9900), new GeoPoint(21.6260, 73.0150), new GeoPoint(21.7100, 72.5800))),

            // Karnataka State Highways
            new ClassifiedRoadAxis("SH-KA-35", "SH-35", "Karnataka State Highway 35 (Hoskote-Kadugodi-Whitefield-Anekal)", TransportCategory.STATE_HIGHWAY, "KA", List.of(
                    new GeoPoint(13.0700, 77.7980), new GeoPoint(12.9900, 77.7600), new GeoPoint(12.9700, 77.7500), new GeoPoint(12.7100, 77.7000))),
            new ClassifiedRoadAxis("SH-KA-39", "SH-39", "Bengaluru-Nelamangala-Doddaballapura Industrial Link", TransportCategory.STATE_HIGHWAY, "KA", List.of(
                    new GeoPoint(13.0900, 77.3900), new GeoPoint(13.2000, 77.4500), new GeoPoint(13.2900, 77.5400))),
            new ClassifiedRoadAxis("SH-KA-96", "SH-96", "Devanahalli KIA Airport-Vijayapura-Kolar Line", TransportCategory.STATE_HIGHWAY, "KA", List.of(
                    new GeoPoint(13.2483, 77.7126), new GeoPoint(13.2900, 77.8000), new GeoPoint(13.1367, 78.1340))),

            // Tamil Nadu State Highways
            new ClassifiedRoadAxis("SH-TN-57", "SH-57", "Tamil Nadu State Highway 57 (Sriperumbudur-Singaperumalkoil)", TransportCategory.STATE_HIGHWAY, "TN", List.of(
                    new GeoPoint(12.9667, 79.9500), new GeoPoint(12.8400, 79.9400), new GeoPoint(12.7600, 79.9900))),
            new ClassifiedRoadAxis("SH-TN-48", "SH-48", "Chennai-Kanchipuram-Arakkonam Highway", TransportCategory.STATE_HIGHWAY, "TN", List.of(
                    new GeoPoint(13.0800, 80.2000), new GeoPoint(12.8300, 79.7000), new GeoPoint(13.0800, 79.6700))),

            // Assam State Highways & MDRs
            new ClassifiedRoadAxis("SH-AS-19", "SH-19", "Assam State Highway 19 (Diphu-Howraghat-Dokmoka)", TransportCategory.STATE_HIGHWAY, "AS", List.of(
                    new GeoPoint(25.8450, 93.4380), new GeoPoint(26.0120, 93.3680), new GeoPoint(26.3400, 93.4950))),
            new ClassifiedRoadAxis("SH-AS-35", "SH-35", "Assam State Highway 35 (Golaghat-Bokakhat)", TransportCategory.STATE_HIGHWAY, "AS", List.of(
                    new GeoPoint(26.5150, 93.9650), new GeoPoint(26.5800, 93.7500), new GeoPoint(26.6380, 93.5980))),
            new ClassifiedRoadAxis("MDR-AS-101", "MDR-101", "Diphu-Manja Agro Feed Line", TransportCategory.MAJOR_DISTRICT_ROAD, "AS", List.of(
                    new GeoPoint(25.8450, 93.4380), new GeoPoint(25.8780, 93.4380)))
    );

    private IndianDistrictsCatalogService districtsCatalog;

    @Autowired(required = false)
    public void setDistrictsCatalog(IndianDistrictsCatalogService districtsCatalog) {
        this.districtsCatalog = districtsCatalog;
    }

    public record ResolvedIntermediateHub(
            String id,
            String name,
            GeoPoint location
    ) {}

    public static final StudyRegion PAN_INDIA_BOUNDS = new StudyRegion(
            "Pan-India Dynamic Greenfield Routing Engine (All-India Mainland)",
            6.50,
            68.00,
            37.50,
            97.50,
            new GeoPoint(26.8500, 75.8000),
            new GeoPoint(26.4500, 74.6500)
    );

    public boolean isWithinIndiaBounds(GeoPoint point) {
        return point != null && PAN_INDIA_BOUNDS.contains(point);
    }

    public Map<String, Object> routeDynamicPanIndia(
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            FacilityMode facilityMode,
            Double corridorBufferDeg,
            Double maxDeviationPercent) {
        return routeDynamicPanIndia(
                requestedStart,
                requestedGoal,
                facilityMode,
                corridorBufferDeg,
                maxDeviationPercent,
                List.of());
    }

    public Map<String, Object> routeDynamicPanIndia(
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            FacilityMode facilityMode,
            Double corridorBufferDeg,
            Double maxDeviationPercent,
            List<String> targetHubs) {
        return routeDynamicPanIndia(
                requestedStart,
                requestedGoal,
                facilityMode,
                corridorBufferDeg,
                maxDeviationPercent,
                targetHubs,
                true,
                null);
    }

    public Map<String, Object> routeDynamicPanIndia(
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            FacilityMode facilityMode,
            Double corridorBufferDeg,
            Double maxDeviationPercent,
            List<String> targetHubs,
            Boolean autoConnectHubs,
            Integer maxAutoHubs) {
        return routeDynamicPanIndia(
                requestedStart,
                requestedGoal,
                facilityMode,
                corridorBufferDeg,
                maxDeviationPercent,
                targetHubs,
                autoConnectHubs,
                maxAutoHubs,
                in.marg.routing.AlignmentPreference.BALANCED);
    }

    public Map<String, Object> routeDynamicPanIndia(
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            FacilityMode facilityMode,
            Double corridorBufferDeg,
            Double maxDeviationPercent,
            List<String> targetHubs,
            Boolean autoConnectHubs,
            Integer maxAutoHubs,
            in.marg.routing.AlignmentPreference preference) {
        return routeDynamicPanIndia(
                requestedStart,
                requestedGoal,
                facilityMode,
                corridorBufferDeg,
                maxDeviationPercent,
                targetHubs,
                autoConnectHubs,
                maxAutoHubs,
                preference,
                false);
    }

    public Map<String, Object> routeDynamicPanIndia(
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            FacilityMode facilityMode,
            Double corridorBufferDeg,
            Double maxDeviationPercent,
            List<String> targetHubs,
            Boolean autoConnectHubs,
            Integer maxAutoHubs,
            in.marg.routing.AlignmentPreference preference,
            Boolean deep) {
        return routeDynamicPanIndia(
                requestedStart,
                requestedGoal,
                facilityMode,
                corridorBufferDeg,
                maxDeviationPercent,
                targetHubs,
                autoConnectHubs,
                maxAutoHubs,
                preference,
                deep,
                null);
    }

    public Map<String, Object> routeDynamicPanIndia(
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            FacilityMode facilityMode,
            Double corridorBufferDeg,
            Double maxDeviationPercent,
            List<String> targetHubs,
            Boolean autoConnectHubs,
            Integer maxAutoHubs,
            in.marg.routing.AlignmentPreference preference,
            Boolean deep,
            Double maxVcRatio) {

        if (!isWithinIndiaBounds(requestedStart) || !isWithinIndiaBounds(requestedGoal)) {
            throw new IllegalArgumentException(
                    "Both endpoints must lie within Pan-India bounds (lat 6.5..37.5 N, lon 68.0..97.5 E).");
        }

        in.marg.routing.AlignmentPreference pref = preference != null
                ? preference
                : in.marg.routing.AlignmentPreference.BALANCED;

        Double effectiveMaxDev = maxDeviationPercent;
        if (effectiveMaxDev == null) {
            if (pref.environmentalWeight() >= 2.0) {
                effectiveMaxDev = 25.0;
            } else if (pref.directnessWeight() >= 2.0) {
                effectiveMaxDev = 10.0;
            }
        }

        FacilityMode mode = facilityMode != null ? facilityMode : FacilityMode.EXPRESSWAY;
        double midLon = (requestedStart.lon() + requestedGoal.lon()) / 2.0;
        int utmZone = Wgs84UtmProjection.indiaUtmZoneForLongitude(midLon);
        Wgs84UtmProjection projection = new Wgs84UtmProjection(utmZone);
        GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
        RightOfWayCorridorEngine rowEngine = new RightOfWayCorridorEngine(projection);
        EndpointSnapService snapService = new EndpointSnapService(projection);

        OsmDynamicBuildingSettlementCache.OsmCorridorIndex osmIndex =
                OsmDynamicBuildingSettlementCache.getDefaultInstance()
                        .indexForCorridor(requestedStart, requestedGoal, projection);

        in.marg.transport.OsmDynamicTransportNetworkCache.OsmTransportIndex osmTransportIndex = null;
        if (Boolean.TRUE.equals(deep)) {
            osmTransportIndex = in.marg.transport.OsmDynamicTransportNetworkCache.getDefaultInstance()
                    .indexArteriesForCorridor(requestedStart, requestedGoal, projection);
        }

        PanIndiaDynamicCostLayer panIndiaSurface = new PanIndiaDynamicCostLayer(
                requestedStart, requestedGoal, mode, pref, osmIndex, osmTransportIndex, false);

        double bufferDeg = corridorBufferDeg != null
                ? Math.max(0.05, Math.min(0.30, corridorBufferDeg))
                : 0.12;

        // Resolve/snap endpoints off hospitals, institutional compounds, colonies, buildings, urban cores (EXPRESSWAY) & protected areas
        EndpointSnapService.EndpointObstacleEvaluator osmObstacleEval =
                EndpointSnapService.forDynamicOsmIndex(osmIndex, osmTransportIndex, mode);
        EndpointSnapService.CandidateTieInScorer dynamicTieInScorer =
                EndpointSnapService.forDynamicRoadTieIn(osmTransportIndex, osmIndex, mode);
        EndpointSnapService.EndpointResolution startSnap = snapService.resolveEndpoints(
                PAN_INDIA_BOUNDS,
                null,
                panIndiaSurface,
                requestedStart,
                requestedGoal,
                osmObstacleEval,
                dynamicTieInScorer);

        GeoPoint start = startSnap.resolvedStart();
        GeoPoint goal = startSnap.resolvedGoal();

        // Strict traffic carrying-capacity cap (never exceed 100% or the user's selected Traffic Comfort Preference)
        double effectiveMaxVc = maxVcRatio != null ? Math.min(1.0, maxVcRatio) : 0.75;
        int maxVcPercent = (int) Math.round(effectiveMaxVc * 100.0);
        String comfortProfileName = in.marg.transport.HubEvaluationService.describeComfortProfile(effectiveMaxVc);
        double designCapacityPcuHr = mode == FacilityMode.EXPRESSWAY ? 4800.0 : 3600.0;
        double baseThroughTrafficPcuHr = mode == FacilityMode.EXPRESSWAY ? 1400.0 : 1350.0;
        double currentVolumePcuHr = baseThroughTrafficPcuHr;

        List<String> droppedUserHubNames = new ArrayList<>();
        List<String> keptUserHubNames = new ArrayList<>();
        List<String> capacityRejectedAutoHubNames = new ArrayList<>();
        double peakRejectedUserVc = 0.0;

        // Step 1: Resolve requested user intermediate hubs FIRST (highest priority), strictly enforcing effectiveMaxVc
        List<ResolvedIntermediateHub> resolvedHubs = new ArrayList<>();
        if (targetHubs != null && !targetHubs.isEmpty()) {
            for (String raw : targetHubs) {
                if (raw == null || raw.isBlank()) continue;
                String token = raw.trim();
                ResolvedIntermediateHub candidateHub = null;
                double inducedPcuHr = 45.0 * 9.5; // Default ~45k town
                if (token.matches("^[-+]?[0-9]*\\.?[0-9]+[ ,;]+[-+]?[0-9]*\\.?[0-9]+$")) {
                    String[] parts = token.split("[ ,;]+");
                    try {
                        double p1 = Double.parseDouble(parts[0]);
                        double p2 = Double.parseDouble(parts[1]);
                        double lat = (p1 >= 6.0 && p1 <= 38.0) ? p1 : p2;
                        double lon = (p1 >= 6.0 && p1 <= 38.0) ? p2 : p1;
                        candidateHub = new ResolvedIntermediateHub("HUB-" + resolvedHubs.size(), "Hub Point", new GeoPoint(lat, lon));
                    } catch (Exception ignored) {}
                } else if (districtsCatalog != null) {
                    var optDist = districtsCatalog.lookup(token);
                    if (optDist.isPresent()) {
                        DistrictRecord dr = optDist.get();
                        candidateHub = new ResolvedIntermediateHub(dr.id(), dr.displayLabel(), dr.toGeoPoint());
                        inducedPcuHr = estimatePanIndiaHubPcuHr(dr);
                    }
                }
                if (candidateHub == null) continue;
                double projectedVolume = currentVolumePcuHr + inducedPcuHr;
                double vcAfter = projectedVolume / designCapacityPcuHr;
                if (vcAfter > effectiveMaxVc + 1e-9) {
                    droppedUserHubNames.add(candidateHub.name());
                    peakRejectedUserVc = Math.max(peakRejectedUserVc, vcAfter);
                    continue;
                }
                currentVolumePcuHr = projectedVolume;
                resolvedHubs.add(candidateHub);
                keptUserHubNames.add(candidateHub.name());
            }
        }

        // Step 2: Autonomous discovery of intermediate districts/towns along corridor up to total cap = autoLimit,
        // prioritizing user-added hubs and strictly enforcing effectiveMaxVc
        boolean enableAuto = autoConnectHubs == null || Boolean.TRUE.equals(autoConnectHubs);
        int autoLimit = (maxAutoHubs != null && maxAutoHubs >= 0) ? maxAutoHubs : 2;
        if (enableAuto && districtsCatalog != null && autoLimit > 0) {
            int needed = autoLimit - resolvedHubs.size();
            if (needed > 0) {
                double dVx = goal.lon() - start.lon();
                double dVy = goal.lat() - start.lat();
                double vLen = Math.hypot(dVx, dVy);
                if (vLen > 0.12) { // Discover along corridors > ~13 km span
                    // Build guide polyline (start -> any user-selected hubs ordered along corridor -> goal)
                    // so neighbouring cities are discovered along the actual curved route, not only the bare chord.
                    List<GeoPoint> guideChain = new ArrayList<>();
                    guideChain.add(start);
                    if (!resolvedHubs.isEmpty()) {
                        double lenSq = dVx * dVx + dVy * dVy;
                        List<ResolvedIntermediateHub> orderedUserHubs = new ArrayList<>(resolvedHubs);
                        orderedUserHubs.sort(Comparator.comparingDouble(h -> {
                            if (lenSq <= 1e-12) return 0.0;
                            return ((h.location().lon() - start.lon()) * dVx + (h.location().lat() - start.lat()) * dVy) / lenSq;
                        }));
                        for (ResolvedIntermediateHub uh : orderedUserHubs) {
                            guideChain.add(uh.location());
                        }
                    }
                    guideChain.add(goal);

                    Set<String> alreadyChosenIds = new HashSet<>();
                    Set<String> alreadyChosenNames = new HashSet<>();
                    List<GeoPoint> chosenPoints = new ArrayList<>();
                    for (ResolvedIntermediateHub h : resolvedHubs) {
                        alreadyChosenIds.add(h.id().toLowerCase(Locale.ROOT));
                        alreadyChosenNames.add(normalizeHubNameKey(h.name()));
                        chosenPoints.add(h.location());
                    }

                    // Scale terminal setback and inter-hub spacing proportionally to corridor span
                    double endpointSetbackDeg = Math.min(0.18, Math.max(0.05, vLen * 0.12));
                    double minHubSpacingDeg = Math.min(0.12, Math.max(0.06, vLen / (autoLimit + 2.5)));

                    // Progressive discovery passes:
                    // Pass 1: District HQs within ~38 km of corridor
                    // Pass 2: Prominent towns/tehsils/subdivisions within ~31 km of corridor
                    // Pass 3: Wider-reach District HQs & towns within ~60 km for sparse corridors
                    double[][] passConfigs = new double[][]{
                            {1.0, 0.35},
                            {0.0, 0.28},
                            {0.0, 0.55}
                    };

                    int added = 0;
                    for (double[] pass : passConfigs) {
                        if (added >= needed) break;
                        boolean districtsOnly = pass[0] > 0.5;
                        double maxLateralDeg = pass[1];

                        List<DistrictRecord> candidates = new ArrayList<>();
                        for (DistrictRecord dr : districtsCatalog.getAllDistricts()) {
                            if (districtsOnly && !dr.isDistrict()) continue;
                            if (alreadyChosenIds.contains(dr.id().toLowerCase(Locale.ROOT))) continue;
                            if (alreadyChosenNames.contains(normalizeHubNameKey(dr.name()))) continue;

                            GeoPoint pt = dr.toGeoPoint();
                            double distToStartDeg = Math.hypot(pt.lon() - start.lon(), pt.lat() - start.lat());
                            double distToGoalDeg = Math.hypot(pt.lon() - goal.lon(), pt.lat() - goal.lat());
                            if (distToStartDeg <= endpointSetbackDeg || distToGoalDeg <= endpointSetbackDeg) {
                                continue;
                            }

                            double distToCorridorDeg = minDistanceToPolylineDeg(pt, guideChain);
                            if (distToCorridorDeg <= maxLateralDeg) {
                                candidates.add(dr);
                            }
                        }

                        candidates.sort(Comparator.comparingDouble((DistrictRecord dr) -> {
                            double d = minDistanceToPolylineDeg(dr.toGeoPoint(), guideChain);
                            double levelPenalty = dr.isDistrict() ? 0.0 : 0.08;
                            return d + dr.tier() * 0.04 + levelPenalty;
                        }));

                        for (DistrictRecord dr : candidates) {
                            if (added >= needed) break;
                            String normName = normalizeHubNameKey(dr.name());
                            if (alreadyChosenNames.contains(normName)) continue;

                            GeoPoint pt = dr.toGeoPoint();
                            boolean tooClose = false;
                            for (GeoPoint existing : chosenPoints) {
                                if (Math.hypot(pt.lon() - existing.lon(), pt.lat() - existing.lat()) < minHubSpacingDeg) {
                                    tooClose = true;
                                    break;
                                }
                            }
                            if (tooClose) continue;

                            double inducedPcuHr = estimatePanIndiaHubPcuHr(dr);
                            double projectedVolume = currentVolumePcuHr + inducedPcuHr;
                            double projectedVc = projectedVolume / designCapacityPcuHr;
                            if (projectedVc > effectiveMaxVc + 1e-9) {
                                if (!capacityRejectedAutoHubNames.contains(dr.displayLabel())) {
                                    capacityRejectedAutoHubNames.add(dr.displayLabel());
                                }
                                continue;
                            }

                            currentVolumePcuHr = projectedVolume;
                            resolvedHubs.add(new ResolvedIntermediateHub(
                                    dr.id(),
                                    dr.displayLabel() + " (Auto-Discovered)",
                                    pt));
                            alreadyChosenIds.add(dr.id().toLowerCase(Locale.ROOT));
                            alreadyChosenNames.add(normName);
                            chosenPoints.add(pt);
                            added++;
                        }
                    }
                }
            }
        }

        double finalVcRatio = currentVolumePcuHr / designCapacityPcuHr;
        int finalVcPercent = (int) Math.round(finalVcRatio * 100.0);
        List<String> keptHubNames = resolvedHubs.stream().map(ResolvedIntermediateHub::name).toList();
        List<Map<String, Object>> trafficCapacityAlerts = new ArrayList<>();
        if (!droppedUserHubNames.isEmpty()) {
            String droppedList = String.join(", ", droppedUserHubNames);
            String hint = effectiveMaxVc < 0.84
                    ? " Switch to 'Connect More Towns' (85% capacity) or remove another city to include it."
                    : " Try Expressway mode (higher capacity) or select fewer major cities.";
            trafficCapacityAlerts.add(new in.marg.transport.HubEvaluationService.CapacityAlert(
                    "USER_CITY_REMOVED_FOR_CAPACITY",
                    "warning",
                    "City Removed to Respect Traffic Comfort Limit",
                    "Removed " + droppedList + " because connecting "
                            + (droppedUserHubNames.size() == 1 ? "it" : "them")
                            + " would push road usage to " + Math.round(peakRejectedUserVc * 100.0)
                            + "%, exceeding your " + maxVcPercent + "% (" + comfortProfileName
                            + ") limit. Road usage is kept at " + finalVcPercent + "%." + hint,
                    List.copyOf(droppedUserHubNames),
                    keptHubNames,
                    maxVcPercent,
                    finalVcPercent
            ).toMap());
        }
        if (enableAuto && maxAutoHubs != null && resolvedHubs.size() < autoLimit && !capacityRejectedAutoHubNames.isEmpty()) {
            List<String> skippedPreview = capacityRejectedAutoHubNames.size() > 3
                    ? capacityRejectedAutoHubNames.subList(0, 3)
                    : capacityRejectedAutoHubNames;
            String priorityNote = !keptUserHubNames.isEmpty()
                    ? ", prioritizing your added " + (keptUserHubNames.size() == 1 ? "city (" : "cities (")
                            + String.join(", ", keptUserHubNames) + ")"
                    : "";
            String hint = effectiveMaxVc < 0.84
                    ? " Switch to 'Connect More Towns' (85% capacity) if you want more towns connected."
                    : "";
            trafficCapacityAlerts.add(new in.marg.transport.HubEvaluationService.CapacityAlert(
                    "AUTO_CITIES_REDUCED_FOR_CAPACITY",
                    "warning",
                    "Connected Cities Reduced to Prevent Road Overcrowding",
                    "Connected " + resolvedHubs.size() + " of " + autoLimit + " requested "
                            + (autoLimit == 1 ? "city" : "cities")
                            + priorityNote
                            + " (skipped " + String.join(", ", skippedPreview)
                            + ") so road usage stays at " + finalVcPercent + "% — within your "
                            + maxVcPercent + "% (" + comfortProfileName + ") traffic limit." + hint,
                    List.copyOf(capacityRejectedAutoHubNames),
                    keptHubNames,
                    maxVcPercent,
                    finalVcPercent
            ).toMap());
        }

        // Order intermediate hubs along corridor vector
        if (!resolvedHubs.isEmpty()) {
            double dVx = goal.lon() - start.lon();
            double dVy = goal.lat() - start.lat();
            double lenSq = dVx * dVx + dVy * dVy;
            resolvedHubs.sort(Comparator.comparingDouble(h -> {
                if (lenSq <= 1e-12) return 0.0;
                return ((h.location().lon() - start.lon()) * dVx + (h.location().lat() - start.lat()) * dVy) / lenSq;
            }));
        }

        // Find nearest navigable existing highway tie-ins for origin & destination (IRC:SP:99 / IRC:SP:84 compliant)
        HighwayTieInResult startTieIn = findNearestHighwayTieIn(start, panIndiaSurface, projection, snapService, osmTransportIndex, osmIndex, mode);
        HighwayTieInResult goalTieIn = findNearestHighwayTieIn(goal, panIndiaSurface, projection, snapService, osmTransportIndex, osmIndex, mode);

        List<GeoPoint> baseline = new ArrayList<>();
        List<GeoPoint> mainline = new ArrayList<>();

        List<GeoPoint> controlWaypoints = new ArrayList<>();
        controlWaypoints.add(start);
        for (ResolvedIntermediateHub h : resolvedHubs) {
            // IRC:SP:84 / IRC:SP:99 Standard: 120 km/h access-controlled expressway bypasses town core
            // and river floodplains by 4-6 km, connecting the town via grade-separated feeder spur.
            GeoPoint hubLoc = h.location();
            double dVx = goal.lon() - start.lon();
            double dVy = goal.lat() - start.lat();
            double vLen = Math.hypot(dVx, dVy);
            GeoPoint bypassPt;
            if (vLen > 1e-6) {
                double uVx = dVx / vLen;
                double uVy = dVy / vLen;
                double projDist = (hubLoc.lon() - start.lon()) * uVx + (hubLoc.lat() - start.lat()) * uVy;
                double projLon = start.lon() + projDist * uVx;
                double projLat = start.lat() + projDist * uVy;
                double perpX = hubLoc.lon() - projLon;
                double perpY = hubLoc.lat() - projLat;
                double perpDistDeg = Math.hypot(perpX, perpY);
                double bypassMarginDeg = 0.045; // ~5.0 km bypass buffer away from town core & riverbanks
                if (perpDistDeg > bypassMarginDeg) {
                    // Pull mainline towards hub but hold back at 5 km bypass margin
                    double scale = (perpDistDeg - bypassMarginDeg) / perpDistDeg;
                    bypassPt = new GeoPoint(projLat + perpY * scale, projLon + perpX * scale);
                } else {
                    // Town is directly on the axis: shift bypass 4 km to the side to avoid urban demolition
                    double normX = -uVy;
                    double normY = uVx;
                    bypassPt = new GeoPoint(hubLoc.lat() + normY * bypassMarginDeg, hubLoc.lon() + normX * bypassMarginDeg);
                }
            } else {
                bypassPt = hubLoc;
            }
            controlWaypoints.add(bypassPt);
        }
        controlWaypoints.add(goal);

        int totalStagesExecuted = 0;
        for (int cw = 0; cw < controlWaypoints.size() - 1; cw++) {
            GeoPoint segStart = controlWaypoints.get(cw);
            GeoPoint segGoal = controlWaypoints.get(cw + 1);

            double dLat = Math.abs(segGoal.lat() - segStart.lat());
            double dLon = Math.abs(segGoal.lon() - segStart.lon());
            int stages = Math.max(1, (int) Math.ceil(Math.max(dLat, dLon) / MAX_SINGLE_STAGE_SPAN_DEG));
            totalStagesExecuted += stages;

            List<GeoPoint> stageWaypoints = buildStageWaypoints(
                    segStart, segGoal, stages, panIndiaSurface, snapService, bufferDeg);

            for (int s = 0; s < stageWaypoints.size() - 1; s++) {
                GeoPoint stageStart = stageWaypoints.get(s);
                GeoPoint stageGoal = stageWaypoints.get(s + 1);
                double stageBuffer = Math.min(bufferDeg, 0.12);
                StudyRegion stageWindow = stageCorridorWindow(stageStart, stageGoal, stageBuffer);
                PanIndiaDynamicCostLayer stageSurface = new PanIndiaDynamicCostLayer(
                        stageStart, stageGoal, mode, pref, osmIndex, osmTransportIndex, false);

                List<GeoPoint> stageBaseline = router.route(
                        PAN_INDIA_BOUNDS,
                        stageStart,
                        stageGoal,
                        new DistanceOnlyCostSurface(),
                        ROUTING_RESOLUTION_METERS,
                        stageWindow);

                RouteConstraints constraints = RouteConstraints.forFacilityMode(null, mode);
                if (effectiveMaxDev != null && Double.isFinite(effectiveMaxDev) && effectiveMaxDev >= 0.0) {
                    double maxStageKm = router.routeLengthKm(stageBaseline) * (1.0 + effectiveMaxDev / 100.0);
                    constraints = RouteConstraints.forFacilityMode(maxStageKm, mode);
                }

                List<GeoPoint> stageMainline;
                try {
                    stageMainline = router.route(
                            PAN_INDIA_BOUNDS,
                            stageStart,
                            stageGoal,
                            stageSurface,
                            ROUTING_RESOLUTION_METERS,
                            stageWindow,
                            constraints);
                } catch (RuntimeException primaryFailed) {
                    // Last-resort urban colony barrier (1000.0x) while Hospitals & Sanctuaries stay Hard-Blocked
                    stageMainline = router.route(
                            PAN_INDIA_BOUNDS,
                            stageStart,
                            stageGoal,
                            stageSurface.withDenseColonyLastResortMode(),
                            ROUTING_RESOLUTION_METERS,
                            stageWindow,
                            constraints);
                }

                appendPathSegment(baseline, stageBaseline);
                appendPathSegment(mainline, stageMainline);
            }
        }

        // Route feasible building-safe A* road connectors joining user start/goal to nearest existing highway
        // For short segment reviews (e.g. Mode 2 corridor checks where start and goal are close together),
        // do not stitch distant regional tie-ins that exceed the corridor baseline distance itself.
        double directEndpointDistanceKm = projection.distanceMeters(start, goal) / 1000.0;
        double maxFeasibleTieInDistanceMeters = directEndpointDistanceKm < 15.0
                ? Math.min(6000.0, directEndpointDistanceKm * 400.0)
                : Double.POSITIVE_INFINITY;

        List<GeoPoint> startConnectorPath = List.of();
        double startConnectorKm = 0.0;
        if (startTieIn != null && startTieIn.distanceMeters() <= maxFeasibleTieInDistanceMeters) {
            if (startTieIn.distanceMeters() > DIRECT_ROAD_TIE_IN_THRESHOLD_METERS) {
                startConnectorPath = routeDynamicConnector(
                        router, snapService, mode, startTieIn.tieInPoint(), start, bufferDeg, osmIndex);
                startConnectorKm = router.routeLengthKm(startConnectorPath);
            } else if (!mainline.isEmpty()) {
                mainline.set(0, startTieIn.tieInPoint());
            }
        }

        List<GeoPoint> goalConnectorPath = List.of();
        double goalConnectorKm = 0.0;
        if (goalTieIn != null && goalTieIn.distanceMeters() <= maxFeasibleTieInDistanceMeters) {
            if (goalTieIn.distanceMeters() > DIRECT_ROAD_TIE_IN_THRESHOLD_METERS) {
                goalConnectorPath = routeDynamicConnector(
                        router, snapService, mode, goal, goalTieIn.tieInPoint(), bufferDeg, osmIndex);
                goalConnectorKm = router.routeLengthKm(goalConnectorPath);
            } else if (!mainline.isEmpty()) {
                mainline.set(mainline.size() - 1, goalTieIn.tieInPoint());
            }
        }

        if (!startConnectorPath.isEmpty() || !goalConnectorPath.isEmpty()) {
            List<GeoPoint> stitched = new ArrayList<>();
            if (!startConnectorPath.isEmpty()) {
                appendPathSegmentExact(stitched, startConnectorPath);
            }
            appendPathSegmentExact(stitched, mainline);
            if (!goalConnectorPath.isEmpty()) {
                appendPathSegmentExact(stitched, goalConnectorPath);
            }
            mainline = stitched;
        }
        in.marg.routing.HorizontalCurveSmoother.SmoothingResult curveSmoothing =
                in.marg.routing.HorizontalCurveSmoother.smoothAndAudit(
                        mainline,
                        panIndiaSurface,
                        null,
                        projection,
                        PAN_INDIA_BOUNDS,
                        in.marg.routing.HorizontalCurveSmoother.CurveProfile.forFacilityMode(mode));
        mainline = curveSmoothing.smoothedPath();
        Map<String, Object> horizontalGeometryAudit = curveSmoothing.report().toMap();

        double totalTerminalAccessRoadLengthKm = round(startConnectorKm + goalConnectorKm);

        double baselineLengthKm = router.routeLengthKm(baseline);
        double routeLengthKm = router.routeLengthKm(mainline);
        double deviationPercent = baselineLengthKm > 0.0
                ? ((routeLengthKm / baselineLengthKm) - 1.0) * 100.0
                : 0.0;

        RightOfWayCorridorEngine.CorridorFootprint rowFootprint =
                rowEngine.buildCorridorFootprint(mainline, mode);
        Map<String, Object> routeDiagnostics = new LinkedHashMap<>(panIndiaSurface.summarizeRouteDiagnostics(
                mainline, mode, projection, rowFootprint));
        routeDiagnostics.put("horizontalGeometryAudit", horizontalGeometryAudit);

        StudyRegion overallWindow = unionWindow(
                localWindowAround(start, bufferDeg),
                localWindowAround(goal, bufferDeg),
                bufferDeg);

        List<Map<String, Object>> features = buildGeoJsonFeatures(
                mainline, mode, routeLengthKm, baselineLengthKm, deviationPercent, projection,
                startTieIn, startConnectorPath, startConnectorKm,
                goalTieIn, goalConnectorPath, goalConnectorKm);

        double totalFeederSpurKm = 0.0;
        int dynamicInterchanges = 0;
        for (ResolvedIntermediateHub h : resolvedHubs) {
            GeoPoint hubPt = h.location();
            GeoPoint tieIn = mainline.stream()
                    .min(Comparator.comparingDouble(pt -> projection.distanceMeters(pt, hubPt)))
                    .orElse(null);
            if (tieIn != null) {
                double straightSpurKm = round(projection.distanceMeters(hubPt, tieIn) / 1000.0);
                List<GeoPoint> spurPath = routeDynamicConnector(router, snapService, mode, tieIn, hubPt, bufferDeg, osmIndex);
                double spurDistKm = spurPath.size() >= 2 ? round(router.routeLengthKm(spurPath)) : straightSpurKm;
                totalFeederSpurKm += spurDistKm;
                dynamicInterchanges++;

                HighwayTieInResult hubRoad = findNearestHighwayTieIn(hubPt, panIndiaSurface, projection, snapService, osmTransportIndex, osmIndex, mode);
                String tieInRoadRef = (hubRoad != null && hubRoad.distanceMeters() < 50000.0) ? hubRoad.roadRef() : "State Highway / MDR";
                String tieInRoadName = (hubRoad != null && hubRoad.distanceMeters() < 50000.0) ? hubRoad.roadName() : "Classified Feeder Road";
                String tieInRoadCategory = (hubRoad != null && hubRoad.category() != null) ? hubRoad.category().name() : "STATE_HIGHWAY";

                Map<String, Object> hubProps = new LinkedHashMap<>();
                hubProps.put("hubId", h.id());
                hubProps.put("name", h.name());
                hubProps.put("category", "INTERMEDIATE_DISTRICT_HUB");
                hubProps.put("tieInRoadCategory", tieInRoadCategory);
                hubProps.put("tieInRoadRef", tieInRoadRef);
                hubProps.put("tieInRoadName", tieInRoadName);
                hubProps.put("spurDistanceKm", spurDistKm);
                features.add(pointFeature(hubPt, "REGIONAL_SERVICE_HUB", hubProps));

                features.add(pointFeature(tieIn, "INTERCHANGE_JUNCTION", Map.of(
                        "junctionId", "INT-" + h.id(),
                        "connectingHub", h.name(),
                        "type", "TRUMPET_GRADE_SEPARATED",
                        "designSpeedKmph", 80.0,
                        "ircStandard", "IRC:SP:84-2019 / IRC:SP:99-2019"
                )));

                List<GeoPoint> spurLine = spurPath.size() >= 2 ? spurPath : List.of(hubPt, tieIn);
                Map<String, Object> spurProps = new LinkedHashMap<>();
                spurProps.put("corridorId", "SPUR-" + h.id());
                spurProps.put("connectingHub", h.name());
                spurProps.put("lengthKm", spurDistKm);
                spurProps.put("facilityMode", "DIVIDED_4_LANE_SPUR");
                spurProps.put("tieInRoadCategory", tieInRoadCategory);
                spurProps.put("tieInRoadRef", tieInRoadRef);
                spurProps.put("tieInRoadName", tieInRoadName);
                features.add(lineFeature(spurLine, "FEEDER_SPUR_HIGHWAY", spurProps));
            }
        }

        long actualRiverBridgesCount = features.stream()
                .filter(f -> "RIVER_BRIDGE".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                .count();
        long actualDrainageStructuresCount = features.stream()
                .filter(f -> "DRAINAGE_CROSSING_STRUCTURE".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                .count();

        // Evaluate exact OSM building demolition & RFCTLARR 2013 displacement along dynamic corridor
        double rowHalfWidthM = rowFootprint != null ? 0.5 * rowFootprint.mainlineRowWidthMeters() : 30.0;
        OsmDynamicBuildingSettlementCache.DynamicDemolitionSummary osmDemolition =
                osmIndex.evaluateRouteDemolitionImpact(mainline, rowHalfWidthM);
        for (OsmDynamicBuildingSettlementCache.IndexedOsmPoly bld : osmDemolition.demolishedBuildings()) {
            Map<String, Object> sczProps = new LinkedHashMap<>();
            sczProps.put("settlementId", bld.osmId());
            sczProps.put("settlementName", bld.name());
            sczProps.put("settlementClassification", "RURAL");
            sczProps.put("settlementSubtype", "OSM_BUILDING_IN_DIRECT_ROW");
            sczProps.put("buildingsInDirectRowCount", 1);
            sczProps.put("buildingsInDirectRowByType", Map.of(bld.category(), 1));
            sczProps.put("affectedDirectRowFootprintSqMeters", round(bld.areaSqM()));
            sczProps.put("recommendedEngineeringMitigation", "RFCTLARR_2013_STRUCTURE_COMPENSATION_AND_REHABILITATION");
            features.add(pointFeature(bld.centroid(), "SETTLEMENT_BUILDING_CONFLICT_ZONE", sczProps));
        }

        // Evaluate dynamic OSM grade-separated crossings (ROB, Flyover, VUP, LVUP)
        int railwayRobCount = 0;
        int roadFlyoverCount = 0;
        if (osmTransportIndex != null && !osmTransportIndex.isEmpty()) {
            if (mode == FacilityMode.EXPRESSWAY) {
                osmTransportIndex.loadMainlineLocalStreets(mainline);
            }
            List<TransportNetworkVectorIndex.RouteCrossing> osmCrossings =
                    osmTransportIndex.findRouteCrossings(mainline);
            for (TransportNetworkVectorIndex.RouteCrossing rc : osmCrossings) {
                if (mode == FacilityMode.NATIONAL_HIGHWAY && rc.category() != TransportCategory.RAILWAY) {
                    continue; // In National Highway mode, non-railway crossings remain at-grade
                }
                if (rc.category() == TransportCategory.RAILWAY) {
                    railwayRobCount++;
                } else {
                    roadFlyoverCount++;
                }
                Map<String, Object> gsProps = new LinkedHashMap<>();
                gsProps.put("featureRole", "GRADE_SEPARATED_CROSSING");
                gsProps.put("structureId", "GS-PAN-" + (railwayRobCount + roadFlyoverCount) + "-" + rc.corridorRef());
                gsProps.put("structureType", rc.category().defaultCrossingStructureType());
                gsProps.put("crossedCategory", rc.category().name());
                gsProps.put("crossedCorridorRef", rc.corridorRef());
                gsProps.put("crossedCorridorName", rc.corridorName());
                gsProps.put("chainageKm", round(rc.chainageKm()));
                gsProps.put("crossingAngleDegrees", round(rc.crossingAngleDegrees()));
                features.add(pointFeature(rc.point(), "GRADE_SEPARATED_CROSSING", gsProps));
            }
        }

        Map<String, Object> terminalRoadConnections = buildTerminalRoadConnectionsMetadata(
                startTieIn, startConnectorKm, start, startSnap.startSnapReason(),
                goalTieIn, goalConnectorKm, goal, startSnap.goalSnapReason(),
                totalTerminalAccessRoadLengthKm, mode);

        Map<String, Object> networkSummary = new LinkedHashMap<>();
        networkSummary.put("mainlineLengthKm", round(routeLengthKm));
        networkSummary.put("totalTerminalAccessRoadLengthKm", totalTerminalAccessRoadLengthKm);
        networkSummary.put("terminalInterchangesCount", terminalRoadConnections.get("terminalInterchangesCount"));
        networkSummary.put("localStreetViolationsCount", terminalRoadConnections.get("localStreetViolationsCount"));
        networkSummary.put("expresswayComplianceVerified", terminalRoadConnections.get("expresswayComplianceVerified"));
        networkSummary.put("dedicatedFeederSpursCount", resolvedHubs.size());
        networkSummary.put("totalDedicatedFeederSpurLengthKm", round(totalFeederSpurKm));
        networkSummary.put("totalConstructedHighwayAndSpurKm", round(routeLengthKm + totalFeederSpurKm));
        networkSummary.put("plannedInterchangesCount", Math.max(2, mainline.size() / 15) + dynamicInterchanges);
        networkSummary.put("riverBridgesCount", actualRiverBridgesCount);
        networkSummary.put("drainageStructuresCount", actualDrainageStructuresCount);
        networkSummary.put("railwayRobCount", railwayRobCount);
        networkSummary.put("roadFlyoverCount", roadFlyoverCount);
        networkSummary.put("gradeSeparatedCrossingsCount", railwayRobCount + roadFlyoverCount);
        if (osmTransportIndex != null) {
            networkSummary.put("osmDynamicIndexedRoadSegmentsCount", osmTransportIndex.totalIndexedSegments());
        }
        networkSummary.put("buildingsInDirectRowCount", osmDemolition.buildingsInDirectRowCount());
        networkSummary.put("buildingsInDirectRowByType", osmDemolition.buildingsByType());
        networkSummary.put("estimatedDirectlyDisplacedHouseholds", osmDemolition.estimatedDisplacedHouseholds());
        networkSummary.put("estimatedDirectlyDisplacedPopulation", osmDemolition.estimatedDisplacedPopulation());
        networkSummary.put("osmDynamicIndexedFeaturesCount", osmIndex.totalIndexedFeatures());
        if (rowFootprint != null) {
            networkSummary.put("rightOfWayAcquisitionFootprint", Map.of(
                    "ircStandard", rowFootprint.ircStandard(),
                    "mainlineRowWidthMeters", rowFootprint.mainlineRowWidthMeters(),
                    "totalDirectRowAcquisitionHectares", rowFootprint.totalDirectRowAcquisitionHectares(),
                    "constructionZoneGrossHectares", rowFootprint.constructionZoneGrossHectares(),
                    "environmentalBufferGrossHectares", rowFootprint.environmentalBufferGrossHectares()
            ));
        }

        Map<String, Object> trafficAnalysis = new LinkedHashMap<>();
        trafficAnalysis.put("designCapacityPcuHr", designCapacityPcuHr);
        trafficAnalysis.put("baseThroughTrafficPcuHr", baseThroughTrafficPcuHr);
        trafficAnalysis.put("projectedTotalTrafficPcuHr", round(currentVolumePcuHr));
        trafficAnalysis.put("projectedVcRatio", round(finalVcRatio));
        trafficAnalysis.put("maxVcRatioCapApplied", effectiveMaxVc);
        trafficAnalysis.put("levelOfService", in.marg.transport.HubEvaluationService.classifyLevelOfService(finalVcRatio));
        trafficAnalysis.put("overCapacityWarning", finalVcRatio > effectiveMaxVc + 1e-9);
        trafficAnalysis.put("sixLaneRecommended", false);
        trafficAnalysis.put("laneConfigurationRecommended", "4_LANE_STANDARD");
        networkSummary.put("trafficCapacityAnalysis", trafficAnalysis);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("corridorTierMode", CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name());
        metadata.put("regionId", "pan_india");
        metadata.put("utmZone", utmZone);
        metadata.put("coordinateReferenceSystem", projection.crs());
        metadata.put("routingResolutionMeters", ROUTING_RESOLUTION_METERS);
        metadata.put("stagesExecuted", totalStagesExecuted);
        metadata.put("targetHubsCount", resolvedHubs.size());
        metadata.put("targetHubs", resolvedHubs.stream().map(ResolvedIntermediateHub::name).toList());
        metadata.put("autoConnectHubs", enableAuto);
        metadata.put("maxAutoHubs", maxAutoHubs != null ? maxAutoHubs : "DEFAULT");
        metadata.put("trafficCapacityAlerts", trafficCapacityAlerts);
        metadata.put("endpointResolution", startSnap.toMetadataMap());
        metadata.put("terminalRoadConnections", terminalRoadConnections);
        metadata.put("horizontalGeometryAudit", horizontalGeometryAudit);
        metadata.put("facilityMode", mode.name());
        metadata.put("searchWindow", overallWindow);
        metadata.put("distanceBaselineLengthKm", round(baselineLengthKm));
        metadata.put("routeLengthKm", round(routeLengthKm));
        metadata.put("deviationPercent", round(deviationPercent));
        metadata.put("routeDiagnostics", routeDiagnostics);
        metadata.put("transportNetworkAndCapacitySummary", networkSummary);

        Map<String, Object> tradeOffProfile = in.marg.routing.TradeOffEvaluationService.evaluateTradeOffs(
                routeDiagnostics,
                networkSummary,
                baselineLengthKm,
                routeLengthKm,
                deviationPercent,
                pref,
                mainline);
        metadata.put("tradeOffProfile", tradeOffProfile);
        metadata.put("alignmentPreference", pref.toMap());
        if (tradeOffProfile.containsKey("statutoryClearanceReport")) {
            metadata.put("statutoryClearanceReport", tradeOffProfile.get("statutoryClearanceReport"));
        }

        Map<String, Object> baselineDiagnostics = panIndiaSurface.summarizeRouteDiagnostics(
                baseline, mode, projection, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> statutoryReport = tradeOffProfile.get("statutoryClearanceReport") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        List<Map<String, Object>> routeLayerProfile = in.marg.routing.LayerVerificationAuditService.sampleTier1RouteProfile(
                mainline, projection);
        List<Map<String, Object>> spurLayerProfiles = new ArrayList<>();
        List<in.marg.routing.LayerVerificationAuditService.SpurDiagnosticsEntry> spurDiagEntries = new ArrayList<>();
        List<in.marg.routing.LayerVerificationAuditService.SpurDiagnosticsEntry> baselineSpurDiagEntries = new ArrayList<>();
        for (Map<String, Object> f : features) {
            if (!(f.get("properties") instanceof Map<?, ?> props)) continue;
            Object role = props.get("featureRole");
            if (!"FEEDER_SPUR_HIGHWAY".equals(role) && !"TERMINAL_ACCESS_ROAD".equals(role)) continue;
            if (!(f.get("geometry") instanceof Map<?, ?> geom) || !(geom.get("coordinates") instanceof List<?> rawCoords)) continue;
            List<GeoPoint> spurPts = new ArrayList<>();
            for (Object cObj : rawCoords) {
                if (cObj instanceof List<?> pair && pair.size() >= 2
                        && pair.get(0) instanceof Number lonN && pair.get(1) instanceof Number latN) {
                    spurPts.add(new GeoPoint(latN.doubleValue(), lonN.doubleValue()));
                }
            }
            if (spurPts.size() >= 2) {
                String spurId = String.valueOf(props.get("corridorId") != null ? props.get("corridorId") : role);
                String hubName = String.valueOf(props.get("connectingHub") != null ? props.get("connectingHub") : "Terminal Spur");
                Map<String, Object> sp = new LinkedHashMap<>();
                sp.put("spurId", spurId);
                sp.put("servedHubName", hubName);
                sp.put("samples", in.marg.routing.LayerVerificationAuditService.sampleTier1RouteProfile(spurPts, projection));
                spurLayerProfiles.add(sp);

                if ("FEEDER_SPUR_HIGHWAY".equals(role)) {
                    double spLen = props.get("lengthKm") instanceof Number n ? n.doubleValue() : router.routeLengthKm(spurPts);
                    Map<String, Object> spDiag = panIndiaSurface.summarizeRouteDiagnostics(spurPts, mode, projection, null);
                    spurDiagEntries.add(new in.marg.routing.LayerVerificationAuditService.SpurDiagnosticsEntry(
                            spurId, hubName, spLen, spDiag));

                    GeoPoint hubTarget = spurPts.get(spurPts.size() - 1);
                    List<GeoPoint> straightSpurPath = in.marg.routing.RouteAlignmentPostRefiner.buildStraightLineSpurPath(
                            baseline, hubTarget, projection, ROUTING_RESOLUTION_METERS);
                    if (straightSpurPath.size() >= 2) {
                        double baseSpurLenKm = router.routeLengthKm(straightSpurPath);
                        Map<String, Object> baseSpDiag = panIndiaSurface.summarizeRouteDiagnostics(
                                straightSpurPath, mode, projection, null);
                        baselineSpurDiagEntries.add(new in.marg.routing.LayerVerificationAuditService.SpurDiagnosticsEntry(
                                "BASE-" + spurId, hubName, baseSpurLenKm, baseSpDiag));
                    }
                }
            }
        }
        List<Map<String, Object>> layerAudit = in.marg.routing.LayerVerificationAuditService.buildTier1Audit(
                routeDiagnostics, baselineDiagnostics, mainline, baseline,
                terminalRoadConnections, statutoryReport, features, mode, projection,
                spurDiagEntries, baselineSpurDiagEntries);
        metadata.put("layerAudit", layerAudit);
        metadata.put("routeLayerProfile", routeLayerProfile);
        metadata.put("spurLayerProfiles", spurLayerProfiles);
        metadata.put("layers", List.of(
                "LULC",
                "FLOOD_HAZARD",
                "SLOPE",
                "PROTECTED_AREA",
                "SETTLEMENT_EXPOSURE",
                "EXISTING_TRANSPORT_NETWORK",
                "HYDROLOGY_WATER_BODIES",
                "WILDLIFE_CORRIDORS_AND_ESZ",
                "GEOTECH_SOIL_AND_LANDSLIDE",
                "HIGH_VOLTAGE_UTILITY_CORRIDORS",
                "SETTLEMENT_AND_BUILDING_FOOTPRINTS",
                "POPULATION_DENSITY_AND_DEMOGRAPHICS",
                "WETLANDS_SEASONAL_STREAMS_AND_DRAINAGE"
        ));

        Map<String, Object> envRouteProps = new LinkedHashMap<>();
        envRouteProps.put("profile", "COMPOSITE_13_LAYER_PAN_INDIA");
        envRouteProps.put("routeLengthKm", round(routeLengthKm));
        envRouteProps.put("distanceBaselineKm", round(baselineLengthKm));
        envRouteProps.put("deviationPercent", round(deviationPercent));
        metadata.put("environmentalSectionInput",
                in.marg.eia.Mode1EnvironmentalSectionAssembler.assemble(envRouteProps, metadata, routeDiagnostics));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "FeatureCollection");
        response.put("corridorTierMode", CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name());
        response.put("regionId", "pan_india");
        response.put("studyRegion", PAN_INDIA_BOUNDS.name() + " (UTM Zone " + utmZone + "N / " + projection.crs() + ")");
        response.put("start", start);
        response.put("goal", goal);
        response.put("requestedStart", requestedStart);
        response.put("requestedGoal", requestedGoal);
        response.put("mode", "COMPOSITE_13_LAYER");
        response.put("facilityMode", mode.name());
        response.put("metadata", metadata);
        if (tradeOffProfile.containsKey("statutoryClearanceReport")) {
            response.put("statutoryClearanceReport", tradeOffProfile.get("statutoryClearanceReport"));
        }
        response.put("warning", "Preliminary greenfield planning corridor only; not a legal land-acquisition or clearance determination.");
        response.put("features", features);
        return response;
    }

    private static double estimatePanIndiaHubPcuHr(DistrictRecord dr) {
        if (dr == null) return 45.0 * 9.5;
        double popThousands;
        double industrialFactor;
        if (!dr.isDistrict()) {
            popThousands = 32.0;
            industrialFactor = 1.0;
        } else if (dr.tier() <= 1) {
            popThousands = 150.0;
            industrialFactor = 1.15;
        } else if (dr.tier() == 2) {
            popThousands = 85.0;
            industrialFactor = 1.05;
        } else {
            popThousands = 50.0;
            industrialFactor = 1.0;
        }
        return popThousands * 9.5 * industrialFactor;
    }

    private List<GeoPoint> buildStageWaypoints(
            GeoPoint start,
            GeoPoint goal,
            int stages,
            PanIndiaDynamicCostLayer surface,
            EndpointSnapService snapService,
            double bufferDeg) {

        if (stages <= 1) {
            return List.of(start, goal);
        }
        List<GeoPoint> waypoints = new ArrayList<>(stages + 1);
        waypoints.add(start);
        for (int i = 1; i < stages; i++) {
            double frac = (double) i / stages;
            GeoPoint rawMid = new GeoPoint(
                    start.lat() + frac * (goal.lat() - start.lat()),
                    start.lon() + frac * (goal.lon() - start.lon()));
            EndpointSnapService.SnapPointResult snapped = snapService.snapPoint(
                    PAN_INDIA_BOUNDS, surface, rawMid);
            waypoints.add(snapped.resolvedPoint());
        }
        waypoints.add(goal);
        return waypoints;
    }

    private List<Map<String, Object>> buildGeoJsonFeatures(
            List<GeoPoint> mainline,
            FacilityMode mode,
            double routeLengthKm,
            double baselineLengthKm,
            double deviationPercent,
            Wgs84UtmProjection projection,
            HighwayTieInResult startTieIn,
            List<GeoPoint> startConnectorPath,
            double startConnectorKm,
            HighwayTieInResult goalTieIn,
            List<GeoPoint> goalConnectorPath,
            double goalConnectorKm) {

        List<Map<String, Object>> features = new ArrayList<>();

        List<List<Double>> coords = mainline.stream()
                .map(p -> List.of(p.lon(), p.lat()))
                .toList();

        Map<String, Object> mainlineProps = new LinkedHashMap<>();
        mainlineProps.put("featureRole", "GREENFIELD_MAINLINE");
        mainlineProps.put("profile", "COMPOSITE_13_LAYER");
        mainlineProps.put("corridorTierMode", CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name());
        mainlineProps.put("facilityMode", mode.name());
        mainlineProps.put("routeLengthKm", round(routeLengthKm));
        mainlineProps.put("distanceBaselineKm", round(baselineLengthKm));
        mainlineProps.put("deviationPercent", round(deviationPercent));
        mainlineProps.put("coordinateReferenceSystem", projection.crs());
        mainlineProps.put("pointCount", mainline.size());

        features.add(Map.of(
                "type", "Feature",
                "geometry", Map.of("type", "LineString", "coordinates", coords),
                "properties", mainlineProps
        ));

        TerminalAccessPolicy.TerminalEndpointSpec originSpec = startTieIn == null ? null
                : new TerminalAccessPolicy.TerminalEndpointSpec(
                        startTieIn.corridorId(),
                        startTieIn.roadRef(),
                        startTieIn.roadName(),
                        startTieIn.category(),
                        startTieIn.tieInPoint(),
                        startConnectorPath,
                        startConnectorKm);
        TerminalAccessPolicy.TerminalEndpointSpec destSpec = goalTieIn == null ? null
                : new TerminalAccessPolicy.TerminalEndpointSpec(
                        goalTieIn.corridorId(),
                        goalTieIn.roadRef(),
                        goalTieIn.roadName(),
                        goalTieIn.category(),
                        goalTieIn.tieInPoint(),
                        goalConnectorPath,
                        goalConnectorKm);
        TerminalAccessPolicy.appendTerminalRoadGeoJsonFeatures(
                features, mode, routeLengthKm, "INTERCHANGE_JUNCTION", originSpec, destSpec);

        // 1. Identify major perennial river bridge crossings (IRC:5-2020 / IRC:SP:13)
        int bridgeCounter = 1;
        Set<String> bridgedRivers = new java.util.HashSet<>();
        for (PanIndiaDynamicCostLayer.MajorRiverAxis river : PanIndiaDynamicCostLayer.MAJOR_RIVERS) {
            for (GeoPoint pt : mainline) {
                double distDeg = PanIndiaDynamicCostLayer.pointToSegmentDistanceDeg(
                        pt.lat(), pt.lon(), river.startLat(), river.startLon(), river.endLat(), river.endLon());
                if (distDeg <= river.channelHalfWidthDeg() * 1.5 && bridgedRivers.add(river.id())) {
                    features.add(pointFeature(pt, "RIVER_BRIDGE", Map.of(
                            "bridgeId", String.format(Locale.ROOT, "BRG-PAN-%03d", bridgeCounter++),
                            "riverId", river.id(),
                            "riverName", river.name(),
                            "structureType", "MAJOR_RIVER_VIADUCT",
                            "recommendedSpanMeters", river.designSpanMeters(),
                            "ircStandard", "IRC:5-2020 / IRC:SP:13-2022"
                    )));
                    break;
                }
            }
        }

        // 2. Add intermediate drainage crossing structures (IRC:SP:13-2022)
        if (!mainline.isEmpty()) {
            int drainageCount = Math.max(1, Math.min(8, mainline.size() / 12));
            int step = Math.max(1, mainline.size() / (drainageCount + 1));
            for (int d = 1; d <= drainageCount; d++) {
                int idx = d * step;
                if (idx < mainline.size()) {
                    features.add(pointFeature(mainline.get(idx), "DRAINAGE_CROSSING_STRUCTURE", Map.of(
                            "structureId", String.format(Locale.ROOT, "DRN-PAN-%03d", d),
                            "structureType", "MULTI_CELL_BOX_CULVERT",
                            "recommendedSpanMeters", 18.0,
                            "ircStandard", "IRC:SP:13-2022"
                    )));
                }
            }
        }

        // 3. Statutory National Parks & Wildlife Sanctuaries (Wildlife Protection Act, 1972)
        if (!mainline.isEmpty()) {
            in.marg.wildlife.IndianWildlifeSanctuaryRegistry sanctuaryReg = in.marg.wildlife.IndianWildlifeSanctuaryRegistry.getDefaultInstance();
            double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
            double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
            for (GeoPoint p : mainline) {
                minLat = Math.min(minLat, p.lat());
                maxLat = Math.max(maxLat, p.lat());
                minLon = Math.min(minLon, p.lon());
                maxLon = Math.max(maxLon, p.lon());
            }
            for (in.marg.wildlife.IndianWildlifeSanctuaryRegistry.StatutorySanctuary s : sanctuaryReg.allSanctuaries()) {
                if (s.maxLat() < minLat - 0.25 || s.minLat() > maxLat + 0.25
                        || s.maxLon() < minLon - 0.25 || s.minLon() > maxLon + 0.25) {
                    continue;
                }
                Map<String, Object> sProps = new LinkedHashMap<>();
                sProps.put("featureRole", "WILDLIFE_SANCTUARY_BOUNDARY");
                sProps.put("sanctuaryId", s.id());
                sProps.put("name", s.name());
                sProps.put("state", s.state());
                sProps.put("district", s.district());
                sProps.put("legalStatute", s.legalStatute());
                sProps.put("areaKm2", s.areaKm2());
                sProps.put("keySpecies", s.keySpecies());
                sProps.put("requiresNbwlClearance", true);

                List<List<double[]>> effectiveRings = s.effectiveBoundaryRings();
                Map<String, Object> geomMap;
                if (effectiveRings.size() > 1) {
                    List<List<List<List<Double>>>> multiCoords = new ArrayList<>(effectiveRings.size());
                    for (List<double[]> r : effectiveRings) {
                        List<List<Double>> ring = new ArrayList<>(r.size());
                        for (double[] pt : r) {
                            ring.add(List.of(Math.round(pt[1] * 100000.0) / 100000.0, Math.round(pt[0] * 100000.0) / 100000.0));
                        }
                        multiCoords.add(List.of(ring));
                    }
                    geomMap = Map.of("type", "MultiPolygon", "coordinates", multiCoords);
                } else {
                    List<List<Double>> ring = new ArrayList<>();
                    if (!effectiveRings.isEmpty()) {
                        for (double[] pt : effectiveRings.get(0)) {
                            ring.add(List.of(Math.round(pt[1] * 100000.0) / 100000.0, Math.round(pt[0] * 100000.0) / 100000.0));
                        }
                    } else {
                        int n = 24;
                        double rLat = (s.maxLat() - s.minLat()) / 2.0;
                        double rLon = (s.maxLon() - s.minLon()) / 2.0;
                        for (int i = 0; i <= n; i++) {
                            double theta = 2.0 * Math.PI * i / n;
                            double lat = s.centerLat() + rLat * Math.sin(theta);
                            double lon = s.centerLon() + rLon * Math.cos(theta);
                            ring.add(List.of(round(lon), round(lat)));
                        }
                    }
                    geomMap = Map.of("type", "Polygon", "coordinates", List.of(ring));
                }
                features.add(Map.of(
                        "type", "Feature",
                        "geometry", geomMap,
                        "properties", sProps
                ));

                if (s.eszPolygon() != null && s.eszPolygon().size() >= 3) {
                    List<List<Double>> eszRing = new ArrayList<>();
                    for (double[] pt : s.eszPolygon()) {
                        eszRing.add(List.of(Math.round(pt[1] * 100000.0) / 100000.0, Math.round(pt[0] * 100000.0) / 100000.0));
                    }
                    Map<String, Object> eszProps = new LinkedHashMap<>();
                    eszProps.put("featureRole", "ECO_SENSITIVE_ZONE_BOUNDARY");
                    eszProps.put("sanctuaryId", s.id());
                    eszProps.put("name", s.name() + " (MoEFCC 10 km Default ESZ)");
                    eszProps.put("eszRadiusKm", in.marg.wildlife.IndianWildlifeSanctuaryRegistry.DEFAULT_ESZ_BUFFER_KM);
                    eszProps.put("state", s.state());
                    features.add(Map.of(
                            "type", "Feature",
                            "geometry", Map.of(
                                    "type", "Polygon",
                                    "coordinates", List.of(eszRing)
                            ),
                            "properties", eszProps
                    ));
                }
            }

            // 4. Authoritative Pan-India Wetlands Polygons (Ramsar Sites & Major Indian Beels/Jheels/Taals/Backwaters)
            List<in.marg.drainage.IndianWetlandRegistry.MappedWetland> nearbyWetlands =
                    in.marg.drainage.IndianWetlandRegistry.getDefaultInstance()
                            .findWetlandsInWindow(minLat - 0.45, minLon - 0.45, maxLat + 0.45, maxLon + 0.45);
            int wmzSeq = 1;
            for (in.marg.drainage.IndianWetlandRegistry.MappedWetland w : nearbyWetlands) {
                List<List<Double>> wRing = new ArrayList<>(w.polygonLonLat().size());
                for (double[] pt : w.polygonLonLat()) {
                    wRing.add(List.of(
                            Math.round(pt[0] * 100000.0) / 100000.0,
                            Math.round(pt[1] * 100000.0) / 100000.0));
                }
                Map<String, Object> wProps = new LinkedHashMap<>();
                wProps.put("featureRole", "CORRIDOR_WETLAND_POLYGON");
                wProps.put("wetlandId", w.id());
                wProps.put("wetlandName", w.name());
                wProps.put("wetlandCategory", w.wetlandCategory());
                wProps.put("state", w.state());
                wProps.put("district", w.district());
                wProps.put("basin", w.basin());
                wProps.put("areaHectares", round(w.areaHectares()));
                wProps.put("isRamsar", w.isRamsar());
                wProps.put("isProtectedBeel", w.isProtectedWetland());
                wProps.put("legalStatute", w.legalStatute());
                wProps.put("recommendedMitigation", "WETLAND_AVOIDANCE_OR_PILE_VIADUCT_EQUALIZER_CAUSEWAY");
                wProps.put("centroidLat", w.centerLat());
                wProps.put("centroidLon", w.centerLon());
                features.add(Map.of(
                        "type", "Feature",
                        "geometry", Map.of("type", "Polygon", "coordinates", List.of(wRing)),
                        "properties", wProps
                ));

                // Flag a wetland hydrologic mitigation zone if the mainline passes within 500 m
                double minDistM = Double.POSITIVE_INFINITY;
                for (GeoPoint p : mainline) {
                    if (p.lat() < w.minLat() - 0.05 || p.lat() > w.maxLat() + 0.05
                            || p.lon() < w.minLon() - 0.05 || p.lon() > w.maxLon() + 0.05) {
                        continue;
                    }
                    double d = w.approximateDistanceMeters(p.lat(), p.lon());
                    if (d < minDistM) {
                        minDistM = d;
                    }
                }
                if (minDistM <= 500.0) {
                    Map<String, Object> wmzProps = new LinkedHashMap<>();
                    wmzProps.put("featureRole", "WETLAND_HYDROLOGIC_MITIGATION_ZONE");
                    wmzProps.put("zoneId", String.format(Locale.ROOT, "WMZ-PAN-%03d", wmzSeq++));
                    wmzProps.put("wetlandId", w.id());
                    wmzProps.put("wetlandName", w.name());
                    wmzProps.put("wetlandCategory", w.wetlandCategory());
                    wmzProps.put("district", w.district());
                    wmzProps.put("isProtectedBeel", w.isProtectedWetland());
                    wmzProps.put("clearanceFromDirectRowEdgeMeters", round(Math.max(0.0, minDistM - 30.0)));
                    wmzProps.put("recommendedMitigation", "WETLAND_AVOIDANCE_OR_PILE_VIADUCT_EQUALIZER_CAUSEWAY");
                    wmzProps.put("recommendedEngineeringMitigation", "WETLAND_AVOIDANCE_OR_PILE_VIADUCT_EQUALIZER_CAUSEWAY");
                    features.add(Map.of(
                            "type", "Feature",
                            "geometry", Map.of("type", "Point", "coordinates", List.of(w.centerLon(), w.centerLat())),
                            "properties", wmzProps
                    ));
                }
            }

            // 5. Major River & Drainage Basin Channels within corridor window (for L7 & L13 map overlays)
            for (PanIndiaDynamicCostLayer.MajorRiverAxis river : PanIndiaDynamicCostLayer.MAJOR_RIVERS) {
                double rMinLat = Math.min(river.startLat(), river.endLat());
                double rMaxLat = Math.max(river.startLat(), river.endLat());
                double rMinLon = Math.min(river.startLon(), river.endLon());
                double rMaxLon = Math.max(river.startLon(), river.endLon());
                if (rMaxLat < minLat - 0.35 || rMinLat > maxLat + 0.35
                        || rMaxLon < minLon - 0.35 || rMinLon > maxLon + 0.35) {
                    continue;
                }
                Map<String, Object> rivProps = new LinkedHashMap<>();
                rivProps.put("featureRole", "CORRIDOR_DRAINAGE_CHANNEL");
                rivProps.put("channelId", river.id());
                rivProps.put("channelName", river.name());
                rivProps.put("sublayerType", "NATURAL_DRAINAGE_CHANNEL");
                rivProps.put("designWidthMeters", river.designSpanMeters());
                rivProps.put("recommendedStructureType", "MULTI_CELL_BOX_CULVERT_OR_MINOR_BRIDGE");
                features.add(Map.of(
                        "type", "Feature",
                        "geometry", Map.of(
                                "type", "LineString",
                                "coordinates", List.of(
                                        List.of(round(river.startLon()), round(river.startLat())),
                                        List.of(round(river.endLon()), round(river.endLat()))
                                )
                        ),
                        "properties", rivProps
                ));
            }
        }

        return features;
    }

    private HighwayTieInResult findNearestHighwayTieIn(
            GeoPoint target,
            PanIndiaDynamicCostLayer surface,
            Wgs84UtmProjection projection,
            EndpointSnapService snapService) {
        return findNearestHighwayTieIn(target, surface, projection, snapService, null, null, FacilityMode.EXPRESSWAY);
    }

    private HighwayTieInResult findNearestHighwayTieIn(
            GeoPoint target,
            PanIndiaDynamicCostLayer surface,
            Wgs84UtmProjection projection,
            EndpointSnapService snapService,
            in.marg.transport.OsmDynamicTransportNetworkCache.OsmTransportIndex osmTransportIndex,
            OsmDynamicBuildingSettlementCache.OsmCorridorIndex osmIndex,
            FacilityMode facilityMode) {
        if (target == null) {
            return null;
        }
        if (osmTransportIndex != null && !osmTransportIndex.isEmpty()) {
            boolean hasOsm = osmIndex != null && !osmIndex.isEmpty();
            TransportNetworkVectorIndex.NearestRoadTieIn osmTie =
                    osmTransportIndex.findNearestRoadTieIn(
                            target,
                            pt -> !surface.assess(pt).blocked()
                                    && (!hasOsm || osmIndex.findEndpointObstacleReason(pt, 75.0, 15.0) == null),
                            (fromPt, toPt) -> !hasOsm
                                    || !osmIndex.hasSegmentBuildingOrExclusionConflict(fromPt, toPt, 25.0, 75.0),
                            facilityMode);
            if (osmTie != null && osmTie.distanceMeters() <= 35000.0) {
                return new HighwayTieInResult(
                        osmTie.corridorId(),
                        osmTie.corridorRef(),
                        osmTie.corridorName(),
                        osmTie.category(),
                        osmTie.tieInPoint(),
                        osmTie.distanceMeters()
                );
            }
        }
        HighwayTieInResult bestSh = findNearestInClassifiedList(
                target, STATE_HIGHWAY_AXES, surface, projection, snapService);
        HighwayTieInResult bestNh = findNearestInClassifiedList(
                target, NATIONAL_HIGHWAY_AXES, surface, projection, snapService);

        // 2-Tier hierarchy: if an intermediate hub or settlement has a State Highway / MDR within 15 km,
        // IRC access-control design standard connects via the local State Highway / MDR feeder.
        if (bestSh != null && bestSh.distanceMeters() <= 15000.0) {
            return bestSh;
        }
        if (bestNh != null) {
            return bestNh;
        }
        return bestSh;
    }

    private HighwayTieInResult findNearestInClassifiedList(
            GeoPoint target,
            List<ClassifiedRoadAxis> axes,
            PanIndiaDynamicCostLayer surface,
            Wgs84UtmProjection projection,
            EndpointSnapService snapService) {
        HighwayTieInResult best = null;
        double bestDistM = Double.POSITIVE_INFINITY;
        for (ClassifiedRoadAxis axis : axes) {
            List<GeoPoint> pts = axis.points();
            for (int i = 1; i < pts.size(); i++) {
                GeoPoint a = pts.get(i - 1);
                GeoPoint b = pts.get(i);
                double dx = b.lon() - a.lon();
                double dy = b.lat() - a.lat();
                double len2 = dx * dx + dy * dy;
                double t = len2 <= 1e-12
                        ? 0.0
                        : ((target.lon() - a.lon()) * dx + (target.lat() - a.lat()) * dy) / len2;
                t = Math.max(0.0, Math.min(1.0, t));
                GeoPoint candidate = new GeoPoint(a.lat() + t * dy, a.lon() + t * dx);
                double dMeters = projection.distanceMeters(target, candidate);
                if (dMeters < bestDistM) {
                    if (surface.assess(candidate).blocked()) {
                        try {
                            candidate = snapService.snapPoint(PAN_INDIA_BOUNDS, surface, candidate).resolvedPoint();
                            dMeters = projection.distanceMeters(target, candidate);
                        } catch (Exception ignored) {
                            continue;
                        }
                    }
                    if (dMeters < bestDistM) {
                        bestDistM = dMeters;
                        best = new HighwayTieInResult(
                                axis.id(), axis.ref(), axis.name(), axis.category(), candidate, round(dMeters));
                    }
                }
            }
        }
        return best;
    }

    private List<GeoPoint> routeDynamicConnector(
            GreenfieldAStarRouter router,
            EndpointSnapService snapService,
            FacilityMode mode,
            GeoPoint fromPt,
            GeoPoint toPt,
            double bufferDeg,
            OsmDynamicBuildingSettlementCache.OsmCorridorIndex osmIndex) {
        if (fromPt == null || toPt == null) {
            return List.of();
        }
        double dLat = Math.abs(toPt.lat() - fromPt.lat());
        double dLon = Math.abs(toPt.lon() - fromPt.lon());
        int stages = Math.max(1, (int) Math.ceil(Math.max(dLat, dLon) / MAX_SINGLE_STAGE_SPAN_DEG));
        RouteConstraints connConstraints = RouteConstraints.forFacilityMode(null, FacilityMode.NATIONAL_HIGHWAY);
        for (boolean lastResortColony : new boolean[]{false, true}) {
            PanIndiaDynamicCostLayer connSurface = new PanIndiaDynamicCostLayer(
                    fromPt, toPt, mode, in.marg.routing.AlignmentPreference.BALANCED, osmIndex, lastResortColony);
            try {
                List<GeoPoint> waypoints = buildStageWaypoints(
                        fromPt, toPt, stages, connSurface, snapService, bufferDeg);
                List<GeoPoint> connector = new ArrayList<>();
                for (int s = 0; s < waypoints.size() - 1; s++) {
                    GeoPoint sA = waypoints.get(s);
                    GeoPoint sB = waypoints.get(s + 1);
                    StudyRegion sWin = stageCorridorWindow(sA, sB, Math.min(bufferDeg, 0.14));
                    PanIndiaDynamicCostLayer sSurface = new PanIndiaDynamicCostLayer(
                            sA, sB, mode, in.marg.routing.AlignmentPreference.BALANCED, osmIndex, lastResortColony);
                    List<GeoPoint> seg = router.route(
                            PAN_INDIA_BOUNDS, sA, sB, sSurface, ROUTING_RESOLUTION_METERS, sWin, connConstraints);
                    appendPathSegment(connector, seg);
                }
                if (!connector.isEmpty()) {
                    connector.set(0, fromPt);
                    connector.set(connector.size() - 1, toPt);
                    int localUtmZone = Math.max(1, Math.min(60,
                            (int) Math.floor((0.5 * (fromPt.lon() + toPt.lon()) + 180.0) / 6.0) + 1));
                    in.marg.spatial.CoordinateProjection localProj =
                            new in.marg.spatial.Wgs84UtmProjection(localUtmZone);
                    return in.marg.routing.HorizontalCurveSmoother.smoothAndAudit(
                            connector,
                            connSurface,
                            null,
                            localProj,
                            PAN_INDIA_BOUNDS,
                            in.marg.routing.HorizontalCurveSmoother.CurveProfile.FEEDER_SPUR).smoothedPath();
                }
            } catch (RuntimeException ignored) {
            }
        }
        // Never allow unchecked straight-line fallback across hospitals, colonies, buildings, or sanctuaries
        if (in.marg.wildlife.IndianWildlifeSanctuaryRegistry.getDefaultInstance()
                .findIntersectingSanctuaryForSegment(fromPt.lat(), fromPt.lon(), toPt.lat(), toPt.lon()) != null) {
            return List.of();
        }
        if (osmIndex != null && osmIndex.hasSegmentBuildingOrExclusionConflict(fromPt, toPt, 25.0, 75.0)) {
            return List.of();
        }
        return List.of(fromPt, toPt);
    }

    private static Map<String, Object> buildTerminalRoadConnectionsMetadata(
            HighwayTieInResult startTieIn,
            double startConnectorKm,
            GeoPoint start,
            String startSnapReason,
            HighwayTieInResult goalTieIn,
            double goalConnectorKm,
            GeoPoint goal,
            String goalSnapReason,
            double totalTerminalAccessRoadLengthKm,
            FacilityMode mode) {
        Map<String, Object> startConn = startTieIn == null ? null : TerminalAccessPolicy.buildTerminalConnectionEntry(
                startTieIn.corridorId(),
                startTieIn.roadRef(),
                startTieIn.roadName(),
                startTieIn.category(),
                startTieIn.tieInPoint(),
                start,
                startTieIn.distanceMeters(),
                startConnectorKm,
                DIRECT_ROAD_TIE_IN_THRESHOLD_METERS,
                mode,
                TerminalAccessPolicy.isSnappedOutsideUrbanCore(startSnapReason));
        Map<String, Object> goalConn = goalTieIn == null ? null : TerminalAccessPolicy.buildTerminalConnectionEntry(
                goalTieIn.corridorId(),
                goalTieIn.roadRef(),
                goalTieIn.roadName(),
                goalTieIn.category(),
                goalTieIn.tieInPoint(),
                goal,
                goalTieIn.distanceMeters(),
                goalConnectorKm,
                DIRECT_ROAD_TIE_IN_THRESHOLD_METERS,
                mode,
                TerminalAccessPolicy.isSnappedOutsideUrbanCore(goalSnapReason));
        return TerminalAccessPolicy.buildTerminalConnectionsMetadata(
                startConn, goalConn, totalTerminalAccessRoadLengthKm, mode);
    }

    private static void appendPathSegmentExact(List<GeoPoint> target, List<GeoPoint> segment) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        if (target.isEmpty()) {
            target.addAll(segment);
            return;
        }
        int startIdx = segment.get(0).equals(target.get(target.size() - 1)) ? 1 : 0;
        for (int i = startIdx; i < segment.size(); i++) {
            target.add(segment.get(i));
        }
    }

    private static Map<String, Object> pointFeature(
            GeoPoint point, String role, Map<String, Object> extraProps) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("featureRole", role);
        props.putAll(extraProps);
        return Map.of(
                "type", "Feature",
                "geometry", Map.of("type", "Point", "coordinates", List.of(point.lon(), point.lat())),
                "properties", props
        );
    }

    private static Map<String, Object> lineFeature(
            List<GeoPoint> points, String role, Map<String, Object> extraProps) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("featureRole", role);
        props.putAll(extraProps);
        List<List<Double>> coords = points.stream()
                .map(p -> List.of(p.lon(), p.lat()))
                .toList();
        return Map.of(
                "type", "Feature",
                "geometry", Map.of("type", "LineString", "coordinates", coords),
                "properties", props
        );
    }

    private static Map<String, Object> polygonFeature(
            List<GeoPoint> points, String role, Map<String, Object> extraProps) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("featureRole", role);
        props.putAll(extraProps);
        List<List<Double>> ring = new ArrayList<>();
        for (GeoPoint p : points) {
            ring.add(List.of(p.lon(), p.lat()));
        }
        if (!ring.isEmpty() && !ring.get(0).equals(ring.get(ring.size() - 1))) {
            ring.add(ring.get(0));
        }
        return Map.of(
                "type", "Feature",
                "geometry", Map.of("type", "Polygon", "coordinates", List.of(ring)),
                "properties", props
        );
    }

    private static void appendPathSegment(List<GeoPoint> target, List<GeoPoint> segment) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        int startIdx = target.isEmpty() ? 0 : 1;
        for (int i = startIdx; i < segment.size(); i++) {
            target.add(segment.get(i));
        }
    }

    private static StudyRegion localWindowAround(GeoPoint p, double bufferDeg) {
        return new StudyRegion(
                "Local Endpoint Window",
                Math.max(PAN_INDIA_BOUNDS.minLat(), p.lat() - bufferDeg),
                Math.max(PAN_INDIA_BOUNDS.minLon(), p.lon() - bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLat(), p.lat() + bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLon(), p.lon() + bufferDeg),
                p,
                p
        );
    }

    private static StudyRegion unionWindow(StudyRegion a, StudyRegion b, double bufferDeg) {
        return new StudyRegion(
                "Pan-India Dynamic Corridor Window",
                Math.max(PAN_INDIA_BOUNDS.minLat(), Math.min(a.minLat(), b.minLat()) - bufferDeg),
                Math.max(PAN_INDIA_BOUNDS.minLon(), Math.min(a.minLon(), b.minLon()) - bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLat(), Math.max(a.maxLat(), b.maxLat()) + bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLon(), Math.max(a.maxLon(), b.maxLon()) + bufferDeg),
                a.origin(),
                b.destination()
        );
    }

    private static StudyRegion stageCorridorWindow(GeoPoint a, GeoPoint b, double bufferDeg) {
        return new StudyRegion(
                "Pan-India Stage Corridor Window",
                Math.max(PAN_INDIA_BOUNDS.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg),
                Math.max(PAN_INDIA_BOUNDS.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg),
                a,
                b
        );
    }

    private static String normalizeHubNameKey(String name) {
        if (name == null) return "";
        String base = name.replaceAll("\\s*\\([^)]*\\)", "").trim().toLowerCase(Locale.ROOT);
        return base.replaceAll("[^a-z0-9]", "");
    }

    private static double minDistanceToPolylineDeg(GeoPoint pt, List<GeoPoint> polyline) {
        if (polyline == null || polyline.isEmpty()) return Double.POSITIVE_INFINITY;
        if (polyline.size() == 1) {
            return Math.hypot(pt.lon() - polyline.get(0).lon(), pt.lat() - polyline.get(0).lat());
        }
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polyline.size() - 1; i++) {
            GeoPoint a = polyline.get(i);
            GeoPoint b = polyline.get(i + 1);
            double dx = b.lon() - a.lon();
            double dy = b.lat() - a.lat();
            double lenSq = dx * dx + dy * dy;
            double t = lenSq <= 1e-12
                    ? 0.0
                    : Math.max(0.0, Math.min(1.0, ((pt.lon() - a.lon()) * dx + (pt.lat() - a.lat()) * dy) / lenSq));
            double px = a.lon() + t * dx;
            double py = a.lat() + t * dy;
            best = Math.min(best, Math.hypot(pt.lon() - px, pt.lat() - py));
        }
        return best;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
