import { useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  useAddGoal,
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
import type { ImprovementPlan } from '../../api/types'

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

  const actions: GoalActions = {
    add: add.mutate,
    edit: edit.mutate,
    approve: approve.mutate,
    moveDate: moveDate.mutate,
    remove: remove.mutate,
    error: add.error ?? edit.error ?? approve.error ?? moveDate.error ?? remove.error,
    busy:
      add.isPending ||
      edit.isPending ||
      approve.isPending ||
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
        <Field
          label="Consequence clause"
          hint="What happens if the plan is not met. Required before HR can co-sign, and fixed once they have."
        >
          <TextArea
            value={consequenceClause}
            onChange={(e) => setConsequenceClause(e.target.value)}
          />
        </Field>
        <Field
          label="Deadline"
          hint="Fixed once set. Nobody can extend a PIP deadline afterwards - not you, not HR, not an administrator."
        >
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
          <p className="mt-2 text-xs text-muted">
            This puts the development plan on hold. The employee cannot see the improvement plan
            until HR co-sign it.
          </p>
        </div>
      </div>
    </Card>
  )
}

function ActivePlan({ plan, userId }: { plan: ImprovementPlan; userId: number }) {
  const actions = useImprovementPlanActions(userId)
  const [title, setTitle] = useState('')
  const [detail, setDetail] = useState('')
  const [targetDate, setTargetDate] = useState('')
  const [clause, setClause] = useState(plan.consequenceClause ?? '')

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
      <Fact label="Co-signed">
        {plan.cosigned ? `${plan.cosignedBy} on ${when(plan.cosignedAt)}` : 'not yet'}
      </Fact>
      <Fact label="Witness">{plan.witnessName ?? 'not recorded'}</Fact>

      {!plan.cosigned && (
        <p className="mt-3 rounded bg-line/40 p-3 text-sm">
          {/*
            Co-sign and witness are HR's alone (P-5.4), and there is no disabled button for
            them here. A disabled control would imply a permission that might be granted; this
            is a separation of duties, and the whole reason the formality objects exist.
          */}
          The employee cannot see this plan until HR co-sign it. Only HR can do that, and only
          once the consequence clause is written.
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
              <li key={goal.id} className="rounded border border-line p-3">
                <div className="flex flex-wrap items-baseline gap-x-3">
                  <span className="font-medium">{goal.title}</span>
                  <span className="text-xs text-muted">
                    {goal.status === 'COMPLETE' ? 'approved' : 'open'}
                  </span>
                  {/*
                    No "move date" control on an improvement goal. A target date on a PIP goal
                    is a PIP deadline, and the server refuses it under the same rule.
                  */}
                  <span className="ml-auto text-xs text-muted">
                    {goal.targetDate ? `due ${when(goal.targetDate)}` : ''}
                  </span>
                </div>
                {goal.detail && <p className="mt-1 whitespace-pre-wrap">{goal.detail}</p>}
              </li>
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
