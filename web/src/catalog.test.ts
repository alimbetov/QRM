import { describe,it,expect } from 'vitest';
import { items, queryCatalog, paginate, type CatalogQuery } from './catalog';
const q:CatalogQuery={category:'phones',search:'',city:'',sellerId:'',minPrice:null,maxPrice:null,featureFilters:{},sort:'recommended',favoriteOnly:false,favorites:new Set()};
describe('buyer catalog / category facets',()=>{
 it('filters only one category without leaking other sellers categories',()=>{
  const data=queryCatalog(items,q);expect(data.length).toBe(8);expect(data.every(x=>x.category==='phones')).toBe(true);
 });
 it('filters by shared characteristics of category',()=>{
  const data=queryCatalog(items,{...q,featureFilters:{brand:'Samsung',memory:'128 ГБ'}});
  expect(data.map(x=>x.id)).toEqual(['p4','p7']);
 });
 it('finds offers across multiple sellers in category',()=>{
  const data=queryCatalog(items,{...q,city:'Алматы'});
  expect(new Set(data.map(x=>x.sellerId)).size).toBeGreaterThan(1);
 });
 it('filters price range and sorts ascending',()=>{
  const data=queryCatalog(items,{...q,minPrice:120000,maxPrice:350000,sort:'priceAsc'});
  expect(data.every(x=>x.price>=120000&&x.price<=350000)).toBe(true);
  expect(data.map(x=>x.price)).toEqual([...data.map(x=>x.price)].sort((a,b)=>a-b));
 });
 it('shows only chosen favorites regardless of category',()=>{
  const data=queryCatalog(items,{...q,category:null,favorites:new Set(['p1','s1']),favoriteOnly:true});
  expect(data.map(x=>x.id)).toEqual(['p1','s1']);
 });
 it('pages deterministically without duplicates',()=>{
  const all=queryCatalog(items,q);const a=paginate(all,1,6),b=paginate(all,2,6);
  expect(a.totalPages).toBe(2);expect(a.pageItems.length).toBe(6);expect(b.pageItems.length).toBe(2);
  expect(new Set([...a.pageItems,...b.pageItems].map(x=>x.id)).size).toBe(8);
 });
 it('clamps out of range page',()=>{expect(paginate(items,999,6).page).toBe(3);});
});
