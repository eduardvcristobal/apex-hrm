import { useEffect, useState, type FormEvent } from 'react';

type Milestone = { id: string; title: string; description?: string; status: string; dueOn?: string };
type Evidence = { note: string; recordedAt: string };
async function call<T>(token: string, path: string, method = 'GET', payload?: unknown): Promise<T> {
  const response = await fetch('/api/v1/performance'+path, {
    method, headers: { Authorization: `Bearer ${token}`, ...(payload ? {'Content-Type':'application/json'} : {}) },
    body: payload ? JSON.stringify(payload) : undefined,
  });
  if(!response.ok) throw Error(`Milestone request failed (${response.status})`);
  const body = await response.text();
  return body ? JSON.parse(body) as T : undefined as T;
}
export function MilestonePanel({token,planId}:{token:string;planId:string}) {
  const [milestones,setMilestones]=useState<Milestone[]>([]);
  const [title,setTitle]=useState('');
  const [notes,setNotes]=useState<Record<string,string>>({});
  const [evidence,setEvidence]=useState<Record<string,Evidence[]>>({});
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  async function reload() {
    if(!planId) {setMilestones([]); return;}
    setMilestones(await call<Milestone[]>(token,'/plans/'+planId+'/milestones'));
  }
  useEffect(()=>{setError('');setEvidence({});void reload().catch(e=>setError(String(e)));},[token,planId]);
  async function run(fn:()=>Promise<unknown>) {
    setBusy(true);setError('');
    try {await fn();await reload();setTitle('');}
    catch(e) {setError(e instanceof Error?e.message:String(e));}
    finally {setBusy(false);}
  }
  if(!planId) return null;
  return <div className="performance-milestones">
    <h3>Development milestones</h3>
    {error && <p role="alert">{error}</p>}
    <form onSubmit={(e:FormEvent)=>{e.preventDefault();void run(()=>call(token,'/plans/'+planId+'/milestones','POST',{title}));}}>
      <input aria-label="Milestone title" maxLength={200} value={title} required onChange={e=>setTitle(e.target.value)} placeholder="Action milestone or training target"/>
      <button disabled={busy}>Add milestone</button>
    </form>
    <ul>{milestones.map(m=><li key={m.id}>
      <strong>{m.title}</strong> · {m.status}
      {['PLANNED','IN_PROGRESS'].includes(m.status) && <>
        <input aria-label={`Evidence for ${m.title}`} maxLength={4000} value={notes[m.id]||''} required placeholder="Evidence or completion notes" onChange={e=>setNotes(n=>({...n,[m.id]:e.target.value}))}/>
        <button disabled={busy||!(notes[m.id]||'').trim()} type="button" onClick={()=>void run(()=>call(token,'/milestones/'+m.id+'/status','POST',{status:m.status==='PLANNED'?'IN_PROGRESS':'COMPLETED',evidence:notes[m.id]}))}>{m.status==='PLANNED'?'Start':'Complete'}</button>
      </>}
      <button type="button" disabled={busy} onClick={()=>void call<Evidence[]>(token,'/milestones/'+m.id+'/evidence').then(v=>setEvidence(old=>({...old,[m.id]:v}))).catch(e=>setError(String(e)))}>Evidence history</button>
      {evidence[m.id] && <ul>{evidence[m.id].map((ev,i)=><li key={i}>{new Date(ev.recordedAt).toLocaleDateString()}: {ev.note}</li>)}</ul>}
    </li>)}</ul>
  </div>;
}
