import { useEffect, useState } from 'react';
import { completeBuyerLogout } from './oidc';

/** Finalizes logout only after successful OIDC state validation by oidc-client-ts. */
export default function AuthSignedOut() {
  const [status,setStatus]=useState<'pending'|'complete'|'error'>('pending');
  useEffect(()=>{
    let alive=true;
    completeBuyerLogout()
      .then(()=>{if(alive)setStatus('complete');})
      .catch(()=>{if(alive)setStatus('error');});
    return ()=>{alive=false;};
  },[]);
  return <main style={{maxWidth:600,margin:'4rem auto',padding:'1rem',fontFamily:'system-ui'}}>
    <a href="/">ZAKAZ</a>
    <h1>Завершение сеанса</h1>
    {status==='pending' && <p role="status">Проверяем завершение сеанса в Keycloak…</p>}
    {status==='complete' && <p role="status">Вы вышли из учётной записи. <a href="/">Вернуться в каталог</a></p>}
    {status==='error' && <p role="alert">Не удалось подтвердить выход в Keycloak. Закройте вкладку и повторите выход через провайдера.</p>}
  </main>;
}
