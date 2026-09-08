import { useHistory } from '../../api/history'
import { Card } from '../../components/Form'
import { QueryFailure } from '../../components/States'
import { Timeline } from './Timeline'

/**
 * What happened to this person before the cycle on screen (P-4.9).
 *
 * Sits on the manager's review screen and on HR's calibration screen, which is the point of
 * the feature rather than a convenience: "so that I am not starting from scratch each time"
 * means the previous outcome has to be in front of the person writing this one, not a tab away.
 *
 * `excludeCycleId` drops the cycle already being read, and is **cosmetic**. It removes a
 * duplicate of the page it sits on, not a row the caller may not see - every row here has
 * already been scoped by the server, and dropping one in the client neither adds nor removes
 * any permission. Nothing about this filter is load-bearing, which is why it is safe here and
 * would not be safe on a list whose count meant anything.
 *
 * Renders nothing at all while loading, and nothing when the person has no earlier cycles.
 * A card headed "Previous cycles" saying "none" is noise on a screen whose real subject is
 * this cycle.
 */
export function PreviousCycles({ userId, excludeCycleId }: {
  userId: number
  excludeCycleId?: number
}) {
  const { data, isPending, error } = useHistory(userId)

  if (isPending) {
    return null
  }

  // Shown rather than swallowed. A caller who may read this person's review may read their
  // history too - the two share a capability - so a failure here is a real fault, not a
  // permission working as intended.
  if (error) {
    return (
      <Card title="Previous cycles">
        <QueryFailure error={error} />
      </Card>
    )
  }

  const earlier = data.filter((entry) => entry.cycleId !== excludeCycleId)
  if (earlier.length === 0) {
    return null
  }

  return (
    <Card title="Previous cycles">
      <Timeline entries={earlier} emptyMessage="" />
    </Card>
  )
}
