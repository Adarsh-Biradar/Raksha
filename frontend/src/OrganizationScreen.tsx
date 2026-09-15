import {useEffect,useState} from 'react';
type Row=Record<string,any>;
type Request=(path:string,options?:RequestInit)=>Promise<any>;
export function OrganizationScreen({request,canManage}:{request:Request;canManage:boolean}){
 const [org,setOrg]=useState<Row|null>(null),[name,setName]=useState(''),[usage,setUsage]=useState<Row|null>(null);
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 async function reload(){const [o,u]=await Promise.all([request('/organization'),request('/billing/usage')]);setOrg(o);setName(o.name);setUsage(u)}
 useEffect(()=>{reload().catch(e=>setError(e.message))},[]);
 async function act(work:()=>Promise<void>){setBusy(true);setError('');setNotice('');try{await work()}catch(e){setError((e as Error).message)}finally{setBusy(false)}}
 if(!org||!usage)return <section className="panel settings-form">{error?<p role="alert">{error}</p>:<p>Loading organization…</p>}</section>;
 const percent=usage.cap==null?0:Math.min(100,Math.round((usage.used/usage.cap)*100));
 return <>
 {error&&<div className="message error" role="alert">{error}</div>}{notice&&<div className="message success" role="status">{notice}</div>}
 <div className="settings-grid">
 <form className="panel settings-form" onSubmit={e=>{e.preventDefault();act(async()=>{
  const updated=await request('/organization',{method:'PUT',body:JSON.stringify({name})});setOrg(updated);setName(updated.name);setNotice('Organization name saved.')
 })}}>
  <div className="panel-heading"><h2>Organization</h2><span className="period">{org.slug}</span></div>
  <p className="muted">Every transaction, alert, rule and notification setting in this workspace is scoped to this organization only.</p>
  <label>Organization name<input required maxLength={100} value={name} disabled={!canManage} onChange={e=>setName(e.target.value)}/></label>
  {canManage&&<div className="rule-actions"><button className="button primary" disabled={busy||name===org.name}>Save name</button></div>}
  {!canManage&&<p className="muted small">Only an administrator can rename the organization.</p>}
 </form>
 <section className="panel settings-form">
  <div className="panel-heading"><h2>Plan &amp; usage</h2><span className="period">{usage.plan}</span></div>
  <p className="muted">Transactions created via <code>POST /api/transactions</code> (including simulator runs) count against this organization's monthly plan.</p>
  <div className="usage-bar" style={{background:'var(--surface-2,#eee)',borderRadius:8,height:10,overflow:'hidden',margin:'12px 0'}}><div style={{width:percent+'%',height:'100%',background:percent>=100?'var(--danger,#d33)':'var(--accent,#2a7)'}}/></div>
  <p>{usage.used} transactions this period ({usage.period}){usage.cap!=null?' of '+usage.cap+' ('+usage.remaining+' remaining)':' · unlimited'}</p>
  {canManage&&<div className="rule-actions">{['FREE','PRO','ENTERPRISE'].map(plan=><button key={plan} type="button" className={'button '+(plan===usage.plan?'primary':'secondary')} disabled={busy||plan===usage.plan} onClick={()=>act(async()=>{const updated=await request('/billing/plan',{method:'PUT',body:JSON.stringify({plan})});setUsage(updated);setNotice('Plan changed to '+plan+'.')})}>{plan}</button>)}</div>}
  <p className="muted small">FREE is capped at 500 transactions/month, PRO at 10,000/month. ENTERPRISE is unlimited. A cap breach returns HTTP 402 from the create-transaction API.</p>
 </section>
 </div>
 </>
}
