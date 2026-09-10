import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
const env=Object.fromEntries(readFileSync(new URL('../.env',import.meta.url),'utf8').trim().split(/\r?\n/).map(line=>{const i=line.indexOf('=');return [line.slice(0,i),line.slice(i+1)]}));
const base=process.env.TEST_BASE_URL||'http://localhost:'+(env.APP_PORT||8088);
function client(){
 const cookies=new Map();let csrf;
 async function request(path,{method='GET',body,headers={}}={}){
  const h={...headers,Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; ')};
  if(csrf&&method!=='GET')h[csrf.headerName]=csrf.token;
  if(body&&typeof body!=='string'){h['Content-Type']='application/json';body=JSON.stringify(body);}
  const response=await fetch(base+'/api'+path,{method,headers:h,body});
  for(const cookie of response.headers.getSetCookie()){const first=cookie.split(';')[0],i=first.indexOf('=');cookies.set(first.slice(0,i),first.slice(i+1));}
  const raw=await response.text();let data;try{data=JSON.parse(raw)}catch{data=raw}
  return {status:response.status,data};
 }
 async function login(role){csrf=(await request('/csrf')).data;const result=await request('/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:role+'@fraudshield.demo',password:env.DEMO_PASSWORD}).toString()});assert.equal(result.status,200,'login '+role);csrf=(await request('/csrf')).data;}
 return {request,login};
}

// Run against local sandbox; restores rule settings in finally.
const admin=client(),analyst=client(),viewer=client();
await admin.login('admin');await analyst.login('analyst');await viewer.login('viewer');
assert.equal((await admin.request('/notifications/settings')).data.enabled,false,'Pause automatic Email alerts before generating smoke-test transactions');
const original=(await admin.request('/rules/catalog')).data;
assert.equal(original.length,8);
const saved=new Map(original.map(r=>[r.code,r]));
const body=(r,extra={})=>({version:r.version,enabled:r.enabled,points:r.points,matchValues:r.match_values,...extra});
async function update(code,extra){const current=(await admin.request('/rules/catalog')).data.find(r=>r.code===code);const response=await admin.request('/rules/catalog/'+code,{method:'PUT',body:body(current,extra)});assert.equal(response.status,200,JSON.stringify(response.data));return response.data;}
async function txn(extra={}){
 const eventId='rules-'+randomUUID();
 const payload={eventId,accountId:'rules-'+randomUUID(),amountMinor:10000,currency:'INR',merchant:'Rule Test Merchant',country:'IN',deviceId:'rule-device',phoneNumber:'+12025550123',failedAttempts:0,occurredAt:new Date().toISOString(),...extra};
 const response=await analyst.request('/transactions',{method:'POST',headers:{'Idempotency-Key':eventId},body:payload});assert.equal(response.status,202,JSON.stringify(response.data));
 for(let i=0;i<40;i++){const row=(await analyst.request('/transactions/'+response.data.id)).data;if(row.status==='SCORED')return {row,payload};await new Promise(r=>setTimeout(r,300));}throw new Error('Scoring timed out');
}
try {
 assert.equal((await analyst.request('/rules/catalog')).status,403);
 assert.equal((await viewer.request('/rules/catalog/BLOCKED_IP',{method:'PUT',body:body(saved.get('BLOCKED_IP'))})).status,403);
 let rule=await update('BLOCKED_IP',{enabled:true,points:100,matchValues:'203.0.113.7\n198.51.100.0/24'});
 assert.equal((await admin.request('/rules/catalog/BLOCKED_IP',{method:'PUT',body:body(saved.get('BLOCKED_IP'))})).status,409);
 assert.equal((await admin.request('/rules/catalog/BLOCKED_IP',{method:'PUT',body:body(rule,{matchValues:'300.1.2.3'})})).status,400);
 assert.equal((await admin.request('/rules/catalog/BLOCKED_IP',{method:'PUT',body:body(rule,{matchValues:'1.2.3.4/33'})})).status,400);
 const exact=(await txn({ipAddress:'203.0.113.7'})).row;
 assert.equal(exact.score,100);assert.equal(exact.classification,'HIGH_RISK');
 assert.ok(JSON.parse(exact.features).matchedRules.includes('BLOCKED_IP'));
 assert.equal((await txn({ipAddress:'198.51.100.255'})).row.score,100);
 assert.equal((await txn({ipAddress:'198.51.101.1'})).row.score,0);
 await update('BLOCKED_IP',{enabled:false});
 assert.equal((await txn({ipAddress:'203.0.113.7'})).row.score,0,'paused rule contributes no points');
 assert.equal((await admin.request('/transactions/'+exact.id)).data.score,100,'old assessment unchanged');
 await update('BLOCKED_IP',{enabled:true,points:40});
 assert.equal((await txn({ipAddress:'203.0.113.7'})).row.score,40,'updated points used');
 rule=await update('BLOCKED_PHONE',{enabled:true,points:100,matchValues:'+91 98765-43210'});
 assert.equal(rule.match_values,'+919876543210');
 const phone=await txn({phoneNumber:'+91 (98765) 43210'});
 assert.equal(phone.row.phone_number,'+919876543210');assert.equal(phone.row.score,100);
 const duplicate=await analyst.request('/transactions',{method:'POST',headers:{'Idempotency-Key':phone.payload.eventId},body:phone.payload});
 assert.equal(duplicate.data.duplicate,true);
 assert.equal((await analyst.request('/transactions',{method:'POST',headers:{'Idempotency-Key':phone.payload.eventId},body:{...phone.payload,phoneNumber:'+919876543211'}})).status,409);
 await update('BLOCKED_PHONE',{enabled:false});
 assert.equal((await txn({phoneNumber:'+919876543210'})).row.score,0);
 await update('LARGE_AMOUNT',{enabled:false});
 assert.equal((await txn({amountMinor:5000000})).row.score,0);
 await update('LARGE_AMOUNT',{enabled:true,points:25});
 assert.equal((await txn({amountMinor:5000000})).row.score,25);
 for(const extra of [{ipAddress:'host.example'},{phoneNumber:'1234'}]){
  const id='invalid-'+randomUUID();
  assert.equal((await analyst.request('/transactions',{method:'POST',headers:{'Idempotency-Key':id},body:{...phone.payload,eventId:id,...extra}})).status,400);
 }
 const audit=(await admin.request('/audit-events')).data;
 assert.ok(audit.some(e=>e.action==='RULE_UPDATED'));
 console.log('PASS: IP exact/CIDR matching, phone normalization, pause/resume, edited points, immutable assessments, stale-version rejection, role enforcement, input validation, idempotency and auditing.');
} finally {
 for(const r of original)await update(r.code,{enabled:r.enabled,points:r.points,matchValues:r.match_values});
}

