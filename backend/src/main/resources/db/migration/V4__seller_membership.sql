-- Trusted membership is provisioned only by approved identity/merchant administration.
-- It must NEVER be created from a user-controlled sellerId or JWT seller_id claim.
CREATE TABLE seller_membership (
    id uuid PRIMARY KEY,
    issuer varchar(255) NOT NULL,
    subject varchar(255) NOT NULL,
    seller_id uuid NOT NULL,
    role varchar(24) NOT NULL,
    active boolean NOT NULL,
    version bigint,
    CONSTRAINT uk_membership_issuer_subject_seller UNIQUE(issuer,subject,seller_id),
    CONSTRAINT chk_membership_role CHECK (role IN ('CATALOG_EDITOR','SELLER_ADMIN','VIEWER'))
);
CREATE INDEX idx_membership_seller ON seller_membership(seller_id);
