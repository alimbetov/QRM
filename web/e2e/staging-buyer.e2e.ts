import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';

const vars = ['STAGING_FIXTURE_FILE','STAGING_SELLER_TOKEN_FILE',
              'STAGING_BROWSER_BUYER_USERNAME','STAGING_BROWSER_BUYER_PASSWORD'];
function env(name: string) {
  const value = process.env[name];
  if (!value) throw new Error('Missing private staging variable: ' + name);
  return value;
}
function fixture(): Record<string,string> {
  const content = readFileSync(env('STAGING_FIXTURE_FILE'), 'utf8');
  return Object.fromEntries(content.split(/\r?\n/)
    .filter(line => /^STAGING_[A-Z_]+=[0-9a-f-]+$/i.test(line))
    .map(line => line.split('=', 2)));
}
const stagingUrl = new URL(env('STAGING_BASE_URL'));

test('real browser login, demand, bid acceptance, confirmation and inventory workflow', async ({ page, request }) => {
  for(const name of vars) env(name);
  const actor=fixture();
  const sellerToken=readFileSync(env('STAGING_SELLER_TOKEN_FILE'),'utf8').trim();
  const headers={Authorization:'Bearer '+sellerToken};
  const publicSearch='/api/v1/public/catalog?query=STAGING-QR-TEST-PRODUCT';
  const catalogResponse=await request.get(publicSearch);
  expect(catalogResponse.status()).toBe(200);
  const catalogJson=await catalogResponse.json();
  if(catalogJson.total===0){
    const publication=await request.post(
      '/api/v1/sellers/'+actor.STAGING_SELLER_ID+'/listings/'+actor.STAGING_LISTING_ID+'/publish',
      {headers});
    expect(publication.status()).toBe(204);
  }
  await page.goto('/');
  await expect(page.getByRole('heading',{name:'Товары и услуги от продавцов'})).toBeVisible();
  await page.getByRole('textbox',{name:'Поиск по каталогу'}).fill('STAGING-QR-TEST-PRODUCT');
  const product=page.getByRole('article').filter({hasText:'STAGING-QR-TEST-PRODUCT'});
  await expect(product).toBeVisible();
  await product.getByRole('link',{name:'Запросить предложение'}).click();

  const signIn=page.getByRole('button',{name:'Войти через OIDC'});
  await expect(signIn).toBeVisible();
  await signIn.click();
  await expect(page).toHaveURL(/\/identity\/realms\/zakaz-staging\//);
  expect(new URL(page.url()).hostname).toBe(stagingUrl.hostname);
  await page.locator('input[name="username"]').fill(env('STAGING_BROWSER_BUYER_USERNAME'));
  await page.locator('input[name="password"]').fill(env('STAGING_BROWSER_BUYER_PASSWORD'));
  await page.locator('#kc-login').click();
  await expect(page).toHaveURL(/\/buy\?/);
  const enroll=page.getByRole('button',{name:'Создать профиль покупателя'});
  const demandButton=page.getByRole('button',{name:'Создать BuyerDemand'});
  await expect(enroll.or(demandButton)).toBeVisible();
  if(await enroll.isVisible()){
    await page.getByRole('textbox',{name:'Никнейм'}).fill('StagingBrowserBuyer');
    await page.getByRole('textbox',{name:/Контактный телефон/}).fill('+77000000000');
    await page.getByRole('checkbox',{name:/Согласен на сохранение/}).check();
    await enroll.click();
  }
  await expect(page.getByRole('button',{name:'Создать BuyerDemand'})).toBeVisible();

  const demandResponsePromise=page.waitForResponse(r=>r.url().includes('/api/v1/buyers/demands')
      && r.request().method()==='POST' && r.status()===201);
  await page.getByRole('button',{name:'Создать BuyerDemand'}).click();
  const demandResponse=await demandResponsePromise;
  const {demandId}=await demandResponse.json();
  expect(demandId).toMatch(/^[0-9a-f-]{36}$/i);
  await expect(page.getByText('Номер заявки: '+demandId)).toBeVisible();

  const expiresAt=new Date(Date.now()+15*60_000).toISOString();
  const bidResponse=await request.post(
    '/api/v1/sellers/'+actor.STAGING_SELLER_ID+'/demands/'+demandId+'/bids',
    {headers,data:{
      revision:1,expiresAt,method:'PICKUP',originLocationId:actor.STAGING_LOCATION_ID,
      deliveryFee:0,lines:[{
        productId:actor.STAGING_PRODUCT_ID,skuId:actor.STAGING_SKU_ID,
        quantity:1,unitPrice:125000.50
      }]
    }});
  expect(bidResponse.status()).toBe(201);
  const {bidId}=await bidResponse.json();
  expect(bidId).toMatch(/^[0-9a-f-]{36}$/i);
  await page.getByRole('button',{name:'Обновить предложения'}).click();
  const accept=page.getByRole('button',{name:'Принять и создать заказ'});
  await expect(accept).toBeVisible();

  const acceptancePromise=page.waitForResponse(r=>r.url().includes('/bids/'+bidId+'/accept')
      && r.request().method()==='POST' && r.status()===201);
  await accept.click();
  const accepted=await acceptancePromise;
  const {orderId}=await accepted.json();
  expect(orderId).toMatch(/^[0-9a-f-]{36}$/i);
  await expect(page.getByText('PLACED',{exact:true})).toBeVisible();

  const confirmation=await request.post(
    '/api/v1/sellers/'+actor.STAGING_SELLER_ID+'/orders/'+orderId+'/confirm',{headers});
  expect(confirmation.status()).toBe(204);
  await page.getByRole('button',{name:'Обновить статус'}).click();
  await expect(page.getByText('CONFIRMED',{exact:true})).toBeVisible();
  // Separate authorized SQL check-order.sql proves exact stock and Outbox invariants.
});
