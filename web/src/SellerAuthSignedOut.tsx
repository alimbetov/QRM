import {useEffect,useState} from 'react';
import {sellerAuth} from './seller-oidc';

export default function SellerAuthSignedOut() {
  const [state,setState]=useState<'pending'|'complete'|'error'>('pending');
  useEffect(()=>{
    if(!sellerAuth){setState('error');return;}
    let active=true;
    sellerAuth.signoutRedirectCallback()
      .then(()=>sellerAuth.removeUser())
      .then(()=>{if(active)setState('complete');})
      .catch(()=>{if(active)setState('error');});
    return ()=>{active=false;};
  },[]);
  return <main className="workspace-auth">
    <h1>Выход продавца</h1>
    {state==='pending'&&<p role="status">Проверяем завершение сессии Keycloak…</p>}
    {state==='complete'&&<p role="status">Сеанс завершён. <a href="/seller">В кабинет продавца</a></p>}
    {state==='error'&&<p role="alert">Не удалось подтвердить выход. Попробуйте завершить сеанс ещё раз.</p>}
  </main>;
}
