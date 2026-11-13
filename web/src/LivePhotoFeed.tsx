import {useCallback, useEffect, useRef, useState} from 'react';
import {loadCategories, loadPhotoFeed, photoMediaUrl,
  type LiveCategory, type PhotoFeedItem} from './catalog-api';
import {formatPublishedPriceKzt, qrTokenPattern} from './qr-api';
import './photo-feed.css';

const STATE_KEY='zakaz.search.photo-feed.v1';

function detailPath(item: PhotoFeedItem): string | null {
  return qrTokenPattern.test(item.qrToken) ? '/q/' + item.qrToken : null;
}

function lowestPrice(item: PhotoFeedItem): string | null {
  const valid=item.variants.map(v => v.priceKzt)
    .filter((price): price is string => price !== null && /^\d+\.\d{2}$/.test(price));
  if (valid.length===0) return null;
  return valid.reduce((min, value) =>
    BigInt(value.replace('.','')) < BigInt(min.replace('.','')) ? value : min);
}

function PhotoCard({item}: {item: PhotoFeedItem}) {
  const detail=detailPath(item);
  const image=photoMediaUrl(item.coverUrl);
  const price=lowestPrice(item);
  function openDetail() {
    if (detail) window.location.assign(detail);
  }
  return <article className="photo-card" aria-label={item.title}>
    <div className="photo-cover" role={detail ? 'link' : undefined}
      tabIndex={detail ? 0 : undefined}
      aria-label={detail ? 'Открыть ' + item.title : undefined}
      onDoubleClick={openDetail}
      onKeyDown={event => {if(event.key==='Enter') openDetail();}}>
      {image ? <img loading="lazy" src={image} alt={item.title}/> :
        <div className="photo-fallback" aria-label="Фото пока нет">
          <span aria-hidden="true">▧</span>Фото пока нет
        </div>}
      {item.imageCount > 1 && <span className="photo-count">{item.imageCount} фото</span>}
    </div>
    <div className="photo-meta">
      <span className="photo-category">{item.category}</span>
      <h2>{item.title}</h2>
      <div className="photo-price">{price ? 'от ' + formatPublishedPriceKzt(price) : 'Цена уточняется'}</div>
      <div className="photo-actions">
        {detail && <a href={detail}>Подробнее</a>}
        {item.productType==='PHYSICAL' &&
          item.variants.some(v => v.unit && v.unit!=='LICENSE' && v.unit!=='SERVICE') &&
          <a className="photo-quote" href={'/buy?productId='+
            encodeURIComponent(item.productId)+'&unit='+
            encodeURIComponent(item.variants.find(v => v.unit)?.unit || 'PCS')}>
            Запросить предложения
          </a>}
      </div>
    </div>
  </article>;
}

