import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import {
  performanceApi,
  type PerformanceGoal,
  type PerformanceReview,
  type RatingCount,
  type ReviewCycle,
} from './api';

type Tab = 'mine' | 'team' | 'cycles';

const TABS: { id: Tab; label: string }[] = [
  { id: 'mine', label: 'My reviews' },
  { id: 'team', label: 'Team reviews' },
  { id: 'cycles', label: 'Review cycles' },
];

function message(err: unknown): string {
  return err instanceof ApiError ? `${err.message} (${err.code})` : 'Unexpected error';
}

function optionalNumber(value: string): number | undefined {
  return value.trim() === '' ? undefined : Number(value);
}

export function PerformancePage() {
  const [tab, setTab] = useState<Tab>('mine');
  const [selected, setSelected] = useState<number | null>(null);
  return (
    <section>
      <h1>Performance</h1>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            aria-selected={tab === t.id}
            onClick={() => {
              setTab(t.id);
              setSelected(null);
            }}
            type="button"
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab !== 'cycles' && selected === null && <ReviewList scope={tab} onOpen={setSelected} />}
      {tab !== 'cycles' && selected !== null && (
        <ReviewDetail reviewId={selected} asReviewer={tab === 'team'} onBack={() => setSelected(null)} />
      )}
      {tab === 'cycles' && <Cycles />}
    </section>
  );
}

function ReviewList({ scope, onOpen }: { scope: 'mine' | 'team'; onOpen: (id: number) => void }) {
  const [reviews, setReviews] = useState<PerformanceReview[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    performanceApi.reviews(scope).then(setReviews).catch((e) => setError(message(e)));
  }, [scope]);
  return (
    <>
      {error && <p className="error" role="alert">{error}</p>}
      <table aria-label={scope === 'mine' ? 'My reviews' : 'Team reviews'}>
        <thead>
          <tr>
            <th>Review</th><th>Cycle</th><th>{scope === 'mine' ? 'Reviewer' : 'Employee'}</th><th>Status</th>
            <th>Rating</th><th />
          </tr>
        </thead>
        <tbody>
          {reviews.map((r) => (
            <tr key={r.reviewId}>
              <td>#{r.reviewId}</td>
              <td>{r.cycleId}</td>
              <td>{scope === 'mine' ? r.reviewerName : r.employeeName}</td>
              <td>{r.status}</td>
              <td>{r.overallRating != null ? `${r.overallRating} ${r.ratingLabel ?? ''}` : ''}</td>
              <td>
                <button type="button" onClick={() => onOpen(r.reviewId)}>Open</button>
              </td>
            </tr>
          ))}
          {reviews.length === 0 && (
            <tr><td colSpan={6}>No reviews</td></tr>
          )}
        </tbody>
      </table>
    </>
  );
}

function ReviewDetail({ reviewId, asReviewer, onBack }: { reviewId: number; asReviewer: boolean; onBack: () => void }) {
  const [review, setReview] = useState<PerformanceReview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  const load = useCallback(() => {
    performanceApi.review(reviewId).then(setReview).catch((e) => setError(message(e)));
  }, [reviewId]);
  useEffect(load, [load]);

  const run = (action: Promise<unknown>, done: string) => {
    setError(null);
    action
      .then(() => {
        setFlash(done);
        load();
      })
      .catch((e) => setError(message(e)));
  };

  if (!review) {
    return error ? <p className="error" role="alert">{error}</p> : <p>Loading…</p>;
  }
  const employeeCanSelfAssess = !asReviewer && (review.status === 'NOT_STARTED' || review.status === 'SELF_REVIEW');
  return (
    <div aria-label="Review detail">
      <button type="button" onClick={onBack}>Back</button>
      <h2>Review #{review.reviewId} — {review.employeeName}</h2>
      {flash && <p className="success" role="status">{flash}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <dl>
        <dt>Status</dt><dd data-testid="review-status">{review.status}</dd>
        <dt>Reviewer</dt><dd>{review.reviewerName}</dd>
        {review.selfAssessment && (<><dt>Self assessment</dt><dd>{review.selfAssessment}</dd></>)}
        {review.selfRating != null && (<><dt>Self rating</dt><dd>{review.selfRating}</dd></>)}
        {review.managerComments && (<><dt>Manager comments</dt><dd>{review.managerComments}</dd></>)}
        {review.overallRating != null && (
          <><dt>Overall rating</dt><dd>{review.overallRating} ({review.ratingLabel})</dd></>
        )}
      </dl>

      {employeeCanSelfAssess && (
        <SelfAssessmentForm
          onSubmit={(text, rating) =>
            run(performanceApi.selfAssessment(reviewId, text, rating), 'Self-assessment submitted')
          }
        />
      )}
      {asReviewer && review.status === 'MANAGER_REVIEW' && (
        <ManagerReviewForm
          onSubmit={(body) => run(performanceApi.managerReview(reviewId, body), 'Manager review submitted')}
        />
      )}
      {!asReviewer && review.status === 'COMPLETED' && (
        <button type="button" onClick={() => run(performanceApi.acknowledge(reviewId), 'Review acknowledged')}>
          Acknowledge review
        </button>
      )}

      <Goals
        goals={review.goals ?? []}
        editable={review.status !== 'ACKNOWLEDGED'}
        onAdd={(goal) => run(performanceApi.addGoal(reviewId, goal), 'Goal added')}
        onProgress={(goalId, pct, status) =>
          run(performanceApi.updateProgress(goalId, pct, status), 'Goal progress updated')
        }
      />
    </div>
  );
}

function SelfAssessmentForm({ onSubmit }: { onSubmit: (text: string, rating?: number) => void }) {
  const [text, setText] = useState('');
  const [rating, setRating] = useState('');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit(text, optionalNumber(rating));
  };
  return (
    <form onSubmit={submit} aria-label="Self-assessment form">
      <h3>Self assessment</h3>
      <label>
        Self assessment
        <textarea value={text} onChange={(e) => setText(e.target.value)} required />
      </label>
      <label>
        Self rating (1-5)
        <input type="number" step="0.1" value={rating} onChange={(e) => setRating(e.target.value)} />
      </label>
      <button type="submit">Submit self-assessment</button>
    </form>
  );
}

