# Asgardeo setup

Console steps for the team to perform. Asgardeo is fixed by the stack — do not substitute another identity provider.

## What Asgardeo does, and what it does not

Asgardeo answers **"who is this?"**. It authenticates the user and signs a JWT whose `sub` claim the backend can then trust.

It does **not** decide what anyone may do here. The relationship and attribute rules — manager → direct reports, HR → granted departments minus their own, subject blind to peer authorship — cannot be expressed as identity-provider roles, because whether a manager may open a review depends on who that employee reports to, not on the caller holding the Manager role. Those decisions live in the application. See [authorization-policy.md](authorization-policy.md).

**Consequence for provisioning:** the backend derives a caller's authorities from the `user_role` table, not from the token's role claim. Roles in Asgardeo are for the login experience and for keeping one system of record; they grant nothing on their own. A token asserting `SUPER_ADMIN` for a user the database records as an employee gets employee access — there is a test for exactly that. Keep the two in sync anyway; the backend logs a warning on drift.

## 1. Register the application

Asgardeo Console → **Applications** → **New Application** → **Single-Page Application**.

| Field | Value |
|---|---|
| Name | `Altrium` |
| Authorized redirect URL | `http://localhost:5173` (Vite dev server) |
| Allowed origins | `http://localhost:5173` |

SPA gives you Authorization Code + PKCE with no client secret, which is what `@asgardeo/auth-react` expects. **Do not** switch it to a traditional web app to obtain a secret — a secret in a browser bundle is not a secret.

## 2. Emit roles in the token

Roles are not included by default.

1. **Applications → Altrium → User Attributes** → add the **Roles** attribute.
2. **Applications → Altrium → Protocol** → confirm `roles` is in the requested scopes.
3. Check the issued token contains a `roles` claim.

Asgardeo prefixes role names by where they are managed — `Internal/everyone`, `Application/HR`. The backend strips everything before the last `/` and matches the remainder case-insensitively against its `Role` enum, ignoring anything that does not match (`Internal/everyone` is discarded harmlessly).

This tenant advertises **both** a `roles` claim and an `application_roles` claim; which one is populated depends on the application's user-attribute configuration. The backend reads both, so a console change cannot silently stop roles being seen.

## 3. Create the roles

**User Management → Roles** → create, with audience **Application → Altrium**:

`EMPLOYEE` · `MANAGER` · `HR` · `LEADERSHIP` · `SUPER_ADMIN`

Roles are additive: everyone gets `EMPLOYEE` in addition to whatever else they hold (P-0.1). The backend grants `ROLE_EMPLOYEE` to every provisioned active user regardless, so a provisioning slip cannot leave someone with no access at all.

## 4. Create users

Each Altrium person needs an Asgardeo user **and** an `app_user` row. The link between them is the `sub` claim stored in `app_user.asgardeo_subject`.

A user who exists in Asgardeo but not in `app_user` authenticates successfully and is then refused **403** by every endpoint — deliberately, so the response does not reveal that the account merely is not provisioned.

> Find a user's `sub` in the Asgardeo console under **User Management → Users → (user) → Profile → User ID**.

## 5. Wire up the backend

**Our tenant is `pasindudilshan`**, and its endpoints are confirmed live:

| | |
|---|---|
| Issuer | `https://api.asgardeo.io/t/pasindudilshan/oauth2/token` |
| JWKS | `https://api.asgardeo.io/t/pasindudilshan/oauth2/jwks` |
| Authorize | `https://api.asgardeo.io/t/pasindudilshan/oauth2/authorize` |
| End session | `https://api.asgardeo.io/t/pasindudilshan/oidc/logout` |

The issuer is a public identifier, not a credential, so it is committed as the default in `application.yml` and the backend starts with no extra configuration. Override only to point at a different tenant:

```powershell
$env:ASGARDEO_ISSUER_URI = 'https://api.asgardeo.io/t/<other-tenant>/oauth2/token'
```

The backend holds **no client secret**. It fetches signing keys from `jwks_uri`, which Spring discovers from the issuer. Re-check discovery any time login breaks:

```powershell
Invoke-RestMethod https://api.asgardeo.io/t/pasindudilshan/oauth2/token/.well-known/openid-configuration
```

## 6. Frontend (Sprint 1, after the backend)

`@asgardeo/auth-react`, configured as:

```ts
{
  signInRedirectURL: "http://localhost:5173",
  signOutRedirectURL: "http://localhost:5173",
  clientID: "4fnWPKRrxnUyy4wxULpkw4nOhnoa",
  baseUrl: "https://api.asgardeo.io/t/pasindudilshan",
  scope: ["openid", "profile", "roles"]
}
```

**Logout must call the end-session endpoint**, not merely clear local tokens. Clearing tokens leaves the Asgardeo session alive, so the next login silently reuses it and the user appears unable to sign out.

## What to hand over

| Value | Status | Used by |
|---|---|---|
| Tenant name | ✅ `pasindudilshan` | Both |
| Issuer URI | ✅ committed as the default | Backend |
| Client ID | ✅ `4fnWPKRrxnUyy4wxULpkw4nOhnoa` | Frontend only |
| Each user's `sub` | **still needed** — User Management → Users → Profile → User ID | `app_user.asgardeo_subject` |

The client ID is a **public identifier, not a credential**. A PKCE single-page app has no client secret precisely because anything shipped in a browser bundle is readable, so committing it is correct and changes nothing about security. If anyone hands you a client *secret* for this application, the application type has been changed to the wrong kind — go back to step 1.

No client secret is needed anywhere. If you find yourself copying one, the application type is wrong.

## Testing without a tenant

The test suite never contacts Asgardeo. A stub `JwtDecoder` replaces **signature verification only** — the resource-server filter chain, the converter and every authority check are the real production path, because a denial test that stubbed the security layer would prove nothing.
