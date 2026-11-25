# MARG v0.5.0 — Kaziranga–Karbi Anglong greenfield alignment prototype

MARG is a preliminary greenfield highway/expressway corridor-planning prototype for the Kaziranga–Karbi Anglong study region in Assam.

This version makes the routing core independent of latitude/longitude degree distances and establishes a typed spatial-data-provider architecture.

## What changed in v0.5

1. **Metric routing grid:** A* now constructs its artificial search grid in projected metres rather than degree units. The current study-area projection is WGS84 / UTM zone 46N (`EPSG:32646`).
2. **Meter-based routing resolution:** the default routing grid is `250 m` rather than `0.002°`.
3. **Provider abstraction:** routing consumes a `LulcProvider` instead of depending directly on the Bhuvan sampler. `BhuvanLulcProvider` is the current implementation.
4. **Provider base contract:** `SpatialProvider<T>` provides the common point-sampling/provenance abstraction that later flood, terrain, settlement, environmental and other providers can build on.
5. **Dataset-independent LULC cost layer:** `LulcCostLayer` no longer has Bhuvan in its class name. The route engine therefore depends on the type of information rather than its source.
6. **Metric distance constraints:** maximum route deviation is enforced against physical route length computed in the routing CRS.

## Study region

Kaziranga–Karbi Anglong, Assam.

Configured research input points:

- A: `26.58970, 93.40035` (Kohora Chariali area)
- B: `25.84573, 93.43781` (Diphu area)

These points are prototype inputs and are not a proposed legal/project alignment.

## Scope boundary

The generated lines are preliminary planning corridors. They are not final highway centerlines, construction-ready geometric designs, environmental-clearance decisions, land-acquisition determinations, or legal displacement assessments.

The current A* graph is an artificial geographic grid. Existing roads are not the routing graph.

## Run with Java 25

From the project root:

```cmd
java -version
mvn -version
mvn clean spring-boot:run
```

The application runs at `http://localhost:8080`.

## Verify the runtime and projection

```text
http://localhost:8080/api/system
```

The response reports the actual Java runtime, the routing model, and the coordinate reference system used internally.

## Route endpoints

### Distance baseline

```text
http://localhost:8080/api/routes?mode=distance
```

A* minimizes physical movement distance on the projected metric grid.

### Bhuvan-LULC-aware route

```text
http://localhost:8080/api/routes?mode=lulc
```

The route cost combines physical movement distance with the configured LULC penalty supplied through `LulcProvider`.

### Compare baseline and LULC-aware route

```text
http://localhost:8080/api/routes?mode=compare
```

### Maximum route deviation

Example:

```text
http://localhost:8080/api/routes?mode=lulc&maxDeviationPercent=15
```

MARG first computes the physical distance-only baseline and derives:

```text
maxAllowedRouteLength = baselineLength × (1 + maxDeviationPercent / 100)
```

The constrained router first returns the ordinary LULC route when it already satisfies the limit. Otherwise it uses a Lagrangian relaxation that adds a distance term to the scalarized A* objective. The constrained stage is an approximate constrained optimization method rather than an exact solver guarantee.

## Routing resolution

Default:

```text
250 metres
```

The grid is no longer expressed in degrees. A grid node is defined in the projected metric CRS and converted to WGS84 only when a spatial provider is queried or when GeoJSON is generated.

For a study region near `93.4°E`, UTM zone 46N is the local metric projection currently configured by `marg.routing.utm-zone`.

Configuration:

```yaml
marg:
  routing:
    resolution-meters: 250
    utm-zone: 46
```

Allowed routing resolution is currently `50–5000 m`.

## Coordinate architecture

```text
WGS84 latitude/longitude
          |
          v
WGS84 / UTM zone 46N
          |
          v
metric X/Y in metres
          |
          v
artificial A* grid
          |
          v
route nodes
          |
          v
WGS84 GeoJSON
```

The routing algorithm therefore uses metre-based physical distances. External GIS services can continue to operate in their native/WGS84-compatible spatial interface.

## LULC provider architecture

```text
                  LulcProvider
                       |
              +--------+--------+
              |                 |
              v                 v
     BhuvanLulcProvider   future/local provider
              |
              v
      BhuvanLulcSampler
              |
              v
        WMS GetFeatureInfo
```

Routing and cost calculation use:

```java
LulcProvider
```

not:

