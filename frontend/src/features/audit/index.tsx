import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { auditApi, type AuditEntry, type AuditFilter } from './api';

type Tab = 'search' | 'trail' | 'purge';

const TABS: { id: Tab; label: string }[] = [
  { id: 'search', label: 'Search' },
  { id: 'trail', label: 'Record trail' },
  { id: 'purge', label: 'Purge' },
];

function message(err: unknown): string {
  return err instanceof ApiError ? `${err.message} (${err.code})` : 'Unexpected error';
}

function formatValues(values?: Record<string, unknown> | null): string {
  return values ? JSON.stringify(values, null, 2) : '—';
}

export function AuditPage() {
  const [tab, setTab] = useState<Tab>('search');
  return (
    <section>
      <h1>Audit Log</h1>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)} type="button">
            {t.label}
          </button>
        ))}
      </div>
      {tab === 'search' && <Search />}
      {tab === 'trail' && <Trail />}
      {tab === 'purge' && <Purge />}
    </section>
  );
}

function EntryTable({ label, entries, onSelect }: { label: string; entries: AuditEntry[]; onSelect: (e: AuditEntry) => void }) {
  return (
    <table aria-label={label}>
      <thead>
        <tr><th>#</th><th>When</th><th>Table</th><th>Record</th><th>Action</th><th>Changed by</th><th /></tr>
      </thead>
      <tbody>
        {entries.map((e) => (
          <tr key={e.auditId}>
            <td>{e.auditId}</td><td>{e.changedDate.replace('T', ' ')}</td><td>{e.tableName}</td>
            <td>{e.recordId}</td><td>{e.action}</td><td>{e.changedBy}</td>
            <td><button type="button" onClick={() => onSelect(e)}>Details</button></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function EntryDetail({ auditId }: { auditId: number }) {
  const [entry, setEntry] = useState<AuditEntry | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    auditApi
      .get(auditId)
      .then((e) => active && setEntry(e))
      .catch((e) => active && setError(message(e)));
    return () => {
      active = false;
    };
  }, [auditId]);
  if (error) return <p role="alert" className="error">{error}</p>;
  if (!entry) return <p>Loading entry #{auditId}…</p>;
  return (
    <div className="card" aria-label="Entry details" role="region">
      <h2>Entry #{entry.auditId}</h2>
      <p>
        {entry.action} on {entry.tableName} #{entry.recordId} by {entry.changedBy} at {entry.changedDate.replace('T', ' ')}
      </p>
      <div className="row">
        <div>
          <h3>Old values</h3>
          <pre aria-label="Old values">{formatValues(entry.oldValues)}</pre>
        </div>
        <div>
          <h3>New values</h3>
          <pre aria-label="New values">{formatValues(entry.newValues)}</pre>
        </div>
      </div>
    </div>
  );
}

function Search() {
  const [filter, setFilter] = useState<AuditFilter>({ action: '', limit: '100' });
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const set = (key: keyof AuditFilter) => (e: { target: { value: string } }) =>
    setFilter((f) => ({ ...f, [key]: e.target.value }));

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSelected(null);
    if (filter.from && filter.to && filter.from > filter.to) {
      setError('From date must be on or before To date');
      return;
    }
    try {
      setEntries(await auditApi.search(filter));
    } catch (err) {
      setEntries(null);
      setError(message(err));
    }
  }

  return (
    <>
      <form className="card" onSubmit={submit} aria-label="Search audit log">
        <div className="row">
          <label>Table<input value={filter.tableName ?? ''} onChange={set('tableName')} placeholder="e.g. LEAVE_REQUESTS" /></label>
          <label>Record ID<input type="number" min={0} value={filter.recordId ?? ''} onChange={set('recordId')} /></label>
          <label>
            Action
            <select value={filter.action ?? ''} onChange={set('action')}>
              <option value="">Any</option>
              <option value="INSERT">INSERT</option>
              <option value="UPDATE">UPDATE</option>
              <option value="DELETE">DELETE</option>
            </select>
          </label>
          <label>Changed by<input value={filter.changedBy ?? ''} onChange={set('changedBy')} /></label>
        </div>
        <div className="row">
          <label>From<input type="date" value={filter.from ?? ''} onChange={set('from')} /></label>
          <label>To<input type="date" value={filter.to ?? ''} onChange={set('to')} /></label>
          <label>Limit<input type="number" min={1} max={1000} value={filter.limit ?? ''} onChange={set('limit')} /></label>
        </div>
        <button type="submit">Search</button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
      {entries && (
        <>
          <p>{entries.length} entr{entries.length === 1 ? 'y' : 'ies'} (newest first)</p>
          <EntryTable label="Audit entries" entries={entries} onSelect={(e) => setSelected(e.auditId)} />
        </>
      )}
      {selected !== null && <EntryDetail key={selected} auditId={selected} />}
    </>
  );
}

function Trail() {
  const [tableName, setTableName] = useState('');
  const [recordId, setRecordId] = useState('');
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSelected(null);
    try {
      setEntries(await auditApi.trail(tableName, recordId));
    } catch (err) {
      setEntries(null);
      setError(message(err));
    }
  }

  return (
    <>
      <form className="card" onSubmit={submit} aria-label="Record trail">
        <div className="row">
          <label>Table name<input value={tableName} onChange={(e) => setTableName(e.target.value)} required /></label>
          <label>Record number<input type="number" min={0} value={recordId} onChange={(e) => setRecordId(e.target.value)} required /></label>
        </div>
        <button type="submit">Show trail</button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
      {entries && entries.length === 0 && <p>No audit history for this record.</p>}
      {entries && entries.length > 0 && (
        <EntryTable label="Record trail" entries={entries} onSelect={(e) => setSelected(e.auditId)} />
      )}
      {selected !== null && <EntryDetail key={selected} auditId={selected} />}
    </>
  );
}

function Purge() {
  const [retentionDays, setRetentionDays] = useState('');
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setResult(null);
    const days = retentionDays ? Number(retentionDays) : undefined;
    if (days !== undefined && days < 30) {
      setError('Retention must be at least 30 days');
      return;
    }
    const scope = days ? `older than ${days} days` : 'older than the configured retention (AUDIT.RETENTION_DAYS)';
    if (!window.confirm(`Permanently delete audit entries ${scope}?`)) return;
    try {
      const r = await auditApi.purge(days);
      setResult(`Purged ${r.rowsAffected} audit entr${r.rowsAffected === 1 ? 'y' : 'ies'}`);
    } catch (err) {
      setError(message(err));
    }
  }

  return (
    <form className="card" onSubmit={submit} aria-label="Purge audit log">
      <p>Deletes entries older than the retention window. The purge itself is recorded in the audit log.</p>
      <label>
        Retention days (blank = system default)
        <input type="number" value={retentionDays} onChange={(e) => setRetentionDays(e.target.value)} />
      </label>
      {error && <p role="alert" className="error">{error}</p>}
      {result && <p role="status" className="success">{result}</p>}
      <button type="submit">Purge old entries</button>
    </form>
  );
}
