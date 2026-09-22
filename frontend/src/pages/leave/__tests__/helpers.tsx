import type { ReactElement } from 'react';
import { Route, Routes } from 'react-router-dom';
import { setAccessToken } from '../../../api/http';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { renderWithProviders } from '../../../test/render';

/** Mounts `ui` under `/leave/*` exactly like App.tsx does, so LeavePage's nested Routes resolve. */
export function renderAs(email: string, ui: ReactElement, path = '/leave') {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(
    <Routes>
      <Route path="/leave/*" element={ui} />
    </Routes>,
    { initialUser: MOCK_USERS[email], initialEntries: [path] },
  );
}
