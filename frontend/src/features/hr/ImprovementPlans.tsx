import { useState } from 'react'
import { useCosign, useImprovementPlanQueue, useRecordWitness } from '../../api/hr'
import { Button, Card, Fact, Field, TextInput, WriteFailure, when } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import type { ImprovementPlan } from '../../api/types'

/**
 * HR's improvement-plan queue: co-signing, and recording the witness (P-5.4).
 *
 * These two acts are HR's alone. The manager who opened the plan can do neither, and that
 * separation is the entire point of the formality objects - one person decides, another
 * confirms the process was followed.
 *
 * **Co-signing is also the moment the plan becomes visible to the employee** (P-5.3). Those
 * two being one event is the design: until HR have read the plan and put their name to it, the
 * employee cannot see it, so a manager cannot draft a PIP and confront somebody with it
 * unreviewed. It is irreversible, and this screen says so before the button rather than after.
 */
export function ImprovementPlans() {
  const { data: plans, isPending, error } = useImprovementPlanQueue()

  if (isPending) {
    return <Loading />
  }
  if (error) {
    return <QueryFailure error={error} />
  }

  const awaiting = plans.filter((plan) => !plan.cosigned)
  const running = plans.filter((plan) => plan.cosigned)

  return (
    <>
      <h1 className="mb-1 text-xl font-semibold tracking-tight">Improvement plans</h1>
      <p className="mb-6 text-sm text-muted">
        Running plans in the departments you hold grants for.
      </p>

      {plans.length === 0 ? (
        <EmptyState>No improvement plans are running in your granted departments.</EmptyState>
      ) : (
        <div className="grid gap-6">
          {awaiting.length > 0 && (
            <section>
              <h2 className="mb-3 text-sm font-medium">Awaiting your co-signature</h2>
              <div className="grid gap-4">
                {awaiting.map((plan) => (
                  <PlanCard key={plan.id} plan={plan} />
                ))}
              </div>
            </section>
          )}

          {running.length > 0 && (
            <section>
              <h2 className="mb-3 text-sm font-medium">Co-signed and running</h2>
              <div className="grid gap-4">
                {running.map((plan) => (
                  <PlanCard key={plan.id} plan={plan} />
                ))}
              </div>
            </section>
          )}
        </div>
      )}
    </>
  )
}

function PlanCard({ plan }: { plan: ImprovementPlan }) {
  const cosign = useCosign()
  const witness = useRecordWitness()
  const [witnessName, setWitnessName] = useState('')

  return (
    <Card title={plan.userName}>
      <Fact label="Opened">
        {when(plan.openedAt)} by {plan.openedBy}
      </Fact>
      {/* Shown, and there is no control on this page or any other that changes it (P-5.5). */}
      <Fact label="Deadline">{when(plan.deadline)}</Fact>
      <Fact label="Co-signed">
        {plan.cosigned ? `${plan.cosignedBy} on ${when(plan.cosignedAt)}` : 'not yet'}
      </Fact>
      <Fact label="Witness">{plan.witnessName ?? 'not recorded'}</Fact>

      <div className="mt-4 border-t border-line pt-4">
        <p className="text-xs font-medium text-muted">Consequence clause</p>
        <p className="whitespace-pre-wrap text-sm">
          {plan.consequenceClause || (
            <span className="text-muted">
              Not written. The manager must write it before this plan can be co-signed.
            </span>
          )}
        </p>
      </div>

      {plan.goals.length > 0 && (
        <div className="mt-4 border-t border-line pt-4">
          <p className="mb-2 text-xs font-medium text-muted">Goals</p>
          <ul className="grid gap-2 text-sm">
            {plan.goals.map((goal) => (
              <li key={goal.id} className="rounded border border-line p-3">
                <div className="flex flex-wrap items-baseline gap-x-3">
                  <span className="font-medium">{goal.title}</span>
                  <span className="text-xs text-muted">
                    {goal.status === 'COMPLETE' ? 'approved' : 'open'}
                  </span>
                  <span className="ml-auto text-xs text-muted">
                    {goal.targetDate ? `due ${when(goal.targetDate)}` : ''}
                  </span>
                </div>
                {goal.detail && <p className="mt-1 whitespace-pre-wrap">{goal.detail}</p>}
              </li>
            ))}
          </ul>
        </div>
      )}

      <WriteFailure error={cosign.error ?? witness.error} />

      {!plan.cosigned ? (
        <div className="mt-4 border-t border-line pt-4">
          <p className="mb-3 text-sm">
            Co-signing shares this plan with {plan.userName}. It cannot be undone, and the
            consequence clause is fixed from that moment.
          </p>
          <Button
            variant="primary"
            busy={cosign.isPending}
            busyLabel="Co-signing"
            onClick={() => cosign.mutate(plan.id)}
          >
            Co-sign and share with {plan.userName}
          </Button>
        </div>
      ) : !plan.witnessName ? (
        <div className="mt-4 grid gap-3 border-t border-line pt-4">
          <Field label="Witness to the meeting" hint="Who was present. Recorded permanently.">
            <TextInput value={witnessName} onChange={(e) => setWitnessName(e.target.value)} />
          </Field>
          <div>
            <Button
              disabled={!witnessName.trim()}
              busy={witness.isPending}
              busyLabel="Recording"
              onClick={() => witness.mutate({ planId: plan.id, witnessName })}
            >
              Record witness
            </Button>
          </div>
        </div>
      ) : null}
    </Card>
  )
}
