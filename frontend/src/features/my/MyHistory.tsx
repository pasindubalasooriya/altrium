import { useMyHistory } from '../../api/history'
import { Loading, QueryFailure } from '../../components/States'
import { Timeline } from '../history/Timeline'

/**
 * The employee's own history across cycles (scenario section 5 step 9).
 *
 * The screen that answers "how have I done over time", which until now nothing did: every
 * rating endpoint took a cycle and returned that one cycle, so an employee could see this
 * quadrimester and had no way to look back at the last.
 *
 * Not cycle-scoped, deliberately, and so it carries no cycle selector. A selector would turn
 * the timeline back into the single-cycle screen it exists to replace.
 */
export function MyHistory() {
  const { data: entries, isPending, error } = useMyHistory()

  if (isPending) {
    return <Loading />
  }
  if (error) {
    return <QueryFailure error={error} />
  }

  return (
    <>
      <h1 className="mb-1 text-xl font-semibold tracking-tight">My history</h1>
      <p className="mb-4 text-sm text-muted">
        Every review cycle you have taken part in, newest first. A cycle shows its result once
        your manager has shared it with you.
      </p>

      <Timeline
        entries={entries}
        emptyMessage="You have not been through a review cycle yet. This fills in after your first one."
      />
    </>
  )
}
