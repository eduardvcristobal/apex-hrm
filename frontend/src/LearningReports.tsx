import { useEffect, useState } from 'react';
type Row = Record<string, unknown>;
type Report = {
  totals: { enrollments: number; completed: number; inProgress: number; notStarted: number; cancelled: number };
  courses: Row[]; expiringCertifications: Row[]; skillDistribution: Row[];
};
export function LearningReports({ token }: { token: string }) {
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    void fetch('/api/v1/learning/reports/summary', { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error('Learning reports are unavailable.');
        return response.json() as Promise<Report>;
      })
      .then(setReport)
      .catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'Unable to load reports.'); });
    return () => controller.abort();
  }, [token]);
  if (error) return <div className="announcements-alert" role="alert">{error}</div>;
  if (!report) return <p>Loading learning analytics…</p>;
  const sections: { label: string; rows: Row[] }[] = [
    { label: 'Training completion by course', rows: report.courses },
    { label: 'Certifications expiring in the next 60 days', rows: report.expiringCertifications },
    { label: 'Skill assessment distribution (below level 3)', rows: report.skillDistribution },
  ];
  function exportCsv(name: string, rows: Row[]) {
    if (!rows.length) return;
    const columns = Object.keys(rows[0]);
    const escape = (value: unknown) => `"${String(value ?? '').replaceAll('"', '""')}"`;
    const csv = [columns.map(escape).join(','), ...rows.map(row => columns.map(key => escape(row[key])).join(','))].join('\r\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `learning-${name}.csv`; anchor.click();
    URL.revokeObjectURL(url);
  }
  return <section className="announcements-feed">
    <div className="announcements-section-heading"><div><p className="eyebrow">LEARNING INSIGHTS</p><h2>Training analytics</h2></div></div>
    <div className="announcements-form-row">
      {Object.entries(report.totals).map(([label, count]) => <article className="announcement-card" key={label}><p>{label.replace(/([A-Z])/g, ' $1')}</p><h2>{count}</h2></article>)}
    </div>
    {sections.map(section => <div key={section.label} className="announcements-feed">
      <div className="announcements-section-heading"><h3>{section.label}</h3><button type="button" className="announcements-secondary" disabled={!section.rows.length} onClick={() => exportCsv(section.label.toLowerCase().replace(/[^a-z0-9]+/g, '-'), section.rows)}>Export CSV</button></div>
      {!section.rows.length ? <p className="announcements-empty">No matching records.</p> :
        <div className="announcements-list">{section.rows.map((row, index) =>
          <article className="announcement-card" key={String(row.id || index)}>
            {Object.entries(row).map(([key, value]) => <p key={key}><strong>{key.replace(/([A-Z])/g, ' $1')}:</strong> {value == null ? '—' : String(value)}</p>)}
          </article>)}</div>}
    </div>)}
  </section>;
}
