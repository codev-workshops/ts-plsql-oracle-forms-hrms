import { Link } from 'react-router-dom';

export function ForbiddenPage() {
  return (
    <section role="alert" className="forbidden-page">
      <h1>Access denied</h1>
      <p>You do not have permission to open this module.</p>
      <Link to="/">Back to home</Link>
    </section>
  );
}
