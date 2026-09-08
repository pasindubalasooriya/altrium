import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { ScheduleMeeting } from '../meetings/ScheduleMeeting'
import {
  useAddGoal,
  useSubmitGoal,
  useReportProgress,
  useApproveGoal,
  useDevelopmentPlan,
  useEditGoal,
  useImprovementPlanActions,
  useImprovementPlanHistory,
  useMoveTargetDate,
  useOpenImprovementPlan,
  useRemoveGoal,
} from '../../api/plans'
import {
  Button,
  Card,
  Fact,
  Field,
  TextArea,
  TextInput,
  WriteFailure,
  when,
} from '../../components/Form'
import { Loading, QueryFailure } from '../../components/States'
import { AddGoal, GoalList, type GoalActions } from '../plan/PlanGoals'
import type { Goal, ImprovementPlan } from '../../api/types'

/**
 * A team member's development plan, and their improvement plan if one is running.
 *
 * Both instruments on one page, because they are one story: opening a PIP suspends the PDP,
 * and closing it resumes the same plan with its goals and progress intact. Two pages would
 * have hidden the thing worth seeing.
 */
export function MemberPlan() {
  const { userId } = useParams()
  const id = Number(userId)

  const plan = useDevelopmentPlan(id)
  const history = useImprovementPlanHistory(id)

  const planKey = ['development-plan', id]
  const add = useAddGoal(id, planKey)
  const edit = useEditGoal(planKey)
  const approve = useApproveGoal(planKey)
  const moveDate = useMoveTargetDate(planKey)
  const remove = useRemoveGoal(planKey)
  const submit = useSubmitGoal(planKey)
  const progress = useReportProgress(planKey)

  // `submit` is here and `agree` is not, which is what tells the shared goal row it is being
  // rendered for the manager. Agreeing is the employee's alone (P-5.9).
  const actions: GoalActions = {
    add: add.mutate,
    edit: edit.mutate,
    approve: approve.mutate,
    moveDate: moveDate.mutate,
    remove: remove.mutate,
    submit: submit.mutate,
    progress: progress.mutate,
    error:
      add.error ?? edit.error ?? approve.error ?? moveDate.error ?? remove.error
      ?? submit.error ?? progress.error,
    busy:
      add.isPending ||
      edit.isPending ||
      approve.isPending ||
      submit.isPending ||
      progress.isPending ||
      moveDate.isPending ||
      remove.isPending,
  }

  if (plan.isPending) {
    return <Loading />
  }
  if (plan.error) {
    return <QueryFailure error={plan.error} />
  }

  const active = history.data?.find((p) => p.status === 'ACTIVE')
  const suspended = plan.data.status === 'SUSPENDED'

  return (
    <>
      <h1 className="mb-6 text-xl font-semibold tracking-tight">{plan.data.userName}</h1>

      <div className="grid gap-4">
        <Card title="Development plan">
          <Fact label="Status">{suspended ? 'On hold, improvement plan running' : 'Active'}</Fact>
          <div className="mt-4">
            <GoalList goals={plan.data.goals} actions={actions} canApprove readOnly={suspended} />
          </div>
          {!suspended && <AddGoal actions={actions} withDate />}
        </Card>

        {active ? (
          <ActivePlan plan={active} userId={id} />
        ) : (
          <OpenPlanForm userId={id} suspended={suspended} />
        )}

        {/*
          Beside the plan, because that is what the meeting is for (scenario section 8, section
          9). It sits after both plan cards rather than inside either: the same meeting agrees
          a development plan or an improvement one, and putting it in one card would imply it
          belonged to that track alone.
        */}
        <ScheduleMeeting type="PLAN_MEETING" subjectId={id} subjectName={plan.data.userName} />

        <PlanHistory plans={history.data ?? []} />
      </div>
    </>
  )
}

/**
 * Opening an improvement plan.
 *
 * The deadline is set here and **never again**. There is no control anywhere in this
 * application that changes it, because `EXTEND_PIP_DEADLINE` names nobody at all - so the form
 * says so at the point where the decision is actually made, which is the only moment the
 * warning is useful.
 */
function OpenPlanForm({ userId, suspended }: { userId: number; suspended: boolean }) {
  const open = useOpenImprovementPlan(userId)
  const [consequenceClause, setConsequenceClause] = useState('')
  const [deadline, setDeadline] = useState('')

  if (suspended) {
    // The development plan is suspended but no active PIP came back, which means the caller
    // cannot read it. Say nothing about it either way.
    return null
  }

  return (
    <Card title="Open an improvement plan">
      <div className="grid gap-3">
        <Field label="Consequence clause">
          <TextArea
            value={consequenceClause}
            onChange={(e) => setConsequenceClause(e.target.value)}
          />
        </Field>
        <Field label="Deadline">
          <TextInput type="date" value={deadline} onChange={(e) => setDeadline(e.target.value)} />
        </Field>

        <WriteFailure error={open.error} />

        <div>
          <Button
            variant="primary"
            disabled={!deadline}
            busy={open.isPending}
            busyLabel="Opening"
            onClick={() => open.mutate({ consequenceClause, deadline })}
          >
            Open plan
          </Button>
        </div>
      </div>
    </Card>
  )
}

