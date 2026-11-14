import {useCallback,useEffect,useRef,useState} from 'react';
import SellerBidComposer from './SellerBidComposer';
import {
  changeSellerOrder,markSellerLead,sellerInvitations,sellerLeads,sellerOrders,
  type SellerLead,type SellerOrdersPage,type SellerInvitation
} from './seller-deals-api';

type Tab='orders'|'callbacks'|'invitations';
const statuses=['ALL','PLACED','CONFIRMED','CANCELLED_BY_BUYER','CANCELLED_BY_SELLER'];
export default function SellerDealsPanel({sellerId,token}:{
  sellerId:string;token:string
}) {
  const [tab,setTab]=useState<Tab>('orders');
  const [replyTo,setReplyTo]=useState<string|null>(null);
  const quoteEnabled=import.meta.env.VITE_SELLER_BID_WORKSPACE_ENABLED==='true';
  const [orders,setOrders]=useState<SellerOrdersPage|null>(null);
  const [leads,setLeads]=useState<SellerLead[]|null>(null);
  const [invitations,setInvitations]=useState<SellerInvitation[]|null>(null);
  const [page,setPage]=useState(0);
  const [status,setStatus]=useState('ALL');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [message,setMessage]=useState('');
  const generation=useRef(0);

  const refreshOrders=useCallback(async (number:number,filter:string)=>{
    const request=++generation.current;
    setBusy(true);setError('');
    try{
      const result=await sellerOrders(sellerId,token,number,filter);
      if(request===generation.current){setOrders(result);setPage(number);setStatus(filter);}
    }catch{
      if(request===generation.current)setError('Заказы не загружены. Проверьте права и подключение.');
    }finally{if(request===generation.current)setBusy(false);}
  },[sellerId,token]);

  useEffect(()=>{
    setOrders(null);setLeads(null);setInvitations(null);setPage(0);setStatus('ALL');
    setTab('orders');setMessage('');setError('');
    void refreshOrders(0,'ALL');
    return ()=>{generation.current++};
  },[refreshOrders]);

  async function actOrder(orderId:string,action:'confirm'|'cancel'){
    if(busy)return;
    const warning=action==='confirm'?
      'Подтвердить заказ? Остаток будет зарезервирован.':
      'Отменить заказ? Активный резерв будет освобождён.';
    if(!window.confirm(warning))return;
    setBusy(true);setError('');setMessage('');
    try{
      await changeSellerOrder(sellerId,orderId,token,action);
      const result=await sellerOrders(sellerId,token,page,status);
      setOrders(result);
      setMessage('Статус заказа обновлён по данным сервера.');
    }catch{setError('Операция отклонена или заказ изменился. Обновите список.');}
    finally{setBusy(false);}
  }
  async function openLeads(){
    setTab('callbacks');setLeads(null);setError('');setMessage('');setBusy(true);
    try{setLeads(await sellerLeads(sellerId,token));}
    catch{setError('Не удалось получить заявки. Нужны отдельное разрешение на чтение контактов и ключ шифрования.');}
    finally{setBusy(false);}
  }
  async function changeLead(id:string,next:'CONTACTED'|'DECLINED'){
    if(busy || !window.confirm('Зафиксировать обработку заявки? Действие нельзя отменить.'))return;
    setBusy(true);setError('');setMessage('');
    try{
      await markSellerLead(sellerId,id,token,next);
      // Re-read the auditable source; never claim a phone call was verified.
      setLeads(await sellerLeads(sellerId,token));
      setMessage('Статус обращения записан. Это отметка продавца, а не проверка звонка.');
    }catch{setError('Заявка не изменена. Возможно, она уже обработана.');}
    finally{setBusy(false);}
  }
  async function openInvitations(){
    setTab('invitations');setInvitations(null);setError('');setMessage('');setBusy(true);
    try{setInvitations(await sellerInvitations(sellerId,token));}
    catch{setError('Приглашения не загружены. Проверьте согласия и включение ML-R2 Inbox.');}
    finally{setBusy(false);}
  }
  return <section className="seller-panel" aria-label="Сделки продавца">
    <h2>Сделки и обращения</h2>
    <div className="seller-deal-tabs" role="group" aria-label="Разделы кабинета">
      <button type="button" aria-pressed={tab==='orders'}
        disabled={busy} onClick={()=>{setTab('orders');setError('');setMessage('');void refreshOrders(page,status)}}>
        Заказы</button>
      <button type="button" aria-pressed={tab==='callbacks'} disabled={busy}
        onClick={()=>void openLeads()}>Обратные звонки</button>
      <button type="button" aria-pressed={tab==='invitations'} disabled={busy}
        onClick={()=>void openInvitations()}>Приглашения</button>
    </div>
    {tab==='orders'&&<>
      <div className="seller-section-title">
        <label>Статус заказов
          <select aria-label="Фильтр статуса" value={status} disabled={busy}
            onChange={e=>void refreshOrders(0,e.target.value)}>
            {statuses.map(x=><option key={x}>{x}</option>)}
          </select>
        </label>
        <button type="button" disabled={busy}
          onClick={()=>void refreshOrders(page,status)}>Обновить заказы</button>
      </div>
      {orders&&orders.items.length===0&&<p>Заказов с выбранным статусом нет.</p>}
      {orders?.items.map(o=><article className="seller-listing" key={o.orderId}>
        <div className="seller-listing-head">
          <div><h3>Заказ {o.publicNumber}</h3>
            <p className="seller-hint">Создан: {new Date(o.createdAt).toLocaleString('ru-RU')}</p></div>
          <span className="seller-status">{o.status}</span>
        </div>
        <p><strong>{o.totalAmount} {o.currency}</strong> · доставка {o.deliveryFee} {o.currency}</p>
        <ul>{o.lines.map(line=><li key={line.skuId}>
          SKU {line.skuId.slice(0,8)} · {line.quantity} {line.unit} · {line.lineTotal} ₸
        </li>)}</ul>
        <div className="seller-listing-actions">
          {o.status==='PLACED'&&
            <button type="button" disabled={busy}
              onClick={()=>void actOrder(o.orderId,'confirm')}>Подтвердить и зарезервировать</button>}
          {(o.status==='PLACED'||o.status==='CONFIRMED')&&
            <button type="button" disabled={busy}
              onClick={()=>void actOrder(o.orderId,'cancel')}>Отменить заказ</button>}
        </div>
      </article>)}
      {orders&&<div className="seller-pagination">
        <button disabled={busy||page===0} onClick={()=>void refreshOrders(page-1,status)}>Назад</button>
        <span>Страница {page+1} · всего {orders.total}</span>
        <button disabled={busy||(page+1)*orders.size>=orders.total}
          onClick={()=>void refreshOrders(page+1,status)}>Далее</button>
      </div>}
    </>}
    {tab==='callbacks'&&<>
      <p className="seller-hint">Номера раскрываются только после явного открытия раздела. Каждое чтение фиксируется в аудит-журнале.</p>
      {leads?.length===0&&<p>Входящих заявок пока нет.</p>}
      {leads?.map(l=><article className="seller-listing" key={l.leadId}>
        <h3>Заявка на звонок · {l.status}</h3>
        <p>Объявление {l.listingId.slice(0,8)} · {new Date(l.receivedAt).toLocaleString('ru-RU')}</p>
        <p><a href={'tel:'+l.phone}>{l.phone}</a></p>
        {l.status==='NEW'&&<div className="seller-listing-actions">
          <button type="button" disabled={busy}
            onClick={()=>void changeLead(l.leadId,'CONTACTED')}>Отметить «Связались»</button>
          <button type="button" disabled={busy}
            onClick={()=>void changeLead(l.leadId,'DECLINED')}>Отклонить заявку</button>
        </div>}
      </article>)}
      <button type="button" disabled={busy} onClick={()=>void openLeads()}>Обновить заявки</button>
    </>}
    {tab==='invitations'&&<>
      <p className="seller-hint">Только разрешённые покупателем и продавцом in-app приглашения. Никакие Push/SMS автоматически не отправляются.</p>
      {invitations?.length===0&&<p>Доступных приглашений сейчас нет.</p>}
      {invitations?.map(i=><article className="seller-listing" key={i.invitationId}>
        <h3>Запрос покупателя</h3>
        <p>Demand: {i.demandId} · получен: {new Date(i.deliveredAt).toLocaleString('ru-RU')}</p>
        {quoteEnabled?<div className="seller-quote-action">
          <button type="button" disabled={busy} onClick={()=>
            setReplyTo(replyTo===i.invitationId?null:i.invitationId)}>
            {replyTo===i.invitationId?'Закрыть форму':'Ответить ставкой'}
          </button>
          {replyTo===i.invitationId&&<SellerBidComposer
            key={i.invitationId} invitationId={i.invitationId}
            sellerId={sellerId} token={token}/>}
        </div>:<p className="seller-hint">
          Ответ ставкой через React отключён до приёмки R2 и включения Backend.</p>}
      </article>)}
      <button type="button" disabled={busy} onClick={()=>void openInvitations()}>
        Обновить приглашения</button>
    </>}
    {busy&&<p role="status">Выполняем запрос…</p>}
    {error&&<p role="alert" className="seller-error">{error}</p>}
    {message&&<p role="status" className="seller-feedback">{message}</p>}
  </section>;
}
