import { useEffect, useState } from 'react';
import { buyerAuth, safeReturnPath } from './oidc';

export default function AuthCallback() {
  const [error,setError] = useState('');
  useEffect(() => {
    if (!buyerAuth) { setError('Провайдер авторизации не настроен.'); return; }
    buyerAuth.signinRedirectCallback()
      .then(user => {
        const state=user.state as { returnTo?: unknown } | undefined;
        window.location.replace(safeReturnPath(state?.returnTo));
      })
      .catch(() => setError('Не удалось проверить результат входа. Повторите авторизацию.'));
  },[]);
  return <main style={{maxWidth:600,margin:'4rem auto',padding:'1rem',fontFamily:'system-ui'}}>
    <a href="/">ZAKAZ</a>
    <h1>Авторизация покупателя</h1>
    <p role={error ? 'alert' : 'status'}>{error || 'Проверяем ответ провайдера…'}</p>
    {error && <a href="/">Вернуться в каталог</a>}
  </main>;
}
