export interface ToolbarPagination {
  page: number;
  totalPages: number;
  onFirst: () => void;
  onPrev: () => void;
  onNext: () => void;
  onLast: () => void;
}

export interface ToolbarProps {
  onSave?: () => void;
  onClear?: () => void;
  onSearch?: () => void;
  onNew?: () => void;
  onDelete?: () => void;
  onExit?: () => void;
  pagination?: ToolbarPagination;
  /** Disable everything while a request is in flight. */
  busy?: boolean;
}

/**
 * Replaces HRMS_COMMON_LIB `toolbar_*` procedures (COMPONENT_MAPPING.md §7). Buttons are
 * rendered only for the handlers a page supplies; Delete stays absent where the domain
 * forbids it.
 */
export function Toolbar({ onSave, onClear, onSearch, onNew, onDelete, onExit, pagination, busy = false }: ToolbarProps) {
  const btn = (label: string, handler?: () => void, extra: { disabled?: boolean } = {}) =>
    handler ? (
      <button type="button" onClick={handler} disabled={busy || extra.disabled}>
        {label}
      </button>
    ) : null;

  return (
    <div className="toolbar" role="toolbar" aria-label="Record actions">
      {btn('Save', onSave)}
      {btn('Clear', onClear)}
      {btn('Query', onSearch)}
      {btn('New', onNew)}
      {btn('Delete', onDelete)}
      {pagination && (
        <span className="toolbar-pagination">
          {btn('First', pagination.onFirst, { disabled: pagination.page <= 0 })}
          {btn('Previous', pagination.onPrev, { disabled: pagination.page <= 0 })}
          <span aria-live="polite">
            Page {pagination.page + 1} of {Math.max(pagination.totalPages, 1)}
          </span>
          {btn('Next', pagination.onNext, { disabled: pagination.page >= pagination.totalPages - 1 })}
          {btn('Last', pagination.onLast, { disabled: pagination.page >= pagination.totalPages - 1 })}
        </span>
      )}
      {btn('Exit', onExit)}
    </div>
  );
}
