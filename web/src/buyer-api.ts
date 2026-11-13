import { publicQrApiBase } from './qr-api';

export type BidLine = {
  skuId: string;
  productId: string;
  quantity: string;
  unit: string;
  unitPriceKzt: string;
};
export type BuyerBid = {
  bidId: string;
  sellerId: string;
  revision: number;
  expiresAt: string;
  method: string;
  totalKzt: string;
  deliveryFeeKzt: string;
  lines: BidLine[];
};
export type BuyerBids = {
  demandId: string;
  acceptedOrderId: string | null;
  bids: BuyerBid[];
};
export type BuyerOrder = {
  orderId: string;
  publicNumber: string;
  sellerId: string;
  status: string;
  totalAmount: string;
  deliveryFee: string;
  currency: string;
  lines: Array<{skuId:string; quantity:string; unit:string; lineTotal:string}>;
};

async function authorizedJson<T>(method: string, path: string, token: string,
                                 payload?: object, idempotencyKey?: string): Promise<T> {
  if (!token) throw new Error('Verified buyer token required');
  const headers: Record<string,string> = {
    Authorization: 'Bearer ' + token,
    Accept: 'application/json'
  };
  if (payload) headers['Content-Type'] = 'application/json';
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  const response = await fetch(publicQrApiBase() + path, {
    method, headers, credentials: 'omit', cache: 'no-store',
    ...(payload ? {body:JSON.stringify(payload)} : {})
  });
  if (!response.ok) {
    // Never surface backend error details or security context to a buyer.
    throw new Error('Buyer API HTTP ' + response.status);
  }
  return await response.json() as T;
}

export async function createBuyerDemand(
  token: string, productId: string, unit: string, quantity: string, city: string
): Promise<string> {
  const now=Date.now();
  const r=await authorizedJson<{demandId:string}>('POST','/api/v1/buyers/demands',token,{
    city:city.trim(),
    expiresAt:new Date(now+7*24*3600*1000).toISOString(),
    lines:[{productId,unit,quantity}]
  });
  return r.demandId;
}
export function loadBuyerBids(token: string, demandId: string): Promise<BuyerBids> {
  return authorizedJson<BuyerBids>('GET',
    '/api/v1/buyers/demands/' + encodeURIComponent(demandId) + '/bids',token);
}
export async function acceptBuyerBid(
  token: string, demandId: string, bidId: string, idempotencyKey: string
): Promise<string> {
  const r=await authorizedJson<{orderId:string}>('POST',
    '/api/v1/buyers/demands/'+encodeURIComponent(demandId)+'/bids/'+encodeURIComponent(bidId)+'/accept',
    token,undefined,idempotencyKey);
  return r.orderId;
}
export async function loadBuyerOrder(token: string, orderId: string): Promise<BuyerOrder> {
  const data = await authorizedJson<BuyerOrder>('GET',
    '/api/v1/buyers/orders/'+encodeURIComponent(orderId),token);
  // Decimal strings are mandatory: JS Number cannot preserve money precisely.
  if (!data || data.orderId !== orderId || typeof data.totalAmount !== 'string' ||
      typeof data.deliveryFee !== 'string' || !Array.isArray(data.lines) ||
      data.lines.some(line => typeof line.quantity !== 'string' ||
        typeof line.lineTotal !== 'string')) {
    throw new Error('Invalid buyer order response');
  }
  return data;
}
