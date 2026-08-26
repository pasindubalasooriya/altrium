import { useState } from 'react'
import {
  useDepartments,
  useGrantActions,
  useGrants,
  useUsers,
  type AdminUser,
} from '../../api/admin'
import { Button, Card, Fact, Field, Select, WriteFailure, when } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import { AdminNav } from './AdminNav'

/**
 * HR department grants - the configuration behind the whole HR half of the authorization
 * model (P-2.1 to P-2.5).
 *
 * Two things this screen has to make legible, because both are easy to get wrong from a form:
 *
 * - **The explicit-grant flag is a property of one grant, not a role.** It lifts the
 *   own-department block for that department only. There is no "make this person an HR Head"
 *   anywhere, and there should not be: the HR Head is a person holding an explicit grant, and
 *   naming it as a role would invite somebody to implement it as one.
 * - **It never lifts the own-review block.** No grant, flag or role lets an HR user act on
 *   their own review, and the screen says so where the flag is set rather than in a document
 *   nobody reads at the moment of setting it.
 *
 * A revoked grant applies on the holder's very next request (P-2.5), with no re-login. Worth
 * stating here, because an administrator revoking access in an emergency needs to know whether
 * it has taken effect.
 */
export function HrGrants() {
  const [hrUser, setHrUser] = useState<AdminUser | null>(null)
  // Filtered to HR in the query, not here. Picking them out of a page of everybody would
  // quietly mean "the HR users on this page", which is correct at thirty people and wrong at
  // three hundred.
  const { data: users, isPending, error } = useUsers({ active: true, role: 'HR' }, 0, 100)
  const { data: departments } = useDepartments()
  const grants = useGrants(hrUser?.id)
  const actions = useGrantActions(hrUser?.id)
  const [departmentId, setDepartmentId] = useState('')
  const [explicit, setExplicit] = useState(false)

  const hrUsers = users?.content ?? []

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Administration</h1>
      <AdminNav />

      {isPending ? (
        <Loading />
      ) : error ? (
        <QueryFailure error={error} />
      ) : hrUsers.length === 0 ? (
        <EmptyState>
          Nobody holds the HR role yet. Give somebody the role on the Users tab first.
        </EmptyState>
      ) : (
        <div className="grid gap-4">
          <Card title="HR user">
            <div className="w-80">
              <Field label="Whose grants to manage">
                <Select
                  value={hrUser?.id ?? ''}
                  onChange={(e) =>
                    setHrUser(hrUsers.find((user) => user.id === Number(e.target.value)) ?? null)
                  }
                >
                  <option value="">Choose somebody</option>
                  {hrUsers.map((user) => (
                    <option key={user.id} value={user.id}>
                      {user.fullName} · {user.departmentName ?? 'no department'}
                    </option>
                  ))}
                </Select>
              </Field>
            </div>
            {hrUser && (
              <p className="mt-3 text-sm text-muted">
                {hrUser.fullName} is in {hrUser.departmentName ?? 'no department'}. Without an
                explicit grant over that department, they cannot act on reviews inside it.
              </p>
            )}
          </Card>

          {hrUser && (
            <>
              <Card title="Grants held">
                {grants.isPending ? (
                  <Loading />
                ) : grants.error ? (
                  <QueryFailure error={grants.error} />
                ) : grants.data.length === 0 ? (
                  <p className="text-sm text-muted">
                    No grants. {hrUser.fullName} can act on nothing at all as an HR user.
                  </p>
                ) : (
                  <ul className="grid gap-2 text-sm">
                    {grants.data.map((grant) => (
                      <li key={grant.id} className="rounded border border-line p-3">
                        <div className="flex flex-wrap items-center gap-3">
                          <span className="font-medium">{grant.departmentName}</span>
                          {grant.departmentId === hrUser.departmentId && (
                            <span className="text-xs text-warn">their own department</span>
                          )}
                          <span className="ml-auto flex items-center gap-3">
                            <label className="flex items-center gap-1 text-xs">
                              <input
                                type="checkbox"
                                checked={grant.explicitGrant}
                                disabled={actions.setExplicit.isPending}
                                onChange={(e) =>
                                  actions.setExplicit.mutate({
                                    departmentId: grant.departmentId,
                                    explicitGrant: e.target.checked,
                                  })
                                }
                              />
                              explicit grant
                            </label>
                            <Button
                              variant="danger"
                              // Per grant, not per screen - one mutation object serves every
                              // row, so a bare `isPending` would spin all of them.
                              busy={
                                actions.revoke.isPending &&
                                actions.revoke.variables === grant.departmentId
                              }
                              busyLabel="Revoking"
                              onClick={() => actions.revoke.mutate(grant.departmentId)}
                            >
                              Revoke
                            </Button>
                          </span>
                        </div>
                        <Fact label="Granted">
                          {when(grant.grantedAt)}
                          {grant.grantedByName ? ` by ${grant.grantedByName}` : ''}
                        </Fact>
                      </li>
                    ))}
                  </ul>
                )}

                <WriteFailure error={actions.setExplicit.error ?? actions.revoke.error} />

                <p className="mt-3 text-xs text-muted">
                  A revoked grant applies on {hrUser.fullName}’s very next request. They do not
                  need to sign out.
                </p>
              </Card>

              <Card title="Grant a department">
                <div className="flex flex-wrap items-end gap-3">
                  <div className="w-64">
                    <Field label="Department">
                      <Select
                        value={departmentId}
                        onChange={(e) => setDepartmentId(e.target.value)}
                      >
                        <option value="">Choose one</option>
                        {departments?.map((department) => (
                          <option key={department.id} value={department.id}>
                            {department.name}
                          </option>
                        ))}
                      </Select>
                    </Field>
                  </div>
                  <label className="flex items-center gap-2 pb-2 text-sm">
                    <input
                      type="checkbox"
                      checked={explicit}
                      onChange={(e) => setExplicit(e.target.checked)}
                    />
                    Explicit grant
                  </label>
                  <Button
                    variant="primary"
                    disabled={!departmentId}
                    busy={actions.grant.isPending}
                    busyLabel="Granting"
                    onClick={() =>
                      actions.grant.mutate(
                        { departmentId: Number(departmentId), explicitGrant: explicit },
                        { onSuccess: () => setDepartmentId('') },
                      )
                    }
                  >
                    Grant
                  </Button>
                </div>

                <WriteFailure error={actions.grant.error} />

                <div className="mt-4 rounded bg-line/30 p-3 text-sm">
                  <p className="font-medium">What the explicit grant does</p>
                  <p className="mt-1 text-muted">
                    It lets this HR user act inside their <em>own</em> department, which they
                    otherwise cannot. This is the HR Head mechanism, and it is a property of
                    this one grant rather than a role.
                  </p>
                  <p className="mt-2 text-muted">
                    {/*
                      Stated at the moment the flag is set, which is the only moment somebody
                      would think otherwise. P-2.2 has no override, and this is the screen where
                      an administrator might expect one.
                    */}
                    It does <strong>not</strong> let them reach their own review, rating or
                    plan. Nothing does.
                  </p>
                </div>
              </Card>
            </>
          )}
        </div>
      )}
    </>
  )
}
