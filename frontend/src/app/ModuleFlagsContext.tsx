import { createContext, useContext, type ReactNode } from 'react';
import { MODULE_FLAGS } from './AppShell';
import type { ModuleFlagValue } from './modules';

const ModuleFlagsContext = createContext<Record<string, ModuleFlagValue>>(MODULE_FLAGS);

/** Proxy flags per module; tests override via `flags` (CUTOVER_PLAN.md §7.1 read-only step). */
export function ModuleFlagsProvider({ flags, children }: { flags?: Record<string, ModuleFlagValue>; children: ReactNode }) {
  return <ModuleFlagsContext.Provider value={flags ?? MODULE_FLAGS}>{children}</ModuleFlagsContext.Provider>;
}

export function useModuleFlag(name: string): ModuleFlagValue {
  return useContext(ModuleFlagsContext)[name] ?? 'LEGACY';
}

/** Write routes are mounted only at `NEW`; `NEW_READONLY` renders the module without write controls. */
export function useModuleWritable(name: string): boolean {
  return useModuleFlag(name) === 'NEW';
}