/**
 * One goal on a running improvement plan, as its manager works it.
 *
 * Two controls, and the split between them is P-5.2 and P-5.9 read together: **the manager
 * records how it is going, and the manager says when it is done.** On a development plan the
 * first of those is shared with the employee; here it is not. A PIP is put to somebody rather
 * than agreed with them, so the employee reads this page and writes nothing on it - the server
 * refuses them either way, and offering a disabled box would imply a permission that does not
 * exist.
 *
 * Progress matters more here than on a PDP. The plan runs for months against a fixed deadline
 * and is judged at the end; without a note in between, the record is a goal set on day one and
 * a pass or fail at the close, with nothing explaining the outcome to the person it lands on.
 */
function ImprovementGoalRow({ goal }: { goal: Goal }) {
  const [reporting, setReporting] = useState(false)
  const [note, setNote] = useState(goal.progressNote ?? '')

  // Invalidating the improvement-plan queries rather than a development plan key: the goal
  // belongs to the PIP, even though the endpoint that writes it is shared.
  const progress = useReportProgress(['improvement-plan'])
  const approve = useApproveGoal(['improvement-plan'])

  const complete = goal.status === 'COMPLETE'
  const busy = progress.isPending || approve.isPending

  return (
    <li className="rounded border border-line p-3">
      <div className="flex flex-wrap items-baseline gap-x-3">
        <span className="font-medium">{goal.title}</span>
        <span className="text-xs text-muted">{complete ? 'approved' : 'open'}</span>
        {/*
          No "move date" control on an improvement goal. A target date on a PIP goal is a PIP
          deadline, and the server refuses it under the same rule.
        */}
        <span className="ml-auto text-xs text-muted">
          {goal.targetDate ? `due ${when(goal.targetDate)}` : ''}
        </span>
      </div>
      {goal.detail && <p className="mt-1 whitespace-pre-wrap">{goal.detail}</p>}

      {goal.progressNote && (
        <p className="mt-2 rounded bg-line/40 p-2 text-xs whitespace-pre-wrap">
          <span className="text-muted">Progress: </span>
          {goal.progressNote}
        </p>
      )}

      <WriteFailure error={progress.error ?? approve.error} />

      {reporting ? (
        <div className="mt-2 grid gap-2">
          <TextArea value={note} onChange={(e) => setNote(e.target.value)} />
          <div className="flex gap-2">
            <Button
              variant="primary"
              busy={progress.isPending}
              busyLabel="Saving"
              onClick={() =>
                progress.mutate(
                  { goalId: goal.id, note },
                  { onSuccess: () => setReporting(false) },
                )
              }
            >
              Save progress
            </Button>
            <Button onClick={() => setReporting(false)}>Cancel</Button>
          </div>
        </div>
      ) : (
        <div className="mt-2 flex flex-wrap gap-2">
          {/*
            An approved goal takes no more progress - the server returns 409 - so the control
            goes rather than sitting there to be refused. Reopening brings it back.
          */}
          {!complete && (
            <Button disabled={busy} onClick={() => setReporting(true)}>
              {goal.progressNote ? 'Update progress' : 'Record progress'}
            </Button>
          )}
          <Button
            disabled={busy}
            onClick={() => approve.mutate({ goalId: goal.id, approved: !complete })}
          >
            {complete ? 'Reopen' : 'Mark complete'}
          </Button>
        </div>
      )}
    </li>
  )
}

