import {useEffect,useState} from 'react';
type Row=Record<string,any>;
type Request=(path:string,options?:RequestInit)=>Promise<any>;
let checkoutScript:Promise<void>|undefined;
function loadCheckout(){
 if((window as any).Razorpay)return Promise.resolve();
 if(!checkoutScript)checkoutScript=new Promise<void>((resolve,reject)=>{const script=document.createElement('script');script.src='https://checkout.razorpay.com/v1/checkout.js';script.onload=()=>resolve();script.onerror=()=>{checkoutScript=undefined;script.remove();reject(new Error('Could not load Razorpay Checkout. Check your connection.'))};document.head.appendChild(script)});
 return checkoutScript;
}
export function PaymentActions({request,transactionId}:{request:Request;transactionId:string}){
 const [config,setConfig]=useState<Row|null>(null),[data,setData]=useState<Row|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const refresh=async()=>setData(await request('/payments/'+transactionId));
 useEffect(()=>{let live=true;const load=async()=>{try{const [c,d]=await Promise.all([request('/payments/config'),request('/payments/'+transactionId)]);if(live){setConfig(c);setData(d)}}catch(e){if(live)setError((e as Error).message)}};load();const timer=setInterval(load,4000);return()=>{live=false;clearInterval(timer)}},[transactionId]);
 async function run(work:()=>Promise<void>){setBusy(true);setError('');try{await work()}catch(e){setError((e as Error).message)}finally{setBusy(false)}}
 const state=data?.order?.status;
 const eligible=data?.transaction?.status==='SCORED'&&data?.transaction?.score<100&&!data?.accountHeld&&Boolean(data?.transaction?.phone_number);
 async function pay(){await run(async()=>{
  await loadCheckout();const order=await request('/payments/'+transactionId+'/checkout',{method:'POST'});await refresh();
  const checkout=new (window as any).Razorpay({key:order.key,order_id:order.orderId,amount:order.amount,currency:order.currency,name:'Raksha Sandbox',description:order.merchant,prefill:{contact:order.contact},readonly:{contact:true},
   handler:(response:Row)=>run(async()=>{setData(await request('/payments/'+transactionId+'/confirm',{method:'POST',body:JSON.stringify({paymentId:response.razorpay_payment_id,signature:response.razorpay_signature})}));setNotice('Payment verified with Razorpay. Check the payment status below.')}),
   modal:{ondismiss:()=>setNotice('Checkout closed. This does not prove payment failure; use Reconcile to check Razorpay.')},theme:{color:'#246647'}});
  checkout.on('payment.failed',()=>setNotice('Razorpay reported a failed attempt. Use Reconcile for the authoritative payment status.'));
  checkout.open();
 })}
 return <section className="payment-section"><h3>Razorpay sandbox payment</h3><p className="muted small">Test mode only. Risk scoring and payment status are separate. Merchant names are demo labels; payments use the configured Razorpay test account.</p>
 {!config?<p>Checking gateway configuration…</p>:!config.configured?<p className="message">Setup required: configure RAZORPAY_KEY_ID (rzp_test_), RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET on the server. Checkout is disabled.</p>:null}
 {error&&<p role="alert" className="message error">{error}</p>}{notice&&<p role="status" className="message">{notice}</p>}
 <p>Payment status: <strong>{state||'NOT STARTED'}</strong></p>{data?.order?.provider_order_id&&<p className="muted small">Order: {data.order.provider_order_id}</p>}
 {!eligible&&<p className="muted">Checkout requires a mobile number, a completed risk assessment below 100 and an account without an active hold.</p>}
 {['CREATING','UNKNOWN'].includes(state)&&<p className="message">Order creation is unconfirmed. Reconcile to recover the existing order. Do not create another transaction to retry this payment.</p>}
 <div className="rule-actions"><button type="button" className="button primary" disabled={busy||!config?.configured||!eligible||['CREATING','UNKNOWN','AUTHORIZED','CAPTURED'].includes(state)} onClick={pay}>Pay in test mode</button>
 <button type="button" className="button secondary" disabled={busy||!config?.configured||!state} onClick={()=>run(async()=>{setData(await request('/payments/'+transactionId+'/reconcile',{method:'POST'}));setNotice('Payment status checked with Razorpay.')} )}>Reconcile</button></div>
 {state==='AUTHORIZED'&&<p className="muted">Authorized, not yet captured. Configure automatic capture in Razorpay Test Dashboard and reconcile again.</p>}
 {data?.attempts?.map((a:Row)=><p key={a.payment_id} className="muted small">{a.payment_id} · {a.status}</p>)}
 </section>
}
export function PaymentsScreen({request,canEdit}:{request:Request;canEdit:boolean}){
 const [rows,setRows]=useState<Row[]>([]),[selected,setSelected]=useState(''),[error,setError]=useState('');
 useEffect(()=>{let live=true;const load=()=>request('/payments').then(r=>{if(live)setRows(r)}).catch(e=>{if(live)setError(e.message)});load();const timer=setInterval(load,4000);return()=>{live=false;clearInterval(timer)}},[]);
 return <section className="panel settings-form"><h2>Test payment history</h2><p className="muted">Create and assess a transaction first, then choose Pay in test mode. These are sandbox payments; no real funds are moved.</p>{error&&<p role="alert">{error}</p>}
 {!rows.length?<p>No gateway orders yet. Open Create transaction to begin.</p>:<div className="table-wrap"><table><thead><tr><th>Merchant / Account</th><th>Amount</th><th>Risk</th><th>Payment</th><th/></tr></thead><tbody>{rows.map(r=><tr key={r.transaction_id}><td data-label="Merchant / Account">{r.merchant}<small>{r.account_id}</small></td><td data-label="Amount">₹{(r.amount_minor/100).toFixed(2)}</td><td data-label="Risk">{r.score}/100</td><td data-label="Payment">{r.status}</td><td>{canEdit&&<button className="text-button" onClick={()=>setSelected(r.transaction_id)}>Open payment</button>}</td></tr>)}</tbody></table></div>}
 {selected&&canEdit&&<PaymentActions key={selected} request={request} transactionId={selected}/>}
 </section>
}

