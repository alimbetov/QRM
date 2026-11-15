-- Execute only on a fresh isolated staging DB as the explicitly authorized DBA.
-- Provided via psql -v issuer=... -v buyer_sub=... -v seller_sub=... .
-- Product and seller memberships are NOT writable through public HTTP.
\set ON_ERROR_STOP on
BEGIN;
SELECT gen_random_uuid() AS category_id,
       gen_random_uuid() AS product_id,
       gen_random_uuid() AS listing_id,
       gen_random_uuid() AS sku_id,
       gen_random_uuid() AS seller_id,
       gen_random_uuid() AS buyer_id,
       gen_random_uuid() AS location_id
\gset
INSERT INTO catalog_category(id,hcc_code,product_type,label,active)
VALUES (:'category_id','02990310000000000000','PHYSICAL','Staging Test Catalog',true);
INSERT INTO catalog_product(id,category_id,canonical_name)
VALUES (:'product_id',:'category_id','STAGING-QR-TEST-PRODUCT');
INSERT INTO catalog_listing(id,seller_id,product_id,status)
VALUES (:'listing_id',:'seller_id',:'product_id','DRAFT');
INSERT INTO catalog_sku(id,listing_id,seller_sku_code,tracking_mode,sales_unit,public_unit_price_kzt)
VALUES (:'sku_id',:'listing_id','STAGING-ONE','TRACKED','PCS',125000.50);
INSERT INTO inventory_location(
 id,seller_id,code,name,region_code,region_name,city,address_line,
 location_type,public_pickup_enabled,active
) VALUES (
 :'location_id',:'seller_id','STAGING-01','Staging pickup','KZ-ALA','Almaty region',
 'Almaty','Synthetic staging address','SHOP',true,true
);
INSERT INTO inventory_balance(id,seller_id,location_id,sku_id,on_hand,reserved)
VALUES (gen_random_uuid(),:'seller_id',:'location_id',:'sku_id',10.000,0.000);
INSERT INTO seller_delivery_area(id,seller_id,city,active)
VALUES (gen_random_uuid(),:'seller_id','Almaty',true);
INSERT INTO buyer_account_link(id,issuer,subject,buyer_id,active)
VALUES (gen_random_uuid(),:'issuer',:'buyer_sub',:'buyer_id',true);
INSERT INTO seller_membership(id,issuer,subject,seller_id,role,active)
VALUES (gen_random_uuid(),:'issuer',:'seller_sub',:'seller_id','SELLER_ADMIN',true);
COMMIT;
-- Output contains nonsecret synthetic fixture UUIDs only. Save to a private local file.
\echo STAGING_PRODUCT_ID=:product_id
\echo STAGING_LISTING_ID=:listing_id
\echo STAGING_SKU_ID=:sku_id
\echo STAGING_SELLER_ID=:seller_id
\echo STAGING_BUYER_ID=:buyer_id
\echo STAGING_LOCATION_ID=:location_id