function ActivePlan({ plan, userId }: { plan: ImprovementPlan; userId: number }) {
  const actions = useImprovementPlanActions(userId)
  const [title, setTitle] = useState('')
  const [detail, setDetail] = useState('')
  const [targetDate, setTargetDate] = useState('')
  const [clause, setClause] = useState(plan.consequenceClause ?? '')

  // The server's fact, not the textarea's. Typing a clause does not submit the plan to HR, and
  // a status driven by local state would flip to "Submitted" while the change was still unsaved.
  const hasClause = Boolean(plan.consequenceClause?.trim())

  const error = actions.addGoal.error ?? actions.setConsequenceClause.error ?? actions.close.error
  const busy =
    actions.addGoal.isPending || actions.setConsequenceClause.isPending || actions.close.isPending

  return (
    <Card title="Improvement plan">
      <Fact label="Opened">
        {when(plan.openedAt)} by {plan.openedBy}
      </Fact>
      {/* Shown, and there is no control here that changes it (P-5.5). */}
      <Fact label="Deadline">{when(plan.deadline)}</Fact>
      {/*
        Where the plan has got to, said once. It used to read "Co-signed: not yet", which is
        true and tells the manager nothing about whose turn it is or what is outstanding.

        Three states, and the middle one is the whole point: HR cannot co-sign until the
        consequence clause is written (P-5.6), so a plan without one is not waiting on HR at
        all - it is waiting on the manager, and saying "submitted" would send them off to chase
        the wrong person.

        There is no fourth state for sharing. Co-signing *is* what makes the plan visible to
        the employee (P-5.3); HR take no second action, and inventing a "shared" step in this
        wording would describe a button that does not exist.
      */}
      <Fact label="Status">
        {plan.cosigned
          ? `Co-signed by ${plan.cosignedBy} on ${when(plan.cosignedAt)}, and now visible to ${plan.userName}`
          : hasClause
            ? 'Submitted for HR co-signature'
            : 'Draft - not yet with HR'}
      </Fact>
      <Fact label="Witness">{plan.witnessName ?? 'not recorded'}</Fact>

      {!plan.cosigned && (
        <p className="mt-3 rounded bg-line/40 p-3 text-sm">
          {/*
            Co-sign and witness are HR's alone (P-5.4), and there is no disabled button for
            them here. A disabled control would imply a permission that might be granted; this
            is a separation of duties, and the whole reason the formality objects exist.
          */}
          {hasClause
            ? `${plan.userName} cannot see this plan until HR co-sign it, which also shares it with them. Only HR can do that.`
            : `Write the consequence clause below, then HR can co-sign. ${plan.userName} sees the plan at that point and not before.`}
        </p>
      )}

      <div className="mt-4 grid gap-3 border-t border-line pt-4">
        <Field label="Consequence clause" hint="Fixed once HR have co-signed.">
          <TextArea
            value={clause}
            disabled={plan.cosigned}
            onChange={(e) => setClause(e.target.value)}
          />
        </Field>
        {!plan.cosigned && (
          <div>
            <Button
              disabled={busy}
              onClick={() =>
                actions.setConsequenceClause.mutate({
                  planId: plan.id,
                  consequenceClause: clause,
                })
              }
            >
              Save clause
            </Button>
          </div>
        )}
      </div>

      <div className="mt-4 border-t border-line pt-4">
        <h3 className="mb-3 text-sm font-medium">Goals</h3>
        {plan.goals.length === 0 ? (
          <p className="text-sm text-muted">No goals yet.</p>
        ) : (
          <ul className="grid gap-2 text-sm">
            {plan.goals.map((goal) => (
              <ImprovementGoalRow key={goal.id} goal={goal} />
            ))}
          </ul>
        )}

        <div className="mt-3 grid gap-2">
          <TextInput
            value={title}
            placeholder="Goal"
            onChange={(e) => setTitle(e.target.value)}
          />
          <TextArea
            value={detail}
            placeholder="What is required"
            onChange={(e) => setDetail(e.target.value)}
          />
          <TextInput
            type="date"
            value={targetDate}
            onChange={(e) => setTargetDate(e.target.value)}
          />
          <div>
            <Button
              disabled={!title.trim() || busy}
              onClick={() => {
                actions.addGoal.mutate({
                  planId: plan.id,
                  goal: { title, detail, targetDate: targetDate || null },
                })
                setTitle('')
                setDetail('')
                setTargetDate('')
              }}
            >
              Add goal
            </Button>
          </div>
        </div>
      </div>

      <WriteFailure error={error} />

      <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-line pt-4">
        <Button
          variant="primary"
          disabled={busy}
          onClick={() => actions.close.mutate({ planId: plan.id, outcome: 'pass' })}
        >
          Pass the plan
        </Button>
        <Button
          variant="danger"
          disabled={busy}
          onClick={() => actions.close.mutate({ planId: plan.id, outcome: 'fail' })}
        >
          Fail the plan
        </Button>
        <span className="text-xs text-muted">
          Passing resumes the development plan with its goals and progress intact. A plan cannot
          be failed before its deadline has passed.
        </span>
      </div>
    </Card>
  )
}

function PlanHistory({ plans }: { plans: ImprovementPlan[] }) {
  const closed = plans.filter((plan) => plan.status !== 'ACTIVE')
  if (closed.length === 0) {
    return null
  }
  return (
    <Card title="Past improvement plans">
      <ul className="grid gap-2 text-sm">
        {closed.map((plan) => (
          <li key={plan.id} className="rounded border border-line p-3">
            <Fact label="Outcome">{plan.status.toLowerCase()}</Fact>
            <Fact label="Ran">
              {when(plan.openedAt)} to {when(plan.closedAt)}
            </Fact>
            <Fact label="Witness">{plan.witnessName ?? 'not recorded'}</Fact>
          </li>
        ))}
      </ul>
    </Card>
  )
}
