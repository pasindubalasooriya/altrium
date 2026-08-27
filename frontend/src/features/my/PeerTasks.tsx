import { useState } from 'react'
import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { useMyPeerAssignments, useSubmitPeerReview } from '../../api/reviews'
import { Button, Card, Field, Select, TextArea, WriteFailure } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import { RATINGS, RATING_LABELS, type PeerTask, type Rating } from '../../api/types'

/**
 * The peer reviews the caller has been asked to write.
 *
 * This is the safe direction of the assignment table. "Whom must I review?" is answerable;
 * "who is reviewing me?" is not, from any endpoint, by anybody except the manager who chose
 * them (P-3.3).
 *
 * The list also does not name the other peer assigned to the same subject. A peer who knew
 * that would be one conversation away from the subject knowing it too, so the server does not
 * send it and there is no field here for it.
 */
export function PeerTasks() {
  const { cycles, cycleId, setCycleId } = useSelectedCycle()
  const { data: tasks, isPending, error } = useMyPeerAssignments(cycleId)

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Peer reviews to write</h1>
      <CycleSelect cycles={cycles} cycleId={cycleId} onChange={setCycleId} />

      {cycleId === undefined ? null : isPending ? (
        <Loading />
      ) : error ? (
        <QueryFailure error={error} />
      ) : tasks.length === 0 ? (
        <EmptyState>You have not been asked to review anyone this cycle.</EmptyState>
      ) : (
        <div className="grid gap-4">
          {tasks.map((task) => (
            <PeerReviewForm key={task.subjectId} task={task} cycleId={cycleId} />
          ))}
        </div>
      )}
    </>
  )
}

function PeerReviewForm({ task, cycleId }: { task: PeerTask; cycleId: number }) {
  const submit = useSubmitPeerReview(cycleId)
  const [feedback, setFeedback] = useState('')
  const [rating, setRating] = useState<Rating>('MEETS_EXPECTATIONS')

  if (task.submitted) {
    // Once written, a peer review is fixed. Showing the text back would be pointless here and
    // is not what the endpoint offers - the feedback belongs to the manager and HR now.
    return (
      <Card title={task.subjectName}>
        <p className="text-sm text-muted">Submitted. Peer feedback cannot be changed.</p>
      </Card>
    )
  }

  return (
    <Card title={task.subjectName}>
      <div className="grid gap-4">
        <Field label="Your feedback" hint="Written once, and it cannot be edited afterwards.">
          <TextArea value={feedback} onChange={(e) => setFeedback(e.target.value)} />
        </Field>
        <Field label="Your rating">
          <Select value={rating} onChange={(e) => setRating(e.target.value as Rating)}>
            {RATINGS.map((value) => (
              <option key={value} value={value}>
                {RATING_LABELS[value]}
              </option>
            ))}
          </Select>
        </Field>

        {/*
          A second submission is 409, not 403. The peer holds the permission; it is the record
          that refuses. It renders here, on the form, and never as a denial screen - telling
          somebody they lack access to a thing they were assigned would be false.
        */}
        <WriteFailure error={submit.error} />

        <div>
          <Button
            variant="primary"
            busy={submit.isPending}
            busyLabel="Submitting"
            onClick={() => submit.mutate({ subjectId: task.subjectId, feedback, rating })}
          >
            Submit feedback
          </Button>
        </div>
      </div>
    </Card>
  )
}
