import {useCallback,useEffect,useRef,useState,type FormEvent} from 'react';
import type {User} from 'oidc-client-ts';
import {sellerAuth,loginSeller,logoutSeller} from './seller-oidc';
import {
  createSellerDraft,downloadSellerQr,listSellerListings,
  publishSellerListing,setSellerSkuPrice,uploadSellerListingImage,
  uuid,validPrice,type WorkspaceListing,type WorkspacePage
} from './seller-api';
import {formatPublishedPriceKzt,qrTokenPattern} from './qr-api';
import SellerDealsPanel from './SellerDealsPanel';
import {sellerAccounts,type SellerAccount} from './seller-deals-api';
import './seller-workspace.css';

const SELLER_STORAGE_KEY='zakaz.seller.workspace.selection.v1';
const physicalUnits=['PCS','KG','G','L','ML','M','M2','M3','HOUR'] as const;

function SkuPriceEditor({sellerId,token,listing,sku,onChanged}:{
  sellerId:string;token:string;listing:WorkspaceListing;
  sku:WorkspaceListing['variants'][number];onChanged:()=>Promise<void>;
}) {
  const [price,setPrice]=useState(sku.priceKzt ?? '');
  const [busy,setBusy]=useState(false);
  const [message,setMessage]=useState('');
  useEffect(()=>setPrice(sku.priceKzt??''),[sku.priceKzt]);
  async function save(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if(!validPrice(price)) {setMessage('Нужна положительная цена в KZT, максимум 2 знака после точки.');return;}
    setBusy(true);setMessage('');
    try {
      await setSellerSkuPrice(sellerId,listing.listingId,sku.skuId,token,price);
      await onChanged();
      setMessage('Цена сохранена.');
    }catch{setMessage('Цена не сохранена. Проверьте статус и доступ.');}
    finally{setBusy(false);}
  }
  return <form onSubmit={save} className="seller-sku">
    <span><strong>{sku.code}</strong> · {sku.unit||'ед. не определена'} · {sku.trackingMode}</span>
    <span className="seller-current-price">{formatPublishedPriceKzt(sku.priceKzt)}</span>
    {listing.status==='DRAFT'&&<>
      <label>Цена за единицу, ₸
        <input aria-label={'Цена '+sku.code} value={price} onChange={e=>setPrice(e.target.value)}
          inputMode="decimal" placeholder="125000.50" maxLength={21}/></label>
      <button type="submit" disabled={busy||!validPrice(price)}>Сохранить цену</button>
    </>}
    {message&&<p role="status">{message}</p>}
  </form>;
}

function ListingPanel({listing,sellerId,token,refresh}:{
  listing:WorkspaceListing;sellerId:string;token:string;refresh:()=>Promise<void>;
}) {
  const [file,setFile]=useState<File|null>(null);
  const [cover,setCover]=useState(false);
  const [busy,setBusy]=useState(false);
  const [message,setMessage]=useState('');
  async function run(work:()=>Promise<void>,done:string) {
    if(busy)return;
    setBusy(true);setMessage('');
    try{await work();await refresh();setMessage(done);}
    catch{setMessage('Операция не выполнена. Проверьте права, данные и состояние объявления.');}
    finally{setBusy(false);}
  }
  async function qr() {
    if(busy)return;
    setBusy(true);setMessage('');
    try {
      const blob=await downloadSellerQr(sellerId,listing.listingId,token);
      const url=URL.createObjectURL(blob);
      const a=document.createElement('a');a.href=url;
      a.download='zakaz-'+listing.listingId+'.png';
      document.body.appendChild(a);a.click();a.remove();
      setTimeout(()=>URL.revokeObjectURL(url),1500);
    }catch{setMessage('Не удалось получить PNG QR. Убедитесь, что товар опубликован.');}
    finally{setBusy(false);}
  }
  return <article className="seller-listing" aria-label={listing.title}>
    <div className="seller-listing-head">
      <div><span className="seller-eyebrow">HCC {listing.categoryCode}</span>
        <h3>{listing.title}</h3>
        <p>Объявление: <code>{listing.listingId}</code></p></div>
      <span className={'seller-status '+(listing.status==='PUBLIC'?'seller-public':'')}>
        {listing.status}</span>
    </div>
    {listing.variants.map(sku=><SkuPriceEditor key={sku.skuId} sellerId={sellerId}
      listing={listing} sku={sku} token={token} onChanged={refresh}/>)}
    {listing.status==='DRAFT'&&<>
      <div className="seller-file-row">
        <label>Фото товара (PNG/JPEG, до 8 МБ)
          <input type="file" accept="image/png,image/jpeg"
            onChange={e=>setFile(e.target.files?.[0]??null)} /></label>
        <label className="seller-check">
          <input type="checkbox" checked={cover} onChange={e=>setCover(e.target.checked)} />
          Сделать обложкой
        </label>
        <button type="button" disabled={busy||!file}
          onClick={()=>file&&void run(async()=>{
            await uploadSellerListingImage(sellerId,listing.listingId,token,file,cover);
            setFile(null);
          },'Фотография загружена и обработана сервером.')}>Загрузить фото</button>
      </div>
      <button type="button" disabled={busy}
        onClick={()=>void run(()=>publishSellerListing(sellerId,listing.listingId,token),
          'Объявление опубликовано. QR готов к использованию.')}>
        Опубликовать объявление
      </button>
    </>}
    {listing.status==='PUBLIC'&&<div className="seller-listing-actions">
      {listing.qrToken&&qrTokenPattern.test(listing.qrToken)&&
        <a href={'/q/'+listing.qrToken} target="_blank" rel="noopener noreferrer">
          Посмотреть публичную карточку</a>}
      <button type="button" disabled={busy} onClick={()=>void qr()}>Скачать QR PNG</button>
    </div>}
    {message&&<p role="status" className="seller-feedback">{message}</p>}
  </article>;
}

