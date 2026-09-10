import {useEffect,useState} from 'react';
type Row=Record<string,any>;
type Request=(path:string,options?:RequestInit)=>Promise<any>;
const rupees=(n:number)=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(n/100);
export function RuleLabScreen({request}:{request:Request}){
 const [rule,setRule]=useState<Row|null>(null),[draft,setDraft]=useState<Row|null>(null),[preview,setPreview]=useState<Row|null>(null),[observations,setObservations]=useState<Row[]>([]);
 const [account,setAccount]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 async function load(){const r=await request('/rules/lab');setRule(r);setDraft(r);setPreview(null)}
 useEffect(()=>{load().catch(e=>setError(e.message));let live=true;
 const poll=()=>request('/rules/lab/observations').then(rows=>{if(live)setObservations(rows)}).catch(e=>{if(live)setError(e.message)});
 poll();const timer=setInterval(poll,5000);return()=>{live=false;clearInterval(timer)}
 },[]);
 async function act(work:()=>Promise<void>){setBusy(true);setError('');setNotice('');try{await work()}catch(e){setError((e as Error).message)}finally{setBusy(false)}}
 if(!rule||!draft)return <section className="panel settings-form"><p>{error||'Loading Rule Lab…'}</p></section>;
 const candidate={repeatCount:Number(draft.repeat_count),windowMinutes:Number(draft.window_minutes),points:Number(draft.points),accountId:account.trim()};
 const edit=(key:string,value:any)=>{setDraft({...draft,[key]:value});setPreview(null)};
 const dirty=draft.mode!==rule.mode||Number(draft.repeat_count)!==rule.repeat_count||Number(draft.window_minutes)!==rule.window_minutes||Number(draft.points)!==rule.points;
 return <>
 {error&&<p role="alert" className="message error">{error}</p>}{notice&&<p role="status" className="message success">{notice}</p>}
 <div className="settings-grid"><form className="panel settings-form" onSubmit={e=>{e.preventDefault();act(async()=>{
 const updated=await request('/rules/lab',{method:'PUT',body:JSON.stringify({...candidate,version:rule.version,mode:draft.mode})});
 setRule(updated);setDraft(updated);setPreview(null);setNotice('Repeated-amount rule saved in '+updated.mode.toLowerCase()+' mode. Configuration version '+updated.version+'.')
 })}}>
 <div className="panel-heading"><h2>Repeated-amount payments</h2><span className="period">{rule.mode} · v{rule.version}</span></div>
 <p className="muted">Detect repeated payments of exactly the same amount and currency from one customer account, across merchants. This is different from counting all payments.</p>
 {[['repeat_count','Number of equal payments',2,20],['window_minutes','Time window (minutes)',1,60],['points','Risk points',1,100]].map(([key,label,min,max])=><label key={key}>{label}<input type="number" required min={min} max={max} step="1" value={draft[key]} onChange={e=>edit(String(key),e.target.value)}/></label>)}
 <label>Operating mode<select value={draft.mode} onChange={e=>edit('mode',e.target.value)}><option value="OFF">Off - do not evaluate</option><option value="SHADOW">Shadow - observe without changing scores</option><option value="ACTIVE">Active - add points to matched transactions</option></select></label>
 <p className="muted small">Shadow mode creates observations only. Other active rules can still generate alerts. Active mode can trigger investigation cases and your configured emails.</p>
 <div className="rule-actions"><button className="button primary" disabled={busy}>{busy?'Working…':'Save configuration'}</button><button type="button" className="button secondary" disabled={busy} onClick={()=>act(load)}>Reload</button></div><p className="muted small">{dirty?'Unsaved configuration changes.':'Saved configuration.'} Mode changes apply to future assessments; previous scores remain unchanged.</p>
 </form><section className="panel settings-form"><span className="eyebrow">TEST BEFORE ACTIVATING</span><h2>Preview historical impact</h2><p className="muted">Try the values on the left against the latest 500 scored transactions received within 30 days. Preview adds or replaces only this rule's contribution; it does not rerun every historical rule.</p>
 <label>Customer account ID (optional)<input maxLength={80} value={account} onChange={e=>{setAccount(e.target.value);setPreview(null)}} placeholder="Leave empty to evaluate all accounts"/></label>
 <button className="button secondary" disabled={busy} onClick={()=>act(async()=>{setPreview(await request('/rules/lab/preview',{method:'POST',body:JSON.stringify(candidate)}))})}>Preview impact</button>
 <p className="muted small">Preview treats your proposed rule as active, regardless of the selected operating mode. It changes no transactions, sends no emails and creates no cases.</p>
 <h3>Explain the pattern</h3><p>{candidate.repeatCount} payments of INR 100 from the same account within {candidate.windowMinutes} minutes match on payment {candidate.repeatCount}. A different amount does not count toward this pattern.</p><p className="muted small">Earlier events must have been received by the assessed event's receipt time. Equal event timestamps use receipt time and ID for stable ordering. This is a count within a window, not a requirement that the payments be consecutive.</p>
 </section></div>
 {preview&&<section className="panel settings-form" style={{marginTop:24}} aria-live="polite"><div className="panel-heading"><h2>Projected impact</h2><span className="period">Policy v{preview.policy.version}</span></div>
 <div className="lab-summary">{[['Evaluated',preview.evaluated],['Pattern matches',preview.matched],['Scores changed',preview.changed],['Additional review cases',preview.additionalReviews],['Fewer review cases',preview.fewerReviews]].map(([label,value])=><div key={label}><small>{label}</small><strong>{value}</strong></div>)}</div>
 <p className="muted">{preview.method}</p>{preview.limited&&<p>More than 500 eligible events exist; this is a limited sample.</p>}
 <p className="muted small">{preview.labeled} evaluated events have recorded investigation outcomes; {preview.knownFalsePositivesFlagged} previously marked false positives would still be flagged. Outcomes exist mainly for reviewed cases, so this is not an unbiased accuracy measure.</p>
 {!preview.evaluated?<p>No scored transactions in this scope. Create payments first or clear the account filter.</p>:!preview.samples.length?<p>No matches or score changes in this sample.</p>:<><p className="muted small">Up to 50 matching or changed events, newest first. Both classification columns use today's policy thresholds.</p><div className="table-wrap"><table><thead><tr><th>Account / Merchant</th><th>Amount</th><th>Equal payments</th><th>Recorded score</th><th>Proposed score</th><th>Reason</th></tr></thead><tbody>{preview.samples.map((r:Row)=><tr key={r.id}><td data-label="Account / Merchant">{r.account_id}<small>{r.merchant}</small></td><td data-label="Amount">{rupees(r.amount_minor)}</td><td data-label="Equal payments">{r.matching_count}</td><td data-label="Recorded score">{r.score}<small>{r.before_classification}</small></td><td data-label="Proposed score">{r.proposed_score}<small>{r.proposed_classification}</small></td><td data-label="Reason">{r.matched?'Same-amount window meets the proposed limit':'Previous contribution removed'}{r.additional_review&&<small>New review case</small>}</td></tr>)}</tbody></table></div></>}
 </section>}
 <section className="panel settings-form" style={{marginTop:24}}><h2>Live shadow observations</h2><p className="muted">Latest 100 matches recorded while in shadow mode, including earlier configuration versions. These observations do not themselves create cases or emails.</p>
 {!observations.length?<p>No shadow matches yet. Save Shadow mode and create repeated payments to compare actual and would-be scores.</p>:<div className="table-wrap"><table><thead><tr><th>Account / Merchant</th><th>Amount</th><th>Equal payments</th><th>Actual score</th><th>Would-be score</th><th>Rule version / Time</th></tr></thead><tbody>{observations.map(r=><tr key={r.transaction_id}><td data-label="Account / Merchant">{r.account_id}<small>{r.merchant}</small></td><td data-label="Amount">{rupees(r.amount_minor)}</td><td data-label="Equal payments">{r.matching_count}</td><td data-label="Actual score">{r.actual_score}</td><td data-label="Would-be score">{r.proposed_score}</td><td data-label="Rule version / Time">v{r.rule_version}<small>{new Date(r.created_at).toLocaleString()}</small></td></tr>)}</tbody></table></div>}
 </section></>
}
