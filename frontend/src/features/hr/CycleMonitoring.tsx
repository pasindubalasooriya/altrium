import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { useCycleMonitoring } from '../../api/hr'
import { Card, Fact, when } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'

/**
 * Cycle progress across the departments HR have been granted (P-6.3).
 *
 * Counts only. That is not a simplification: it is the reason monitoring can be a separate
 * endpoint at all. A monitoring view that listed names and statuses would be the review list
 * again with a second implementation of the scoping rules, and the two would eventually
 * disagree. Anyone who needs the rows has the review list, scoped by the same rules.
 *
 * **The caller's own row is excluded from every figure**, because the server excludes it
 * (P-2.2). This screen must not add it back from `/api/me` for completeness: in a small
 * department, a `ratingsSet` that included the HR user would disclose that their own rating
 * had been set before it was shared with them.
 *
 * An open cycle showing zero participants is not an empty screen to be tidied away - it is the
 * visible form of a cycle configured wrongly, and the thing HR most need to notice.
 */
export function CycleMonitoring() {
  const { cycles, cycleId, setCycleId } = useSelectedCycle()
  const { data, isPending, error } = useCycleMonitoring(cycleId)

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Cycle monitoring</h1>
      <CycleSelect cycles={cycles} cycleId={cycleId} onChange={setCycleId} />

      {cycleId === undefined ? null : isPending ? (
        <Loading />
      ) : error ? (
        <QueryFailure error={error} />
      ) : (
        <div className="grid gap-4">
          <Card>
            <Fact label="Cycle">{data.cycle.label}</Fact>
            <Fact label="Status">{data.cycle.status.toLowerCase()}</Fact>
            <Fact label="Runs">
              {when(data.cycle.startDate)} to {when(data.cycle.endDate)}
            </Fact>
            <Fact label="Opened">
              {data.cycle.openedAt ? when(data.cycle.openedAt) : 'not yet'}
            </Fact>
          </Card>

          {data.departments.length === 0 ? (
            <EmptyState>
              {data.cycle.openedAt
                ? 'No participants in the departments you hold grants for. If you expected people here, the cohort configuration for this quadrimester may be wrong.'
                : 'This cycle has not opened yet, so nobody is under review in it.'}
            </EmptyState>
          ) : (
            <div className="overflow-x-auto rounded border border-line bg-white">
              <table className="w-full text-sm">
                <thead className="border-b border-line text-left text-muted">
                  <tr>
                    <th className="p-3 font-medium">Department</th>
                    <th className="p-3 font-medium">Under review</th>
                    <th className="p-3 font-medium">Self-reviews</th>
                    <th className="p-3 font-medium">Manager reviews</th>
                    <th className="p-3 font-medium">Peer reviews</th>
                    <th className="p-3 font-medium">Ratings set</th>
                    <th className="p-3 font-medium">Shared</th>
                  </tr>
                </thead>
                <tbody>
                  {data.departments.map((department) => (
                    <tr
                      key={department.departmentId}
                      className="border-b border-line/60 last:border-0"
                    >
                      <td className="p-3">{department.departmentName}</td>
                      <td className="p-3">{department.participants}</td>
                      <td className="p-3">
                        {department.selfReviewsSubmitted} / {department.participants}
                      </td>
                      <td className="p-3">
                        {department.managerReviewsSubmitted} / {department.participants}
                      </td>
                      <td className="p-3">
                        {department.peerReviewsSubmitted} / {department.peerAssignmentsMade}
                      </td>
                      <td className="p-3">
                        {department.ratingsSet} / {department.participants}
                      </td>
                      <td className="p-3">{department.ratingsReleased}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </>
  )
}
