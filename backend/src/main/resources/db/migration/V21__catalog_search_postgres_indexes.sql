-- SEARCH-S1: PostgreSQL-native indexes over authoritative Catalog facts.
-- We deliberately DO NOT materialize catalog_search_document yet: a partial,
-- non-transactional read model could leak drafts or lag behind publication.
-- The live search read service joins authoritative Listing/Category/QR on every request.
CREATE INDEX idx_search_term_trgm
    ON catalog_search_term USING gin (normalized_term gin_trgm_ops)
    WHERE status='ACTIVE';

CREATE INDEX idx_search_product_trgm
    ON catalog_product USING gin (lower(canonical_name) gin_trgm_ops);

CREATE INDEX idx_search_product_fts
    ON catalog_product USING gin (to_tsvector('simple',lower(canonical_name)));

CREATE INDEX idx_search_public_listing_product
    ON catalog_listing(product_id,id) WHERE status='PUBLIC';

CREATE INDEX idx_search_active_category_parent
    ON catalog_category(parent_id,id) WHERE active=true;
