import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { Cycle, PageView, Role } from './types'

/**
 * Super Admin data access.
 *
 * **Nothing in this module touches a review, a rating or a plan** (P-9.4). The Super Admin
 * manages people, structure, grants and cycle configuration; they read none of what those
 * things are for. Since they are also the role that grants HR their departments, review access
 * on top would make the role omnipotent - so the absence of any such hook here is the point,
 * not an omission.
 */

export interface AdminUser {
  id: number
  email: string
  fullName: string
  departmentId: number | null
  departmentName: string | null
  managerId: number | null
  managerName: string | null
  active: boolean
  roles: Role[]
}

export interface Department {
  id: number
  name: string
}

/**
 * `role` is applied in the SQL, not to a fetched page.
 *
 * The HR-grants screen needs "the HR users", and picking them out of a page of everybody would
 * quietly mean "the HR users who happen to be on this page" - correct at thirty people and
 * wrong at three hundred. Nothing may be hardcoded to the size of the organisation.
 */
export interface UserFilters {
  search?: string
  departmentId?: number
  active?: boolean
  role?: Role
  /** False for people not in any cohort, true for those already placed. A SQL predicate. */
  inCohort?: boolean
}

export function useUsers(filters: UserFilters, page: number, size = 25) {
  return useQuery({
    queryKey: ['admin-users', filters, page, size],
    queryFn: () =>
      api.get<PageView<AdminUser>>('/api/admin/users', {
        search: filters.search || undefined,
        departmentId: filters.departmentId,
        active: filters.active,
        role: filters.role,
        inCohort: filters.inCohort,
        page,
        size,
      }),
  })
}

export function useDirectReports(userId: number | undefined) {
  return useQuery({
    queryKey: ['direct-reports', userId],
    queryFn: () => api.get<AdminUser[]>(`/api/admin/users/${userId}/direct-reports`),
    enabled: userId !== undefined,
  })
}

export function useDepartments() {
  return useQuery({
    queryKey: ['departments'],
    queryFn: () => api.get<Department[]>('/api/admin/departments'),
  })
}

export interface CreateUserInput {
  asgardeoSubject: string
  email: string
  fullName: string
  departmentId: number | null
  managerId: number | null
  roles: Role[]
}

export function useUserActions() {
  const queries = useQueryClient()
  const refresh = () => {
    void queries.invalidateQueries({ queryKey: ['admin-users'] })
    void queries.invalidateQueries({ queryKey: ['direct-reports'] })
  }

  const create = useMutation({
    mutationFn: (input: CreateUserInput) => api.post<AdminUser>('/api/admin/users', input),
    onSuccess: refresh,
  })

  const update = useMutation({
    mutationFn: ({
      id,
      ...input
    }: {
      id: number
      email: string
      fullName: string
      departmentId: number | null
      roles: Role[]
    }) => api.put<AdminUser>(`/api/admin/users/${id}`, input),
    onSuccess: refresh,
  })

  /** A null managerId detaches, which is how the top of the chain is set. Loops are 400. */
  const setManager = useMutation({
    mutationFn: ({ id, managerId }: { id: number; managerId: number | null }) =>
      api.put<AdminUser>(`/api/admin/users/${id}/manager`, { managerId }),
    onSuccess: refresh,
  })

  /**
   * Deactivation sets a flag. It is a PUT rather than a DELETE because nothing is deleted -
   * the row and its history survive, and the verb would misdescribe that.
   */
  const deactivate = useMutation({
    mutationFn: (id: number) =>
      api.put<{ user: AdminUser; danglingReports: number; warning: string | null }>(
        `/api/admin/users/${id}/deactivate`,
      ),
    onSuccess: refresh,
  })

  const reactivate = useMutation({
    mutationFn: (id: number) => api.put<AdminUser>(`/api/admin/users/${id}/reactivate`),
    onSuccess: refresh,
  })

  return { create, update, setManager, deactivate, reactivate }
}

export function useDepartmentActions() {
  const queries = useQueryClient()
  const refresh = () => void queries.invalidateQueries({ queryKey: ['departments'] })

  const create = useMutation({
    mutationFn: (name: string) => api.post<Department>('/api/admin/departments', { name }),
    onSuccess: refresh,
  })

  const rename = useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) =>
      api.put<Department>(`/api/admin/departments/${id}`, { name }),
    onSuccess: refresh,
  })

  return { create, rename }
}

// ---------------------------------------------------------------- HR grants

export interface Grant {
  id: number
  hrUserId: number
  hrUserName: string
  departmentId: number
  departmentName: string
  explicitGrant: boolean
  grantedByName: string | null
  grantedAt: string
}

export function useGrants(hrUserId: number | undefined) {
  return useQuery({
    queryKey: ['hr-grants', hrUserId],
    queryFn: () => api.get<Grant[]>(`/api/admin/hr-grants/${hrUserId}`),
    enabled: hrUserId !== undefined,
  })
}

