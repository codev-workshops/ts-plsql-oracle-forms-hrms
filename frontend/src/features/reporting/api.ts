import { ApiError, api, tokenStore, type ApiErrorBody } from '../../api/client';

export type ReportKind = 'headcount' | 'compensation' | 'turnover' | 'new-hires'
  | 'leave-utilization' | 'payroll-summary' | 'eeo';

export interface Report {
  name: string;
  generatedAt: string;
  columns: string[];
  rows: Record<string, string | number | boolean | null>[];
}

export interface ReportQuery {
  kind: ReportKind;
  params: URLSearchParams;
}

function reportPath({ kind, params }: ReportQuery) {
  const query = params.toString();
  return `/reports/${kind}${query ? `?${query}` : ''}`;
}

export function fetchReport(query: ReportQuery) {
  return api<Report>(reportPath(query));
}

export async function downloadReport(query: ReportQuery) {
  const headers = new Headers({ Accept: 'text/csv' });
  const token = tokenStore.get();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const response = await fetch(`/api/v1${reportPath(query)}`, { headers });
  if (!response.ok) {
    const error = await response.json().catch(() => undefined) as ApiErrorBody | undefined;
    throw new ApiError(response.status, error ?? {
      code: String(response.status),
      message: 'Could not download the report.',
    });
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = `${query.kind}.csv`;
  document.body.append(link);
  try {
    link.click();
  } finally {
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  }
}
