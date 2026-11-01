import { useEffect, useState, type FormEvent } from 'react';

type Item = Record<string, unknown>;
type Cycle = { id: string; name: string; startsOn: string; endsOn: string; status: string };
type Employee = { id: string; firstName: string; lastName: string; employeeNumber: string };
async function request<T>(token: string, path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch('/api/v1/performance' + path, {
    method, headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) throw new Error(`Request failed (${response.status})`);
  return response.status === 204 || method === 'POST' && path.endsWith('/activate') ? undefined as T : await response.json() as T;
}
export function PerformanceWorkspace({ token }: { token: string }) {
  const [companyId, setCompanyId] = useState('');
  const [companies, setCompanies] = useState<{ id: string; name: string }[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [cycles, setCycles] = useState<Cycle[]>([]);
  const [cycleId, setCycleId] = useState('');
  const [goals, setGoals] = useState<Item[]>([]);
  const [reviews, setReviews] = useState<Item[]>([]);
  const [plans, setPlans] = useState<Item[]>([]);
  const [reviewId, setReviewId] = useState('');
  const [name, setName] = useState('Annual performance review');
  const [startsOn, setStartsOn] = useState(new Date().getFullYear() + '-01-01');
  const [endsOn, setEndsOn] = useState(new Date().getFullYear() + '-12-31');
  const [employeeId, setEmployeeId] = useState('');
  const [title, setTitle] = useState('');
  const [target, setTarget] = useState('100');
  const [rating, setRating] = useState('3');
  const [comments, setComments] = useState('');
  const [planTitle, setPlanTitle] = useState('');
  const [actions, setActions] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const auth = { Authorization: `Bearer ${token}` };
  async function loadCompany(id: string) {
    const [cy, emp] = await Promise.all([
      request<Cycle[]>(token, '/cycles?companyId=' + encodeURIComponent(id)),
      fetch('/api/v1/personnel/employees?companyId=' + encodeURIComponent(id), { headers: auth }).then(async r => { if (!r.ok) throw Error('Unable to fetch employees'); return await r.json() as Employee[]; }),
    ]);
    setCycles(cy); setEmployees(emp);
    if (!cy.some(c => c.id === cycleId)) setCycleId(cy[0]?.id || '');
    if (!emp.some(e => e.id === employeeId)) setEmployeeId(emp[0]?.id || '');
  }
  useEffect(() => {
    void fetch('/api/v1/companies', { headers: auth }).then(async r => {
      if (!r.ok) throw Error('Unable to fetch companies');
      const raw = await r.json() as { id: string; name?: string; companyName?: string; legalName?: string }[];
      setCompanies(raw.map(c => ({ id: c.id, name: c.legalName || c.companyName || c.name || c.id })));
      setCompanyId(old => old || raw[0]?.id || '');
    }).catch(e => setError(String(e)));
  }, [token]);
  useEffect(() => { if (companyId) void loadCompany(companyId).catch(e => setError(String(e))); }, [companyId, token]);
  useEffect(() => {
    if (!cycleId) { setGoals([]); setReviews([]); return; }
    void Promise.all([request<Item[]>(token, '/goals?cycleId=' + cycleId), request<Item[]>(token, '/reviews?cycleId=' + cycleId)])
      .then(([g, r]) => { setGoals(g); setReviews(r); if (!r.some(x => x.id === reviewId)) setReviewId(String(r[0]?.id || '')); })
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
        const [g, r] = await Promise.all([request<Item[]>(token, '/goals?cycleId=' + cycleId), request<Item[]>(token, '/reviews?cycleId=' + cycleId)]);
        setGoals(g); setReviews(r);
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
        <label>Employee <select value={employeeId} onChange={e => setEmployeeId(e.target.value)}><option value="">Select employee</option>{employees.map(e => <option key={e.id} value={e.id}>{e.firstName} {e.lastName} ({e.employeeNumber})</option>)}</select></label>
        <form onSubmit={e => { e.preventDefault(); void run(() => request(token, '/goals', 'POST', { cycleId, employeeId, title, weight: 1, metric: 'Percentage', targetValue: Number(target) })); }}>
          <input aria-label="Goal title" placeholder="Goal or KPI" required value={title} onChange={e => setTitle(e.target.value)} />
          <label>Target value <input type="number" min="0" required value={target} onChange={e => setTarget(e.target.value)} /></label>
          <button disabled={busy || !active || !employeeId}>Add goal</button>
        </form>
        <ul>{goals.map(g => <li key={String(g.id)}>{String(g.title)} · Target {String(g.targetValue ?? '—')} · {String(g.status)}</li>)}</ul>
      </div>
      <div className="performance-panel"><h2>Manager evaluations</h2>
        <form onSubmit={e => { e.preventDefault(); void run(() => request(token, '/reviews', 'POST', { cycleId, employeeId, rating: Number(rating), managerComments: comments })); }}>
          <label>Rating (1–5) <input type="number" min="1" max="5" step=".5" value={rating} onChange={e => setRating(e.target.value)} required /></label>
          <textarea aria-label="Manager comments" placeholder="Evidence and feedback" value={comments} onChange={e => setComments(e.target.value)} />
          <button disabled={busy || !active || !employeeId}>Submit evaluation</button>
        </form>
        <ul>{reviews.map(r => <li key={String(r.id)}>{employees.find(e => e.id === r.employeeId)?.firstName || 'Employee'} · {String(r.rating)} / 5 · {String(r.status)}</li>)}</ul>
      </div>
      <div className="performance-panel"><h2>Development plans</h2>
        <label>Review <select value={reviewId} onChange={e => setReviewId(e.target.value)}><option value="">Select review</option>{reviews.map(r => <option key={String(r.id)} value={String(r.id)}>{employees.find(e => e.id === r.employeeId)?.firstName || 'Employee'} · {String(r.rating)}/5</option>)}</select></label>
        <form onSubmit={e => { e.preventDefault(); void run(() => request(token, '/reviews/' + reviewId + '/plans', 'POST', { title: planTitle, actionItems: actions })); }}>
          <input aria-label="Plan title" required placeholder="Development objective" value={planTitle} onChange={e => setPlanTitle(e.target.value)} />
          <textarea aria-label="Action items" required placeholder="Specific actions, coaching, training…" value={actions} onChange={e => setActions(e.target.value)} />
          <button disabled={busy || !reviewId}>Create development plan</button>
        </form>
        <ul>{plans.map(p => <li key={String(p.id)}>{String(p.title)} · {String(p.status)}</li>)}</ul>
      </div>
    </div>
  </section>;
}