function ManagerReviewForm({
  onSubmit,
}: {
  onSubmit: (body: { overallRating: number; managerComments?: string; strengths?: string; improvements?: string }) => void;
}) {
  const [rating, setRating] = useState('');
  const [comments, setComments] = useState('');
  const [strengths, setStrengths] = useState('');
  const [improvements, setImprovements] = useState('');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit({
      overallRating: Number(rating),
      managerComments: comments || undefined,
      strengths: strengths || undefined,
      improvements: improvements || undefined,
    });
  };
  return (
    <form onSubmit={submit} aria-label="Manager review form">
      <h3>Manager review</h3>
      <label>
        Overall rating (1-5)
        <input type="number" step="0.1" value={rating} onChange={(e) => setRating(e.target.value)} required />
      </label>
      <label>
        Manager comments
        <textarea value={comments} onChange={(e) => setComments(e.target.value)} />
      </label>
      <label>
        Strengths
        <textarea value={strengths} onChange={(e) => setStrengths(e.target.value)} />
      </label>
      <label>
        Areas for improvement
        <textarea value={improvements} onChange={(e) => setImprovements(e.target.value)} />
      </label>
      <button type="submit">Submit manager review</button>
    </form>
  );
}

function Goals({
  goals,
  editable,
  onAdd,
  onProgress,
}: {
  goals: PerformanceGoal[];
  editable: boolean;
  onAdd: (goal: { goalTitle: string; weightPct?: number; targetDate?: string }) => void;
  onProgress: (goalId: number, pct: number, status?: string) => void;
}) {
  const [title, setTitle] = useState('');
  const [weight, setWeight] = useState('');
  const [target, setTarget] = useState('');
  const [progress, setProgress] = useState<Record<number, string>>({});
  const add = (e: FormEvent) => {
    e.preventDefault();
    onAdd({ goalTitle: title, weightPct: optionalNumber(weight), targetDate: target || undefined });
    setTitle('');
    setWeight('');
    setTarget('');
  };
  return (
    <>
      <h3>Goals</h3>
      <table aria-label="Goals">
        <thead>
          <tr><th>Goal</th><th>Weight %</th><th>Target</th><th>Progress %</th><th>Status</th><th /></tr>
        </thead>
        <tbody>
          {goals.map((g) => (
            <tr key={g.goalId}>
              <td>{g.goalTitle}</td>
              <td>{g.weightPct ?? ''}</td>
              <td>{g.targetDate ?? ''}</td>
              <td>{g.progressPct ?? 0}</td>
              <td>{g.status}</td>
              <td>
                {editable && (
                  <>
                    <input
                      type="number"
                      min={0}
                      max={100}
                      aria-label={`Progress for ${g.goalTitle}`}
                      value={progress[g.goalId] ?? ''}
                      onChange={(e) => setProgress({ ...progress, [g.goalId]: e.target.value })}
                    />
                    <button
                      type="button"
                      onClick={() => onProgress(g.goalId, Number(progress[g.goalId] ?? g.progressPct ?? 0))}
                    >
                      Update
                    </button>
                  </>
                )}
              </td>
            </tr>
          ))}
          {goals.length === 0 && (
            <tr><td colSpan={6}>No goals</td></tr>
          )}
        </tbody>
      </table>
      {editable && (
        <form onSubmit={add} aria-label="Add goal">
          <label>
            Goal title
            <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
          </label>
          <label>
            Weight %
            <input type="number" min={0} max={100} value={weight} onChange={(e) => setWeight(e.target.value)} />
          </label>
          <label>
            Target date
            <input type="date" value={target} onChange={(e) => setTarget(e.target.value)} />
          </label>
          <button type="submit">Add goal</button>
        </form>
      )}
    </>
  );
}

