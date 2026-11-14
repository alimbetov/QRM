import {publicQrApiBase} from './qr-api';
import {uuid} from './seller-api';

export type MatchingSku={skuId:string;code:string;priceKzt:string|null;
  availableByOrigin:Record<string,string>};
export type DemandLine={productId:string;productName:string;hccCode:string;
  unit:string;quantity:string;maxUnitPrice:string|null;skus:MatchingSku[]};
export type BidOrigin={originLocationId:string;city:string;regionCode:string;
  address:string;type:string;pickup:boolean;delivery:boolean};
export type InvitationQuoteContext={invitationId:string;demandId:string;
  destinationCity:string;expiresAt:string;nextRevision:number;
  lines:DemandLine[];origins:BidOrigin[]};
export type BidInput={revision:number;expiresAt:string;
  method:'PICKUP'|'DELIVERY';originLocationId:string;deliveryFee:string;
  lines:{productId:string;skuId:string;quantity:string;unitPrice:string}[]};

export const priceKzt=/^(?:0|[1-9]\d{0,16})(?:\.\d{1,2})?$/;
export const demandQuantity=/^(?:0|[1-9]\d{0,15})(?:\.\d{1,3})?$/;
export function decimalMilli(value:string):bigint{
  if(!demandQuantity.test(value))throw new Error('Invalid quantity');
  const [whole,fraction='']=value.split('.');
  return BigInt(whole)*1000n+BigInt(fraction.padEnd(3,'0'));
}
export function stockCovers(available:string,quantity:string):boolean{
  return decimalMilli(available)>=decimalMilli(quantity);
}
function auth(token:string){
  if(!token||token!==token.trim()||/[\r\n]/.test(token))
    throw new Error('Seller token required');
  return {Authorization:'Bearer '+token,Accept:'application/json'};
}
function invitationPath(sellerId:string,invitationId:string):string{
  if(!uuid.test(sellerId)||!uuid.test(invitationId))
    throw new Error('Invalid invitation identifier');
  return '/api/v1/sellers/'+sellerId+'/invitations/'+invitationId;
}
function assertContext(data:InvitationQuoteContext,invitationId:string){
  if(!data||data.invitationId!==invitationId||!uuid.test(data.demandId)||
     typeof data.destinationCity!=='string'||!Array.isArray(data.lines)||
     data.lines.length===0||data.lines.length>50||!Array.isArray(data.origins)||
     !Number.isInteger(data.nextRevision)||data.nextRevision<1||
     !Number.isFinite(Date.parse(data.expiresAt))||
     data.lines.some(l=>!uuid.test(l.productId)||!demandQuantity.test(l.quantity)||
        decimalMilli(l.quantity)<=0n||!Array.isArray(l.skus)||l.skus.length===0||
        l.skus.some(s=>!uuid.test(s.skuId)||typeof s.code!=='string'||
          !s.availableByOrigin||typeof s.availableByOrigin!=='object'))||
     data.origins.some(o=>!uuid.test(o.originLocationId)||
        typeof o.pickup!=='boolean'||typeof o.delivery!=='boolean'))
     throw new Error('Invalid invitation quote context');
}
export async function loadInvitationQuote(sellerId:string,invitationId:string,
                                          token:string):Promise<InvitationQuoteContext>{
  const response=await fetch(publicQrApiBase()+invitationPath(sellerId,invitationId)+'/quote-context',{
    method:'GET',headers:auth(token),credentials:'omit',cache:'no-store'
  });
  if(!response.ok)throw new Error('Seller quote HTTP '+response.status);
  const data=await response.json() as InvitationQuoteContext;
  assertContext(data,invitationId);
  return data;
}
export function validateBidInput(context:InvitationQuoteContext,input:BidInput){
  if(!Number.isInteger(input.revision)||input.revision!==context.nextRevision||
    !uuid.test(input.originLocationId)||
    !context.origins.some(o=>o.originLocationId===input.originLocationId&&
      (input.method==='PICKUP'?o.pickup:input.method==='DELIVERY'&&o.delivery))||
    !priceKzt.test(input.deliveryFee)||!Number.isFinite(Date.parse(input.expiresAt))||
    new Date(input.expiresAt).getTime()>new Date(context.expiresAt).getTime()||
    new Date(input.expiresAt).getTime()<=Date.now()||
    (input.method==='PICKUP'&&decimalMilli(input.deliveryFee)>0n)||
    input.lines.length!==context.lines.length)
    throw new Error('Invalid bid quote');
  const seen=new Set<string>();
  for(const line of input.lines){
    const matched=context.lines.find(l=>l.productId===line.productId);
    const sku=matched?.skus.find(s=>s.skuId===line.skuId);
    if(!matched||!sku||seen.has(line.productId)||
       !demandQuantity.test(line.quantity)||line.quantity!==matched.quantity||
       !priceKzt.test(line.unitPrice)||
       !stockCovers(sku.availableByOrigin[input.originLocationId]||'0',line.quantity))
      throw new Error('Invalid or unavailable bid SKU');
    seen.add(line.productId);
  }
}
export async function submitInvitationBid(sellerId:string,invitationId:string,token:string,
  context:InvitationQuoteContext,input:BidInput,key?:string):Promise<string>{
  validateBidInput(context,input);
  if(key!==undefined && !/^[A-Za-z0-9_.:-]{1,80}$/.test(key))
    throw new Error('Invalid bid idempotency key');
  const response=await fetch(publicQrApiBase()+invitationPath(sellerId,invitationId)+'/bid',{
    method:'POST',headers:{...auth(token),'Content-Type':'application/json',
      ...(key?{'Idempotency-Key':key}:{})},
    credentials:'omit',cache:'no-store',body:JSON.stringify(input)
  });
  if(response.status!==201)throw new Error('Seller bid HTTP '+response.status);
  const value=await response.json() as {bidId?:string};
  if(!value||!value.bidId||!uuid.test(value.bidId))throw new Error('Invalid bid receipt');
  return value.bidId;
}
