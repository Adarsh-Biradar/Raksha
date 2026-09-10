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

const admin=client(),analyst=client(),viewer=client();
await admin.login('admin');await analyst.login('analyst');await viewer.login('viewer');
const emails=(await admin.request('/notifications/settings')).data;
const rule=(await admin.request('/rules/catalog')).data.find(r=>r.code==='BLOCKED_PHONE');
const account='hold-'+randomUUID();
const phone='+12025550999';
async function mail(enabled){const s=(await admin.request('/notifications/settings')).data;assert.equal((await admin.request('/notifications/settings',{method:'PUT',body:{version:s.version,enabled,minimumClassification:s.minimum_classification,recipients:s.recipients}})).status,200);}
async function config(value){const r=(await admin.request('/rules/catalog')).data.find(r=>r.code===rule.code);assert.equal((await admin.request('/rules/catalog/'+rule.code,{method:'PUT',body:{version:r.version,...value}})).status,200);}
function payload(extra={}){return {eventId:'hold-'+randomUUID(),accountId:account,amountMinor:10000,currency:'INR',merchant:'Account hold test',country:'IN',deviceId:'hold-device',failedAttempts:0,occurredAt:new Date().toISOString(),...extra};}
async function submit(p){const r=await admin.request('/transactions',{method:'POST',body:p,headers:{'Idempotency-Key':p.eventId}});assert.equal(r.status,202,JSON.stringify(r.data));return r.data;}
async function terminal(id){for(let i=0;i<60;i++){const response=await admin.request('/transactions/'+id);assert.equal(response.status,200,JSON.stringify(response.data));const r=response.data;if(r.status!=='PENDING')return r;await new Promise(r=>setTimeout(r,250));}throw Error('Timeout');}
async function action(c,a,body){return c.request('/alerts/'+a.id+'/action',{method:'POST',body:{version:a.version,...body}});}
let openCase;
try {
 await mail(false);await config({enabled:true,points:100,matchValues:phone});
 const first=payload({phoneNumber:phone});const high=await terminal((await submit(first)).id);assert.equal(high.score,100);assert.equal(high.account_holds.length,1);
 openCase=(await admin.request('/alerts')).data.find(a=>a.transaction_id===high.id);assert.ok(openCase.account_held);
 const repeat=await submit(first);assert.equal(repeat.id,high.id);assert.equal(repeat.duplicate,true);
 const attempt=payload();const blocked=await submit(attempt);assert.equal(blocked.status,'BLOCKED');assert.equal((await terminal(blocked.id)).score,null);
 assert.equal((await submit(attempt)).id,blocked.id);
 const changed=await admin.request('/transactions',{method:'POST',body:{...attempt,amountMinor:20000},headers:{'Idempotency-Key':attempt.eventId}});assert.equal(changed.status,409);
 const parallel=await Promise.all(Array.from({length:5},()=>submit(payload())));assert.ok(parallel.every(t=>t.status==='BLOCKED'));
 const other=await terminal((await submit(payload({accountId:'other-'+randomUUID()}))).id);assert.equal(other.status,'SCORED');assert.equal(other.account_holds.length,0);
 assert.equal((await action(viewer,openCase,{action:'RESOLVE',reason:'test',outcome:'FALSE_POSITIVE'})).status,403);
 assert.equal((await action(analyst,openCase,{action:'RESOLVE',reason:'test',outcome:'FALSE_POSITIVE'})).status,409);
 openCase=(await action(analyst,openCase,{action:'CLAIM'})).data;
 assert.equal((await action(admin,openCase,{action:'RESOLVE',reason:'test',outcome:'FALSE_POSITIVE'})).status,409);
 assert.equal((await action(analyst,openCase,{action:'RESOLVE',reason:'',outcome:'FALSE_POSITIVE'})).status,400);
 const resolved=await action(analyst,openCase,{action:'RESOLVE',reason:'Synthetic test investigated and cleared',outcome:'FALSE_POSITIVE'});assert.equal(resolved.status,200);openCase=null;
 assert.equal((await terminal(high.id)).account_holds.length,0);
 assert.equal((await submit(attempt)).status,'BLOCKED','Old blocked events never replay');
 assert.equal((await terminal((await submit(payload())).id)).status,'SCORED');
 const again=await terminal((await submit(payload({phoneNumber:phone}))).id);assert.equal(again.account_holds.length,1,'Account can be held again');
 openCase=(await admin.request('/alerts')).data.find(a=>a.transaction_id===again.id);
 openCase=(await action(analyst,openCase,{action:'CLAIM'})).data;
 assert.equal((await action(analyst,openCase,{action:'RESOLVE',reason:'Synthetic fraud investigated; release per hold policy',outcome:'CONFIRMED_FRAUD'})).status,200);openCase=null;
 const audit=(await admin.request('/audit-events')).data;
 assert.ok(audit.some(a=>a.action==='ACCOUNT_HELD'&&a.target===account));assert.ok(audit.some(a=>a.action==='ACCOUNT_HOLD_RELEASED'&&a.target===account));assert.ok(audit.some(a=>a.action==='TRANSACTION_BLOCKED'));
 console.log('PASS: hold at 100, concurrent blocking, account isolation, idempotency, authorization, claimed resolution, release, no replay, re-hold and audit.');
} finally {
 if(openCase){if(!openCase.assignee)openCase=(await action(analyst,openCase,{action:'CLAIM'})).data;await action(analyst,openCase,{action:'RESOLVE',reason:'Test cleanup',outcome:'FALSE_POSITIVE'});}
 await config({enabled:rule.enabled,points:rule.points,matchValues:rule.match_values});await mail(emails.enabled);
}