export default function SellerWorkspace() {
  const enabled=import.meta.env.VITE_SELLER_WORKSPACE_ENABLED==='true';
  const [user,setUser]=useState<User|null>(null);
  const [authReady,setAuthReady]=useState(false);
  const [sellerInput,setSellerInput]=useState(()=>{
    const saved=sessionStorage.getItem(SELLER_STORAGE_KEY)||'';
    return uuid.test(saved)?saved:'';
  });
  const [sellerId,setSellerId]=useState('');
  const [availableSellers,setAvailableSellers]=useState<SellerAccount[]>([]);
  const [accountsError,setAccountsError]=useState('');
  const [page,setPage]=useState<WorkspacePage|null>(null);
  const [loading,setLoading]=useState(false);
  const [error,setError]=useState('');
  const [success,setSuccess]=useState('');
  const [product,setProduct]=useState('');
  const [skuCode,setSkuCode]=useState('');
  const [unit,setUnit]=useState<string>('PCS');
  const [price,setPrice]=useState('');
  const [busy,setBusy]=useState(false);
  const generation=useRef(0);

  useEffect(()=>{
    const client=sellerAuth;
    if(!enabled||!client){setAuthReady(true);return;}
    let active=true;
    client.getUser().then(value=>{
      if(active){setUser(value&&!value.expired?value:null);setAuthReady(true);}
    }).catch(()=>{if(active)setAuthReady(true);});
    const expired=()=>{setUser(null);setSellerId('');setPage(null);};
    client.events.addAccessTokenExpired(expired);
    return ()=>{active=false;client.events.removeAccessTokenExpired(expired);};
  },[enabled]);

  const loadPage=useCallback(async(id:string,token:string,number:number)=>{
    const call=++generation.current;
    setLoading(true);setError('');
    try {
      const data=await listSellerListings(id,token,number);
      if(call!==generation.current)return;
      setSellerId(id);setPage(data);
      sessionStorage.setItem(SELLER_STORAGE_KEY,id);
    }catch{
      if(call===generation.current){
        setError('Не удалось загрузить объявления. Проверьте идентификатор продавца, активное членство и права.');
      }
    }finally{if(call===generation.current)setLoading(false);}
  },[]);


  useEffect(()=>{
    if(!enabled||!user){setAvailableSellers([]);return;}
    let active=true;
    sellerAccounts(user.access_token).then(accounts=>{
      if(!active)return;
      setAvailableSellers(accounts);setAccountsError('');
      if(accounts.length===1){
        setSellerInput(accounts[0].sellerId);
        void loadPage(accounts[0].sellerId,user.access_token,0);
      }
    }).catch(()=>{
      if(active)setAccountsError('Список организаций недоступен. Можно ввести известный Seller ID вручную; права всегда проверяются на сервере.');
    });
    return ()=>{active=false;};
  },[enabled,user,loadPage]);

  async function connect(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if(!user||!uuid.test(sellerInput)){setError('Укажите идентификатор продавца (UUID).');return;}
    await loadPage(sellerInput,user.access_token,0);
  }

  async function create(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if(!user||!sellerId||!uuid.test(product)||!skuCode.trim()||
        (price.trim()!==''&&!validPrice(price.trim()))) {
      setError('Проверьте канонический Product ID, SKU и цену.');return;
    }
    setBusy(true);setError('');setSuccess('');
    try {
      await createSellerDraft(sellerId,user.access_token,{
        productId:product,skuCode:skuCode.trim(),unit,trackingMode:'TRACKED',
        publicPriceKzt:price.trim()||null
      });
      setProduct('');setSkuCode('');setPrice('');
      setSuccess('Черновик создан в PostgreSQL. Теперь можно загрузить фото и опубликовать.');
      await loadPage(sellerId,user.access_token,0);
    }catch{setError('Не удалось создать черновик. Нужен активный канонический физический товар и права редактора каталога.');}
    finally{setBusy(false);}
  }

  async function refresh(){if(user&&sellerId)await loadPage(sellerId,user.access_token,page?.page??0);}

  if(!enabled)return <main className="seller-shell">
    <h1>Кабинет продавца</h1>
    <p role="status">Seller Workspace выключен до проверки Keycloak, API и независимого S2 Review.</p>
  </main>;

  return <main className="seller-shell">
    <header className="seller-intro">
      <span className="seller-eyebrow">ZAKAZ · SELLER WORKSPACE R1</span>
      <h1>Управление объявлениями</h1>
      <p>Создавайте черновики существующих товаров, задавайте цену SKU,
        загружайте фотографии и публикуйте QR-объявления.
        Заказы и остатки остаются под контролем Backend.</p>
    </header>
    {!sellerAuth&&<p role="alert">Отдельный OIDC-клиент продавца не настроен.</p>}
    {sellerAuth&&!authReady&&<p role="status">Проверяем авторизацию…</p>}
    {sellerAuth&&authReady&&!user&&<section className="seller-panel">
      <h2>Авторизация продавца</h2>
      <p>Вход через отдельный публичный клиент Keycloak. Доступ проверяется по активному
        членству продавца в Backend; ввод UUID не выдаёт полномочий.</p>
      <button type="button" onClick={()=>void loginSeller().catch(
        ()=>setError('Вход продавца через Keycloak не запустился.'))}>
        Войти как продавец
      </button>
    </section>}
    {user&&<div className="seller-content">
      <div className="seller-account"><span>Сеанс продавца активен</span>
        <button type="button" onClick={()=>void logoutSeller().catch(
          ()=>setError('Не удалось завершить сеанс Keycloak.'))}>Выйти</button></div>
      <section className="seller-panel">
        <h2>Организация продавца</h2>
        {availableSellers.length>0&&<label>Разрешённые организации
          <select aria-label="Доступная организация" value={sellerInput}
            onChange={e=>setSellerInput(e.target.value)}>
            {availableSellers.length>1&&<option value="">Выберите организацию</option>}
            {availableSellers.map(s=><option key={s.sellerId} value={s.sellerId}>
              {s.sellerId} · {s.role}
            </option>)}
          </select>
        </label>}
        {accountsError&&<p className="seller-hint" role="status">{accountsError}</p>}
        <form onSubmit={connect} className="seller-inline">
          <label>Seller ID
            <input value={sellerInput} onChange={e=>setSellerInput(e.target.value)}
              placeholder="UUID организации" autoComplete="off" required/></label>
          <button type="submit" disabled={loading||!uuid.test(sellerInput)}>
            Проверить доступ и загрузить</button>
        </form>
        <p className="seller-hint">На первом этапе Seller ID выдаёт администратор.
          Подключение выбирает организацию, но не предоставляет прав.</p>
      </section>
      {sellerId&&<section className="seller-panel">
        <h2>Создать объявление</h2>
        <p className="seller-hint">R1: выбирается уже существующий канонический физический товар.
          Свободное создание HCC и новых справочников продавцу недоступно.</p>
        <form onSubmit={create} className="seller-form">
          <label>Канонический Product ID
            <input value={product} onChange={e=>setProduct(e.target.value)}
              placeholder="UUID товара" required/></label>
          <label>Код SKU
            <input value={skuCode} maxLength={80}
              onChange={e=>setSkuCode(e.target.value)} placeholder="Например, DRILL-001" required/></label>
          <label>Единица измерения
            <select value={unit} onChange={e=>setUnit(e.target.value)}>
              {physicalUnits.map(name=><option key={name}>{name}</option>)}
            </select></label>
          <label>Публичная цена за единицу, ₸ (необязательно)
            <input value={price} onChange={e=>setPrice(e.target.value)}
              inputMode="decimal" placeholder="125000.50"/></label>
          <button type="submit" disabled={busy}>Создать черновик и SKU</button>
        </form>
      </section>}
      {sellerId&&<section className="seller-panel">
        <div className="seller-section-title"><h2>Мои объявления</h2>
          <button type="button" onClick={()=>void refresh()} disabled={loading}>Обновить</button></div>
        {loading&&<p role="status">Загружаем объявления…</p>}
        {!loading&&page&&page.total===0&&<p>Объявлений пока нет. Создайте первый черновик.</p>}
        {page?.items.map(listing=><ListingPanel key={listing.listingId}
          listing={listing} sellerId={sellerId} token={user.access_token} refresh={refresh}/>)}
        {page&&page.total>page.size&&<div className="seller-pagination">
          <button type="button" disabled={loading||page.page===0}
            onClick={()=>void loadPage(sellerId,user.access_token,page.page-1)}>Назад</button>
          <span>Страница {page.page+1} · всего {page.total}</span>
          <button type="button" disabled={loading||(page.page+1)*page.size>=page.total}
            onClick={()=>void loadPage(sellerId,user.access_token,page.page+1)}>Далее</button>
        </div>}
      </section>}
      {sellerId&&<SellerDealsPanel key={sellerId} sellerId={sellerId} token={user.access_token}/>}
    </div>}
    {error&&<p role="alert" className="seller-error">{error}</p>}
    {success&&<p role="status" className="seller-feedback">{success}</p>}
  </main>;
}
