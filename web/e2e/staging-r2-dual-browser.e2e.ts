import {expect,test,type Page} from '@playwright/test';

/**
 * R2 two-browser USER journey: no test-generated REST requests and no synthetic
 * invitation fixtures. Operator provisions real Keycloak members, an existing
 * PUBLIC/stocked PHYSICAL SKU, origin, territory and both scheduler+delivery.
 * This test is intentionally OFF in ordinary CI. All actions use React UI.
 */
test('real buyer and seller browsers: demand, both opt-ins, auto invitation, bid, acceptance, reservation',
  async ({browser,baseURL})=>{
  test.skip(process.env.STAGING_R2_DUAL_BROWSER_UAT!=='true',
    'Requires real staging seller+buyer accounts, candidate scanner, worker, public SKU, inventory');
  test.setTimeout(240_000);
  const requireVar=(name:string)=>{
    const value=process.env[name];
    if(!value)throw new Error('Missing private R2 dual-browser fixture '+name);
    return value;
  };
  const productId=requireVar('STAGING_R2_PRODUCT_ID');
  const sellerId=requireVar('STAGING_R2_SELLER_ID');
  const skuCode=requireVar('STAGING_R2_SELLER_SKU_CODE');
  const unitPrice=requireVar('STAGING_R2_UNIT_PRICE');
  const buyerLogin=requireVar('STAGING_BROWSER_BUYER_USERNAME');
  const buyerPassword=requireVar('STAGING_BROWSER_BUYER_PASSWORD');
  const sellerLogin=requireVar('STAGING_SELLER_BROWSER_USERNAME');
  const sellerPassword=requireVar('STAGING_SELLER_BROWSER_PASSWORD');
  const buyerContext=await browser.newContext({baseURL});
  const sellerContext=await browser.newContext({baseURL});
  try{
    const buyer=await buyerContext.newPage();
    const seller=await sellerContext.newPage();

    async function keycloakLogin(page:Page,username:string,password:string){
      await expect(page).toHaveURL(/\/identity\/realms\/zakaz-staging\//);
      await page.locator('input[name="username"]').fill(username);
      await page.locator('input[name="password"]').fill(password);
      await page.locator('#kc-login').click();
    }

    // Seller opt-in FIRST: candidate discovery remains denied until buyer opt-in too.
    await seller.goto('/seller');
    await seller.getByRole('button',{name:'Войти как продавец'}).click();
    await keycloakLogin(seller,sellerLogin,sellerPassword);
    await expect(seller).toHaveURL(/\/seller$/);
    const sellerField=seller.getByRole('textbox',{name:'Seller ID'});
    await sellerField.fill(sellerId);
    await seller.getByRole('button',{name:'Проверить доступ и загрузить'}).click();
    const sellerDeals=seller.getByRole('region',{name:'Сделки продавца'});
    await expect(sellerDeals).toBeVisible();
    await sellerDeals.getByRole('button',{name:'Приглашения'}).click();
    const optIn=sellerDeals.getByRole('checkbox',{
      name:'Разрешить in-app приглашения этой организации'
    });
    await expect(optIn).toBeVisible();
    if(!await optIn.isChecked())await optIn.check();
    await expect(optIn).toBeChecked();

    // Buyer creates demand with own OIDC and explicitly approves seller discovery.
    await buyer.goto('/buy?productId='+encodeURIComponent(productId)+'&unit=PCS');
    await buyer.getByRole('button',{name:'Войти через OIDC'}).click();
    await keycloakLogin(buyer,buyerLogin,buyerPassword);
    await expect(buyer).toHaveURL(/\/buy\?/);
    const create=buyer.getByRole('button',{name:'Создать BuyerDemand'});
    await expect(create).toBeVisible();
    const demandResponse=buyer.waitForResponse(r=>
      r.request().method()==='POST'&&r.url().endsWith('/api/v1/buyers/demands')&&
      r.status()===201);
    await create.click();
    const demand=(await (await demandResponse).json()) as {demandId:string};
    expect(demand.demandId).toMatch(/^[0-9a-f-]{36}$/i);
    const sharing=buyer.getByRole('checkbox',{
      name:'Разрешаю приглашения по этой заявке'
    });
    await expect(sharing).toBeVisible();
    await sharing.check();
    await expect(sharing).toBeChecked();

    // No out-of-band invitation INSERT or direct REST call. The trusted worker
    // must discover and deliver the real consented invite asynchronously.
    const invitations=sellerDeals.getByRole('article').filter({
      hasText:'Demand: '+demand.demandId
    });
    let found=false;
    for(let attempt=0;attempt<40;attempt++){
      await sellerDeals.getByRole('button',{name:'Обновить приглашения'}).click();
      if(await invitations.count()===1){found=true;break;}
      await seller.waitForTimeout(2500);
    }
    expect(found,'Auto-generated invitation must arrive through real ML-R2 worker')
      .toBe(true);
    await invitations.getByRole('button',{name:'Ответить ставкой'}).click();
    const composer=invitations.getByRole('region',{name:'Подготовка ставки'});
    await expect(composer.getByText('Предложение по заявке')).toBeVisible();
    const sku=composer.getByLabel('Мой SKU');
    const option=sku.locator('option').filter({hasText:skuCode});
    const optionValue=await option.getAttribute('value');
    expect(optionValue).not.toBeNull();
    await sku.selectOption(optionValue!);
    await composer.getByLabel(/Моя цена за/).fill(unitPrice);
    await expect(composer.getByRole('button',{name:'Отправить ставку покупателю'}))
      .toBeEnabled();
    seller.once('dialog',dialog=>void dialog.accept());
    const bidResponse=seller.waitForResponse(r=>
      r.request().method()==='POST'&&r.url().endsWith('/bid')&&r.status()===201);
    await composer.getByRole('button',{name:'Отправить ставку покупателю'}).click();
    const bid=(await (await bidResponse).json()) as {bidId:string};
    expect(bid.bidId).toMatch(/^[0-9a-f-]{36}$/i);

    await buyer.getByRole('button',{name:'Обновить предложения'}).click();
    const accept=buyer.getByRole('button',{name:'Принять и создать заказ'});
    await expect(accept).toBeVisible();
    const orderResponse=buyer.waitForResponse(r=>
      r.request().method()==='POST'&&r.url().includes('/bids/'+bid.bidId+'/accept')&&
      r.status()===201);
    await accept.click();
    const order=(await (await orderResponse).json()) as {orderId:string};
    expect(order.orderId).toMatch(/^[0-9a-f-]{36}$/i);
    await expect(buyer.getByText('PLACED',{exact:true})).toBeVisible();

    // Seller confirms using React; the Backend transaction reserves stock.
    await sellerDeals.getByRole('button',{name:'Заказы'}).click();
    await sellerDeals.getByRole('button',{name:'Обновить заказы'}).click();
    // Seller projection exposes public number, not raw order UUID. Match the
    // exact public number shown to the buyer; avoid confirming another order.
    const publicNumber=await buyer.locator('.buyer-order p')
      .filter({hasText:'Номер заказа:'}).locator('strong').innerText();
    expect(publicNumber).toMatch(/^ZK-/);
    const placed=sellerDeals.getByRole('article').filter({
      hasText:'Заказ '+publicNumber
    });
    await expect(placed).toBeVisible();
    seller.once('dialog',dialog=>void dialog.accept());
    await placed.getByRole('button',{name:'Подтвердить и зарезервировать'}).click();
    await expect(sellerDeals.getByText('Статус заказа обновлён по данным сервера.'))
      .toBeVisible();
    await buyer.getByRole('button',{name:'Обновить статус'}).click();
    await expect(buyer.getByText('CONFIRMED',{exact:true})).toBeVisible();
    // Only PostgreSQL DB assertion qualifies exact inventory_reservation and
    // outbox counts; UI status alone is not evidence of those invariants.
  }finally{
    await buyerContext.close();
    await sellerContext.close();
  }
});
