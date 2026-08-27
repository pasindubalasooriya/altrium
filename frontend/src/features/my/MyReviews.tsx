import { Link } from 'react-router-dom'
import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { useMyReviewRecord } from '../../api/reviews'
import { useCurrentUser } from '../../auth/useCurrentUser'
import { Card, Fact, when } from '../../components/Form'
import { Loading, NotUnderReview, QueryFailure } from '../../components/States'
import { isNotFound } from '../../api/errors'
import type { OwnReviewRecord, Section } from '../../api/types'
import { RATING_LABELS } from '../../api/types'

/**
 * The employee's own record for a cycle.
 *
 * Rendered from `visibleSections`, which the server sends precisely so a client does not have
 * to guess. A null section could mean "you may not see this" or "nobody has written it yet",
 * and those are different sentences to show somebody.
 *
 * **Nothing on this page concerns peers.** Not their feedback, not their names, not how many
 * there are, not how many have submitted. The type this page receives has no field for any of
 * it, so there is no code path here that could render it.
 */
export function MyReviews() {
  const { data: me } = useCurrentUser()
  const { cycles, cycleId, setCycleId, error: cycleError } = useSelectedCycle()
  const { data: record, isPending, error } = useMyReviewRecord(cycleId, me?.id)

  if (cycleError) {
    return <QueryFailure error={cycleError} />
  }

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">My review</h1>
      <CycleSelect cycles={cycles} cycleId={cycleId} onChange={setCycleId} />

      {cycleId === undefined ? null : isPending ? (
        <Loading />
      ) : isNotFound(error) ? (
        // Not a participant in this cycle. The access decision passed - this is the caller's
        // own record - and it is the record that is absent, so this is an ordinary state and
        // not a denial. It used to render as a failed request.
        <NotUnderReview />
      ) : error ? (
        <QueryFailure error={error} />
      ) : (
        <Record record={record} />
      )}
    </>
  )
}

function Record({ record }: { record: OwnReviewRecord }) {
  const visible = (section: Section) => record.visibleSections.includes(section)

  return (
    <div className="grid gap-4">
      <Card>
        <Fact label="Cycle">{record.summary.cycleLabel}</Fact>
        <Fact label="Department">{record.summary.departmentName ?? '-'}</Fact>
      </Card>

      <Card title="Your self-review">
        {visible('SELF_REVIEW') && record.selfReview ? (
          <div className="grid gap-3 text-sm">
            <Written label="Achievements" text={record.selfReview.achievements} />
            <Written label="Challenges" text={record.selfReview.challenges} />
            <Written label="Goals" text={record.selfReview.goals} />
            <p className="text-muted">
              {record.selfReview.submittedAt
                ? `Submitted ${when(record.selfReview.submittedAt)}`
                : 'Draft, not yet submitted'}
            </p>
          </div>
        ) : (
          <p className="text-sm text-muted">
            You have not written your self-review yet.{' '}
            <Link className="text-accent underline" to="/my/self-review">
              Write it now
            </Link>
            .
          </p>
        )}
      </Card>

      <Card title="Your manager's feedback">
        {visible('MANAGER_REVIEW') && record.managerReview?.submittedAt ? (
          <div className="grid gap-2 text-sm">
            <Written label={record.managerReview.managerName} text={record.managerReview.feedback} />
            <p className="text-muted">Submitted {when(record.managerReview.submittedAt)}</p>
          </div>
        ) : (
          <p className="text-sm text-muted">
            Your manager has not shared their review with you yet.
          </p>
        )}
      </Card>

      <Card title="Your rating">
        {visible('FINAL_RATING') && record.finalRating?.releasedAt ? (
          <p className="text-sm">
            <strong>{RATING_LABELS[record.finalRating.rating]}</strong>
            <span className="text-muted"> · shared {when(record.finalRating.releasedAt)}</span>
          </p>
        ) : (
          // Identical whether no rating exists or one exists unreleased. Distinguishing them
          // would disclose that a rating had been set, which is what release exists to control.
          <p className="text-sm text-muted">No rating has been shared with you for this cycle.</p>
        )}
      </Card>
    </div>
  )
}

function Written({ label, text }: { label: string; text: string | null }) {
  if (!text) {
    return null
  }
  return (
    <div>
      <p className="text-xs font-medium text-muted">{label}</p>
      <p className="whitespace-pre-wrap">{text}</p>
    </div>
  )
}
