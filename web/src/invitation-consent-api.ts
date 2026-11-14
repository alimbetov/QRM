import {publicQrApiBase} from './qr-api';
import {uuid} from './seller-api';

/** Consent is separate from enrollment/profile and must NEVER be auto-enabled. */
function bearer(token:string){
  if(!token||token!==token.trim()||/[\r\n]/.test(token))
    throw new Error('Authenticated consent session required');
  return {Authorization:'Bearer '+token,Accept:'application/json'};
}
function buyerPath(demandId:string){
  if(!uuid.test(demandId))throw new Error('Invalid demand');
  return '/api/v1/buyers/demands/'+demandId+'/invitation-sharing';
}
function sellerPath(sellerId:string){
  if(!uuid.test(sellerId))throw new Error('Invalid seller');
  return '/api/v1/sellers/'+sellerId+'/invitation-preference';
}
async function read(path:string,token:string):Promise<boolean>{
  const response=await fetch(publicQrApiBase()+path,{
    method:'GET',headers:bearer(token),cache:'no-store',credentials:'omit'
  });
  if(!response.ok)throw new Error('Consent status HTTP '+response.status);
  const result=await response.json() as {enabled?:unknown};
  if(typeof result?.enabled!=='boolean')throw new Error('Invalid consent state');
  return result.enabled;
}
async function update(path:string,token:string,enabled:boolean):Promise<void>{
  const response=await fetch(publicQrApiBase()+path,{
    method:'PUT',headers:{...bearer(token),'Content-Type':'application/json'},
    credentials:'omit',cache:'no-store',body:JSON.stringify({enabled})
  });
  if(response.status!==204)throw new Error('Consent update HTTP '+response.status);
}
export const loadDemandSharing=(demandId:string,token:string)=>
  read(buyerPath(demandId),token);
export const setDemandSharing=(demandId:string,token:string,enabled:boolean)=>
  update(buyerPath(demandId),token,enabled);
export const loadSellerPreference=(sellerId:string,token:string)=>
  read(sellerPath(sellerId),token);
export const setSellerPreference=(sellerId:string,token:string,enabled:boolean)=>
  update(sellerPath(sellerId),token,enabled);
