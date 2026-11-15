import {expect,test} from '@playwright/test';

/**
 * Real staging-only user journey. This is NOT a mock and never runs in ordinary
 * CI: the operator must provision verified seller OIDC, BOTH invitation consents,
 * a delivered invitation, matching PUBLIC SKU and an active stocked origin.
 */
test('seller submits first valid SellerBid from live ML-R2 invitation',async({page})=>{
  test.skip(process.env.STAGING_SELLER_BID_UAT!=='true',
    'Requires real dual-consent invitation, live Keycloak and real stocked PostgreSQL fixture');
  const need=(name:string):string=>{
    const v=process.env[name];
    if(!v)throw new Error('Missing private SellerBid browser fixture '+name);
    return v;
  };
  const seller=need('STAGING_SELLER_ID');
  const username=need('STAGING_SELLER_BROWSER_USERNAME');
  const password=need('STAGING_SELLER_BROWSER_PASSWORD');
  const demand=need('STAGING_SELLER_BID_DEMAND_ID');
  const skuCode=need('STAGING_SELLER_BID_SKU_CODE');
  const price=need('STAGING_SELLER_BID_UNIT_PRICE');

  await page.goto('/seller');
  await page.getByRole('button',{name:'Войти как продавец'}).click();
  await expect(page).toHaveURL(/\/identity\/realms\/zakaz-staging\//);
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('#kc-login').click();
  await expect(page).toHaveURL(/\/seller$/);
  await page.getByRole('textbox',{name:'Seller ID'}).fill(seller);
  await page.getByRole('button',{name:'Проверить доступ и загрузить'}).click();

  const workspace=page.getByRole('region',{name:'Сделки продавца'});
  await workspace.getByRole('button',{name:'Приглашения'}).click();
  const invited=workspace.getByRole('article').filter({hasText:'Demand: '+demand});
  await expect(invited).toBeVisible();
  const context=page.waitForResponse(r=>
    r.url().includes('/invitations/')&&r.url().endsWith('/quote-context')&&
    r.status()===200);
  await invited.getByRole('button',{name:'Ответить ставкой'}).click();
  await context;
  const form=invited.getByRole('region',{name:'Подготовка ставки'});
  await expect(form.getByText('Предложение по заявке')).toBeVisible();
  // One-line pre-provisioned fixture; full basket is mandatory on Backend.
  const selector=form.getByLabel('Мой SKU');
  const skuOption=selector.locator('option').filter({hasText:skuCode});
  const optionValue=await skuOption.getAttribute('value');
  expect(optionValue).not.toBeNull();
  await selector.selectOption(optionValue!);
  await form.getByLabel(/Моя цена за/).fill(price);
  await expect(form.getByRole('button',{name:'Отправить ставку покупателю'}))
    .toBeEnabled();
  page.once('dialog',dialog=>void dialog.accept());
  const submitted=page.waitForResponse(r=>
    r.request().method()==='POST'&&r.url().endsWith('/bid')&&r.status()===201);
  await form.getByRole('button',{name:'Отправить ставку покупателю'}).click();
  const receipt=await submitted;
  const json=await receipt.json() as {bidId:string};
  expect(json.bidId).toMatch(/^[0-9a-f-]{36}$/);
  await expect(form.getByRole('status').filter({hasText:'Покупатель решает'}))
    .toBeVisible();
});
