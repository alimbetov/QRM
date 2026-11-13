import {afterEach, describe, expect, it, vi} from 'vitest';
import {enrollBuyer,loadBuyerProfile} from './enrollment-api';

afterEach(()=>vi.unstubAllGlobals());

describe('OIDC buyer enrollment API',()=>{
  it('returns null only for a not-yet-enrolled user',async()=>{
    const fetchMock=vi.fn().mockResolvedValue({status:404,ok:false});
    vi.stubGlobal('fetch',fetchMock);
    expect(await loadBuyerProfile('signed-token')).toBeNull();
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer signed-token');
    expect(fetchMock.mock.calls[0][1].credentials).toBe('omit');
  });
  it('requires an authenticated user and never supplies a claimed buyerId',async()=>{
    await expect(loadBuyerProfile('')).rejects.toThrow();
    const fetchMock=vi.fn().mockResolvedValue({
      status:201,ok:true,json:async()=>({
        buyerId:'server-owned-id',nickname:'Buyer123',maskedPhone:'••••0000',
        email:'buyer@example.test',phoneVerified:false,created:true
      })
    });
    vi.stubGlobal('fetch',fetchMock);
    const profile=await enrollBuyer('signed-token','Buyer123','+77000000000',true);
    expect(profile.buyerId).toBe('server-owned-id');
    const body=JSON.parse(fetchMock.mock.calls[0][1].body as string);
    expect(body).toEqual({nickname:'Buyer123',phone:'+77000000000',consentToStorePhone:true});
    expect(body).not.toHaveProperty('buyerId');
    expect(body).not.toHaveProperty('emailVerified');
  });
  it('does not silently create accounts when protected API denies access',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({status:403,ok:false}));
    await expect(loadBuyerProfile('signed-token')).rejects.toThrow(/403/);
  });
});
