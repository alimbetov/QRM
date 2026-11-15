import {describe,it,expect,vi,afterEach} from 'vitest';
import {catalogUrl,loadCatalog,loadCategories,photoFeedUrl,loadPhotoFeed,photoMediaUrl} from './catalog-api';

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


describe('photo discovery feed API',()=>{
  it('encodes Unicode query, category and opaque cursor',()=>{
    const uri=new URL(photoFeedUrl('болгарка','01020030000000000000','abc.def',24),'http://localhost');
    expect(uri.pathname).toBe('/api/v1/public/catalog/feed');
    expect(uri.searchParams.get('query')).toBe('болгарка');
    expect(uri.searchParams.get('cursor')).toBe('abc.def');
    expect(uri.searchParams.get('categoryCode')).toBe('01020030000000000000');
    expect(()=>photoFeedUrl('','',null,100)).toThrow('Invalid feed limit');
    expect(()=>photoFeedUrl('','bad',null)).toThrow('Invalid feed category');
  });
  it('returns real paginated feed only and validates cursor',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      ok:true,json:async()=>({items:[{
        listingId:'listing-uuid',productId:'product-uuid',title:'Test',category:'Tool',
        categoryCode:'01020030000000000000',productType:'PHYSICAL',
        qrToken:'a'.repeat(43),variants:[],coverUrl:null,imageCount:0
      }],nextCursor:'opaque',hasMore:true})
    }));
    const res=await loadPhotoFeed('bolg','',null);
    expect(res.items).toHaveLength(1);
    expect(res.nextCursor).toBe('opaque');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      ok:true,json:async()=>({items:[],nextCursor:null,hasMore:true})
    }));
    await expect(loadPhotoFeed('','',null)).rejects.toThrow('Feed cursor required');
  });
  it('accepts only own public media path and rejects arbitrary URLs',()=>{
    expect(photoMediaUrl('/api/v1/public/media/'+'a'.repeat(36)+'?size=thumb'))
      .toContain('/api/v1/public/media/');
    expect(photoMediaUrl('https://other.example/unsafe')).toBeNull();
    expect(photoMediaUrl('/api/v1/public/media/../admin')).toBeNull();
  });
});
