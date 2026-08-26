import { useState } from 'react'
import { useDepartmentActions, useDepartments } from '../../api/admin'
import { Button, Card, Field, TextInput, WriteFailure } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'
import { AdminNav } from './AdminNav'

/**
 * Departments.
 *
 * Small screen, load-bearing data: a department is the unit HR grants are written against, so
 * renaming one changes what a grant reads as, and there is deliberately no delete. A
 * department with people and grants attached cannot be removed without deciding what happens
 * to both, and that is a question nobody has asked yet.
 */
export function Departments() {
  const { data: departments, isPending, error } = useDepartments()
  const actions = useDepartmentActions()
  const [name, setName] = useState('')
  const [editing, setEditing] = useState<{ id: number; name: string } | null>(null)

  return (
    <>
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Administration</h1>
      <AdminNav />

      <div className="grid gap-4">
        <Card title="Add a department">
          <div className="flex items-end gap-3">
            <div className="w-72">
              <Field label="Name">
                <TextInput value={name} onChange={(e) => setName(e.target.value)} />
              </Field>
            </div>
            <Button
              variant="primary"
              disabled={!name.trim()}
              busy={actions.create.isPending}
              busyLabel="Creating"
              onClick={() => actions.create.mutate(name, { onSuccess: () => setName('') })}
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
        ) : departments.length === 0 ? (
          <EmptyState>No departments yet.</EmptyState>
        ) : (
          <Card title="Departments">
            <ul className="grid gap-2 text-sm">
              {departments.map((department) => (
                <li
                  key={department.id}
                  className="flex items-center gap-3 rounded border border-line p-3"
                >
                  {editing?.id === department.id ? (
                    <>
                      <TextInput
                        value={editing.name}
                        onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                      />
                      <Button
                        variant="primary"
                        busy={actions.rename.isPending}
                        busyLabel="Saving"
                        onClick={() =>
                          actions.rename.mutate(editing, { onSuccess: () => setEditing(null) })
                        }
                      >
                        Save
                      </Button>
                      <Button onClick={() => setEditing(null)}>Cancel</Button>
                    </>
                  ) : (
                    <>
                      <span>{department.name}</span>
                      <span className="ml-auto">
                        <Button onClick={() => setEditing({ ...department })}>Rename</Button>
                      </span>
                    </>
                  )}
                </li>
              ))}
            </ul>
            <WriteFailure error={actions.rename.error} />
          </Card>
        )}
      </div>
    </>
  )
}