```java
BhuvanLulcSampler
```

This is intentional: Bhuvan is a current **data source**, not the routing contract.

The base interface is:

```java
SpatialProvider<T>
```

and `LulcProvider` extends it with region preloading and dataset metadata.

The current implementation is:

```java
BhuvanLulcProvider implements LulcProvider
```

## Planned provider types

The architecture is intended to support additional typed providers without coupling them to A*:

```text
LulcProvider
FloodProvider
TerrainProvider
SettlementProvider
ProtectedAreaProvider
RoadNetworkProvider
```

Examples of future implementations:

```text
BhuvanLulcProvider
BhuvanFloodProvider
DemTerrainProvider
PariveshProtectedAreaProvider
OsmRoadNetworkProvider
```

Only the LULC provider is implemented in this version. The other providers should be introduced when their actual data model is implemented, rather than as empty placeholder classes.

## LULC acquisition

The current Bhuvan implementation queries the Assam LULC 1:50,000 (2015–16) layer through WMS `GetFeatureInfo` and caches point classifications on a `0.01°` sample grid.

Configuration:

```yaml
marg:
  bhuvan:
    lulc:
      sample-resolution-deg: 0.01
      query-half-size-deg: 0.004
      parallelism: 6
      cache-file: data/cache/bhuvan-lulc-samples.json
      max-remote-samples: 4000
```

The routing grid and the LULC sampling grid are intentionally different resolutions. The provider abstraction isolates this difference.

Delete `data/cache/bhuvan-lulc-samples.json` when a fresh remote acquisition is required.

LULC endpoints:

```text
http://localhost:8080/api/bhuvan/lulc/sample?lat=26.5897&lon=93.40035
http://localhost:8080/api/bhuvan/lulc/check
http://localhost:8080/api/bhuvan/lulc/cache
```

## LULC policy

The runtime policy is in `src/main/resources/application.yml`:

```yaml
marg:
  routing:
    lulc:
      penalties:
        BUILT_UP: 1.5
        FOREST: 0.8
        WETLAND_WATER: 2.0
        AGRICULTURE: 0.25
        GRASS_GRAZING: 0.10
        BARREN_WASTELAND: 0.0
        SNOW: 2.5
        UNKNOWN: 0.0
```

These are MARG model parameters. They are not Bhuvan values, statutory weights, or legal/regulatory thresholds.

Current routing formula:

```text
edge cost = physical distance × (1 + sum of active spatial penalty contributions)
```

A separate hard-constraint mechanism exists through `CostAssessment.blocked()` and can later be used for planning rules that require explicit avoidance.

## LULC cost-surface endpoint

```text
http://localhost:8080/api/routes/cost-surface
```

The endpoint returns the sampled LULC grid as GeoJSON polygons with:

- LULC class
- MARG penalty multiplier
- remote-success flag
- sample latitude/longitude

This is a diagnostic view of the spatial field used by the current LULC layer.

## Existing Bhuvan map proxy

For visualization only:

```text
http://localhost:8080/api/bhuvan/map/assam-lulc-50k-1516?minLon=93.10&minLat=25.75&maxLon=93.75&maxLat=26.65
```

The routing engine does not infer LULC from rendered map colors.

## Cost and route architecture

```text
                     A + B
                       |
                       v
               A-B search window
                       |
                       v
              projected metric grid
                       |
          +------------+-------------+
          |                          |
          v                          v
     LulcProvider               future providers
          |                     flood / terrain /
          v                     settlement / PA /
    LULC CostLayer             roads / rail etc.
          |                          |
          +------------+-------------+
                       |
                       v
                CompositeCostSurface
                       |
                       v
                       A*
                       |
                       v
                candidate alignment
                       |
                       v
          metrics / GeoJSON / map UI
```

The key software separation is:

```text
A* → CostSurface → CostLayer → Provider → External data source
```

The A* implementation therefore does not know whether a cost came from Bhuvan, a downloaded GeoTIFF, PARIVESH, OSM or another source.

## Constrained search

The route-length constraint is a physical-distance constraint:

```text
L(route) <= Lmax
```

where distance is computed in the projected routing CRS.

The algorithm uses a feasibility-first strategy:

```text
ordinary LULC A*
        |
        | route fits budget?
       / \
     yes  no
      |    |
   return  Lagrangian relaxation
           + A* with lambda × distance
           |
           v
      feasible candidate
```

