import { useAuthContext } from '@asgardeo/auth-react'
import type { ReactNode } from 'react'
import { Loading } from '../components/States'

/**
 * The sign-in gate.
 *
 * This is the only thing in the app that decides anything before the server is asked, and it
 * decides only whether there is a session at all. It is not authorization: an authenticated
 * caller with no Altrium record gets past here and is then refused by every endpoint, which
 * is exactly what the backend intends - the response must not reveal that the account merely
 * is not provisioned.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { state, signIn } = useAuthContext()

  if (state.isLoading) {
    return <Loading what="Checking your session" />
  }

  if (!state.isAuthenticated) {
    return <SignIn onSignIn={() => void signIn()} />
  }

  return <>{children}</>
}

function SignIn({ onSignIn }: { onSignIn: () => void }) {
  return (
    <div className="flex min-h-full items-center justify-center p-6">
      <section className="w-full max-w-sm rounded border border-line bg-white p-8 text-center">
        <h1 className="mb-1 text-xl font-semibold tracking-tight">Altrium</h1>
        <p className="mb-6 text-sm text-muted">Performance and development reviews</p>
        <button
          type="button"
          className="w-full rounded bg-accent px-4 py-2 text-white"
          onClick={onSignIn}
        >
          Sign in
        </button>
      </section>
    </div>
  )
}
