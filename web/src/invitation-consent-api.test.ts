import {afterEach,describe,expect,it,vi} from 'vitest';
import {loadDemandSharing,loadSellerPreference,setDemandSharing,setSellerPreference}
  from './invitation-consent-api';

const demand='11111111-1111-4111-8111-111111111111';
const seller='22222222-2222-4222-8222-222222222222';
afterEach(()=>vi.unstubAllGlobals());

describe('ML-R2 opt-in settings use server authority',()=>{
  it('reads explicit owner-scoped buyer and seller choice without defaulting to true',async()=>{
    const fetcher=vi.fn().mockResolvedValue({ok:true,json:async()=>({enabled:false})});
    vi.stubGlobal('fetch',fetcher);
    expect(await loadDemandSharing(demand,'buyer-jwt')).toBe(false);
    expect(fetcher.mock.calls[0][0])
      .toBe('/api/v1/buyers/demands/'+demand+'/invitation-sharing');
    expect(fetcher.mock.calls[0][1].headers.Authorization).toBe('Bearer buyer-jwt');
    expect(fetcher.mock.calls[0][1].credentials).toBe('omit');
    expect(await loadSellerPreference(seller,'seller-jwt')).toBe(false);
    expect(fetcher.mock.calls[1][0])
      .toBe('/api/v1/sellers/'+seller+'/invitation-preference');
  });
  it('only a server 204 changes consent; never pretend a failed PUT succeeded',async()=>{
    const fetcher=vi.fn().mockResolvedValueOnce({status:204})
      .mockResolvedValueOnce({status:403});
    vi.stubGlobal('fetch',fetcher);
    await expect(setDemandSharing(demand,'buyer-jwt',true)).resolves.toBeUndefined();
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({enabled:true});
    await expect(setSellerPreference(seller,'seller-jwt',true))
      .rejects.toThrow('Consent update HTTP 403');
  });
  it('rejects malformed statuses and untrusted URL/token fields',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
      ok:true,json:async()=>({enabled:'true'})
    }));
    await expect(loadDemandSharing(demand,'buyer-jwt'))
      .rejects.toThrow('Invalid consent state');
    await expect(loadDemandSharing('not-uuid','buyer-jwt'))
      .rejects.toThrow('Invalid demand');
    await expect(loadSellerPreference(seller,'bad\r\nHeader:x'))
      .rejects.toThrow('Authenticated consent session required');
  });
});
