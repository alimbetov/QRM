import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import type { User } from 'oidc-client-ts';
import { buyerAuth, loginBuyer, registerBuyer, logoutBuyer } from './oidc';
import {
  createBuyerDemand, loadBuyerBids, acceptBuyerBid, loadBuyerOrder,
  type BuyerBids, type BuyerOrder
} from './buyer-api';
import { formatPublishedPriceKzt } from './qr-api';
import {enrollBuyer,loadBuyerProfile,type BuyerProfile} from './enrollment-api';
import './buyer-flow.css';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const supportedUnits = new Set(['PCS','KG','G','L','ML','M','M2','M3','HOUR']);
const qs = new URLSearchParams(window.location.search);

export default function BuyerFlow() {
  const productId=qs.get('productId') || '';
  const unit=qs.get('unit') || '';
  const [user,setUser]=useState<User|null>(null);
  const [authReady,setAuthReady]=useState(false);
  const [profile,setProfile]=useState<BuyerProfile|null>(null);
  const [profileReady,setProfileReady]=useState(false);
  const [nickname,setNickname]=useState('');
  const [phone,setPhone]=useState('');
  const [consent,setConsent]=useState(false);
  const [city,setCity]=useState('Алматы');
  const [quantity,setQuantity]=useState('1');
  const [demandId,setDemandId]=useState(qs.get('demandId') || '');
  const [orderId,setOrderId]=useState(qs.get('orderId') || '');
  const [quotes,setQuotes]=useState<BuyerBids|null>(null);
  const [order,setOrder]=useState<BuyerOrder|null>(null);
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  const keys=useRef<Map<string,string>>(new Map());

  useEffect(() => {
    if (!buyerAuth) {setAuthReady(true);return;}
    buyerAuth.getUser().then(u=>{
      setUser(u && !u.expired ? u : null);
      setAuthReady(true);
    }).catch(()=>setAuthReady(true));
  },[]);

  useEffect(()=>{
    let active=true;
    setProfile(null);setProfileReady(false);
    if (!user) return;
    loadBuyerProfile(user.access_token)
      .then(found=>{if(active){setProfile(found);setProfileReady(true);}})
      .catch(()=>{if(active){setProfileReady(false);setError('Не удалось проверить профиль покупателя. Проверьте подтверждение email и доступ к регистрации.');}});
    return ()=>{active=false;};
  },[user]);

  useEffect(()=>{
    if (!buyerAuth) return;
    const expired=()=>{
      setUser(null);setProfile(null);setProfileReady(false);
      setQuotes(null);setOrder(null);
      setError('Срок действия сеанса истёк. Для нового запроса войдите снова.');
    };
    buyerAuth.events.addAccessTokenExpired(expired);
    return ()=>buyerAuth.events.removeAccessTokenExpired(expired);
  },[]);

  const refreshBids=useCallback(async ()=>{
    if (!user || !profile || !demandId || !uuid.test(demandId)) return;
    try{
      setBusy(true);setError('');
      const result=await loadBuyerBids(user.access_token,demandId);
      setQuotes(result);
      if (result.acceptedOrderId) setOrderId(result.acceptedOrderId);
    }catch{
      setError('Не удалось получить предложения. Проверьте авторизацию и повторите запрос.');
    }finally{setBusy(false);}
  },[user,profile,demandId]);

  const refreshOrder=useCallback(async ()=>{
    if (!user || !profile || !orderId || !uuid.test(orderId)) return;
    try{
      setBusy(true);setError('');
      setOrder(await loadBuyerOrder(user.access_token,orderId));
    }catch{setError('Не удалось прочитать заказ. Проверьте доступ к заказу.');}
    finally{setBusy(false);}
  },[user,profile,orderId]);

  useEffect(()=>{void refreshBids();},[refreshBids]);
  useEffect(()=>{void refreshOrder();},[refreshOrder]);

  async function register(event:FormEvent<HTMLFormElement>){
    event.preventDefault();
    if(!user || !/^[\p{L}\p{N}][\p{L}\p{N}_.-]{2,39}$/u.test(nickname) ||
       !/^\+[1-9][0-9]{7,14}$/.test(phone) || !consent){
      setError('Укажите ник, номер в международном формате и согласие на сохранение контакта.');
      return;
    }
    try {
      setBusy(true);setError('');
      setProfile(await enrollBuyer(user.access_token,nickname,phone,consent));
    } catch {
      setError('Не удалось завершить регистрацию. Необходим подтверждённый email в OIDC и активный сервис регистрации.');
    } finally {setBusy(false);}
  }

  async function create(event:FormEvent<HTMLFormElement>){
    event.preventDefault();
    if(!user || !uuid.test(productId) || !supportedUnits.has(unit) ||
       !/^\d{1,10}(\.\d{1,3})?$/.test(quantity) || Number(quantity)<=0 ||
       !city.trim() || city.length>160) {
      setError('Проверьте товар, единицу измерения, количество и город.');
      return;
    }
    try{
      setBusy(true);setError('');
      const id=await createBuyerDemand(user.access_token,productId,unit,quantity,city);
      setDemandId(id);
      window.history.replaceState(null,'','/buy?demandId='+encodeURIComponent(id));
    }catch{setError('Заявка не создана. Убедитесь, что покупатель зарегистрирован и авторизован.');}
    finally{setBusy(false);}
  }

  async function accept(bidId:string) {
    if(!user || !demandId || busy) return;
    let key=keys.current.get(bidId);
    if (!key){key=crypto.randomUUID();keys.current.set(bidId,key);}
    try{
      setBusy(true);setError('');
      const id=await acceptBuyerBid(user.access_token,demandId,bidId,key);
      setOrderId(id);
      setOrder(await loadBuyerOrder(user.access_token,id));
      window.history.replaceState(null,'','/buy?orderId='+encodeURIComponent(id));
    }catch{setError('Не удалось принять предложение. Обновите список: заявка могла быть принята ранее или срок предложения истёк.');}
    finally{setBusy(false);}
  }

  async function signOut() {
    setError('');
    try {
      await logoutBuyer();
    } catch {
      setError('Не удалось завершить сеанс в Keycloak. Локальный токен не считается отозванным.');
    }
  }

  const configured=Boolean(buyerAuth);
  return <div className="buyer-shell">
    <header className="buyer-topbar"><a href="/">ZAKAZ</a><span>Оформление заказа</span>
      {user && <button onClick={()=>{void signOut();}}>Выйти через Keycloak</button>}</header>
    <main className="buyer-main">
      <div className="buyer-breadcrumb"><a href="/">Каталог</a> / Согласование предложения</div>
      <h1>Запросить цену и оформить заказ</h1>
      <p className="buyer-muted">Заказ формируется только после выбора предложения продавца. Резерв товара выполняется позже, когда продавец подтвердит заказ.</p>

      {!configured && <section className="buyer-panel" role="status">
        <h2>Вход пока недоступен</h2>
        <p>Не настроен внешний OIDC-провайдер покупателя. Тестовый вход и незаверенные телефоны не используются.</p>
      </section>}
      {configured && !authReady && <p role="status">Проверяем авторизацию…</p>}
      {configured && authReady && !user && <section className="buyer-panel">
        <h2>Требуется вход покупателя</h2>
        <p>Для согласования цен необходима подтверждённая учётная запись, привязанная к покупателю на сервере.</p>
        <button onClick={()=>{void loginBuyer(window.location.pathname+window.location.search).catch(()=>setError('Не удалось открыть провайдер входа.'));}}>Войти через OIDC</button>
        <button onClick={()=>{void registerBuyer(window.location.pathname+window.location.search).catch(()=>setError('Регистрация через Keycloak пока недоступна. Проверьте SMTP и настройку саморегистрации.'));}}>Создать аккаунт через Keycloak</button>
      </section>}
      {user && !profileReady && !error && <p role="status">Проверяем профиль покупателя…</p>}
      {user && profileReady && !profile && <section className="buyer-panel">
        <h2>Завершить регистрацию покупателя</h2>
        <p className="buyer-muted">Email должен быть подтверждён в Keycloak. Номер телефона сохраняется зашифрованным, но не подтверждается SMS и не используется для входа или восстановления доступа.</p>
        <form className="buyer-form" onSubmit={register}>
          <label>Никнейм<input maxLength={40} value={nickname} onChange={e=>setNickname(e.target.value)} required /></label>
          <label>Контактный телефон (+77001234567)<input type="tel" maxLength={16} value={phone} onChange={e=>setPhone(e.target.value)} required /></label>
          <label><input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)} required /> Согласен на сохранение контактного номера для обработки заказов</label>
          <button disabled={busy} type="submit">Создать профиль покупателя</button>
        </form>
      </section>}
      {user && profile && <div className="buyer-steps">
        {!demandId && !orderId && <section className="buyer-panel">
          <h2>1. Создать заявку</h2>
          {uuid.test(productId) && supportedUnits.has(unit) ? <form onSubmit={create} className="buyer-form">
            <p className="buyer-muted">Товар: {productId.slice(0,8)} · {unit}. Конечная цена будет известна после предложения продавца.</p>
            <label>Город<input maxLength={160} value={city} onChange={e=>setCity(e.target.value)} required /></label>
            <label>Количество<input value={quantity} inputMode="decimal" onChange={e=>setQuantity(e.target.value)} required /></label>
            <button disabled={busy} type="submit">Создать BuyerDemand</button>
          </form> : <p>Товар не выбран. Откройте опубликованный товар из <a href="/">каталога</a>.</p>}
        </section>}
        {demandId && !orderId && <section className="buyer-panel">
          <h2>2. Предложения продавцов</h2>
          <p className="buyer-muted">Номер заявки: {demandId}</p>
          <button disabled={busy} onClick={()=>{void refreshBids();}}>Обновить предложения</button>
          {!quotes && <p>Загружаем предложения…</p>}
          {quotes && quotes.bids.length===0 && <p>Предложений пока нет. Продавец должен направить SellerBid через авторизованный API.</p>}
          {quotes?.bids.map(bid=><article key={bid.bidId} className="buyer-quote">
            <p><strong>Продавец {bid.sellerId.slice(0,8)}</strong> · версия {bid.revision}</p>
            <h3>{formatPublishedPriceKzt(bid.totalKzt)}</h3>
            <p className="buyer-muted">{bid.method==='PICKUP'?'Самовывоз':'Доставка'} · доставка {formatPublishedPriceKzt(bid.deliveryFeeKzt)}</p>
            <p className="buyer-muted">Предложение до {new Date(bid.expiresAt).toLocaleString('ru-RU')}</p>
            <button disabled={busy} onClick={()=>{void accept(bid.bidId);}}>Принять и создать заказ</button>
          </article>)}
        </section>}
        {orderId && <section className="buyer-panel">
          <h2>3. Заказ создан</h2>
          <p className="buyer-muted">Идентификатор: {orderId}</p>
          <button disabled={busy} onClick={()=>{void refreshOrder();}}>Обновить статус</button>
          {order && <div className="buyer-order">
            <p>Номер заказа: <strong>{order.publicNumber}</strong></p>
            <p>Статус: <strong>{order.status}</strong></p>
            <p>Сумма заказа: <strong>{String(order.totalAmount)} {order.currency}</strong></p>
            <p className="buyer-muted">Статус PLACED не означает оплату или резервирование товара.</p>
          </div>}
        </section>}
      </div>}
      {error && <p role="alert" className="buyer-error">{error}</p>}
    </main>
  </div>;
}
