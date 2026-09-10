import {useEffect,useState} from 'react';
type Row=Record<string,any>;
type Request=(path:string,options?:RequestInit)=>Promise<any>;
export function NotificationSettings({request}:{request:Request}){
 const [settings,setSettings]=useState<Row|null>(null),[saved,setSaved]=useState<Row|null>(null),[deliveries,setDeliveries]=useState<Row[]>([]),[smsDeliveries,setSmsDeliveries]=useState<Row[]>([]);
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 async function reload(){const s=await request('/notifications/settings');setSettings(s);setSaved(s)}
 async function history(){setDeliveries(await request('/notifications/deliveries'));setSmsDeliveries(await request('/notifications/sms-deliveries'))}
 useEffect(()=>{reload().catch(e=>setError(e.message));let live=true;
 const poll=()=>Promise.all([request('/notifications/deliveries'),request('/notifications/sms-deliveries')]).then(([rows,sms])=>{if(live){setDeliveries(rows);setSmsDeliveries(sms)}}).catch(e=>{if(live)setError(e.message)});
 poll();const timer=setInterval(poll,4000);return()=>{live=false;clearInterval(timer)}
 },[]);
 async function act(work:()=>Promise<void>){setBusy(true);setError('');setNotice('');try{await work();await history()}catch(e){setError((e as Error).message)}finally{setBusy(false)}}
 if(!settings)return <section className="panel settings-form">{error?<p role="alert">{error}</p>:<p>Loading email settings…</p>}</section>;
 const dirty=settings.enabled!==saved?.enabled||settings.minimum_classification!==saved?.minimum_classification||settings.recipients!==saved?.recipients;
 const recipientCount=settings.recipients.split(/[,\n]+/).filter((s:string)=>s.trim()).length;
 return <>
 {error&&<div className="message error" role="alert">{error}</div>}{notice&&<div className="message success" role="status">{notice}</div>}
 <div className="settings-grid">
 <form className="panel settings-form" onSubmit={e=>{e.preventDefault();act(async()=>{
 const updated=await request('/notifications/settings',{method:'PUT',body:JSON.stringify({version:settings.version,enabled:settings.enabled,minimumClassification:settings.minimum_classification,recipients:settings.recipients})});setSettings(updated);setSaved(updated);setNotice('Management notification settings saved.')
 })}}>
 <div className="panel-heading"><h2>Management recipients</h2><span className="period">Version {settings.version}</span></div>
 <p className="muted">Choose who receives new fraud-alert emails. Each recipient receives a separate message containing the alert ID, transaction ID, risk score and classification.</p>
 <label>Email notifications<select value={settings.enabled?'on':'off'} onChange={e=>setSettings({...settings,enabled:e.target.value==='on'})}><option value="off">Paused</option><option value="on">Enabled</option></select></label>
 <label>Notify for<select value={settings.minimum_classification} onChange={e=>setSettings({...settings,minimum_classification:e.target.value})}><option value="HIGH_RISK">High-risk alerts only</option><option value="SUSPICIOUS">Suspicious and high-risk alerts</option></select></label>
 <label>Management email addresses<textarea rows={5} maxLength={6000} value={settings.recipients} placeholder="manager@example.com" onChange={e=>setSettings({...settings,recipients:e.target.value})}/><small>One address per line, up to 20. Remove an address to stop future delivery to it.</small></label>
 <p className="muted">{recipientCount} recipient{recipientCount===1?'':'s'} · {dirty?'Unsaved changes':saved?.enabled?'Notifications enabled':'Notifications paused'}</p>
 <div className="rule-actions"><button className="button primary" disabled={busy}>Save settings</button><button type="button" className="button secondary" disabled={busy} onClick={()=>act(reload)}>Reload</button></div>
 <p className="muted small">Changes apply to new alerts and pending deliveries. Messages already accepted by the mail server cannot be recalled. Old alerts are not emailed retroactively.</p>
 </form>
 <section className="panel settings-form"><h2>SMTP connection</h2>
 <dl className="smtp-details"><div><dt>Server</dt><dd>{settings.smtp.host}:{settings.smtp.port}</dd></div><div><dt>Sender</dt><dd>{settings.smtp.sender||'Not configured'}</dd></div><div><dt>Security</dt><dd>{settings.smtp.security}</dd></div><div><dt>Credentials</dt><dd>{settings.smtp.configured?'Configured on server':'Not configured'}</dd></div></dl>
 <p className="muted">SMTP credentials are managed on the server. The app password is never returned to the browser. Configured means credentials are present; use a test email to verify sending.</p>
 <button className="button secondary" disabled={busy||dirty||!settings.smtp.configured||!saved?.recipients.trim()} onClick={()=>act(async()=>{const r=await request('/notifications/test',{method:'POST'});setNotice('Test queued for '+r.queued+' saved recipient(s). Check delivery status below.')})}>Send test email</button>
 <p className="muted small">Sends to the saved recipients, even while automatic notifications are paused. Save edits first. Tests are limited to one batch per minute.</p>
 </section></div>
 <section className="panel settings-form" style={{marginTop:24}}><div className="panel-heading"><div><h2>Email delivery history</h2><p>Latest 100 messages · refreshes every four seconds</p></div><button className="button secondary" disabled={busy} onClick={()=>act(history)}>Refresh history</button></div>
 <p className="muted small">Sent means accepted by SMTP, not confirmed inbox delivery. Failures retry up to three attempts. A connection loss after SMTP acceptance can cause a duplicate; the notification ID identifies it.</p>
 {!deliveries.length?<p className="muted">No emails queued yet.</p>:<div className="table-wrap"><table><thead><tr><th>Recipient</th><th>Alert</th><th>Status</th><th>Attempts</th><th>Time</th><th>Details</th></tr></thead><tbody>{deliveries.map(row=><tr key={row.id}><td data-label="Recipient">{row.recipient}</td><td data-label="Alert">{row.classification}<small>{row.alert_id?.slice(0,8)||'Test email'}</small></td><td data-label="Status"><span className={'badge '+(row.state==='SENT'?'normal':row.state==='FAILED'?'high_risk':'pending')}>{row.state}</span></td><td data-label="Attempts">{row.attempts}</td><td data-label="Time">{new Date(row.created_at).toLocaleString()}</td><td data-label="Details">{row.last_error||'—'}{row.state==='FAILED'&&<button className="button secondary" disabled={busy} onClick={()=>act(async()=>{await request('/notifications/deliveries/'+row.id+'/retry',{method:'POST'});setNotice('Email queued for retry.')})}>Retry</button>}</td></tr>)}</tbody></table></div>}
 </section>
 <section className="panel settings-form" style={{marginTop:24}}><div className="panel-heading"><div><h2>SMS delivery history</h2><p>Latest 100 messages · refreshes every four seconds</p></div></div>
 <p className="muted small">Sent to the mobile number provided when the transaction was created, on success or failure. Failures retry up to three attempts.</p>
 {!smsDeliveries.length?<p className="muted">No SMS queued yet.</p>:<div className="table-wrap"><table><thead><tr><th>Recipient</th><th>Transaction</th><th>Outcome</th><th>Status</th><th>Attempts</th><th>Time</th><th>Details</th></tr></thead><tbody>{smsDeliveries.map(row=><tr key={row.id}><td data-label="Recipient">{row.recipient}</td><td data-label="Transaction"><small>{row.transaction_id?.slice(0,8)}</small></td><td data-label="Outcome">{row.outcome}</td><td data-label="Status"><span className={'badge '+(row.state==='SENT'?'normal':row.state==='FAILED'?'high_risk':'pending')}>{row.state}</span></td><td data-label="Attempts">{row.attempts}</td><td data-label="Time">{new Date(row.created_at).toLocaleString()}</td><td data-label="Details">{row.last_error||'—'}</td></tr>)}</tbody></table></div>}
 </section></>
}
