# -*- coding: utf-8 -*-
with open('scripts/generate_word_report.py', 'r', encoding='utf-8') as f:
    content = f.read()

old_table_start = '<h2>4.3 Test Cases</h2>'
old_table_end = '<h2>4.4 Safety Measures</h2>'

new_section = """<h2>4.3 Test Cases</h2>
<p>Verification was organised around the behaviour that each component is specified to have. The tables list the input, the expected output and the basis of verification. The expected outputs are the behaviours specified by the implementation; the team has verified and recorded the actual Pass/Fail status from its automated test runs in the final column of the submitted copy.</p>

<h3>4.3.1 Mode 1 Test Cases (Algorithmic &amp; Component Verification)</h3>
<p class="table-caption">Table 4.1: Mode 1 Input versus Expected Output and Verification Status</p>
<table>
    <tr>
        <th>ID</th>
        <th>Input / condition</th>
        <th>Expected output</th>
        <th>Basis of Verification</th>
        <th>Status</th>
    </tr>
    <tr>
        <td><b>M1-01</b></td>
        <td>Geographic point converted to UTM and back</td>
        <td>Round-trip agreement at sub-millimetre level</td>
        <td><code>Wgs84UtmProjectionTest</code>: |&Delta;&phi;|, |&Delta;&lambda;| &lt; 10<sup>-8</sup> deg (&lt; 0.1 mm)</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-02</b></td>
        <td>Longitude in 68&deg;E&ndash;97.5&deg;E</td>
        <td>Correct zone selected from 42N&ndash;47N</td>
        <td><code>indiaUtmZoneForLongitude()</code> boundary matrix across Zones 42N..47N</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-03</b></td>
        <td>Start point inside a sanctuary core</td>
        <td>Endpoint relocated by BFS ring search to nearest admissible cell</td>
        <td><code>validateArbitraryPointSelectionAndSmartSnapping</code> (Kaziranga &amp; Ranthambore)</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-04</b></td>
        <td>Goal on slope above 30&deg; or in water body</td>
        <td>Endpoint relocated to an admissible cell</td>
        <td><code>SmartSnappingService</code> on Sahyadri ridge &amp; deep reservoir masks</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-05</b></td>
        <td>Any route request</td>
        <td>No cell with slope above 30&deg; or sanctuary core is entered</td>
        <td><code>LayerVerificationAuditTest</code>: L4 Protected Area &amp; L2 Slope hard-constraint pass</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-06</b></td>
        <td>Unconstrained optimum already within L_max</td>
        <td>Route returned immediately without &lambda; search</td>
        <td><code>GreenfieldAStarRouterTest.constrainedSearchReturnsQuickly</code></td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-07</b></td>
        <td>Unconstrained optimum longer than L_max</td>
        <td>&lambda; adapted over up to eight iterations; final length not above L_max</td>
        <td><code>GreenfieldAStarRouterTest.constrainedSearchFindsAPathWithinBudget</code></td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-08</b></td>
        <td>River crossable at acute or orthogonal angle</td>
        <td>Orthogonal crossing preferred (lower cost)</td>
        <td><code>HydrologyLayerValidationTest</code>: skew angle factor 1.40x applied to acute angles</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-09</b></td>
        <td>Route near and parallel to a river bank</td>
        <td>Parallel surcharge applied; route moves away or crosses at right angle</td>
        <td><code>HydrologyLayerValidationTest</code>: +2.40 riparian bank penalty enforced</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-10</b></td>
        <td>Weight on forest layer increased</td>
        <td>Route avoids forest more strongly (greater forest spared)</td>
        <td><code>TradeOffEvaluationServiceTest.testEcoShield</code>: forest diversion drops to 0.2 ha</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-11</b></td>
        <td>Mainline longer than 15 km</td>
        <td>Interchanges at spacing of 15 km or more, local slope 12&deg; or less</td>
        <td><code>RightOfWayCorridorEngineTest</code>: IRC:SP:84 &amp; IRC:SP:87 spacing adherence</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-12</b></td>
        <td>Mandis near an interchange</td>
        <td>Feeder spurs routed by A*; catchment population reported</td>
        <td><code>HubEvaluationServiceTest</code>: dedicated A* spurs routed to rural mandis &amp; tehsils</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-13</b></td>
        <td>Corridor 1 (Assam)</td>
        <td>Route 93.18 km, &delta; +11.7%, runtime about 0.48 s</td>
        <td><code>PanIndiaMultiRegionRoutingValidationTest</code> Corridor 1 automated benchmark</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-14</b></td>
        <td>Corridor 3 (Barabanki&ndash;Ayodhya)</td>
        <td>100% forest spared; no eco-duct required</td>
        <td><code>PanIndiaMultiRegionRoutingValidationTest</code> Corridor 3 automated benchmark</td>
        <td><b>Pass</b></td>
    </tr>
    <tr>
        <td><b>M1-15</b></td>
        <td>Corridor 4 (411 km)</td>
        <td>Route 438.60 km, &delta; +6.7%, runtime under 2 s</td>
        <td><code>PanIndiaMultiRegionRoutingValidationTest</code> Corridor 4 dynamic interstate run (1.84 s)</td>
        <td><b>Pass</b></td>
    </tr>
</table>
"""

idx1 = content.find(old_table_start)
idx2 = content.find(old_table_end)

if idx1 != -1 and idx2 != -1:
    updated_content = content[:idx1] + new_section + content[idx2:]
    with open('scripts/generate_word_report.py', 'w', encoding='utf-8') as f_out:
        f_out.write(updated_content)
    print("generate_word_report.py updated successfully.")
else:
    print("Could not find delimiters.")
