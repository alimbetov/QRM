import {afterEach,describe,expect,it,vi} from 'vitest';
import {
  createSellerDraft,downloadSellerQr,listSellerListings,publishSellerListing,
  setSellerSkuPrice,validateDraft,validPrice,type DraftInput
} from './seller-api';
import {sellerReturnTo} from './seller-oidc';

const seller='11111111-1111-4111-8111-111111111111';
const product='22222222-2222-4222-8222-222222222222';
const listing='33333333-3333-4333-8333-333333333333';
const sku='44444444-4444-4444-8444-444444444444';
const valid:DraftInput={productId:product,skuCode:'SELLER-PCS',
  unit:'PCS',trackingMode:'TRACKED',publicPriceKzt:'999.50'};
afterEach(()=>vi.unstubAllGlobals());

describe('seller catalog workspace contract',()=>{
  it('accepts exact KZT prices and rejects floating-point or forged drafts',()=>{
    expect(validPrice('999.50')).toBe(true);
    expect(validPrice('1')).toBe(true);
    for(const amount of ['0','0.00','-1','1e5','99.999','1,20','01.00','']) {
      expect(validPrice(amount)).toBe(false);
    }
    expect(()=>validateDraft(valid)).not.toThrow();
    expect(()=>validateDraft({...valid,productId:'bad'})).toThrow();
    expect(()=>validateDraft({...valid,unit:'SERVICE'})).toThrow();
    expect(()=>validateDraft({...valid,trackingMode:'UNTRACKED' as 'TRACKED'})).toThrow();
  });

  it('creates a server-owned DRAFT and never sends a seller identity in JSON',async()=>{
    const response={listingId:listing,productId:product,title:'Drill',
      categoryCode:'01020030000000000000',status:'DRAFT',qrToken:null,
      variants:[{skuId:sku,code:'SELLER-PCS',unit:'PCS',
        trackingMode:'TRACKED',priceKzt:'999.50'}]};
    const fetcher=vi.fn().mockResolvedValue({
      ok:true,status:201,json:async()=>response
    });
    vi.stubGlobal('fetch',fetcher);
    expect((await createSellerDraft(seller,'verified-seller-token',valid)).listingId)
      .toBe(listing);
    const [url,request]=fetcher.mock.calls[0];
    expect(url).toBe('/api/v1/sellers/'+seller+'/listings');
    expect(request.method).toBe('POST');
    expect(request.credentials).toBe('omit');
    expect(request.cache).toBe('no-store');
    expect(request.headers.Authorization).toBe('Bearer verified-seller-token');
    const body=JSON.parse(request.body);
    expect(body).not.toHaveProperty('sellerId');
    expect(body).toMatchObject({publicPriceKzt:'999.50',trackingMode:'TRACKED'});
  });

  it('only returns owner-scoped results and fails closed on HTTP 403/malformed JSON',async()=>{
    const fetcher=vi.fn().mockResolvedValue({ok:false,status:403});
    vi.stubGlobal('fetch',fetcher);
    await expect(listSellerListings(seller,'signed')).rejects.toThrow('Seller API HTTP 403');
    expect(fetcher.mock.calls[0][0]).toContain('/sellers/'+seller+'/listings?page=0&size=12');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      ok:true,status:200,json:async()=>({total:1,page:0,size:12,items:[{listingId:'bad'}]})
    }));
    await expect(listSellerListings(seller,'signed')).rejects.toThrow('Invalid seller listing');
    await expect(listSellerListings('bad','signed')).rejects.toThrow('Invalid seller identifier');
    await expect(listSellerListings(seller,'')).rejects.toThrow('Verified seller token required');
  });

  it('does not convert authorization/stock conflict into publishing success',async()=>{
    const fetcher=vi.fn().mockResolvedValue({ok:false,status:409});
    vi.stubGlobal('fetch',fetcher);
    await expect(publishSellerListing(seller,listing,'signed'))
      .rejects.toThrow('Seller API HTTP 409');
    expect(fetcher.mock.calls[0][0])
      .toContain('/sellers/'+seller+'/listings/'+listing+'/publish');
    await expect(setSellerSkuPrice(seller,listing,sku,'signed','5.999'))
      .rejects.toThrow('Invalid SKU price');
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('downloads only verified PNG QR after authenticated GET',async()=>{
    const bytes=new Blob(['png-bytes'],{type:'image/png'});
    const fetcher=vi.fn().mockResolvedValue({
      ok:true,headers:{get:()=> 'image/png'},blob:async()=>bytes
    });
    vi.stubGlobal('fetch',fetcher);
    expect(await downloadSellerQr(seller,listing,'signed')).toBe(bytes);
    const [url,request]=fetcher.mock.calls[0];
    expect(url).toContain('/qr.png');
    expect(request.headers.Authorization).toBe('Bearer signed');
    expect(request.headers.Accept).toBe('image/png');
  });
});

describe('OIDC seller isolation and return route',()=>{
  it('only restores seller-owned paths after PKCE callback',()=>{
    expect(sellerReturnTo('/seller')).toBe('/seller');
    expect(sellerReturnTo('/seller?tab=listings')).toBe('/seller?tab=listings');
    for(const unsafe of ['//evil.example','https://evil.example','/buy',
                          '/seller/admin','/seller\\bad','/seller\nInjected:x']) {
      expect(sellerReturnTo(unsafe)).toBe('/seller');
    }
  });
});
