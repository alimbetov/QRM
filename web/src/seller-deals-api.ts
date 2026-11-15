import {publicQrApiBase} from './qr-api';
import {uuid} from './seller-api';

export type SellerAccount={sellerId:string;role:'SELLER_ADMIN'};
export type SellerOrderLine={skuId:string;quantity:string;unit:string;lineTotal:string};
export type SellerOrder={
  orderId:string;publicNumber:string;status:string;totalAmount:string;
  deliveryFee:string;currency:string;createdAt:string;lines:SellerOrderLine[];
};
export type SellerOrdersPage={page:number;size:number;total:number;items:SellerOrder[]};
export type SellerLead={
  leadId:string;listingId:string;phone:string;receivedAt:string;
  status:'NEW'|'CONTACTED'|'DECLINED'
};
export type SellerInvitation={invitationId:string;demandId:string;deliveredAt:string};
export type LeadDisposition='CONTACTED'|'DECLINED';
const money=/^\d{1,17}\.\d{2}$/;
const qty=/^\d{1,16}(?:\.\d{1,3})?$/;
function sellerPath(id:string){
  if(!uuid.test(id))throw new Error('Invalid seller ID');
  return '/api/v1/sellers/'+id;
}
function bearer(token:string){
  if(!token||token.trim()!==token||/[\r\n]/.test(token))
    throw new Error('Verified seller token required');
  return {Authorization:'Bearer '+token,Accept:'application/json'};
}
async function get<T>(path:string,token:string):Promise<T>{
  const response=await fetch(publicQrApiBase()+path,{
    method:'GET',headers:bearer(token),credentials:'omit',cache:'no-store'
  });
  if(!response.ok)throw new Error('Seller HTTP '+response.status);
  return response.json() as Promise<T>;
}
async function mutate(path:string,token:string,method:'POST'|'PUT',body?:object){
  const response=await fetch(publicQrApiBase()+path,{
    method,credentials:'omit',cache:'no-store',
    headers:{...bearer(token),...(body?{'Content-Type':'application/json'}:{})},
    ...(body?{body:JSON.stringify(body)}:{})
  });
  if(response.status!==204)throw new Error('Seller HTTP '+response.status);
}
export async function sellerAccounts(token:string):Promise<SellerAccount[]> {
  const accounts=await get<SellerAccount[]>('/api/v1/sellers/me',token);
  if(!Array.isArray(accounts)||accounts.some(s=>!s||!uuid.test(s.sellerId)||
    s.role!=='SELLER_ADMIN'))throw new Error('Invalid seller membership response');
  return accounts;
}
export async function sellerOrders(id:string,token:string,page=0,
    status='ALL'):Promise<SellerOrdersPage>{
  if(!Number.isInteger(page)||page<0||page>1000 ||
     !['ALL','PLACED','CONFIRMED','CANCELLED_BY_BUYER',
       'CANCELLED_BY_SELLER'].includes(status))
    throw new Error('Invalid order filter');
  const result=await get<SellerOrdersPage>(sellerPath(id)+'/orders?page='+page+
    '&size=20&status='+encodeURIComponent(status),token);
  if(!result||result.page!==page||result.size!==20||
     !Number.isInteger(result.total)||result.total<0||
     !Array.isArray(result.items)||
     result.items.some(o=>!o||!uuid.test(o.orderId)||
        !money.test(o.totalAmount)||!money.test(o.deliveryFee)||
        !Array.isArray(o.lines) || !o.lines.every(l=>
          uuid.test(l.skuId)&&typeof l.quantity==='string'&&qty.test(l.quantity)&&
          typeof l.lineTotal==='string'&&money.test(l.lineTotal))))
    throw new Error('Invalid seller order response');
  return result;
}
export async function changeSellerOrder(id:string,orderId:string,
  token:string,action:'confirm'|'cancel'):Promise<void>{
  if(!uuid.test(orderId))throw new Error('Invalid order ID');
  await mutate(sellerPath(id)+'/orders/'+orderId+'/'+action,token,'POST');
}
export async function sellerLeads(id:string,token:string,page=0):Promise<SellerLead[]>{
  if(!Number.isInteger(page)||page<0||page>1000)
    throw new Error('Invalid leads page');
  const leads=await get<SellerLead[]>(sellerPath(id)+'/leads?page='+page+'&size=20',token);
  if(!Array.isArray(leads)||leads.some(l=>!l||!uuid.test(l.leadId)||
    !uuid.test(l.listingId)||typeof l.phone!=='string'||
    !['NEW','CONTACTED','DECLINED'].includes(l.status)))
    throw new Error('Invalid seller lead response');
  return leads;
}
export async function markSellerLead(id:string,leadId:string,token:string,
  status:LeadDisposition):Promise<void> {
  if(!uuid.test(leadId)||!['CONTACTED','DECLINED'].includes(status))
    throw new Error('Invalid lead transition');
  await mutate(sellerPath(id)+'/leads/'+leadId+'/status',token,'PUT',{status});
}
export async function sellerInvitations(id:string,token:string):Promise<SellerInvitation[]> {
  const list=await get<SellerInvitation[]>(sellerPath(id)+'/invitations?limit=24',token);
  if(!Array.isArray(list)||list.some(x=>!x||!uuid.test(x.invitationId)||
    !uuid.test(x.demandId)||typeof x.deliveredAt!=='string'))
    throw new Error('Invalid seller inbox response');
  return list;
}
