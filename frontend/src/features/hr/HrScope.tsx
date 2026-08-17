import { useHrScope } from '../../api/hr'
import { Card } from '../../components/Form'
import { EmptyState, Loading, QueryFailure } from '../../components/States'

/**
 * The shape of an HR user's own authority.
 *
 * More than a courtesy screen. HR scoping has three rules that interact - the granted
 * departments, the own-department block, and the explicit-grant override that lifts it - and
 * an HR user who cannot see which apply to them experiences the system as arbitrary. The
 * department that is missing from their list is the one they will ask about.
 *
 * It is also where **P-2.5 is demonstrable**. Grants are resolved from the database on every
 * request and never cached at login, so revoking one in the admin console and refreshing this
 * page shows it gone, with no re-login. That is a claim worth being able to show rather than
 * assert.
 *
 * The explanatory sentence comes from the server. Composing a second wording here would give
 * the two places to disagree about a rule that is already subtle.
 */
export function HrScope() {
  const { data: scope, isPending, error } = useHrScope()

  if (isPending) {
    return <Loading />
  }
  if (error) {
    return <QueryFailure error={error} />
  }

  return (
    <>
      <h1 className="mb-1 text-xl font-semibold tracking-tight">My HR scope</h1>
      <p className="mb-6 text-sm text-muted">
        Resolved fresh on every request. A change made by an administrator applies to your very
        next action, with no need to sign in again.
      </p>

      <div className="grid gap-4">
        <Card title="Departments you can act in">
          {scope.departments.length === 0 ? (
            <EmptyState>
              You hold no department grants. An administrator assigns these.
            </EmptyState>
          ) : (
            <ul className="grid gap-2 text-sm">
              {scope.departments.map((department) => (
                <li
                  key={department.id}
                  className="flex items-center justify-between rounded border border-line p-3"
                >
                  <span>{department.name}</span>
                  {department.isOwnDepartment && (
                    <span className="text-xs text-accent">
                      your own department, by explicit grant
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Your own department">
          <p className="text-sm">{scope.note}</p>
          {scope.ownDepartmentExcluded && (
            <p className="mt-2 text-xs text-muted">
              This is not a configuration mistake. HR do not act on reviews in their own
              department unless an administrator grants it explicitly.
            </p>
          )}
        </Card>

        <Card title="Your own review">
          {/*
            Stated on the screen because it is the rule people find surprising, and because
            an HR user hitting a 403 on their own record should already know why. It is
            absolute: no grant, flag or role reaches it.
          */}
          <p className="text-sm">
            You can never act on your own review, rating or plan as an HR user, whatever grants
            you hold. Your own rating reaches you the way it reaches everybody else, on{' '}
            <span className="font-medium">My rating</span>, once it has been shared with you.
          </p>
        </Card>
      </div>
    </>
  )
}
