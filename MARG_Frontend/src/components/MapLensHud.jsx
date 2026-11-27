import { BASEMAP_ORDER, BASEMAPS, getBasemapConfig } from "../layers/basemaps";
import { LAYER_BY_ID } from "../layers/layerCatalog";

export default function MapLensHud({
  baseMapType,
  lockBasemap,
  onSelectBasemap,
  onResetBasicMap,
  onToggleLockBasemap,
  onZoomIndia,
  activeLayerId,
  pinnedLayerIds,
  onUnpinLayer,
  onExitLens,
}) {
  const activeCfg = activeLayerId ? LAYER_BY_ID[activeLayerId] : null;
  const pinnedList = Array.from(pinnedLayerIds || [])
    .filter((id) => id !== activeLayerId)
    .map((id) => LAYER_BY_ID[id])
    .filter(Boolean);
  const currentBase = getBasemapConfig(baseMapType);

  return (
    <>
      {/* Top-Right Map Background & Navigation Controls */}
      <div className="map-floating-bar map-lens-controls">
        {/* Manual Map Style Picker */}
        <div className="basemap-select-wrap" title="Choose Map Background Style">
          <span className="basemap-select-icon">{currentBase.icon}</span>
          <select
            className="map-basemap-select"
            value={baseMapType}
            onChange={(e) => onSelectBasemap(e.target.value)}
            aria-label="Choose Map Style"
          >
            {BASEMAP_ORDER.map((id) => {
              const b = BASEMAPS[id];
              return (
                <option key={id} value={id}>
                  {b.shortLabel}
                </option>
              );
            })}
          </select>
        </div>

        {/* One-Click Return to Street Map — keeps active view open & auto-switch enabled */}
        {baseMapType !== "osm" && (
          <button
            type="button"
            className="map-control-btn basic-map-btn"
            onClick={onResetBasicMap}
            title="Switch back to the clean Street Map while keeping your selected topic open"
          >
            🗺️ Street Map
          </button>
        )}

        {/* Auto-Switch vs Keep Current Map Style Toggle */}
        <button
          type="button"
          className={`map-control-btn lock-map-btn ${lockBasemap ? "locked" : ""}`}
          onClick={onToggleLockBasemap}
          title={
            lockBasemap
              ? "Map background is locked. Click to let topics pick the best map style automatically."
              : "Map background switches automatically for each topic. Click to keep your current map style."
          }
        >
          {lockBasemap ? "🔒 Map Locked" : "🔓 Auto Map"}
        </button>

        <button
          type="button"
          className="map-control-btn"
          onClick={onZoomIndia}
          title="Zoom out to see all of India"
        >
          🇮🇳 All India
        </button>
      </div>

      {/* Bottom-Left Active Map View Legend */}
      {(activeCfg || pinnedList.length > 0) && (
        <div className="map-lens-legend-hud">
          <div className="lens-hud-header">
            <div className="lens-hud-title">
              {activeCfg ? (
                <>
                  <span
                    className="lens-hud-badge"
                    style={{ background: activeCfg.accentColor }}
                  >
                    #{activeCfg.number}
                  </span>
                  <span>
                    {activeCfg.icon} {activeCfg.shortName}
                  </span>
                </>
              ) : (
                <span>📌 Pinned Map Views</span>
              )}
            </div>
            <button
              type="button"
              className="lens-hud-exit"
              onClick={onExitLens}
              title="Return to the clean Main Route view"
            >
              ✕ Main Route
            </button>
          </div>

          {activeCfg && activeCfg.legend && activeCfg.legend.length > 0 && (
            <div className="lens-hud-swatches">
              {activeCfg.legend.map((item) => (
                <div key={item.key} className="lens-hud-swatch">
                  <span
                    className="lens-swatch-dot"
                    style={{ background: item.color }}
                  />
                  <span>{item.label}</span>
                </div>
              ))}
            </div>
          )}

          {pinnedList.length > 0 && (
            <div className="lens-hud-pinned-row">
              <span className="lens-pinned-label">Pinned:</span>
              {pinnedList.map((p) => (
                <span
                  key={p.id}
                  className="lens-pinned-chip"
                  style={{ borderColor: p.accentColor }}
                >
                  <span>
                    #{p.number} {p.shortName}
                  </span>
                  <button
                    type="button"
                    onClick={() => onUnpinLayer(p.id)}
                    aria-label={`Unpin ${p.shortName}`}
                  >
                    ×
                  </button>
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </>
  );
}
