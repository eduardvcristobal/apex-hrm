import { useEffect, useState, type FormEvent } from 'react';

type Account = { id: string; email: string; enabled: boolean };
type Employee = { id: string; name: string; employeeNumber: string };
type Company = { id: string; name: string };

export function LearningAccountLinks({ token }: { token: string }) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [companies, setCompanies] = useState<Company[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [companyId, setCompanyId] = useState('');
  const [identityId, setIdentityId] = useState('');
  const [employeeId, setEmployeeId] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const headers = { Authorization: `Bearer ${token}` };
  useEffect(() => {
    const controller = new AbortController();
    void Promise.all([
      fetch('/api/v1/admin/users', { headers, signal: controller.signal }),
      fetch('/api/v1/learning/companies', { headers, signal: controller.signal }),
    ]).then(async ([users, orgs]) => {
      if (!users.ok || !orgs.ok) throw new Error('Verify your administrator MFA to manage account links.');
      const [a, c] = await Promise.all([users.json() as Promise<Account[]>, orgs.json() as Promise<Company[]>]);
      setAccounts(a.filter(item => item.enabled));
      setCompanies(c);
    }).catch(error => { if (!controller.signal.aborted) setNotice(error instanceof Error ? error.message : 'Unable to load users.'); });
    return () => controller.abort();
  }, [token]);
  useEffect(() => {
    setEmployees([]); setEmployeeId('');
    if (!companyId) return;
    const controller = new AbortController();
    void fetch(`/api/v1/learning/employees?companyId=${encodeURIComponent(companyId)}`, { headers, signal: controller.signal })
      .then(response => response.ok ? response.json() as Promise<Employee[]> : [])
      .then(setEmployees).catch(() => setEmployees([]));
    return () => controller.abort();
  }, [token, companyId]);
  async function link(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setNotice('');
    try {
      const response = await fetch('/api/v1/learning/accounts', {
        method: 'PUT', headers: { ...headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({ identityId, employeeId }),
      });
      if (!response.ok) throw new Error(`Unable to link account (HTTP ${response.status}). Check existing links and access rights.`);
      setNotice('Employee account linked. The user can now see their own learning records.');
    } catch (error) { setNotice(error instanceof Error ? error.message : 'Unable to link account.'); }
    finally { setBusy(false); }
  }
  return <form className="announcements-editor" onSubmit={event => void link(event)}>
    <h2>Employee portal account linking</h2>
    <p>Workspace owners can explicitly associate a signed-in staff account with an employee record. Each employee can have one linked account.</p>
    <div className="announcements-form-row">
      <label>User account<select required value={identityId} onChange={event => setIdentityId(event.target.value)}><option value="">Select user</option>{accounts.map(account => <option key={account.id} value={account.id}>{account.email}</option>)}</select></label>
      <label>Company<select required value={companyId} onChange={event => setCompanyId(event.target.value)}><option value="">Select company</option>{companies.map(company => <option key={company.id} value={company.id}>{company.name}</option>)}</select></label>
      <label>Employee<select required value={employeeId} onChange={event => setEmployeeId(event.target.value)}><option value="">Select employee</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.name} · {employee.employeeNumber}</option>)}</select></label>
    </div>
    {notice && <p role="status">{notice}</p>}
    <button type="submit" className="announcements-primary" disabled={busy || !identityId || !employeeId}>{busy ? 'Linking…' : 'Link employee account'}</button>
  </form>;
}
