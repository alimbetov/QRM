// QA-02 media ingestion qualification. Run ONLY against isolated staging/dev.
// Every upload uses a distinct DRAFT listing prepared by the test operator.
// Real RustFS + ClamAV expected; no simulated scanner or fake local object store.
import http from 'k6/http';
import {check} from 'k6';
import {SharedArray} from 'k6/data';
import {Counter, Rate, Trend} from 'k6/metrics';

const vus = Number(__ENV.VUS || '20');
const maxDuration = __ENV.MAX_DURATION || '10m';
if(__ENV.I_UNDERSTAND_LOAD!=='true')
  throw new Error('Set I_UNDERSTAND_LOAD=true to authorize isolated media load');
if(!Number.isInteger(vus)||vus<1||vus>5000)
  throw new Error('VUS must be in 1..5000');
if(!__ENV.BASE_URL||!/^https:\/\//.test(__ENV.BASE_URL))
  throw new Error('Use an HTTPS isolated staging BASE_URL');
if(!__ENV.FIXTURES_PATH)
  throw new Error('FIXTURES_PATH must be a private JSON array of sellerId/listingId/token');
const mediaFile = __ENV.MEDIA_FILE || './fixtures/media-upload-640x480.png';
const binary = open(mediaFile,'b');
const listings = new SharedArray('distinct draft media listing slots',()=>{
  const data = JSON.parse(open(__ENV.FIXTURES_PATH));
  if(!Array.isArray(data)) throw new Error('Media fixtures must be a JSON array');
  return data;
});
if(listings.length < vus) throw new Error('Prepare at least VUS distinct DRAFT listings');

const success = new Counter('zakaz_media_created');
const throttled = new Counter('zakaz_media_429');
const conflicts = new Counter('zakaz_media_conflict');
const unexpected = new Rate('zakaz_media_unexpected_error');
const acceptedLatency = new Trend('zakaz_media_accepted_ms',true);

export const options = {
  scenarios:{
    distinct_listing_media_upload:{
      executor:'per-vu-iterations', vus, iterations:1,
      maxDuration, gracefulStop:'0s'
    }
  },
  thresholds:{zakaz_media_unexpected_error:['rate==0']},
  discardResponseBodies:true
};

export default function() {
  const record = listings[__VU-1];
  if(!record||!record.sellerId||!record.listingId||!record.sellerJwt)
    throw new Error('Missing sellerId/listingId/sellerJwt in private fixture');
  const url = `${__ENV.BASE_URL}/api/v1/sellers/${encodeURIComponent(record.sellerId)}/listings/${encodeURIComponent(record.listingId)}/media`;
  const response = http.post(url,{
    image: http.file(binary,'synthetic-640x480.png','image/png'),
    cover: 'true'
  },{
    headers:{Authorization:`Bearer ${record.sellerJwt}`,Accept:'application/json'},
    tags:{operation:'media_upload'},timeout:'40s'
  });
  const ok = response.status===201;
  const limited = response.status===429;
  const conflict = response.status===409;
  success.add(ok);
  throttled.add(limited);
  conflicts.add(conflict);
  unexpected.add(!ok&&!limited&&!conflict);
  if(ok) acceptedLatency.add(response.timings.duration);
  check(response,{'created, overload-limited or explicit draft conflict':r=>
    r.status===201||r.status===429||r.status===409});
}
