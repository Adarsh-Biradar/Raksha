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

if(process.env.SEND_TEST_MAIL!=='true')throw new Error('This check sends two real emails. Set SEND_TEST_MAIL=true only with authorization.');
const admin=client(),analyst=client(),viewer=client();
await admin.login('admin');await analyst.login('analyst');await viewer.login('viewer');
const recipient=env.SMTP_USERNAME;
assert.ok(recipient);
let settings=(await admin.request('/notifications/settings')).data;
assert.ok(settings.smtp.configured,'SMTP credentials present');
assert.ok(!JSON.stringify(settings).includes(env.SMTP_PASSWORD),'SMTP secret is not returned');
for(const c of [analyst,viewer]){
 assert.equal((await c.request('/notifications/settings')).status,403);
 assert.equal((await c.request('/notifications/test',{method:'POST'})).status,403);
 assert.equal((await c.request('/notifications/settings',{method:'PUT',body:{version:settings.version,enabled:true,minimumClassification:'HIGH_RISK',recipients:recipient}})).status,403);
}
const settingBody=(s,extra={})=>({version:s.version,enabled:s.enabled,minimumClassification:s.minimum_classification,recipients:s.recipients,...extra});
async function save(extra){
 settings=(await admin.request('/notifications/settings')).data;
 const r=await admin.request('/notifications/settings',{method:'PUT',body:settingBody(settings,extra)});
 assert.equal(r.status,200,JSON.stringify(r.data));settings=r.data;return settings;
}
assert.equal((await admin.request('/notifications/settings',{method:'PUT',body:settingBody(settings,{recipients:'bad-address'})})).status,400);
const stale=settings;
await save({enabled:false,recipients:recipient,minimumClassification:'HIGH_RISK'});
assert.equal((await admin.request('/notifications/settings',{method:'PUT',body:settingBody(stale)})).status,409);
const test=await admin.request('/notifications/test',{method:'POST'});
assert.equal(test.status,200,JSON.stringify(test.data));assert.equal(test.data.queued,1);
assert.equal((await admin.request('/notifications/test',{method:'POST'})).status,429,'test cooldown');
async function waitMail(id){
 for(let i=0;i<45;i++){
  const row=(await admin.request('/notifications/deliveries')).data.find(r=>r.id===id);
  if(row?.state==='SENT'){console.log('SMTP accepted '+row.classification+' email; notification '+row.id);return row;}
  if(row?.attempts>0&&row.last_error)throw new Error(row.last_error);
  await new Promise(r=>setTimeout(r,1000));
 }
 throw new Error('Email status timeout');
}
await waitMail(test.data.ids[0]);
const rule=(await admin.request('/rules/catalog')).data.find(r=>r.code==='BLOCKED_IP');
async function setRule(extra){
 const r=(await admin.request('/rules/catalog')).data.find(r=>r.code==='BLOCKED_IP');
 const response=await admin.request('/rules/catalog/BLOCKED_IP',{method:'PUT',body:{version:r.version,enabled:r.enabled,points:r.points,matchValues:r.match_values,...extra}});
 assert.equal(response.status,200);
}
async function create(){
 const eventId='email-check-'+randomUUID();
 const payload={eventId,accountId:'email-'+randomUUID(),amountMinor:10000,currency:'INR',merchant:'Email verification merchant',country:'IN',deviceId:'email-test',phoneNumber:'+12025550123',failedAttempts:0,occurredAt:new Date().toISOString(),ipAddress:'203.0.113.238'};
 const response=await admin.request('/transactions',{method:'POST',headers:{'Idempotency-Key':eventId},body:payload});assert.equal(response.status,202);
 for(let i=0;i<40;i++){const row=(await admin.request('/transactions/'+response.data.id)).data;if(row.status==='SCORED')return {row,payload};await new Promise(r=>setTimeout(r,300));}
 throw new Error('Scoring timeout');
}
let completed=false;
try {
 await setRule({enabled:true,points:100,matchValues:'203.0.113.238'});
 await save({enabled:true});
 const {row,payload}=await create();assert.equal(row.score,100);
 let delivery=(await admin.request('/notifications/deliveries')).data.find(d=>d.transaction_id===row.id);
 assert.ok(delivery,'Scoring committed email outbox entry');
 await waitMail(delivery.id);
 const duplicate=await admin.request('/transactions',{method:'POST',headers:{'Idempotency-Key':payload.eventId},body:payload});assert.equal(duplicate.data.duplicate,true);
 assert.equal((await admin.request('/notifications/deliveries')).data.filter(d=>d.transaction_id===row.id).length,1);
 await save({enabled:false});
 const paused=await create();assert.equal(paused.row.score,100,'Scoring continues while email paused');
 assert.equal((await admin.request('/notifications/deliveries')).data.filter(d=>d.transaction_id===paused.row.id).length,0);
 completed=true;
 console.log('PASS: admin-only settings, secret protection, validation, optimistic locking, cooldown, Gmail test, high-risk outbox delivery, deduplication and pause behavior.');
} finally {
 await setRule({enabled:rule.enabled,points:rule.points,matchValues:rule.match_values});
 await save({enabled:completed,minimumClassification:'HIGH_RISK',recipients:recipient});
}

