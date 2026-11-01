import { useEffect, useState } from 'react';
import './performance.css';

type Goal = { id: string; title: string; metric?: string; targetValue?: number; actualValue?: number; status: string };
type Review = { id: string; cycleId: string; rating?: number; selfRating?: number; status: string; managerComments?: string };
type TeamGoal = { id: string; employeeId: string; title: string; actualValue?: number; status: string };
type TeamReview = { id: string; employeeId: string; rating?: number; status: string };
async function api<T>(token: string, path: string, method = 'GET', payload?: unknown): Promise<T> {
  const response = await fetch('/api/v1/performance' + path, { method,
    headers: { Authorization: `Bearer ${token}`, ...(payload ? { 'Content-Type': 'application/json' } : {}) },
    body: payload ? JSON.stringify(payload) : undefined });
  if (!response.ok) throw new Error(`Performance request failed: ${response.status}`);
  const text = await response.text();
  return text ? JSON.parse(text) as T : undefined as T;
}
export function PerformanceSelfService({ token, isManager }: { token: string; isManager: boolean }) {
  const [goals, setGoals] = useState<Goal[]>([]);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [teamGoals, setTeamGoals] = useState<TeamGoal[]>([]);
  const [teamReviews, setTeamReviews] = useState<TeamReview[]>([]);
  const [values, setValues] = useState<Record<string,string>>({});
  const [notes, setNotes] = useState<Record<string,string>>({});
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  async function reload() {
    const [g, r] = await Promise.all([api<Goal[]>(token, '/my/goals'),api<Review[]>(token, '/my/reviews')]);
    setGoals(g);setReviews(r);
    if (isManager) {
      const [tg, tr] = await Promise.all([api<TeamGoal[]>(token,'/team/goals'),api<TeamReview[]>(token,'/team/reviews')]);
      setTeamGoals(tg);setTeamReviews(tr);
    }
  }
  useEffect(() => { void reload().catch(e => setError(String(e))); }, [token,isManager]);
  async function run(action: () => Promise<unknown>) {
    setBusy(true);setError('');setNotice('');
    try { await action();await reload();setNotice('Performance record updated.'); }
    catch(e) { setError(e instanceof Error?e.message:String(e)); }
    finally {setBusy(false);}
  }
  return <section className="account-page">
    <p className="eyebrow">MY PERFORMANCE</p><h1>Goals & reviews</h1>
    <p className="account-page-intro">Track your objectives, share self-assessments and acknowledge your evaluations.</p>
    {error && <p role="alert" className="form-message" data-kind="error">{error}</p>}
    {notice && <p role="status" className="form-message" data-kind="success">{notice}</p>}
    <div className="performance-grid">
      <div className="performance-panel"><h2>My goals</h2>
        {goals.length===0 && <p>No active performance goals. Contact HR if your employee account has not been linked.</p>}
        {goals.map(g=><form key={g.id} onSubmit={e=>{e.preventDefault();void run(()=>api(token,'/goals/'+g.id+'/progress','POST',{actualValue:Number(values[g.id]??g.actualValue??0),note:notes[g.id]??''}));}}>
          <h3>{g.title}</h3><p>{g.metric||'KPI'} · {g.actualValue??0} / {g.targetValue??'—'}</p>
          <label>Actual value <input type="number" step=".001" min="0" required value={values[g.id]??String(g.actualValue??0)} onChange={e=>setValues(v=>({...v,[g.id]:e.target.value}))}/></label>
          <input aria-label={`Progress note for ${g.title}`} placeholder="What changed?" maxLength={2000} value={notes[g.id]??''} onChange={e=>setNotes(v=>({...v,[g.id]:e.target.value}))}/>
          <button disabled={busy||g.status!=='ACTIVE'}>Update progress</button>
        </form>)}
      </div>
      <div className="performance-panel"><h2>My evaluations</h2>
        {reviews.length===0 && <p>No reviews assigned.</p>}
        {reviews.map(r=><div key={r.id}>
          <h3>Review · {r.status}</h3><p>Manager rating: {r.rating??'Not yet submitted'} / 5</p>
          {r.status==='DRAFT' && <form onSubmit={e=>{e.preventDefault();void run(()=>api(token,'/reviews/'+r.id+'/self-assessment','POST',{rating:Number(values[r.id]??3),assessment:notes[r.id]??''}));}}>
            <label>Self-rating <input type="number" min="1" max="5" step=".5" required value={values[r.id]??'3'} onChange={e=>setValues(v=>({...v,[r.id]:e.target.value}))}/></label>
            <textarea aria-label="Self-assessment" placeholder="Achievements, challenges, evidence" required value={notes[r.id]??''} onChange={e=>setNotes(v=>({...v,[r.id]:e.target.value}))}/>
            <button disabled={busy}>Save self-assessment</button>
          </form>}
          {r.status==='SUBMITTED' && <><p>{r.managerComments||'No manager comments recorded.'}</p><button disabled={busy} onClick={()=>void run(()=>api(token,'/reviews/'+r.id+'/acknowledge','POST'))}>Acknowledge review</button></>}
        </div>)}
      </div>
      {isManager && <div className="performance-panel"><h2>My direct reports</h2>
        <p>Goals: {teamGoals.length} · Evaluations: {teamReviews.length}</p>
        <ul>{teamGoals.map(g=><li key={g.id}>{g.title} · {g.status}</li>)}</ul>
        <h3>Review queue</h3><ul>{teamReviews.map(r=><li key={r.id}>{r.employeeId.slice(0,8)} · {r.status}</li>)}</ul>
        <p>Review drafts and submissions are currently managed in the API; HR can manage cycles and assignments.</p>
      </div>}
    </div>
  </section>;
}