The response reports the requested maximum deviation and whether the resulting route satisfies it.

## Frontend (Unified Workspace)

The interactive React + Leaflet frontend is located in `MARG_Frontend/`.

### Development Mode (Vite Hot-Reload)
Run from the repository root:
```cmd
npm run dev
```
*(or inside `MARG_Frontend/`: `npm.cmd run dev`)*

This starts the Vite development server at `http://localhost:5173` with automatic API proxying to `http://localhost:8080`.

### Production Build & Single-Port Deployment
To build the frontend and automatically sync assets into Spring Boot's `src/main/resources/static/`:
```cmd
npm run build
```
Once built, running `mvn spring-boot:run` serves the complete frontend application directly on `http://localhost:8080/` without needing a separate frontend server.

## Testing

The routing core has unit tests for:

- baseline path generation
- UTM forward/inverse round-trip
- constrained physical-length behavior
- the fast path when an unconstrained route already fits the length budget

The core projection/router classes can also be compiled independently from Spring for algorithmic smoke testing.

## Next implementation stage

The next provider should be the **flood-hazard provider**.

It should be introduced as a separate provider/cost layer and tested independently before being composed with LULC:

```text
FloodProvider
     |
     v
FloodCostLayer
     |
     +----> CompositeCostSurface
```

After flood, the next layers can be added one at a time:

```text
LULC
  ↓
Flood
  ↓
Protected areas / forest / ESZ
  ↓
Settlements / population exposure
  ↓
Terrain / slope
  ↓
Existing roads / railways
  ↓
Multi-objective alternatives
```

## Mode 1 environmental report pipeline contract

The current composite-route response includes `metadata.environmentalSectionInput`, assembled from the existing `routeDiagnostics`. This is the GIS evidence input for the environmental report workflow; it is not a completed EIA and does not contain baseline field measurements, legal applicability determinations, or project approvals.

The input contract preserves:

- `method`: route profile, routing resolution, CRS, search window, route length, diagnostic spacing/sample count, and approximation status.
- `findings`: stable evidence ID, evidence type, environmental component, metric, value, units, source layer, method, and limitations.
- `notAssessed` and `limitations`: explicit boundaries for the current desktop GIS analysis.
- `reportSections`: chapter ID/title, readiness status, template tokens, evidence IDs, and writer instruction. Statuses distinguish GIS screening available, partial screening only, project input required, legal knowledge-base and project input required, and further studies required.
- `templateFields`: current values mapped to named template fields for methodology, GIS findings, not-assessed topics, limitations, and evidence register.

### Writer and MCP workflow

1. Validate project metadata, route alternative IDs, geometry/version, assessment boundary, and requested report type.
2. Run the existing deterministic GIS route diagnostics and assemble `environmentalSectionInput` without changing GIS calculations.
3. Retrieve project-document passages and curated legal/guidance records with source, page, version, jurisdiction, applicability, and stable evidence IDs.
4. Build a chapter evidence package. Keep `GIS_OBSERVATION`, `PROJECT_DOCUMENT_CLAIM`, `GUIDANCE_REFERENCE`, and `MARG_INTERPRETATION` separate; mark chapters as ready, partial, or requiring inputs/studies.
5. Draft each chapter as structured data with factual statements linked to evidence IDs. LLM drafting may interpret evidence but may not calculate GIS metrics, infer legal applicability from proximity, or create missing measurements/approvals.
6. Validate evidence IDs and source locators, numeric values/units, legal-source currency/applicability, missing-input statuses, and report scope. Reject unsupported factual claims or preserve them as unresolved questions.
7. Populate the controlled DOCX template. Generate the contents/index after pagination. At map and approval insertion locations, provide instructions and source requirements; do not embed sample-report images or fabricate approval records.
8. Render DOCX/PDF, run layout and completeness checks, then route to qualified human review. End the report at final findings and recommendations; include only essential evidence schedules before that final chapter.

The initial MCP/DOCX slice implements job handling, evidence validation, and DOCX population. Curated-library retrieval and LLM drafting are available for the environmental report workflow; the Mode 2 project-scoped document workflow is described below.

### Environmental report MCP tools

The Spring MVC MCP server uses stateless Streamable HTTP at `/mcp`, suited to the deployed-service boundary. It exposes:

