import { useAuthContext } from '@asgardeo/auth-react'
import { NavLink, Outlet } from 'react-router-dom'
import { hasRole, useCurrentUser } from '../auth/useCurrentUser'
import type { Role } from '../api/types'
import { Loading, NotProvisioned, QueryFailure } from './States'
import { useSelectedCycle } from './CycleSelect'
import { useMyPeerAssignments, usePermittedReviews } from '../api/reviews'
import { anyWaiting, hrIsWaitedOn, managerIsWaitedOn } from '../features/manager/waiting'
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
      <Header id={me.id} fullName={me.fullName} roles={me.roles} />
      <main className="mx-auto w-full max-w-6xl flex-1 p-6">
        <Outlet />
      </main>
    </div>
  )
}

function Header({ id, fullName, roles }: { id: number; fullName: string; roles: Role[] }) {
  const { signOut } = useAuthContext()

  // Peer reviews are the one thing in this app somebody is asked to do *for* somebody else, and
  // nothing else tells them it is waiting: no email goes out in Sprint 1, and the task sits
  // behind a tab they have no reason to open. So the nav says so.
  //
  // Unlike the manager's unread dot on the team list, this one is not cleared by looking. It
  // marks outstanding work rather than something new, and work does not stop being owed because
  // you glanced at it - it goes when the review is submitted, which the mutation already
  // refreshes. Nothing is remembered anywhere for it.
  const { cycleId } = useSelectedCycle()

  // Whether this account takes part in the review cycle at all, on either side of it. The Super
  // Admin is a dedicated platform account (P-9.5) and Leadership sit above the review chain
  // (P-1.5, P-7.2): neither is reviewed, and neither is anybody's peer - Leadership are refused
  // as peer reviewers by P-3.13. So the whole personal half of this navigation goes, and the
  // peer-assignment query is not fired for an account that can never hold one.
  const participates = !hasRole(roles, 'SUPER_ADMIN') && !hasRole(roles, 'LEADERSHIP')
  const tasks = useMyPeerAssignments(participates ? cycleId : undefined)
  const peerReviewsOwed = tasks.data?.some((task) => !task.submitted) ?? false

  // The same list serves both consoles - it returns different rows because the scope is in the
  // SQL, not because the client asks differently - so one query answers both dots. It is read
  // here rather than per screen because the whole point is to reach somebody who has not opened
  // the screen yet.
  const showsReviewLists = hasRole(roles, 'MANAGER') || hasRole(roles, 'HR')
  const reviews = usePermittedReviews(showsReviewLists ? cycleId : undefined, 0, 25)
  const rows = reviews.data?.content
  const teamWaiting = cycleId !== undefined
    && anyWaiting(rows, (review) => managerIsWaitedOn(cycleId, review, id))
  const hrWaiting = anyWaiting(rows, hrIsWaitedOn)

  return (
    <header className="border-b-2 border-brand bg-white">
      <div className="mx-auto flex w-full max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 p-4">
        {/*
          The supplied wordmark, which already contains the name, so no text label sits beside
          it. Height-constrained with width:auto so the aspect ratio is the file's, not ours.
        */}
        <img src="/altrium-logo.png" alt="Altrium" className="h-7 w-auto" />

        <nav className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
          {/*
            Two kinds of account have no personal stake in a review cycle, for different
            reasons, and so hold no self-review, no rating, no plan and no peer task. The Super
            Admin is a dedicated platform account and is never a reviewee (P-9.5). Leadership
            sit above the review chain: no review, rating or plan exists for them at all (P-1.5,
            P-7.2), and they are refused as peer reviewers (P-3.13), so a Leadership account
            sees only Metrics.

            Omitted rather than disabled: a disabled control implies a permission that could be
            granted, and for these accounts there is no artifact behind the link at all.

            Still cosmetic, as the rest of this nav is. The server refuses on its own - step 2
            of the evaluation order denies any review capability whose subject is Leadership or
            a Super Admin - and typing the URL gets that refusal rather than a screen.
          */}
          {participates && (
            <>
              <Link to="/my/reviews">My review</Link>
              <Link to="/my/self-review">Self-review</Link>
              <Link
                to="/my/peer-tasks"
                dot={peerReviewsOwed}
                dotLabel="You have a peer review to write"
              >
                Peer reviews
              </Link>
              <Link to="/my/plan">My plan</Link>
            </>
          )}
          {hasRole(roles, 'MANAGER') && (
            <Link to="/manager/team" dot={teamWaiting} dotLabel="Something is waiting on you">
              My team
            </Link>
          )}
          {hasRole(roles, 'HR') && <Link to="/hr/cycles">Cycles</Link>}
          {hasRole(roles, 'HR') && (
            <Link to="/hr/reviews" dot={hrWaiting} dotLabel="A rating is waiting for sign-off">
              HR reviews
            </Link>
          )}
          {hasRole(roles, 'HR') && <Link to="/hr/improvement-plans">Improvement plans</Link>}
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

function Link({ to, dot, dotLabel, children }: {
  to: string
  /** Something is waiting behind this link. */
  dot?: boolean
  dotLabel?: string
  children: string
}) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        `inline-flex items-center gap-1.5 ${
          isActive ? 'font-medium text-accent' : 'text-muted hover:text-ink'
        }`
      }
    >
      {children}
      {dot && (
        <>
          <span
            aria-hidden="true"
            className="inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-brand"
          />
          <span className="sr-only">{dotLabel ?? 'Waiting for you'}</span>
        </>
      )}
    </NavLink>
  )
}
