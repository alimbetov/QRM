-- CAT-R1A: additive typed numeric bounds, immutable attribute schema history,
-- per-fact revision pinning, safe HCC parent references and explicit UOM dimensions.
-- Existing order, inventory, QR and ML event tables remain untouched.
-- This migration is NOT a four-vertical production category seed.

ALTER TABLE catalog_attribute_definition
    ADD COLUMN min_decimal numeric(19,6),
    ADD COLUMN max_decimal numeric(19,6),
    ADD COLUMN schema_revision bigint NOT NULL DEFAULT 1,
    ADD COLUMN fact_scope varchar(20) NOT NULL DEFAULT 'SKU';

-- All legacy numeric attributes were non-negative. Preserve that meaning.
UPDATE catalog_attribute_definition
SET min_decimal=0 WHERE kind='DECIMAL';

ALTER TABLE catalog_attribute_definition DROP CONSTRAINT chk_attribute_kind;
ALTER TABLE catalog_attribute_definition
    ADD CONSTRAINT chk_attribute_kind
    CHECK (kind IN ('ENUM','DECIMAL','BOOLEAN','TEXT','DATE'));
ALTER TABLE catalog_attribute_definition
    ADD CONSTRAINT chk_cat_attr_revision CHECK (schema_revision >= 1),
    ADD CONSTRAINT chk_cat_attr_scope CHECK
        (fact_scope IN ('PRODUCT','SKU','LOT','SERVICE_SPEC','SERVICE_OFFER')),
    ADD CONSTRAINT chk_cat_attr_bounds CHECK (
        (kind='DECIMAL' AND min_decimal IS NOT NULL AND
            (max_decimal IS NULL OR min_decimal<=max_decimal))
        OR
        (kind<>'DECIMAL' AND min_decimal IS NULL AND max_decimal IS NULL)
    );

-- Each schema revision is an immutable historical validation policy.
CREATE TABLE catalog_attribute_schema_revision (
    attribute_id uuid NOT NULL REFERENCES catalog_attribute_definition(id),
    revision bigint NOT NULL CHECK (revision>=1),
    value_kind varchar(16) NOT NULL CHECK (value_kind IN ('ENUM','DECIMAL','BOOLEAN','TEXT','DATE')),
    canonical_unit varchar(16),
    fact_scope varchar(20) NOT NULL CHECK
        (fact_scope IN ('PRODUCT','SKU','LOT','SERVICE_SPEC','SERVICE_OFFER')),
    min_decimal numeric(19,6),
    max_decimal numeric(19,6),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (attribute_id,revision),
    CONSTRAINT chk_cat_attr_rev_bounds CHECK (
        (value_kind='DECIMAL' AND min_decimal IS NOT NULL
            AND (max_decimal IS NULL OR min_decimal<=max_decimal))
        OR (value_kind<>'DECIMAL' AND min_decimal IS NULL AND max_decimal IS NULL)
    )
);

INSERT INTO catalog_attribute_schema_revision
    (attribute_id,revision,value_kind,canonical_unit,fact_scope,min_decimal,max_decimal)
SELECT id,schema_revision,kind,canonical_unit,fact_scope,min_decimal,max_decimal
FROM catalog_attribute_definition;

CREATE FUNCTION catalog_attribute_definition_policy_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.kind='DECIMAL' AND NEW.min_decimal IS NULL THEN
            NEW.min_decimal := 0;
        END IF;
        IF NEW.schema_revision IS DISTINCT FROM 1 THEN
            RAISE EXCEPTION 'New attribute must start at revision 1';
        END IF;
    ELSE
        IF (NEW.id,NEW.code,NEW.kind,NEW.canonical_unit,NEW.fact_scope)
            IS DISTINCT FROM (OLD.id,OLD.code,OLD.kind,OLD.canonical_unit,OLD.fact_scope) THEN
            RAISE EXCEPTION 'Attribute identity, type, dimension and scope are immutable';
        END IF;
        IF (NEW.min_decimal,NEW.max_decimal)
            IS DISTINCT FROM (OLD.min_decimal,OLD.max_decimal) THEN
            IF NEW.schema_revision IS DISTINCT FROM OLD.schema_revision+1 THEN
                RAISE EXCEPTION 'Numeric bounds require exactly one new schema revision';
            END IF;
        ELSIF NEW.schema_revision IS DISTINCT FROM OLD.schema_revision THEN
            RAISE EXCEPTION 'Cannot advance schema revision without changed bounds';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_cat_attribute_definition_policy_guard
BEFORE INSERT OR UPDATE ON catalog_attribute_definition
FOR EACH ROW EXECUTE FUNCTION catalog_attribute_definition_policy_guard();

