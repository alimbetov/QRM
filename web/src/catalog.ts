export type Kind='PHYSICAL'|'SERVICE'|'DIGITAL';
export type CategoryId='phones'|'home'|'services'|'learning';
export type FeatureValue=string|number|boolean;
export interface Item {
 id:string; sellerId:string; seller:string; title:string; category:CategoryId; kind:Kind; city:string;
 price:number; image:string; icon:string; short:string; features:Record<string,FeatureValue>; rating:number;
}
export interface FeatureDef { key:string; label:string; values:string[] }
export const categoryNames:Record<CategoryId,string>={phones:'Телефоны и гаджеты',home:'Для дома',services:'Услуги',learning:'Обучение'};
export const featureDefs:Record<CategoryId,FeatureDef[]>={
 phones:[{key:'brand',label:'Бренд',values:['Samsung','Apple','Xiaomi']},{key:'memory',label:'Память',values:['128 ГБ','256 ГБ']}],
 home:[{key:'type',label:'Тип товара',values:['Техника','Мебель','Освещение']},{key:'delivery',label:'Доставка',values:['Есть','Самовывоз']}],
 services:[{key:'speciality',label:'Специализация',values:['Ремонт','Уборка','Электрика']},{key:'visit',label:'Выезд',values:['Да','Нет']}],
 learning:[{key:'format',label:'Формат',values:['Онлайн','Очно']},{key:'subject',label:'Направление',values:['Английский','Программирование']}],
};
export const items:Item[]=[
 {id:'p1',sellerId:'s1',seller:'TechStore KZ',title:'Samsung Galaxy A56 · 256 ГБ',category:'phones',kind:'PHYSICAL',city:'Алматы',price:170000,image:'phone',icon:'▣',short:'Новый · гарантия магазина',features:{brand:'Samsung',memory:'256 ГБ'},rating:4.8},
 {id:'p2',sellerId:'s2',seller:'iMarket',title:'Apple iPhone 16 · 128 ГБ',category:'phones',kind:'PHYSICAL',city:'Астана',price:369000,image:'phone2',icon:'▣',short:'Официальная гарантия',features:{brand:'Apple',memory:'128 ГБ'},rating:4.9},
 {id:'p3',sellerId:'s3',seller:'Nomad Mobile',title:'Xiaomi Redmi Note 14 · 256 ГБ',category:'phones',kind:'PHYSICAL',city:'Шымкент',price:119000,image:'phone3',icon:'▣',short:'Доставка по Казахстану',features:{brand:'Xiaomi',memory:'256 ГБ'},rating:4.6},
 {id:'p4',sellerId:'s4',seller:'Smart World',title:'Samsung Galaxy S25 · 128 ГБ',category:'phones',kind:'PHYSICAL',city:'Алматы',price:429000,image:'phone4',icon:'▣',short:'В наличии в магазине',features:{brand:'Samsung',memory:'128 ГБ'},rating:4.7},
 {id:'p5',sellerId:'s5',seller:'Gadget Point',title:'Apple iPhone 15 · 256 ГБ',category:'phones',kind:'PHYSICAL',city:'Караганда',price:339000,image:'phone5',icon:'▣',short:'Самовывоз сегодня',features:{brand:'Apple',memory:'256 ГБ'},rating:4.5},
 {id:'p6',sellerId:'s6',seller:'Digital House',title:'Xiaomi 14T · 256 ГБ',category:'phones',kind:'PHYSICAL',city:'Актобе',price:229000,image:'phone6',icon:'▣',short:'Фирменная упаковка',features:{brand:'Xiaomi',memory:'256 ГБ'},rating:4.8},
 {id:'p7',sellerId:'s7',seller:'GalaxyShop',title:'Samsung Galaxy A35 · 128 ГБ',category:'phones',kind:'PHYSICAL',city:'Алматы',price:124000,image:'phone7',icon:'▣',short:'Комплект и чехол',features:{brand:'Samsung',memory:'128 ГБ'},rating:4.6},
 {id:'p8',sellerId:'s8',seller:'iStore City',title:'Apple iPhone 16 Plus · 256 ГБ',category:'phones',kind:'PHYSICAL',city:'Астана',price:519000,image:'phone8',icon:'▣',short:'Новинка',features:{brand:'Apple',memory:'256 ГБ'},rating:4.9},
 {id:'h1',sellerId:'s9',seller:'Home Market',title:'Компактная стиральная машина',category:'home',kind:'PHYSICAL',city:'Алматы',price:158000,image:'home',icon:'▤',short:'Доставка и установка',features:{type:'Техника',delivery:'Есть'},rating:4.6},
 {id:'h2',sellerId:'s10',seller:'Cozy House',title:'Диван для гостиной',category:'home',kind:'PHYSICAL',city:'Астана',price:239000,image:'home2',icon:'▥',short:'Разные цвета ткани',features:{type:'Мебель',delivery:'Есть'},rating:4.7},
 {id:'h3',sellerId:'s11',seller:'Lumen',title:'Настольный LED-светильник',category:'home',kind:'PHYSICAL',city:'Алматы',price:18500,image:'home3',icon:'✳',short:'Три режима освещения',features:{type:'Освещение',delivery:'Самовывоз'},rating:4.5},
 {id:'s1',sellerId:'s12',seller:'FixIT',title:'Ремонт бытовой техники',category:'services',kind:'SERVICE',city:'Алматы',price:12000,image:'service',icon:'⚙',short:'Диагностика и ремонт',features:{speciality:'Ремонт',visit:'Да'},rating:4.8},
 {id:'s2',sellerId:'s13',seller:'CleanTime',title:'Генеральная уборка квартиры',category:'services',kind:'SERVICE',city:'Астана',price:26000,image:'service2',icon:'✦',short:'По предварительной записи',features:{speciality:'Уборка',visit:'Да'},rating:4.9},
 {id:'s3',sellerId:'s14',seller:'Master Electric',title:'Услуги электрика',category:'services',kind:'SERVICE',city:'Алматы',price:9000,image:'service3',icon:'ϟ',short:'Монтаж и диагностика',features:{speciality:'Электрика',visit:'Да'},rating:4.8},
 {id:'l1',sellerId:'s15',seller:'LangLab',title:'Английский язык · разговорный курс',category:'learning',kind:'SERVICE',city:'Алматы',price:35000,image:'learning',icon:'◎',short:'Онлайн-занятия',features:{format:'Онлайн',subject:'Английский'},rating:4.9},
 {id:'l2',sellerId:'s16',seller:'Code Academy',title:'Основы программирования',category:'learning',kind:'SERVICE',city:'Шымкент',price:45000,image:'learning2',icon:'⌘',short:'Практические проекты',features:{format:'Очно',subject:'Программирование'},rating:4.7},
];
export type SortKey='recommended'|'priceAsc'|'priceDesc'|'rating';
export interface CatalogQuery {category:CategoryId|null; search:string; city:string; sellerId:string; minPrice:number|null; maxPrice:number|null; featureFilters:Record<string,string>; sort:SortKey; favoriteOnly:boolean; favorites:ReadonlySet<string>}
export function queryCatalog(all:Item[],query:CatalogQuery):Item[]{
 const term=query.search.trim().toLocaleLowerCase('ru');
 const filtered=all.filter(item=>{
  if(query.category && item.category!==query.category)return false;
  if(query.favoriteOnly && !query.favorites.has(item.id))return false;
  if(query.city && item.city!==query.city)return false;
  if(query.sellerId && item.sellerId!==query.sellerId)return false;
  if(query.minPrice!==null&&item.price<query.minPrice)return false;
  if(query.maxPrice!==null&&item.price>query.maxPrice)return false;
  if(term&&!([item.title,item.seller,item.short,categoryNames[item.category]].some(v=>v.toLocaleLowerCase('ru').includes(term))))return false;
  return Object.entries(query.featureFilters).every(([key,value])=>!value||String(item.features[key])===value);
 });
 return filtered.sort((a,b)=>query.sort==='priceAsc'?a.price-b.price:query.sort==='priceDesc'?b.price-a.price:query.sort==='rating'?b.rating-a.rating:0);
}
export function paginate<T>(data:T[],page:number,size:number):{pageItems:T[];totalPages:number;total:number;page:number}{
 const total=data.length,totalPages=Math.max(1,Math.ceil(total/size)),safePage=Math.min(Math.max(page,1),totalPages);
 return {pageItems:data.slice((safePage-1)*size,safePage*size),totalPages,total,page:safePage};
}
export const formatKzt=(value:number)=>new Intl.NumberFormat('ru-RU').format(value)+' ₸';
