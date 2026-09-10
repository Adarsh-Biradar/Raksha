import {RuleLabScreen} from './RuleLabScreen';
import {auditCsv} from './auditCsv';
import {NotificationSettings} from './NotificationSettings';
import {RuleManager,CreateTransaction} from './RuleScreens';
import {useEffect,useState, type FormEvent} from 'react';
import {ShieldCheck,LayoutDashboard,ArrowLeftRight,ScanLine,SlidersHorizontal,ScrollText,LogOut,Search,ArrowUpRight,ChevronRight,Check,Plus,Activity,Clock,TriangleAlert,X,RefreshCw,Play,CheckCircle2,Inbox,LockKeyhole,Moon,Sun,Menu,Download} from 'lucide-react';

type Row=Record<string,any>;
let csrf:{token:string;headerName:string}|null=null;
async function getCsrf(){const response=await fetch('/api/csrf');if(!response.ok)throw new Error('Cannot connect to the server');csrf=await response.json();}
async function api(path:string,options:RequestInit={}):Promise<any>{
 const method=options.method||'GET';
 if(method!=='GET'&&!csrf)await getCsrf();
 const headers:Record<string,string>={'Content-Type':'application/json',...(options.headers as Record<string,string>||{})};
 if(method!=='GET'&&csrf)headers[csrf.headerName]=csrf.token;
 const response=await fetch('/api'+path,{...options,headers});
 if(!response.ok){let message='Request failed ('+response.status+')';try{const data=await response.json();message=data.message||message;}catch{}
 if(response.status===401)window.dispatchEvent(new Event('session-expired'));
 throw Object.assign(new Error(message),{status:response.status});}
 return response.status===204?null:response.json();
}
const money=(value:number)=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:0}).format(value/100);
const date=(value:string)=>new Date(value).toLocaleString('en-IN',{day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit'});
const pretty=(value:string)=>value?.replaceAll('_',' ').toLowerCase()||'pending';
function Badge({value}:{value:string}){return <span className={'badge '+(value||'PENDING').toLowerCase()}><i/>{pretty(value)}</span>}
function parsed(value:any,fallback:any){try{return typeof value==='string'?JSON.parse(value):value||fallback}catch{return fallback}}


type Theme='light'|'dark';
function ThemeToggle({theme,toggle}:{theme:Theme;toggle:()=>void}){
 return <button type="button" className="theme-toggle" aria-label={theme==='dark'?'Switch to light mode':'Switch to night mode'} title={theme==='dark'?'Switch to light mode':'Switch to night mode'} onClick={toggle}>{theme==='dark'?<Sun size={19}/>:<Moon size={19}/>}<span>{theme==='dark'?'Light mode':'Night mode'}</span></button>
}
export default function App(){
 const [theme,setTheme]=useState<Theme>(()=>document.documentElement.dataset.theme==='dark'?'dark':'light');
 const [mobileOpen,setMobileOpen]=useState(false);
 const [isMobile,setIsMobile]=useState(()=>window.matchMedia('(max-width:850px)').matches);
 useEffect(()=>{
  const media=window.matchMedia('(max-width:850px)');
  const change=()=>{setIsMobile(media.matches);if(!media.matches)setMobileOpen(false);};
  media.addEventListener('change',change);return()=>media.removeEventListener('change',change);
 },[]);
 useEffect(()=>{
  document.documentElement.dataset.theme=theme;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content',theme==='dark'?'#0d1713':'#f7f9f8');
 },[theme]);
 function toggleTheme(){const next=theme==='dark'?'light':'dark';setTheme(next);try{localStorage.setItem('raksha-theme',next)}catch{}}

 const [user,setUser]=useState<Row|null>(null),[checking,setChecking]=useState(true),[page,setPage]=useState('Overview');
 const [stats,setStats]=useState<Row>({}),[transactions,setTransactions]=useState<Row[]>([]),[alerts,setAlerts]=useState<Row[]>([]);
 const [audit,setAudit]=useState<Row[]>([]),[jobs,setJobs]=useState<Row[]>([]),[policy,setPolicy]=useState<Row|null>(null);
 const [search,setSearch]=useState(''),[filter,setFilter]=useState('ALL'),[pagination,setPagination]=useState(0);
 const [error,setError]=useState(''),[notice,setNotice]=useState(''),[busy,setBusy]=useState(false),[selected,setSelected]=useState<Row|null>(null),[selectedAlert,setSelectedAlert]=useState<Row|null>(null);
 const [notes,setNotes]=useState<Row[]>([]),[reason,setReason]=useState(''),[note,setNote]=useState(''),[outcome,setOutcome]=useState('FALSE_POSITIVE');
 const [lastUpdate,setLastUpdate]=useState<Date|null>(null);
 const canEdit=user?.role!=='VIEWER';
 useEffect(()=>{
  if(!selected&&!(mobileOpen&&isMobile))return;
  const panel=document.getElementById(selected?'transaction-dialog':'workspace-navigation');
  const previous=document.activeElement as HTMLElement|null;
  const oldOverflow=document.body.style.overflow;document.body.style.overflow='hidden';
  const focusable=()=>Array.from(panel?.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input,select,textarea,[tabindex="0"]')||[]).filter(el=>el.getClientRects().length>0);
  const frame=requestAnimationFrame(()=>focusable()[0]?.focus());
  const key=(e:KeyboardEvent)=>{
   if(e.key==='Escape'){e.preventDefault();if(selected)setSelected(null);else setMobileOpen(false);}
   if(e.key==='Tab'){const nodes=focusable();const first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}}
  };
  document.addEventListener('keydown',key);
  return()=>{cancelAnimationFrame(frame);document.body.style.overflow=oldOverflow;document.removeEventListener('keydown',key);previous?.focus();};
 },[Boolean(selected),mobileOpen,isMobile]);

 useEffect(()=>{api('/auth/me').then(setUser).catch(()=>{}).finally(()=>setChecking(false));const handler=()=>{setUser(null);setMobileOpen(false);csrf=null;setSelected(null);setPolicy(null);};window.addEventListener('session-expired',handler);return()=>window.removeEventListener('session-expired',handler)},[]);
 async function refresh(){
  if(!user)return;
  const [s,t,a]=await Promise.all([api('/dashboard'),api('/transactions?search='+encodeURIComponent(search)+'&classification='+filter+'&page='+pagination),api('/alerts')]);
  setStats(s);setTransactions(t);setAlerts(a);setLastUpdate(new Date());
  if(user.role==='ADMIN'&&page==='Audit trail')setAudit(await api('/audit-events'));
  if(user.role==='ADMIN'&&page==='Rules & settings')setJobs(await api('/jobs'));
 }
 useEffect(()=>{if(!user)return;let live=true;const run=()=>{if(live)refresh().catch(e=>setError(e.message));};run();const interval=setInterval(run,4000);return()=>{live=false;clearInterval(interval)}},[user,page,search,filter,pagination]);
 useEffect(()=>{if(user?.role==='ADMIN'&&page==='Rules & settings')api('/rules').then(setPolicy).catch(e=>setError(e.message))},[page,user]);
 useEffect(()=>{if(notice){const timer=setTimeout(()=>setNotice(''),6500);return()=>clearTimeout(timer)}},[notice]);
 async function act(work:()=>Promise<void>){setBusy(true);setError('');try{await work();await refresh()}catch(e){setError((e as Error).message)}finally{setBusy(false)}}
 async function openTransaction(id:string,alert:Row|null=null){
  try{setSelected(await api('/transactions/'+id));setSelectedAlert(alert);setReason('');setNote('');setNotes(alert?await api('/alerts/'+alert.id+'/notes'):[])}catch(e){setError((e as Error).message)}
 }
 function downloadAudit(){
  const url=URL.createObjectURL(new Blob([auditCsv(audit)],{type:'text/csv;charset=utf-8'}));
  const link=document.createElement('a');link.href=url;link.download='raksha-audit-'+new Date().toISOString().replaceAll(':','-')+'.csv';
  document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
  setNotice('Downloaded '+audit.length+' audit records from the current view.');
 }

 async function simulate(scenario:string){await act(async()=>{const result=await api('/simulations',{method:'POST',body:JSON.stringify({scenario})});setNotice(result.transactionIds.length+' transactions queued for '+result.accountId);})}
 async function caseAction(action:string){if(!selectedAlert)return;await act(async()=>{
  const updated=await api('/alerts/'+selectedAlert.id+'/action',{method:'POST',body:JSON.stringify({version:selectedAlert.version,action,reason,outcome})});setSelectedAlert(updated);if(selected)setSelected(await api('/transactions/'+selected.id));setNotice(action==='CLAIM'?'Case assigned to you':'Case resolved and recorded in audit history');
 })}
 if(checking)return <div className="loading"><ShieldCheck size={40}/><p>Connecting to Raksha…</p></div>;
 if(!user)return <Login theme={theme} toggleTheme={toggleTheme} onLogin={async()=>{csrf=null;await getCsrf();setUser(await api('/auth/me'));setError('')}}/>;
 const nav=[{name:'Overview',icon:LayoutDashboard},{name:'Transactions',icon:ArrowLeftRight},...(canEdit?[{name:'Create transaction',icon:Plus}]:[]),{name:'Investigations',icon:ScanLine},{name:'Simulator',icon:Play},...(user.role==='ADMIN'?[{name:'Rules & settings',icon:SlidersHorizontal},{name:'Rule Lab',icon:Activity},{name:'Email alerts',icon:Inbox},{name:'Audit trail',icon:ScrollText}]:[])];
 const titles:Record<string,string>={'Overview':'Your financial activity, in focus.','Transactions':'Every transaction. A clearer picture.','Create transaction':'Submit a merchant payment and inspect its risk signals.','Investigations':'Turn signals into decisions.','Simulator':'Put your defenses to the test.','Rules & settings':'Define what deserves attention.','Rule Lab':'Measure a rule before you activate it.','Email alerts':'Keep management informed of suspicious activity.','Audit trail':'Every action, accounted for.'};
 return <div className="shell">
  {mobileOpen&&isMobile&&<button className="mobile-scrim" aria-label="Close navigation" tabIndex={-1} onClick={()=>setMobileOpen(false)}/>}
  <aside id="workspace-navigation" className={'sidebar'+(mobileOpen?' mobile-open':'')} aria-label="Workspace navigation" role={isMobile&&mobileOpen?'dialog':undefined} aria-modal={isMobile&&mobileOpen?true:undefined} inert={isMobile&&!mobileOpen} aria-hidden={isMobile&&!mobileOpen}>
   <button className="mobile-nav-close" aria-label="Close navigation" onClick={()=>setMobileOpen(false)}><X size={21}/></button>
   <a className="brand" href="#" onClick={e=>{e.preventDefault();setPage('Overview');setMobileOpen(false)}}><span className="brand-mark"><ShieldCheck size={23}/></span>Raksha<span className="brand-dot">.</span></a>
   <div className="workspace"><span className="workspace-avatar">R</span><div><strong>Raksha</strong><small>Transaction intelligence</small></div><span className="workspace-dot"/></div>
   <div className="nav-caption">WORKSPACE</div>
   <nav>{nav.map(({name,icon:Icon})=><button key={name} className={page===name?'active':''} aria-current={page===name?'page':undefined} onClick={()=>{setPage(name);setError('');setMobileOpen(false)}}><Icon size={19}/>{name}{name==='Investigations'&&Number(stats.open_alerts)>0&&<span className="nav-count">{stats.open_alerts}</span>}</button>)}</nav>
   <div className="sidebar-bottom"><div className="sandbox-card"><ShieldCheck size={21}/><strong>Built for safer decisions</strong><p>Simulated data. Explainable signals. Human judgment.</p></div>
   <div className="profile"><span className="avatar">{user.role.slice(0,1)}</span><div><strong>{pretty(user.role)}</strong><small>{user.email}</small></div><button aria-label="Sign out" onClick={()=>act(async()=>{await api('/auth/logout',{method:'POST'});setUser(null);setMobileOpen(false);csrf=null})}><LogOut size={17}/></button></div></div>
  </aside>
  <main inert={(mobileOpen&&isMobile)||Boolean(selected)}>
   <header className="topbar"><div><button className="mobile-menu-button" aria-label="Open navigation" aria-expanded={mobileOpen} aria-controls="workspace-navigation" onClick={()=>setMobileOpen(true)}><Menu size={21}/></button><span className="topbar-brand">Raksha</span><span className="desktop-workspace">Workspace</span> <ChevronRight size={14}/> <strong>{page}</strong></div><div className="top-right"><ThemeToggle theme={theme} toggle={toggleTheme}/><span className="live"><i/> {lastUpdate?'Connected':'Connecting'}</span></div></header>
   <div className="content">
    <div className="page-heading"><div><div className="eyebrow">TRANSACTION INTELLIGENCE</div><h1>{page}</h1><p>{titles[page]}</p></div><div className="heading-actions"><button className="button secondary icon-button" aria-label="Refresh data" onClick={()=>act(refresh)} disabled={busy}><RefreshCw size={17}/></button>{canEdit&&<button className="button primary" aria-label="Create transaction" onClick={()=>setPage('Create transaction')}><Plus size={17}/> Create transaction</button>}</div></div>
    {error&&<div role="alert" className="message error"><TriangleAlert size={18}/>{error}<button aria-label="Dismiss error" onClick={()=>setError('')}><X size={17}/></button></div>}
    {notice&&<div role="status" className="message success"><CheckCircle2 size={18}/>{notice}</div>}
    {page==='Overview'&&<>
     <div className="metrics">
      {[{label:'Total transactions',value:stats.total??0,sub:money(Number(stats.volume_minor||0))+' monitored',icon:ArrowLeftRight,tone:'green'},{label:'Open investigations',value:stats.open_alerts??0,sub:'Awaiting an analyst decision',icon:ScanLine,tone:'amber'},{label:'High-risk transactions',value:stats.high_risk??0,sub:'Prioritized for human review',icon:TriangleAlert,tone:'red'},{label:'Pending analysis',value:stats.pending??0,sub:stats.dead_jobs?stats.dead_jobs+' jobs require attention':'Background processing active',icon:Activity,tone:'blue'}].map(({label,value,sub,icon:Icon,tone})=><section className="metric" key={label}><div className="metric-top">{label}<span className={'metric-icon '+tone}><Icon size={18}/></span></div><strong>{Number(value).toLocaleString()}</strong><small>{sub}</small></section>)}
     </div>
     <div className="overview-middle"><section className="panel chart-panel"><div className="panel-heading"><div><h2>Activity at a glance</h2><p>Transaction volume over the last 7 days</p></div><span className="period">Last 7 days</span></div><Trend rows={stats.trend||[]}/><div className="chart-legend"><span><i/> All transactions</span><span><i/> Flagged for review</span></div></section>
     <section className="panel posture"><div className="panel-heading"><h2>Detection overview</h2><ShieldCheck size={19}/></div><div className="posture-visual"><ShieldCheck size={36}/></div><h3>Context behind every signal.</h3><p>Behavioral history and configurable rules explain why activity needs attention.</p><div className="posture-line"><span>Detection method</span><strong>Rules + statistics</strong></div><div className="posture-line"><span>Confirmed fraud</span><strong>{stats.confirmed_fraud??0}</strong></div><div className="posture-line"><span>Financial environment</span><strong className="green-text">Simulation only</strong></div></section></div>
     <section className="panel"><div className="panel-heading"><div><h2>Recent transactions</h2><p>A live view of incoming financial activity</p></div><button className="text-button" onClick={()=>setPage('Transactions')}>View all <ArrowUpRight size={16}/></button></div><Transactions rows={transactions.slice(0,6)} onOpen={openTransaction}/></section>
    </>}
    {page==='Transactions'&&<section className="panel"><div className="panel-heading"><div className="search"><Search size={17}/><input aria-label="Search transactions" placeholder="Search account, merchant, event…" value={search} onChange={e=>{setSearch(e.target.value);setPagination(0)}}/></div><select aria-label="Filter risk" value={filter} onChange={e=>{setFilter(e.target.value);setPagination(0)}}>{['ALL','NORMAL','SUSPICIOUS','HIGH_RISK','PENDING','FAILED','BLOCKED'].map(x=><option key={x} value={x}>{pretty(x)}</option>)}</select></div><Transactions rows={transactions} onOpen={openTransaction}/><div className="pagination"><button className="button secondary" disabled={pagination===0} onClick={()=>setPagination(pagination-1)}>Previous</button><span>Page {pagination+1} · up to 50 results</span><button className="button secondary" disabled={transactions.length<50} onClick={()=>setPagination(pagination+1)}>Next</button></div></section>}
    {page==='Investigations'&&<section className="panel"><div className="panel-heading"><div><h2>Investigation queue</h2><p>Highest risk first · latest 100 cases</p></div><span className="period">{stats.open_alerts||0} open</span></div>{alerts.length===0?<Empty title="All clear for now" text="Flagged transactions will appear here. Run a takeover scenario to create your first case."/>:<div className="table-wrap"><table><thead><tr><th>Account / Merchant</th><th>Amount</th><th>Risk</th><th>Status</th><th>Assigned to</th><th/></tr></thead><tbody>{alerts.map(a=><tr key={a.id}><td data-label="Account / Merchant"><strong>{a.account_id}</strong><small>{a.merchant}</small>{a.account_held&&<span className="badge blocked">Account held</span>}</td><td data-label="Amount">{money(a.amount_minor)}</td><td data-label="Risk"><span className="risk-number">{a.score}</span><Badge value={a.classification}/></td><td data-label="Status"><Badge value={a.status}/></td><td data-label="Assigned to">{a.assignee?.split('@')[0]||'Unassigned'}</td><td data-label="Details"><button className="text-button" onClick={()=>openTransaction(a.transaction_id,a)}>Investigate <ArrowUpRight size={15}/></button></td></tr>)}</tbody></table></div>}</section>}
    {page==='Simulator'&&<><div className="info-banner"><ShieldCheck size={23}/><div><strong>A safe place to test real failure patterns.</strong><p>Each scenario seeds eight historical transactions and then submits new activity. All amounts are simulated INR.</p></div></div><div className="scenario-grid">{[{id:'NORMAL',title:'Everyday spending',tag:'BASELINE',text:'A familiar device and a typical purchase. Validate that normal behavior stays quiet.',icon:CheckCircle2},{id:'TAKEOVER',title:'Account takeover',tag:'HIGH RISK',text:'A large payment, unfamiliar device, new country, and repeated failed attempts.',icon:LockKeyhole},{id:'VELOCITY',title:'Rapid-fire payments',tag:'PATTERN DETECTION',text:'Eight payments in a few seconds. Test historical deviation and velocity limits.',icon:Activity},{id:'LATE',title:'Delayed delivery',tag:'EVENT INTEGRITY',text:'A transaction arrives two hours late. Preserve event time and record the limitation.',icon:Clock}].map(({id,title,tag,text,icon:Icon})=><section className="panel scenario" key={id}><span className="scenario-icon"><Icon size={25}/></span><span className="eyebrow">{tag}</span><h2>{title}</h2><p>{text}</p><button className="button secondary" disabled={busy||!canEdit} onClick={()=>simulate(id)}><Play size={15}/> Run scenario</button></section>)}</div>{!canEdit&&<p className="muted">Your viewer role cannot submit transactions. Sign in as an analyst or administrator.</p>}</>}
    {page==='Create transaction'&&canEdit&&<CreateTransaction request={api} merchants={transactions.map(t=>t.merchant)}/>}
    {page==='Rule Lab'&&user.role==='ADMIN'&&<RuleLabScreen request={api}/>}
    {page==='Email alerts'&&user.role==='ADMIN'&&<NotificationSettings request={api}/>}
    {page==='Rules & settings'&&<RuleManager request={api} onSaved={()=>api('/rules').then(setPolicy).catch(e=>setError(e.message))}/>}
    {page==='Rules & settings'&&policy&&<div className="settings-grid"><form className="panel settings-form" onSubmit={e=>{e.preventDefault();act(async()=>{const updated=await api('/rules',{method:'PUT',body:JSON.stringify({version:policy.version,amountThresholdMinor:Number(policy.amount_threshold_minor),velocityLimit:Number(policy.velocity_limit),reviewThreshold:Number(policy.review_threshold),highThreshold:Number(policy.high_threshold)})});setPolicy(updated);setNotice('Policy saved. New assessments use version '+updated.version)})}}><div className="panel-heading"><h2>Risk policy</h2><span className="period">Version {policy.version}</span></div><p className="muted">Changes apply to future assessments. Existing explanations retain their original policy version.</p>{[{key:'amount_threshold_minor',label:'Large-payment threshold (paise)',min:1,max:100000000000},{key:'velocity_limit',label:'Transactions in five minutes',min:2,max:100},{key:'review_threshold',label:'Suspicious score threshold',min:1,max:98},{key:'high_threshold',label:'High-risk score threshold',min:2,max:100}].map(({key,label,min,max})=><label key={key}>{label}<input type="number" required min={min} max={max} step="1" value={policy[key]} onChange={e=>setPolicy({...policy,[key]:e.target.value})}/></label>)}<button className="button primary" disabled={busy}><Check size={16}/> Save policy</button></form><section className="panel settings-form"><h2>Recovery queue</h2><p className="muted">Jobs move here after three failed attempts. Investigate the cause before retrying.</p>{jobs.length===0?<Empty title="No failed jobs" text="The processing queue has no dead-letter jobs."/>:jobs.map(j=><div className="job" key={j.id}><code>{j.transaction_id}</code><p>{j.last_error}</p><button className="button secondary" disabled={busy} onClick={()=>act(async()=>{await api('/jobs/'+j.id+'/retry',{method:'POST'});setNotice('Job queued for retry')})}>Retry job</button></div>)}</section></div>}
    {page==='Audit trail'&&<section className="panel"><div className="panel-heading"><div><h2>Audit history</h2><p>Latest 100 system and investigation events</p></div><button className="button secondary" disabled={audit.length===0||busy} onClick={downloadAudit} title="Export the latest audit records currently displayed (up to 100)"><Download size={16}/> Download CSV ({audit.length})</button></div>{audit.length===0?<Empty title="No events yet" text="Submit a scenario to start the audit trail."/>:<div className="table-wrap"><table><thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th><th>Details</th></tr></thead><tbody>{audit.map(a=><tr key={a.id}><td data-label="Time" className="nowrap">{date(a.created_at)}</td><td data-label="Actor">{a.actor}</td><td data-label="Action">{pretty(a.action)}</td><td data-label="Target"><code>{a.target.slice(0,12)}</code></td><td data-label="Details" className="audit-details">{a.details}</td></tr>)}</tbody></table></div>}</section>}
    <footer><span><ShieldCheck size={14}/> Raksha · Explainable by design</span><span>{lastUpdate?'Updated '+lastUpdate.toLocaleTimeString():'Connecting'} · INR</span></footer>
   </div>
  </main>
  {selected&&<div className="modal-backdrop" onClick={()=>setSelected(null)}><section id="transaction-dialog" className="detail-panel" role="dialog" aria-modal="true" aria-label="Transaction details" onClick={e=>e.stopPropagation()}><div className="panel-heading"><div><div className="eyebrow">{selectedAlert?'INVESTIGATION':'TRANSACTION DETAILS'}</div><h2>{selected.merchant}</h2></div><button className="close-button" aria-label="Close details" onClick={()=>setSelected(null)}><X/></button></div><div className="detail-body">{selected.account_holds?.length>0&&<p className="message error">Account on hold: further transactions are blocked. Resolve the triggering investigation to release the hold.</p>}<div className="detail-amount">{money(selected.amount_minor)}<Badge value={selected.classification||selected.status}/></div><dl>{[['Account',selected.account_id],['Event',selected.event_id],['Occurred',date(selected.occurred_at)],['Received',date(selected.received_at)],['Device',selected.device_id],['Country',selected.country],['IP address',selected.ip_address||'Not supplied'],['Phone number',selected.phone_number||'Not supplied'],['Policy version',selected.policy_version??'Pending']].map(([k,v])=><div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl><div className="explanation"><div className="panel-heading"><h3>Why this score?</h3><strong>{selected.score??'—'}<small>/100</small></strong></div>{parsed(selected.explanation,['Analysis is pending. Reopen this transaction after processing.']).map((s:string)=><p key={s}><span>•</span>{s}</p>)}</div><p className="muted small">A risk score is a review priority, not a probability of fraud. No real funds are blocked or moved.</p>
   {selectedAlert&&<div className="case-section"><h3>Case workflow</h3>{selected.account_holds?.some((h:Row)=>h.alert_id===selectedAlert.id)&&<p className="muted">Resolving this case releases its account hold for either outcome. Other open holds still apply. Blocked attempts remain blocked.</p>}<Badge value={selectedAlert.status}/><p className="muted">Assigned to: {selectedAlert.assignee||'Unassigned'}</p>{selectedAlert.status==='RESOLVED'?<div className="resolution"><strong>{pretty(selectedAlert.outcome)}</strong><p>{selectedAlert.resolution}</p></div>:canEdit&&<>{!selectedAlert.assignee?<button className="button primary" disabled={busy} onClick={()=>caseAction('CLAIM')}>Assign to me</button>:selectedAlert.assignee===user.email?<><label>Investigation outcome<select value={outcome} onChange={e=>setOutcome(e.target.value)}><option value="FALSE_POSITIVE">False positive</option><option value="CONFIRMED_FRAUD">Confirmed fraud</option></select></label><label>Resolution reason<textarea value={reason} maxLength={2000} onChange={e=>setReason(e.target.value)} placeholder="Record the evidence behind your decision"/></label><button className="button primary" disabled={busy||!reason.trim()} onClick={()=>caseAction('RESOLVE')}>Resolve case</button></>:<p>Another analyst owns this investigation.</p>}</>}<h3 className="notes-heading">Investigation notes</h3>{notes.map(n=><article className="note" key={n.id}><strong>{n.author.split('@')[0]} <small>{date(n.created_at)}</small></strong><p>{n.note}</p></article>)}{canEdit&&<><textarea aria-label="New investigation note" placeholder="Add a note…" maxLength={2000} value={note} onChange={e=>setNote(e.target.value)}/><button className="button secondary" disabled={busy||!note.trim()} onClick={()=>act(async()=>{await api('/alerts/'+selectedAlert.id+'/notes',{method:'POST',body:JSON.stringify({note})});setNotes(await api('/alerts/'+selectedAlert.id+'/notes'));setNote('')})}>Add note</button></>}</div>}
   {error&&<p role="alert" className="message error">{error}</p>}
   </div></section></div>}
 </div>
}

function Login({onLogin,theme,toggleTheme}:{onLogin:()=>Promise<void>;theme:Theme;toggleTheme:()=>void}){
 const [email,setEmail]=useState(''),[password,setPassword]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 async function submit(e:FormEvent){e.preventDefault();setBusy(true);setError('');try{await getCsrf();await api('/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:email,password}).toString()});await onLogin()}catch(e){setError((e as Error).message)}finally{setBusy(false)}}
 return <div className="login-page"><div className="login-theme"><ThemeToggle theme={theme} toggle={toggleTheme}/></div><div className="login-story"><div className="brand"><span className="brand-mark"><ShieldCheck/></span>Raksha.</div><span className="eyebrow">CONFIDENCE IN EVERY TRANSACTION</span><h1>See the pattern.<br/>Understand the risk.<br/><em>Make the right call.</em></h1><p>A focused workspace for transaction monitoring, explainable risk signals, and human-led investigations.</p><div className="login-proof"><span><CheckCircle2 size={17}/> Explainable detection</span><span><CheckCircle2 size={17}/> Auditable decisions</span></div><div className="login-orbit"><ShieldCheck size={110}/></div></div><div className="login-form-wrap"><form onSubmit={submit} className="login-form"><div className="mobile-login-brand"><span className="brand-mark"><ShieldCheck size={23}/></span>Raksha<span>.</span></div><h2>Welcome back</h2><p>Sign in to your fraud intelligence workspace.</p><label>Email address<input type="email" autoComplete="username" required value={email} onChange={e=>setEmail(e.target.value)}/></label><label>Password<input type="password" autoComplete="current-password" required value={password} onChange={e=>setPassword(e.target.value)} placeholder="Enter your workspace password"/></label>{error&&<div className="message error" role="alert">{error}</div>}<button className="button primary" disabled={busy}>{busy?'Signing in…':'Sign in to workspace'}<ArrowUpRight size={18}/></button><div className="login-note"><LockKeyhole size={16}/> Simulated financial data only</div></form></div></div>
}
function Empty({title,text}:{title:string;text:string}){return <div className="empty"><span><Inbox size={26}/></span><h3>{title}</h3><p>{text}</p></div>}
function Transactions({rows,onOpen}:{rows:Row[];onOpen:(id:string)=>void}){return rows.length===0?<Empty title="Your first transaction starts here" text="Run a simulation to see transactions, risk signals, and explanations flow into your workspace."/>:<div className="table-wrap"><table><thead><tr><th>Merchant / Account</th><th>Amount</th><th>Risk assessment</th><th>Occurred</th><th>Device</th><th/></tr></thead><tbody>{rows.map(t=><tr key={t.id}><td data-label="Merchant / Account"><div className="merchant-cell"><span className="merchant-avatar">{t.merchant.slice(0,1)}</span><div><strong>{t.merchant}</strong><small>{t.account_id}</small></div></div></td><td data-label="Amount" className="amount-cell">{money(t.amount_minor)}</td><td data-label="Risk assessment"><Badge value={t.classification||t.status}/>{t.score!=null&&<span className="score-inline">{t.score}/100</span>}</td><td data-label="Occurred" className="nowrap">{date(t.occurred_at)}{t.late_event&&<small>Delayed event</small>}</td><td data-label="Device"><span className="device">{t.device_id}</span></td><td data-label="Details"><button className="table-open" aria-label={'View transaction '+t.event_id} onClick={()=>onOpen(t.id)}><ArrowUpRight size={17}/></button></td></tr>)}</tbody></table></div>}
function Trend({rows}:{rows:Row[]}){
 const days=Array.from({length:7},(_,i)=>{const d=new Date();d.setUTCDate(d.getUTCDate()-6+i);const key=d.toISOString().slice(0,10);const row=rows.find(r=>String(r.day).slice(0,10)===key);return {label:d.toLocaleDateString('en-IN',{weekday:'short',timeZone:'UTC'}),total:Number(row?.total||0),flagged:Number(row?.flagged||0)}});
 const max=Math.max(5,...days.map(d=>d.total));
 return <div className="chart"><div className="chart-y"><span>{max}</span><span>{Math.round(max/2)}</span><span>0</span></div><div className="bars" role="img" aria-label={days.map(d=>d.label+': '+d.total+' transactions, '+d.flagged+' flagged').join('; ')}>{days.map((d,i)=><div className="bar-column" key={i}><div className="bar-track"><div className="bar-total" title={d.total+' transactions'} style={{height:(d.total/max*100)+'%'}}/><div className="bar-flagged" title={d.flagged+' flagged'} style={{height:(d.flagged/max*100)+'%'}}/></div><span>{d.label}</span></div>)}</div></div>
}


