import { AuthProvider, useAuthContext } from '@asgardeo/auth-react'
import type { ReactNode } from 'react'
import { config } from '../config'
import { installTokenProvider } from '../api/client'
import { installSessionRecovery } from './session'

/**
 * Asgardeo, wired once.
 *
 * The SDK owns the authorization-code-with-PKCE dance and the token store. This wrapper does
 * two things on top: it hands the API client a way to get the current access token, and it
 * makes sure nothing else in the app has to know the SDK exists.
 */
export function AltriumAuthProvider({ children }: { children: ReactNode }) {
  return (
    <AuthProvider config={config.asgardeo}>
      <TokenBridge>{children}</TokenBridge>
    </AuthProvider>
  )
}

/**
 * Connects the SDK's token store to the API client.
 *
 * `getAccessToken` is asked for on every request rather than captured once, because the SDK
 * refreshes tokens underneath us. A token read at mount and reused would work for exactly as
 * long as the first one lasted, and then fail as a 401 on a screen that had been fine a
 * moment earlier - the kind of bug that looks like a backend problem.
 */
function TokenBridge({ children }: { children: ReactNode }) {
  const { getAccessToken, signOut } = useAuthContext()

  // Installed during render, and deliberately not from an effect.
  //
  // React runs effects child-first, parent-last. This component wraps the entire app, so an
  // effect here runs *after* every screen below it has mounted and fired its first query -
  // and those queries then found no provider. On a first sign-in the loading state delayed the
  // screens long enough to hide it; on a refresh the SDK restores the session before the first
  // render, the screens mount immediately, and every one of them failed. The failure was
  // reported as an ended session, which the session emphatically had not.
  //
  // Both calls are idempotent assignments, so running them on every render costs nothing and
  // keeps them pointed at the current SDK callbacks, which is what the token bridge needs
  // anyway: the SDK refreshes tokens underneath us, and a callback captured once would work
  // for exactly as long as the first token lasted.
  installTokenProvider(() => getAccessToken())

  // Recovery from a dead session is a full sign-out, not a reload. Once the token can no longer
  // be refreshed the SDK still reports the session as authenticated, so a reload passes the
  // sign-in gate and fails again on the first request. signOut ends the session at Asgardeo,
  // which is the only thing that actually clears it.
  installSessionRecovery(async () => {
    await signOut()
  })

  return <>{children}</>
}
