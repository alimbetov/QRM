-- ML-R2 parity hardening: invitation must be backed by an actual full-basket,
-- same-origin, free-stock eligible merchant quote. Keep historical decisions immutable.
-- New policy version is required for reevaluation; never rewrite prior decision.
ALTER TABLE ml_seller_invitation
    DROP CONSTRAINT ck_ml_invitation_reason;
ALTER TABLE ml_seller_invitation
    ADD CONSTRAINT ck_ml_invitation_reason CHECK (reason_code IN (
        'MATCHED','EXPIRED','ALREADY_ACCEPTED','NO_ACTIVE_MEMBERSHIP',
        'NO_PUBLIC_COMPATIBLE_SKU','NO_FULFILLMENT_AREA','INSUFFICIENT_STOCK'
    ));
