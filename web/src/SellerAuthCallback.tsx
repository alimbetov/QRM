import {useEffect,useState} from 'react';
import {sellerAuth,sellerReturnTo} from './seller-oidc';

export default function SellerAuthCallback() {
  const [error,setError]=useState('');
  useEffect(()=>{
    if(!sellerAuth){setError('OIDC продавца не настроен.');return;}
    sellerAuth.signinRedirectCallback()
      .then(user=>{
        const state=user.state as {returnTo?:unknown}|undefined;
        window.location.replace(sellerReturnTo(state?.returnTo));
      })
      .catch(()=>setError('Не удалось подтвердить вход продавца через Keycloak.'));
  },[]);
  return <main className="workspace-auth">
    <h1>Вход продавца</h1>
    <p role={error?'alert':'status'}>{error||'Проверяем учётную запись продавца…'}</p>
    {error&&<a href="/seller">Вернуться в кабинет продавца</a>}
  </main>;
}
