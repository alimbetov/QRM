# Location geocoordinates — approved direction

InventoryLocation has optional **WGS84 latitude/longitude** for real pickup/dispatch point. Store as numeric(10,7) or equivalent precise decimal, validate latitude -90..90 and longitude -180..180 as a pair, with optional positional accuracy/source metadata for audited updates. Current `GeoPoint` is a validated Java 21 value object; the full InventoryLocation Entity is the next slice.

A warehouse has `locationType = WAREHOUSE | SHOP | PICKUP_POINT | SERVICE_POINT`, `publicPickupEnabled`, seller ownership, structured KZ region/city/address. Public listing may display city or generalized area. **Only public-enabled customer-facing points** may reveal exact coordinate/address in buyer-facing selected fulfillment DTOs. Private warehouses remain concealed even if they have geocoordinates.

Future last-mile delivery: route from **confirmed inventory/source location** to a buyer-provided delivery destination only after consent and authorization. Buyer delivery address/geo is sensitive PII; avoid embedding raw coordinates in public QR or logs. Do not expose user live GPS or derive their location without permission. Support `geolocationSource = MANUAL_PIN | GEOCODED_ADDRESS | GPS_WITH_CONSENT`; avoid claiming geocoding accuracy until verified. Initial PostgreSQL numeric coordinates, later PostGIS `geography(Point,4326)` only if distance/nearest-point queries justify extension and data migration.

Tests: GEO-01 valid boundary pairs, GEO-02 reject invalid/partial coordinates, GEO-03 seller-only edit, GEO-04 public point precise coordinate visibility, GEO-05 private warehouse leaks forbidden, GEO-06 authorized delivery ETA uses confirmed origin and consented destination, GEO-07 address/point change retains historical delivery snapshot and audits actor, GEO-08 reject cross-seller coordinates assignment.

This is a design addition, not a completed delivery service or geocoded InventoryLocation persistence.
