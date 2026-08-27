import { Link } from 'react-router-dom'
import {
  useAgreeGoal,
  useEditGoal,
  useMyDevelopmentPlan,
  useRemoveGoal,
  useReportProgress,
} from '../../api/plans'
import { Card, Fact, when } from '../../components/Form'
import { Loading, QueryFailure } from '../../components/States'
import { GoalList, type GoalActions } from '../plan/PlanGoals'

/**
 * The employee's own development plan.
 *
 * Materialised by the server on first sight, so there is no "create a plan" step and no empty
 * state that means "you have none". Everybody has one from the moment they join (scenario
 * section 8), and a UI that offered to create one would misdescribe that.
 *
 * **The employee does not write the goals.** Their manager drafts them and submits them, and
 * the employee's part is to agree and then report progress (P-5.9). That is a Product Owner
 * ruling and a deviation from scenario section 8, which describes the plan as collaborative
 * between the two of them - the collaboration survives as agreement rather than as shared
 * authorship.
 *
 * <p>So there is no "add a goal" here, and no approval or target-date control either: those
 * belong to the manager (P-5.2, P-5.5). All of them are absent rather than disabled, because a
 * disabled control implies a permission that might one day arrive.
 */
export function MyPlan() {
  const { data: plan, isPending, error } = useMyDevelopmentPlan()

  const planKey = ['development-plan', 'me']
  const agree = useAgreeGoal(planKey)
  const progress = useReportProgress(planKey)
  const edit = useEditGoal(planKey)
  const remove = useRemoveGoal(planKey)

  // No `submit`, and `add` and `remove` are here only to satisfy the shared type - the server
  // refuses all three for the employee (P-5.9), and no control on this screen calls them. The
  // two the employee actually holds are agreeing and reporting progress.
  const actions: GoalActions = {
    add: () => undefined,
    edit: edit.mutate,
    remove: remove.mutate,
    agree: agree.mutate,
    progress: progress.mutate,
    error: agree.error ?? progress.error,
    busy: agree.isPending || progress.isPending,
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
            <Link className="text-accent underline" to="/my/improvement-plan">
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
        </Card>
      </div>
    </>
  )
}
