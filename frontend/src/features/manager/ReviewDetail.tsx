import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  useReleaseRating,
  useReviewRecord,
  useSaveManagerReview,
  useSetRating,
} from '../../api/reviews'
import { Button, Card, Fact, Field, Select, TextArea, WriteFailure, when } from '../../components/Form'
import { Loading, QueryFailure } from '../../components/States'
import { RATINGS, RATING_LABELS, type Rating, type ReviewRecord, type Section } from '../../api/types'
import { PeerPicker } from './PeerPicker'

/**
 * One reviewee's record, as their manager.
 *
 * Everything on the page is driven by `visibleSections`. This screen is also reached by HR
 * from their own console, and it renders correctly for both without asking who the caller is,
 * because the server has already decided what they may see and said so.
 */
export function ReviewDetail() {
  const { subjectId } = useParams()
  const [params] = useSearchParams()
  const id = Number(subjectId)
  const cycleId = Number(params.get('cycleId'))

  const { data: record, isPending, error } = useReviewRecord(id, cycleId || undefined)

  if (!cycleId) {
    return <QueryFailure error={new Error('no cycle')} />
  }
  if (isPending) {
    return <Loading />
  }
  if (error) {
    return <QueryFailure error={error} />
  }

  const visible = (section: Section) => record.visibleSections.includes(section)

  return (
    <>
      <h1 className="text-xl font-semibold tracking-tight">{record.summary.subjectName}</h1>
      <p className="mb-6 text-sm text-muted">
        {record.summary.cycleLabel} · {record.summary.departmentName ?? 'no department'} ·{' '}
        <Link className="text-accent" to={`/manager/plans/${id}`}>
          Development and improvement plans
        </Link>
      </p>

      <div className="grid gap-4">
        <Card title="Self-review">
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
            <Unavailable visible={visible('SELF_REVIEW')} what="self-review" />
          )}
        </Card>

        <PeerFeedback record={record} visible={visible('PEER_REVIEWS')} />

        <PeerPicker subjectId={id} cycleId={cycleId} />

        <ManagerReviewForm record={record} subjectId={id} cycleId={cycleId} />

        <RatingCard record={record} subjectId={id} cycleId={cycleId} />
      </div>
    </>
  )
}

/**
 * Peer feedback, with author names.
 *
 * The manager and HR-in-scope see who wrote what (P-3.2); the subject sees none of it, ever,
 * and reaches this page through no route at all. That asymmetry is enforced on the server -
 * `READ_PEER_REVIEW` has no `SELF` ground - and this section simply renders what arrived.
 */
function PeerFeedback({ record, visible }: { record: ReviewRecord; visible: boolean }) {
  return (
    <Card title="Peer feedback">
      {visible && record.peerReviews?.length ? (
        <ul className="grid gap-3 text-sm">
          {record.peerReviews.map((peer) => (
            <li key={peer.peerId} className="rounded border border-line p-3">
              <p className="text-xs text-muted">
                {peer.peerName}
                {peer.rating ? ` · ${RATING_LABELS[peer.rating]}` : ''}
                {peer.submittedAt ? ` · ${when(peer.submittedAt)}` : ' · not submitted'}
              </p>
              {peer.feedback && <p className="mt-1 whitespace-pre-wrap">{peer.feedback}</p>}
            </li>
          ))}
        </ul>
      ) : (
        <Unavailable visible={visible} what="peer feedback" />
      )}
    </Card>
  )
}