CREATE FUNCTION catalog_attribute_record_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' OR NEW.schema_revision IS DISTINCT FROM OLD.schema_revision THEN
        INSERT INTO catalog_attribute_schema_revision
            (attribute_id,revision,value_kind,canonical_unit,fact_scope,min_decimal,max_decimal)
        VALUES
            (NEW.id,NEW.schema_revision,NEW.kind,NEW.canonical_unit,NEW.fact_scope,
             NEW.min_decimal,NEW.max_decimal);
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_cat_attribute_record_revision
AFTER INSERT OR UPDATE ON catalog_attribute_definition
FOR EACH ROW EXECUTE FUNCTION catalog_attribute_record_revision();

CREATE FUNCTION catalog_attribute_revision_no_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Attribute schema history is immutable';
END;
$$;
CREATE TRIGGER trg_cat_attribute_revision_immutable
BEFORE UPDATE OR DELETE ON catalog_attribute_schema_revision
FOR EACH ROW EXECUTE FUNCTION catalog_attribute_revision_no_mutation();

-- Existing SKU facts remain linked to their originally approved legacy revision.
ALTER TABLE catalog_sku_attribute_value
    ADD COLUMN definition_revision bigint NOT NULL DEFAULT 1;
ALTER TABLE catalog_sku_attribute_value
    ADD CONSTRAINT fk_sku_attribute_schema_revision
    FOREIGN KEY (attribute_id,definition_revision)
    REFERENCES catalog_attribute_schema_revision(attribute_id,revision);

-- Reject direct-SQL bypass of declared scope, signed numeric bounds and DATE.
CREATE FUNCTION catalog_validate_sku_attribute_policy()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    revision_info catalog_attribute_schema_revision%ROWTYPE;
    current_rev bigint;
    number_value numeric;
    date_value date;
BEGIN
    SELECT d.schema_revision INTO current_rev
    FROM catalog_attribute_definition d WHERE d.id=NEW.attribute_id;
    SELECT * INTO revision_info FROM catalog_attribute_schema_revision
    WHERE attribute_id=NEW.attribute_id AND revision=NEW.definition_revision;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown schema revision for SKU attribute';
    END IF;
    IF revision_info.fact_scope<>'SKU' THEN
        RAISE EXCEPTION 'Only SKU-scoped attribute policies can be attached to SKU facts';
    END IF;
    IF NEW.definition_revision<>current_rev THEN
        RAISE EXCEPTION 'New/edited SKU facts must use the current schema revision';
    END IF;
    IF NEW.canonical_value IS NULL OR NEW.canonical_value='' OR
       length(NEW.canonical_value)>256 THEN
        RAISE EXCEPTION 'Empty or oversized SKU fact';
    END IF;
    IF revision_info.value_kind='DECIMAL' THEN
        IF NEW.canonical_value !~ '^-?(0|[1-9][0-9]*)(\.[0-9]+)?$' THEN
            RAISE EXCEPTION 'Noncanonical decimal attribute';
        END IF;
        BEGIN
            number_value := NEW.canonical_value::numeric;
        EXCEPTION WHEN numeric_value_out_of_range OR invalid_text_representation THEN
            RAISE EXCEPTION 'Out-of-range decimal attribute';
        END;
        IF number_value<revision_info.min_decimal OR
           (revision_info.max_decimal IS NOT NULL AND number_value>revision_info.max_decimal) OR
           scale(number_value)>6 OR length(replace(replace(NEW.canonical_value,'.',''),'-',''))>19 THEN
            RAISE EXCEPTION 'Decimal attribute violates its pinned bounds or precision';
        END IF;
    ELSIF revision_info.value_kind='DATE' THEN
        IF NEW.canonical_value !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
            RAISE EXCEPTION 'DATE must use strict ISO-8601 YYYY-MM-DD';
        END IF;
        BEGIN
            date_value := NEW.canonical_value::date;
        EXCEPTION WHEN datetime_field_overflow OR invalid_datetime_format THEN
            RAISE EXCEPTION 'Invalid calendar date';
        END;
        IF to_char(date_value,'YYYY-MM-DD')<>NEW.canonical_value THEN
            RAISE EXCEPTION 'Noncanonical calendar date';
        END IF;
    ELSIF revision_info.value_kind='BOOLEAN' THEN
        IF NEW.canonical_value NOT IN ('true','false') THEN
            RAISE EXCEPTION 'Invalid boolean attribute';
        END IF;
    ELSIF revision_info.value_kind='ENUM' THEN
        IF NEW.canonical_value !~ '^[A-Z][A-Z0-9_]{0,63}$' THEN
            RAISE EXCEPTION 'Invalid enum code';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_cat_validate_sku_attribute_policy
