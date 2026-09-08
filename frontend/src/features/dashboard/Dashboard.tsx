import { useDashboard } from '../../api/dashboard'
import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { Card } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import { RatingBars } from './RatingBars'
import { ExportButtons } from './ExportButtons'
import { hasRole, useCurrentUser } from '../../auth/useCurrentUser'

/**
 * The manager's and HR's dashboard (scenario section 13).
 *
 * **One screen for both**, because it is one endpoint: the scope lives in the server's `WHERE`
 * clause, so a manager sees their reports and an HR user sees their granted departments from an
 * identical request. Two screens would mean two ideas of who counts, and the one that differed
 * would be the leak.
 *
 * **Nothing here is clickable through to a person**, and not because a link was left out: the
 * response carries no id and no name, so there is nothing to build one from. Both roles do have
 * drill-down screens - the review list and the calibration screen - and those check on their own.
 */
export function Dashboard() {
  const { cycles, cycleId, setCycleId } = useSelectedCycle()
  const { data, isPending, error } = useDashboard(cycleId)
  const { data: me } = useCurrentUser()

  // A manager sees this whole screen and no export control, which is the one place in the app
  // where somebody is shown data they cannot take out (P-8.1). Cosmetic: the endpoint refuses
  // them on its own.
  const mayExport = me !== undefined && hasRole(me.roles, 'HR')

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Dashboard</h1>
      <CycleSelect cycles={cycles} cycleId={cycleId} onChange={setCycleId} />

      {cycleId === undefined ? null : isPending ? (
        <Loading />
      ) : error ? (
        <QueryFailure error={error} />
      ) : data.scopeIsEmpty ? (
        // Told apart from "nobody has started yet" by the server, because the two are the same
        // set of zeroes and only this one is worth explaining.
        <EmptyState>
          There is no dashboard for you in this cycle. It covers the people whose reviews you can
          open, and that is nobody here.
        </EmptyState>
      ) : (
        <div className="grid gap-4">
          <Card title={`Progress · ${data.cycleLabel}`}>
            <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
              <Tile label="Under review" value={data.participants} />
              <Tile
                label="Self-reviews in"
                value={data.selfReviewsSubmitted}
                of={data.participants}
              />
              <Tile
                label="Manager reviews in"
                value={data.managerReviewsSubmitted}
                of={data.participants}
              />
              <Tile
                label="Peer reviews in"
                value={data.peerReviewsSubmitted}
                // Two peers each, so the target is twice the number of people (P-3.6).
                of={data.participants * 2}
              />
              <Tile label="Ratings set" value={data.ratingsSet} of={data.participants} />
              <Tile label="Shared with the employee" value={data.ratingsReleased} of={data.ratingsSet} />
            </dl>
          </Card>

          <Card title="Ratings">
            <RatingBars
              distribution={data.ratingDistribution}
              empty="No ratings have been set yet in this cycle."
            />
          </Card>

          {mayExport && cycleId !== undefined && <ExportButtons cycleId={cycleId} />}
        </div>
      )}
    </>
  )
}

/**
 * One number, with the total it is working towards where there is a meaningful one.
 *
 * The denominator is not decoration: "3 self-reviews" says nothing on its own, and "3 of 4"
 * is the whole point of a progress screen. Where the denominator is zero the fraction is
 * dropped rather than printed as "0 of 0", which reads as a fault.
 */
function Tile({ label, value, of }: { label: string; value: number; of?: number }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-muted">{label}</dt>
      <dd className="mt-0.5 text-2xl font-semibold tabular-nums">
        {value}
        {of !== undefined && of > 0 && (
          <span className="ml-1 text-sm font-normal text-muted">of {of}</span>
        )}
      </dd>
    </div>
  )
}
