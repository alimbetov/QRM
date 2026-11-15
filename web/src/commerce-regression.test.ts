import {afterEach,describe,expect,it,vi} from 'vitest';
import {acceptBuyerBid,createBuyerDemand,loadBuyerBids,loadBuyerOrder} from './buyer-api';
import {loadPhotoFeed,photoFeedUrl,photoMediaUrl} from './catalog-api';
import {enrollBuyer,loadBuyerProfile} from './enrollment-api';
import {formatPublishedPriceKzt,publicQrPath,tokenFromPath} from './qr-api';

afterEach(()=>vi.unstubAllGlobals());

describe('completed commerce regression contracts',()=>{
  it('does not manufacture order on an idempotency conflict or forbidden response',async()=>{
    const mocked=vi.fn().mockResolvedValue({ok:false,status:409});
    vi.stubGlobal('fetch',mocked);
    await expect(acceptBuyerBid('signed','demand','bid','retry-key'))
      .rejects.toThrow('Buyer API HTTP 409');
    const req=mocked.mock.calls[0][1];
    expect(req.headers['Idempotency-Key']).toBe('retry-key');
    expect(req.credentials).toBe('omit');
    expect(req.cache).toBe('no-store');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:403}));
    await expect(loadBuyerOrder('signed','foreign-order')).rejects.toThrow('Buyer API HTTP 403');
  });

  it('percent-encodes untrusted identifiers and never transmits a buyer ID',async()=>{
    const mocked=vi.fn().mockResolvedValue({ok:true,json:async()=>({bids:[]})});
    vi.stubGlobal('fetch',mocked);
    await loadBuyerBids('signed','demand/../../other');
    expect(mocked.mock.calls[0][0]).toContain('demand%2F..%2F..%2Fother');
    const secondMock=vi.fn().mockResolvedValue({ok:true,json:async()=>({demandId:'one'})});
    vi.stubGlobal('fetch',secondMock);
    await createBuyerDemand('signed','canonical-id','PCS','1','  Алматы ');
    const body=JSON.parse(secondMock.mock.calls[0][1].body);
    expect(body.city).toBe('Алматы');
    expect(body.lines).toEqual([{productId:'canonical-id',unit:'PCS',quantity:'1'}]);
    expect(body).not.toHaveProperty('buyerId');
  });

  it('preserves fail-closed enrollment semantics for account creation',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:404}));
    await expect(enrollBuyer('signed','buyer','+77001234567',true))
      .rejects.toThrow('Buyer enrollment HTTP 404');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:401}));
    await expect(loadBuyerProfile('expired')).rejects.toThrow('Buyer enrollment HTTP 401');
  });
});

describe('completed QR and photo-feed regression contracts',()=>{
  it('limits public media to canonical relative paths',()=>{
    const id='a'.repeat(36);
    expect(photoMediaUrl('/api/v1/public/media/'+id+'?size=thumb')).not.toBeNull();
    for(const url of ['https://evil.example/path','//evil.example/image',
      '/api/v1/public/media/../admin?size=thumb',
      '/api/v1/public/media/'+id+'?size=full']) {
      expect(photoMediaUrl(url)).toBeNull();
    }
  });

  it('does not fabricate a feed page when HTTP response or cursor is inconsistent',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:503}));
    await expect(loadPhotoFeed('','',null)).rejects.toThrow('Catalog HTTP 503');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      ok:true,json:async()=>({items:[],nextCursor:null,hasMore:true})
    }));
    await expect(loadPhotoFeed('','',null)).rejects.toThrow('Feed cursor required');
    expect(()=>photoFeedUrl('','',null,49)).toThrow('Invalid feed limit');
  });

  it('rejects forged QR path and never rounds arbitrary catalog price text',()=>{
    const token='A'.repeat(43);
    expect(tokenFromPath('/q/'+token)).toBe(token);
    expect(tokenFromPath('/q/'+token+'/../admin')).toBeNull();
    expect(()=>publicQrPath('bad/token')).toThrow('Invalid QR token');
    expect(formatPublishedPriceKzt('99999999999999999.99'))
      .toBe('99\u00a0999\u00a0999\u00a0999\u00a0999\u00a0999,99 ₸');
    expect(formatPublishedPriceKzt('1.999')).toBe('Цена уточняется');
  });
});