BEFORE INSERT OR UPDATE ON catalog_sku_attribute_value
FOR EACH ROW EXECUTE FUNCTION catalog_validate_sku_attribute_policy();

-- HCC remains a string identity. A specified parent must be an immediate
-- same-type ancestor; legacy rows with omitted parent are not remapped here.
CREATE FUNCTION catalog_hcc_depth_sql(code varchar)
RETURNS integer LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    segments text[];
    segment text;
    depth integer := 0;
    ended boolean := false;
BEGIN
    IF code IS NULL OR code !~ '^[0-9]{20}$' THEN
        RAISE EXCEPTION 'Invalid HCC width';
    END IF;
    segments:=ARRAY[
       substr(code,1,2),substr(code,3,2),substr(code,5,3),
       substr(code,8,5),substr(code,13,8)
    ];
    FOREACH segment IN ARRAY segments LOOP
        IF segment ~ '^0+$' THEN
            ended:=true;
        ELSE
            IF ended THEN RAISE EXCEPTION 'HCC has a gap'; END IF;
            depth:=depth+1;
        END IF;
    END LOOP;
    IF depth=0 THEN RAISE EXCEPTION 'Empty HCC'; END IF;
    RETURN depth;
END;
$$;

CREATE FUNCTION catalog_category_structure_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent_code varchar(20);
    parent_type varchar(16);
    parent_depth integer;
    prefix_width integer;
BEGIN
    IF TG_OP='UPDATE' AND (NEW.hcc_code,NEW.parent_id,NEW.product_type)
        IS DISTINCT FROM (OLD.hcc_code,OLD.parent_id,OLD.product_type) THEN
        RAISE EXCEPTION 'Changing HCC identity, parent or type requires separate migration';
    END IF;
    PERFORM catalog_hcc_depth_sql(NEW.hcc_code);
    IF NEW.parent_id IS NOT NULL THEN
        IF NEW.parent_id=NEW.id THEN RAISE EXCEPTION 'HCC cannot parent itself'; END IF;
        SELECT hcc_code,product_type INTO parent_code,parent_type
          FROM catalog_category WHERE id=NEW.parent_id;
        IF NOT FOUND THEN RAISE EXCEPTION 'Unknown HCC parent'; END IF;
        parent_depth:=catalog_hcc_depth_sql(parent_code);
        IF catalog_hcc_depth_sql(NEW.hcc_code)<>parent_depth+1
           OR NEW.product_type<>parent_type THEN
            RAISE EXCEPTION 'HCC parent must be immediate and same type';
        END IF;
        prefix_width:=CASE parent_depth WHEN 1 THEN 2 WHEN 2 THEN 4
                                       WHEN 3 THEN 7 WHEN 4 THEN 12 ELSE 20 END;
        IF left(NEW.hcc_code,prefix_width)<>left(parent_code,prefix_width) THEN
            RAISE EXCEPTION 'HCC parent code must be an actual ancestor';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_catalog_category_structure_guard
BEFORE INSERT OR UPDATE ON catalog_category
FOR EACH ROW EXECUTE FUNCTION catalog_category_structure_guard();

-- Existing exact checkout unit enum is preserved. These are only canonical
-- dimension definitions for information/comparison, NOT automatic checkout
-- conversion and NOT BOX/PACK inference.
CREATE TABLE catalog_unit (
    code varchar(20) PRIMARY KEY,
    dimension varchar(16) NOT NULL CHECK
        (dimension IN ('COUNT','MASS','VOLUME','LENGTH','AREA','TIME','SERVICE','LICENSE')),
    factor_to_base numeric(19,9) NOT NULL CHECK (factor_to_base>0),
    active boolean NOT NULL DEFAULT true
);
INSERT INTO catalog_unit(code,dimension,factor_to_base) VALUES
    ('PCS','COUNT',1),
    ('KG','MASS',1),
    ('G','MASS',0.001),
    ('L','VOLUME',1),
    ('ML','VOLUME',0.001),
    ('M','LENGTH',1),
    ('M2','AREA',1),
    ('M3','VOLUME',1000),
    ('HOUR','TIME',1),
    ('SERVICE','SERVICE',1),
    ('LICENSE','LICENSE',1);

CREATE FUNCTION catalog_unit_identity_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Unit dimension and factor registry cannot be mutated in place';
END;
$$;
CREATE TRIGGER trg_catalog_unit_identity_guard
BEFORE UPDATE OR DELETE ON catalog_unit
FOR EACH ROW EXECUTE FUNCTION catalog_unit_identity_guard();
