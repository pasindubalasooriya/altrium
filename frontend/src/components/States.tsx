import type { ReactNode } from 'react'
import { ApiError, isForbidden } from '../api/errors'

/**
 * The one denial screen.
 *
 * One component, one wording, used everywhere. If a denial looked different depending on
 * which screen produced it, the difference would itself be the signal the backend refuses to
 * give: it answers "not permitted" and "no such record" identically, and a client that said
 * "no such employee" on one screen and "access denied" on another would undo that in the UI.
 *
 * It also never says *why*. Explaining the rule that fired would be a description of the
 * organisation's reporting lines and HR grants, handed to the person who just failed to get
 * past them.
 */
export function Forbidden() {
  return (
    <Panel tone="warn" title="You do not have access to this">
      <p>
        If you think you should, ask your manager or HR. Access follows your reporting line
        and your role, so it can change without anything being wrong here.
      </p>
    </Panel>
  )
}

/** A validated Asgardeo account with no Altrium record behind it. */
export function NotProvisioned() {
  return (
    <Panel tone="warn" title="Your account is not set up in Altrium">
      <p>
        You signed in successfully, but there is no Altrium record for this account, or it
        has been deactivated. Ask the system administrator to set it up.
      </p>
    </Panel>
  )
}

export function Loading({ what = 'Loading' }: { what?: string }) {
  return <p className="p-6 text-muted">{what}…</p>
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="rounded border border-line bg-white p-6 text-muted">{children}</p>
}

/**
 * Renders a query failure.
 *
 * A 403 gets the denial screen. Everything else gets an ordinary error, because a network
 * failure is not a permission decision and telling someone they lack access when the API is
 * simply down would send them to ask for a grant they already hold.
 */
export function QueryFailure({ error }: { error: unknown }) {
  if (isForbidden(error)) {
    return <Forbidden />
  }
  const detail =
    error instanceof ApiError && error.status === 0
      ? 'Could not reach the Altrium API. Is the backend running?'
      : 'Something went wrong loading this.'
  return (
    <Panel tone="warn" title="Could not load">
      <p>{detail}</p>
    </Panel>
  )
}

function Panel({
  tone,
  title,
  children,
}: {
  tone: 'warn' | 'plain'
  title: string
  children: ReactNode
}) {
  return (
    <section
      className={`rounded border p-6 ${
        tone === 'warn' ? 'border-warn/40 bg-warn/5' : 'border-line bg-white'
      }`}
    >
      <h2 className="mb-2 font-semibold">{title}</h2>
      <div className="text-sm text-muted">{children}</div>
    </section>
  )
}
