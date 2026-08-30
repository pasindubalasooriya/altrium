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

export const config: { asgardeo: AuthReactConfig; apiBaseUrl: string } = {
  asgardeo: {
    clientID: import.meta.env.VITE_ASGARDEO_CLIENT_ID ?? '4fnWPKRrxnUyy4wxULpkw4nOhnoa',
    baseUrl: import.meta.env.VITE_ASGARDEO_BASE_URL ?? 'https://api.asgardeo.io/t/pasindudilshan',
    signInRedirectURL: origin,
    signOutRedirectURL: origin,
    scope: ['openid', 'profile', 'roles'],
  },
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
}
