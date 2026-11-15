import {expect, test, type Page} from '@playwright/test';
import {readFileSync, writeFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';

/**
 * P0 dual-user qualification. Every business mutation is performed in React.
 * The only SQL is a read-only, independent assertion after CONFIRMED.
 * This test MUST fail (not fabricate an invitation) when automatic candidate
 * evaluation and delivery are not operational.
 *
 * Ordinary CI enumerates this test only. A real isolated HTTPS staging host,
 * two verified Keycloak identities and a stocked DRAFT listing are mandatory.
 */
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function need(key: string): string {
  const value = process.env[key];
  if (!value) throw new Error('P0 dual-user UAT requires ' + key);
  return value;
}

function seededIds(): Record<string, string> {
  const rows = readFileSync(resolve(need('STAGING_FIXTURE_FILE')), 'utf8').split(/\r?\n/);
  const values: Record<string, string> = {};
  for (const row of rows) {
    const match = /^(STAGING_[A-Z_]+)=([0-9a-f-]{36})$/i.exec(row);
    if (match) values[match[1]] = match[2];
  }
  for (const key of ['STAGING_SELLER_ID', 'STAGING_PRODUCT_ID', 'STAGING_LISTING_ID']) {
    if (!uuid.test(values[key] || '')) throw new Error('Missing UUID fixture ' + key);
  }
  return values;
}

async function keycloakLogin(page: Page, role: 'buyer'|'seller') {
  const prefix = role === 'seller' ? 'STAGING_SELLER_BROWSER' : 'STAGING_BROWSER_BUYER';
  await page.getByRole('button', {name: role === 'seller'
    ? 'Войти как продавец' : 'Войти через OIDC'}).click();
  await expect(page).toHaveURL(/\/identity\/realms\/zakaz-staging\//);
  await page.locator('input[name="username"]').fill(need(prefix + '_USERNAME'));
  await page.locator('input[name="password"]').fill(need(prefix + '_PASSWORD'));
  await page.locator('#kc-login').click();
}

function checkDatabase(orderId: string) {
  if (!uuid.test(orderId)) throw new Error('Invalid returned order ID');
  const envFile = resolve(need('STAGING_PRIVATE_ENV_FILE'));
  const compose = fileURLToPath(new URL('../../deploy/staging/compose.yaml', import.meta.url));
  const sql = readFileSync(
    fileURLToPath(new URL('../../deploy/staging/check-order.sql', import.meta.url)), 'utf8');
  // The staging DB has no publicly exposed port; the verifier runs on its
  // operator host via docker compose exec, with only synthetic fixture data.
  execFileSync('docker', [
    'compose', '--env-file', envFile, '-f', compose,
    'exec', '-T', 'app-db', 'psql', '-v', 'ON_ERROR_STOP=1',
    '-U', 'zakaz', '-d', 'zakaz', '-v', 'order_id=' + orderId
  ], {input: sql, encoding: 'utf8', timeout: 30_000, stdio: ['pipe', 'pipe', 'pipe']});
}

test('P0: two Keycloak actors complete listing to confirmed reserved order in React', async ({browser}) => {
  test.skip(process.env.STAGING_DUAL_USER_UAT !== 'true',
    'Requires real HTTPS staging, Keycloak, PostgreSQL and autonomous invitation delivery');
  test.setTimeout(240_000);
  const fixture = seededIds();
  const baseURL = need('STAGING_BASE_URL');
  if (!baseURL.startsWith('https://')) throw new Error('Real HTTPS staging is required');
  const sellerContext = await browser.newContext({baseURL});
  const buyerContext = await browser.newContext({baseURL});
  try {
    const seller = await sellerContext.newPage();
    const buyer = await buyerContext.newPage();

    // Seller publishes a pre-provisioned, stocked DRAFT listing via the UI.
    await seller.goto('/seller');
    await keycloakLogin(seller, 'seller');
    await expect(seller).toHaveURL(/\/seller$/);
    await seller.getByRole('textbox', {name: 'Seller ID'}).fill(fixture.STAGING_SELLER_ID);
    await seller.getByRole('button', {name: 'Проверить доступ и загрузить'}).click();
    const listing = seller.getByRole('article').filter({hasText: fixture.STAGING_LISTING_ID});
    await expect(listing).toBeVisible();
    await expect(listing.getByText('DRAFT', {exact: true})).toBeVisible();
    const publish = seller.waitForResponse(r =>
      r.request().method() === 'POST' && r.url().includes('/listings/') &&
      r.url().endsWith('/publish') && r.status() === 204);
    await listing.getByRole('button', {name: 'Опубликовать объявление'}).click();
    await publish;
    await expect(listing.getByText('PUBLIC', {exact: true})).toBeVisible();
    await expect(listing.getByRole('link', {name: 'Посмотреть публичную карточку'}))
      .toHaveAttribute('href', /^\/q\/[A-Za-z0-9_-]{43}$/);

    // Seller authorizes in-app invitations in its independent authenticated session.
    const sellerPanel = seller.getByRole('region', {name: 'Сделки продавца'});
    await sellerPanel.getByRole('button', {name: 'Приглашения'}).click();
    const sellerOptIn = sellerPanel.getByRole('checkbox',
      {name: 'Разрешить in-app приглашения этой организации'});
    await expect(sellerOptIn).toBeVisible();
    if (!(await sellerOptIn.isChecked())) await sellerOptIn.check();
    await expect(sellerPanel.getByText('Включено на сервере. Нужны действующие приглашения.'))
      .toBeVisible();

    // Buyer discovers the same PUBLIC listing and creates a new demand in React.
    await buyer.goto('/');
    await buyer.getByRole('textbox', {name: 'Поиск по каталогу'})
      .fill('STAGING-QR-TEST-PRODUCT');
    const product = buyer.getByRole('article').filter({hasText: 'STAGING-QR-TEST-PRODUCT'});
    await expect(product).toBeVisible();
    await product.getByRole('link', {name: 'Запросить предложение'}).click();
    await keycloakLogin(buyer, 'buyer');
    await expect(buyer).toHaveURL(/\/buy\?/);

    const enroll = buyer.getByRole('button', {name: 'Создать профиль покупателя'});
    const demandAction = buyer.getByRole('button', {name: 'Создать BuyerDemand'});
    await expect(enroll.or(demandAction)).toBeVisible();
    if (await enroll.isVisible()) {
      await buyer.getByRole('textbox', {name: 'Никнейм'}).fill('DualUserStagingBuyer');
      await buyer.getByRole('textbox', {name: /Контактный телефон/}).fill('+77000000000');
      await buyer.getByRole('checkbox', {name: /Согласен на сохранение/}).check();
      await enroll.click();
    }
    await expect(demandAction).toBeVisible();
    await buyer.getByRole('textbox', {name: 'Город'}).fill('Almaty');
    await buyer.getByRole('textbox', {name: 'Количество'}).fill('1');
    const demandCreated = buyer.waitForResponse(r =>
      r.request().method() === 'POST' &&
      r.url().includes('/api/v1/buyers/demands') && r.status() === 201);
    await demandAction.click();
    const demandId = (await (await demandCreated).json() as {demandId: string}).demandId;
    expect(demandId).toMatch(uuid);
    await expect(buyer.getByText('Номер заявки: ' + demandId)).toBeVisible();

    const buyerOptIn = buyer.getByRole('checkbox',
      {name: 'Разрешаю приглашения по этой заявке'});
    await expect(buyerOptIn).toBeVisible();
    if (!(await buyerOptIn.isChecked())) await buyerOptIn.check();
    await expect(buyer.getByText(/Разрешение сохранено сервером/)).toBeVisible();

    // No direct SQL/Service call or private API request is allowed to create
    // MlSellerInvitation. The running product must evaluate + deliver it.
    const invitation = sellerPanel.getByRole('article').filter({hasText: 'Demand: ' + demandId});
    let delivered = false;
    for (let attempt = 0; attempt < 18; attempt++) {
      await sellerPanel.getByRole('button', {name: 'Обновить приглашения'}).click();
      if (await invitation.count() === 1) {delivered = true; break;}
      await seller.waitForTimeout(5_000);
    }
    if (!delivered) throw new Error(
      'BLOCKED: autonomous buyer-demand matching/invitation delivery not observed. ' +
      'Do not seed an invitation, call evaluator directly or report USER_E2E_VERIFIED.');

    const quoteContext = seller.waitForResponse(r =>
      r.url().endsWith('/quote-context') && r.status() === 200);
    await invitation.getByRole('button', {name: 'Ответить ставкой'}).click();
    await quoteContext;
    const composer = invitation.getByRole('region', {name: 'Подготовка ставки'});
    await expect(composer.getByText('Предложение по заявке')).toBeVisible();
    const skuSelect = composer.getByLabel('Мой SKU');
    const sku = skuSelect.locator('option').filter({hasText: 'STAGING-ONE'});
    const skuValue = await sku.getAttribute('value');
    expect(skuValue).toBeTruthy();
    await skuSelect.selectOption(skuValue!);
    await composer.getByLabel(/Моя цена за/).fill('125000.50');
    await expect(composer.getByRole('button', {name: 'Отправить ставку покупателю'}))
      .toBeEnabled();
    seller.once('dialog', dialog => void dialog.accept());
    const bidCreated = seller.waitForResponse(r =>
      r.request().method() === 'POST' && r.url().endsWith('/bid') &&
      r.status() === 201);
    await composer.getByRole('button', {name: 'Отправить ставку покупателю'}).click();
    const bidId = (await (await bidCreated).json() as {bidId: string}).bidId;
    expect(bidId).toMatch(uuid);
    await expect(composer.getByText(/Покупатель решает, принимать ли её/)).toBeVisible();

    await buyer.getByRole('button', {name: 'Обновить предложения'}).click();
    await expect(buyer.getByRole('button', {name: 'Принять и создать заказ'}))
      .toBeVisible();
    const accepted = buyer.waitForResponse(r =>
      r.request().method() === 'POST' &&
      r.url().endsWith('/bids/' + bidId + '/accept') && r.status() === 201);
    await buyer.getByRole('button', {name: 'Принять и создать заказ'}).click();
    const orderId = (await (await accepted).json() as {orderId: string}).orderId;
    expect(orderId).toMatch(uuid);
    await expect(buyer.getByText('PLACED', {exact: true})).toBeVisible();
    const publicNumber = await buyer.locator('.buyer-order p')
      .filter({hasText: 'Номер заказа:'}).locator('strong').innerText();
    expect(publicNumber).toBeTruthy();

    // Seller confirms through its UI; buyer independently re-reads status.
    await sellerPanel.getByRole('button', {name: 'Заказы'}).click();
    const sellerOrder = sellerPanel.getByRole('article').filter({hasText: 'Заказ ' + publicNumber});
    await expect(sellerOrder.getByText('PLACED', {exact: true})).toBeVisible();
    seller.once('dialog', dialog => void dialog.accept());
    const confirmed = seller.waitForResponse(r =>
      r.request().method() === 'POST' &&
      r.url().endsWith('/orders/' + orderId + '/confirm') && r.status() === 204);
    await sellerOrder.getByRole('button', {name: 'Подтвердить и зарезервировать'}).click();
    await confirmed;
    await expect(sellerOrder.getByText('CONFIRMED', {exact: true})).toBeVisible();
    await buyer.getByRole('button', {name: 'Обновить статус'}).click();
    await expect(buyer.getByText('CONFIRMED', {exact: true})).toBeVisible();

    // A separate DB connection independently checks exact money, reservation,
    // movement, outbox and non-overselling; an unreachable DB is FAIL.
    checkDatabase(orderId);
    if (process.env.STAGING_ORDER_ID_FILE) {
      writeFileSync(resolve(process.env.STAGING_ORDER_ID_FILE), orderId + '\n',
        {mode: 0o600});
    }
  } finally {
    await buyerContext.close();
    await sellerContext.close();
  }
});
