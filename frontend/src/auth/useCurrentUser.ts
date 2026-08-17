import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Me, Role } from '../api/types'

/**
 * Who the caller is, according to the **server**.
 *
 * Not according to the token. The backend derives authorities from the `user_role` table and
 * ignores the token's role claim entirely - there is a test asserting that a token claiming
 * `SUPER_ADMIN` for someone the database records as an employee gets employee access. A UI
 * that read roles out of the ID token could therefore disagree with the server about who the
 * user is, and would disagree in the direction of showing them more.
 *
 * Retry is off: the interesting failures here are 403 for an unprovisioned or deactivated
 * account, and retrying those three times only delays telling the person what is wrong.
 */
export function useCurrentUser() {
  return useQuery({
    queryKey: ['me'],
    queryFn: () => api.get<Me>('/api/me'),
    retry: false,
    staleTime: 5 * 60 * 1000,
  })
}

/**
 * Whether the caller holds a role - for **navigation only**.
 *
 * Every use of this is cosmetic by definition. It decides which links are worth showing
 * somebody; it decides nothing about access, and the endpoint behind each link refuses on
 * its own. Hiding a control is not access control, and this function is not to be used as
 * though it were: no screen guards data with it, only menus.
 */
export function hasRole(roles: Role[] | undefined, role: Role): boolean {
  return roles?.includes(role) ?? false
}
