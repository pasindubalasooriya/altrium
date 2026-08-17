import { AuthProvider, useAuthContext } from '@asgardeo/auth-react'
import { useEffect, type ReactNode } from 'react'
import { config } from '../config'
import { installTokenProvider } from '../api/client'

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
  const { getAccessToken } = useAuthContext()

  useEffect(() => {
    installTokenProvider(() => getAccessToken())
  }, [getAccessToken])

  return <>{children}</>
}
