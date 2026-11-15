-- SEARCH-S1 operator-only import, step 2. Stage table must exist from prepare script.
-- All-or-nothing. Do not bypass review of translations, HCC depth or relation meaning.
DO $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM search_dictionary_stage) THEN
        RAISE EXCEPTION 'Dictionary import stage is empty';
    END IF;
    IF EXISTS (
        SELECT 1 FROM search_dictionary_stage s
        WHERE s.approval_status<>'APPROVED'
           OR s.approved_hcc_code !~ '^[0-9]{20}$'
           OR s.locale NOT IN ('ru','kk','en','zh')
           OR s.relation_type NOT IN ('SYNONYM','CATEGORY_TERM','RELATED','SERVICE_INTENT')
           OR s.product_type NOT IN ('PHYSICAL','SERVICE','DIGITAL')
           OR length(btrim(s.term)) NOT BETWEEN 1 AND 160
    ) THEN
        RAISE EXCEPTION 'Import contains unapproved, empty, or invalid rows';
    END IF;
    IF EXISTS (
        SELECT 1 FROM search_dictionary_stage
        GROUP BY locale,lower(btrim(term))
        HAVING count(*)>1
    ) THEN
        RAISE EXCEPTION 'Duplicate normalized term in a locale';
    END IF;
    IF EXISTS (
        SELECT 1 FROM search_dictionary_stage s
        LEFT JOIN catalog_category c ON c.hcc_code=s.approved_hcc_code
        WHERE c.id IS NULL OR c.active IS DISTINCT FROM true
           OR c.product_type<>s.product_type
    ) THEN
        RAISE EXCEPTION 'Unknown/inactive HCC or mismatched ProductType';
    END IF;
    IF EXISTS (
        SELECT 1 FROM search_dictionary_stage s
        JOIN catalog_search_term st ON st.locale=s.locale
            AND st.normalized_term=lower(btrim(s.term))
        JOIN catalog_term_category tc ON tc.term_id=st.id
        JOIN catalog_category c ON c.id=tc.category_id
        WHERE c.hcc_code<>s.approved_hcc_code
          AND tc.relation_type IN ('SYNONYM','CATEGORY_TERM','SERVICE_INTENT')
    ) THEN
        RAISE EXCEPTION 'Existing search term maps to different exact HCC; manual reconciliation required';
    END IF;
END;
$$;

INSERT INTO catalog_search_term(id,term,normalized_term,locale,status,revision,created_at)
SELECT gen_random_uuid(),btrim(s.term),lower(btrim(s.term)),s.locale,
       'ACTIVE',1,clock_timestamp()
FROM search_dictionary_stage s
ON CONFLICT(locale,normalized_term)
DO UPDATE SET term=EXCLUDED.term,status='ACTIVE',
              revision=catalog_search_term.revision+1;

INSERT INTO catalog_term_category(term_id,category_id,relation_type,weight)
SELECT st.id,c.id,s.relation_type,100
FROM search_dictionary_stage s
JOIN catalog_search_term st ON st.locale=s.locale
    AND st.normalized_term=lower(btrim(s.term))
JOIN catalog_category c ON c.hcc_code=s.approved_hcc_code
ON CONFLICT (term_id,category_id,relation_type)
DO UPDATE SET weight=EXCLUDED.weight;

-- Human operator should record reviewed CSV SHA256, approver and DB revision
-- in the deployment change ticket. Transaction commits only after validation.
COMMIT;
