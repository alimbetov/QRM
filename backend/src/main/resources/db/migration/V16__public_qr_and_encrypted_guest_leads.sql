-- Public QR link is random 256-bit opaque token issued by trusted publishing transaction.
CREATE TABLE qr_public_link (
    id uuid PRIMARY KEY,
    version bigint,
    listing_id uuid NOT NULL REFERENCES catalog_listing(id),
    token varchar(64) NOT NULL,
    issued_at timestamptz NOT NULL,
    CONSTRAINT uk_qr_link_listing UNIQUE(listing_id),
    CONSTRAINT uk_qr_link_token UNIQUE(token),
    CONSTRAINT chk_qr_token_entropy CHECK (length(token)>=43)
);
CREATE TABLE qr_purchase_lead (
    id uuid PRIMARY KEY,
    version bigint,
    public_link_id uuid NOT NULL REFERENCES qr_public_link(id),
    listing_id uuid NOT NULL REFERENCES catalog_listing(id),
    idempotency_key varchar(80) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    phone_ciphertext varchar(256) NOT NULL,
    source_digest varchar(64) NOT NULL,
    consent_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_qr_lead_request UNIQUE(public_link_id,idempotency_key)
);
CREATE INDEX idx_qr_lead_listing_time ON qr_purchase_lead(listing_id,created_at);
CREATE TABLE qr_lead_rate_bucket (
    listing_id uuid NOT NULL REFERENCES catalog_listing(id),
    source_digest varchar(64) NOT NULL,
    window_bucket bigint NOT NULL,
    attempts integer NOT NULL,
    PRIMARY KEY(listing_id,source_digest,window_bucket),
    CONSTRAINT chk_qr_lead_rate CHECK(attempts BETWEEN 1 AND 5)
);
