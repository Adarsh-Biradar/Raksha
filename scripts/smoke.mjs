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
const admin=client(),viewer=client(),analyst=client(),anonymous=client();
assert.equal((await anonymous.request('/transactions')).status,401);
assert.equal((await anonymous.request('/simulations',{method:'POST',body:{scenario:'NORMAL'}})).status,403,'CSRF enforced');
await admin.login('admin');await viewer.login('viewer');await analyst.login('analyst');
assert.equal((await admin.request('/notifications/settings')).data.enabled,false,'Pause automatic Email alerts before generating smoke-test transactions');
assert.equal((await viewer.request('/simulations',{method:'POST',body:{scenario:'NORMAL'}})).status,403,'viewer cannot mutate');
assert.equal((await analyst.request('/rules')).status,403,'analyst cannot configure policy');
const id='test-'+randomUUID();
const payload={eventId:id,accountId:'smoke-'+randomUUID(),amountMinor:125000,currency:'INR',merchant:'Test merchant',country:'IN',deviceId:'test-device',phoneNumber:'+12025550123',failedAttempts:0,occurredAt:new Date().toISOString()};
const post=body=>admin.request('/transactions',{method:'POST',headers:{'Idempotency-Key':id},body});
const [first,second]=await Promise.all([post(payload),post(payload)]);
assert.equal(first.status,202);assert.equal(second.status,202);assert.equal(first.data.id,second.data.id);assert.notEqual(first.data.duplicate,second.data.duplicate,'concurrent duplicate creates one event');
assert.equal((await post({...payload,amountMinor:125001})).status,409,'payload conflict');
assert.equal((await post({...payload,amountMinor:-1})).status,400,'invalid amount');
assert.equal((await post({...payload,currency:'USD'})).status,400,'unsupported currency');
const scenario=await admin.request('/simulations',{method:'POST',body:{scenario:'TAKEOVER'}});
assert.equal(scenario.status,200);
const attack=scenario.data.transactionIds.at(-1);
let transaction;
for(let attempt=0;attempt<40;attempt++){transaction=(await admin.request('/transactions/'+attack)).data;if(transaction.status==='SCORED')break;await new Promise(r=>setTimeout(r,500));}
assert.equal(transaction.status,'SCORED','background worker completes');
assert.equal(transaction.classification,'HIGH_RISK','takeover is high risk');
assert.equal(transaction.score,100);
assert.ok(JSON.parse(transaction.explanation).length>=4,'explanations persisted');
assert.equal(JSON.parse(transaction.features).historyCount,8,'point-in-time history');
const alerts=(await admin.request('/alerts')).data.filter(a=>a.transaction_id===attack);
assert.equal(alerts.length,1,'exactly one alert');
const alert=alerts[0];
assert.equal((await admin.request('/alerts/'+alert.id+'/action',{method:'POST',body:{version:0,action:'CLAIM'}})).status,200);
assert.equal((await admin.request('/alerts/'+alert.id+'/action',{method:'POST',body:{version:0,action:'RESOLVE',reason:'stale',outcome:'FALSE_POSITIVE'}})).status,409,'stale case version rejected');
assert.equal((await analyst.request('/alerts/'+alert.id+'/action',{method:'POST',body:{version:1,action:'CLAIM'}})).status,409,'another analyst cannot take assigned case');
assert.equal((await admin.request('/alerts/'+alert.id+'/notes',{method:'POST',body:{note:'Smoke test: correlated amount, device, location and failure signals.'}})).status,200);
assert.equal((await admin.request('/alerts/'+alert.id+'/action',{method:'POST',body:{version:1,action:'RESOLVE',reason:'Synthetic takeover scenario verified by smoke test',outcome:'CONFIRMED_FRAUD'}})).status,200);
assert.equal((await admin.request('/alerts/'+alert.id+'/notes')).data.length,1);
const audit=(await admin.request('/audit-events')).data;
assert.ok(audit.some(e=>e.action==='CASE_RESOLVE'&&e.target===alert.id),'resolution audited');
assert.equal((await admin.request('/transactions/'+randomUUID())).status,404);

for(const endpoint of ['/dashboard','/transactions','/rules','/jobs']){assert.equal((await admin.request(endpoint)).status,200,'dashboard support endpoint '+endpoint);}
console.log('PASS: authentication, CSRF, roles, concurrent idempotency, validation, scoring, explanations, case workflow, audit, dashboard, transaction list, policy and recovery endpoints.');