- `generate_environmental_eia_report`: accepts project metadata, evidence records, evidence-linked report claims, and optionally the existing `metadata.environmentalSectionInput` object from `/api/routes/composite`. The existing GIS section is converted to `GIS_OBSERVATION` evidence without recalculating route metrics.
- `generate_environmental_eia_report_from_library`: retrieves page-grounded passages, assembles optional Mode 1 GIS findings before drafting, supplies those typed observations and limitations to the LLM, validates citations/numbers, and queues writing/rendering.
- `ask_environmental_question`: retrieves source passages and, when an LLM is configured, returns an answer with source IDs and page/chunk references.
- `search_environmental_evidence` and `refresh_environmental_document_library`: inspect and refresh the local PDF index. Sample EIAs are excluded from factual retrieval and remain format references.
- `screen_environmental_legal_applicability`: screens supplied, page-cited project facts against retrieved legal sources. It returns candidate statuses, missing project facts and knowledge-base gaps. It does not issue a final legal determination.
- `get_environmental_eia_report_status`: returns `QUEUED`, `WRITING`, `RENDERING_PDF`, `COMPLETED`, `REJECTED`, or `FAILED`, validation issues, page count, and download URLs.

The PDF library uses PDFBox text extraction and page-local character chunks with overlap. BM25 retrieves likely passages without requiring a hosted vector database. The retriever is an interface boundary for later embedding/vector search. Image-only/scanned pages are not OCR'd and cannot support evidence until OCR is added. The library indexes PDFs directly inside the configured directory, not nested folders.

Upload and index PDFs through `POST /api/eia/library/documents` (`multipart/form-data`, field `file`, PDF only, max 50 MB); inspect `GET /api/eia/library`; refresh with `POST /api/eia/library/refresh`. These EIA browser HTTP endpoints are unauthenticated in this prototype; do not expose them publicly without deployment authentication. The default knowledge directory is the MARG project root when running from `MARG_backend`; set `MARG_EIA_KNOWLEDGE_DIRECTORY` for deployment. Boot-time indexing is off by default; set `MARG_EIA_AUTO_INDEX=true` to build the index after startup, or call the refresh MCP/HTTP tool. Uploaded file names are retained as source provenance. Sample EIA/ESIA documents are classified as format references and excluded from facts.

The Mode 1 browser report panel captures `metadata.environmentalSectionInput` from a composite route response when available and submits it with project metadata to `POST /api/eia/reports/from-library`. It refreshes the PDF index before drafting, polls `GET /api/eia/reports/jobs/{jobId}`, and downloads the finished PDF/DOCX. If route screening is absent, it can still draft from retrieved library evidence and must mark GIS findings unavailable. `projectState` is supplied separately from `studyArea` for legal-source retrieval.

LLM drafting is off by default. Configure `MARG_EIA_LLM_ENABLED=true`, `MARG_EIA_LLM_API_KEY`, and `MARG_EIA_LLM_MODEL`; set `MARG_EIA_LLM_BASE_URL` for an OpenAI-compatible provider. `MARG_EIA_LLM_FALLBACK_MODELS` accepts up to three comma-separated backup model IDs on the same provider endpoint/key. On HTTP 503 only, the client retries the primary once after a bounded exponential delay, then tries each backup at most once with bounded backoff. Other HTTP failures and local validation errors are not retried. The API key is never logged. Logs name the operation and model, not prompts or credentials. Fallback model IDs must be enabled for the configured provider. The key is read only from the environment. RAG results, not model memory, form the drafting context. The output is structured JSON and must pass evidence-ID and numeric checks.

For Gemini's OpenAI-compatible endpoint, configure the base URL as `https://generativelanguage.googleapis.com/v1beta/openai/`. Example model settings (use only model IDs enabled for the API key):

```bash
export MARG_EIA_LLM_MODEL='gemini-3.5-flash-lite'
export MARG_EIA_LLM_FALLBACK_MODELS='gemini-3.6-flash,gemini-3.8-flash,gemini-3.1-flash-lite'
export MARG_EIA_LLM_RETRY_DELAY_MILLIS=500
```

