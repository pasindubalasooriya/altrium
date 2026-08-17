import { useEffect, useState } from 'react'
import { useCycles } from '../api/reviews'
import { Select } from './Form'

/**
 * Which cycle a screen is about.
 *
 * Cycles are the one unscoped read in the system: a cycle's dates are the same fact for the
 * whole organisation and are not about anybody, so everyone sees the same list. What differs
 * per caller is what is *in* the cycle, which is every other endpoint's problem.
 *
 * Defaults to the open cycle, then the most recent, because the question a person almost
 * always has is about the one running now.
 */
export function useSelectedCycle() {
  const { data: cycles, isPending, error } = useCycles()
  const [cycleId, setCycleId] = useState<number | undefined>()

  useEffect(() => {
    if (cycleId === undefined && cycles?.length) {
      const open = cycles.find((cycle) => cycle.status === 'OPEN')
      setCycleId((open ?? cycles[cycles.length - 1]).id)
    }
  }, [cycles, cycleId])

  return { cycles, cycleId, setCycleId, isPending, error }
}

export function CycleSelect({
  cycles,
  cycleId,
  onChange,
}: {
  cycles: { id: number; label: string; status: string }[] | undefined
  cycleId: number | undefined
  onChange: (id: number) => void
}) {
  if (!cycles?.length) {
    return <p className="text-sm text-muted">No review cycles have been configured yet.</p>
  }
  return (
    <div className="mb-6 flex items-center gap-3">
      <span className="text-sm text-muted">Cycle</span>
      <div className="w-72">
        <Select value={cycleId ?? ''} onChange={(e) => onChange(Number(e.target.value))}>
          {cycles.map((cycle) => (
            <option key={cycle.id} value={cycle.id}>
              {cycle.label} · {cycle.status.toLowerCase()}
            </option>
          ))}
        </Select>
      </div>
    </div>
  )
}
