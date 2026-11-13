import {describe,it,expect,vi,afterEach} from 'vitest';
import {createBuyerDemand,loadBuyerBids,acceptBuyerBid,loadBuyerOrder} from './buyer-api';
afterEach(()=>vi.unstubAllGlobals());
describe('Buyer commerce HTTP contract',()=>{
  it('only sends authenticated demand with canonical product and server-owned buyer identity',async()=>{
    const fetchMock=vi.fn().mockResolvedValue({ok:true,json:async()=>({demandId:'demand-1'})});
    vi.stubGlobal('fetch',fetchMock);
    const id=await createBuyerDemand('signed-token','product-1','PCS','2','Алматы');
    expect(id).toBe('demand-1');
    const [url,request]=fetchMock.mock.calls[0];
    expect(url).toBe('/api/v1/buyers/demands');
    expect(request.headers.Authorization).toBe('Bearer signed-token');
    const body=JSON.parse(request.body);
    expect(body.lines).toEqual([{productId:'product-1',unit:'PCS',quantity:'2'}]);
    expect(body.buyerId).toBeUndefined();
  });
  it('reads owned bid and sends a stable acceptance key',async()=>{
    const fetchMock=vi.fn()
      .mockResolvedValueOnce({ok:true,json:async()=>({demandId:'d',acceptedOrderId:null,bids:[]})})
      .mockResolvedValueOnce({ok:true,json:async()=>({orderId:'order-1'})})
      .mockResolvedValueOnce({ok:true,json:async()=>({
        orderId:'order-1',status:'PLACED',totalAmount:'100.00',
        deliveryFee:'0.00',lines:[{skuId:'sku',quantity:'1.000',lineTotal:'100.00'}]
      })});
    vi.stubGlobal('fetch',fetchMock);
    expect((await loadBuyerBids('token','d')).bids).toEqual([]);
    expect(await acceptBuyerBid('token','d','bid-1','accept-abc')).toBe('order-1');
    expect(fetchMock.mock.calls[1][1].headers['Idempotency-Key']).toBe('accept-abc');
    expect((await loadBuyerOrder('token','order-1')).status).toBe('PLACED');
  });
  it('rejects missing auth and remote errors without fake order',async()=>{
    await expect(createBuyerDemand('','p','PCS','1','Алматы')).rejects.toThrow('Verified buyer token required');
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:403}));
    await expect(loadBuyerBids('token','d')).rejects.toThrow('Buyer API HTTP 403');
  });

  it('rejects rounded numeric HTTP order amounts',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,json:async()=>({
      orderId:'order-1',totalAmount:9007199254740993.01,
      deliveryFee:0,lines:[{skuId:'sku',quantity:1,lineTotal:100}]
    })}));
    await expect(loadBuyerOrder('signed','order-1')).rejects.toThrow('Invalid buyer order response');
  });
});
