import { useEffect, useState } from 'react';

type Row = Record<string, unknown>;
type PortalData = { enrollments: Row[]; skills: Row[]; certifications: Row[] };
const headings: { title: string; key: keyof PortalData }[] = [
  { title: 'My courses & training history', key: 'enrollments' },
  { title: 'My skills', key: 'skills' },
  { title: 'My certifications', key: 'certifications' },
];
export function MyLearningWorkspace({ token }: { token: string }) {
  const [data, setData] = useState<PortalData | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError('');
    void fetch('/api/v1/learning/me', { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal })
      .then(async response => {
        if (response.status === 404) throw new Error('Your learning profile has not been linked to your employee record. Contact your workspace owner.');
        if (!response.ok) throw new Error('Unable to load your learning records.');
        return response.json() as Promise<PortalData>;
      })
      .then(setData)
      .catch(reason => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Unable to load learning records.'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [token]);
  return <main className="announcements-page">
    <header className="announcements-header"><div><p className="eyebrow">EMPLOYEE LEARNING PORTAL</p><h1>My learning</h1><p>Your assigned courses, training history, skills and certifications.</p></div></header>
    {loading && <p>Loading your learning profile…</p>}
    {error && <div className="announcements-alert" role="alert">{error}</div>}
    {data && headings.map(section => <section className="announcements-feed" key={section.key}>
      <div className="announcements-section-heading"><h2>{section.title}</h2><span>{data[section.key].length} records</span></div>
      {!data[section.key].length ? <p className="announcements-empty">No records yet.</p> :
        <div className="announcements-list">{data[section.key].map((item, index) =>
          <article className="announcement-card" key={String(item.id || index)}>
            <h3>{String(item.courseTitle || item.skillName || item.title || 'Learning record')}</h3>
            {Object.entries(item).filter(([key]) => key !== 'id').map(([key, value]) =>
              <p key={key}><strong>{key.replace(/([A-Z])/g, ' $1')}:</strong> {value == null ? '—' : String(value)}</p>)}
          </article>)}</div>}
    </section>)}
  </main>;
}
