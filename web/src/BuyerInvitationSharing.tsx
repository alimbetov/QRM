import {useEffect,useState} from 'react';
import {loadDemandSharing,setDemandSharing} from './invitation-consent-api';

export default function BuyerInvitationSharing({demandId,token}:{
  demandId:string;token:string
}){
  const [enabled,setEnabled]=useState(false);
  const [ready,setReady]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  useEffect(()=>{
    let active=true;
    setReady(false);setEnabled(false);setError('');
    loadDemandSharing(demandId,token)
      .then(value=>{if(active){setEnabled(value);setReady(true);}})
      .catch(()=>{if(active)setError('Состояние согласия недоступно. Приглашение продавцов не включаем.');});
    return ()=>{active=false;};
  },[demandId,token]);
  async function change(next:boolean){
    if(busy||!ready)return;
    setBusy(true);setError('');
    try{
      await setDemandSharing(demandId,token,next);
      setEnabled(next);
    }catch{setError('Не удалось изменить согласие. Текущее значение не изменено.');}
    finally{setBusy(false);}
  }
  return <section className="buyer-panel" aria-label="Согласие на приглашения продавцов">
    <h3>Пригласить подходящих продавцов</h3>
    <p className="buyer-muted">По умолчанию ваша заявка не передаётся продавцам через
      механизм приглашений. Вы можете разрешить показ её состава подходящим продавцам.
      Телефон и email не передаются в приглашении. Согласие можно отозвать.</p>
    {ready?<label className="buyer-consent-label">
      <input type="checkbox" checked={enabled} disabled={busy}
        onChange={e=>void change(e.target.checked)}/>
      Разрешаю приглашения по этой заявке
    </label>:<p role="status">Проверяем состояние согласия…</p>}
    {error&&<p role="alert" className="buyer-error">{error}</p>}
    {ready&&<p className="buyer-muted">
      {enabled?'Разрешение сохранено сервером. Это не гарантирует появление ставок.':
        'Приглашения по этой заявке отключены.'}
    </p>}
  </section>;
}
