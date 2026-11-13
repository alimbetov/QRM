import { useEffect, useState } from 'react';
import { catalogUrl, loadCatalog, loadCategories, type LiveCategory, type CatalogPage } from './catalog-api';
import { formatPublishedPriceKzt, qrTokenPattern } from './qr-api';
import './live-catalog.css';

type LoadState = 'loading' | 'ready' | 'error';

function kindLabel(kind: string): string {
  if (kind === 'PHYSICAL') return 'Товар';
  if (kind === 'SERVICE') return 'Услуга';
  if (kind === 'DIGITAL') return 'Цифровой продукт';
  return 'Предложение';
}

export default function LiveCatalog() {
  const [typedSearch, setTypedSearch] = useState('');
  const [query, setQuery] = useState('');
  const [categoryCode, setCategoryCode] = useState('');
  const [categories, setCategories] = useState<LiveCategory[]>([]);
  const [page, setPage] = useState(0);
  const [state, setState] = useState<LoadState>('loading');
  const [data, setData] = useState<CatalogPage | null>(null);

  useEffect(() => {
    const id = window.setTimeout(() => {
      setPage(0);
      setQuery(typedSearch);
    }, 300);
    return () => window.clearTimeout(id);
  }, [typedSearch]);

  useEffect(() => {
    const controller = new AbortController();
    loadCategories(controller.signal).then(setCategories).catch(() => {
      if (!controller.signal.aborted) setCategories([]);
    });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setState('loading');
    setData(null);
    loadCatalog(query, categoryCode, page, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return;
        setData(result);
        setState('ready');
      })
      .catch(() => {
        if (!controller.signal.aborted) setState('error');
      });
    return () => controller.abort();
  }, [query, categoryCode, page]);

  const lastPage = data ? Math.max(0, Math.ceil(data.total / data.size) - 1) : 0;

  return <div className="live-shell">
    <header className="live-topbar"><a href="/" className="live-brand">ZAKAZ</a>
      <span>Каталог опубликованных предложений</span></header>
    <main className="live-container">
      <div className="live-intro">
        <p className="live-eyebrow">QR-FIRST MARKETPLACE</p>
        <h1>Товары и услуги от продавцов</h1>
        <p>Актуальные опубликованные объявления ZAKAZ. Цены указаны за единицу товара, если продавец их установил. Оформление заказа требует авторизации и согласования условий.</p>
      </div>
      <div className="live-filters">
        <label>Поиск
          <input aria-label="Поиск по каталогу" value={typedSearch}
            onChange={e => setTypedSearch(e.target.value)}
            maxLength={80} placeholder="Название товара или услуги"/>
        </label>
        <label>Категория
          <select aria-label="Категория каталога" value={categoryCode}
            onChange={e => { setCategoryCode(e.target.value); setPage(0); }}>
            <option value="">Все категории</option>
            {categories.map(category => <option key={category.code} value={category.code}>{category.label}</option>)}
          </select>
        </label>
      </div>
      {state === 'loading' && <div className="live-status" role="status">Загружаем опубликованные предложения…</div>}
      {state === 'error' && <div className="live-status" role="alert">
        Каталог временно недоступен. Проверьте соединение с сервером и повторите попытку.
      </div>}
      {state === 'ready' && data && <>
        <div className="live-result-count">{data.total} опубликованных предложений</div>
        {data.items.length === 0 ?
          <div className="live-status">Предложения не найдены. Попробуйте другой запрос или категорию.</div> :
          <div className="live-grid">{data.items.map(item =>
            <article className="live-card" key={item.listingId}>
              <div className="live-card-kind">{kindLabel(item.productType)}</div>
              <div className="live-card-category">{item.category}</div>
              <h2>{item.title}</h2>
              <p className="live-seller">Продавец · {item.sellerId.slice(0, 8)}</p>
              <div className="live-variants">{item.variants.map(variant =>
                <div key={variant.id} className="live-variant">
                  <span>{variant.code} · {variant.unit ?? 'Единица уточняется'}</span>
                  <strong>{formatPublishedPriceKzt(variant.priceKzt)}</strong>
                </div>)}
              </div>
              {qrTokenPattern.test(item.qrToken) &&
                <a className="live-card-link" href={'/q/' + item.qrToken}>Открыть карточку по QR</a>}
            </article>)}</div>}
        <nav className="live-pagination" aria-label="Страницы каталога">
          <button disabled={page <= 0} onClick={() => setPage(p => Math.max(0, p - 1))}>Назад</button>
          <span>Страница {page + 1} из {lastPage + 1}</span>
          <button disabled={page >= lastPage} onClick={() => setPage(p => p + 1)}>Вперёд</button>
        </nav>
      </>}
      <p className="live-disclaimer">QR-связь с продавцом не является покупкой. Наличие, доставка, налоги и итоговая цена подтверждаются отдельно.</p>
    </main>
    <footer className="live-footer">ZAKAZ · реальный каталог PostgreSQL · R2.3C</footer>
  </div>;
}
