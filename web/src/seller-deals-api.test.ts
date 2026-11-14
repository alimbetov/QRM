import {afterEach,describe,expect,it,vi} from 'vitest';
import {
  sellerAccounts,sellerOrders,changeSellerOrder,sellerLeads,markSellerLead,
  sellerInvitations
} from './seller-deals-api';

const seller='11111111-1111-4111-8111-111111111111';
const order='22222222-2222-4222-8222-222222222222';
const listing='33333333-3333-4333-8333-333333333333';
const sku='44444444-4444-4444-8444-444444444444';
const lead='55555555-5555-4555-8555-555555555555';
const demand='66666666-6666-4666-8666-666666666666';
afterEach(()=>vi.unstubAllGlobals());

describe('seller inbox protected HTTP contracts',()=>{
  it('loads only trusted memberships from /sellers/me with bearer and no body',async()=>{
    const fetcher=vi.fn().mockResolvedValue({ok:true,json:async()=>[
      {sellerId:seller,role:'SELLER_ADMIN'}]});
    vi.stubGlobal('fetch',fetcher);
    expect(await sellerAccounts('seller-jwt')).toEqual([{sellerId:seller,role:'SELLER_ADMIN'}]);
    const [url,request]=fetcher.mock.calls[0];
    expect(url).toBe('/api/v1/sellers/me');
    expect(request.headers.Authorization).toBe('Bearer seller-jwt');
    expect(request.credentials).toBe('omit');
    expect(request.cache).toBe('no-store');
    expect(request.body).toBeUndefined();
  });

  it('rejects unexpected membership and never treats client sellerId as authorization',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,json:async()=>[
      {sellerId:'forged',role:'SELLER_ADMIN'}]}));
    await expect(sellerAccounts('jwt')).rejects.toThrow('Invalid seller membership');
    await expect(sellerOrders('forged','jwt')).rejects.toThrow('Invalid seller ID');
    await expect(sellerAccounts('')).rejects.toThrow('Verified seller token required');
  });

  it('reads bounded seller-only order page with exact decimal strings',async()=>{
    const fetcher=vi.fn().mockResolvedValue({ok:true,json:async()=>({
      page:0,size:20,total:1,items:[{
        orderId:order,publicNumber:'ZK-0001-0001',status:'PLACED',
        totalAmount:'100.01',deliveryFee:'0.00',currency:'KZT',
        createdAt:'2026-11-14T00:00:00Z',lines:[{
          skuId:sku,quantity:'1.000',unit:'PCS',lineTotal:'100.01'
        }]
      }]
    })});
    vi.stubGlobal('fetch',fetcher);
    expect((await sellerOrders(seller,'token')).items[0].totalAmount).toBe('100.01');
    expect(fetcher.mock.calls[0][0])
      .toBe('/api/v1/sellers/'+seller+'/orders?page=0&size=20&status=ALL');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,json:async()=>({
      page:0,size:20,total:1,items:[{
        orderId:order,totalAmount:100.01,deliveryFee:0,lines:[]
      }]
    })}));
    await expect(sellerOrders(seller,'token')).rejects.toThrow('Invalid seller order');
  });

  it('never silently confirms on 403/409 and only accepts server 204',async()=>{
    const fetcher=vi.fn().mockResolvedValueOnce({status:204})
      .mockResolvedValueOnce({status:409});
    vi.stubGlobal('fetch',fetcher);
    await expect(changeSellerOrder(seller,order,'seller-token','confirm'))
      .resolves.toBeUndefined();
    const [path,request]=fetcher.mock.calls[0];
    expect(path).toBe('/api/v1/sellers/'+seller+'/orders/'+order+'/confirm');
    expect(request.method).toBe('POST');
    expect(request.headers.Authorization).toBe('Bearer seller-token');
    expect(request.body).toBeUndefined();
    await expect(changeSellerOrder(seller,order,'seller-token','cancel'))
      .rejects.toThrow('Seller HTTP 409');
  });

  it('explicitly reads audited guest callbacks and marks one terminal disposition',async()=>{
    const fetcher=vi.fn().mockResolvedValueOnce({ok:true,json:async()=>[{
      leadId:lead,listingId:listing,phone:'+77011234567',
      receivedAt:'2026-11-14T00:00:00Z',status:'NEW'
    }]}).mockResolvedValueOnce({status:204});
    vi.stubGlobal('fetch',fetcher);
    expect((await sellerLeads(seller,'seller-token'))[0].status).toBe('NEW');
    await markSellerLead(seller,lead,'seller-token','CONTACTED');
    const [path,request]=fetcher.mock.calls[1];
    expect(path).toBe('/api/v1/sellers/'+seller+'/leads/'+lead+'/status');
    expect(request.method).toBe('PUT');
    expect(JSON.parse(request.body)).toEqual({status:'CONTACTED'});
    expect(request.headers.Authorization).toBe('Bearer seller-token');
    await expect(markSellerLead(seller,'bad','token','DECLINED'))
      .rejects.toThrow('Invalid lead transition');
  });

  it('does not invent invitations when the consent API is disabled',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:403}));
    await expect(sellerInvitations(seller,'token')).rejects.toThrow('Seller HTTP 403');
    const fetcher=vi.fn().mockResolvedValue({ok:true,json:async()=>[{
      invitationId:order,demandId:demand,deliveredAt:'2026-11-14T00:00:00Z'
    }]});
    vi.stubGlobal('fetch',fetcher);
    expect((await sellerInvitations(seller,'token'))[0].demandId).toBe(demand);
  });
});
