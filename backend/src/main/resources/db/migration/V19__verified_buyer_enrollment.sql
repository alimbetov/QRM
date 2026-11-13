-- Explicit OIDC-backed buyer enrollment. An email_verified JWT is required at the
-- HTTP boundary. Phone is collected for contact only, not an authentication key.
-- Existing buyer_account_link rows remain valid and may complete their profile.
CREATE TABLE buyer_profile (
    buyer_id uuid PRIMARY KEY,
    nickname varchar(40) NOT NULL,
    phone_ciphertext varchar(160) NOT NULL,
    phone_last_four char(4) NOT NULL,
    phone_verified boolean NOT NULL DEFAULT false,
    consent_to_store_phone_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT chk_buyer_phone_unverified CHECK (phone_verified=false),
    CONSTRAINT chk_buyer_phone_last_four CHECK (phone_last_four ~ '^[0-9]{4}$')
);
CREATE INDEX idx_buyer_profile_created_at ON buyer_profile(created_at);
-- Deliberately no unique constraint on unverified phone: possession is not proven.
-- No email is duplicated in the application DB: verified email remains in the IdP.
