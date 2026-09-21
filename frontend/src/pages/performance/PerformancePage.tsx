import { Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { GoalsTab } from './GoalsTab';
import { MyReviewsTab } from './MyReviewsTab';
import { ReviewCyclesTab } from './ReviewCyclesTab';
import { TeamTab } from './TeamTab';

const tabs = [
  { label: 'Review Cycles', path: '/performance' },
  { label: 'My Reviews', path: '/performance/my-reviews' },
  { label: 'Goals', path: '/performance/goals' },
  { label: 'Team', path: '/performance/team' },
];

export function PerformancePage() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <section className="performance-page">
      <h1>Performance</h1>
      <div className="tabs" role="tablist" aria-label="Performance">
        {tabs.map((tab) => {
          const selected = tab.path === '/performance' ? location.pathname === tab.path : location.pathname.startsWith(tab.path);
          return (
            <button
              key={tab.path}
              type="button"
              role="tab"
              aria-selected={selected}
              onClick={() => navigate(tab.path)}
            >
              {tab.label}
            </button>
          );
        })}
      </div>
      <Routes>
        <Route index element={<ReviewCyclesTab />} />
        <Route path="my-reviews" element={<MyReviewsTab />} />
        <Route path="goals" element={<GoalsTab />} />
        <Route path="team" element={<TeamTab />} />
        <Route path="*" element={<ReviewCyclesTab />} />
      </Routes>
    </section>
  );
}
