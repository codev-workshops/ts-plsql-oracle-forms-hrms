import type { PayrollDetail } from './api';
import { money } from './format';

export function PayrollStatus({ status }: { status: string }) {
  return <span className="payroll-status" data-status={status}>{status}</span>;
}

export function DetailTable({ lines, showEmployee = false }: { lines: PayrollDetail[]; showEmployee?: boolean }) {
  return (
    <div className="payroll-table">
      <table aria-label="Pay elements">
        <thead>
          <tr>
            {showEmployee && <th>Employee ID</th>}
            <th>Pay element</th><th>Type</th><th>Amount</th><th>Status</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr key={line.detailId}>
              {showEmployee && <td>{line.empId}</td>}
              <th scope="row">{line.elementCode ?? 'Calculation error'}</th>
              <td>{line.elementCode ? line.elementType : '—'}</td>
              <td>{money(line.amount)}</td>
              <td>
                <PayrollStatus status={line.status} />
                {line.errorMessage && <div className="error">{line.errorMessage}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
