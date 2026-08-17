import { Link } from 'react-router-dom'
import {
  useAddGoal,
  useEditGoal,
  useMyDevelopmentPlan,
  useRemoveGoal,
} from '../../api/plans'
import { useCurrentUser } from '../../auth/useCurrentUser'
import { Card, Fact, when } from '../../components/Form'
import { Loading, QueryFailure } from '../../components/States'
import { AddGoal, GoalList, type GoalActions } from '../plan/PlanGoals'

/**
 * The employee's own development plan.
 *
 * Materialised by the server on first sight, so there is no "create a plan" step and no empty
 * state that means "you have none". Everybody has one from the moment they join (scenario
 * section 8), and a UI that offered to create one would misdescribe that.
 *
 * The employee writes goals and reports progress; they do not approve completion and do not
 * move target dates. Those controls are absent here rather than disabled - `canApprove` is
 * false - because they belong to the manager (P-5.2, P-5.5), and a disabled control implies a
 * permission that might arrive.
 */
export function MyPlan() {
  const { data: me } = useCurrentUser()
  const { data: plan, isPending, error } = useMyDevelopmentPlan()

  const planKey = ['development-plan', 'me']
  const add = useAddGoal(me?.id, planKey)
  const edit = useEditGoal(planKey)
  const remove = useRemoveGoal(planKey)

  const actions: GoalActions = {
    add: add.mutate,
    edit: edit.mutate,
    remove: remove.mutate,
    error: add.error ?? edit.error ?? remove.error,
    busy: add.isPending || edit.isPending || remove.isPending,
  }

  if (isPending) {
    return <Loading />
  }
  if (error) {
    return <QueryFailure error={error} />
  }

  const suspended = plan.status === 'SUSPENDED'

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">My development plan</h1>

      {suspended && (
        <Card>
          <p className="text-sm">
            Your development plan is on hold while an improvement plan is running. It is kept
            exactly as it is, with your goals and progress, and resumes when the improvement
            plan finishes.
          </p>
          <p className="mt-2 text-sm text-muted">
            Paused {when(plan.suspendedAt)} ·{' '}
            <Link className="text-accent" to="/my/improvement-plan">
              See the improvement plan
            </Link>
          </p>
        </Card>
      )}

      <div className="mt-4">
        <Card>
          <Fact label="Status">{suspended ? 'On hold' : 'Active'}</Fact>
          <div className="mt-4">
            <GoalList goals={plan.goals} actions={actions} canApprove={false} readOnly={suspended} />
          </div>
          {!suspended && <AddGoal actions={actions} withDate={false} />}
        </Card>
      </div>
    </>
  )
}
