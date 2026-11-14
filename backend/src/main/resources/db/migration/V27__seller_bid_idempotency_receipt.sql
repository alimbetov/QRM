-- R3: durable, immutable response for retry-safe seller bidding.
-- The receipt and immutable SellerBid + Quote commit in the same transaction.
CREATE TABLE seller_bid_http_receipt (
    seller_id uuid NOT NULL,
    invitation_id uuid NOT NULL REFERENCES ml_seller_invitation_inbox(invitation_id),
    request_key varchar(80) NOT NULL,
    fingerprint char(64) NOT NULL,
    bid_id uuid NOT NULL UNIQUE REFERENCES seller_bid_snapshot(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (seller_id,invitation_id,request_key),
    CONSTRAINT chk_bid_http_receipt_sha CHECK(fingerprint ~ '^[0-9a-f]{64}$')
);
