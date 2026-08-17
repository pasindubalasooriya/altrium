import { NavLink } from 'react-router-dom'

/** Sub-navigation for the administration console. Cosmetic, like all navigation here. */
export function AdminNav() {
  return (
    <nav className="mb-6 flex flex-wrap gap-4 border-b border-line pb-3 text-sm">
      <Tab to="/admin/users">Users</Tab>
      <Tab to="/admin/departments">Departments</Tab>
      <Tab to="/admin/hr-grants">HR grants</Tab>
      <Tab to="/admin/cycles">Cycles</Tab>
      <Tab to="/admin/cohorts">Cohorts</Tab>
    </nav>
  )
}

function Tab({ to, children }: { to: string; children: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        isActive ? 'font-medium text-accent' : 'text-muted hover:text-ink'
      }
    >
      {children}
    </NavLink>
  )
}
