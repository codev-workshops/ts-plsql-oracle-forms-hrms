import type { FormEvent, ReactNode } from 'react';

interface Props {
  title: string;
  onSubmit: (event: FormEvent) => void;
  onClose: () => void;
  busy: boolean;
  submitLabel: string;
  children: ReactNode;
  destructive?: boolean;
}

/** Modal form shell shared by the employee dialogs (same markup as leave's CancelRequestDialog). */
export function Dialog({ title, onSubmit, onClose, busy, submitLabel, children, destructive }: Props) {
  const id = `dlg-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby={id} className="dialog">
        <h3 id={id}>{title}</h3>
        <form onSubmit={onSubmit} noValidate>
          <fieldset disabled={busy}>{children}</fieldset>
          <div className="actions">
            <button type="submit" className={destructive ? 'danger' : undefined} disabled={busy}>
              {submitLabel}
            </button>
            <button type="button" onClick={onClose} disabled={busy}>
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
