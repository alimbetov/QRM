-- ML-R3.0: authenticated seller view-ack + append-only opt-in history.
-- A queued/delivered internal invitation is NOT proof that a merchant viewed it.
-- No historical view events are backfilled or inferred.
CREATE UNIQUE INDEX uk_ml_inbox_invitation_lineage
  ON ml_seller_invitation_inbox(invitation_id,demand_id,seller_id);

CREATE TABLE ml_seller_invitation_view (
    invitation_id uuid PRIMARY KEY,
    demand_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    requested_lines smallint NOT NULL CHECK (requested_lines BETWEEN 1 AND 50),
    CONSTRAINT fk_ml_view_authoritative_inbox
      FOREIGN KEY(invitation_id,demand_id,seller_id)
      REFERENCES ml_seller_invitation_inbox(invitation_id,demand_id,seller_id)
);
CREATE INDEX idx_ml_view_cutoff ON ml_seller_invitation_view(recorded_at,viewed_at,invitation_id);
CREATE INDEX idx_ml_view_demand_seller ON ml_seller_invitation_view(demand_id,seller_id,viewed_at);
CREATE TRIGGER trg_ml_view_append_only
  BEFORE UPDATE OR DELETE ON ml_seller_invitation_view
  FOR EACH ROW EXECUTE FUNCTION ml_r2_deny_history_mutation();

-- Only changes observed AFTER this migration may be used as consent history.
-- Neither buyer identity, phone, email, IP nor private address is retained.
CREATE TABLE ml_invitation_consent_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope varchar(16) NOT NULL CHECK (scope IN ('DEMAND','SELLER')),
    object_id uuid NOT NULL,
    enabled boolean NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_ml_consent_history ON ml_invitation_consent_audit(scope,object_id,recorded_at);
CREATE TRIGGER trg_ml_consent_audit_append_only
  BEFORE UPDATE OR DELETE ON ml_invitation_consent_audit
  FOR EACH ROW EXECUTE FUNCTION ml_r2_deny_history_mutation();

CREATE FUNCTION ml_r3_audit_consent_change()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE audit_scope varchar(16); audit_object uuid; audit_enabled boolean;
BEGIN
    IF TG_TABLE_NAME='ml_buyer_demand_invitation_consent' THEN
        audit_scope:='DEMAND';
        IF TG_OP='DELETE' THEN
            audit_object:=OLD.demand_id; audit_enabled:=false;
        ELSE
            audit_object:=NEW.demand_id; audit_enabled:=NEW.enabled;
        END IF;
    ELSIF TG_TABLE_NAME='ml_seller_invitation_preference' THEN
        audit_scope:='SELLER';
        IF TG_OP='DELETE' THEN
            audit_object:=OLD.seller_id; audit_enabled:=false;
        ELSE
            audit_object:=NEW.seller_id; audit_enabled:=NEW.in_app_enabled;
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported consent history table';
    END IF;
    INSERT INTO ml_invitation_consent_audit(scope,object_id,enabled)
    VALUES (audit_scope,audit_object,audit_enabled);
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_ml_r3_buyer_consent_history
  AFTER INSERT OR UPDATE OR DELETE ON ml_buyer_demand_invitation_consent
  FOR EACH ROW EXECUTE FUNCTION ml_r3_audit_consent_change();
CREATE TRIGGER trg_ml_r3_seller_preference_history
  AFTER INSERT OR UPDATE OR DELETE ON ml_seller_invitation_preference
  FOR EACH ROW EXECUTE FUNCTION ml_r3_audit_consent_change();