export function useGrantActions(hrUserId: number | undefined) {
  const queries = useQueryClient()
  const refresh = () => void queries.invalidateQueries({ queryKey: ['hr-grants', hrUserId] })

  const grant = useMutation({
    mutationFn: ({ departmentId, explicitGrant }: { departmentId: number; explicitGrant: boolean }) =>
      api.post<Grant>('/api/admin/hr-grants', { hrUserId, departmentId, explicitGrant }),
    onSuccess: refresh,
  })

  const setExplicit = useMutation({
    mutationFn: ({ departmentId, explicitGrant }: { departmentId: number; explicitGrant: boolean }) =>
      api.put<Grant>(`/api/admin/hr-grants/${hrUserId}/${departmentId}/explicit`, { explicitGrant }),
    onSuccess: refresh,
  })

  /** A real delete, unlike deactivating a user: a grant authors nothing and keeps no history. */
  const revoke = useMutation({
    mutationFn: (departmentId: number) =>
      api.del<void>(`/api/admin/hr-grants/${hrUserId}/${departmentId}`),
    onSuccess: refresh,
  })

  return { grant, setExplicit, revoke }
}

// ---------------------------------------------------------------- cycles and cohorts

export interface Cohort {
  id: number
  name: string
  quadrimesterNo: number | null
}

export interface CohortMember {
  userId: number
  fullName: string
  cohortId: number
  cohortName: string
}

export function useCohorts() {
  return useQuery({
    queryKey: ['cohorts'],
    queryFn: () => api.get<Cohort[]>('/api/admin/cohorts'),
  })
}

export function useCohortMembers(cohortId: number | undefined) {
  return useQuery({
    queryKey: ['cohort-members', cohortId],
    queryFn: () => api.get<CohortMember[]>(`/api/admin/cohorts/${cohortId}/members`),
    enabled: cohortId !== undefined,
  })
}

export function useCycleActions() {
  const queries = useQueryClient()
  const refresh = () => void queries.invalidateQueries({ queryKey: ['cycles'] })

  const create = useMutation({
    mutationFn: (input: {
      financialYear: number
      quadrimesterNo: number
      startDate: string
      endDate: string
    }) => api.post<Cycle>('/api/admin/cycles', input),
    onSuccess: refresh,
  })

  /** 409 once the cycle has opened (P-6.2). The caller has permission; the state refuses. */
  const reschedule = useMutation({
    mutationFn: ({
      cycleId,
      startDate,
      endDate,
    }: {
      cycleId: number
      startDate: string
      endDate: string
    }) => api.put<Cycle>(`/api/admin/cycles/${cycleId}/dates`, { startDate, endDate }),
    onSuccess: refresh,
  })

  /** The sweep's own code path, entered by a person. Cycles otherwise open on their date. */
  const open = useMutation({
    mutationFn: (cycleId: number) => api.post<Cycle>(`/api/admin/cycles/${cycleId}/open`),
    onSuccess: refresh,
  })

  const close = useMutation({
    mutationFn: (cycleId: number) => api.post<Cycle>(`/api/admin/cycles/${cycleId}/close`),
    onSuccess: refresh,
  })

  return { create, reschedule, open, close }
}

export function useCohortActions(cohortId?: number) {
  const queries = useQueryClient()
  const refresh = () => {
    void queries.invalidateQueries({ queryKey: ['cohorts'] })
    void queries.invalidateQueries({ queryKey: ['cohort-members'] })
    // The user list too, because the cohort screen asks it for "who is in no cohort" and
    // membership has just changed. Without this the person added stays in the dropdown until
    // something else happens to refetch, and can be added a second time to no effect.
    void queries.invalidateQueries({ queryKey: ['admin-users'] })
  }

  const create = useMutation({
    mutationFn: (input: { name: string; quadrimesterNo: number | null }) =>
      api.post<Cohort>('/api/admin/cohorts', input),
    onSuccess: refresh,
  })

  /** Null detaches the cohort, which is how it stops being swept into a cycle. */
  const retarget = useMutation({
    mutationFn: ({ id, quadrimesterNo }: { id: number; quadrimesterNo: number | null }) =>
      api.put<Cohort>(`/api/admin/cohorts/${id}/quadrimester`, { quadrimesterNo }),
    onSuccess: refresh,
  })

  const addMember = useMutation({
    mutationFn: (userId: number) =>
      api.post<CohortMember>(`/api/admin/cohorts/${cohortId}/members`, { userId }),
    onSuccess: refresh,
  })

  /**
   * 409 while the person is in an open cycle. Scenario section 15.4 - what becomes of reviews
   * already written about somebody removed mid-cycle - is still with the Product Owner, and
   * this refusal is what keeps the question open rather than answering it by accident.
   */
  const removeMember = useMutation({
    mutationFn: (userId: number) => api.del<void>(`/api/admin/cohorts/members/${userId}`),
    onSuccess: refresh,
  })

  return { create, retarget, addMember, removeMember }
}
