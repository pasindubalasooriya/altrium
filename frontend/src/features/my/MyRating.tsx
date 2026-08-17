import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { useMyRating } from '../../api/reviews'
import { Card, when } from '../../components/Form'
import { Loading, QueryFailure } from '../../components/States'
import { RATING_LABELS } from '../../api/types'

/**
 * The employee's own rating and their manager's feedback (P-4.4).
 *
 * **"No rating yet" and "rating withheld" render identically here**, and that is the whole
 * point of the endpoint's shape. `OwnRatingView` was built so the two cases are
 * indistinguishable on the wire; a screen that said "awaiting release" for one and "not yet
 * rated" for the other would put back exactly the disclosure the DTO removed - that a rating
 * exists and is being held back.
 *
 * There is no calibration trail on this page either. `READ_RATING_AUDIT` has no `SELF` ground:
 * "your manager said Meets, HR moved it to Exceeds" is neither the rating nor the manager's
 * feedback, and handing it over would undermine the manager in the conversation they have to
 * hold.
 */
export function MyRating() {
  const { cycles, cycleId, setCycleId } = useSelectedCycle()
  const { data: own, isPending, error } = useMyRating(cycleId)

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">My rating</h1>
      <CycleSelect cycles={cycles} cycleId={cycleId} onChange={setCycleId} />

      {cycleId === undefined ? null : isPending ? (
        <Loading />
      ) : error ? (
        <QueryFailure error={error} />
      ) : own.released && own.rating ? (
        <Card>
          <p className="text-lg font-semibold">{RATING_LABELS[own.rating]}</p>
          <p className="mb-4 text-sm text-muted">Shared with you {when(own.releasedAt)}</p>
          {own.managerFeedback && (
            <>
              <p className="text-xs font-medium text-muted">Your manager’s feedback</p>
              <p className="whitespace-pre-wrap text-sm">{own.managerFeedback}</p>
            </>
          )}
        </Card>
      ) : (
        <Card>
          <p className="text-sm text-muted">
            No rating has been shared with you for this cycle. Your manager and HR will discuss
            it with you when it is ready.
          </p>
        </Card>
      )}
    </>
  )
}
