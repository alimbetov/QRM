import {publicQrApiBase} from './qr-api';
import {uuid} from './seller-api';

/** Trusted server catalog option, not an arbitrary seller-typed UUID. */
export type SellerCanonicalProduct = {
  id:string;name:string;categoryCode:string;categoryLabel:string;
};
const searchPattern=/^[\p{L}\p{N} .-]{2,80}$/u;

export function validProductSearch(query:string):boolean {
  const trimmed=query.trim();
  return trimmed.length>=2&&trimmed.length<=80&&searchPattern.test(trimmed);
}

/**
 * Never search by unscoped PUBLIC feed: a canonical product may have no
 * published listing yet. The Backend verifies catalog:write and real seller
 * membership before returning the bounded result.
 */
export async function searchSellerCanonicalProducts(sellerId:string,token:string,
  query:string,signal?:AbortSignal):Promise<SellerCanonicalProduct[]> {
  if(!uuid.test(sellerId)||!token||token.trim()!==token||/[\r\n]/.test(token))
    throw new Error('Verified seller identity required');
  if(!validProductSearch(query))throw new Error('Invalid canonical product query');
  const url=publicQrApiBase()+'/api/v1/sellers/'+sellerId+
    '/catalog-products?query='+encodeURIComponent(query.trim());
  const response=await fetch(url,{
    method:'GET',credentials:'omit',cache:'no-store',signal,
    headers:{Authorization:'Bearer '+token,Accept:'application/json'}
  });
  if(!response.ok)throw new Error('Seller product search HTTP '+response.status);
  const data:unknown=await response.json();
  if(!Array.isArray(data)||data.length>20||data.some((item:unknown)=>
      !item||typeof item!=='object'||
      !('id' in item)||typeof item.id!=='string'||!uuid.test(item.id)||
      !('name' in item)||typeof item.name!=='string'||!item.name.trim()||
      !('categoryCode' in item)||typeof item.categoryCode!=='string'||
      !/^[0-9]{20}$/.test(item.categoryCode)||
      !('categoryLabel' in item)||typeof item.categoryLabel!=='string'))
    throw new Error('Invalid canonical product search response');
  return data as SellerCanonicalProduct[];
}
