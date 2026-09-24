import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles.css';

async function bootstrap() {
  if (import.meta.env.VITE_MOCK_API === 'true') {
    const { startBrowserMocks } = await import('./mocks/browser');
    await startBrowserMocks();
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void bootstrap();
