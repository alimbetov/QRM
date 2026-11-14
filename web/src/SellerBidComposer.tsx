import {useEffect,useState,type FormEvent} from 'react';
import {
  loadInvitationQuote,submitInvitationBid,priceKzt,stockCovers,
  type InvitationQuoteContext,type BidInput,type MatchingSku
} from './seller-bid-api';

export default function SellerBidComposer({sellerId,invitationId,token}:{
  sellerId:string;invitationId:string;token:string
}){
  const [context,setContext]=useState<InvitationQuoteContext|null>(null);
  const [chosen,setChosen]=useState<Record<string,string>>({});
  const [prices,setPrices]=useState<Record<string,string>>({});
  const [originId,setOriginId]=useState('');
  const [method,setMethod]=useState<'PICKUP'|'DELIVERY'>('PICKUP');
  const [fee,setFee]=useState('0.00');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [receipt,setReceipt]=useState('');
  useEffect(()=>{
    let active=true;
    setContext(null);setError('');setReceipt('');setBusy(true);
    loadInvitationQuote(sellerId,invitationId,token).then(data=>{
      if(!active)return;
      setContext(data);
      setChosen({});setPrices({});
      const first=data.origins.find(o=>o.pickup||o.delivery);
      setOriginId(first?.originLocationId||'');
      setMethod(first?.pickup?'PICKUP':'DELIVERY');setFee('0.00');
    }).catch(()=>{
      if(active)setError('Заявка недоступна: проверьте согласия, срок действия и подключение.');
    }).finally(()=>{if(active)setBusy(false);});
    return ()=>{active=false;};
  },[sellerId,invitationId,token]);

  const origin=context?.origins.find(o=>o.originLocationId===originId);
  const canPickup=origin?.pickup===true;
  const canDeliver=origin?.delivery===true;
  function chosenSku(productId:string):MatchingSku|undefined{
    return context?.lines.find(x=>x.productId===productId)?.skus
      .find(x=>x.skuId===chosen[productId]);
  }
  const available=context?.lines.every(line=>{
    const sku=chosenSku(line.productId);
    return sku&&stockCovers(sku.availableByOrigin[originId]||'0',line.quantity)&&
      priceKzt.test(prices[line.productId]||'');
  })===true;
  const expiry=context?Math.min(new Date(context.expiresAt).getTime()-1000,
                                Date.now()+30*60*1000):0;
  const ready=context&&origin&&(method==='PICKUP'?canPickup:canDeliver)&&available&&
    priceKzt.test(fee)&&!(method==='PICKUP'&&fee!=='0.00'&&fee!=='0')&&
    expiry>Date.now()+5000&&!busy&&!receipt;

  async function submit(event:FormEvent<HTMLFormElement>){
    event.preventDefault();
    if(!context||!ready)return;
    const terms:BidInput={
      revision:context.nextRevision,
      expiresAt:new Date(Math.min(
        new Date(context.expiresAt).getTime()-1000,Date.now()+30*60*1000)).toISOString(),
      originLocationId:originId,method,deliveryFee:method==='PICKUP'?'0.00':fee,
      lines:context.lines.map(line=>({
        productId:line.productId,skuId:chosen[line.productId],
        quantity:line.quantity,unitPrice:prices[line.productId]
      }))
    };
    if(!window.confirm('Отправить ставку покупателю? Это НЕ создаёт заказ и НЕ резервирует склад.'))return;
    setBusy(true);setError('');
    try{
      const id=await submitInvitationBid(sellerId,invitationId,token,context,terms);
      setReceipt(id);
    }catch{
      setError('Ставка не подтверждена сервером. Не отправляйте повторно без обновления заявки: возможно, изменились согласия, остатки или ревизия.');
    }finally{setBusy(false);}
  }
  if(busy&&!context)return <p role="status">Проверяем корзину и доступные SKU…</p>;
  return <section className="seller-quote" aria-label="Подготовка ставки">
    {error&&<p role="alert" className="seller-error">{error}</p>}
    {context&&<>
      <h4>Предложение по заявке</h4>
      <p className="seller-hint">Доставка: {context.destinationCity}.
        Срок заявки: {new Date(context.expiresAt).toLocaleString('ru-RU')}.
        Ревизия предложения: {context.nextRevision}.</p>
      <form onSubmit={submit} className="seller-quote-form">
        {context.lines.map(line=>{
          const sku=chosenSku(line.productId);
          return <fieldset key={line.productId}>
            <legend>{line.productName} · HCC {line.hccCode}</legend>
            <p>Покупатель запрашивает: <strong>{line.quantity} {line.unit}</strong>
              {line.maxUnitPrice&&<> · ориентир {line.maxUnitPrice} ₸/ед.</>}</p>
            <label>Мой SKU
              <select value={chosen[line.productId]||''}
                onChange={e=>{
                  const item=line.skus.find(s=>s.skuId===e.target.value);
                  setChosen({...chosen,[line.productId]:e.target.value});
                  setPrices({...prices,[line.productId]:item?.priceKzt||''});
                }} required>
                <option value="">Выберите совместимый SKU</option>
                {line.skus.map(item=><option key={item.skuId} value={item.skuId}>
                  {item.code} · остаток на выбранной локации: {item.availableByOrigin[originId]||'0'}
                  {' '} {line.unit}
                </option>)}
              </select></label>
            {sku&&!stockCovers(sku.availableByOrigin[originId]||'0',line.quantity)&&
              <p role="alert">Недостаточный свободный остаток на выбранной локации.</p>}
            <label>Моя цена за {line.unit}, ₸
              <input value={prices[line.productId]||''}
                onChange={e=>setPrices({...prices,[line.productId]:e.target.value})}
                inputMode="decimal" placeholder="125000.00" required/></label>
          </fieldset>;
        })}
        <label>Место исполнения
          <select value={originId} onChange={e=>{
            const id=e.target.value;
            const loc=context.origins.find(o=>o.originLocationId===id);
            setOriginId(id);
            setMethod(loc?.pickup?'PICKUP':'DELIVERY');
            setFee('0.00');
          }} required>
            {context.origins.filter(o=>o.pickup||o.delivery)
              .map(o=><option value={o.originLocationId} key={o.originLocationId}>
                {o.city}, {o.address} ({o.type})
              </option>)}
          </select></label>
        <fieldset>
          <legend>Способ исполнения</legend>
          <label className="seller-choice"><input type="radio" checked={method==='PICKUP'}
            disabled={!canPickup} onChange={()=>{setMethod('PICKUP');setFee('0.00');}}/>
            Самовывоз</label>
          <label className="seller-choice"><input type="radio" checked={method==='DELIVERY'}
            disabled={!canDeliver} onChange={()=>setMethod('DELIVERY')}/>
            Доставка в {context.destinationCity}</label>
        </fieldset>
        {method==='DELIVERY'&&<label>Стоимость доставки, ₸
          <input value={fee} inputMode="decimal"
            onChange={e=>setFee(e.target.value)} required/>
        </label>}
        <p className="seller-hint">Свободный остаток не резервируется при отправке ставки.
          Итоговая сумма с доставкой и заказ создаются только по проверенным серверным правилам.
          При подтверждении заказа остатки проверяются повторно.</p>
        <button type="submit" disabled={!ready}>
          {busy?'Сохраняем…':'Отправить ставку покупателю'}
        </button>
      </form>
    </>}
    {receipt&&<p role="status" className="seller-feedback">
      Ставка записана в PostgreSQL: <code>{receipt}</code>.
      Покупатель решает, принимать ли её; заказ пока не создан.
    </p>}
  </section>;
}
