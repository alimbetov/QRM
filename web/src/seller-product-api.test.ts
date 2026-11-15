import {afterEach,describe,expect,it,vi} from 'vitest';
import {searchSellerCanonicalProducts,validProductSearch} from './seller-product-api';

const seller='11111111-1111-4111-8111-111111111111';
const product='22222222-2222-4222-8222-222222222222';
const option={id:product,name:'Кабель медный',categoryCode:'01020030000000000000',categoryLabel:'Кабели'};

afterEach(()=>vi.unstubAllGlobals());

describe('seller canonical-product picker',()=>{
  it('accepts names but rejects empty/wildcard/oversized queries',()=>{
    expect(validProductSearch('Кабель')).toBe(true);
    expect(validProductSearch('  Drill  ')).toBe(true);
    for(const value of ['','x','%','_','a_b','a%','a'.repeat(81)]) {
      expect(validProductSearch(value)).toBe(false);
    }
  });

  it('calls only the seller-scoped authenticated API and displays real options',async()=>{
    const fetcher=vi.fn().mockResolvedValue({
      ok:true,status:200,json:async()=>[option]
    });
    vi.stubGlobal('fetch',fetcher);
    expect(await searchSellerCanonicalProducts(seller,'signed','  Кабель  ')).toEqual([option]);
    const [url,init]=fetcher.mock.calls[0];
    expect(url).toBe('/api/v1/sellers/'+seller+
      '/catalog-products?query='+encodeURIComponent('Кабель'));
    expect(init.headers.Authorization).toBe('Bearer signed');
    expect(init.credentials).toBe('omit');
    expect(init.cache).toBe('no-store');
    expect(init.method).toBe('GET');
    expect(init.body).toBeUndefined();
  });

  it('fails closed on a denied or malformed response; no placeholder data',async()=>{
    const fetcher=vi.fn().mockResolvedValue({ok:false,status:403});
    vi.stubGlobal('fetch',fetcher);
    await expect(searchSellerCanonicalProducts(seller,'signed','Кабель'))
      .rejects.toThrow('HTTP 403');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      ok:true,status:200,json:async()=>[{
        id:'not-a-uuid',name:'Invented product',categoryCode:'broken',categoryLabel:'Bad'
      }]
    }));
    await expect(searchSellerCanonicalProducts(seller,'signed','Кабель'))
      .rejects.toThrow('Invalid canonical product');
    await expect(searchSellerCanonicalProducts(seller,'','Кабель'))
      .rejects.toThrow('Verified seller');
    await expect(searchSellerCanonicalProducts('bad','signed','Кабель'))
      .rejects.toThrow('Verified seller');
    await expect(searchSellerCanonicalProducts(seller,'signed','%'))
      .rejects.toThrow('Invalid canonical product query');
  });

  it('returns an empty set when the actual catalog contains no matched product',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      ok:true,status:200,json:async()=>[]
    }));
    expect(await searchSellerCanonicalProducts(seller,'signed','Кабель')).toEqual([]);
  });
});
