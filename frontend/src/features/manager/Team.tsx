import { Link } from 'react-router-dom'
import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { usePermittedReviews } from '../../api/reviews'
import { useCurrentUser } from '../../auth/useCurrentUser'
import { Pager, usePaging } from '../../components/Pager'
import { EmptyState, Loading, QueryFailure } from '../../components/States'

/**
 * The manager's team for a cycle.
 *
 * This calls **the same endpoint** the employee console calls for their own record list. It
 * returns different rows because the scope is in the SQL `WHERE` clause - the caller's report
 * ids for a manager, their granted departments for HR - not because the client asked
 * differently. There is no `?scope=team` parameter and there could not usefully be one.
 *
 * That also means `totalElements` counts the caller's team rather than the organisation, which
 * is why this pages on the server and never slices an array.
 *
 * The rows carry progress, never content. A manager scanning the list sees who is under
 * review; they open the record to read anything at all.
 */
export function Team() {
  const { data: me } = useCurrentUser()
  const { cycles, cycleId, setCycleId } = useSelectedCycle()
  const { page, size, setPage } = usePaging()
  const { data: reviews, isPending, error } = usePermittedReviews(cycleId, page, size)

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">My team</h1>
      <CycleSelect cycles={cycles} cycleId={cycleId} onChange={setCycleId} />

      {cycleId === undefined ? null : isPending ? (
        <Loading />
      ) : error ? (
        <QueryFailure error={error} />
      ) : reviews.content.length === 0 ? (
        <EmptyState>
          Nobody you manage is under review in this cycle. People are reviewed in the
          quadrimester their cohort is assigned to.
        </EmptyState>
      ) : (
        <div className="rounded border border-line bg-white">
          <table className="w-full text-sm">
            <thead className="border-b border-line text-left text-muted">
              <tr>
                <th className="p-3 font-medium">Name</th>
                <th className="p-3 font-medium">Department</th>
                <th className="p-3 font-medium"> </th>
              </tr>
            </thead>
            <tbody>
              {reviews.content.map((review) => (
                <tr key={review.subjectId} className="border-b border-line/60 last:border-0">
                  <td className="p-3">
                    {review.subjectName}
                    {/*
                      The caller's own row appears in this list when they are themselves under
                      review - they are the subject, and SELF is a ground on the summary. It is
                      marked rather than removed, because their own record is genuinely theirs
                      to read.
                    */}
                    {review.subjectId === me?.id && (
                      <span className="ml-2 text-xs text-muted">(you)</span>
                    )}
                    {!review.subjectActive && (
                      <span className="ml-2 text-xs text-warn">deactivated</span>
                    )}
                  </td>
                  <td className="p-3 text-muted">{review.departmentName ?? '-'}</td>
                  <td className="p-3 text-right">
                    <Link
                      className="text-accent"
                      to={`/manager/reviews/${review.subjectId}?cycleId=${cycleId}`}
                    >
                      Open review
                    </Link>
                    <Link className="ml-4 text-accent" to={`/manager/plans/${review.subjectId}`}>
                      Plan
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager
            page={reviews.number}
            totalPages={reviews.totalPages}
            totalElements={reviews.totalElements}
            onPage={setPage}
          />
        </div>
      )}
    </>
  )
}
