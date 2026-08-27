import { useState } from 'react'
import type { Goal } from '../../api/types'
import { Button, Field, TextArea, TextInput, WriteFailure, when } from '../../components/Form'

/**
 * The goal list, shared by the employee's own plan and the manager's view of it.
 *
 * One component rather than two, because the rules are genuinely shared and splitting them
 * would give the split two places to drift. What differs between the two screens is which
 * actions are handed in: the manager gets `submit`, the employee gets `agree`, both get
 * `progress`. Under P-5.9 the manager writes the goals and the employee agrees to them, so
 * "who may do what" is expressed by which callbacks exist rather than by a role flag.
 *
 * **What the flags do not do is decide anything.** They choose which controls are worth
 * drawing. The server refuses an employee's approval attempt whether or not the button was
 * rendered.
 */

export interface GoalActions {
  add: (goal: { title: string; detail: string; targetDate: string | null }) => void
  edit: (input: { goalId: number; title: string; detail: string }) => void
  approve?: (input: { goalId: number; approved: boolean }) => void
  moveDate?: (input: { goalId: number; targetDate: string | null }) => void
  remove: (goalId: number) => void

  /**
   * Present only on the manager's screen. Its presence is what tells the row it is being
   * rendered for the person who writes the goals, rather than the person they are about.
   */
  submit?: (goalId: number) => void

  /** Present only on the employee's own plan. Nobody agrees on their behalf (P-5.9). */
  agree?: (goalId: number) => void

  /** Present on both: the employee reports progress, and so may their manager. */
  progress?: (input: { goalId: number; note: string }) => void

  error: unknown
  busy: boolean
}

export function GoalList({
  goals,
  actions,
  canApprove,
  readOnly,
}: {
  goals: Goal[]
  actions: GoalActions
  canApprove: boolean
  readOnly: boolean
}) {
  if (goals.length === 0) {
    return <p className="text-sm text-muted">No goals yet.</p>
  }
  return (
    <ul className="grid gap-3">
      {goals.map((goal) => (
        <GoalRow
          key={goal.id}
          goal={goal}
          actions={actions}
          canApprove={canApprove}
          readOnly={readOnly}
        />
      ))}
    </ul>
  )
}

