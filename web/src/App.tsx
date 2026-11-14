import { useEffect, useMemo, useState } from 'react';
import { categoryNames, featureDefs, formatKzt, items, paginate, queryCatalog, type CategoryId, type SortKey } from './catalog';
import './styles.css';
type Tab='catalog'|'favorites';
const STORAGE_KEY='zakaz-buyer-demo-favorites-v1';
const categoryKeys=Object.keys(categoryNames) as CategoryId[];
function initialFavorites():string[]{
 try {const value=JSON.parse(localStorage.getItem(STORAGE_KEY)||'[]');return Array.isArray(value)?value.filter((x):x is string=>typeof x==='string'):[];} catch{return [];}
}
export default function App(){
 const [tab,setTab]=useState<Tab>('catalog');
 const [favorites,setFavorites]=useState<string[]>(initialFavorites);
 const [category,setCategory]=useState<CategoryId|null>('phones');
 const [search,setSearch]=useState('');
 const [city,setCity]=useState('');
 const [sellerId,setSellerId]=useState('');
 const [minPrice,setMinPrice]=useState('');
 const [maxPrice,setMaxPrice]=useState('');
 const [features,setFeatures]=useState<Record<string,string>>({});
 const [sort,setSort]=useState<SortKey>('recommended');
 const [page,setPage]=useState(1);
 const [showMobileFilters,setShowMobileFilters]=useState(false);
 const [detail,setDetail]=useState<string|null>(null);
 useEffect(()=>{localStorage.setItem(STORAGE_KEY,JSON.stringify(favorites));},[favorites]);
 const filtered=useMemo(()=>queryCatalog(items,{
  category:tab==='favorites'?null:category,search,city,sellerId,
  minPrice:minPrice.trim()===''?null:Number(minPrice),maxPrice:maxPrice.trim()===''?null:Number(maxPrice),
  featureFilters:tab==='favorites'?{}:features,sort,favoriteOnly:tab==='favorites',favorites:new Set(favorites)
 }),[tab,category,search,city,sellerId,minPrice,maxPrice,features,sort,favorites]);
 const result=paginate(filtered,page,6);
 const cities=[...new Set(items.map(i=>i.city))].sort((a,b)=>a.localeCompare(b,'ru'));
 const sellers=[...new Map(items.filter(i=>tab==='favorites'||!category||i.category===category).map(i=>[i.sellerId,{id:i.sellerId,name:i.seller}])).values()].sort((a,b)=>a.name.localeCompare(b.name));
 const activeDetail=items.find(i=>i.id===detail);
 function resetPage(){setPage(1);}
 function chooseCategory(value:CategoryId|null){setCategory(value);setFeatures({});setSellerId('');resetPage();}
 function toggleFavorite(id:string){setFavorites(old=>old.includes(id)?old.filter(v=>v!==id):[...old,id]);}
 function resetFilters(){setSearch('');setCity('');setSellerId('');setMinPrice('');setMaxPrice('');setFeatures({});resetPage();}
 const filterPanel=<div className="filter-content">
  <div className="panel-heading"><h3>Фильтры</h3><button type="button" className="text-btn" onClick={resetFilters}>Сбросить</button></div>
  <label className="control-label">Город
   <select aria-label="Город" value={city} onChange={e=>{setCity(e.target.value);resetPage();}}><option value="">Все города</option>{cities.map(c=><option key={c} value={c}>{c}</option>)}</select>
  </label>
  <label className="control-label">Продавец
   <select aria-label="Продавец" value={sellerId} onChange={e=>{setSellerId(e.target.value);resetPage();}}><option value="">Все продавцы</option>{sellers.map(s=><option key={s.id} value={s.id}>{s.name}</option>)}</select>
  </label>
  <div className="control-label">Цена, ₸<div className="range-fields">
   <input aria-label="Цена от" type="number" min="0" placeholder="От" value={minPrice} onChange={e=>{setMinPrice(e.target.value);resetPage();}}/>
   <input aria-label="Цена до" type="number" min="0" placeholder="До" value={maxPrice} onChange={e=>{setMaxPrice(e.target.value);resetPage();}}/>
  </div></div>
  {tab==='catalog'&&category&&featureDefs[category].map(f=><label className="control-label" key={f.key}>{f.label}
   <select value={features[f.key]||''} onChange={e=>{setFeatures(old=>({...old,[f.key]:e.target.value}));resetPage();}}><option value="">Любой</option>{f.values.map(v=><option key={v} value={v}>{v}</option>)}</select>
  </label>)}
  <div className="filter-note"><span>ⓘ</span> Характеристики зависят от категории. На реальном каталоге они будут поступать из справочника Backend.</div>
 </div>;
 return <div className="app-shell">
  <header className="topbar"><div className="topbar-inner">
   <a className="brand" href="/" aria-label="ZAKAZ главная"><span className="brand-symbol">Z</span><span>ZAKAZ</span><small>покупателю</small></a>
   <div className="search-wrap"><span aria-hidden="true">⌕</span><input aria-label="Поиск по товарам и услугам" value={search} onChange={e=>{setSearch(e.target.value);resetPage();}} placeholder="Найти товар, услугу или продавца"/></div>
   <nav className="header-actions"><button className={tab==='catalog'?'nav-btn active':'nav-btn'} onClick={()=>{setTab('catalog');resetPage();}}>Каталог</button><button className={tab==='favorites'?'nav-btn active':'nav-btn'} onClick={()=>{setTab('favorites');resetPage();}}>♡ Избранное <b>{favorites.length}</b></button></nav>
  </div></header>
  <main className="content">
   <div className="breadcrumbs">ZAKAZ <span>›</span> Покупателю <span>›</span> {tab==='favorites'?'Избранное':category?categoryNames[category]:'Каталог'}</div>
   <section className="hero"><div><div className="eyebrow">ЕДИНЫЙ МАРКЕТПЛЕЙС</div><h1>{tab==='favorites'?'Ваши сохранённые предложения':'Находите то, что вам нужно'}</h1><p>{tab==='favorites'?'Сохраняйте интересные товары и услуги, сравнивайте продавцов и возвращайтесь к ним позже.':'Товары и услуги от разных продавцов. Сравнивайте цены, характеристики и города — всё в одном месте.'}</p></div><div className="hero-art" aria-hidden="true"><span>▣</span><span>✳</span><span>⌂</span></div></section>
   <div className="tabs"><button className={tab==='catalog'?'tab current':'tab'} onClick={()=>{setTab('catalog');resetPage();}}>Все предложения</button><button className={tab==='favorites'?'tab current':'tab'} onClick={()=>{setTab('favorites');resetPage();}}>♡ Моё избранное <span>{favorites.length}</span></button></div>
   {tab==='catalog'&&<div className="categories" aria-label="Категории">
    <button className={category===null?'category active':'category'} onClick={()=>chooseCategory(null)}>Все категории</button>
    {categoryKeys.map(id=><button key={id} className={category===id?'category active':'category'} onClick={()=>chooseCategory(id)}>{categoryNames[id]}</button>)}
   </div>}
   <div className="layout"><aside className="sidebar">{filterPanel}</aside><section className="results">
    <div className="results-head"><div><h2>{tab==='favorites'?'Сохранённое':category?categoryNames[category]:'Каталог товаров и услуг'}</h2><p>{result.total} предложений от разных продавцов</p></div><div className="sort-row"><button className="mobile-filter-btn" onClick={()=>setShowMobileFilters(true)}>☷ Фильтры</button><label>Сортировка <select aria-label="Сортировка" value={sort} onChange={e=>{setSort(e.target.value as SortKey);resetPage();}}><option value="recommended">Рекомендуемые</option><option value="priceAsc">Сначала дешевле</option><option value="priceDesc">Сначала дороже</option><option value="rating">По рейтингу</option></select></label></div></div>
    {result.total===0?<div className="empty-state"><div>⌕</div><h3>Предложения не найдены</h3><p>Измените фильтры или выберите другую категорию.</p><button onClick={resetFilters}>Сбросить фильтры</button></div>:<div className="product-grid">{result.pageItems.map(item=><article className="product-card" key={item.id}>
     <button className="favorite-toggle" aria-label={favorites.includes(item.id)?'Убрать из избранного':'В избранное'} aria-pressed={favorites.includes(item.id)} onClick={()=>toggleFavorite(item.id)}>{favorites.includes(item.id)?'♥':'♡'}</button>
     <div className={'product-image '+item.image}><span>{item.icon}</span><small>{item.kind==='SERVICE'?'УСЛУГА':item.kind==='DIGITAL'?'ЦИФРОВОЙ':'ТОВАР'}</small></div>
     <div className="product-body"><div className="seller">{item.seller} <span>★ {item.rating.toFixed(1)}</span></div><h3>{item.title}</h3><div className="description">{item.short}</div><div className="city">⌖ {item.city}</div><div className="product-bottom"><strong>{formatKzt(item.price)}</strong><button onClick={()=>setDetail(item.id)}>Подробнее →</button></div></div>
    </article>)}</div>}
    {result.totalPages>1&&<nav className="pagination" aria-label="Страницы каталога"><button disabled={result.page===1} onClick={()=>setPage(p=>Math.max(1,p-1))}>←</button>{Array.from({length:result.totalPages},(_,i)=>i+1).map(p=><button key={p} aria-current={p===result.page?'page':undefined} className={result.page===p?'current':''} onClick={()=>setPage(p)}>{p}</button>)}<button disabled={result.page===result.totalPages} onClick={()=>setPage(p=>Math.min(result.totalPages,p+1))}>→</button></nav>}
    <p className="prototype-note">Демо-каталог: данные и цены условные. Избранное хранится только в этом браузере, серверная синхронизация ещё не подключена. Заказы не создаются.</p>
   </section></div>
  </main>
  <footer><span>ZAKAZ · Buyer Web</span><span>Прототип интерфейса · Казахстан</span></footer>
  {showMobileFilters&&<div className="modal-shade" role="presentation" onMouseDown={()=>setShowMobileFilters(false)}><div className="mobile-drawer" role="dialog" aria-label="Фильтры каталога" onMouseDown={e=>e.stopPropagation()}><button className="drawer-close" onClick={()=>setShowMobileFilters(false)} aria-label="Закрыть">✕</button>{filterPanel}<button className="apply-btn" onClick={()=>setShowMobileFilters(false)}>Показать {result.total} предложений</button></div></div>}
  {activeDetail&&<div className="modal-shade" role="presentation" onMouseDown={()=>setDetail(null)}><div className="detail-modal" role="dialog" aria-modal="true" aria-label={activeDetail.title} onMouseDown={e=>e.stopPropagation()}><button aria-label="Закрыть" className="drawer-close" onClick={()=>setDetail(null)}>✕</button><div className={'detail-image '+activeDetail.image}>{activeDetail.icon}</div><div><span className="eyebrow">{categoryNames[activeDetail.category]}</span><h2>{activeDetail.title}</h2><p>{activeDetail.short}</p><p>Продавец: <b>{activeDetail.seller}</b> · {activeDetail.city}</p><div className="details-list">{Object.entries(activeDetail.features).map(([k,v])=><div key={k}><span>{featureDefs[activeDetail.category].find(f=>f.key===k)?.label||k}</span><b>{String(v)}</b></div>)}</div><h2>{formatKzt(activeDetail.price)}</h2><button className="apply-btn" onClick={()=>toggleFavorite(activeDetail.id)}>{favorites.includes(activeDetail.id)?'♥ Убрать из избранного':'♡ Сохранить в избранное'}</button><p className="prototype-note">Оформление заказа и предложение цены будут доступны после подключения авторизованного API.</p></div></div></div>}
 </div>;
}
