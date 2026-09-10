import {useEffect,useState,type FormEvent} from 'react';
type Row=Record<string,any>;
type Request=(path:string,options?:RequestInit)=>Promise<any>;

export function RuleManager({request,onSaved}:{request:Request;onSaved:()=>void}){
 const [rules,setRules]=useState<Row[]>([]),[error,setError]=useState(''),[busy,setBusy]=useState(false),[notice,setNotice]=useState('');
 async function load(){try{setRules(await request('/rules/catalog'));setError('')}catch(e){setError((e as Error).message)}}
 useEffect(()=>{load()},[]);
 async function save(rule:Row,enabled=rule.enabled){
  setBusy(true);setError('');setNotice('');
  try{const updated=await request('/rules/catalog/'+rule.code,{method:'PUT',body:JSON.stringify({version:rule.version,enabled,points:Number(rule.points),matchValues:rule.match_values})});setRules(rows=>rows.map(r=>r.code===rule.code?updated:r));setNotice(updated.name+' '+(updated.enabled?'is active':'is paused')+'. Future assessments use this configuration.');onSaved()}
  catch(e){setError((e as Error).message)}finally{setBusy(false)}
 }
 return <section className="panel settings-form rule-manager"><div className="panel-heading"><div><h2>Detection rules</h2><p>{rules.filter(r=>r.enabled).length} active · {rules.filter(r=>!r.enabled).length} paused</p></div><button type="button" className="button secondary" disabled={busy} onClick={load}>Reload rules</button></div>
 <p className="muted">Pause a check to stop it contributing to future scores. Block-list matches flag a transaction for review; no money is moved. Changes are audited. Reload discards unsaved edits.</p>
 {error&&<p role="alert" className="message error">{error}</p>}{notice&&<p role="status" className="message success">{notice}</p>}
 <div className="rule-grid">{rules.map(rule=><form className="rule-card" key={rule.code} onSubmit={e=>{e.preventDefault();save(rule)}}>
 <div className="panel-heading"><h3>{rule.name}</h3><span className={'badge '+(rule.enabled?'normal':'pending')}>{rule.enabled?'Active':'Paused'}</span></div>
 <p className="muted">{rule.description}</p><small>Rule version {rule.version}</small>
 <label>Risk points<input type="number" min="1" max="100" step="1" required value={rule.points} onChange={e=>setRules(rows=>rows.map(r=>r.code===rule.code?{...r,points:e.target.value}:r))}/></label>
 {rule.code.startsWith('BLOCKED_')&&<label>{rule.code==='BLOCKED_IP'?'IPv4 addresses or CIDR ranges':'Phone numbers with country code'}<textarea rows={4} maxLength={10000} value={rule.match_values} placeholder={rule.code==='BLOCKED_IP'?'203.0.113.10\n198.51.100.0/24':'+919876543210\n+919876543211'} onChange={e=>setRules(rows=>rows.map(r=>r.code===rule.code?{...r,match_values:e.target.value}:r))}/><small>One entry per line, maximum 200. An empty list matches nothing.</small></label>}
 <div className="rule-actions"><button className="button primary" disabled={busy}>Save changes</button><button type="button" className="button secondary" disabled={busy} onClick={()=>save(rule,!rule.enabled)}>{rule.enabled?'Pause rule':'Resume rule'}</button></div></form>)}</div></section>
}

