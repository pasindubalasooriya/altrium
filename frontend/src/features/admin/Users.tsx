import { useState } from 'react'
import {
  useDepartments,
  useUserActions,
  useUsers,
  type AdminUser,
  type UserFilters,
} from '../../api/admin'
import { Button, Card, Field, Select, TextInput, WriteFailure } from '../../components/Form'
import { Pager, usePaging } from '../../components/Pager'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import type { Role } from '../../api/types'
import { AdminNav } from './AdminNav'
import { ManagerPicker } from './ManagerPicker'

const ROLES: Role[] = ['EMPLOYEE', 'MANAGER', 'HR', 'LEADERSHIP', 'SUPER_ADMIN']

/**
 * The user console.
 *
 * **Paged and filtered on the server**, which the stack calls out specifically: nothing may
 * assume a small organisation, and loading every employee into one table does not hold at a
 * hundred people, let alone beyond.
 *
 * Nothing here shows review, rating or plan content, and there is no link to any (P-9.4).
 */
export function Users() {
  const [filters, setFilters] = useState<UserFilters>({})
  const { page, size, setPage } = usePaging()
  const { data: users, isPending, error } = useUsers(filters, page, size)
  const { data: departments } = useDepartments()
  const [editing, setEditing] = useState<AdminUser | null>(null)
  const [creating, setCreating] = useState(false)

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Administration</h1>
      <AdminNav />

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <div className="w-64">
          <Field label="Search">
            <TextInput
              placeholder="Name or email"
              onChange={(e) => {
                setFilters((f) => ({ ...f, search: e.target.value }))
                setPage(0)
              }}
            />
          </Field>
        </div>
        <div className="w-56">
          <Field label="Department">
            <Select
              onChange={(e) => {
                setFilters((f) => ({
                  ...f,
                  departmentId: e.target.value ? Number(e.target.value) : undefined,
                }))
                setPage(0)
              }}
            >
              <option value="">All</option>
              {departments?.map((department) => (
                <option key={department.id} value={department.id}>
                  {department.name}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="w-40">
          <Field label="Status">
            <Select
              onChange={(e) => {
                const value = e.target.value
                setFilters((f) => ({
                  ...f,
                  active: value === '' ? undefined : value === 'active',
                }))
                setPage(0)
              }}
            >
              <option value="">All</option>
              <option value="active">Active</option>
              <option value="inactive">Deactivated</option>
            </Select>
          </Field>
        </div>
        <Button variant="primary" onClick={() => setCreating(true)}>
          Add a person
        </Button>
      </div>

      {creating && (
        <div className="mb-4">
          <CreateUser onDone={() => setCreating(false)} />
        </div>
      )}

      {editing && (
        <div className="mb-4">
          <EditUser user={editing} onDone={() => setEditing(null)} />
        </div>
      )}

      {isPending ? (
        <Loading />
      ) : error ? (
        <QueryFailure error={error} />
      ) : users.content.length === 0 ? (
        <EmptyState>Nobody matches those filters.</EmptyState>
      ) : (
        <div className="rounded border border-line bg-white">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-line text-left text-muted">
                <tr>
                  <th className="p-3 font-medium">Name</th>
                  <th className="p-3 font-medium">Department</th>
                  <th className="p-3 font-medium">Reports to</th>
                  <th className="p-3 font-medium">Roles</th>
                  <th className="p-3 font-medium"> </th>
                </tr>
              </thead>
              <tbody>
                {users.content.map((user) => (
                  <UserRow
                    key={user.id}
                    user={user}
                    editing={editing?.id === user.id}
                    onEdit={() => setEditing(editing?.id === user.id ? null : user)}
                  />
                ))}
              </tbody>
            </table>
          </div>
          <Pager
            page={users.page}
            totalPages={users.totalPages}
            totalElements={users.totalElements}
            onPage={setPage}
          />
        </div>
      )}
    </>
  )
}

function UserRow({
  user,
  editing,
  onEdit,
}: {
  user: AdminUser
  editing: boolean
  onEdit: () => void
}) {
  const actions = useUserActions()
  const [confirming, setConfirming] = useState(false)
  const result = actions.deactivate.data

  return (
    <>
      {/*
        The row being edited is marked, because the edit panel sits above the table and at a
        hundred people it is otherwise possible to scroll it out of view and lose track of whose
        details are open in it.
      */}
      <tr
        className={`border-b border-line/60 last:border-0 ${editing ? 'bg-brand/10' : ''}`}
        aria-selected={editing || undefined}
      >
        <td className="p-3">
          {user.fullName}
          {!user.active && <span className="ml-2 text-xs text-warn">deactivated</span>}
          <span className="block text-xs text-muted">{user.email}</span>
        </td>
        <td className="p-3 text-muted">{user.departmentName ?? '-'}</td>
        <td className="p-3 text-muted">{user.managerName ?? 'nobody'}</td>
        <td className="p-3 text-xs text-muted">{user.roles.join(', ')}</td>
        <td className="p-3 text-right">
          <Button variant={editing ? 'primary' : 'plain'} onClick={onEdit}>
            {editing ? 'Editing' : 'Edit'}
          </Button>
          {user.active ? (
            <span className="ml-2 inline-block">
              <Button
                variant="danger"
                // Confirming is the pending state here. The click opens the explanation row
                // below rather than writing anything, so the button reflects that it is now
                // waiting on the person, not on the server.
                disabled={confirming}
                onClick={() => setConfirming(true)}
              >
                Deactivate
              </Button>
            </span>
          ) : (
            <span className="ml-2 inline-block">
              <Button
                busy={actions.reactivate.isPending}
                busyLabel="Reactivating"
                onClick={() => actions.reactivate.mutate(user.id)}
              >
                Reactivate
              </Button>
            </span>
          )}
        </td>
      </tr>

      {actions.reactivate.error != null && (
        <tr>
          <td colSpan={5} className="px-3 pb-3">
            <WriteFailure error={actions.reactivate.error} />
          </td>
        </tr>
      )}

      {confirming && (
        <tr>
          <td colSpan={5} className="bg-warn/5 p-4">
            <p className="text-sm">
              {/*
                Says what actually happens. "Delete" would misdescribe it, and the history and
                carry-over pillar depends on the row surviving - a deactivated person keeps
                their reviews, their plan and their goals.
              */}
              Deactivating {user.fullName} sets a flag. Their record, reviews and plans are
              kept, and they drop out of peer selection and any new cycle. They cannot sign in.
            </p>
            {result?.warning && <p className="mt-2 text-sm text-warn">{result.warning}</p>}
            <WriteFailure error={actions.deactivate.error} />
            <div className="mt-3 flex gap-2">
              <Button
                variant="danger"
                busy={actions.deactivate.isPending}
                busyLabel="Deactivating"
                onClick={() =>
                  actions.deactivate.mutate(user.id, { onSuccess: () => setConfirming(false) })
                }
              >
                Deactivate
              </Button>
              <Button disabled={actions.deactivate.isPending} onClick={() => setConfirming(false)}>
                Cancel
              </Button>
            </div>
          </td>
        </tr>
      )}
    </>
  )
}

function CreateUser({ onDone }: { onDone: () => void }) {
  const actions = useUserActions()
  const { data: departments } = useDepartments()
  const [form, setForm] = useState({
    asgardeoSubject: '',
    email: '',
    fullName: '',
    departmentId: '',
    roles: ['EMPLOYEE'] as Role[],
  })

  return (
    <Card title="Add a person">
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Full name">
          <TextInput
            value={form.fullName}
            onChange={(e) => setForm({ ...form, fullName: e.target.value })}
          />
        </Field>
        <Field label="Email">
          <TextInput
            value={form.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}
          />
        </Field>
        <Field
          label="Asgardeo user ID"
          hint="The sub claim. Console: User Management, Users, Profile, User ID."
        >
          <TextInput
            value={form.asgardeoSubject}
            onChange={(e) => setForm({ ...form, asgardeoSubject: e.target.value })}
          />
        </Field>
        <Field label="Department">
          <Select
            value={form.departmentId}
            onChange={(e) => setForm({ ...form, departmentId: e.target.value })}
          >
            <option value="">None</option>
            {departments?.map((department) => (
              <option key={department.id} value={department.id}>
                {department.name}
              </option>
            ))}
          </Select>
        </Field>
      </div>

      <RolePicker
        roles={form.roles}
        onChange={(roles) => setForm({ ...form, roles })}
      />

      <WriteFailure error={actions.create.error} />

      <div className="mt-3 flex gap-2">
        <Button
          variant="primary"
          busy={actions.create.isPending}
          busyLabel="Creating"
          onClick={() =>
            actions.create.mutate(
              {
                asgardeoSubject: form.asgardeoSubject,
                email: form.email,
                fullName: form.fullName,
                departmentId: form.departmentId ? Number(form.departmentId) : null,
                managerId: null,
                roles: form.roles,
              },
              { onSuccess: onDone },
            )
          }
        >
          Create
        </Button>
        <Button disabled={actions.create.isPending} onClick={onDone}>
          Cancel
        </Button>
      </div>
    </Card>
  )
}

function EditUser({ user, onDone }: { user: AdminUser; onDone: () => void }) {
  const actions = useUserActions()
  const { data: departments } = useDepartments()
  const [form, setForm] = useState({
    email: user.email,
    fullName: user.fullName,
    departmentId: user.departmentId ? String(user.departmentId) : '',
    roles: user.roles,
  })
  const [manager, setManager] = useState<{ id: number | null; name: string | null }>({
    id: user.managerId,
    name: user.managerName,
  })
  const saving = actions.update.isPending || actions.setManager.isPending

  return (
    <Card title={`Edit ${user.fullName}`}>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Full name">
          <TextInput
            value={form.fullName}
            onChange={(e) => setForm({ ...form, fullName: e.target.value })}
          />
        </Field>
        <Field label="Email">
          <TextInput
            value={form.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}
          />
        </Field>
        <Field label="Department">
          <Select
            value={form.departmentId}
            onChange={(e) => setForm({ ...form, departmentId: e.target.value })}
          >
            <option value="">None</option>
            {departments?.map((department) => (
              <option key={department.id} value={department.id}>
                {department.name}
              </option>
            ))}
          </Select>
        </Field>
      </div>

      {/*
        Out of the two-column grid on purpose: the picker is a search box over a list, and
        pairing it with a single-line input would leave whichever column it sat in stretched to
        its height with the other half empty.
      */}
      <div className="mt-3">
        <Field
          label="Reports to"
          hint="People this person manages, directly or further down, are not offered - that would be a loop."
        >
          <ManagerPicker
            userId={user.id}
            value={manager.id}
            valueName={manager.name}
            onChange={(id, name) => setManager({ id, name })}
          />
        </Field>
      </div>

      <RolePicker roles={form.roles} onChange={(roles) => setForm({ ...form, roles })} />

      {/*
        The loop rejection is a 400 and would render here, but through this console it should
        no longer be reachable: the candidate list carries the same exclusions the write does,
        so a choice that would close a loop is never offered. What does still land here is the
        race the list cannot pre-empt - somebody deactivated between this panel opening and the
        save - along with any 400 from the fields above.
      */}
      <WriteFailure error={actions.update.error ?? actions.setManager.error} />

      <div className="mt-3 flex gap-2">
        <Button
          variant="primary"
          busy={saving}
          busyLabel="Saving"
          onClick={() => {
            // Sequential, and the panel closes only when both have succeeded. Fired in
            // parallel, a rejected reporting line - the server walks the whole chain for a
            // loop, which no client-side guess can do - would still have closed the panel,
            // because only the second call carried the close and it did not know the first had
            // failed. The person would have been told nothing and half their edit would have
            // landed.
            void (async () => {
              try {
                await actions.update.mutateAsync({
                  id: user.id,
                  email: form.email,
                  fullName: form.fullName,
                  departmentId: form.departmentId ? Number(form.departmentId) : null,
                  roles: form.roles,
                })
                await actions.setManager.mutateAsync({
                  id: user.id,
                  managerId: manager.id,
                })
                onDone()
              } catch {
                // Already rendered by WriteFailure above; the panel stays open so the value
                // that was refused is still on screen to be corrected.
              }
            })()
          }}
        >
          Save
        </Button>
        <Button disabled={saving} onClick={onDone}>
          Cancel
        </Button>
      </div>
    </Card>
  )
}

/**
 * Roles are additive (P-0.1), so this is a set of checkboxes and never a single choice.
 * Employee is always included by the server whatever is sent, which the hint says.
 */
function RolePicker({ roles, onChange }: { roles: Role[]; onChange: (roles: Role[]) => void }) {
  return (
    <div className="mt-3">
      <p className="mb-1 text-sm font-medium">Roles</p>
      <p className="mb-2 text-xs text-muted">
        Additive. Everyone is an employee as well as whatever else they hold.
      </p>
      <div className="flex flex-wrap gap-3 text-sm">
        {ROLES.map((role) => (
          <label key={role} className="flex items-center gap-1">
            <input
              type="checkbox"
              checked={roles.includes(role)}
              onChange={(e) =>
                onChange(
                  e.target.checked ? [...roles, role] : roles.filter((held) => held !== role),
                )
              }
            />
            {role.toLowerCase().replace('_', ' ')}
          </label>
        ))}
      </div>
    </div>
  )
}
