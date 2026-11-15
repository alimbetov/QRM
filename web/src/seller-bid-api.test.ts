import {afterEach,describe,expect,it,vi} from 'vitest';
import {
  loadInvitationQuote,submitInvitationBid,validateBidInput,
  stockCovers,decimalMilli,type InvitationQuoteContext,type BidInput
} from './seller-bid-api';

const seller='11111111-1111-4111-8111-111111111111';
const invitation='22222222-2222-4222-8222-222222222222';
const demand='33333333-3333-4333-8333-333333333333';
const product='44444444-4444-4444-8444-444444444444';
const sku='55555555-5555-4555-8555-555555555555';
const origin='66666666-6666-4666-8666-666666666666';
const ctx:InvitationQuoteContext={
  invitationId:invitation,demandId:demand,destinationCity:'Almaty',
  expiresAt:'2090-11-14T12:30:00Z',nextRevision:1,
  origins:[{originLocationId:origin,city:'Almaty',regionCode:'KZ-ALA',
    address:'Main 10',type:'SHOP',pickup:true,delivery:true}],
  lines:[{productId:product,productName:'Hammer',hccCode:'01020030000000000000',
    unit:'PCS',quantity:'1.000',maxUnitPrice:'200.00',
    skus:[{skuId:sku,code:'A',priceKzt:'100.00',availableByOrigin:{[origin]:'5.000'}}]
  }]
};
const input:BidInput={revision:1,expiresAt:'2090-11-14T12:00:00Z',
  method:'PICKUP',originLocationId:origin,deliveryFee:'0.00',
  lines:[{productId:product,skuId:sku,quantity:'1.000',unitPrice:'100.00'}]
};
afterEach(()=>vi.unstubAllGlobals());

describe('consented SellerBid workspace',()=>{
  it('compares PostgreSQL NUMERIC quantities exactly with BigInt, not JS float',()=>{
    expect(decimalMilli('1.001')).toBe(1001n);
    expect(stockCovers('1.000','1.000')).toBe(true);
    expect(stockCovers('0.999','1.000')).toBe(false);
    expect(stockCovers('999999999999999.999','1.000')).toBe(true);
    expect(()=>decimalMilli('1e3')).toThrow();
    expect(()=>decimalMilli('-1')).toThrow();
  });
  it('only GETs a verified seller invitation, no buyer identity or token in URL',async()=>{
    const fetcher=vi.fn().mockResolvedValue({
      ok:true,json:async()=>ctx
    });
    vi.stubGlobal('fetch',fetcher);
    expect((await loadInvitationQuote(seller,invitation,'scoped')).demandId).toBe(demand);
    const [url,options]=fetcher.mock.calls[0];
    expect(url).toBe('/api/v1/sellers/'+seller+'/invitations/'+invitation+'/quote-context');
    expect(options.headers.Authorization).toBe('Bearer scoped');
    expect(options.credentials).toBe('omit');
    expect(options.cache).toBe('no-store');
  });
  it('submits complete seller-owned basket to a real HTTP endpoint and respects 201 only',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      status:201,json:async()=>({bidId:'77777777-7777-4777-8777-777777777777'})
    }));
    const bidId=await submitInvitationBid(seller,invitation,'seller-auth',ctx,input);
    expect(bidId).toBe('77777777-7777-4777-8777-777777777777');
    const options=vi.mocked(fetch).mock.calls[0][1]!;
    expect(options.method).toBe('POST');
    expect(options.credentials).toBe('omit');
    expect(JSON.parse(String(options.body))).toEqual(input);
    expect(String(options.body)).not.toContain('buyerId');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({status:409}));
    await expect(submitInvitationBid(seller,invitation,'seller-auth',ctx,input))
      .rejects.toThrow('Seller bid HTTP 409');
  });
  it('rejects foreign SKU, stale revision, partial quantity and insufficient stock preflight',()=>{
    expect(()=>validateBidInput(ctx,input)).not.toThrow();
    expect(()=>validateBidInput(ctx,{...input,revision:2})).toThrow();
    expect(()=>validateBidInput(ctx,{...input,deliveryFee:'1.00'})).toThrow();
    expect(()=>validateBidInput(ctx,{...input,lines:[{...input.lines[0],skuId:demand}]}))
      .toThrow();
    expect(()=>validateBidInput(ctx,{...input,lines:[{...input.lines[0],quantity:'2.000'}]}))
      .toThrow();
    const tooLittle:InvitationQuoteContext={...ctx,
      lines:ctx.lines.map(l=>({...l,skus:l.skus.map(s=>({...s,
        availableByOrigin:{[origin]:'0.000'}}))}))};
    expect(()=>validateBidInput(tooLittle,input)).toThrow();
  });
  it('never fabricates a request after unconsented, expired or malformed API response',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:404}));
    await expect(loadInvitationQuote(seller,invitation,'scoped'))
      .rejects.toThrow('Seller quote HTTP 404');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,json:async()=>({
      ...ctx,lines:[{...ctx.lines[0],skus:[]}],buyerId:'PII'
    })}));
    await expect(loadInvitationQuote(seller,invitation,'scoped'))
      .rejects.toThrow('Invalid invitation quote context');
  });
});
