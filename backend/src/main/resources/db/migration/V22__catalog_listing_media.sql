-- SEARCH-S2: seller-owned listing media metadata. Binary data stays outside PG.
-- A DB guard serializes media writes with SellerListing publication.
-- Legacy PUBLIC listings may have zero media (no breaking publication constraint).
CREATE TABLE catalog_listing_media (
    id uuid PRIMARY KEY,
    listing_id uuid NOT NULL REFERENCES catalog_listing(id),
    object_key varchar(256) NOT NULL,
    content_type varchar(32) NOT NULL,
    sha256 char(64) NOT NULL,
    width integer NOT NULL,
    height integer NOT NULL,
    sort_position integer NOT NULL,
    is_cover boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_listing_media_object_key UNIQUE(object_key),
    CONSTRAINT chk_listing_media_dimensions CHECK (width BETWEEN 1 AND 8192 AND height BETWEEN 1 AND 8192
        AND width::bigint * height::bigint <= 16000000),
    CONSTRAINT chk_listing_media_position CHECK (sort_position BETWEEN 0 AND 19),
    CONSTRAINT chk_listing_media_checksum CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_listing_media_type CHECK (content_type='image/jpeg'),
    CONSTRAINT chk_listing_media_status CHECK (status IN ('STAGED','READY','REJECTED','REVOKED'))
);
CREATE INDEX idx_listing_media_gallery ON catalog_listing_media(listing_id,sort_position,id)
    WHERE status='READY';
CREATE UNIQUE INDEX uk_listing_media_single_ready_cover ON catalog_listing_media(listing_id)
    WHERE is_cover=true AND status='READY';

-- Applies even to direct SQL, not just JPA. After PUBLIC only controlled
-- revocation of a READY asset is allowed; files remain immutable.
CREATE FUNCTION catalog_assert_draft_media()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_listing uuid;
    current_state varchar(16);
BEGIN
    IF TG_OP='DELETE' THEN
        target_listing:=OLD.listing_id;
    ELSE
        target_listing:=NEW.listing_id;
    END IF;

    IF TG_OP='UPDATE' AND NEW.listing_id IS DISTINCT FROM OLD.listing_id THEN
        RAISE EXCEPTION 'Moving media between listings is forbidden';
    END IF;
    SELECT status INTO current_state
      FROM catalog_listing WHERE id=target_listing FOR UPDATE;

    IF current_state IS DISTINCT FROM 'DRAFT' THEN
        IF TG_OP='UPDATE' AND OLD.status='READY' AND NEW.status='REVOKED'
           AND NEW.id=OLD.id AND NEW.listing_id=OLD.listing_id
           AND NEW.object_key=OLD.object_key AND NEW.content_type=OLD.content_type
           AND NEW.sha256=OLD.sha256 AND NEW.width=OLD.width
           AND NEW.height=OLD.height AND NEW.sort_position=OLD.sort_position
           AND NEW.is_cover=OLD.is_cover AND NEW.created_at=OLD.created_at
           AND NEW.version=OLD.version THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Media facts can be modified only for a DRAFT listing';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_catalog_media_draft_only
    BEFORE INSERT OR UPDATE OR DELETE ON catalog_listing_media
    FOR EACH ROW EXECUTE FUNCTION catalog_assert_draft_media();
