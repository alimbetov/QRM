// R2.3 performance qualification: 5000 distinct PLACED orders, one attempt each.
// NOT runnable against current application: the reviewed authenticated confirm
// HTTP route and populated fixture dataset do not exist yet.
// Example: BASE_URL=https://test.zakaz.kz FIXTURES_PATH=./private-orders.json \\
//          k6 run -e BASE_URL=https://test.zakaz.kz \\
//                 -e FIXTURES_PATH=./private-orders.json -e VUS=5000 perf/k6/r2-confirm-reserve.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate } from 'k6/metrics';

const vus=Number(__ENV.VUS || 5000);
const duration=__ENV.MAX_DURATION || '10m';
if (!__ENV.BASE_URL) throw new Error('BASE_URL must point to isolated S2-approved test environment');
if (!__ENV.FIXTURES_PATH) throw new Error('FIXTURES_PATH must point to private JSON array of distinct pre-created orders');
if (!Number.isInteger(vus)||vus<1||vus>5000) throw new Error('VUS must be integer 1..5000');

const orders=new SharedArray('prepared distinct PLACED orders',()=>{
    const rows=JSON.parse(open(__ENV.FIXTURES_PATH));
    if(!Array.isArray(rows))throw new Error('Fixtures must be JSON array');
    return rows;
});
if(orders.length<vus)throw new Error(`Need at least ${vus} distinct pre-created orders; got ${orders.length}`);
const orderIds=new Set();
for(let i=0;i<vus;i++)orderIds.add(orders[i].orderId);
if(orderIds.size!==vus)throw new Error('Duplicate order IDs in fixture; benchmark would measure replay instead of concurrency');

const confirmed=new Counter('zakaz_confirm_success');
const conflicts=new Counter('zakaz_confirm_conflicts');
const unexpected=new Rate('zakaz_unexpected_response');

export const options={
    scenarios:{
        distinct_order_confirmation:{
            executor:'per-vu-iterations',vus,iterations:1,maxDuration:duration,
            exec:'confirmOrder',gracefulStop:'0s'
        }
    },
    thresholds:{
        http_req_duration:['p(95)<1500','p(99)<3000'],
        zakaz_unexpected_response:['rate==0']
    },
    discardResponseBodies:true,
};

export function confirmOrder(){
    const i=__VU-1;
    const fixture=orders[i];
    if(!fixture||!fixture.sellerId||!fixture.orderId||!fixture.sellerJwt){
        throw new Error(`Invalid fixture at index ${i}: sellerId/orderId/sellerJwt required`);
    }
    const endpoint=`${__ENV.BASE_URL}/api/v1/sellers/${encodeURIComponent(fixture.sellerId)}/orders/${encodeURIComponent(fixture.orderId)}/confirm`;
    const res=http.post(endpoint,null,{
        headers:{'Authorization':`Bearer ${fixture.sellerJwt}`,'Accept':'application/json'},
        timeout:'30s',
        tags:{operation:'order_confirm'}
    });
    const ok=res.status===200 || res.status===204;
    const contention=res.status===409;
    confirmed.add(ok?1:0);
    conflicts.add(contention?1:0);
    unexpected.add(!ok && !contention);
    check(res,{'expected successful confirmation or a stock conflict':r=>
        r.status===200||r.status===204||r.status===409});
}
