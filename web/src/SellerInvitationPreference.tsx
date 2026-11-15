import {useEffect,useState} from 'react';
import {loadSellerPreference,setSellerPreference} from './invitation-consent-api';

export default function SellerInvitationPreference({sellerId,token}:{
  sellerId:string;token:string
}){
  const [enabled,setEnabled]=useState(false);
  const [ready,setReady]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  useEffect(()=>{
    let active=true;
    setReady(false);setEnabled(false);setError('');
    loadSellerPreference(sellerId,token)
      .then(value=>{if(active){setEnabled(value);setReady(true);}})
      .catch(()=>{if(active)setError('Настройка приглашений недоступна.');});
    return ()=>{active=false;};
  },[sellerId,token]);
  async function change(next:boolean){
    if(!ready||busy)return;
    setBusy(true);setError('');
    try{await setSellerPreference(sellerId,token,next);setEnabled(next);}
    catch{setError('Согласие продавца не изменено. Проверьте права SELLER_ADMIN.');}
    finally{setBusy(false);}
  }
  return <div className="seller-consent">
    <h3>Согласие продавца</h3>
    <p className="seller-hint">Приглашения показываются только при отдельном
      согласии покупателя по конкретной заявке. Включение не отправляет SMS/Push.</p>
    {ready?<label className="seller-choice">
      <input type="checkbox" checked={enabled} disabled={busy}
        onChange={e=>void change(e.target.checked)}/>
      Разрешить in-app приглашения этой организации
    </label>:<p role="status">Проверяем предпочтение продавца…</p>}
    {ready&&<p className="seller-hint">
      {enabled?'Включено на сервере. Нужны действующие приглашения.':'Выключено на сервере.'}
    </p>}
    {error&&<p role="alert" className="seller-error">{error}</p>}
  </div>;
}
