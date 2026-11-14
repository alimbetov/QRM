import {expect,test} from '@playwright/test';

/**
 * Real HTTPS+Keycloak+PostgreSQL seller acceptance.
 * Operator provisions a real CATALOG_EDITOR membership and a PHYSICAL
 * canonical product with no unfilled required SKU attributes before running.
 * CI only compiles/lists this test, it does NOT claim real browser acceptance.
 */
test('seller logs in, creates own draft SKU, publishes and opens stable QR',async({page})=>{
  test.skip(process.env.STAGING_SELLER_BROWSER_UAT!=='true',
    'Requires operator-provisioned seller membership, real Keycloak and HTTPS staging');
  function required(name:string):string {
    const value=process.env[name];
    if(!value)throw new Error('Missing private seller staging setting '+name);
    return value;
  }
  const sellerId=required('STAGING_SELLER_ID');
  const productId=required('STAGING_CANONICAL_PRODUCT_ID');
  const username=required('STAGING_SELLER_BROWSER_USERNAME');
  const password=required('STAGING_SELLER_BROWSER_PASSWORD');

  await page.goto('/seller');
  await expect(page.getByRole('heading',{name:'Управление объявлениями'})).toBeVisible();
  await page.getByRole('button',{name:'Войти как продавец'}).click();
  await expect(page).toHaveURL(/\/identity\/realms\/zakaz-staging\//);
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('#kc-login').click();
  await expect(page).toHaveURL(/\/seller$/);

  await page.getByRole('textbox',{name:'Seller ID'}).fill(sellerId);
  await page.getByRole('button',{name:'Проверить доступ и загрузить'}).click();
  await expect(page.getByRole('heading',{name:'Мои объявления'})).toBeVisible();
  const sku='R1-BROWSER-'+Date.now();
  await page.getByRole('textbox',{name:'Канонический Product ID'}).fill(productId);
  await page.getByRole('textbox',{name:'Код SKU'}).fill(sku);
  await page.getByRole('textbox',{name:/Публичная цена за единицу/}).fill('125000.50');
  await page.getByRole('button',{name:'Создать черновик и SKU'}).click();
  await expect(page.getByText('Черновик создан в PostgreSQL.',{exact:false})).toBeVisible();
  const listing=page.getByRole('article').filter({hasText:sku});
  await expect(listing).toBeVisible();
  await expect(listing.getByText('DRAFT',{exact:true})).toBeVisible();
  await listing.getByRole('button',{name:'Опубликовать объявление'}).click();
  await expect(listing.getByText('PUBLIC',{exact:true})).toBeVisible();

  const publicLink=listing.getByRole('link',{name:'Посмотреть публичную карточку'});
  await expect(publicLink).toHaveAttribute('href',/^\/q\/[A-Za-z0-9_-]{43}$/);
  const href=await publicLink.getAttribute('href');
  await page.goto(href!);
  await expect(page.getByText(sku)).toBeVisible();
});
