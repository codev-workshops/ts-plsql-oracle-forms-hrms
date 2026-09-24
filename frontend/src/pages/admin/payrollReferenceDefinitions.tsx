import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Holiday, HolidayRequest, PayElement, PayElementRequest, TaxBracket, TaxBracketRequest } from '../../api/types';
import { humanizeCode } from '../shared/schemaForm';
import { ReferenceDataTab, type ReferenceDataConfig } from './ReferenceDataTab';
import type { AdminLookups } from './adminDefinitions';

const dash = (v: string | number | null | undefined) => (v === null || v === undefined || v === '' ? '—' : v);

/**
 * CUTOVER_PLAN §9.2 expansion: the three payroll reference tables the Forms `HRMS_ADMIN` module
 * never exposed (HOLIDAYS / PAY_ELEMENTS / TAX_BRACKETS). Same `ReferenceDataTab` grid as the P0
 * reference tables – scalar rules from the generated schema, cross-field / protection rules
 * (`-20607`, `-20608`, `-20609`) stay on the server and surface through `useErrorHandler`.
 */
export function holidaysConfig(lookups: AdminLookups): ReferenceDataConfig<Holiday, HolidayRequest, number> {
  return {
    id: 'holidays',
    title: 'Holidays',
    legacy: 'HOLIDAYS (BusinessCalendar; company-wide when location is blank)',
    dto: 'HolidayRequest',
    codeField: '',
    fields: [
      { name: 'holidayDate', label: 'Date', hint: 'Between 1990-01-01 and ten years from today' },
      { name: 'holidayName', label: 'Name' },
      { name: 'locationCode', label: 'Location', options: lookups.locations, hint: 'Blank = company-wide' },
      { name: 'floatingFlag', label: 'Floating' },
      { name: 'activeFlag', label: 'Active' },
    ],
    columns: [
      { key: 'holidayDate', label: 'Date', render: (r) => r.holidayDate },
      { key: 'observedDate', label: 'Observed', render: (r) => dash(r.observedDate) },
      { key: 'holidayName', label: 'Name', render: (r) => r.holidayName },
      { key: 'locationCode', label: 'Location', render: (r) => r.locationCode ?? 'Company-wide' },
      { key: 'floatingFlag', label: 'Floating', render: (r) => (r.floatingFlag ? 'Yes' : 'No') },
    ],
    rowId: (r) => r.holidayId,
    rowLabel: (r) => `${r.holidayName} (${r.holidayDate})`,
    list: api.admin.listHolidays,
    create: api.admin.createHoliday,
    update: api.admin.updateHoliday,
    deactivate: api.admin.deactivateHoliday,
    invalidates: [['leave', 'business-days']],
  };
}

export function payElementsConfig(): ReferenceDataConfig<PayElement, PayElementRequest, number> {
  return {
    id: 'pay-elements',
    title: 'Pay elements',
    legacy: 'PAY_ELEMENTS (PayrollConstants reserved rows 0, 1, 100–103 → -20607)',
    dto: 'PayElementRequest',
    codeField: 'elementCode',
    fields: [
      { name: 'elementCode', label: 'Code' },
      { name: 'elementName', label: 'Name' },
      { name: 'elementType', label: 'Type', hint: 'TAX rows are reserved and cannot be created' },
      { name: 'calculationType', label: 'Calculation' },
      { name: 'defaultAmount', label: 'Default amount', hint: 'FLAT elements only (server rule -20603)' },
      { name: 'defaultPercentage', label: 'Default percentage', hint: 'PERCENTAGE elements only (server rule -20603)' },
      { name: 'taxableFlag', label: 'Taxable' },
      { name: 'pretaxFlag', label: 'Pre-tax' },
      { name: 'employerPaid', label: 'Employer paid' },
      { name: 'glAccountCode', label: 'GL account' },
      { name: 'priorityOrder', label: 'Priority' },
      { name: 'activeFlag', label: 'Active' },
    ],
    columns: [
      { key: 'elementId', label: 'Id', render: (r) => r.elementId },
      { key: 'elementCode', label: 'Code', render: (r) => (r.reserved ? `${r.elementCode} (reserved)` : r.elementCode) },
      { key: 'elementName', label: 'Name', render: (r) => r.elementName },
      { key: 'elementType', label: 'Type', render: (r) => humanizeCode(r.elementType) },
      { key: 'calculationType', label: 'Calculation', render: (r) => humanizeCode(r.calculationType) },
      { key: 'default', label: 'Default', render: (r) => (r.defaultPercentage ? `${r.defaultPercentage} %` : dash(r.defaultAmount)) },
      { key: 'flags', label: 'Flags', render: (r) => [r.taxableFlag && 'taxable', r.pretaxFlag && 'pre-tax', r.employerPaid && 'employer'].filter(Boolean).join(', ') || '—' },
      { key: 'glAccountCode', label: 'GL', render: (r) => dash(r.glAccountCode) },
      { key: 'priorityOrder', label: 'Priority', render: (r) => dash(r.priorityOrder) },
      { key: 'activeEmployeeElements', label: 'Employees', render: (r) => dash(r.activeEmployeeElements) },
    ],
    rowId: (r) => r.elementId,
    rowLabel: (r) => `${r.elementCode} – ${r.elementName}`,
    list: api.admin.listPayElements,
    create: api.admin.createPayElement,
    update: api.admin.updatePayElement,
    deactivate: api.admin.deactivatePayElement,
    canDeactivate: (r) => !r.reserved,
  };
}

