import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { AcknowledgeRequest, ManagerReviewRequest, SelfAssessmentRequest } from '../../api/types';
import { useAuth } from '../../app/AuthContext';
import { ratingLabelFor } from '../../app/ratingLabel';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';
import { StatusBadge } from './StatusBadge';

const selfSchema = zodFor('SelfAssessmentRequest');
const managerSchema = zodFor('ManagerReviewRequest');
const acknowledgeSchema = zodFor('AcknowledgeRequest');

export function ReviewDetail({ reviewId }: { reviewId: number }) {
  const { user } = useAuth();
  const { push } = useToast();
  const { handleError } = useErrorHandler();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ['performance', 'reviews', reviewId], queryFn: () => api.performance.getReview(reviewId) });
  const [selfAssessment, setSelfAssessment] = useState('');
  const [selfErrors, setSelfErrors] = useState<Record<string, string>>({});
  const [manager, setManager] = useState<ManagerReviewRequest>({ overallRating: 1, managerAssessment: '', strengths: '', improvementAreas: '', developmentPlan: '' });
  const [managerErrors, setManagerErrors] = useState<Record<string, string>>({});
  const [comments, setComments] = useState('');
  const [ackErrors, setAckErrors] = useState<Record<string, string>>({});
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['performance', 'reviews'] });
    queryClient.invalidateQueries({ queryKey: ['performance', 'goals'] });
    queryClient.invalidateQueries({ queryKey: ['performance', 'team'] });
    queryClient.invalidateQueries({ queryKey: ['performance', 'distribution'] });
  };
  const submitSelf = useMutation({
    mutationFn: (body: SelfAssessmentRequest) => api.performance.submitSelfAssessment(reviewId, body),
    onSuccess: () => { push({ kind: 'success', message: 'Self-assessment submitted' }); invalidate(); },
    onError: (error) => {
      const handled = handleError(error, { fieldNames: ['selfAssessment'] });
      setSelfErrors(handled.fieldErrors);
    },
  });
  const submitManager = useMutation({
    mutationFn: (body: ManagerReviewRequest) => api.performance.submitManagerReview(reviewId, body),
    onSuccess: () => { push({ kind: 'success', message: 'Manager review submitted' }); invalidate(); },
    onError: (error) => setManagerErrors(handleError(error, { fieldNames: ['overallRating', 'managerAssessment', 'strengths', 'improvementAreas', 'developmentPlan'] }).fieldErrors),
  });
  const acknowledge = useMutation({
    mutationFn: (body: AcknowledgeRequest) => api.performance.acknowledgeReview(reviewId, body),
    onSuccess: () => { push({ kind: 'success', message: 'Review acknowledged' }); invalidate(); },
    onError: (error) => setAckErrors(handleError(error, { fieldNames: ['employeeComments'] }).fieldErrors),
  });

  if (query.isPending) return <p role="status">Loading review…</p>;
  if (query.isError || !query.data) return <p role="alert">Could not load review.</p>;
  const review = query.data;
  const selfVisible = review.empId === user?.empId && ['NOT_STARTED', 'SELF_REVIEW'].includes(review.status);
  const managerVisible = review.reviewerEmpId === user?.empId && ['MANAGER_REVIEW', 'MEETING_SCHEDULED'].includes(review.status);
  const acknowledgeVisible = review.empId === user?.empId && review.status === 'COMPLETED';

  const submitSelfAssessment = (event: FormEvent) => {
    event.preventDefault();
    const parsed = selfSchema.safeParse({ selfAssessment });
    if (!parsed.success) {
      setSelfErrors(zodFieldErrors(parsed.error));
      return;
    }
    setSelfErrors({});
    submitSelf.mutate(parsed.data as SelfAssessmentRequest);
  };
  const submitManagerReview = (event: FormEvent) => {
    event.preventDefault();
    const parsed = managerSchema.safeParse(manager);
    if (!parsed.success) {
      setManagerErrors(zodFieldErrors(parsed.error));
      return;
    }
    setManagerErrors({});
    submitManager.mutate(parsed.data as ManagerReviewRequest);
  };
  const submitAcknowledge = (event: FormEvent) => {
    event.preventDefault();
    const parsed = acknowledgeSchema.safeParse({ employeeComments: comments || undefined });
    if (!parsed.success) {
      setAckErrors(zodFieldErrors(parsed.error));
      return;
    }
    setAckErrors({});
    acknowledge.mutate(parsed.data);
  };
  const field = (name: string, label: string, value: string, onChange: (value: string) => void, error?: string) => (
    <div className="field">
      <label htmlFor={`review-${name}`}>{label}</label>
      <textarea id={`review-${name}`} value={value} onChange={(event) => onChange(event.target.value)} aria-invalid={error ? true : undefined} />
      {error && <span role="alert" className="field-error">{error}</span>}
    </div>
  );

  return (
    <article className="review-detail">
      <h3>{review.employeeName} — Review</h3>
      <p>Status: <StatusBadge status={review.status} /></p>
      <dl>
        <dt>Overall rating</dt><dd>{review.overallRating ?? '—'}</dd>
        <dt>Rating label</dt><dd>{review.ratingLabel ?? '—'}</dd>
        <dt>Self-assessment</dt><dd className="preformatted">{review.selfAssessment ?? '—'}</dd>
        <dt>Manager assessment</dt><dd className="preformatted">{review.managerAssessment ?? '—'}</dd>
        <dt>Strengths</dt><dd className="preformatted">{review.strengths ?? '—'}</dd>
        <dt>Areas for improvement</dt><dd className="preformatted">{review.areasForImprovement ?? '—'}</dd>
        <dt>Development plan</dt><dd className="preformatted">{review.developmentPlan ?? '—'}</dd>
        <dt>Employee comments</dt><dd className="preformatted">{review.employeeComments ?? '—'}</dd>
      </dl>
      {selfVisible && (
        <form onSubmit={submitSelfAssessment}>
          <h4>Self assessment</h4>
          {field('selfAssessment', 'Self-assessment', selfAssessment, setSelfAssessment, selfErrors.selfAssessment)}
          <button type="submit" disabled={submitSelf.isPending}>Submit</button>
        </form>
      )}
      {managerVisible && (
        <form onSubmit={submitManagerReview}>
          <h4>Manager review</h4>
          <div className="field">
            <label htmlFor="review-overallRating">Overall rating</label>
            <input id="review-overallRating" type="number" step="0.1" min="1" max="5" value={manager.overallRating} onChange={(event) => setManager((old) => ({ ...old, overallRating: Number(event.target.value) }))} aria-invalid={managerErrors.overallRating ? true : undefined} />
            <span className="hint">{ratingLabelFor(manager.overallRating)}</span>
            {managerErrors.overallRating && <span role="alert" className="field-error">{managerErrors.overallRating}</span>}
          </div>
          {field('managerAssessment', 'Manager assessment', manager.managerAssessment, (value) => setManager((old) => ({ ...old, managerAssessment: value })), managerErrors.managerAssessment)}
          {field('strengths', 'Strengths', manager.strengths ?? '', (value) => setManager((old) => ({ ...old, strengths: value })), managerErrors.strengths)}
          {field('improvementAreas', 'Areas for improvement', manager.improvementAreas ?? '', (value) => setManager((old) => ({ ...old, improvementAreas: value })), managerErrors.improvementAreas)}
          {field('developmentPlan', 'Development plan', manager.developmentPlan ?? '', (value) => setManager((old) => ({ ...old, developmentPlan: value })), managerErrors.developmentPlan)}
          <button type="submit" disabled={submitManager.isPending}>Submit</button>
        </form>
      )}
      {acknowledgeVisible && (
        <form onSubmit={submitAcknowledge}>
          <h4>Acknowledge review</h4>
          {field('employeeComments', 'Employee comments', comments, setComments, ackErrors.employeeComments)}
          <button type="submit" disabled={acknowledge.isPending}>Acknowledge</button>
        </form>
      )}
    </article>
  );
}
