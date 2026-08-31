import { useEffect, useState } from 'react'
import { CycleSelect, useSelectedCycle } from '../../components/CycleSelect'
import { useMyReviewRecord, useSaveSelfReview } from '../../api/reviews'
import { useCurrentUser } from '../../auth/useCurrentUser'
import { Button, Card, Field, TextArea, WriteFailure, when } from '../../components/Form'
import { Loading, NotUnderReview, QueryFailure } from '../../components/States'
import { isNotFound } from '../../api/errors'

/**
 * Writing the caller's own self-review.
 *
 * The endpoint takes **no subject id**, so this form has no employee selector and no way to be
 * pointed at anybody else. That is the server's design and it removes a whole class of mistake
 * from the client: there is nothing here to get wrong.
 *
 * Draft and submit are the same endpoint, distinguished by a flag. Once submitted the server
 * returns 409 to any further write - editing after the fact would let somebody rewrite what
 * their manager has already read and acted on - and that arrives here as a message on the
 * form, not as a denial, because the permission was never in question.
 */
export function SelfReview() {
  const { data: me } = useCurrentUser()
  const { cycles, cycleId, setCycleId } = useSelectedCycle()
  const { data: record, isPending, error } = useMyReviewRecord(cycleId, me?.id)
  const save = useSaveSelfReview(cycleId)

  const [achievements, setAchievements] = useState('')
  const [challenges, setChallenges] = useState('')
  const [goals, setGoals] = useState('')

  const existing = record?.selfReview
  const submitted = Boolean(existing?.submittedAt)

  // The row exists only once something has been saved, so its presence is what distinguishes
  // a first save from a revision. The save button says which of the two it is doing: without
  // it there is nothing on the screen that confirms a draft was ever kept, and people saved
  // twice to be sure.
  const hasDraft = Boolean(existing) && !submitted

  // Load whatever is already saved when the cycle changes. Keyed on the cycle rather than on
  // the record, so typing is not overwritten by a background refetch mid-sentence.
  useEffect(() => {
    setAchievements(existing?.achievements ?? '')
    setChallenges(existing?.challenges ?? '')
    setGoals(existing?.goals ?? '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cycleId, submitted])

  const write = (submit: boolean) =>
    save.mutate({ input: { achievements, challenges, goals }, submit })

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">My self-review</h1>
      <CycleSelect cycles={cycles} cycleId={cycleId} onChange={setCycleId} />

      {cycleId === undefined ? null : isPending ? (
        <Loading />
      ) : isNotFound(error) ? (
        // Nobody is under review in every cycle, and the write would be refused anyway - the
        // server checks participation before it will save a self-review. Showing the empty
        // form here would invite somebody to type an essay into a 404.
        <NotUnderReview what="self-review" />
      ) : error ? (
        <QueryFailure error={error} />
      ) : (
        <Card>
          {submitted ? (
            <p className="mb-4 rounded bg-line/40 p-3 text-sm">
              Submitted {when(existing?.submittedAt)}. A submitted self-review cannot be
              changed - your manager may already have read it.
            </p>
          ) : null}

          <div className="grid gap-4">
            <Field label="What did you achieve?">
              <TextArea
                value={achievements}
                disabled={submitted}
                onChange={(e) => setAchievements(e.target.value)}
              />
            </Field>
            <Field label="What was difficult?">
              <TextArea
                value={challenges}
                disabled={submitted}
                onChange={(e) => setChallenges(e.target.value)}
              />
            </Field>
            <Field label="What do you want to work on?">
              <TextArea
                value={goals}
                disabled={submitted}
                onChange={(e) => setGoals(e.target.value)}
              />
            </Field>

            <WriteFailure error={save.error} />

            {!submitted && (
              <div className="flex items-center gap-3">
                <Button onClick={() => write(false)} busy={save.isPending} busyLabel="Saving">
                  {hasDraft ? 'Edit draft' : 'Save draft'}
                </Button>
                <Button variant="primary" onClick={() => write(true)} busy={save.isPending} busyLabel="Submitting">
                  Submit
                </Button>
              </div>
            )}
          </div>
        </Card>
      )}
    </>
  )
}
