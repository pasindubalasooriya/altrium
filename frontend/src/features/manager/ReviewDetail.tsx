import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  useCalibrationHistory,
  useReleaseRating,
  useReviewRecord,
  useSaveManagerReview,
  useSetRating,
} from '../../api/reviews'
import { Button, Card, Fact, Field, Select, TextArea, WriteFailure, when } from '../../components/Form'
import { Loading, QueryFailure } from '../../components/States'
import {
  RATINGS,
  RATING_LABELS,
  type Calibration,
  type Rating,
  type ReviewRecord,
  type Section,
} from '../../api/types'
import { PeerPicker } from './PeerPicker'
import { useCurrentUser } from '../../auth/useCurrentUser'
import { markPeerFeedbackSeen } from './peerSeen'

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
  const { data: me } = useCurrentUser()

  // Opening the record is what counts as having seen the peer feedback, so the unread dot on
  // the team list clears here rather than on a click. Before the early returns below, because
  // a hook cannot sit after one.
  const peersVisible = record?.visibleSections.includes('PEER_REVIEWS') ?? false
  const submittedPeerReviews =
    record?.peerReviews?.filter((peer) => peer.submittedAt).length ?? 0
  useEffect(() => {
    if (peersVisible && cycleId) {
      markPeerFeedbackSeen(cycleId, id, submittedPeerReviews)
    }
  }, [peersVisible, submittedPeerReviews, cycleId, id])

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

  // This screen is reachable by somebody who is not this person's manager. The review list is
  // scoped by *all* of the caller's grounds, so an HR user who also manages a team sees their
  // granted departments in the same table as their own reports, and both rows link here.
  //
  // The server already refuses their writes. What it cannot fix is the wording: the card below
  // was titled "Your review" unconditionally, so an HR user opening a report of somebody else's
  // read that manager's assessment under a heading claiming it as their own.
  const isTheirManager = me?.id !== undefined && record.summary.managerId === me.id

  return (
    <>
      <h1 className="text-xl font-semibold tracking-tight">{record.summary.subjectName}</h1>
      <p className="mb-6 text-sm text-muted">
        {record.summary.cycleLabel} · {record.summary.departmentName ?? 'no department'} ·{' '}
        <Link className="text-accent underline" to={`/manager/plans/${id}`}>
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

        {/*
          Once both peers have written, this card says nothing the peer feedback above does not
          already say - the same two names, with their words against them. It stays while the
          pair is being chosen and while one of them is outstanding, which is when the manager
          needs to know who has not written yet and who to swap.
        */}
        {isTheirManager && !peerProgress(record, visible('PEER_REVIEWS')).ready && (
          <PeerPicker subjectId={id} cycleId={cycleId} peerReviews={record.peerReviews} />
        )}

        <ManagerReviewForm
          record={record}
          subjectId={id}
          cycleId={cycleId}
          peers={peerProgress(record, visible('PEER_REVIEWS'))}
          isTheirManager={isTheirManager}
        />

        <RatingCard
          record={record}
          subjectId={id}
          cycleId={cycleId}
          peers={peerProgress(record, visible('PEER_REVIEWS'))}
          isTheirManager={isTheirManager}
        />
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

/** Whether the caller was given the manager-review section at all, for the read-only card. */
function peersVisibleTo(record: ReviewRecord): boolean {
  return record.visibleSections.includes('MANAGER_REVIEW')
}

/** Both peers, from scenario section 5 and matching what `ASSIGN_PEERS` requires. */
const REQUIRED_PEERS = 2

type PeerProgress = { ready: boolean }

/**
 * How far the peer stream has got, as this screen understands it.
 *
 * The server is what refuses the write - `PeerFeedbackGate` returns 409 and it renders inline
 * if this disagrees. This exists so the manager is told what they are waiting for instead of
 * meeting a locked form with no explanation.
 *
 * When the caller has no grounds for the peer section, `ready` is true and nothing is blocked
 * here. Guessing "nothing submitted" from an absent section would lock a screen on the
 * strength of a permission the caller does not have, which is the wrong way round: the client
 * renders what it was told and never infers a refusal.
 */
function peerProgress(record: ReviewRecord, peersVisible: boolean): PeerProgress {
  if (!peersVisible) {
    return { ready: true }
  }
  const submitted = record.peerReviews?.filter((peer) => peer.submittedAt).length ?? 0
  return { ready: submitted >= REQUIRED_PEERS }
}

/**
 * What HR did to this rating.
 *
 * The calibrated value replaces the manager's in place, so without this the manager opened the
 * record and read a number they were told was theirs. The trail is the only thing that says
 * otherwise - and the note is where HR explain why, which is the half a manager actually needs
 * before they sit down with the person.
 *
 * An approval and an adjustment are one table and read differently here. A row whose two values
 * are equal is HR agreeing, and rendering it as "Meets to Meets" would be true and useless.
 *
 * Never rendered for the subject: `READ_RATING_AUDIT` has no `SELF` ground, so this query is
 * refused for them and there is no screen of theirs that calls it (P-4.7).
 */
function CalibrationTrail({ rows }: { rows: Calibration[] | undefined }) {
  if (!rows?.length) {
    return null
  }
  return (
    <div className="mb-4 grid gap-2">
      {rows.map((row, index) => (
        <div key={index} className="rounded border border-line p-3 text-sm">
          <p>
            {row.from === row.to ? (
              <>
                Approved as set by <span className="font-medium">{row.by}</span>
              </>
            ) : (
              <>
                <span className="font-medium">{row.by}</span> changed this from{' '}
                {RATING_LABELS[row.from]} to{' '}
                <span className="font-medium">{RATING_LABELS[row.to]}</span>
              </>
            )}
            <span className="text-muted"> · {when(row.at)}</span>
          </p>
          {row.note && <p className="mt-1 whitespace-pre-wrap">{row.note}</p>}
        </div>
      ))}
    </div>
  )
}

/**
 * Says the box is closed, and why, in one line.
 *
 * Deliberately carries no count. How far the peer stream has got is a number the manager may
 * have - the capability behind this screen has `DIRECT_MANAGER` as its only grounds, so the
 * caller is never the subject - but it is already on the peer reviewer list above, and saying
 * it twice made the card read as an incident report rather than a closed control.
 */
function WaitingForPeers() {
  return <p className="text-sm text-muted">Disabled until both peer reviews are in.</p>
}

function ManagerReviewForm({
  record,
  subjectId,
  cycleId,
  peers,
  isTheirManager,
}: {
  record: ReviewRecord
  subjectId: number
  cycleId: number
  peers: PeerProgress
  isTheirManager: boolean
}) {
  const save = useSaveManagerReview(subjectId, cycleId)
  const existing = record.managerReview
  const submitted = Boolean(existing?.submittedAt)
  const [feedback, setFeedback] = useState('')

  useEffect(() => {
    setFeedback(existing?.feedback ?? '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subjectId, cycleId, submitted])

  // Nothing may be written before both peers have submitted, drafts included - the whole point
  // of the rule is that the assessment is formed in light of the peer feedback, and a draft
  // written first would defeat it while keeping to the timing. An already-submitted review is
  // still shown: the gate governs writing, not reading back what was written.
  // Somebody else's assessment, read by an HR user in scope. Read-only, and titled as what it
  // is - not "your review", which claimed authorship of another manager's words.
  if (!isTheirManager) {
    return (
      <Card title="Manager review">
        {existing?.feedback ? (
          <div className="text-sm">
            <p className="text-xs text-muted">{existing.managerName}</p>
            <p className="mt-1 whitespace-pre-wrap">{existing.feedback}</p>
          </div>
        ) : (
          <Unavailable visible={peersVisibleTo(record)} what="manager review" />
        )}
      </Card>
    )
  }

  if (!peers.ready && !submitted) {
    return (
      <Card title="Your review">
        <WaitingForPeers />
      </Card>
    )
  }

  return (
    <Card title="Your review">
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
  peers,
  isTheirManager,
}: {
  record: ReviewRecord
  subjectId: number
  cycleId: number
  peers: PeerProgress
  isTheirManager: boolean
}) {
  const set = useSetRating(subjectId, cycleId)
  const release = useReleaseRating(subjectId, cycleId)
  // What HR did to this rating, and why. `READ_RATING_AUDIT` carries `DIRECT_MANAGER`, so the
  // manager has always been entitled to this - the screen simply never asked for it, and the
  // calibrated value arrived looking like the one they had chosen themselves.
  const history = useCalibrationHistory(subjectId, cycleId)
  const current = record.finalRating
  const [rating, setRating] = useState<Rating>(current?.rating ?? 'MEETS_EXPECTATIONS')

  const released = Boolean(current?.releasedAt)

  // Setting and sharing are the manager's, both of them (P-4.1, P-4.6 gives HR release too, but
  // an HR user who wants to act on a rating has their own console for it, where calibrating and
  // approving live). Here they read.
  if (!isTheirManager) {
    // `signedOffByHr` is null unless the caller may read the calibration trail, and this branch
    // has already established they are not the manager - so a value here means HR-in-scope.
    // They have somewhere to act; this screen is not it, and saying nothing left them looking
    // for a control that was never on this page.
    const canCalibrate = current !== null && current.signedOffByHr !== null

    return (
      <Card title="Final rating">
        {current ? (
          <>
            <Fact label="Current">{RATING_LABELS[current.rating]}</Fact>
            <Fact label="Set">{when(current.setAt)}</Fact>
            <Fact label="Shared with the employee">
              {current.releasedAt ? when(current.releasedAt) : 'not yet'}
            </Fact>
          </>
        ) : (
          <p className="text-sm text-muted">No rating has been set yet.</p>
        )}

        <CalibrationTrail rows={history.data} />

        {canCalibrate && !current.releasedAt && (
          <p className="mt-3 text-sm">
            <Link
              className="text-accent underline"
              to={`/hr/reviews/${subjectId}?cycleId=${cycleId}`}
            >
              {current.signedOffByHr ? 'Open the calibration record' : 'Calibrate or approve this rating'}
            </Link>
          </p>
        )}
      </Card>
    )
  }

  // A rating that already exists cleared the same gate on the way in, so the card stays usable
  // for sharing it even in the unlikely event the peer stream is read differently afterwards.
  if (!peers.ready && !current) {
    return (
      <Card title="Final rating">
        <WaitingForPeers />
      </Card>
    )
  }

  return (
    <Card title="Final rating">
      {current && (
        <div className="mb-4">
          <Fact label="Current">{RATING_LABELS[current.rating]}</Fact>
          <Fact label="Set">{when(current.setAt)}</Fact>
        </div>
      )}

      <CalibrationTrail rows={history.data} />

      <div className="grid gap-3">
        <Field label="Rating">
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

        {/*
          Released is the end of this card. The rating is final at that point - the server
          returns 409 to any further change - and the employee has read it, so the controls go
          rather than sitting there disabled. What replaces them is the one thing still worth
          knowing, said once: it is out.
        */}
        {released ? (
          <p className="text-sm">
            Shared with {record.summary.subjectName} on {when(current?.releasedAt)}. They can now
            see this rating and your written review.
          </p>
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <Button
              variant="primary"
              busy={set.isPending}
              busyLabel="Setting"
              onClick={() => set.mutate(rating)}
            >
              Set rating
            </Button>
            {/*
              HR see the peer feedback, your review and the number, then either adjust it or
              approve it as set - and only then may it go to the employee (P-4.8). The control
              is absent rather than disabled while that is outstanding, because the wait is not
              something the manager can act on from here.

              `signedOffByHr` is null where the caller has no grounds to know, which never
              happens on this screen: the manager reads the calibration trail. Compared against
              false rather than treated as falsy, so a null would show the button and let the
              server answer instead of hiding a control on a fact we were not given.
            */}
            {current && current.signedOffByHr !== false && (
              <Button
                busy={release.isPending}
                busyLabel="Sharing"
                onClick={() => release.mutate()}
              >
                Share with the employee
              </Button>
            )}
          </div>
        )}
      </div>
    </Card>
  )
}

/**
 * Says which of the two reasons a section is empty, using what the server told us.
 *
 * Guessing from a null would eventually show "nobody has written this yet" to somebody who was
 * simply not permitted to see it, which is a claim about the record rather than about them.
 * The reverse is just as wrong and is what this screen used to do: telling a manager they had
 * no access to their own report's peer feedback, when in truth no peer had submitted yet.
 * `visibleSections` names grounds, so the two cases stay apart.
 */
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
