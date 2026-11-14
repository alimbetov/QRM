-- SEARCH-S1 operator-only import, step 1. Run in the SAME psql session as apply.
-- Never run candidate CSV against production; only reviewed APPROVED rows with
-- valid existing HCC codes. This SQL does not create or guess categories.
BEGIN;
CREATE TEMP TABLE search_dictionary_stage (
    concept_key varchar(64) NOT NULL,
    product_type varchar(16) NOT NULL,
    locale varchar(2) NOT NULL,
    term varchar(160) NOT NULL,
    relation_type varchar(24) NOT NULL,
    approved_hcc_code varchar(20) NOT NULL,
    approval_status varchar(16) NOT NULL
) ON COMMIT DROP;
-- Load the approved CSV with client-side \copy (use absolute local path):
-- \copy search_dictionary_stage FROM '/absolute/path/approved.csv' WITH (FORMAT csv, HEADER true)
-- Then run \i scripts/search/apply-approved-dictionary.sql in this session.
