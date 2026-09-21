import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Toolbar } from '../Toolbar';

describe('Toolbar', () => {
  it('renders only the buttons whose handlers are supplied and wires them', async () => {
    const onSave = vi.fn();
    const onExit = vi.fn();
    render(<Toolbar onSave={onSave} onExit={onExit} />);
    const buttons = screen.getAllByRole('button').map((b) => b.textContent);
    expect(buttons).toEqual(['Save', 'Exit']);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Save' }));
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('disables everything while busy', () => {
    render(<Toolbar onSave={vi.fn()} onDelete={vi.fn()} busy />);
    for (const b of screen.getAllByRole('button')) expect(b).toBeDisabled();
  });

  it('renders pagination with boundary-aware disabling', () => {
    const p = { page: 0, totalPages: 3, onFirst: vi.fn(), onPrev: vi.fn(), onNext: vi.fn(), onLast: vi.fn() };
    render(<Toolbar pagination={p} />);
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'First' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Last' })).toBeEnabled();
  });
});
