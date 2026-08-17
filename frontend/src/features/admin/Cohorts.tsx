import { useState } from 'react'
import {
  useCohortActions,
  useCohortMembers,
  useCohorts,
  useUsers,
  type Cohort,
} from '../../api/admin'
import { Button, Card, Field, Select, TextInput, WriteFailure } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import { AdminNav } from './AdminNav'

/**
 * Cohorts - who is reviewed in which quadrimester (scenario section 4).
 *
 * A person belongs to **exactly one** cohort, which is a unique key in the schema rather than
 * a rule in a service, so adding somebody to a second cohort moves them rather than
 * duplicating them. The screen says so, because "add" reads like it might do otherwise.
 *
 * A cohort attached to no quadrimester is never swept into a cycle. That is the deliberate way
 * to park a group, and detaching is offered rather than deleting.
 */
export function Cohorts() {
  const { data: cohorts, isPending, error } = useCohorts()
  const actions = useCohortActions()
  const [selected, setSelected] = useState<Cohort | null>(null)
  const [form, setForm] = useState({ name: '', quadrimesterNo: '' })

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Administration</h1>
      <AdminNav />

      <div className="grid gap-4">
        <Card title="Create a cohort">
          <div className="flex flex-wrap items-end gap-3">
            <div className="w-64">
              <Field label="Name">
                <TextInput
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                />
              </Field>
            </div>
            <div className="w-56">
              <Field label="Quadrimester" hint="Leave empty to park it, unswept.">
                <Select
                  value={form.quadrimesterNo}
                  onChange={(e) => setForm({ ...form, quadrimesterNo: e.target.value })}
                >
                  <option value="">None</option>
                  <option value="1">1</option>
                  <option value="2">2</option>
                  <option value="3">3</option>
                </Select>
              </Field>
            </div>
            <Button
              variant="primary"
              disabled={!form.name.trim() || actions.create.isPending}
              onClick={() =>
                actions.create.mutate(
                  {
                    name: form.name,
                    quadrimesterNo: form.quadrimesterNo ? Number(form.quadrimesterNo) : null,
                  },
                  { onSuccess: () => setForm({ name: '', quadrimesterNo: '' }) },
                )
              }
            >
              Create
            </Button>
          </div>
          <WriteFailure error={actions.create.error} />
        </Card>

        {isPending ? (
          <Loading />
        ) : error ? (
          <QueryFailure error={error} />
        ) : cohorts.length === 0 ? (
          <EmptyState>
            No cohorts yet. Nobody is reviewed until they are in one attached to a
            quadrimester.
          </EmptyState>
        ) : (
          <Card title="Cohorts">
            <ul className="grid gap-2 text-sm">
              {cohorts.map((cohort) => (
                <li key={cohort.id} className="rounded border border-line p-3">
                  <div className="flex flex-wrap items-center gap-3">
                    <span className="font-medium">{cohort.name}</span>
                    <span className="text-xs text-muted">
                      {cohort.quadrimesterNo
                        ? `quadrimester ${cohort.quadrimesterNo}`
                        : 'no quadrimester, never swept'}
                    </span>
                    <span className="ml-auto flex items-center gap-2">
                      <Select
                        value={cohort.quadrimesterNo ?? ''}
                        onChange={(e) =>
                          actions.retarget.mutate({
                            id: cohort.id,
                            quadrimesterNo: e.target.value ? Number(e.target.value) : null,
                          })
                        }
                      >
                        <option value="">Detach</option>
                        <option value="1">Quadrimester 1</option>
                        <option value="2">Quadrimester 2</option>
                        <option value="3">Quadrimester 3</option>
                      </Select>
                      <Button
                        onClick={() => setSelected(selected?.id === cohort.id ? null : cohort)}
                      >
                        {selected?.id === cohort.id ? 'Hide members' : 'Members'}
                      </Button>
                    </span>
                  </div>
                </li>
              ))}
            </ul>
            <WriteFailure error={actions.retarget.error} />
          </Card>
        )}

        {selected && <Members cohort={selected} />}
      </div>
    </>
  )
}

function Members({ cohort }: { cohort: Cohort }) {
  const members = useCohortMembers(cohort.id)
  const actions = useCohortActions(cohort.id)
  const [search, setSearch] = useState('')
  const candidates = useUsers({ search, active: true }, 0, 10)

  return (
    <Card title={`${cohort.name} members`}>
      {members.isPending ? (
        <Loading />
      ) : members.error ? (
        <QueryFailure error={members.error} />
      ) : members.data.length === 0 ? (
        <p className="text-sm text-muted">Nobody is in this cohort.</p>
      ) : (
        <ul className="mb-4 grid gap-2 text-sm">
          {members.data.map((member) => (
            <li
              key={member.userId}
              className="flex items-center gap-3 rounded border border-line p-3"
            >
              <span>{member.fullName}</span>
              <span className="ml-auto">
                <Button
                  variant="danger"
                  disabled={actions.removeMember.isPending}
                  onClick={() => actions.removeMember.mutate(member.userId)}
                >
                  Remove
                </Button>
              </span>
            </li>
          ))}
        </ul>
      )}

      {/*
        Removal is refused with 409 while the person is in an open cycle. Scenario section
        15.4 - what becomes of the reviews already written about them - is still with the
        Product Owner, and the refusal is what keeps that question open rather than answering
        it by accident. The message says so, so it does not read as a bug.
      */}
      <WriteFailure error={actions.removeMember.error} />
      {actions.removeMember.error != null && (
        <p className="mb-3 text-xs text-muted">
          Somebody already under review in an open cycle cannot be removed. What should happen
          to their in-flight reviews has not been decided, so the system refuses rather than
          choosing for you.
        </p>
      )}

      <div className="border-t border-line pt-4">
        <div className="w-72">
          <Field label="Add somebody">
            <TextInput
              value={search}
              placeholder="Search by name"
              onChange={(e) => setSearch(e.target.value)}
            />
          </Field>
        </div>
        {search && candidates.data && (
          <ul className="mt-2 grid gap-1 text-sm">
            {candidates.data.content.map((user) => (
              <li key={user.id}>
                <button
                  type="button"
                  className="w-full rounded border border-line px-3 py-2 text-left"
                  onClick={() => actions.addMember.mutate(user.id, { onSuccess: () => setSearch('') })}
                >
                  {user.fullName}
                  <span className="ml-2 text-xs text-muted">
                    {user.departmentName ?? 'no department'}
                  </span>
                </button>
              </li>
            ))}
            {candidates.data.content.length === 0 && (
              <li className="text-muted">Nobody matches that name.</li>
            )}
          </ul>
        )}
        <WriteFailure error={actions.addMember.error} />
        <p className="mt-2 text-xs text-muted">
          Adding somebody moves them out of any other cohort. A person belongs to exactly one,
          which is what makes "assessed once a year, in a fixed quadrimester" a guarantee.
        </p>
      </div>
    </Card>
  )
}