Report jobs now render DOCX and then use a headless LibreOffice installation to paginate PDF, resolve contents page numbers against the rendered pages, and check that pagination stabilizes. Set `MARG_LIBREOFFICE_BIN` if the executable is not on `PATH`. `MARG_EIA_MINIMUM_PDF_PAGES` defaults to 8; a shorter rendered report fails the length gate with its page count rather than being padded with unsupported prose. A successful job provides `/api/eia/reports/{jobId}/document` and `/api/eia/reports/{jobId}/pdf`; the DOCX remains downloadable if PDF rendering fails. This workspace does not have LibreOffice installed, so PDF conversion must be verified in the deployment image. The index and job registry remain in memory and are rebuilt after restart.

The legal-reference inventory is deliberately a screened list, not an exhaustive catalogue. The current local EIA compilation covers amendments through March 2025; the official PARIVESH portal lists a compilation through July 2026, so update the local source before relying on its currency. The file named `regulations_air.pdf` contains the Biological Diversity Rules, 2024, not the Air Act. If no legal-source passages are retrieved, report drafting can continue with an explicit legal-applicability gap; it does not present laws as assessed.

Set `MARG_MCP_BEARER_TOKEN` before calling MCP. Browser EIA and Mode 2 HTTP endpoints are unauthenticated for local development. Set `MARG_EIA_TEMPLATE_PATH` or `MARG_EIA_OUTPUT_DIRECTORY` to override the defaults. Generated DOCX files are downloadable from `/api/eia/reports/{jobId}/document`; job status is also available at `/api/eia/reports/jobs/{jobId}`. The job registry is in memory, so job-status history does not survive an application restart; generated files remain in the configured output directory.

The request contains:

- `projectName`, `projectProponent`, `routeAlternativeName`, `studyArea`, `projectState`, `reportVersion`, and ISO `assessmentDate`.
- `evidence`: records typed as `GIS_OBSERVATION`, `PROJECT_DOCUMENT_CLAIM`, `GUIDANCE_REFERENCE`, or `MARG_INTERPRETATION`. Project-document claims require title, filename, page, chunk ID and excerpt. GIS observations require layer, operation, metric, value, units and limitations. Guidance records require source/version, jurisdiction, applicability, locator and excerpt.
- `claims`: structured report statements with a section ID, an exact template token, evidence IDs, and typed numeric claims with units. Unknown evidence IDs, missing page provenance, ungrounded numbers and unknown template tokens reject the job before a DOCX is written.
- `gisScreening` (optional): the complete existing `environmentalSectionInput` object. Its findings and method metadata are converted into evidence and report claims automatically.

The writer fills the controlled DOCX, includes evidence IDs beside each supplied claim, creates the evidence/reference text, removes internal drafting instructions, and preserves the map and approval insertion-point instructions. Unprovided fields are marked unavailable and require verification. The contents page lists chapters but cannot finalize page numbers until an office/PDF renderer paginates the document. The generated report is an evidence-grounded draft for human review, not a statutory EIA or clearance. No images, approval scans, legal determinations, field measurements or unsupported narrative are generated by this service.

Spring AI MCP Server WebMVC 2.0.2 provides the MCP Streamable HTTP protocol/annotation integration, and Apache POI OOXML 6.0.0 populates DOCX files. These dependencies are isolated to the application adapter and document writer; GIS calculations and existing route endpoints are unchanged.

## MongoDB saved Mode 1 routes

Generated Mode 1 alignments remain in browser memory until the user chooses **Save generated route for EIA**. Saved records are written to the MongoDB `mode1_saved_routes` collection and include WGS84 mainline coordinates, project name/proponent, study area and state, route summary, routing metadata, and the environmental GIS screening snapshot. The home page's Environmental EIA workflow loads saved routes and submits the selected route's stored GIS evidence to the existing report pipeline.

Configure the connection string outside source control:

```sh
export MARG_MONGODB_URI='mongodb+srv://<user>:<password>@<cluster>/<database>?retryWrites=true&w=majority'
```

When `MARG_MONGODB_URI` is unset, Spring uses `mongodb://localhost:27017/marg`. Route planning remains available if MongoDB is offline, but saving and loading routes require a working database. Endpoints are `POST /api/routes/saved`, `GET /api/routes/saved`, `GET /api/routes/saved/{routeId}`, and `GET /api/routes/saved/health` for a database ping.

## Mode 2 project document foundation

Mode 2 project source documents are stored separately from the curated environmental knowledge library. Create a persistent project workspace, upload source PDFs to that project, and retrieve passages with project-scoped filters. The original PDFs and project/document metadata are stored under `MARG_MODE2_PROJECTS_DIRECTORY` (default `data/mode2/projects`). The retrieval index is rebuilt lazily from those stored PDFs after application restart; no database or vector-store dependency is used in this slice.

