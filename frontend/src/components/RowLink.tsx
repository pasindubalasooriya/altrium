import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * A row action at the end of a table row.
 *
 * Bordered rather than set in accent text. At the end of a row, coloured text reads as a status
 * - "Open review" alongside "deactivated" in the same column looked like a state the person was
 * in, not a thing to press - and the tables here carry both. The border gives it an edge, the
 * hover moves, and the underline is a second signal for anybody who cannot pick the colour out.
 *
 * `dot` marks the control as having something new behind it. It belongs on the control rather
 * than beside the name: a dot next to a person says something about the person, and what is new
 * here is what sits behind this particular link - the same row's other action has not changed.
 */
export function RowLink({
  to,
  dot,
  dotLabel,
  children,
}: {
  to: string
  dot?: boolean
  /** What is new, for anybody who cannot see the dot. */
  dotLabel?: string
  children: ReactNode
}) {
  return (
    <Link
      to={to}
      className="ml-2 inline-flex cursor-pointer items-center gap-2 rounded border border-line bg-surface px-3 py-1.5 text-sm text-accent shadow-sm transition-colors hover:border-ink/25 hover:bg-line/30 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
    >
      {children}
      {dot && (
        <>
          <span
            aria-hidden="true"
            className="inline-block h-2 w-2 shrink-0 rounded-full bg-brand"
          />
          <span className="sr-only">{dotLabel ?? 'New'}</span>
        </>
      )}
    </Link>
  )
}
