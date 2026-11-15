import {expect,test} from '@playwright/test';

/**
 * Operator-gated Seller Deals browser journey on REAL Keycloak + PostgreSQL.
 * Needs one disposable PLACED order with available tracked stock and one
 * synthetic opt-in callback, owned by the same SELLER_ADMIN membership.
 * This file is enumerated, NOT executed, by ordinary web CI.
 */
test('seller confirms a real order then processes a consented guest callback',async({page})=>{
  test.skip(process.env.STAGING_SELLER_DEALS_UAT!=='true',
    'Requires a private staging seller and disposable order/callback fixtures');
  function need(name:string):string {
    const value=process.env[name];
    if(!value)throw new Error('Missing private seller test fixture: '+name);
    return value;
  }
  const seller=need('STAGING_SELLER_ID');
  const username=need('STAGING_SELLER_BROWSER_USERNAME');
  const password=need('STAGING_SELLER_BROWSER_PASSWORD');
  const orderNumber=need('STAGING_SELLER_ORDER_PUBLIC_NUMBER');
  const callbackPhone=need('STAGING_SELLER_UAT_LEAD_PHONE');

  await page.goto('/seller');
  await page.getByRole('button',{name:'Войти как продавец'}).click();
  await expect(page).toHaveURL(/\/identity\/realms\/zakaz-staging\//);
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('#kc-login').click();
  await expect(page).toHaveURL(/\/seller$/);
  const sellerField=page.getByRole('textbox',{name:'Seller ID'});
  await expect(sellerField).toBeVisible();
  await sellerField.fill(seller);
  await page.getByRole('button',{name:'Проверить доступ и загрузить'}).click();
  await expect(page.getByRole('heading',{name:'Сделки и обращения'})).toBeVisible();

  const order=page.getByRole('article').filter({hasText:'Заказ '+orderNumber});
  await expect(order).toBeVisible();
  await expect(order.getByText('PLACED',{exact:true})).toBeVisible();
  page.once('dialog',dialog=>void dialog.accept());
  const confirmation=page.waitForResponse(r=>
    r.url().includes('/orders/') && r.url().endsWith('/confirm') &&
    r.request().method()==='POST' && r.status()===204);
  await order.getByRole('button',{name:'Подтвердить и зарезервировать'}).click();
  await confirmation;
  await expect(page.getByRole('status').filter({hasText:'Статус заказа обновлён'}))
    .toBeVisible();

  // An explicit click performs an audited disclosure of decrypted contact data.
  await page.getByRole('button',{name:'Обратные звонки'}).click();
  const callback=page.getByRole('article').filter({hasText:callbackPhone});
  await expect(callback).toBeVisible();
  await expect(callback.getByText('Заявка на звонок · NEW')).toBeVisible();
  page.once('dialog',dialog=>void dialog.accept());
  const mark=page.waitForResponse(r=>
    r.url().includes('/leads/') && r.url().endsWith('/status') &&
    r.request().method()==='PUT' && r.status()===204);
  await callback.getByRole('button',{name:'Отметить «Связались»'}).click();
  await mark;
  await expect(callback.getByText('Заявка на звонок · CONTACTED')).toBeVisible();
  await expect(callback.getByRole('button',{name:'Отметить «Связались»'}))
    .toHaveCount(0);
});
