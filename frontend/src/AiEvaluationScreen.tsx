import {useEffect,useState} from 'react';
type Row=Record<string,any>;
type Request=(path:string,options?:RequestInit)=>Promise<any>;
const DIMENSIONS=['accuracy','hallucination','financialConsistency','reliability','explainability','safety','latency','cost'];
const LABELS:Record<string,string>={accuracy:'Accuracy',hallucination:'Hallucination',financialConsistency:'Financial consistency',reliability:'Reliability',explainability:'Explainability',safety:'Safety',latency:'Latency',cost:'Cost'};
function CheckPill({name,check}:{name:string;check?:Row}){
 if(!check)return <span className="badge pending" title="Not applicable to this case"><i/>{LABELS[name]}</span>;
 return <span className={'badge '+(check.pass?'normal':'high_risk')} title={check.detail}><i/>{LABELS[name]}</span>;
}
export function AiEvaluationScreen({request}:{request:Request}){
 const [runs,setRuns]=useState<Row[]>([]),[latest,setLatest]=useState<Row|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
 async function reload(){setRuns(await request('/ai/evaluations'))}
 useEffect(()=>{reload().catch(e=>setError(e.message))},[]);
 async function run(){setBusy(true);setError('');try{const result=await request('/ai/evaluations/run',{method:'POST'});setLatest(result);await reload()}catch(e){setError((e as Error).message)}finally{setBusy(false)}}
 return <>
 {error&&<div className="message error" role="alert">{error}</div>}
 <section className="panel settings-form">
  <div className="panel-heading"><div><h2>AI evaluation</h2><p>Grades AiExplainer's narratives against the deterministic scoring output — not a second AI judging the first.</p></div>
  <button className="button primary" disabled={busy} onClick={run}>{busy?'Running…':'Run evaluation'}</button></div>
  <p className="muted small">Each run replays a TAKEOVER and a VELOCITY scenario, scores them, generates an AI narrative, and checks it against eight dimensions: {DIMENSIONS.map(d=>LABELS[d]).join(', ')}.</p>
 </section>
 {latest&&<section className="panel settings-form" style={{marginTop:24}}>
  <div className="panel-heading"><h2>Latest run</h2><span className="period">{latest.passed}/{latest.total} passed</span></div>
  {latest.cases.map((c:Row,i:number)=><div key={i} className="job" style={{alignItems:'flex-start'}}>
   <div style={{flex:1}}>
    <p><strong>{c.scenario}</strong> · classification {c.classification} · AI state {c.aiState} · {c.passed?'PASSED':'FAILED'}</p>
    {c.note&&<p className="muted small">{c.note}</p>}
    {c.checks&&<div style={{display:'flex',flexWrap:'wrap',gap:6,margin:'6px 0'}}>{DIMENSIONS.map(d=><CheckPill key={d} name={d} check={c.checks[d]}/>)}</div>}
    {c.narrative&&<p className="muted small">"{c.narrative}"</p>}
   </div>
  </div>)}
 </section>}
 <section className="panel settings-form" style={{marginTop:24}}>
  <div className="panel-heading"><div><h2>Run history</h2><p>Latest 50 runs</p></div><button className="button secondary" disabled={busy} onClick={()=>reload().catch(e=>setError(e.message))}>Refresh</button></div>
  {!runs.length?<p className="muted">No evaluation runs yet.</p>:<div className="table-wrap"><table><thead><tr><th>Time</th><th>Total</th><th>Passed</th><th>Failed</th></tr></thead><tbody>{runs.map(r=><tr key={r.id}><td data-label="Time">{new Date(r.run_at).toLocaleString()}</td><td data-label="Total">{r.total}</td><td data-label="Passed">{r.passed}</td><td data-label="Failed">{r.failed}</td></tr>)}</tbody></table></div>}
 </section>
 </>
}
