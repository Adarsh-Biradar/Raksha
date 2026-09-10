import {execFileSync} from 'node:child_process';
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
assert.equal((await admin.request('/payments/config')).data.configured,false);
assert.deepEqual((await admin.request('/payments')).data,[]);
const id=randomUUID();
assert.equal((await viewer.request('/payments/'+id+'/checkout',{method:'POST'})).status,403);
assert.equal((await admin.request('/payments/'+id+'/checkout',{method:'POST'})).status,503);
const anonymous=client();assert.equal((await anonymous.request('/payments')).status,401);
assert.equal((await anonymous.request('/payments/'+id+'/checkout',{method:'POST'})).status,403);
const webhook=await anonymous.request('/payments/webhook',{method:'POST',headers:{'x-razorpay-event-id':'evt_security','X-Razorpay-Signature':'0'.repeat(64)},body:{event:'payment.captured'}});
assert.equal(webhook.status,503,'Webhook reaches signature handler without session/CSRF but disabled gateway rejects it');
console.log('PASS: HTTP payment routes, missing-key state, viewer and anonymous restrictions, webhook-only CSRF exception.');
