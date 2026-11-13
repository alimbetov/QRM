-- SEARCH-S1: curated multilingual aliases for the existing HCC tree.
-- Approved terms only; never infer a product identity or seller authorization here.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE catalog_search_term (
    id uuid PRIMARY KEY,
    term varchar(160) NOT NULL,
    normalized_term varchar(160) NOT NULL,
    locale varchar(2) NOT NULL,
    status varchar(16) NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_search_term_locale_normalized UNIQUE(locale,normalized_term),
    CONSTRAINT chk_search_term_locale CHECK(locale IN ('ru','kk','en','zh')),
    CONSTRAINT chk_search_term_status CHECK(status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT chk_search_term_values CHECK(
        length(btrim(term)) BETWEEN 1 AND 160
        AND length(btrim(normalized_term)) BETWEEN 1 AND 160
        AND revision >= 1
    )
);

CREATE TABLE catalog_term_category (
    term_id uuid NOT NULL REFERENCES catalog_search_term(id) ON DELETE CASCADE,
    category_id uuid NOT NULL REFERENCES catalog_category(id) ON DELETE RESTRICT,
    relation_type varchar(24) NOT NULL,
    weight integer NOT NULL DEFAULT 100,
    PRIMARY KEY(term_id,category_id,relation_type),
    CONSTRAINT chk_search_relation_type CHECK (
        relation_type IN ('SYNONYM','CATEGORY_TERM','RELATED','SERVICE_INTENT')
    ),
    CONSTRAINT chk_search_relation_weight CHECK(weight BETWEEN 1 AND 100)
);
CREATE INDEX idx_search_term_category_category ON catalog_term_category(category_id,term_id);

-- Cursor versions change on all curated dictionary mutations, including removals.
CREATE TABLE catalog_search_dictionary_revision (
    id smallint PRIMARY KEY CHECK(id=1),
    revision bigint NOT NULL CHECK(revision>=1)
);
INSERT INTO catalog_search_dictionary_revision(id,revision) VALUES(1,1);

CREATE FUNCTION catalog_bump_search_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE catalog_search_dictionary_revision
       SET revision=revision+1 WHERE id=1;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_search_term_revision
AFTER INSERT OR UPDATE OR DELETE ON catalog_search_term
FOR EACH STATEMENT EXECUTE FUNCTION catalog_bump_search_revision();
CREATE TRIGGER trg_search_category_revision
AFTER INSERT OR UPDATE OR DELETE ON catalog_term_category
FOR EACH STATEMENT EXECUTE FUNCTION catalog_bump_search_revision();