function GoalRow({
  goal,
  actions,
  canApprove,
  readOnly,
}: {
  goal: Goal
  actions: GoalActions
  canApprove: boolean
  readOnly: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState(goal.title)
  const [detail, setDetail] = useState(goal.detail ?? '')
  const [reporting, setReporting] = useState(false)
  const [note, setNote] = useState(goal.progressNote ?? '')
  const complete = goal.status === 'COMPLETE'

  // The three states a development goal moves through (P-5.9). An improvement goal has no
  // agreement at all, and `agreement` is null on it, so it behaves as it always did.
  const draft = goal.agreement === 'DRAFT'
  const pending = goal.agreement === 'PENDING'
  const agreed = goal.agreement === 'AGREED' || goal.agreement === null

  return (
    <li className="rounded border border-line p-4">
      {editing ? (
        <div className="grid gap-3">
          <Field label="Goal">
            <TextInput value={title} onChange={(e) => setTitle(e.target.value)} />
          </Field>
          <Field label="Detail">
            <TextArea value={detail} onChange={(e) => setDetail(e.target.value)} />
          </Field>
          <div className="flex gap-2">
            <Button
              variant="primary"
              busy={actions.busy}
              busyLabel="Saving"
              onClick={() => {
                actions.edit({ goalId: goal.id, title, detail })
                setEditing(false)
              }}
            >
              Save
            </Button>
            <Button onClick={() => setEditing(false)}>Cancel</Button>
          </div>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-baseline gap-x-3">
            <span className="font-medium">{goal.title}</span>

            {/*
              The agreement state is said plainly, because it changes what the goal *is*. A
              pending goal is one the employee has been asked to accept and has not yet; a
              draft is one they cannot see at all, so this label only ever appears to the
              manager who wrote it.
            */}
            {draft && <span className="text-xs text-muted">draft, not sent yet</span>}
            {pending && (
              <span className="text-xs text-warn">
                {actions.agree ? 'waiting for you to agree' : 'sent, awaiting agreement'}
              </span>
            )}
            {agreed && complete && (
              // Matches the control that produced it, "Approve as complete", so the state reads
              // as the result of the act rather than as a separate word for it.
              <span className="text-xs text-accent">approved as completed</span>
            )}
            {agreed && !complete && <span className="text-xs text-muted">open</span>}

            <span className="ml-auto text-xs text-muted">
              {goal.targetDate ? `due ${when(goal.targetDate)}` : 'no date set'}
            </span>
          </div>

          {goal.detail && <p className="mt-2 whitespace-pre-wrap text-sm">{goal.detail}</p>}

          {/* Progress is the employee's account, so it is shown as theirs and kept separate. */}
          {goal.progressNote && !reporting && (
            <div className="mt-3 rounded bg-line/40 p-3">
              <p className="mb-1 text-xs font-medium text-muted">Progress</p>
              <p className="whitespace-pre-wrap text-sm">{goal.progressNote}</p>
            </div>
          )}

          {reporting && (
            <div className="mt-3 grid gap-2">
              <Field label="Progress" hint="Your own words. The goal itself is not changed.">
                <TextArea value={note} onChange={(e) => setNote(e.target.value)} />
              </Field>
              <div className="flex gap-2">
                <Button
                  variant="primary"
                  busy={actions.busy}
                  busyLabel="Saving"
                  onClick={() => {
                    actions.progress!({ goalId: goal.id, note })
                    setReporting(false)
                  }}
                >
                  Save progress
                </Button>
                <Button onClick={() => setReporting(false)}>Cancel</Button>
              </div>
            </div>
          )}

          {!readOnly && (
            <div className="mt-3 flex flex-wrap gap-2">
              {/*
                Agreeing is the employee's alone (P-5.9), so this button exists only on their
                own plan - the manager's copy of this component is not given the action at all.
              */}
              {pending && actions.agree && (
                <Button
                  variant="primary"
                  busy={actions.busy}
                  busyLabel="Agreeing"
                  onClick={() => actions.agree!(goal.id)}
                >
                  Agree to this goal
                </Button>
              )}

              {draft && actions.submit && (
                <Button
                  variant="primary"
                  busy={actions.busy}
                  busyLabel="Sending"
                  onClick={() => actions.submit!(goal.id)}
                >
                  Send to employee
                </Button>
              )}

              {/*
                Rewording is refused by the server once the goal is agreed, so the control goes
                rather than sitting there to produce a 409. Before agreement it is the
                manager's, which is why it rides with the other manager actions.
              */}
              {!agreed && actions.submit && (
                <Button onClick={() => setEditing(true)}>Edit</Button>
              )}

              {agreed && !complete && actions.progress && (
                <Button onClick={() => setReporting(true)}>
                  {goal.progressNote ? 'Update progress' : 'Add progress'}
                </Button>
              )}

              {canApprove && actions.approve && agreed && (
                <Button
                  busy={actions.busy}
                  busyLabel={complete ? 'Reopening' : 'Approving'}
                  onClick={() => actions.approve!({ goalId: goal.id, approved: !complete })}
                >
                  {complete ? 'Reopen' : 'Approve as complete'}
                </Button>
              )}

              {canApprove && actions.moveDate && <MoveDate goal={goal} actions={actions} />}

              {!complete && actions.submit && (
                <Button
                  variant="danger"
                  busy={actions.busy}
                  busyLabel="Removing"
                  onClick={() => actions.remove(goal.id)}
                >
                  Remove
                </Button>
              )}
            </div>
          )}
        </>
      )}
    </li>
  )
}

/**
 * Moving a development goal's target date - the manager's alone (P-5.5).
 *
 * The same control is deliberately never rendered for an improvement-plan goal. A target date
 * on a PIP goal is a PIP deadline, and the server refuses it under `EXTEND_PIP_DEADLINE`.
 */
function MoveDate({ goal, actions }: { goal: Goal; actions: GoalActions }) {
  const [open, setOpen] = useState(false)
  const [date, setDate] = useState(goal.targetDate ?? '')

  if (!open) {
    return <Button onClick={() => setOpen(true)}>Move date</Button>
  }
  return (
    <span className="flex items-center gap-2">
      <input
        type="date"
        value={date}
        onChange={(e) => setDate(e.target.value)}
        className="rounded border border-line px-2 py-1 text-sm"
      />
      <Button
        variant="primary"
        disabled={actions.busy}
        onClick={() => {
          actions.moveDate!({ goalId: goal.id, targetDate: date || null })
          setOpen(false)
        }}
      >
        Save
      </Button>
      <Button onClick={() => setOpen(false)}>Cancel</Button>
    </span>
  )
}

export function AddGoal({ actions, withDate }: { actions: GoalActions; withDate: boolean }) {
  const [title, setTitle] = useState('')
  const [detail, setDetail] = useState('')
  const [targetDate, setTargetDate] = useState('')

  return (
    <div className="mt-4 grid gap-3 border-t border-line pt-4">
      <Field label="Add a goal">
        <TextInput
          value={title}
          placeholder="What is the goal?"
          onChange={(e) => setTitle(e.target.value)}
        />
      </Field>
      <TextArea
        value={detail}
        placeholder="Detail, and how it is going"
        onChange={(e) => setDetail(e.target.value)}
      />
      {withDate && (
        <Field label="Target date">
          <input
            type="date"
            value={targetDate}
            onChange={(e) => setTargetDate(e.target.value)}
            className="rounded border border-line px-3 py-2 text-sm"
          />
        </Field>
      )}

      <WriteFailure error={actions.error} />

      <div>
        <Button
          variant="primary"
          disabled={!title.trim() || actions.busy}
          onClick={() => {
            actions.add({ title, detail, targetDate: targetDate || null })
            setTitle('')
            setDetail('')
            setTargetDate('')
          }}
        >
          Add goal
        </Button>
      </div>
    </div>
  )
}
