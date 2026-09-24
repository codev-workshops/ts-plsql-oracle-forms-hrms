import type { Authority, ProxyModule } from '../api/types';

/**
 * Home tiles = HRMS_MENU buttons (COMPONENT_MAPPING.md §2). Visibility follows the
 * `has_permission` calls the menu made: PAYROLL/ADMIN/REPORTS gated, the rest granted to
 * every authenticated user. Until a module is promoted (`NEW*` proxy flag) its tile is
 * rendered disabled (P0-D1: legacy Oracle Forms are not available in this environment,
 * so there is no legacy-tile navigation and the SPA never calls `/legacy/sso/exchange`).
 */
export interface ModuleTile {
  id: string;
  label: string;
  description: string;
  /** Route in the SPA once promoted; also the proxy path while LEGACY. */
  path: string;
  proxyFlag: ProxyModule | null;
  formsModule: string | null;
  /** Empty = every authenticated user (mirrors `has_permission` granting LEAVE/EMPLOYEE:VIEW to all). */
  anyOf: Authority[];
  /** Tiles without a recoverable source are hidden until Phase 5 (PROC-02). */
  hiddenUntilPhase5?: boolean;
  /** Tiles with no proxy route of their own go live together with this module's flag. */
  promotedWith?: ProxyModule;
}

export const MODULE_TILES: readonly ModuleTile[] = [
  {
    id: 'employees',
    label: 'Employees',
    description: 'Employee records, transfers, terminations',
    path: '/employees',
    proxyFlag: 'employee',
    formsModule: 'HRMS_EMPLOYEE',
    anyOf: ['EMPLOYEE:VIEW'],
  },
  {
    id: 'payroll',
    label: 'Payroll',
    description: 'Pay runs, calculations, approvals',
    path: '/payroll',
    proxyFlag: 'payroll',
    formsModule: 'HRMS_PAYROLL',
    anyOf: ['PAYROLL:VIEW'],
  },
  {
    id: 'leave',
    label: 'Leave',
    description: 'Leave requests, approvals, balances',
    path: '/leave',
    proxyFlag: 'leave',
    formsModule: 'HRMS_LEAVE',
    anyOf: ['LEAVE:VIEW'],
  },
  {
    id: 'performance',
    label: 'Performance',
    description: 'Review cycles, ratings, goals',
    path: '/performance',
    proxyFlag: 'performance',
    formsModule: 'HRMS_PERFORMANCE',
    anyOf: [],
  },
  {
    id: 'reports',
    label: 'Reports',
    description: 'Headcount, compensation, turnover',
    path: '/reports',
    proxyFlag: 'reporting',
    formsModule: null,
    anyOf: ['REPORTS:VIEW'],
    hiddenUntilPhase5: true,
  },
  {
    id: 'admin',
    label: 'Administration',
    description: 'System parameters and reference data',
    path: '/admin',
    proxyFlag: null,
    formsModule: null,
    anyOf: ['ADMIN:VIEW'],
    hiddenUntilPhase5: true,
    // `/api/admin/**` and `/admin` are gated by the `reporting` flag (contracts/p5-reporting-decommission/openapi.yaml, "Proxy flags").
    promotedWith: 'reporting',
  },
];

/** Every switchable proxy module (proxy/flags.env); `auth` is NEW from Phase 0. */
export const SWITCHABLE_MODULES: readonly ProxyModule[] = ['employee', 'payroll', 'leave', 'performance', 'reporting'];

/**
 * Forms decommission switch (CUTOVER_PLAN.md §9.3): `decommission=NEW` in `VITE_MODULE_FLAGS` is
 * flipped after the 30-day zero-legacy-hit gate. It only takes effect once **every** switchable
 * module is `NEW` – a module still on LEGACY / NEW_READONLY keeps its (disabled) legacy tile.
 */
export const DECOMMISSION_FLAG = 'decommission';

/**
 * Proxy flag values per module, frozen vocabulary from contracts/p0-foundation/README.md.
 * The SPA reads them from `VITE_MODULE_FLAGS` (e.g. `employee=NEW_READONLY,leave=NEW`);
 * the default at the end of P0 is LEGACY for every switchable module.
 */
export type ModuleFlagValue = 'LEGACY' | 'NEW_READONLY' | 'NEW' | 'JAVA';

/** Flags of the running build (`VITE_MODULE_FLAGS`). */
export const MODULE_FLAGS = parseModuleFlags(import.meta.env.VITE_MODULE_FLAGS);

export function parseModuleFlags(raw: string | undefined): Record<string, ModuleFlagValue> {
  const out: Record<string, ModuleFlagValue> = { auth: 'NEW' };
  if (!raw) return out;
  for (const pair of raw.split(',')) {
    const [k, v] = pair.split('=').map((s) => s.trim());
    if (k && (v === 'LEGACY' || v === 'NEW_READONLY' || v === 'NEW' || v === 'JAVA')) out[k] = v;
  }
  return out;
}

export function isModulePromoted(tile: ModuleTile, flags: Record<string, ModuleFlagValue>): boolean {
  const key = tile.proxyFlag ?? tile.promotedWith;
  if (!key) return false;
  const v = flags[key];
  return v === 'NEW' || v === 'NEW_READONLY';
}

export function allModulesNew(flags: Record<string, ModuleFlagValue>): boolean {
  return SWITCHABLE_MODULES.every((m) => flags[m] === 'NEW');
}

/** True when the legacy tiles and the SSO bridge entry points must disappear from the shell. */
export function isDecommissioned(flags: Record<string, ModuleFlagValue>): boolean {
  return flags[DECOMMISSION_FLAG] === 'NEW' && allModulesNew(flags);
}

export function visibleTiles(
  roles: readonly Authority[],
  flags: Record<string, ModuleFlagValue>,
): Array<ModuleTile & { promoted: boolean }> {
  const decommissioned = isDecommissioned(flags);
  return MODULE_TILES.filter((t) => {
    if (t.anyOf.length > 0 && !t.anyOf.some((a) => roles.includes(a))) return false;
    const promoted = isModulePromoted(t, flags);
    if (t.hiddenUntilPhase5 && !promoted) return false;
    if (decommissioned && !promoted) return false;
    return true;
  }).map((t) => ({ ...t, promoted: isModulePromoted(t, flags) }));
}
