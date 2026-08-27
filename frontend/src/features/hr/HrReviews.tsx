import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { usePermittedReviews } from '../../api/reviews'
import { Pager, usePaging } from '../../components/Pager'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import { RowLink } from '../../components/RowLink'
import { hrIsWaitedOn } from '../manager/waiting'

/**
 * The reviews in HR's granted departments.
 *
 * The **same endpoint** the manager console and the employee console call. The rows differ
 * because the scope is a `WHERE` clause built from the caller's grants, resolved this request.
 * Three consoles, one query, no duplicated rule.
 *
 * `totalElements` therefore counts what this HR user may see, not what exists, which is why
 * the pager sends `page` to the server rather than slicing anything here.
 *
 * The caller's own row is absent, and stays absent (P-2.2).
 */
export function HrReviews() {
  const { cycles, cycleId, setCycleId } = useSelectedCycle()
  const { page, size, setPage } = usePaging()
  const { data: reviews, isPending, error } = usePermittedReviews(cycleId, page, size)

  return (
    <>
      <h1 className="mb-1 text-xl font-semibold tracking-tight">Reviews in my scope</h1>
      <p className="mb-4 text-sm text-muted">
        Everyone under review in the departments you hold grants for.
      </p>
      <CycleSelect cycles={cycles} cycleId={cycleId} onChange={setCycleId} />

      {cycleId === undefined ? null : isPending ? (
        <Loading />
      ) : error ? (
        <QueryFailure error={error} />
      ) : reviews.content.length === 0 ? (
        <EmptyState>
          Nobody in your granted departments is under review in this cycle.
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
                  <td className="p-3">{review.subjectName}</td>
                  <td className="p-3 text-muted">{review.departmentName ?? '-'}</td>
                  <td className="p-3 text-right">
                    {/*
                      A rating the manager has set and nobody has signed off. Until HR act, the
                      manager cannot share it (P-4.8), so this row is holding up somebody else's
                      work as well as their own.
                    */}
                    <RowLink
                      to={`/hr/reviews/${review.subjectId}?cycleId=${cycleId}`}
                      dot={hrIsWaitedOn(review) !== null}
                      dotLabel={hrIsWaitedOn(review) ?? undefined}
                    >
                      Open and calibrate
                    </RowLink>
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