export const ladderGapsKey = ['admin', 'tax-brackets', 'ladder-gaps'] as const;

export function taxBracketsConfig(): ReferenceDataConfig<TaxBracket, TaxBracketRequest, number> {
  return {
    id: 'tax-brackets',
    title: 'Tax brackets',
    legacy: 'TAX_BRACKETS (TaxEngine ladders; overlap -20608, locked year -20609)',
    dto: 'TaxBracketRequest',
    codeField: '',
    fields: [
      { name: 'taxYear', label: 'Tax year' },
      { name: 'filingStatus', label: 'Filing status', hint: 'State rows use ALL' },
      { name: 'stateCode', label: 'State', hint: 'Blank = federal ladder step' },
      { name: 'bracketMin', label: 'Bracket minimum' },
      { name: 'bracketMax', label: 'Bracket maximum', hint: 'Blank = open-ended top step' },
      { name: 'taxRate', label: 'Rate', hint: 'Fraction, e.g. 0.2200' },
      { name: 'baseTax', label: 'Base tax' },
      { name: 'activeFlag', label: 'Active' },
    ],
    columns: [
      { key: 'taxYear', label: 'Year', render: (r) => (r.locked ? `${r.taxYear} (locked)` : r.taxYear) },
      { key: 'stateCode', label: 'Jurisdiction', render: (r) => r.stateCode ?? 'Federal' },
      { key: 'filingStatus', label: 'Filing status', render: (r) => humanizeCode(r.filingStatus) },
      { key: 'range', label: 'Range', render: (r) => `${r.bracketMin} – ${r.bracketMax ?? '∞'}` },
      { key: 'taxRate', label: 'Rate', render: (r) => r.taxRate },
      { key: 'baseTax', label: 'Base tax', render: (r) => dash(r.baseTax) },
    ],
    rowId: (r) => r.bracketId,
    rowLabel: (r) => `${r.taxYear} ${r.stateCode ?? 'Federal'} ${humanizeCode(r.filingStatus)} from ${r.bracketMin}`,
    list: api.admin.listTaxBrackets,
    create: api.admin.createTaxBracket,
    update: api.admin.updateTaxBracket,
    deactivate: api.admin.deactivateTaxBracket,
    rowLocked: (r) => (r.locked ? 'Locked by an approved payroll run' : null),
    invalidates: [ladderGapsKey],
  };
}

/** Tax brackets grid plus the `GET /api/admin/tax-brackets/ladder-gaps` panel for one year. */
export function TaxBracketsTab() {
  const [year, setYear] = useState(String(new Date().getFullYear()));
  const taxYear = Number(year);
  const valid = Number.isInteger(taxYear) && taxYear >= 2000 && taxYear <= 2100;
  const gaps = useQuery({ queryKey: [...ladderGapsKey, taxYear], queryFn: () => api.admin.taxLadderGaps(taxYear), enabled: valid });
  return (
    <ReferenceDataTab config={taxBracketsConfig()}>
      <section aria-labelledby="ladder-gaps-title" className="panel">
        <h3 id="ladder-gaps-title">Federal ladder gaps</h3>
        <form className="toolbar" onSubmit={(e) => e.preventDefault()}>
          <label htmlFor="ladderGapYear">Tax year</label>
          <input id="ladderGapYear" type="number" min={2000} max={2100} value={year} onChange={(e) => setYear(e.target.value)} />
        </form>
        {gaps.isError && (
          <p role="alert" className="field-error">
            Could not load ladder gaps.
          </p>
        )}
        {gaps.data && gaps.data.length === 0 && <p role="status">No active federal ladders for {taxYear}.</p>}
        {gaps.data && gaps.data.length > 0 && (
          <ul data-testid="ladder-gaps">
            {gaps.data.map((g) => (
              <li key={g.filingStatus}>
                <strong>{humanizeCode(g.filingStatus)}</strong>: {g.gaps.length === 0 ? 'contiguous' : g.gaps.map((gap) => `[${gap.from}, ${gap.to ?? '∞'})`).join(', ')}
                {g.gaps.length > 0 && <span className="muted"> → engine returns MISSING_TAX_RATE in these ranges</span>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </ReferenceDataTab>
  );
}
