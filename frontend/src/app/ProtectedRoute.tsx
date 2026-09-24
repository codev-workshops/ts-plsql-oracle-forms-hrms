import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { Authority } from '../api/types';
import { useAuth } from './AuthContext';

interface Props {
  /** Any one of these authorities grants access; omit for "authenticated only". */
  anyOf?: Authority[];
  /** Skip the forced password-change redirect (used by the change-password route itself). */
  allowMustChangePassword?: boolean;
}

/**
 * Replaces `PKG_SECURITY.is_session_valid` + `has_permission` gating in HRMS_MENU
 * (COMPONENT_MAPPING.md §1 `ProtectedRoute`, §7 `check_session`).
 */
export function ProtectedRoute({ anyOf, allowMustChangePassword = false }: Props) {
  const { status, user, hasAnyAuthority } = useAuth();
  const location = useLocation();

  if (status === 'initialising') {
    return (
      <div className="page-loading" role="status" aria-live="polite">
        Loading…
      </div>
    );
  }
  if (status !== 'authenticated' || !user) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  if (user.mustChangePassword && !allowMustChangePassword && location.pathname !== '/password') {
    return <Navigate to="/password" replace state={{ forced: true }} />;
  }
  if (anyOf && anyOf.length > 0 && !hasAnyAuthority(...anyOf)) {
    return <Navigate to="/forbidden" replace />;
  }
  return <Outlet />;
}
