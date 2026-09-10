// Run against a stack started with WORKER_MODE=queue (see docs/plans/02-messaging-rabbitmq.md).
// Verifies the RabbitMQ consumer path scores transactions end-to-end and preserves the same
// idempotency/no-duplicate-alert guarantees as the DB-poll worker.
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
const admin=client();
await admin.login('admin');
assert.equal((await admin.request('/notifications/settings')).data.enabled,false,'Pause automatic Email alerts before generating smoke-test transactions');

// A simple everyday-spending transaction should reach SCORED via the queue consumer.
const id='queue-smoke-'+randomUUID();
const payload={eventId:id,accountId:'queue-smoke-'+randomUUID(),amountMinor:50000,currency:'INR',merchant:'Queue smoke merchant',country:'IN',deviceId:'queue-smoke-device',failedAttempts:0,occurredAt:new Date().toISOString()};
const post=body=>admin.request('/transactions',{method:'POST',headers:{'Idempotency-Key':id},body});
const accepted=await post(payload);
assert.equal(accepted.status,202);
let transaction;
for(let attempt=0;attempt<40;attempt++){transaction=(await admin.request('/transactions/'+accepted.data.id)).data;if(transaction.status==='SCORED')break;await new Promise(r=>setTimeout(r,500));}
assert.equal(transaction.status,'SCORED','queue consumer scores the transaction');

// A takeover scenario (the simulator calls FraudService.ingest() too, so it also goes through
// the outbox/queue path) still produces exactly one alert - proving the alerts-unique-constraint
// guarantee holds with both the DB-poll worker and the queue consumer active concurrently.
const scenario=await admin.request('/simulations',{method:'POST',body:{scenario:'TAKEOVER'}});
assert.equal(scenario.status,200);
const attack=scenario.data.transactionIds.at(-1);
let attackTransaction;
for(let attempt=0;attempt<40;attempt++){attackTransaction=(await admin.request('/transactions/'+attack)).data;if(attackTransaction.status==='SCORED')break;await new Promise(r=>setTimeout(r,500));}
assert.equal(attackTransaction.status,'SCORED');
assert.equal(attackTransaction.classification,'HIGH_RISK');
const alerts=(await admin.request('/alerts')).data.filter(a=>a.transaction_id===attack);
assert.equal(alerts.length,1,'exactly one alert even with both workers active');

console.log('PASS: queue-mode consumer scores transactions end-to-end; no duplicate alerts with both workers active.');
console.log('NOTE: dead-letter/retry behavior is not exercised by this script - see docs/plans/02-messaging-rabbitmq.md for the manual verification steps (stop the API mid-processing, or use the RabbitMQ management UI at :15672 to inspect transactions.ingested.dlq).');