Browser HTTP endpoints (unauthenticated in this local prototype):

- `POST /api/mode2/projects` with JSON `{"name":"Project name"}` creates a workspace and returns its `projectId`.
- `GET /api/mode2/projects` lists workspaces.
- `POST /api/mode2/projects/{projectId}/documents` uploads a PDF as multipart field `file` (maximum 50 MB).
- `GET /api/mode2/projects/{projectId}/documents` lists document name, SHA-256, page count, chunk count and extraction status.
- `POST /api/mode2/projects/{projectId}/search` with JSON `{"query":"forest diversion and wildlife crossings","limit":8}` returns only that project's passages. Each hit contains a stable evidence ID, document ID, source title, filename, page number, chunk ID and excerpt.
- `POST /api/mode2/projects/{projectId}/screen-alignment` screens a supplied WGS84 GeoJSON LineString using JSON fields `alignmentName`, optional `regionId`, optional `facilityMode`, and `coordinates` as `[longitude, latitude]` pairs.
- `POST /api/mode2/projects/{projectId}/compare-alignment` runs cited fact extraction and GIS screening together, then compares supported numeric pairs. Its JSON body includes `factQuery`, optional `factLimit` (defaults to 20), and the same alignment fields as `screen-alignment`.

MCP tools: `create_mode2_project`, `list_mode2_projects`, `list_mode2_project_documents`, `search_mode2_project_documents`, `ask_mode2_project_question`, `extract_mode2_project_facts`, `screen_mode2_project_alignment`, and `compare_mode2_project_claims_to_gis`. Upload PDFs through the HTTP endpoint, then call the tools with the returned project ID. Q&A and fact extraction retrieve only that project's passages. They return page-grounded evidence IDs; fact extraction additionally requires the model's supporting excerpt to match a retrieved passage and rejects unsupported numbers. Extracted values remain project-document claims pending verification. Each extracted fact carries its source title, filename, page, chunk, and excerpt. The combined comparison currently pairs reported project length with sampled GIS route length, and reported water-crossing count with the GIS drainage/wetland crossing count. It preserves both evidence references, reports absolute and relative differences, labels differences as potential inconsistencies for review, and lists claims for which no comparable GIS metric is configured. It does not compare bridge/culvert inventory counts to GIS crossings because those may represent different things. Numeric agreement does not validate either source; a difference does not establish error or non-compliance. Alignment screening passes an explicit line geometry to the existing `CompositeRouteMetricsService`; it does not ask the LLM to calculate GIS values. The MCP transport uses `MARG_MCP_BEARER_TOKEN`; the Mode 2 browser endpoints do not use a bearer token in this local prototype.

PDFs are validated with PDFBox. Curated-library and Mode 2 project indexing now share `PageTextChunker`, which applies the proposal chunker's page-local natural-break and overlap rules while retaining page/chunk offsets. The configured defaults are 1,200 characters with 200-character overlap; BM25 is the initial retrieval method. Image-only/scanned PDFs are retained and marked `NO_EXTRACTABLE_TEXT`, but are not searchable because OCR is not implemented. Repeated identical PDF content within one project is deduplicated by SHA-256; same-named PDFs with different contents remain separate documents. LLM-backed Q&A and fact extraction require `MARG_EIA_LLM_ENABLED=true`, `MARG_EIA_LLM_API_KEY` and `MARG_EIA_LLM_MODEL`. Without those settings, Q&A returns retrieved passages and fact extraction marks the requested fields unavailable. Curated knowledge passages remain a separate source class; combining them with a project answer is a later evidence-assembly step.

Alignment screening requires an explicit authoritative alignment geometry; the PDF fact extractor does not derive line coordinates from prose or images. Coordinates are WGS84 GeoJSON `[longitude, latitude]` pairs, and every vertex must fit inside one configured regional GIS preset (or a registered ingested corridor). The service runs the existing deterministic route diagnostics against that geometry without snapping, clipping, or optimizing it. The result includes GIS diagnostics, stable `GIS_OBSERVATION` evidence IDs, method metadata, layers not emitted by the active regional pipeline, and screening limitations. The combined comparison operation currently covers selected numeric checks only; it does not validate route geometry from documents, reconcile conflicting claims, or generate alternative alignments.
