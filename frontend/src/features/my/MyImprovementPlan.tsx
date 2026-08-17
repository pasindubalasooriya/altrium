import { useMyImprovementPlan } from '../../api/plans'
import { Card, Fact, when } from '../../components/Form'
import { Loading, QueryFailure } from '../../components/States'
import type { ImprovementPlan } from '../../api/types'

/**
 * The employee's own improvement plan, once HR have co-signed it (P-5.3).
 *
 * **`hasPlan` is false both when no plan exists and when one exists uncosigned**, and this
 * screen renders those identically - one sentence, the same sentence. Distinguishing them
 * would disclose that a plan had been drafted about somebody before HR had reviewed it, which
 * is exactly what the co-sign gate withholds. So the empty state here is worded as a fact
 * about the person's situation, not as a status of a document.
 *
 * Nothing on this page is editable. An improvement plan is put *to* an employee rather than
 * written with them - `WRITE_IMPROVEMENT_PLAN` has no `SELF` ground - and an employee who
 * could edit their own consequence clause could soften it.
 */
export function MyImprovementPlan() {
  const { data, isPending, error } = useMyImprovementPlan()

  if (isPending) {
    return <Loading />
  }
  if (error) {
    return <QueryFailure error={error} />
  }

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">My improvement plan</h1>
      {data.hasPlan && data.plan ? (
        <PlanDetail plan={data.plan} />
      ) : (
        <Card>
          <p className="text-sm text-muted">You are not on an improvement plan.</p>
        </Card>
      )}
    </>
  )
}

function PlanDetail({ plan }: { plan: ImprovementPlan }) {
  return (
    <div className="grid gap-4">
      <Card>
        <Fact label="Status">{plan.status.toLowerCase()}</Fact>
        <Fact label="Opened by">
          {plan.openedBy} on {when(plan.openedAt)}
        </Fact>
        {/* The deadline is shown and there is no control anywhere that changes it (P-5.5). */}
        <Fact label="Deadline">{when(plan.deadline)}</Fact>
        <Fact label="Co-signed by">
          {plan.cosignedBy} on {when(plan.cosignedAt)}
        </Fact>
        {plan.witnessName && <Fact label="Witness">{plan.witnessName}</Fact>}
        {plan.closedAt && <Fact label="Closed">{when(plan.closedAt)}</Fact>}
      </Card>

      <Card title="What is required">
        <p className="whitespace-pre-wrap text-sm">{plan.consequenceClause}</p>
      </Card>

      <Card title="Goals">
        {plan.goals.length === 0 ? (
          <p className="text-sm text-muted">No goals have been set.</p>
        ) : (
          <ul className="grid gap-3">
            {plan.goals.map((goal) => (
              <li key={goal.id} className="rounded border border-line p-4">
                <div className="flex flex-wrap items-baseline gap-x-3">
                  <span className="font-medium">{goal.title}</span>
                  <span className="text-xs text-muted">
                    {goal.status === 'COMPLETE'
                      ? `approved${goal.approvedBy ? ` by ${goal.approvedBy}` : ''}`
                      : 'open'}
                  </span>
                  <span className="ml-auto text-xs text-muted">
                    {goal.targetDate ? `due ${when(goal.targetDate)}` : ''}
                  </span>
                </div>
                {goal.detail && <p className="mt-2 whitespace-pre-wrap text-sm">{goal.detail}</p>}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}
