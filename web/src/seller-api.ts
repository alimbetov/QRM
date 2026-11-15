import {publicQrApiBase} from './qr-api';

export type WorkspaceSku = {
  skuId:string; code:string; unit:string|null;
  trackingMode:string; priceKzt:string|null;
};
export type WorkspaceListing = {
  listingId:string; productId:string; title:string;
  categoryCode:string; status:'DRAFT'|'PUBLIC'|'ARCHIVED'|'UNLISTED';
  qrToken:string|null; variants:WorkspaceSku[];
};
export type WorkspacePage = {
  page:number; size:number; total:number; items:WorkspaceListing[];
};
export type DraftInput = {
  productId:string;skuCode:string;unit:string;
  trackingMode:'TRACKED';publicPriceKzt:string|null;
};
export const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const physicalUnits=new Set(['PCS','KG','G','L','ML','M','M2','M3','HOUR']);
const amount=/^(?:0|[1-9][0-9]{0,16})(?:\.[0-9]{1,2})?$/;

export function validPrice(value:string):boolean {
  return amount.test(value) && !/^0(?:\.0{1,2})?$/.test(value);
}
export function validateDraft(input:DraftInput):void {
  if(!uuid.test(input.productId)||input.skuCode.trim().length<1||
      input.skuCode.length>80 || !physicalUnits.has(input.unit) ||
      input.trackingMode!=='TRACKED' ||
      (input.publicPriceKzt!==null && !validPrice(input.publicPriceKzt)))
    throw new Error('Invalid seller draft');
}
function sellerPath(sellerId:string):string {
  if(!uuid.test(sellerId)) throw new Error('Invalid seller identifier');
  return '/api/v1/sellers/'+sellerId;
}
function listingPath(sellerId:string,listingId:string):string {
  if(!uuid.test(listingId)) throw new Error('Invalid listing identifier');
  return sellerPath(sellerId)+'/listings/'+listingId;
}
function auth(token:string):Record<string,string> {
  if(!token||token.trim()!==token||/[\r\n]/.test(token))
    throw new Error('Verified seller token required');
  return {Authorization:'Bearer '+token,Accept:'application/json'};
}
async function requestJson<T>(path:string,token:string,method='GET',body?:unknown):Promise<T> {
  const response=await fetch(publicQrApiBase()+path,{
    method,credentials:'omit',cache:'no-store',
    headers:{...auth(token),...(body===undefined?{}:{'Content-Type':'application/json'})},
    ...(body===undefined?{}:{body:JSON.stringify(body)})
  });
  if(!response.ok)throw new Error('Seller API HTTP '+response.status);
  if(response.status===204)return undefined as T;
  return response.json() as Promise<T>;
}
function checkListing(item:WorkspaceListing):void {
  if(!item||!uuid.test(item.listingId)||!uuid.test(item.productId)||
    typeof item.title!=='string'||!Array.isArray(item.variants)||
    !['DRAFT','PUBLIC','ARCHIVED','UNLISTED'].includes(item.status))
    throw new Error('Invalid seller listing response');
}
export async function listSellerListings(sellerId:string,token:string,page=0):Promise<WorkspacePage> {
  if(!Number.isInteger(page)||page<0||page>10000)throw new Error('Invalid page');
  const result=await requestJson<WorkspacePage>(
    sellerPath(sellerId)+'/listings?page='+page+'&size=12',token);
  if(!result||!Array.isArray(result.items)||!Number.isInteger(result.page)||
    result.page!==page||!Number.isInteger(result.total)||result.total<0)
    throw new Error('Invalid seller listing page');
  result.items.forEach(checkListing);
  return result;
}
export async function createSellerDraft(sellerId:string,token:string,
                                         input:DraftInput):Promise<WorkspaceListing> {
  validateDraft(input);
  const item=await requestJson<WorkspaceListing>(
    sellerPath(sellerId)+'/listings',token,'POST',input);
  checkListing(item);
  if(item.status!=='DRAFT')throw new Error('Draft creation did not return DRAFT');
  return item;
}
export async function setSellerSkuPrice(sellerId:string,listingId:string,
    skuId:string,token:string,price:string):Promise<void> {
  if(!uuid.test(skuId)||!validPrice(price))throw new Error('Invalid SKU price');
  await requestJson<void>(listingPath(sellerId,listingId)+'/skus/'+skuId+'/price',
                          token,'PUT',{priceKzt:price});
}
export async function publishSellerListing(sellerId:string,listingId:string,
                                           token:string):Promise<void> {
  await requestJson<void>(listingPath(sellerId,listingId)+'/publish',token,'POST');
}
export async function uploadSellerListingImage(sellerId:string,listingId:string,
    token:string,file:File,cover:boolean):Promise<string> {
  if(file.size<=0||file.size>8*1024*1024||
    !['image/png','image/jpeg'].includes(file.type))
    throw new Error('Only a PNG/JPEG image up to 8 MiB is allowed');
  const form=new FormData();
  form.append('image',file);
  form.append('cover',String(cover));
  const response=await fetch(publicQrApiBase()+listingPath(sellerId,listingId)+'/media',{
    method:'POST',headers:auth(token),credentials:'omit',cache:'no-store',body:form
  });
  if(response.status!==201)throw new Error('Seller media HTTP '+response.status);
  const body=await response.json() as {mediaId?:string};
  if(!body||!body.mediaId||!uuid.test(body.mediaId))
    throw new Error('Invalid media upload response');
  return body.mediaId;
}
export async function downloadSellerQr(sellerId:string,listingId:string,
                                       token:string):Promise<Blob> {
  const response=await fetch(publicQrApiBase()+listingPath(sellerId,listingId)+'/qr.png',{
    headers:{...auth(token),Accept:'image/png'},credentials:'omit',cache:'no-store'
  });
  if(!response.ok)throw new Error('Seller QR HTTP '+response.status);
  if(!response.headers.get('content-type')?.startsWith('image/png'))
    throw new Error('Invalid QR content type');
  return response.blob();
}