export function CreateTransaction({request,merchants}:{request:Request;merchants:string[]}){
 const initial=()=>({merchant:'',accountId:'',amount:'',country:'IN',deviceId:'',ipAddress:'',phoneNumber:'',failedAttempts:'0'});
 const [form,setForm]=useState(initial),[submitted,setSubmitted]=useState<Row|null>(null),[result,setResult]=useState<Row|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 useEffect(()=>{
  if(!result?.id||result.status==='SCORED'||result.status==='FAILED')return;
  let live=true;
  const poll=async()=>{try{const row=await request('/transactions/'+result.id);if(live){setResult(row);setError('')}}catch(e){if(live)setError((e as Error).message)}};
  const timer=setInterval(poll,1500);poll();return()=>{live=false;clearInterval(timer)};
 },[result?.id,result?.status]);
 async function submit(e:FormEvent){
  e.preventDefault();setError('');setBusy(true);
  try{
   let payload=submitted;
   if(!payload){
    if(!/^\d+(\.\d{1,2})?$/.test(form.amount))throw new Error('Enter a positive INR amount with at most two decimal places');
    const [whole,fraction='']=form.amount.split('.');
    const amountMinor=Number(whole)*100+Number(fraction.padEnd(2,'0'));
    if(!Number.isSafeInteger(amountMinor)||amountMinor<1||amountMinor>100000000000)throw new Error('Amount must be between ₹0.01 and ₹1,000,000,000');
    payload={eventId:'ui-'+crypto.randomUUID(),accountId:form.accountId,amountMinor,currency:'INR',merchant:form.merchant.trim(),country:form.country.toUpperCase(),deviceId:form.deviceId,failedAttempts:Number(form.failedAttempts),occurredAt:new Date().toISOString(),ipAddress:form.ipAddress||null,phoneNumber:form.phoneNumber||null};
    setSubmitted(payload);
   }
   const accepted=await request('/transactions',{method:'POST',headers:{'Idempotency-Key':payload.eventId},body:JSON.stringify(payload)});
   setResult(accepted);
  }catch(e){setError((e as Error).message);if((e as {status?:number}).status===400)setSubmitted(null)}finally{setBusy(false)}
 }
 const fields=[['merchant','Merchant name','text'],['accountId','Customer account ID','text'],['amount','Amount (INR)','text'],['country','Country code','text'],['deviceId','Device ID','text'],['ipAddress','Customer IPv4 address (optional)','text'],['phoneNumber','Customer phone with country code (optional)','tel'],['failedAttempts','Preceding failed attempts','number']];
 let reasons:string[]=[];try{reasons=JSON.parse(result?.explanation||'[]')}catch{}
 return <div className="settings-grid"><form className="panel settings-form" onSubmit={submit}><h2>Create merchant transaction</h2><p className="muted">Simulated INR payment. Active rules are applied automatically by the processing queue. IP and phone are supplied transaction signals.</p>
 <fieldset disabled={busy||Boolean(submitted)} className="transaction-fields">{fields.map(([key,label,type])=><label key={key}>{label}<input type={type} inputMode={key==='amount'?'decimal':undefined} list={key==='merchant'?'merchant-options':undefined} required={!['ipAddress','phoneNumber'].includes(key)} maxLength={key==='merchant'?100:key==='country'?2:key==='phoneNumber'?40:key==='ipAddress'?45:80} pattern={['accountId','deviceId'].includes(key)?'[A-Za-z0-9_-]{1,80}':key==='country'?'[A-Za-z]{2}':undefined} min={key==='failedAttempts'?0:undefined} max={key==='failedAttempts'?100:undefined} step={key==='failedAttempts'?1:undefined} value={form[key as keyof typeof form]} onChange={e=>setForm({...form,[key]:e.target.value})}/></label>)}</fieldset>
 <datalist id="merchant-options">{[...new Set(merchants)].map(m=><option key={m} value={m}/>)}</datalist>
 {error&&<p className="message error" role="alert">{error}</p>}
 {!result&&<button className="button primary" disabled={busy}>{busy?'Submitting…':submitted?'Retry same transaction':'Create transaction'}</button>}
 {submitted&&<><p className="muted small">Event: {submitted.eventId}. Retrying reuses this event to prevent duplicates.</p><button type="button" className="button secondary" disabled={busy} onClick={()=>{setSubmitted(null);setResult(null);setError('');setForm(initial())}}>Start a new transaction</button></>}
 </form><section className="panel settings-form" aria-live="polite"><h2>Rule evaluation</h2>{!result?<p className="muted">Submit a transaction to see its processing status, score and reasons here.</p>:<><span className={'badge '+(result.classification||result.status).toLowerCase()}>{result.classification||result.status}</span><h3>{result.merchant||submitted?.merchant}</h3><p>Risk score: <strong>{result.score??'Pending'}</strong> / 100</p><p>Policy version: {result.policy_version??'Pending'}</p>{reasons.map((r,i)=><p key={i}>{r}</p>)}{result.status==='PENDING'&&<p>Waiting for the scoring worker…</p>}{result.status==='FAILED'&&<p>Processing failed. An administrator can inspect the recovery queue.</p>}<p className="muted small">A risk score prioritizes investigation. This sandbox does not transfer or decline real money.</p></>}</section></div>
}
