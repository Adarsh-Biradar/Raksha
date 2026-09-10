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
const original=(await admin.request('/rules/lab')).data;
const emails=(await admin.request('/notifications/settings')).data;
const rules=(await admin.request('/rules/catalog')).data.filter(r=>['VELOCITY','LARGE_AMOUNT'].includes(r.code));
async function mail(enabled){const s=(await admin.request('/notifications/settings')).data;const r=await admin.request('/notifications/settings',{method:'PUT',body:{version:s.version,enabled,minimumClassification:s.minimum_classification,recipients:s.recipients}});assert.equal(r.status,200);}
async function setRule(rule,enabled){const current=(await admin.request('/rules/catalog')).data.find(r=>r.code===rule.code);assert.equal((await admin.request('/rules/catalog/'+rule.code,{method:'PUT',body:{version:current.version,enabled,points:rule.points,matchValues:rule.match_values}})).status,200);}
async function configure(extra){const s=(await admin.request('/rules/lab')).data;const r=await admin.request('/rules/lab',{method:'PUT',body:{version:s.version,mode:s.mode,repeatCount:s.repeat_count,windowMinutes:s.window_minutes,points:s.points,...extra}});assert.equal(r.status,200,JSON.stringify(r.data));return r.data;}
const account='lab-'+randomUUID();let index=0;
async function create(extra={}){
 const eventId='lab-'+randomUUID();
 const payload={eventId,accountId:account,amountMinor:10000,currency:'INR',merchant:'Rule Lab test merchant',country:'IN',deviceId:'lab-device',failedAttempts:0,occurredAt:new Date(Date.now()-120000+(index++*1000)).toISOString(),...extra};
 const r=await admin.request('/transactions',{method:'POST',headers:{'Idempotency-Key':eventId},body:payload});assert.equal(r.status,202,JSON.stringify(r.data));
 for(let i=0;i<50;i++){const row=(await admin.request('/transactions/'+r.data.id)).data;if(row.status==='SCORED')return row;if(row.status==='FAILED')throw new Error('Scoring failed');await new Promise(r=>setTimeout(r,250));}throw new Error('Scoring timeout');
}
async function preview(extra={}){
 const r=await admin.request('/rules/lab/preview',{method:'POST',body:{repeatCount:5,windowMinutes:5,points:100,accountId:account,...extra}});
 assert.equal(r.status,200,JSON.stringify(r.data));return r.data;
}
try{
 await mail(false);
 for(const rule of rules)await setRule(rule,false);
 for(const c of [analyst,viewer]){
  assert.equal((await c.request('/rules/lab')).status,403);
  assert.equal((await c.request('/rules/lab/preview',{method:'POST',body:{repeatCount:5,windowMinutes:5,points:100,accountId:''}})).status,403);
 }
 assert.equal((await admin.request('/rules/lab/preview',{method:'POST',body:{repeatCount:1,windowMinutes:5,points:100,accountId:''}})).status,400);
 const shadow=await configure({mode:'SHADOW',repeatCount:5,windowMinutes:5,points:100});
 assert.equal((await admin.request('/rules/lab',{method:'PUT',body:{version:original.version,mode:'ACTIVE',repeatCount:5,windowMinutes:5,points:100}})).status,409);
 const beforeEmails=(await admin.request('/notifications/deliveries')).data.length;
 for(let i=0;i<4;i++)await create();
 await create({amountMinor:20000});
 const fifth=await create();assert.equal(fifth.score,0,'Shadow has no score contribution');
 const observation=(await admin.request('/rules/lab/observations')).data.find(o=>o.transaction_id===fifth.id);
 assert.ok(observation);assert.equal(Number(observation.matching_count),5);assert.equal(observation.proposed_score,100);assert.equal(observation.rule_version,shadow.version);
 assert.ok(!(await admin.request('/alerts')).data.some(a=>a.transaction_id===fifth.id),'Shadow does not create a case');
 assert.equal((await admin.request('/notifications/deliveries')).data.length,beforeEmails);
 const auditBefore=(await admin.request('/audit-events')).data[0].id;
 const result=await preview();assert.equal(result.evaluated,6);assert.equal(result.matched,1);assert.equal(result.additionalReviews,1);assert.equal(result.samples[0].proposed_score,100);
 assert.equal((await admin.request('/audit-events')).data[0].id,auditBefore,'Preview has no audit mutation');
 assert.equal((await admin.request('/transactions/'+fifth.id)).data.score,0,'Preview keeps original assessment');
 await configure({mode:'ACTIVE',points:60});
 const active=await create();assert.equal(active.score,60);assert.ok(JSON.parse(active.features).matchedRules.includes('REPEATED_AMOUNT'));
 const replacement=await preview({points:90});assert.equal(replacement.samples.find(r=>r.id===active.id).proposed_score,90,'Replace contribution rather than double-add');
 assert.equal((await admin.request('/transactions/'+fifth.id)).data.score,0,'Activation keeps historic shadow scores');
 const other=await create({accountId:'other-'+randomUUID()});assert.equal(other.score,0,'Accounts isolated');
 // A history item just outside the window does not contribute.
 const boundaryAccount='boundary-'+randomUUID(),now=Date.now();
 await create({accountId:boundaryAccount,occurredAt:new Date(now-301000).toISOString()});
 const boundary=await create({accountId:boundaryAccount,occurredAt:new Date(now).toISOString()});
 assert.equal(JSON.parse(boundary.features).ruleLab.matching_count,1);
 // Equal event timestamps are ordered by receipt time / stable ID.
 await configure({repeatCount:2,mode:'SHADOW'});
 const equalAccount='equal-'+randomUUID(),sameTime=new Date(Date.now()-20000).toISOString();
 await create({accountId:equalAccount,occurredAt:sameTime});
 const equal=await create({accountId:equalAccount,occurredAt:sameTime});
 assert.equal(JSON.parse(equal.features).ruleLab.matching_count,2);
 // An older event received afterwards must not leak into historical replay.
 const lateAccount='late-'+randomUUID(),anchor=Date.now()-30000;
 const earlyReceipt=await create({accountId:lateAccount,occurredAt:new Date(anchor).toISOString()});
 await create({accountId:lateAccount,occurredAt:new Date(anchor-1000).toISOString()});
 const latePreview=await preview({accountId:lateAccount,repeatCount:2});
 assert.ok(!latePreview.samples.some(s=>s.id===earlyReceipt.id),'Later receipt excluded from earlier event history');
 await configure({mode:'OFF'});
 const off=await create();assert.equal(off.score,0);assert.equal(JSON.parse(off.features).ruleLab.mode,'OFF');
 console.log('PASS: equal-amount detection, differing amounts, account isolation, window boundary, equal timestamps, late-arrival causality, shadow isolation, active points, preview replacement, immutable history, access control and stale writes.');
}finally{
 await configure({mode:original.mode,repeatCount:original.repeat_count,windowMinutes:original.window_minutes,points:original.points});
 for(const rule of rules)await setRule(rule,rule.enabled);
 await mail(emails.enabled);
}
