# MARG Greenfield Route Planner and Environmental EIA

The React interface calls the Spring Boot GIS backend for Mode 1 route planning. After a route is generated, its environmental GIS screening can be sent through environmental knowledge retrieval, legal-source screening, structured drafting, validation, DOCX generation, and PDF rendering.

## Run

Run the Spring Boot application from `MARG_backend` in one terminal. Then, from this directory:

```sh
npm install
npm run dev
```

The Vite development server proxies `/api` to `http://localhost:8080`.

## Generate a Mode 1 environmental report

1. Configure `MARG_EIA_LLM_ENABLED=true`, `MARG_EIA_LLM_API_KEY`, and `MARG_EIA_LLM_MODEL` in the backend environment.
2. No bearer token is needed for browser HTTP endpoints. The MCP transport remains protected by `MARG_MCP_BEARER_TOKEN`.
3. Ensure environmental PDFs are in `MARG_EIA_KNOWLEDGE_DIRECTORY` (by default, the MARG project root when the backend is launched from `MARG_backend`).
4. Enter project details, refresh the PDF library, and generate the environmental report. Route GIS screening is included when available; without a successful route, the report marks GIS findings unavailable.
5. The panel polls the report job and offers PDF and DOCX downloads after rendering finishes.

Browser HTTP endpoints are unauthenticated in this prototype for local use. Do not expose the backend publicly without adding deployment authentication. The MCP transport still requires the bearer token.

## Save Mode 1 routes and generate EIA reports

Set `MARG_MONGODB_URI` in the backend environment to your MongoDB connection string. The default local URI is `mongodb://localhost:27017/marg`. In Mode 1, generate an alignment, enter or confirm the project details, then click **Save generated route for EIA**. The EIA reports workflow lists saved routes and uses the selected route's stored coordinates and GIS screening when generating a report.

The legal screen needs retrievable official legal-source passages. If none are found, report generation can proceed with an explicit legal-applicability gap; it must not imply that laws were assessed. LibreOffice must be installed for PDF rendering. The report is preliminary desktop decision support and requires qualified review.

GeoJSON uses `[longitude, latitude]`; Leaflet map positions use `[latitude, longitude]`.
