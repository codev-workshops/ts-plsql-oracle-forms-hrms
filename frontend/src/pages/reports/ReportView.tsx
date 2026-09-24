import { useState, type FormEvent, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { CsvDownload, PageMeta } from '../../api/types';
import { normaliseError, useErrorHandler } from '../../app/useErrorHandler';
import type { DtoName } from '../../validation/schema';
import { saveTextFile } from '../payroll/payrollShared';
import { SchemaField, emptyValues, parseWith, type FieldConfig, type FormValues } from '../shared/schemaForm';

export interface ReportColumn<Row> {
  key: string;
  label: string;
  render: (row: Row) => ReactNode;
  numeric?: boolean;
}

export interface ReportPage<Row> {
  content: Row[];
  page: PageMeta;
}

export interface ReportDefinition<Query extends { page?: number; size?: number }, Row, Page extends ReportPage<Row>> {
  id: string;
  title: string;
  /** Legacy `HRMS_REPORTS` block / `VW_*` this report replaces (COMPONENT_MAPPING.md §9). */
  legacy: string;
  dto: DtoName;
  filters: FieldConfig[];
  fetch: (query: Query) => Promise<Page>;
  csv: (query: Query) => Promise<CsvDownload>;
  columns: ReportColumn<Row>[];
  rowKey: (row: Row) => string;
  summary?: (page: Page) => ReactNode;
  /** Default filter values when the report first opens. */
  defaults?: Partial<Record<string, string | boolean>>;
}

const PAGE_SIZE = 50;

/**
 * One `HRMS_REPORTS` block replacement: filters validated by the exported DTO (`…Query` in
 * validation-schema.json), a paged JSON grid via `GET /api/reports/{report}` and the streamed
 * `GET …/{report}.csv` twin behind "Export CSV" (same filters, no paging, frozen column order).
 */
export function ReportView<Query extends { page?: number; size?: number }, Row, Page extends ReportPage<Row>>({ report, defaults }: { report: ReportDefinition<Query, Row, Page>; defaults?: Partial<Record<string, string | boolean>> }) {
  const { handleError } = useErrorHandler();
  const initial = { ...report.defaults, ...defaults };
  const [values, setValues] = useState<FormValues>(() => emptyValues(report.dto, initial));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [query, setQuery] = useState<Query | null>(() => parseWith<Query>(report.dto, emptyValues(report.dto, initial)).data ?? null);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);

  const filterNames = report.filters.map((f) => f.name);
  const result = useQuery({
    queryKey: ['reports', report.id, query, page],
    queryFn: () => report.fetch({ ...(query as Query), page, size: PAGE_SIZE }),
    enabled: query !== null,
    placeholderData: (prev) => prev,
  });

  const run = (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseWith<Query>(report.dto, values);
    setErrors(parsed.errors);
    if (!parsed.data) return;
    setPage(0);
    setQuery(parsed.data);
  };

  const exportCsv = async () => {
    if (!query) return;
    setExporting(true);
    try {
      const { filename, csv } = await report.csv(query);
      saveTextFile(filename, csv);
    } catch (error) {
      setErrors(handleError(error, { fieldNames: filterNames }).fieldErrors);
    } finally {
      setExporting(false);
    }
  };

  const serverError = result.isError ? normaliseError(result.error).apiError : null;
  const data = result.data;
  const meta = data?.page;

  return (
    <section className="report" data-testid={`report-${report.id}`} aria-labelledby={`report-${report.id}-title`}>
      <h2 id={`report-${report.id}-title`}>{report.title}</h2>
      <p className="muted">
        Replaces <code>{report.legacy}</code>
      </p>
      <form className="toolbar filters" onSubmit={run} noValidate aria-label={`${report.title} filters`}>
        {report.filters.map((f) => (
          <SchemaField key={f.name} dto={report.dto} field={f} values={values} errors={errors} onChange={(name, v) => setValues((prev) => ({ ...prev, [name]: v }))} idPrefix={`${report.id}-`} />
        ))}
        <div className="actions">
          <button type="submit">Run report</button>
          <button type="button" onClick={exportCsv} disabled={!query || exporting || result.isPending}>
            {exporting ? 'Exporting…' : 'Export CSV'}
          </button>
        </div>
      </form>

      {query && result.isPending && <p role="status">Loading {report.title.toLowerCase()}…</p>}
      {result.isError && (
        <p role="alert" className="field-error">
          Could not load {report.title.toLowerCase()}.{serverError ? ` ${serverError.code}: ${serverError.message}` : ''}
        </p>
      )}
      {data && data.content.length === 0 && <p role="status">No rows match the selected filters.</p>}
      {data && data.content.length > 0 && (
        <>
          {report.summary && <div className="report-summary">{report.summary(data)}</div>}
          <table className="grid" aria-label={report.title}>
            <thead>
              <tr>
                {report.columns.map((c) => (
                  <th key={c.key} scope="col" className={c.numeric ? 'num' : undefined}>
                    {c.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.content.map((row) => (
                <tr key={report.rowKey(row)}>
                  {report.columns.map((c) => (
                    <td key={c.key} className={c.numeric ? 'num' : undefined}>
                      {c.render(row)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {meta && (
            <div className="toolbar-pagination">
              <button type="button" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={meta.page === 0}>
                Previous
              </button>
              <span>
                Page {meta.page + 1} of {Math.max(1, meta.totalPages)} · {meta.totalElements} rows
              </span>
              <button type="button" onClick={() => setPage((p) => p + 1)} disabled={meta.page + 1 >= meta.totalPages}>
                Next
              </button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
