-- SEARCH-S2B: database-backed immutable media lifecycle audit.
-- No binary/image content or buyer PII in audit records.
CREATE TABLE catalog_media_audit_event (
    id uuid PRIMARY KEY,
    media_id uuid NOT NULL REFERENCES catalog_listing_media(id),
    listing_id uuid NOT NULL REFERENCES catalog_listing(id),
    seller_id uuid NOT NULL,
    event_type varchar(16) NOT NULL,
    sha256 char(64) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_media_audit_event_type CHECK(event_type IN ('UPLOADED','REVOKED','MIGRATED'))
);
CREATE INDEX idx_media_audit_listing ON catalog_media_audit_event(listing_id,recorded_at,id);

-- Backfill all pre-existing media rows from V22; their true uploader identity is
-- not retrospectively knowable. seller_id here is Listing owner, not JWT sub.
INSERT INTO catalog_media_audit_event(id,media_id,listing_id,seller_id,event_type,sha256)
SELECT gen_random_uuid(),m.id,m.listing_id,l.seller_id,'MIGRATED',m.sha256
FROM catalog_listing_media m JOIN catalog_listing l ON l.id=m.listing_id;

CREATE FUNCTION catalog_media_audit_write()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner uuid;
BEGIN
    SELECT seller_id INTO owner FROM catalog_listing WHERE id=NEW.listing_id;
    IF TG_OP='INSERT' THEN
        INSERT INTO catalog_media_audit_event
            (id,media_id,listing_id,seller_id,event_type,sha256)
        VALUES (gen_random_uuid(),NEW.id,NEW.listing_id,owner,'UPLOADED',NEW.sha256);
    ELSIF OLD.status IS DISTINCT FROM NEW.status AND NEW.status='REVOKED' THEN
        INSERT INTO catalog_media_audit_event
            (id,media_id,listing_id,seller_id,event_type,sha256)
        VALUES (gen_random_uuid(),NEW.id,NEW.listing_id,owner,'REVOKED',NEW.sha256);
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_catalog_media_audit_insert
AFTER INSERT ON catalog_listing_media
FOR EACH ROW EXECUTE FUNCTION catalog_media_audit_write();
CREATE TRIGGER trg_catalog_media_audit_status
AFTER UPDATE OF status ON catalog_listing_media
FOR EACH ROW EXECUTE FUNCTION catalog_media_audit_write();

CREATE FUNCTION catalog_media_audit_append_only()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Media audit is append-only';
END;
$$;
CREATE TRIGGER trg_media_audit_no_mutation
BEFORE UPDATE OR DELETE ON catalog_media_audit_event
FOR EACH ROW EXECUTE FUNCTION catalog_media_audit_append_only();

CREATE TABLE catalog_media_orphan_gc_event (
    id uuid PRIMARY KEY,
    object_key varchar(256) NOT NULL,
    deleted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER trg_media_gc_audit_no_mutation
BEFORE UPDATE OR DELETE ON catalog_media_orphan_gc_event
FOR EACH ROW EXECUTE FUNCTION catalog_media_audit_append_only();
