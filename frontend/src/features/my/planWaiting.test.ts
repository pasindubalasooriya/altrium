import { beforeEach, describe, expect, it } from 'vitest'
import { markImprovementPlanSeen, planWaitsOnYou } from './planWaiting'
import type { DevelopmentPlan, Goal, OwnImprovementPlan } from '../../api/types'

/**
 * The dot on the employee's plan tab.
 *
 * Not a security test, and it must not be described as one. What is proved here is that the
 * two signals behave as the two different things they are: a goal awaiting agreement is
 * outstanding work and stays until it is done, and an improvement plan is news and clears on
 * being read. Getting that backwards is what makes a dot worth ignoring.
 */
describe('planWaitsOnYou', () => {
  beforeEach(() => window.localStorage.clear())

  const goal = (agreement: Goal['agreement']): Goal =>
    ({
      id: 1,
      title: 'Lead a design review',
      detail: null,
      targetDate: null,
      status: 'OPEN',
      agreement,
      submittedAt: null,
      agreedAt: null,
      progressNote: null,
    }) as Goal

  const development = (goals: Goal[]): DevelopmentPlan =>
    ({
      userId: 7,
      userName: 'John Alvarez',
      status: 'ACTIVE',
      active: true,
      suspendedAt: null,
      goals,
    }) as DevelopmentPlan

  const improvement = (id: number | null): OwnImprovementPlan =>
    id === null
      ? { hasPlan: false, plan: null }
      : ({ hasPlan: true, plan: { id } } as OwnImprovementPlan)

  it('says nothing when there is nothing to say', () => {
    expect(planWaitsOnYou(development([goal('AGREED')]), improvement(null))).toBeNull()
  })

  it('dots for a goal submitted for agreement', () => {
    expect(planWaitsOnYou(development([goal('PENDING')]), improvement(null))).toMatch(/agree/i)
  })

  /**
   * A draft is the manager's unfinished thought and never reaches the employee (P-5.9). If one
   * ever did, dotting on it would tell them a goal was being written about them.
   */
  it('does not dot for a draft goal', () => {
    expect(planWaitsOnYou(development([goal('DRAFT')]), improvement(null))).toBeNull()
  })

  it('dots for an improvement plan that has been shared', () => {
    expect(planWaitsOnYou(undefined, improvement(42))).toMatch(/improvement plan/i)
  })

  it('stops dotting for an improvement plan once it has been opened', () => {
    markImprovementPlanSeen(42)
    expect(planWaitsOnYou(undefined, improvement(42))).toBeNull()
  })

  /** The receipt is the plan's id, not a boolean, so a later second plan is not swallowed. */
  it('dots again for a different improvement plan', () => {
    markImprovementPlanSeen(42)
    expect(planWaitsOnYou(undefined, improvement(43))).toMatch(/improvement plan/i)
  })

  /**
   * The opposite of the goal case: reading a PIP clears it, but agreeing is what clears a goal.
   * Opening the tab must not silence a goal that is still waiting.
   */
  it('keeps dotting for a pending goal after the improvement plan has been read', () => {
    markImprovementPlanSeen(42)
    expect(planWaitsOnYou(development([goal('PENDING')]), improvement(42))).toMatch(/agree/i)
  })

  it('says nothing while the queries are still loading', () => {
    expect(planWaitsOnYou(undefined, undefined)).toBeNull()
  })
})
