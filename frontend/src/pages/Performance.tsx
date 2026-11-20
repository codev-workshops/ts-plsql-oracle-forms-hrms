import { useState } from 'react';
import { api, call, type Schemas } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { ActionButton, body, DataTable, ErrorBanner, SimpleForm, useLoad } from '../components/ui';

type Review = Schemas['PerformanceReview'];

/** Mirrors the server's review status gates (PerformanceService) to hide forms that would be rejected. */
const SELF_ASSESSABLE = ['NOT_STARTED', 'SELF_REVIEW'];
const MANAGER_REVIEWABLE = ['MANAGER_REVIEW', 'MEETING_SCHEDULED'];

/** HRMS_PERFORMANCE: cycles (admin lifecycle), my reviews, goals. No delete actions (block DML policy). */
export function Performance() {
  const { has, user } = useAuth();
  const admin = has('PERFORMANCE', 'ADMIN');
  const cycles = useLoad(() => call(api.GET('/api/v1/performance/cycles', { params: { query: { includeClosed: true } } })), []);
  const [source, setSource] = useState<{ role: 'EMPLOYEE' | 'REVIEWER' } | { cycleId: number }>({ role: 'EMPLOYEE' });
  const reviews = useLoad(
    () =>
      'cycleId' in source
        ? call(api.GET('/api/v1/performance/cycles/{cycleId}/reviews', { params: { path: { cycleId: source.cycleId } } }))
        : call(api.GET('/api/v1/performance/reviews', { params: { query: { role: source.role } } })),
    [source],
  );
  const [selected, setSelected] = useState<Review | null>(null);
  const goals = useLoad(
    () =>
      selected
        ? call(api.GET('/api/v1/performance/reviews/{reviewId}/goals', { params: { path: { reviewId: selected.reviewId! } } }))
        : Promise.resolve([]),
    [selected?.reviewId],
  );
  const cyclePath = (c: Schemas['ReviewCycle']) => ({ path: { cycleId: c.cycleId! } });
  const reviewPath = selected ? { path: { reviewId: selected.reviewId! } } : { path: { reviewId: 0 } };
  const refresh = async (p: Promise<Review>) => {
    setSelected(await p);
    reviews.reload();
  };

  return (
    <section>
      <h2>Performance</h2>
      <h3>Review cycles</h3>
      <ErrorBanner error={cycles.error} />
      <DataTable
        label="Review cycles"
        rows={cycles.data}
        columns={[
          { header: 'Cycle', render: (c: Schemas['ReviewCycle']) => c.cycleName },
          { header: 'Year', render: (c) => c.cycleYear },
          { header: 'Status', render: (c) => c.status },
          {
            header: '',
            render: (c) =>
              admin && (
                <>
                  {c.status === 'DRAFT' && (
                    <ActionButton label="Open" onClick={() => call(api.POST('/api/v1/performance/cycles/{cycleId}/open', { params: cyclePath(c) })).then(cycles.reload)} />
                  )}
                  {c.status !== 'DRAFT' && c.status !== 'CLOSED' && (
                    <>
                      <ActionButton label="Generate reviews" onClick={() => call(api.POST('/api/v1/performance/cycles/{cycleId}/reviews/generate', { params: cyclePath(c) })).then(() => setSource({ cycleId: c.cycleId! }))} />
                      <button type="button" onClick={() => setSource({ cycleId: c.cycleId! })}>All reviews</button>
                      <ActionButton label="Close" onClick={() => call(api.POST('/api/v1/performance/cycles/{cycleId}/close', { params: cyclePath(c) })).then(cycles.reload)} />
                    </>
                  )}
                </>
              ),
          },
        ]}
      />
      {admin && (
        <SimpleForm
          title="New review cycle"
          submitLabel="Create cycle"
          fields={[
            { name: 'cycleName', label: 'Name', required: true },
            { name: 'cycleYear', label: 'Year', type: 'number', required: true },
            { name: 'startDate', label: 'Start date', type: 'date', required: true },
            { name: 'endDate', label: 'End date', type: 'date', required: true },
          ]}
          onSubmit={(v) =>
            call(api.POST('/api/v1/performance/cycles', { body: body(v, ['cycleYear']) as Schemas['CreateReviewCycle'] })).then(cycles.reload)
          }
        />
      )}
      <h3>Reviews</h3>
      <div className="tabs">
        <button type="button" aria-pressed={'role' in source && source.role === 'EMPLOYEE'} onClick={() => setSource({ role: 'EMPLOYEE' })}>
          My reviews
        </button>
        <button type="button" aria-pressed={'role' in source && source.role === 'REVIEWER'} onClick={() => setSource({ role: 'REVIEWER' })}>
          Reviews I conduct
        </button>
        {'cycleId' in source && <span className="muted">All reviews in cycle {source.cycleId}</span>}
      </div>
      <ErrorBanner error={reviews.error} />
      <DataTable
        label="Reviews"
        rows={reviews.data}
        columns={[
          { header: 'Cycle', render: (r: Review) => r.cycleName },
          { header: 'Employee', render: (r) => r.employeeName },
          { header: 'Reviewer', render: (r) => r.reviewerName ?? '' },
          { header: 'Status', render: (r) => r.status },
          { header: 'Rating', render: (r) => (r.overallRating ? `${r.overallRating} (${r.ratingLabel})` : '') },
          { header: '', render: (r) => <button type="button" onClick={() => setSelected(r)}>Open</button> },
        ]}
      />
      {selected && (
        <div>
          <h3>
            Review {selected.reviewId}: {selected.employeeName} — {selected.status}
          </h3>
          <div className="row">
            {selected.empId === user?.empId && SELF_ASSESSABLE.includes(selected.status ?? '') && (
                <SimpleForm
                  title="Self assessment"
                  submitLabel="Submit self assessment"
                  fields={[{ name: 'selfAssessment', label: 'Self assessment', type: 'textarea', required: true }]}
                  onSubmit={(v) => refresh(call(api.POST('/api/v1/performance/reviews/{reviewId}/self-assessment', { params: reviewPath, body: { selfAssessment: v.selfAssessment ?? '' } })))}
                />
            )}
            {selected.empId === user?.empId && selected.status === 'COMPLETED' && (
                <SimpleForm
                  title="Acknowledge"
                  submitLabel="Acknowledge"
                  fields={[{ name: 'comments', label: 'Comments', type: 'textarea' }]}
                  onSubmit={(v) => refresh(call(api.POST('/api/v1/performance/reviews/{reviewId}/acknowledge', { params: reviewPath, body: body(v) })))}
                />
            )}
            {selected.reviewerEmpId === user?.empId && MANAGER_REVIEWABLE.includes(selected.status ?? '') && (
              <SimpleForm
                title="Manager review"
                submitLabel="Submit manager review"
                fields={[
                  { name: 'overallRating', label: 'Overall rating (1.0–5.0)', type: 'number', step: '0.1', required: true },
                  { name: 'managerAssessment', label: 'Assessment', type: 'textarea' },
                ]}
                onSubmit={(v) =>
                  refresh(call(api.POST('/api/v1/performance/reviews/{reviewId}/manager-review', { params: reviewPath, body: body(v, ['overallRating']) as Schemas['ManagerReview'] })))
                }
              />
            )}
            <SimpleForm
              title="Add goal"
              submitLabel="Add goal"
              fields={[
                { name: 'goalTitle', label: 'Title', required: true },
                { name: 'weightPct', label: 'Weight %', type: 'number' },
                { name: 'targetDate', label: 'Target date', type: 'date' },
              ]}
              onSubmit={(v) =>
                call(api.POST('/api/v1/performance/reviews/{reviewId}/goals', { params: reviewPath, body: body(v, ['weightPct']) as Schemas['AddGoal'] })).then(goals.reload)
              }
            />
          </div>
          <h4>Goals</h4>
          <DataTable
            label="Goals"
            rows={goals.data}
            columns={[
              { header: 'Goal', render: (g: Schemas['PerformanceGoal']) => g.goalTitle },
              { header: 'Status', render: (g) => g.status },
              { header: 'Progress %', render: (g) => g.progressPct },
              {
                header: '',
                render: (g) => (
                  <ActionButton
                    label="Mark 100%"
                    onClick={() => call(api.POST('/api/v1/performance/goals/{goalId}/progress', { params: { path: { goalId: g.goalId! } }, body: { progressPct: 100, status: 'COMPLETED' } })).then(goals.reload)}
                  />
                ),
              },
            ]}
          />
        </div>
      )}
    </section>
  );
}
