import { useCallback, useEffect, useState, type FormEvent } from 'react';

type Announcement = {
  id: string;
  title: string;
  body: string;
  priority: 'NORMAL' | 'IMPORTANT' | 'URGENT';
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  publishedAt: string | null;
  expiresOn: string | null;
  pinned: boolean;
};

type Props = { token: string; canManage: boolean; onOpenSecurity: () => void };
const emptyForm = { title: '', body: '', priority: 'NORMAL' as Announcement['priority'], expiresOn: '', pinned: false };

export function AnnouncementsWorkspace({ token, canManage, onOpenSecurity }: Props) {
  const [items, setItems] = useState<Announcement[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('ALL');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const response = await fetch('/api/announcements?size=100&sort=publishedAt,desc', { headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error(response.status === 403 ? 'Your account cannot view announcements.' : 'Could not load announcements.');
      const data = await response.json() as { content?: Announcement[] };
      setItems(data.content ?? []);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load announcements.');
    } finally { setLoading(false); }
  }, [token]);

  useEffect(() => { void load(); }, [load]);

  const resetForm = () => { setEditing(null); setForm(emptyForm); };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true); setError(''); setNotice('');
    try {
      const response = await fetch(editing ? `/api/announcements/${editing}` : '/api/announcements', {
        method: editing ? 'PUT' : 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...form, expiresOn: form.expiresOn || null }),
      });
      if (response.status === 403) throw new Error('Authoring requires workspace owner or HR administrator access with MFA verified.');
      if (!response.ok) throw new Error('Could not save this announcement. Check the fields and try again.');
      setNotice(editing ? 'Announcement updated.' : 'Draft created.'); resetForm(); await load();
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not save this announcement.'); }
    finally { setBusy(false); }
  };

  const transition = async (id: string, action: 'publish' | 'archive') => {
    setBusy(true); setError(''); setNotice('');
    try {
      const response = await fetch(`/api/announcements/${id}/${action}`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } });
      if (response.status === 403) throw new Error('Authoring requires workspace owner or HR administrator access with MFA verified.');
      if (!response.ok) throw new Error(`Could not ${action} this announcement.`);
      setNotice(action === 'publish' ? 'Announcement published.' : 'Announcement archived.'); await load();
    } catch (cause) { setError(cause instanceof Error ? cause.message : `Could not ${action} announcement.`); }
    finally { setBusy(false); }
  };

  const beginEdit = (item: Announcement) => {
    setEditing(item.id); setForm({ title: item.title, body: item.body, priority: item.priority, expiresOn: item.expiresOn ?? '', pinned: item.pinned });
    document.querySelector('.announcements-editor')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  const visible = items.filter(item => (filter === 'ALL' || item.status === filter)
    && `${item.title} ${item.body}`.toLowerCase().includes(query.toLowerCase()));

  return <main className="announcements-page">
    <header className="announcements-header"><div><p className="eyebrow">WORKSPACE UPDATES</p><h1>Announcements</h1><p>Share important updates with everyone in your workspace.</p></div><span className="announcements-count">{items.length} {items.length === 1 ? 'announcement' : 'announcements'}</span></header>
    {error && <div className="announcements-alert" role="alert">{error}{error.includes('MFA verified') && <button type="button" onClick={onOpenSecurity}>Open security settings</button>}</div>}
    {notice && <p className="announcements-notice" role="status">{notice}</p>}
    {canManage && <form className="announcements-editor" onSubmit={submit}>
      <div className="announcements-section-heading"><div><p className="eyebrow">{editing ? 'EDIT UPDATE' : 'COMPOSE'}</p><h2>{editing ? 'Edit announcement' : 'Create an announcement'}</h2></div>{editing && <button type="button" className="announcements-secondary" onClick={resetForm}>Cancel edit</button>}</div>
      <label>Title<input value={form.title} onChange={event => setForm({ ...form, title: event.currentTarget.value })} required maxLength={200} placeholder="What should your team know?" /></label>
      <label>Message<textarea value={form.body} onChange={event => setForm({ ...form, body: event.currentTarget.value })} required maxLength={20000} rows={5} placeholder="Write your update…" /></label>
      <div className="announcements-form-row"><label>Priority<select value={form.priority} onChange={event => setForm({ ...form, priority: event.currentTarget.value as Announcement['priority'] })}><option value="NORMAL">Normal</option><option value="IMPORTANT">Important</option><option value="URGENT">Urgent</option></select></label><label>Expires on (optional)<input type="date" value={form.expiresOn} onChange={event => setForm({ ...form, expiresOn: event.currentTarget.value })} /></label><label className="announcements-check"><input type="checkbox" checked={form.pinned} onChange={event => setForm({ ...form, pinned: event.currentTarget.checked })} /> Pin announcement</label></div>
      <button className="announcements-primary" disabled={busy}>{busy ? 'Saving…' : editing ? 'Save changes' : 'Save draft'}</button>
      <small>Publishing a draft makes it visible to workspace members. Authoring requires MFA.</small>
    </form>}
    <section className="announcements-feed"><div className="announcements-section-heading"><div><p className="eyebrow">{canManage ? 'MANAGE UPDATES' : 'LATEST UPDATES'}</p><h2>{canManage ? 'All announcements' : 'From your workspace'}</h2></div><div className="announcements-controls"><input aria-label="Search announcements" placeholder="Search updates" value={query} onChange={event => setQuery(event.currentTarget.value)} /><select aria-label="Filter announcements" value={filter} onChange={event => setFilter(event.currentTarget.value)}>{(canManage ? ['ALL', 'PUBLISHED', 'DRAFT', 'ARCHIVED'] : ['ALL', 'PUBLISHED']).map(value => <option key={value} value={value}>{value === 'ALL' ? 'All status' : value[0] + value.slice(1).toLowerCase()}</option>)}</select></div></div>
      {loading ? <p className="announcements-empty">Loading announcements…</p> : visible.length === 0 ? <p className="announcements-empty">{query ? 'No announcements match your search.' : 'No announcements yet.'}</p> : <div className="announcements-list">{visible.map(item => <article className={`announcement-card priority-${item.priority.toLowerCase()}`} key={item.id}><div className="announcement-card-top"><div className="announcement-tags"><span className={`announcement-status ${item.status.toLowerCase()}`}>{item.status}</span><span className={`announcement-priority ${item.priority.toLowerCase()}`}>{item.priority}</span>{item.pinned && <span className="announcement-pin">PINNED</span>}</div><time>{item.publishedAt ? new Date(item.publishedAt).toLocaleDateString() : 'Draft'}{item.expiresOn ? ` · Expires ${new Date(`${item.expiresOn}T00:00:00`).toLocaleDateString()}` : ''}</time></div><h3>{item.title}</h3><p className="announcement-body">{item.body}</p>{canManage && <div className="announcement-actions">{item.status !== 'ARCHIVED' && <button type="button" className="announcements-secondary" onClick={() => beginEdit(item)}>Edit</button>}{item.status === 'DRAFT' && <button type="button" className="announcements-primary" disabled={busy} onClick={() => void transition(item.id, 'publish')}>Publish</button>}{item.status !== 'ARCHIVED' && <button type="button" className="announcements-secondary" disabled={busy} onClick={() => void transition(item.id, 'archive')}>Archive</button>}</div>}</article>)}</div>}
    </section>
  </main>;
}
