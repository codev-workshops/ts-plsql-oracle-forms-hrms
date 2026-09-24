import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { ErrorBoundary } from '../ErrorBoundary';

function Bomb({ explode }: { explode: boolean }) {
  if (explode) throw new Error('boom');
  return <p>Fine</p>;
}

function Harness() {
  const [explode, setExplode] = useState(true);
  return (
    <ErrorBoundary fallback={(err, reset) => (
      <div role="alert">
        {err.message}
        <button type="button" onClick={() => { setExplode(false); reset(); }}>Reset</button>
      </div>
    )}>
      <Bomb explode={explode} />
    </ErrorBoundary>
  );
}

describe('ErrorBoundary', () => {
  it('renders the default fallback on a render error', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <ErrorBoundary>
        <Bomb explode />
      </ErrorBoundary>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('supports a custom fallback and recovers on reset', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<Harness />);
    expect(screen.getByRole('alert')).toHaveTextContent('boom');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reset' }));
    expect(screen.getByText('Fine')).toBeInTheDocument();
  });
});
