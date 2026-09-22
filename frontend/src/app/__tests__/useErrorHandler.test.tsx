import { act, renderHook, screen } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import type { ReactNode } from 'react';
import type { ApiError } from '../../api/types';
import { ToastProvider } from '../ToastContext';
import { useErrorHandler } from '../useErrorHandler';

function apiErr(status: number, body: ApiError) {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  return new AxiosError(body.message, String(status), config, undefined, {
    status,
    statusText: '',
    headers: {},
    config,
    data: body,
  });
}

const wrapper = ({ children }: { children: ReactNode }) => <ToastProvider autoHideMs={0}>{children}</ToastProvider>;

describe('useErrorHandler', () => {
  it('is silent for 401 TOKEN_INVALID (the interceptor owns it)', () => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    let h!: ReturnType<typeof result.current.handleError>;
    act(() => {
      h = result.current.handleError(apiErr(401, { code: 'TOKEN_INVALID', message: 'Session has expired', traceId: 't1' }));
    });
    expect(h.toasted).toBe(false);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('routes `field` errors inline and does not toast', () => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    let h!: ReturnType<typeof result.current.handleError>;
    act(() => {
      h = result.current.handleError(apiErr(400, { code: '-20311', message: 'Needs uppercase', field: 'newPassword', traceId: 't2' }));
    });
    expect(h.fieldErrors).toEqual({ newPassword: 'Needs uppercase' });
    expect(h.toasted).toBe(false);
  });

  it('uses the default field for password-policy codes when the server omits `field`', () => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    let h!: ReturnType<typeof result.current.handleError>;
    act(() => {
      h = result.current.handleError(apiErr(400, { code: '-20310', message: 'Too short', traceId: 't3' }));
    });
    expect(h.fieldErrors).toEqual({ newPassword: 'Too short' });
  });

  it('uses overallRating for an unfielded rating error without a toast', () => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    let h!: ReturnType<typeof result.current.handleError>;
    act(() => {
      h = result.current.handleError(apiErr(400, { code: '-20403', message: 'Rating must be between 1.0 and 5.0', traceId: 't5' }));
    });
    expect(h.fieldErrors).toEqual({ overallRating: 'Rating must be between 1.0 and 5.0' });
    expect(h.toasted).toBe(false);
  });

  it('toasts a known review status error', () => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    act(() => {
      result.current.handleError(apiErr(422, { code: '-20402', message: 'Review not found or not in correct status', traceId: 't6' }));
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Review not found or not in correct status');
  });

  it('toasts a known cycle not found error', () => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    act(() => {
      result.current.handleError(apiErr(404, { code: 'CYCLE_NOT_FOUND', message: 'Review cycle not found', traceId: 't7' }));
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Review cycle not found');
  });

  it.each([
    ['-20201', 'leaveTypeId', 'Insufficient leave balance. Available: 2, Requested: 3'],
    ['-20202', 'startDate', 'Leave request overlaps with existing request'],
    ['-20203', 'leaveTypeId', 'Invalid leave type: 99'],
    ['-20210', 'endDate', 'Start date must be before or equal to end date'],
    ['-20211', 'startDate', 'Cannot submit leave requests more than 5 days in the past'],
    ['-20212', 'startDate', 'Leave request must include at least one business day'],
  ])('maps unfielded leave code %s to %s (contracts/p2-leave/error-codes.md)', (code, field, message) => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    let h!: ReturnType<typeof result.current.handleError>;
    act(() => {
      h = result.current.handleError(apiErr(422, { code, message, traceId: 't8' }));
    });
    expect(h.fieldErrors).toEqual({ [field]: message });
    expect(h.toasted).toBe(false);
  });

  it.each([
    ['-20204', 'Cannot approve request in status: APPROVED'],
    ['LEAVE_REQUEST_NOT_FOUND', 'Leave request not found'],
  ])('toasts leave code %s which has no default field', (code, message) => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    let h!: ReturnType<typeof result.current.handleError>;
    act(() => {
      h = result.current.handleError(apiErr(422, { code, message, traceId: 't9' }));
    });
    expect(h.toasted).toBe(true);
    expect(screen.getByRole('alert')).toHaveTextContent(message);
  });

  it('collects VALIDATION_FAILED details per field', () => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    let h!: ReturnType<typeof result.current.handleError>;
    act(() => {
      h = result.current.handleError(
        apiErr(400, {
          code: 'VALIDATION_FAILED',
          message: 'Request validation failed',
          traceId: 't4',
          details: [
            { field: 'username', code: 'NotBlank', message: 'must not be blank' },
            { field: 'password', code: 'NotBlank', message: 'must not be blank' },
          ],
        }),
        { fieldNames: ['username'] },
      );
    });
    expect(h.fieldErrors).toEqual({ username: 'must not be blank' });
  });

  it('toasts unfielded legacy errors with the traceId', () => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    act(() => {
      result.current.handleError(apiErr(404, { code: '-20001', message: 'Employee not found or not active', traceId: 'abc123' }));
    });
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Employee not found or not active');
    expect(alert).toHaveTextContent('Ref: abc123');
  });

  it('toasts a generic message for non-API failures (network)', () => {
    const { result } = renderHook(() => useErrorHandler(), { wrapper });
    act(() => {
      result.current.handleError(new Error('Network Error'));
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong. Please try again.');
  });
});
