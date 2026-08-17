import { useState } from 'react'
import { useCycles } from '../../api/reviews'
import { useCycleActions } from '../../api/admin'
import { Button, Card, Fact, Field, TextInput, WriteFailure, when } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import type { Cycle } from '../../api/types'
import { AdminNav } from './AdminNav'

/**
 * Cycle configuration (P-6.1, P-6.2).
 *
 * The Super Admin sets when a cycle opens; **the system opens it**, on the date, by a daily
 * sweep. That is scenario section 4, and the screen is worded to match: a configured cycle is
 * one that will fire, not one waiting for somebody to press something.
 *
 * "Open now" exists anyway, and honestly labelled. It runs the sweep's own code path rather
 * than a second one, and it is here because a cycle that can only be opened by waiting until
 * tomorrow cannot be demonstrated today.
 *
 * Dates are editable only while `openedAt` is null. Afterwards the server returns 409 - the
 * caller has the permission and it is the cycle's state that refuses - and it renders on the
 * form rather than as a denial.
 */
export function Cycles() {
  const { data: cycles, isPending, error } = useCycles()
  const actions = useCycleActions()
  const [form, setForm] = useState({
    financialYear: String(new Date().getFullYear()),
    quadrimesterNo: '1',
    startDate: '',
    endDate: '',
  })

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Administration</h1>
      <AdminNav />

      <div className="grid gap-4">
        <Card title="Configure a cycle">
          <div className="grid gap-3 md:grid-cols-4">
            <Field label="Financial year">
              <TextInput
                value={form.financialYear}
                onChange={(e) => setForm({ ...form, financialYear: e.target.value })}
              />
            </Field>
            <Field label="Quadrimester" hint="1, 2 or 3.">
              <TextInput
                value={form.quadrimesterNo}
                onChange={(e) => setForm({ ...form, quadrimesterNo: e.target.value })}
              />
            </Field>
            <Field label="Opens on">
              <TextInput
                type="date"
                value={form.startDate}
                onChange={(e) => setForm({ ...form, startDate: e.target.value })}
              />
            </Field>
            <Field label="Ends on">
              <TextInput
                type="date"
                value={form.endDate}
                onChange={(e) => setForm({ ...form, endDate: e.target.value })}
              />
            </Field>
          </div>

          <WriteFailure error={actions.create.error} />

          <div className="mt-3">
            <Button
              variant="primary"
              disabled={!form.startDate || !form.endDate || actions.create.isPending}
              onClick={() =>
                actions.create.mutate({
                  financialYear: Number(form.financialYear),
                  quadrimesterNo: Number(form.quadrimesterNo),
                  startDate: form.startDate,
                  endDate: form.endDate,
                })
              }
            >
              Create
            </Button>
            <p className="mt-2 text-xs text-muted">
              The cycle opens by itself on its start date, and takes in whoever is in the
              matching cohort at that moment. A date already past is picked up on the next
              daily sweep rather than being skipped.
            </p>
          </div>
        </Card>

        {isPending ? (
          <Loading />
        ) : error ? (
          <QueryFailure error={error} />
        ) : cycles.length === 0 ? (
          <EmptyState>No cycles configured yet.</EmptyState>
        ) : (
          <div className="grid gap-3">
            {cycles.map((cycle) => (
              <CycleRow key={cycle.id} cycle={cycle} />
            ))}
          </div>
        )}
      </div>
    </>
  )
}

function CycleRow({ cycle }: { cycle: Cycle }) {
  const actions = useCycleActions()
  const [dates, setDates] = useState({ startDate: cycle.startDate, endDate: cycle.endDate })
  const opened = cycle.openedAt !== null

  return (
    <Card title={cycle.label}>
      <Fact label="Status">{cycle.status.toLowerCase()}</Fact>
      <Fact label="Runs">
        {when(cycle.startDate)} to {when(cycle.endDate)}
      </Fact>
      <Fact label="Opened">{opened ? when(cycle.openedAt) : 'not yet'}</Fact>

      {!opened ? (
        <div className="mt-4 border-t border-line pt-4">
          <div className="flex flex-wrap items-end gap-3">
            <Field label="Opens on">
              <TextInput
                type="date"
                value={dates.startDate}
                onChange={(e) => setDates({ ...dates, startDate: e.target.value })}
              />
            </Field>
            <Field label="Ends on">
              <TextInput
                type="date"
                value={dates.endDate}
                onChange={(e) => setDates({ ...dates, endDate: e.target.value })}
              />
            </Field>
            <Button
              disabled={actions.reschedule.isPending}
              onClick={() => actions.reschedule.mutate({ cycleId: cycle.id, ...dates })}
            >
              Reschedule
            </Button>
            <Button
              variant="primary"
              disabled={actions.open.isPending}
              onClick={() => actions.open.mutate(cycle.id)}
            >
              Open now
            </Button>
          </div>
          <p className="mt-2 text-xs text-muted">
            Opening takes in everyone in the matching cohort. Once open, the dates are fixed
            and people cannot be removed from the cohort mid-cycle.
          </p>
        </div>
      ) : cycle.status === 'OPEN' ? (
        <div className="mt-4 border-t border-line pt-4">
          <Button
            variant="danger"
            disabled={actions.close.isPending}
            onClick={() => actions.close.mutate(cycle.id)}
          >
            Close the cycle
          </Button>
          <p className="mt-2 text-xs text-muted">
            {/* Closing stops writes; it hides nothing that was already readable. */}
            No further reviews or ratings can be written. Everything already written stays
            readable to whoever could read it.
          </p>
        </div>
      ) : null}

      <WriteFailure
        error={actions.reschedule.error ?? actions.open.error ?? actions.close.error}
      />
    </Card>
  )
}
