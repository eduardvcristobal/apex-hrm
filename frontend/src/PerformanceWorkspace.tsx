import { useEffect, useState, type FormEvent } from 'react';
import './performance.css';
import { MilestonePanel } from './PerformanceMilestones';

type Item = Record<string, unknown>;
type Cycle = { id: string; name: string; startsOn: string; endsOn: string; status: string };
type Employee = { id: string; firstName: string; lastName: string; employeeNumber: string };
async function request<T>(token: string, path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch('/api/v1/performance' + path, {
    method, headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) throw new Error(`Request failed (${response.status})`);
  const text = await response.text();
  return text ? JSON.parse(text) as T : undefined as T;
}
export function PerformanceWorkspace({ token }: { token: string }) {
  const [companyId, setCompanyId] = useState('');
  const [companies, setCompanies] = useState<{ id: string; name: string }[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [cycles, setCycles] = useState<Cycle[]>([]);
  const [cycleId, setCycleId] = useState('');
  const [goals, setGoals] = useState<Item[]>([]);
  const [reviews, setReviews] = useState<Item[]>([]);
  const [scores, setScores] = useState<Item[]>([]);
  const [reports, setReports] = useState<Item[]>([]);
  const [editGoalId, setEditGoalId] = useState('');
  const [editTitle, setEditTitle] = useState('');
  const [editTarget, setEditTarget] = useState('100');
  const [plans, setPlans] = useState<Item[]>([]);
  const [reviewId, setReviewId] = useState('');
  const [name, setName] = useState('Annual performance review');
  const [startsOn, setStartsOn] = useState(new Date().getFullYear() + '-01-01');
  const [endsOn, setEndsOn] = useState(new Date().getFullYear() + '-12-31');
  const [employeeId, setEmployeeId] = useState('');
  const [accountId, setAccountId] = useState('');
  const [title, setTitle] = useState('');
  const [target, setTarget] = useState('100');
  const [rating, setRating] = useState('3');
  const [goalId, setGoalId] = useState('');
  const [progress, setProgress] = useState('0');
  const [progressNote, setProgressNote] = useState('');
  const [progressHistory, setProgressHistory] = useState<Item[]>([]);
  const [historyGoal, setHistoryGoal] = useState('');
  const [comments, setComments] = useState('');
  const [planTitle, setPlanTitle] = useState('');
  const [actions, setActions] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  async function loadCompany(id: string) {
    const [cy, emp] = await Promise.all([
      request<Cycle[]>(token, '/cycles?companyId=' + encodeURIComponent(id)),
      request<Employee[]>(token, '/employees?companyId=' + encodeURIComponent(id)),
    ]);
    setCycles(cy); setEmployees(emp);
    setReports(await request<Item[]>(token,'/reports/summary?companyId='+encodeURIComponent(id)));
    if (!cy.some(c => c.id === cycleId)) setCycleId(cy[0]?.id || '');
    if (!emp.some(e => e.id === employeeId)) setEmployeeId(emp[0]?.id || '');
  }
  useEffect(() => {
    void request<{ id: string; name?: string; companyName?: string; legalName?: string }[]>(token, '/companies').then(raw => {
      setCompanies(raw.map(c => ({ id: c.id, name: c.legalName || c.companyName || c.name || c.id })));
      setCompanyId(old => old || raw[0]?.id || '');
    }).catch(e => setError(String(e)));
  }, [token]);
  useEffect(() => { if (companyId) void loadCompany(companyId).catch(e => setError(String(e))); }, [companyId, token]);
  useEffect(() => {
    if (!cycleId) { setGoals([]); setReviews([]); setScores([]); return; }
    void Promise.all([request<Item[]>(token, '/goals?cycleId=' + cycleId), request<Item[]>(token, '/reviews?cycleId=' + cycleId), request<Item[]>(token, '/scores?cycleId=' + cycleId)])
      .then(([g, r, sc]) => { setGoals(g); setReviews(r); setScores(sc); if (!r.some(x => x.id === reviewId)) setReviewId(String(r[0]?.id || '')); })
      .catch(e => setError(String(e)));
  }, [cycleId, token]);
  useEffect(() => {
    if (!reviewId) { setPlans([]); return; }
    void request<Item[]>(token, '/reviews/' + reviewId + '/plans').then(setPlans).catch(e => setError(String(e)));
  }, [reviewId, token]);
  async function run(action: () => Promise<unknown>) {
    setBusy(true); setError(''); setNotice('');
    try {
      await action();
      if (companyId) await loadCompany(companyId);
      if (cycleId) {
        const [g, r, sc] = await Promise.all([request<Item[]>(token, '/goals?cycleId=' + cycleId), request<Item[]>(token, '/reviews?cycleId=' + cycleId), request<Item[]>(token, '/scores?cycleId=' + cycleId)]);
        setGoals(g); setReviews(r); setScores(sc);
      }
      if (reviewId) setPlans(await request<Item[]>(token, '/reviews/' + reviewId + '/plans'));
      setNotice('Saved successfully.');
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  const active = cycles.find(c => c.id === cycleId)?.status === 'ACTIVE';
  return <section className="account-page">
    <p className="eyebrow">PEOPLE DEVELOPMENT · M5</p><h1>Performance management</h1>
    <p className="account-page-intro">Manage review cycles, measurable goals, manager evaluations and employee development plans.</p>
    {error && <p role="alert" className="form-message" data-kind="error">{error}</p>}
    {notice && <p role="status" className="form-message" data-kind="success">{notice}</p>}
    <div className="performance-panel"><h2>Performance reporting</h2>
      {reports.length===0 ? <p>No performance cycles to report.</p> : <table><thead><tr><th>Cycle</th><th>Goals</th><th>Reviews</th><th>Finalized</th><th>Average rating</th></tr></thead><tbody>
        {reports.map(r=><tr key={String(r.cycleId)}><td>{String(r.cycleName)}</td><td>{String(r.goalCount)}</td><td>{String(r.reviewCount)}</td><td>{String(r.finalizedCount)}</td><td>{String(r.averageRating??'—')}</td></tr>)}
      </tbody></table>}
    </div>
    <div className="performance-grid">
      <div className="performance-panel"><h2>Review cycles</h2>
        <label>Company <select value={companyId} onChange={e => setCompanyId(e.target.value)}>{companies.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
        <label>Cycle <select value={cycleId} onChange={e => setCycleId(e.target.value)}><option value="">Select cycle</option>{cycles.map(c => <option key={c.id} value={c.id}>{c.name} ({c.status})</option>)}</select></label>
        {cycles.find(c => c.id === cycleId)?.status === 'DRAFT' && <button disabled={busy} onClick={() => void run(() => request(token, '/cycles/' + cycleId + '/activate', 'POST'))}>Activate selected cycle</button>}
        <form onSubmit={(e: FormEvent) => { e.preventDefault(); void run(async () => { const created = await request<{id:string}>(token, '/cycles', 'POST', { companyId, name, startsOn, endsOn }); setCycleId(created.id); }); }}>
          <h3>Create cycle</h3><input aria-label="Cycle name" required maxLength={160} value={name} onChange={e => setName(e.target.value)} />
          <label>Starts <input type="date" required value={startsOn} onChange={e => setStartsOn(e.target.value)} /></label>
          <label>Ends <input type="date" required value={endsOn} onChange={e => setEndsOn(e.target.value)} /></label>
          <button disabled={busy || !companyId}>Create cycle</button>
        </form>
      </div>
      <div className="performance-panel"><h2>Goals & KPIs</h2>
        <p>Weighted attainment: {scores.length ? scores.map(sc => `${employees.find(e => e.id === sc.employeeId)?.firstName || 'Employee'} ${String(sc.attainmentPercent ?? 0)}%`).join(' · ') : 'No measurements yet'}</p>
        <label>Employee <select value={employeeId} onChange={e => setEmployeeId(e.target.value)}><option value="">Select employee</option>{employees.map(e => <option key={e.id} value={e.id}>{e.firstName} {e.lastName} ({e.employeeNumber})</option>)}</select></label>
        <form onSubmit={e => { e.preventDefault(); void run(() => request(token, '/goals', 'POST', { cycleId, employeeId, title, weight: 1, metric: 'Percentage', targetValue: Number(target) })); }}>
          <input aria-label="Goal title" placeholder="Goal or KPI" required value={title} onChange={e => setTitle(e.target.value)} />
          <label>Target value <input type="number" min="0" required value={target} onChange={e => setTarget(e.target.value)} /></label>
          <button disabled={busy || !active || !employeeId}>Add goal</button>
        </form>
        <form onSubmit={e => { e.preventDefault(); void run(() => request(token, '/goals/' + goalId + '/progress', 'POST', { actualValue: Number(progress), note: progressNote })); }}>
          <h3>Record KPI progress</h3>
          <label>Goal <select value={goalId} onChange={e => setGoalId(e.target.value)}><option value="">Select a goal</option>{goals.map(g => <option key={String(g.id)} value={String(g.id)}>{String(g.title)}</option>)}</select></label>
          <label>Current value <input type="number" min="0" step=".001" value={progress} onChange={e => setProgress(e.target.value)} /></label>
          <input aria-label="Progress notes" maxLength={2000} placeholder="Progress notes" value={progressNote} onChange={e => setProgressNote(e.target.value)} />
          <button disabled={busy || !active || !goalId}>Record progress</button>
        </form>
        <ul>{goals.map(g => <li key={String(g.id)}>
          {String(g.title)} · {String(g.actualValue ?? 0)} / {String(g.targetValue ?? '—')} · {String(g.status)}
          <button type="button" disabled={busy} onClick={() => {setEditGoalId(String(g.id));setEditTitle(String(g.title));setEditTarget(String(g.targetValue??100));}}>Edit</button>
          <button type="button" disabled={busy} onClick={() => void request<Item[]>(token,'/goals/'+String(g.id)+'/progress').then(history => {setHistoryGoal(String(g.id));setProgressHistory(history);}).catch(e=>setError(String(e)))}>History</button>
        </li>)}</ul>
        {historyGoal && <div className="performance-history"><h3>Progress history — {goals.find(g=>g.id===historyGoal)?.title}</h3><ul>{progressHistory.map((entry,i)=><li key={i}>{String(entry.actualValue)} · {String(entry.note||'No note')} · {String(entry.recordedAt||'')}</li>)}</ul><button type="button" onClick={()=>setHistoryGoal('')}>Close history</button></div>}
        {editGoalId && <form onSubmit={e=>{e.preventDefault();void run(async()=>{await request(token,'/goals/'+editGoalId,'PUT',{title:editTitle,targetValue:Number(editTarget),weight:Number(goals.find(g=>g.id===editGoalId)?.weight??1),description:goals.find(g=>g.id===editGoalId)?.description??null,metric:goals.find(g=>g.id===editGoalId)?.metric??null,dueOn:goals.find(g=>g.id===editGoalId)?.dueOn??null});setEditGoalId('');});}}>
          <h3>Edit goal</h3>
          <input aria-label="Edit goal title" required value={editTitle} onChange={e=>setEditTitle(e.target.value)}/>
          <label>Target <input type="number" step=".001" min=".001" required value={editTarget} onChange={e=>setEditTarget(e.target.value)}/></label>
          <button disabled={busy}>Save goal</button><button type="button" onClick={()=>setEditGoalId('')}>Cancel</button>
        </form>}
      </div>
      <div className="performance-panel"><h2>Employee account linking</h2>
        <p>Connect an employee masterfile record to an existing active login account to enable self-service. Links must be unique.</p>
        <form onSubmit={e => { e.preventDefault(); void run(() => request(token, '/identity-links', 'POST', { employeeId, identityId: accountId })); }}>
          <label>Employee <select value={employeeId} onChange={e => setEmployeeId(e.target.value)}><option value="">Select employee</option>{employees.map(e => <option key={e.id} value={e.id}>{e.firstName} {e.lastName}</option>)}</select></label>
          <label>Account UUID <input value={accountId} onChange={e => setAccountId(e.target.value)} placeholder="Existing workspace account UUID" required pattern="[a-fA-F0-9-]{36}" /></label>
          <button disabled={busy || !employeeId}>Link login to employee</button>
        </form>
      </div>
      <div className="performance-panel"><h2>Manager evaluations</h2>
        <form onSubmit={e => { e.preventDefault(); void run(() => request(token, '/reviews', 'POST', { cycleId, employeeId, rating: Number(rating), managerComments: comments })); }}>
          <label>Rating (1–5) <input type="number" min="1" max="5" step=".5" value={rating} onChange={e => setRating(e.target.value)} required /></label>
          <textarea aria-label="Manager comments" placeholder="Evidence and feedback" value={comments} onChange={e => setComments(e.target.value)} />
          <button disabled={busy || !active || !employeeId}>Submit evaluation</button>
        </form>
        <button type="button" disabled={busy || !active || !employeeId} onClick={() => void run(() => request(token, '/reviews/drafts', 'POST', { cycleId, employeeId }))}>Create review draft (self-assessment workflow)</button>
        <ul>{reviews.map(r => <li key={String(r.id)}>
          {employees.find(e => e.id === r.employeeId)?.firstName || 'Employee'} · {String(r.rating ?? 'Pending')} / 5 · {String(r.status)}
          {r.status === 'ACKNOWLEDGED' && <button type="button" disabled={busy} onClick={() => void run(() => request(token, '/reviews/' + String(r.id) + '/finalize', 'POST'))}>Finalize</button>}
        </li>)}</ul>
      </div>
      <div className="performance-panel"><h2>Development plans</h2>
        <label>Review <select value={reviewId} onChange={e => setReviewId(e.target.value)}><option value="">Select review</option>{reviews.map(r => <option key={String(r.id)} value={String(r.id)}>{employees.find(e => e.id === r.employeeId)?.firstName || 'Employee'} · {String(r.rating)}/5</option>)}</select></label>
        <form onSubmit={e => { e.preventDefault(); void run(() => request(token, '/reviews/' + reviewId + '/plans', 'POST', { title: planTitle, actionItems: actions })); }}>
          <input aria-label="Plan title" required placeholder="Development objective" value={planTitle} onChange={e => setPlanTitle(e.target.value)} />
          <textarea aria-label="Action items" required placeholder="Specific actions, coaching, training…" value={actions} onChange={e => setActions(e.target.value)} />
          <button disabled={busy || !reviewId}>Create development plan</button>
        </form>
        <ul>{plans.map(p => <li key={String(p.id)}>{String(p.title)} · {String(p.status)} {['PLANNED','IN_PROGRESS'].includes(String(p.status)) && <button type="button" disabled={busy} onClick={() => void run(() => request(token, '/plans/' + String(p.id) + '/status', 'POST', { status: p.status === 'PLANNED' ? 'IN_PROGRESS' : 'COMPLETED' }))}>{p.status === 'PLANNED' ? 'Start' : 'Complete'}</button>}</li>)}</ul>
        <MilestonePanel token={token} planId={reviewId ? String(plans.find(p=>p.reviewId===reviewId)?.id||'') : ''}/>
      </div>
    </div>
  </section>;
}
