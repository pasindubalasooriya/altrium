/**
 * How the app recovers from a session that has ended.
 *
 * Injected rather than imported, for the same reason the token provider is: the Asgardeo SDK
 * spawns a Web Worker, and pulling it into a module that nearly every screen imports takes the
 * whole test suite down with it. Screens call {@link recoverSession}; only the auth provider
 * knows what that means.
 *
 * <h2>Why a reload alone does not work</h2>
 *
 * When the access token can no longer be refreshed, the SDK still reports the session as
 * authenticated - the flag it restores from storage and the token's usability are two different
 * facts. So a reload passes straight through the sign-in gate, fires the same request, fails
 * the same way, and lands back on the same screen. Pressing the button again does it again.
 *
 * `signOut()` is what clears it, because it ends the session at Asgardeo rather than dropping
 * local tokens. Dropping tokens alone leaves the identity provider's session alive, and the
 * person appears unable to sign out at all.
 *
 * <h2>Why there is a fallback under it</h2>
 *
 * `signOut()` itself can fail on a session that is already broken - it may need a valid id
 * token to build the end-session request. If recovery can fail, it is not recovery: the person
 * is left on a dead screen with a button that does nothing. So a failure falls through to
 * clearing the SDK's stored session by hand and reloading, which cannot fail. The identity
 * provider's own session may outlive that and sign them straight back in, which is the right
 * outcome anyway - what they needed was a working token, not necessarily a login prompt.
 */

type Recovery = () => void | Promise<void>

/** Everything the SDK persists is keyed on this. Nothing else in the app writes there. */
function clearStoredSession(): void {
  for (const store of [window.sessionStorage, window.localStorage]) {
    try {
      const doomed = Object.keys(store).filter((key) => key.toLowerCase().includes('asgardeo'))
      doomed.forEach((key) => store.removeItem(key))
    } catch {
      // A browser with site data blocked. Nothing stored means nothing to clear.
    }
  }
}

const fallback: Recovery = () => {
  clearStoredSession()
  window.location.reload()
}

let recover: Recovery = fallback

/** Called once, from the auth provider, with the SDK's real sign-out. */
export function installSessionRecovery(next: Recovery): void {
  recover = next
}

export function recoverSession(): void {
  void (async () => {
    try {
      await recover()
    } catch (cause) {
      console.warn('[altrium] sign-out failed; clearing the stored session instead', cause)
      fallback()
    }
  })()
}
