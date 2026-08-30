/**
 * Where the app points, with the working defaults committed.
 *
 * The client ID is a **public identifier, not a credential**. A PKCE single-page app has no
 * client secret precisely because anything in a browser bundle is readable, so committing it
 * is correct and changes nothing about security. This mirrors the backend, which commits its
 * Asgardeo issuer URI in `application.yml` for the same reason.
 *
 * The redirect origin is **build-time configuration, never guessed at runtime** from
 * `window.location`. Asgardeo has to have registered the exact origin, so a value derived from
 * whatever host the page happens to be served on would fail at the redirect with an error that
 * points at the identity provider rather than at the build. `VITE_APP_ORIGIN` sets it for a
 * deployment; the default is the development origin, which is why `npm run dev` needs no `.env`.
 *
 * Whatever it is set to must be registered in the Asgardeo console as both an authorised
 * redirect URL and an allowed origin. Both the local and the deployed origins are registered
 * there, so neither breaks the other.
 */

import type { AuthReactConfig } from '@asgardeo/auth-react'

const origin = import.meta.env.VITE_APP_ORIGIN ?? 'http://localhost:5173'

/**
 * Whether this browser can verify the ID token signature at all.
 *
 * The Asgardeo SDK verifies through `jose`, and `jose` verifies through WebCrypto. But
 * `crypto.subtle` is defined **only in a secure context** - HTTPS, or localhost. The demo host
 * is plain HTTP on an EC2 address, so there the verify throws and `signIn()` rejects *after* a
 * perfectly good token exchange: the app returns from Asgardeo holding valid tokens and drops
 * straight back to the login screen. PKCE still works there, because the same SDK hashes the
 * code challenge with a pure-JS sha256 that needs no WebCrypto - which is why the failure
 * looks like nothing is wrong right up until the last step.
 *
 * So the check is skipped exactly where it cannot run, rather than switched off by hand.
 * Locally and behind HTTPS it stays on, and it turns itself back on the day this is served
 * over TLS.
 *
 * **What is lost, stated plainly.** The ID token still arrives over TLS from Asgardeo's token
 * endpoint, in response to a PKCE exchange this app started, so it is not unauthenticated - the
 * signature check is defence in depth against a compromised transport. And it was never the
 * access control: the backend validates every access token against Asgardeo's JWKS on every
 * request, and that is untouched. A browser cannot grant itself anything by believing a token.
 *
 * The real fix is HTTPS, which also fixes the larger problem this sits inside: on a plain HTTP
 * host the bearer token travels to the API in clear text.
 */
const canVerifyTokenSignature = typeof crypto !== 'undefined' && crypto.subtle !== undefined

export const config: { asgardeo: AuthReactConfig; apiBaseUrl: string } = {
  asgardeo: {
    clientID: import.meta.env.VITE_ASGARDEO_CLIENT_ID ?? '4fnWPKRrxnUyy4wxULpkw4nOhnoa',
    baseUrl: import.meta.env.VITE_ASGARDEO_BASE_URL ?? 'https://api.asgardeo.io/t/pasindudilshan',
    signInRedirectURL: origin,
    signOutRedirectURL: origin,
    scope: ['openid', 'profile', 'roles'],
    validateIDToken: canVerifyTokenSignature,
  },
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
}
