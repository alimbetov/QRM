import {describe,it,expect,vi,afterEach} from 'vitest';
import {catalogUrl,loadCatalog,loadCategories} from './catalog-api';

afterEach(() => vi.unstubAllGlobals());
describe('live catalog HTTP client',()=>{
  it('constructs bounded URL with properly encoded search and category',()=>{
    const url=catalogUrl('Электрика Алматы','01020030000000000000',2,12);
    const parsed=new URL(url,'http://localhost');
    expect(parsed.pathname).toBe('/api/v1/public/catalog');
    expect(parsed.searchParams.get('query')).toBe('Электрика Алматы');
    expect(parsed.searchParams.get('categoryCode')).toBe('01020030000000000000');
    expect(parsed.searchParams.get('page')).toBe('2');
    expect(()=>catalogUrl('', '',-1)).toThrow();
    expect(()=>catalogUrl('', '',0,100)).toThrow();
  });
  it('uses real HTTP response, never demo data fallback',async()=>{
    const mock=vi.fn().mockResolvedValue({
      ok:true, json:async()=>({page:0,size:12,total:0,items:[]})
    });
    vi.stubGlobal('fetch',mock);
    const data=await loadCatalog('','',0);
    expect(data.items).toEqual([]);
    expect(data.total).toBe(0);
    expect(mock).toHaveBeenCalledOnce();
    const options=mock.mock.calls[0][1];
    expect(options.credentials).toBe('omit');
  });
  it('rejects HTTP failure and malformed responses',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:503}));
    await expect(loadCatalog('','',0)).rejects.toThrow('Catalog HTTP 503');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,json:async()=>({items:null})}));
    await expect(loadCatalog('','',0)).rejects.toThrow('Invalid catalog response');
  });
  it('loads categories only through API',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,
      json:async()=>[{code:'01020030000000000000',label:'Товары',productType:'PHYSICAL'}]}));
    expect((await loadCategories())[0].label).toBe('Товары');
  });
});