function ManagerReviewForm({
  record,
  subjectId,
  cycleId,
}: {
  record: ReviewRecord
  subjectId: number
  cycleId: number
}) {
  const save = useSaveManagerReview(subjectId, cycleId)
  const existing = record.managerReview
  const submitted = Boolean(existing?.submittedAt)
  const [feedback, setFeedback] = useState('')

  useEffect(() => {
    setFeedback(existing?.feedback ?? '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subjectId, cycleId, submitted])

  return (
    <Card title="Your review">
      {submitted && (
        <p className="mb-4 rounded bg-line/40 p-3 text-sm">
          Submitted {when(existing?.submittedAt)}. This is what the employee reads once their
          rating is shared with them.
        </p>
      )}
      <div className="grid gap-3">
        <Field label="Feedback">
          <TextArea
            value={feedback}
            disabled={submitted}
            onChange={(e) => setFeedback(e.target.value)}
          />
        </Field>
        <WriteFailure error={save.error} />
        {!submitted && (
          <div className="flex gap-3">
            <Button
              busy={save.isPending}
              busyLabel="Saving"
              onClick={() => save.mutate({ feedback, submit: false })}
            >
              Save draft
            </Button>
            <Button
              variant="primary"
              busy={save.isPending}
              busyLabel="Submitting"
              onClick={() => save.mutate({ feedback, submit: true })}
            >
              Submit
            </Button>
          </div>
        )}
      </div>
    </Card>
  )
}

/**
 * Setting and releasing the final rating.
 *
 * **No average, no suggestion, no computed hint anywhere on this card.** The peer ratings are
 * above, as input to a judgement; P-4.1 requires the final rating to be *chosen*, and a "peer
 * average: 2.5" here would make that false in practice while remaining true in the database.
 * The scale is an enum with no numeric weight for exactly the same reason.
 *
 * Releasing is what opens the employee's view of it, and is irreversible, so the button says
 * what it does rather than being labelled "save".
 */
function RatingCard({
  record,
  subjectId,
  cycleId,
}: {
  record: ReviewRecord
  subjectId: number
  cycleId: number
}) {
  const set = useSetRating(subjectId, cycleId)
  const release = useReleaseRating(subjectId, cycleId)
  const current = record.finalRating
  const [rating, setRating] = useState<Rating>(current?.rating ?? 'MEETS_EXPECTATIONS')

  const released = Boolean(current?.releasedAt)

  return (
    <Card title="Final rating">
      {current && (
        <div className="mb-4">
          <Fact label="Current">{RATING_LABELS[current.rating]}</Fact>
          <Fact label="Set">{when(current.setAt)}</Fact>
          <Fact label="Shared with the employee">
            {released ? when(current.releasedAt) : 'not yet'}
          </Fact>
        </div>
      )}

      <div className="grid gap-3">
        <Field label="Rating" hint="Your judgement. Nothing here is calculated from the peer ratings.">
          <Select
            value={rating}
            disabled={released}
            onChange={(e) => setRating(e.target.value as Rating)}
          >
            {RATINGS.map((value) => (
              <option key={value} value={value}>
                {RATING_LABELS[value]}
              </option>
            ))}
          </Select>
        </Field>

        {/*
          Once HR have calibrated, or the rating has been shared, the server returns 409. That
          is a state refusing a write, not a permission being denied, and it renders here.
        */}
        <WriteFailure error={set.error ?? release.error} />

        <div className="flex flex-wrap items-center gap-3">
          <Button
            variant="primary"
            disabled={released}
            busy={set.isPending}
            busyLabel="Setting"
            onClick={() => set.mutate(rating)}
          >
            Set rating
          </Button>
          {current && !released && (
            <Button
              busy={release.isPending}
              busyLabel="Sharing"
              onClick={() => release.mutate()}
            >
              Share with the employee
            </Button>
          )}
          {!released && (
            <span className="text-xs text-muted">
              Sharing cannot be undone, and is normally done after the HR normalisation meeting.
            </span>
          )}
        </div>
      </div>
    </Card>
  )
}

/**
 * Says which of the two reasons a section is empty, using what the server told us.
 *
 * Guessing from a null would eventually show "nobody has written this yet" to somebody who was
 * simply not permitted to see it, which is a claim about the record rather than about them.
 */
function Unavailable({ visible, what }: { visible: boolean; what: string }) {
  return (
    <p className="text-sm text-muted">
      {visible ? `No ${what} has been written yet.` : `You do not have access to the ${what}.`}
    </p>
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
