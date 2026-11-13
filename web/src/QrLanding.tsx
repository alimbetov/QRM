import { useEffect, useState, type FormEvent } from 'react';
import { publicQrApiBase, publicQrPath, validContactPhone, type QrListing } from './qr-api';
import './styles.css';
import './qr-landing.css';

type LoadingState = 'loading' | 'ready' | 'notfound' | 'unavailable';
type SubmitState = 'idle' | 'working' | 'accepted' | 'error' | 'rate-limited';

export default function QrLanding({ token }: { token: string }) {
  const [listing, setListing] = useState<QrListing | null>(null);
  const [loading, setLoading] = useState<LoadingState>('loading');
  const [phone, setPhone] = useState('');
  const [consent, setConsent] = useState(false);
  const [submitState, setSubmitState] = useState<SubmitState>('idle');
  const [requestKey, setRequestKey] = useState(() => crypto.randomUUID());
  useEffect(() => {
    const abort = new AbortController();
    setLoading('loading');
    fetch(publicQrApiBase() + publicQrPath(token), {
      signal: abort.signal,
      headers: { Accept: 'application/json' },
      credentials: 'omit'
    }).then(async response => {
      if (response.status === 404) { setLoading('notfound'); return; }
      if (!response.ok) { setLoading('unavailable'); return; }
      const body = await response.json() as QrListing;
      setListing(body);
      setLoading('ready');
    }).catch(error => {
      if (!(error instanceof DOMException && error.name === 'AbortError'))
        setLoading('unavailable');
    });
    return () => abort.abort();
  }, [token]);

  async function sendContact(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!validContactPhone(phone) || !consent || submitState === 'working') return;
    setSubmitState('working');
    try {
      const response = await fetch(publicQrApiBase() + publicQrPath(token) + '/leads', {
        method: 'POST',
        credentials: 'omit',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': requestKey,
          Accept: 'application/json'
        },
        body: JSON.stringify({ phone: phone.trim(), consent: true })
      });
      if (response.status === 202) { setSubmitState('accepted'); return; }
      if (response.status === 429) { setSubmitState('rate-limited'); return; }
      setSubmitState('error');
    } catch {
      // Keep the same idempotency key after network failure to permit safe retry.
      setSubmitState('error');
    }
  }
  return <div className="qr-shell">
    <header className="qr-topbar"><a className="brand" href="/" aria-label="ZAKAZ, каталог"><span className="brand-symbol">Z</span><span>ZAKAZ</span></a><span>Карточка по QR</span></header>
    <main className="qr-main">
      {loading === 'loading' && <div role="status" className="qr-panel"><p>Загрузка предложения…</p></div>}
      {loading === 'notfound' && <div role="alert" className="qr-panel"><h1>Предложение недоступно</h1><p>QR-ссылка не найдена или товар больше не опубликован.</p><a href="/">Вернуться в каталог</a></div>}
      {loading === 'unavailable' && <div role="alert" className="qr-panel"><h1>Не удалось загрузить предложение</h1><p>Проверьте соединение и попробуйте открыть ссылку снова.</p></div>}
      {loading === 'ready' && listing && <>
        <section className="qr-panel qr-product">
          <span className="qr-eyebrow">{listing.category}</span>
          <h1>{listing.title}</h1>
          <p className="qr-muted">Опубликованное предложение продавца</p>
          <h2>Варианты товара</h2>
          <ul className="qr-variants">
            {listing.variants.map(variant => <li key={variant.id}>
              <strong>{variant.code}</strong><span>{variant.unit || 'Единица уточняется'}</span>
            </li>)}
          </ul>
          <p className="qr-note">Цена и наличие не подтверждаются этой карточкой. Уточните условия у продавца.</p>
        </section>
        <section className="qr-panel">
          <h2>Связаться с продавцом</h2>
          <p className="qr-muted">Оставьте телефон для обратной связи. Это не оформление заказа и не подтверждение покупки.</p>
          {submitState === 'accepted' ? <p role="status" className="qr-success">Обращение принято. Номер используется только для связи по вашему запросу.</p> :
            <form onSubmit={sendContact} className="qr-form">
              <label htmlFor="qr-phone">Номер телефона</label>
              <input id="qr-phone" type="tel" autoComplete="tel" inputMode="tel" value={phone}
                placeholder="+77011234567" onChange={e => { setPhone(e.target.value); setRequestKey(crypto.randomUUID()); setSubmitState('idle'); }}
                required aria-invalid={phone.length > 0 && !validContactPhone(phone)} />
              <label className="qr-consent"><input type="checkbox" checked={consent}
                onChange={e => setConsent(e.target.checked)}/>
                <span>Согласен на обработку номера телефона для ответа продавца</span>
              </label>
              <button className="apply-btn" type="submit" disabled={!consent || !validContactPhone(phone) || submitState === 'working'}>
                {submitState === 'working' ? 'Отправляем…' : 'Отправить обращение'}
              </button>
              {submitState === 'rate-limited' && <p role="alert" className="qr-warning">Слишком много обращений. Повторите попытку позднее.</p>}
              {submitState === 'error' && <p role="alert" className="qr-warning">Обращение не принято. Повторите попытку или попробуйте позже.</p>}
            </form>}
        </section>
      </>}
    </main>
    <footer><span>ZAKAZ · QR-first</span><span>Обращение гостя без регистрации</span></footer>
  </div>;
}
