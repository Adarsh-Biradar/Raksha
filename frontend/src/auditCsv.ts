export function auditCsv(rows:Record<string,unknown>[]):string {
 const cell=(value:unknown)=>{
  let text=value==null?'':String(value);
  // Keep untrusted audit text from becoming a spreadsheet formula.
  if(/^[\s\u0000-\u001f]*[=+@-]/.test(text)||/^[\t\r\n]/.test(text))text="'"+text;
  return '"'+text.replaceAll('"','""')+'"';
 };
 const lines=[['ID','Time (UTC)','Actor','Action','Target','Details'],...rows.map(r=>[r.id,r.created_at,r.actor,r.action,r.target,r.details])];
 return '\uFEFF'+lines.map(row=>row.map(cell).join(',')).join('\r\n')+'\r\n';
}
