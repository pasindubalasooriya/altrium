import { RATING_LABELS, type TimelineEntry } from '../../api/types'
import { EmptyState } from '../../components/States'

/**
 * Somebody's cycles, newest first (P-4.9).
 *
 * Shared by the employee's own history screen and by the manager's and HR's review screens,
 * because it is the same list read through three different scopes. The server decides what
 * each entry carries; this renders whatever arrived and infers nothing.
 *
 * **A missing rating is never labelled "withheld".** The server returns null both for a rating
 * still with HR and for a cycle where none was ever set, and those two are indistinguishable on
 * purpose (P-4.4): a screen that said "withheld" would reconstruct the very disclosure the null
 * exists to prevent. "Not shared" is true of both and claims nothing about which.
 */
export function Timeline({ entries, emptyMessage }: {
  entries: TimelineEntry[]
  emptyMessage: string
}) {
  if (entries.length === 0) {
    return <EmptyState>{emptyMessage}</EmptyState>
  }

  return (
    <ol className="space-y-3">
      {entries.map((entry) => (
        <li key={entry.cycleId} className="rounded border border-line bg-white p-4">
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <h3 className="font-medium">
              FY{entry.financialYear} · Q{entry.quadrimester}
            </h3>
            <span className="text-xs text-muted">
              {entry.startDate} to {entry.endDate}
            </span>
            {entry.cycleStatus === 'OPEN' && (
              <span className="rounded bg-brand/10 px-2 py-0.5 text-xs text-brand">
                In progress
              </span>
            )}
          </div>

          <p className="mt-2 text-sm">
            {entry.rating ? (
              <span className="font-medium">{RATING_LABELS[entry.rating]}</span>
            ) : (
              <span className="text-muted">Not shared</span>
            )}
          </p>

          {entry.managerFeedback && (
            <p className="mt-2 whitespace-pre-wrap text-sm text-muted">
              {entry.managerFeedback}
            </p>
          )}
        </li>
      ))}
    </ol>
  )
}
