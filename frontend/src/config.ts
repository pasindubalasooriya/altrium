/**
 * Where the app points, with the working defaults committed.
 *
 * The client ID is a **public identifier, not a credential**. A PKCE single-page app has no
 * client secret precisely because anything in a browser bundle is readable, so committing it
 * is correct and changes nothing about security. This mirrors the backend, which commits its
 * Asgardeo issuer URI in `application.yml` for the same reason.
 *
 * The redirect URLs are pinned to `http://localhost:5173` because that exact origin is what
 * the Asgardeo console has registered and what the backend's CORS configuration allows. All
 * three have to agree, so none of them is guessed at runtime from `window.location`.
 */

import type { AuthReactConfig } from '@asgardeo/auth-react'

const origin = 'http://localhost:5173'

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