function Cycles() {
  const [cycles, setCycles] = useState<ReviewCycle[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [distribution, setDistribution] = useState<{ cycleId: number; rows: RatingCount[] } | null>(null);
  const [name, setName] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [selfDue, setSelfDue] = useState('');
  const [managerDue, setManagerDue] = useState('');

  const load = useCallback(() => {
    performanceApi.cycles().then(setCycles).catch((e) => setError(message(e)));
  }, []);
  useEffect(load, [load]);

  const run = <T,>(action: Promise<T>, done: (result: T) => string) => {
    setError(null);
    action
      .then((result) => {
        setFlash(done(result));
        load();
      })
      .catch((e) => setError(message(e)));
  };

  const create = (e: FormEvent) => {
    e.preventDefault();
    run(
      performanceApi.createCycle({
        cycleName: name,
        startDate: start,
        endDate: end,
        selfReviewDeadline: selfDue || undefined,
        managerReviewDeadline: managerDue || undefined,
      }),
      (c) => `Cycle #${c.cycleId} created (${c.status})`,
    );
  };

  const showDistribution = (cycleId: number) => {
    setError(null);
    performanceApi
      .ratingDistribution(cycleId)
      .then((rows) => setDistribution({ cycleId, rows }))
      .catch((e) => setError(message(e)));
  };

  return (
    <>
      {flash && <p className="success" role="status">{flash}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <table aria-label="Review cycles">
        <thead>
          <tr><th>Cycle</th><th>Year</th><th>Start</th><th>End</th><th>Status</th><th /></tr>
        </thead>
        <tbody>
          {cycles.map((c) => (
            <tr key={c.cycleId}>
              <td>{c.cycleName}</td>
              <td>{c.cycleYear}</td>
              <td>{c.startDate}</td>
              <td>{c.endDate}</td>
              <td>{c.status}</td>
              <td>
                {c.status === 'DRAFT' && (
                  <button type="button" onClick={() => run(performanceApi.openCycle(c.cycleId), () => 'Cycle opened')}>
                    Open
                  </button>
                )}
                {c.status !== 'CLOSED' && (
                  <>
                    <button
                      type="button"
                      onClick={() =>
                        run(performanceApi.generateReviews(c.cycleId), (r) => `${r.rowsAffected} review(s) generated`)
                      }
                    >
                      Generate reviews
                    </button>
                    <button
                      type="button"
                      onClick={() => run(performanceApi.closeCycle(c.cycleId), () => 'Cycle closed')}
                    >
                      Close
                    </button>
                  </>
                )}
                <button type="button" onClick={() => showDistribution(c.cycleId)}>Ratings</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {distribution && (
        <table aria-label="Rating distribution">
          <caption>Rating distribution — cycle {distribution.cycleId}</caption>
          <thead>
            <tr><th>Rating</th><th>Count</th></tr>
          </thead>
          <tbody>
            {distribution.rows.map((r) => (
              <tr key={r.ratingLabel}><td>{r.ratingLabel}</td><td>{r.count}</td></tr>
            ))}
            {distribution.rows.length === 0 && (
              <tr><td colSpan={2}>No completed reviews</td></tr>
            )}
          </tbody>
        </table>
      )}

      <form onSubmit={create} aria-label="New review cycle">
        <h3>New review cycle</h3>
        <label>
          Cycle name
          <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} />
        </label>
        <label>
          Start date
          <input type="date" value={start} onChange={(e) => setStart(e.target.value)} required />
        </label>
        <label>
          End date
          <input type="date" value={end} onChange={(e) => setEnd(e.target.value)} required />
        </label>
        <label>
          Self-review deadline
          <input type="date" value={selfDue} onChange={(e) => setSelfDue(e.target.value)} />
        </label>
        <label>
          Manager-review deadline
          <input type="date" value={managerDue} onChange={(e) => setManagerDue(e.target.value)} />
        </label>
        <button type="submit">Create cycle</button>
      </form>
    </>
  );
}
