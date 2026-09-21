import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { ReviewCycle } from '../../api/types';
import { useAuth } from '../../app/AuthContext';
import { ReferenceDropdown } from '../../components/ReferenceDropdown';
import { ReviewDetail } from './ReviewDetail';
import { StatusBadge } from './StatusBadge';

export function TeamTab() {
  const { hasAnyAuthority } = useAuth();
  const cycles = useQuery({ queryKey: ['performance', 'cycles', { status: 'DRAFT,OPEN,IN_PROGRESS,CALIBRATION,CLOSED' }], queryFn: () => api.performance.listCycles({ status: 'DRAFT,OPEN,IN_PROGRESS,CALIBRATION,CLOSED' }) });
  const [cycleId, setCycleId] = useState<number | null>(null);
  const [department, setDepartment] = useState<string | null>(null);
  const [selectedReview, setSelectedReview] = useState<number | null>(null);
  const cycle: ReviewCycle | undefined = cycles.data?.find((value) => value.cycleId === (cycleId ?? cycles.data?.[0]?.cycleId));
  const selectedCycleId = cycle?.cycleId;
  const team = useQuery({ queryKey: ['performance', 'team', selectedCycleId], queryFn: () => api.performance.listTeamReviews(selectedCycleId!), enabled: selectedCycleId !== undefined });
  const distribution = useQuery({ queryKey: ['performance', 'distribution', selectedCycleId, department], queryFn: () => api.performance.getRatingDistribution(selectedCycleId!, { deptId: department ? Number(department) : undefined }), enabled: selectedCycleId !== undefined && hasAnyAuthority('PERFORMANCE:VIEW', 'PERFORMANCE:ADMIN') });
  return (
    <section aria-labelledby="team-title">
      <h2 id="team-title">Team</h2>
      <div className="field">
        <label htmlFor="team-cycle">Cycle</label>
        <select id="team-cycle" value={selectedCycleId ?? ''} onChange={(event) => { setCycleId(Number(event.target.value)); setSelectedReview(null); }}>
          {(cycles.data ?? []).map((value) => <option key={value.cycleId} value={value.cycleId}>{value.cycleName}</option>)}
        </select>
      </div>
      <h3>Team reviews</h3>
      {team.isPending ? <p role="status">Loading team reviews…</p> : team.isError ? <p role="alert">Could not load team reviews.</p> : team.data?.length ? (
        <table className="grid">
          <thead><tr><th>Employee</th><th>Job title</th><th>Department</th><th>Status</th><th>Rating</th><th>Label</th><th /></tr></thead>
          <tbody>{team.data.map((row) => <tr key={row.reviewId}><td>{row.employeeName}</td><td>{row.jobTitle}</td><td>{row.deptName}</td><td><StatusBadge status={row.status} /></td><td>{row.overallRating ?? '—'}</td><td>{row.ratingLabel ?? '—'}</td><td><button type="button" onClick={() => setSelectedReview(row.reviewId)}>Review</button> <Link to={`/performance/goals?reviewId=${row.reviewId}`}>Goals</Link></td></tr>)}</tbody>
        </table>
      ) : <p>No direct-report reviews in this cycle</p>}
      {selectedReview !== null && <ReviewDetail reviewId={selectedReview} />}
      {hasAnyAuthority('PERFORMANCE:VIEW', 'PERFORMANCE:ADMIN') && (
        <section>
          <h3>Rating distribution</h3>
          <ReferenceDropdown source="departments" label="Department" value={department} onChange={setDepartment} />
          {distribution.isPending ? <p role="status">Loading rating distribution…</p> : distribution.isError ? <p role="alert">Could not load rating distribution.</p> : distribution.data?.length ? (
            <table className="grid"><thead><tr><th>Label</th><th>Count</th><th>%</th><th /></tr></thead><tbody>{distribution.data.map((row) => <tr key={row.ratingLabel}><td>{row.ratingLabel}</td><td>{row.count}</td><td>{row.percentage.toFixed(1)}</td><td><div className="bar" style={{ width: `${row.percentage}%` }} /></td></tr>)}</tbody></table>
          ) : <p>No rated reviews</p>}
        </section>
      )}
    </section>
  );
}
