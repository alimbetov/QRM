// Real HTTPS → Keycloak-signed JWT → Spring Security/JPA/Postgres.
 // No mock JWT, bypass header or fabricated order. Node.js 22 built-in fetch.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const required = name => {
  const value = process.env[name];
  if (!value) throw new Error('Required environment variable: '+name);
  return value;
};
const base = required('STAGING_BASE_URL').replace(/\/$/,'');
if (!base.startsWith('https://')) throw new Error('HTTPS staging base required');
const fixture = Object.fromEntries(
  readFileSync(required('STAGING_FIXTURE_FILE'),'utf8').split(/\r?\n/)
    .filter(line=>/^STAGING_[A-Z_]+=[0-9a-f-]+$/i.test(line))
    .map(line=>line.split('=',2))
);
for(const field of ['STAGING_PRODUCT_ID','STAGING_SKU_ID','STAGING_LISTING_ID',
                    'STAGING_SELLER_ID','STAGING_LOCATION_ID']) {
  if (!fixture[field]) throw new Error('Fixture missing '+field);
}
const buyer=readFileSync(required('STAGING_BUYER_TOKEN_FILE'),'utf8').trim();
const seller=readFileSync(required('STAGING_SELLER_TOKEN_FILE'),'utf8').trim();
if (buyer.split('.').length!==3 || seller.split('.').length!==3)
  throw new Error('Real signed JWTs required (run oidc-pkce-login.py)');
async function api(method,path,token,body,key,expected=200){
  const response = await fetch(base+path,{
    method,
    headers:{
      Accept:'application/json',
      ...(body?{'Content-Type':'application/json'}:{}),
      ...(token?{Authorization:'Bearer '+token}:{}),
      ...(key?{'Idempotency-Key':key}:{})
    },
    ...(body?{body:JSON.stringify(body)}:{}),
    signal:AbortSignal.timeout(15000),
    redirect:'manual'
  });
  assert.equal(response.status,expected,method+' '+path+' expected '+expected+', got '+response.status);
  if(response.status===204)return null;
  if(response.status===401 || response.status===403)return null;
  const type=response.headers.get('content-type')||'';
  assert.match(type,/application\/json/i,'Expected JSON HTTP DTO');
  return response.json();
}

console.log('R2.3D: verifying real OIDC and transactional HTTP journey');
const registration=await api('POST','/api/v1/buyers/enroll',buyer,{
  nickname:'StagingBuyer',phone:'+77000000000',consentToStorePhone:true
},null,201);
assert.equal(registration.phoneVerified,false,'Phone was not verified by SMS');
assert.equal(registration.maskedPhone,'••••0000');
const me=await api('GET','/api/v1/buyers/me',buyer);
assert.equal(me.buyerId,registration.buyerId);
console.log('PASS: verified OIDC buyer enrollment without SMS or client-assigned buyer ID');
const uri='/api/v1/public/catalog?query=STAGING-QR-TEST-PRODUCT';
const before=await api('GET',uri,null,null,null,200);
assert.equal(before.total,0,'Seed must remain DRAFT before seller publication');
await api('POST',
  '/api/v1/sellers/'+fixture.STAGING_SELLER_ID+'/listings/'+fixture.STAGING_LISTING_ID+'/publish',
  seller,null,null,204);
const catalog=await api('GET',uri,null);
assert.equal(catalog.total,1);
const item=catalog.items[0];
assert.equal(item.listingId,fixture.STAGING_LISTING_ID);
assert.equal(item.productId,fixture.STAGING_PRODUCT_ID);
assert.equal(item.variants[0].id,fixture.STAGING_SKU_ID);
assert.equal(item.variants[0].priceKzt,'125000.50');
assert.ok(item.qrToken && item.qrToken.length>=43);
assert.equal(Object.hasOwn(item,'stock'),false);
assert.equal(Object.hasOwn(item,'phone'),false);
await api('GET','/api/v1/public/q/'+item.qrToken,null,null,null,200);
console.log('PASS: seller-controlled publication and safe public QR catalog');

const expiresAt=new Date(Date.now()+15*60*1000).toISOString();
const demandPath='/api/v1/buyers/demands';
await api('POST',demandPath,null,
  {city:'Almaty',expiresAt,lines:[{productId:item.productId,quantity:1,unit:'PCS'}]},
  null,401);
const created=await api('POST',demandPath,buyer,
  {city:'Almaty',expiresAt,lines:[{productId:item.productId,quantity:1,unit:'PCS'}]},null,201);
assert.ok(created.demandId);
const bidsUrl='/api/v1/sellers/'+fixture.STAGING_SELLER_ID+'/demands/'+created.demandId+'/bids';
const bidCreated=await api('POST',bidsUrl,seller,{
  revision:1,expiresAt,method:'PICKUP',originLocationId:fixture.STAGING_LOCATION_ID,
  deliveryFee:0,lines:[{
    productId:item.productId,skuId:fixture.STAGING_SKU_ID,quantity:1,unitPrice:125000.50
  }]
},null,201);
assert.ok(bidCreated.bidId);
const quotesUrl='/api/v1/buyers/demands/'+created.demandId+'/bids';
await api('GET',quotesUrl,seller,null,null,403);
const quotes=await api('GET',quotesUrl,buyer);
assert.equal(quotes.bids.length,1);
assert.equal(quotes.bids[0].bidId,bidCreated.bidId);
assert.equal(quotes.bids[0].totalKzt,'125000.50');
console.log('PASS: verified buyer demand, trusted seller bid, buyer-only quote');

const acceptUrl=quotesUrl.replace(/\/bids$/, '/bids/'+bidCreated.bidId+'/accept');
const key='staging-'+randomUUID();
const accepted=await api('POST',acceptUrl,buyer,null,key,201);
assert.ok(accepted.orderId);
const replay=await api('POST',acceptUrl,buyer,null,key,201);
assert.equal(replay.orderId,accepted.orderId,'Idempotent accept must reuse order');
const orderUrl='/api/v1/buyers/orders/'+accepted.orderId;
const placed=await api('GET',orderUrl,buyer);
assert.equal(placed.status,'PLACED');
assert.equal(Number(placed.totalAmount),125000.50,'Accepted all-in quote must match order total')
console.log('PASS: exactly one PLACED order after explicit acceptance');

await api('POST',
  '/api/v1/sellers/'+fixture.STAGING_SELLER_ID+'/orders/'+accepted.orderId+'/confirm',
  seller,null,null,204);
await api('POST',
  '/api/v1/sellers/'+fixture.STAGING_SELLER_ID+'/orders/'+accepted.orderId+'/confirm',
  seller,null,null,204);
const confirmed=await api('GET',orderUrl,buyer);
assert.equal(confirmed.status,'CONFIRMED');
assert.equal(Number(confirmed.totalAmount),125000.50);
const signatureParts=buyer.split('.');
const signature=signatureParts[2];
const at=3;
const changed=signature.slice(0,at)+(signature[at]==='A'?'B':'A')+signature.slice(at+1);
await api('GET',orderUrl,signatureParts.slice(0,2).join('.')+'.'+changed,null,null,401);
console.log('PASS: seller confirmation, stable replay, signed token integrity');

const out=process.env.STAGING_ORDER_ID_FILE;
if(out)writeFileSync(resolve(out),accepted.orderId+'\n',{mode:0o600,flag:'wx'});
console.log('FINAL_ORDER_ID='+accepted.orderId);
console.log('Run check-order.sql against isolated app-db to verify exact reservation/Outbox counts.');