export default function LivePhotoFeed() {
  const initial=new URLSearchParams(window.location.search);
  const [typed,setTyped]=useState(initial.get('q') || '');
  const [query,setQuery]=useState(initial.get('q') || '');
  const [category,setCategory]=useState(initial.get('categoryCode') || '');
  const [categories,setCategories]=useState<LiveCategory[]>([]);
  const [items,setItems]=useState<PhotoFeedItem[]>([]);
  const [cursor,setCursor]=useState<string | null>(null);
  const [hasMore,setHasMore]=useState(true);
  const [loading,setLoading]=useState(true);
  const [loadingMore,setLoadingMore]=useState(false);
  const [error,setError]=useState('');
  const [retryNonce,setRetryNonce]=useState(0);
  const sentinel=useRef<HTMLDivElement>(null);
  const pending=useRef<AbortController | null>(null);
  const restore=useRef<number | null>(null);

  useEffect(()=>{
    const timeout=window.setTimeout(()=>setQuery(typed.trim()),300);
    return ()=>window.clearTimeout(timeout);
  },[typed]);

  useEffect(()=>{
    const abort=new AbortController();
    loadCategories(abort.signal).then(setCategories).catch(()=>{
      if(!abort.signal.aborted) setCategories([]);
    });
    return ()=>abort.abort();
  },[]);

  useEffect(()=>{
    const params=new URLSearchParams();
    if(query) params.set('q',query);
    if(category) params.set('categoryCode',category);
    const next=window.location.pathname+(params.toString()?'?'+params.toString():'');
    window.history.replaceState(null,'',next);
    pending.current?.abort();
    const controller=new AbortController();
    pending.current=controller;
    setItems([]);
    setCursor(null);
    setHasMore(true);
    setLoading(true);
    setLoadingMore(false);
    setError('');
    let saved: {q:string;category:string;y:number} | null=null;
    try {saved=JSON.parse(sessionStorage.getItem(STATE_KEY) || 'null');} catch {saved=null;}
    restore.current=saved?.q===query && saved.category===category ? saved.y : null;
    loadPhotoFeed(query,category,null,controller.signal)
      .then(result=>{
        if(controller.signal.aborted) return;
        setItems(result.items);
        setCursor(result.nextCursor);
        setHasMore(result.hasMore);
        setLoading(false);
      }).catch(()=>{
        if(!controller.signal.aborted) {setError('Не удалось загрузить каталог.');setLoading(false);}
      }).finally(()=>{
        if(pending.current===controller) pending.current=null;
      });
    return ()=>controller.abort();
  },[query,category,retryNonce]);

  useEffect(()=>{
    if(loading || restore.current===null) return;
    const y=restore.current;
    restore.current=null;
    window.requestAnimationFrame(()=>window.scrollTo({top:y,behavior:'instant'}));
  },[loading,items.length]);

  useEffect(()=>{
    const save=()=>sessionStorage.setItem(STATE_KEY,JSON.stringify({
      q:query,category,y:window.scrollY
    }));
    window.addEventListener('pagehide',save);
    return ()=>window.removeEventListener('pagehide',save);
  },[query,category]);

  const nextPage=useCallback(()=>{
    if(loading || loadingMore || !hasMore || !cursor || pending.current?.signal.aborted===false)
      return;
    const controller=new AbortController();
    pending.current=controller;
    setLoadingMore(true);
    loadPhotoFeed(query,category,cursor,controller.signal).then(result=>{
      if(controller.signal.aborted) return;
      setItems(previous=>{
        const seen=new Set(previous.map(x=>x.listingId));
        return [...previous,...result.items.filter(x=>!seen.has(x.listingId))];
      });
      setCursor(result.nextCursor);
      setHasMore(result.hasMore);
      setError('');
    }).catch(()=>{
      if(!controller.signal.aborted) setError('Следующая страница недоступна.');
    }).finally(()=>{
      if(!controller.signal.aborted) {
        setLoadingMore(false);
        pending.current=null;
      }
    });
  },[query,category,cursor,loading,loadingMore,hasMore]);

  useEffect(()=>{
    if(loading || loadingMore || !hasMore || !sentinel.current) return;
    const observer=new IntersectionObserver(entries=>{
      if(entries.some(e=>e.isIntersecting)) nextPage();
    },{rootMargin:'500px'});
    observer.observe(sentinel.current);
    return ()=>observer.disconnect();
  },[loading,loadingMore,hasMore,nextPage]);

  return <div className="photo-shell">
    <header className="photo-topbar"><a href="/">ZAKAZ</a><span>Фотокаталог товаров и услуг</span></header>
    <main className="photo-container">
      <div className="photo-intro">
        <span>QR-FIRST MARKETPLACE</span>
        <h1>Найдите нужное по названию или категории</h1>
        <p>Опубликованные предложения продавцов. Указанная цена — за единицу и не включает непроверенные условия доставки.</p>
      </div>
      <div className="photo-filters">
        <label>Поиск<input aria-label="Поиск по фотокаталогу"
          placeholder="Например, болгарка или диван" maxLength={80}
          value={typed} onChange={e=>setTyped(e.target.value)}/></label>
        <label>Категория<select aria-label="Категория фотокаталога"
          value={category} onChange={e=>setCategory(e.target.value)}>
          <option value="">Все категории</option>
          {categories.map(c=><option key={c.code} value={c.code}>{c.label}</option>)}
        </select></label>
      </div>
      {loading && <div className="photo-status" role="status">Загружаем предложения…</div>}
      {error && <div className="photo-status" role="alert">{error}
        {!loading && <button type="button" onClick={()=>{
          if(items.length===0) setRetryNonce(n=>n+1);
          else nextPage();
        }}>Повторить</button>}
      </div>}
      {!loading && items.length===0 && !error &&
        <div className="photo-status">Ничего не найдено. Попробуйте другой запрос или категорию.</div>}
      <section className="photo-grid" aria-label="Результаты поиска">
        {items.map(item=><PhotoCard key={item.listingId} item={item}/>)}
      </section>
      <div ref={sentinel} className="photo-sentinel" aria-hidden="true"/>
      {loadingMore && <div role="status" className="photo-status">Загружаем следующую страницу…</div>}
      {!loading && hasMore && <button type="button" className="photo-more"
        disabled={loadingMore} onClick={nextPage}>Показать ещё</button>}
      {!loading && !hasMore && items.length>0 && <div className="photo-finished">Все предложения загружены</div>}
      <p className="photo-disclaimer">Открытие карточки не создаёт заказ. Для покупки необходимо отдельно согласовать предложение с продавцом.</p>
    </main>
  </div>;
}
