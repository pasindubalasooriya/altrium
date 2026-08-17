import { useAuthContext } from '@asgardeo/auth-react'
import { NavLink, Outlet } from 'react-router-dom'
import { hasRole, useCurrentUser } from '../auth/useCurrentUser'
import type { Role } from '../api/types'
import { Loading, NotProvisioned, QueryFailure } from './States'
import { isForbidden } from '../api/errors'

/**
 * The frame every signed-in screen sits in.
 *
 * The navigation is built from the caller's roles, and that is **cosmetic**. It decides which
 * links are worth showing; it decides nothing about access. Every route behind every link
 * re-checks on the server, and typing a URL for a console you do not hold gets you the same
 * 403 the hidden link would have.
 */
export function Shell() {
  const { data: me, isPending, error } = useCurrentUser()

  if (isPending) {
    return <Loading what="Signing you in" />
  }

  if (error) {
    // 403 from /api/me is not an ordinary denial: it means Asgardeo authenticated somebody
    // Altrium has no active record for. The generic denial screen would be true but useless,
    // because there is no permission to ask for - the account itself is missing.
    return isForbidden(error) ? <NotProvisioned /> : <QueryFailure error={error} />
  }

  return (
    <div className="flex min-h-full flex-col">
      <Header fullName={me.fullName} roles={me.roles} />
      <main className="mx-auto w-full max-w-6xl flex-1 p-6">
        <Outlet />
      </main>
    </div>
  )
}

function Header({ fullName, roles }: { fullName: string; roles: Role[] }) {
  const { signOut } = useAuthContext()

  return (
    <header className="border-b border-line bg-white">
      <div className="mx-auto flex w-full max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 p-4">
        <span className="font-semibold tracking-tight">Altrium</span>

        <nav className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
          <Link to="/my/reviews">My review</Link>
          <Link to="/my/self-review">Self-review</Link>
          <Link to="/my/peer-tasks">Peer reviews</Link>
          <Link to="/my/rating">My rating</Link>
          <Link to="/my/plan">My plan</Link>
          {hasRole(roles, 'MANAGER') && <Link to="/manager/team">My team</Link>}
          {hasRole(roles, 'HR') && <Link to="/hr/cycles">Cycles</Link>}
          {hasRole(roles, 'HR') && <Link to="/hr/reviews">HR reviews</Link>}
          {hasRole(roles, 'HR') && <Link to="/hr/improvement-plans">Improvement plans</Link>}
          {hasRole(roles, 'HR') && <Link to="/hr/scope">My scope</Link>}
          {hasRole(roles, 'LEADERSHIP') && <Link to="/leadership/metrics">Metrics</Link>}
          {hasRole(roles, 'SUPER_ADMIN') && <Link to="/admin/users">Administration</Link>}
        </nav>

        <div className="ml-auto flex items-center gap-3 text-sm">
          <span className="text-muted">{fullName}</span>
          <button
            type="button"
            className="rounded border border-line px-3 py-1"
            // signOut hits Asgardeo's end-session endpoint. Clearing tokens locally would
            // leave the identity-provider session alive, so the next sign-in would reuse it
            // silently and the user would appear unable to log out.
            onClick={() => void signOut()}
          >
            Sign out
          </button>
        </div>
      </div>
    </header>
  )
}

function Link({ to, children }: { to: string; children: string }) {
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
