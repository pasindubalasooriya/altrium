import { useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { useCalibrationHistory, useReviewRecord } from '../../api/reviews'
import { useApproveRating, useCalibrate } from '../../api/hr'
import {
  Button,
  Card,
  Fact,
  Field,
  Select,
  TextArea,
  WriteFailure,
  when,
} from '../../components/Form'
import { Loading, QueryFailure } from '../../components/States'
import { RATINGS, RATING_LABELS, type Rating, type Section } from '../../api/types'
import { PreviousCycles } from '../history/PreviousCycles'
import { ScheduleMeeting } from '../meetings/ScheduleMeeting'

/**
 * One person's record as HR see it, with the calibration controls.
 *
 * HR read the same sections a manager reads - self-review, peer feedback with author names,
 * manager review, rating - because `HR_IN_SCOPE` is a ground on all four. What differs is what
 * they may write: they normalise the rating, and they do not touch the reviews.
 *
 * There is **no development-plan write on this page**. HR read a PDP and never write one
 * (P-5.1), and a screen that offered an approve button would be offering something the server
 * refuses.
 */
export function Calibration() {
  const { subjectId } = useParams()
  const [params] = useSearchParams()
  const id = Number(subjectId)
  const cycleId = Number(params.get('cycleId'))

  const { data: record, isPending, error } = useReviewRecord(id, cycleId || undefined)
  const history = useCalibrationHistory(id, cycleId || undefined)
  const calibrate = useCalibrate(id, cycleId || undefined)
  const approve = useApproveRating(id, cycleId || undefined)

  const [rating, setRating] = useState<Rating>('MEETS_EXPECTATIONS')
  const [note, setNote] = useState('')

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
  const current = record.finalRating

  return (
    <>
      <h1 className="text-xl font-semibold tracking-tight">{record.summary.subjectName}</h1>
      <p className="mb-6 text-sm text-muted">
        {record.summary.cycleLabel} · {record.summary.departmentName ?? 'no department'}
      </p>

      <div className="grid gap-4">
        {/*
          Calibration is a judgment about whether this rating sits right against the others, and
          a person's own previous ratings are part of that comparison (scenario section 11).
        */}
        <PreviousCycles userId={id} excludeCycleId={cycleId} />

        <Card title="Self-review">
          {visible('SELF_REVIEW') && record.selfReview ? (
            <div className="grid gap-3 text-sm">
              <Written label="Achievements" text={record.selfReview.achievements} />
              <Written label="Challenges" text={record.selfReview.challenges} />
              <Written label="Goals" text={record.selfReview.goals} />
            </div>
          ) : (
            <Unavailable visible={visible('SELF_REVIEW')} what="self-review" />
          )}
        </Card>

        <Card title="Peer feedback">
          {visible('PEER_REVIEWS') && record.peerReviews?.length ? (
            <ul className="grid gap-3 text-sm">
              {record.peerReviews.map((peer) => (
                <li key={peer.peerId} className="rounded border border-line p-3">
                  <p className="text-xs text-muted">
                    {/* HR see who wrote what (P-3.2). The subject never does (P-3.3). */}
                    {peer.peerName}
                    {peer.rating ? ` · ${RATING_LABELS[peer.rating]}` : ''}
                  </p>
                  {peer.feedback && <p className="mt-1 whitespace-pre-wrap">{peer.feedback}</p>}
                </li>
              ))}
            </ul>
          ) : (
            <Unavailable visible={visible('PEER_REVIEWS')} what="peer feedback" />
          )}
        </Card>

        <Card title="Manager review">
          {visible('MANAGER_REVIEW') && record.managerReview ? (
            <div className="text-sm">
              <p className="text-xs text-muted">{record.managerReview.managerName}</p>
              <p className="mt-1 whitespace-pre-wrap">{record.managerReview.feedback}</p>
            </div>
          ) : (
            <Unavailable visible={visible('MANAGER_REVIEW')} what="manager review" />
          )}
        </Card>

        <Card title="Calibration">
          {current ? (
            <>
              <Fact label="Current rating">{RATING_LABELS[current.rating]}</Fact>
              <Fact label="Shared with the employee">
                {current.releasedAt ? when(current.releasedAt) : 'not yet'}
              </Fact>

              <div className="mt-4 grid gap-3">
                <Field label="Normalised rating">
                  <Select
                    value={rating}
                    disabled={Boolean(current.releasedAt)}
                    onChange={(e) => setRating(e.target.value as Rating)}
                  >
                    {RATINGS.map((value) => (
                      <option key={value} value={value}>
                        {RATING_LABELS[value]}
                      </option>
                    ))}
                  </Select>
                </Field>
                <Field label="Note">
                  <TextArea value={note} onChange={(e) => setNote(e.target.value)} />
                </Field>

                {/*
                  Two failures land here and neither is a denial. Recalibrating to the value it
                  already holds is 400 - there is nothing to record - and calibrating after the
                  rating has been shared is 409. Both are the state of the record answering, and
                  both render as messages on this form.
                */}
                <WriteFailure error={calibrate.error ?? approve.error} />

                {/*
                  Two ways to sign the same rating off, and the trail records which. Approving
                  is not a courtesy button: until HR do one or the other, the manager cannot
                  share the rating at all (P-4.8), so this is the step the employee is waiting
                  on when nothing appears to be happening.
                */}
                <div className="flex flex-wrap items-center gap-3">
                  <Button
                    variant="primary"
                    disabled={Boolean(current.releasedAt)}
                    busy={calibrate.isPending}
                    busyLabel="Calibrating"
                    onClick={() => calibrate.mutate({ rating, note })}
                  >
                    Calibrate
                  </Button>
                  {!current.releasedAt && !current.signedOffByHr && (
                    <Button
                      busy={approve.isPending}
                      busyLabel="Approving"
                      onClick={() => approve.mutate(note)}
                    >
                      Approve as set
                    </Button>
                  )}
                  {current.releasedAt && (
                    <p className="mt-2 text-xs text-muted">
                      This rating has been shared with the employee and can no longer be
                      changed.
                    </p>
                  )}
                </div>
              </div>
            </>
          ) : (
            <p className="text-sm text-muted">
              The manager has not set a rating yet. There is nothing to normalise.
            </p>
          )}
        </Card>

        {/*
          The normalization meeting (scenario section 5 step 5), beside the calibration it
          exists to hold. The invitation goes to this employee's manager, and the same grant
          that admits the calibration below admits the meeting - which is why an HR user
          looking at their own record is refused both.
        */}
        <ScheduleMeeting
          type="NORMALIZATION_MEETING"
          subjectId={id}
          subjectName={record.summary.subjectName}
        />

        <Card title="Calibration history">
          {history.data?.length ? (
            <ul className="grid gap-2 text-sm">
              {history.data.map((row, index) => (
                <li key={index} className="rounded border border-line p-3">
                  <p>
                    {RATING_LABELS[row.from]} → {RATING_LABELS[row.to]}
                  </p>
                  <p className="text-xs text-muted">
                    {row.by} · {when(row.at)}
                  </p>
                  {row.note && <p className="mt-1 whitespace-pre-wrap">{row.note}</p>}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted">
              {/* The trail is append-only; an empty one means nothing has been changed. */}
              This rating has not been changed.
            </p>
          )}
        </Card>
      </div>
    </>
  )
}

function Unavailable({ visible, what }: { visible: boolean; what: string }) {
  return (
    <p className="text-sm text-muted">
      {visible ? `No ${what} has been submitted yet.` : `You do not have access to the ${what}.`}
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
