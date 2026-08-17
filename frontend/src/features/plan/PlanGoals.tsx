import { useState } from 'react'
import type { Goal } from '../../api/types'
import { Button, Field, TextArea, TextInput, WriteFailure, when } from '../../components/Form'

/**
 * The goal list, shared by the employee's own plan and the manager's view of it.
 *
 * One component with a `canApprove` flag rather than two, because the underlying rules are
 * genuinely shared: both parties write goals and progress (P-5.1), and only the manager
 * approves completion and moves target dates (P-5.2, P-5.5). Two components would have meant
 * two places for that split to drift.
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
  const complete = goal.status === 'COMPLETE'

  return (
    <li className="rounded border border-line p-4">
      {editing ? (
        <div className="grid gap-3">
          <Field label="Goal">
            <TextInput value={title} onChange={(e) => setTitle(e.target.value)} />
          </Field>
          <Field label="Progress and detail">
            <TextArea value={detail} onChange={(e) => setDetail(e.target.value)} />
          </Field>
          <div className="flex gap-2">
            <Button
              variant="primary"
              disabled={actions.busy}
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
            {complete ? (
              <span className="text-xs text-accent">
                approved{goal.approvedBy ? ` by ${goal.approvedBy}` : ''}
              </span>
            ) : (
              <span className="text-xs text-muted">open</span>
            )}
            <span className="ml-auto text-xs text-muted">
              {goal.targetDate ? `due ${when(goal.targetDate)}` : 'no date agreed'}
            </span>
          </div>
          {goal.detail && <p className="mt-2 whitespace-pre-wrap text-sm">{goal.detail}</p>}

          {!readOnly && (
            <div className="mt-3 flex flex-wrap gap-2">
              <Button onClick={() => setEditing(true)}>Edit</Button>

              {canApprove && actions.approve && (
                <Button
                  disabled={actions.busy}
                  onClick={() => actions.approve!({ goalId: goal.id, approved: !complete })}
                >
                  {complete ? 'Reopen' : 'Approve as complete'}
                </Button>
              )}

              {canApprove && actions.moveDate && <MoveDate goal={goal} actions={actions} />}

              {!complete && (
                <Button variant="danger" disabled={actions.busy} onClick={() => actions.remove(goal.id)}>
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
        <Field label="Target date" hint="Can be agreed later, and moved by the manager.">
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
