import { LearningAccountLinks } from './LearningAccountLinks';
import { LearningReports } from './LearningReports';
import { useCallback, useEffect, useState, type FormEvent } from 'react';

type RecordRow = Record<string, unknown> & { id: string };
type Props = { token: string; canManage: boolean; canLinkAccounts: boolean; onOpenSecurity: () => void };
const sections = ['courses', 'enrollments', 'skills', 'employee-skills', 'certifications'] as const;
type Section = typeof sections[number];
const titles: Record<Section, string> = {
  courses: 'Training courses', enrollments: 'Enrollments & history',
  skills: 'Skill catalog', 'employee-skills': 'Employee skills', certifications: 'Certifications',
};
const fields: Record<Section, { name: string; label: string; type?: string; required?: boolean; options?: string[] }[]> = {
  courses: [
    { name: 'companyId', label: 'Company ID', required: true }, { name: 'code', label: 'Course code', required: true },
    { name: 'title', label: 'Course title', required: true }, { name: 'description', label: 'Description' },
    { name: 'deliveryMode', label: 'Delivery mode', options: ['ONLINE', 'CLASSROOM', 'BLENDED', 'SELF_PACED'] },
    { name: 'durationHours', label: 'Duration (hours)', type: 'number', required: true },
  ],
  enrollments: [{ name: 'courseId', label: 'Course', required: true }, { name: 'employeeId', label: 'Employee', required: true }],
  skills: [{ name: 'name', label: 'Skill name', required: true }, { name: 'category', label: 'Category' }],
  'employee-skills': [
    { name: 'employeeId', label: 'Employee', required: true }, { name: 'skillId', label: 'Skill', required: true },
    { name: 'level', label: 'Proficiency (1–5)', type: 'number', required: true },
  ],
  certifications: [
    { name: 'employeeId', label: 'Employee', required: true }, { name: 'courseId', label: 'Related course (optional)' },
    { name: 'title', label: 'Certificate title', required: true }, { name: 'issuer', label: 'Issued by', required: true },
    { name: 'credentialRef', label: 'Credential reference' },
    { name: 'issuedOn', label: 'Issued on', type: 'date', required: true },
    { name: 'expiresOn', label: 'Expires on', type: 'date' },
  ],
};
function readable(value: unknown) { return value === null || value === undefined ? '—' : String(value); }
export function LearningWorkspace({ token, canManage, canLinkAccounts, onOpenSecurity }: Props) {
  const [tab, setTab] = useState<Section>('courses');
  const [data, setData] = useState<Record<Section, RecordRow[]>>({
    courses: [], enrollments: [], skills: [], 'employee-skills': [], certifications: [],
  });
  const [employees, setEmployees] = useState<RecordRow[]>([]);
  const [companyId, setCompanyId] = useState('');
  const [companies, setCompanies] = useState<RecordRow[]>([]);
  const [showReports, setShowReports] = useState(false);
  const [showAccountLinks, setShowAccountLinks] = useState(false);
  const [form, setForm] = useState<Record<string, string>>({ deliveryMode: 'ONLINE', durationHours: '0', level: '1' });
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [query, setQuery] = useState('');
  const [completionScores, setCompletionScores] = useState<Record<string,string>>({});
  const headers = { Authorization: `Bearer ${token}` };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const responses = await Promise.all([...sections, 'companies'].map(s => fetch(`/api/v1/learning/${s}`, { headers })));
      if (responses.some(r => !r.ok)) throw new Error('Unable to load learning data. Check your permissions.');
      const items = await Promise.all(responses.map(r => r.json() as Promise<RecordRow[]>));
      setData(Object.fromEntries(sections.map((s, index) => [s, items[index]])) as Record<Section, RecordRow[]>);
      setCompanies(items[sections.length]);
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Unable to load learning data.'); }
    finally { setLoading(false); }
  }, [token]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!companyId || !canManage) { setEmployees([]); return; }
    let active = true;
    void fetch(`/api/v1/learning/employees?companyId=${encodeURIComponent(companyId)}`, { headers })
      .then(r => r.ok ? r.json() as Promise<RecordRow[]> : [])
      .then(rows => { if (active) setEmployees(rows); }).catch(() => { if (active) setEmployees([]); });
    return () => { active = false; };
  }, [companyId, canManage, token]);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('');
    const payload: Record<string, unknown> = {};
    fields[tab].forEach(field => {
      const value = field.name === 'companyId' ? (form.companyId || companyId) : (form[field.name] || '');
      payload[field.name] = value === '' ? null : field.type === 'number' ? Number(value) : value;
    });
    try {
      const response = await fetch(`/api/v1/learning/${tab}`, {
        method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
      });
      if (!response.ok) throw new Error(response.status === 403 ? 'Verified HR administrator access is required.' : `Unable to save record (HTTP ${response.status}). Check the values and duplicates.`);
      setNotice('Record saved successfully.');
      setForm({ companyId: companyId || '', deliveryMode: 'ONLINE', durationHours: '0', level: '1' });
      await load();
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Unable to save.'); }
    finally { setBusy(false); }
  }
  async function transition(id: string, status: string) {
    setBusy(true); setError(''); setNotice('');
    try {
      const response = await fetch(`/api/v1/learning/enrollments/${id}`, {
        method: 'PATCH', headers: { ...headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({ status, score: status === 'COMPLETED' && completionScores[id] ? Number(completionScores[id]) : null }),
      });
      if (!response.ok) throw new Error(`Could not update enrollment (HTTP ${response.status}).`);
      setNotice(`Enrollment changed to ${status.toLowerCase().replaceAll('_', ' ')}.`);
      await load();
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Update failed.'); }
    finally { setBusy(false); }
  }
  const shown = data[tab].filter(row => JSON.stringify(row).toLowerCase().includes(query.toLowerCase()));
  function fieldControl(field: (typeof fields)[Section][number]) {
    const value = field.name === 'companyId' ? (form.companyId || companyId) : (form[field.name] || '');
    const options = field.name === 'courseId' ? data.courses.filter(c => !companyId || c.companyId === companyId).map(c => ({ value: String(c.id), label: String(c.title) }))
      : field.name === 'skillId' ? data.skills.map(s => ({ value: String(s.id), label: String(s.name) }))
      : field.name === 'employeeId' ? employees.map(e => ({ value: String(e.id), label: `${e.name} (${e.employeeNumber})` }))
      : null;
    if (field.name === 'companyId') return <select required value={value} onChange={e => { setCompanyId(e.target.value); setForm({ ...form, companyId: e.target.value }); }}><option value="">Select company</option>{companies.map(company => <option key={company.id} value={company.id}>{String(company.name)}</option>)}</select>;
    if (options || field.options) return <select required={field.required} value={value} onChange={e => setForm({ ...form, [field.name]: e.target.value })}>
      <option value="">Select {field.label.toLowerCase()}</option>
      {(options || field.options!.map(v => ({ value: v, label: v.replaceAll('_', ' ') }))).map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
    </select>;
    return <input required={field.required} type={field.type || 'text'} value={value} min={field.name === 'level' ? 1 : field.type === 'number' ? 0 : undefined} max={field.name === 'level' ? 5 : undefined} onChange={e => setForm({ ...form, [field.name]: e.target.value })} />;
  }
  return <main className="announcements-page">
    <header className="announcements-header"><div><p className="eyebrow">PEOPLE DEVELOPMENT</p><h1>Learning &amp; development</h1><p>Manage training, enrollment progress, skills and employee credentials.</p></div><span className="announcements-count">{data.courses.length} courses</span></header>
    {error && <div className="announcements-alert" role="alert">{error} {error.includes('Verified') && <button type="button" onClick={onOpenSecurity}>Open MFA settings</button>}</div>}
    {notice && <p className="announcements-notice" role="status">{notice}</p>}
    <div className="account-tabs" role="tablist" aria-label="Learning sections">
      {sections.map(section => <button type="button" key={section} role="tab" aria-selected={!showReports && !showAccountLinks && tab === section} className={!showReports && !showAccountLinks && tab === section ? 'active' : ''} onClick={() => { setShowAccountLinks(false); setShowReports(false); setTab(section); setError(''); setQuery(''); }}>{titles[section]}</button>)}
      <button type="button" role="tab" aria-selected={showReports} className={showReports ? 'active' : ''} onClick={() => { setShowReports(true); setShowAccountLinks(false); }}>Reports &amp; analytics</button>
      {canLinkAccounts && <button type="button" role="tab" aria-selected={showAccountLinks} className={showAccountLinks ? 'active' : ''} onClick={() => { setShowAccountLinks(true); setShowReports(false); }}>Account links</button>}
    </div>
    {showAccountLinks ? <LearningAccountLinks token={token} /> : showReports ? <LearningReports token={token} /> : <>
    {canManage && <div className="announcements-editor"><label>Company<select value={companyId} onChange={event => { setCompanyId(event.target.value); setForm(previous => ({ ...previous, companyId: event.target.value, employeeId: '', courseId: '' })); }}><option value="">Select a company</option>{companies.map(company => <option key={company.id} value={company.id}>{String(company.name)}</option>)}</select></label></div>}
    {canManage && <form className="announcements-editor" onSubmit={event => void create(event)}>
      <h2>Add {titles[tab].toLowerCase().replace(/s$/, '')}</h2>
      <p className="security-description">Select a company and choose employees or courses from the lists.</p>
      <div className="announcements-form-row" style={{ flexWrap: 'wrap' }}>
        {fields[tab].map(field => <label key={field.name} style={{ minWidth: 180, flex: 1 }}>{field.label}{fieldControl(field)}</label>)}
      </div>
      <button type="submit" className="announcements-primary" disabled={busy}>{busy ? 'Saving…' : 'Save record'}</button>
    </form>}
    <section className="announcements-feed"><div className="announcements-section-heading"><h2>{titles[tab]}</h2><input aria-label="Search learning records" placeholder="Search records" value={query} onChange={e => setQuery(e.target.value)} /></div>
      {loading ? <p>Loading…</p> : shown.length === 0 ? <p className="announcements-empty">No records found.</p> :
        <div className="announcements-list">{shown.map(row => <article className="announcement-card" key={row.id}>
          <h3>{readable(row.title || row.name || row.courseTitle || row.skillName || row.id)}</h3>
          <div>{Object.entries(row).filter(([key]) => key !== 'id').map(([key, value]) => <p key={key}><strong>{key.replace(/([A-Z])/g, ' $1')}:</strong> {readable(value)}</p>)}</div>
          {tab === 'enrollments' && canManage && row.status !== 'COMPLETED' && row.status !== 'CANCELLED' && <div className="announcement-actions">
            {row.status === 'ENROLLED' && <button type="button" className="announcements-secondary" disabled={busy} onClick={() => void transition(row.id, 'IN_PROGRESS')}>Start</button>}
            {row.status === 'IN_PROGRESS' && <><label>Score (optional, 0–100)<input type="number" min={0} max={100} value={completionScores[row.id] || ''} onChange={event => setCompletionScores(values => ({ ...values, [row.id]: event.target.value }))} /></label><button type="button" className="announcements-primary" disabled={busy || (completionScores[row.id] !== undefined && completionScores[row.id] !== '' && (Number(completionScores[row.id]) < 0 || Number(completionScores[row.id]) > 100))} onClick={() => void transition(row.id, 'COMPLETED')}>Complete</button></>}
            <button type="button" className="announcements-secondary" disabled={busy} onClick={() => void transition(row.id, 'CANCELLED')}>Cancel</button>
          </div>}
        </article>)}</div>}
    </section></>}
  </main>;
}
