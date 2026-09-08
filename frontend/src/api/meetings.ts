import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

/**
 * Google Calendar scheduling (scenario section 12).
 *
 * Two halves that look similar and are not. Connecting is about the caller's own Google
 * account and takes no user id anywhere, because connecting somebody else's is not a
 * permission that exists. Scheduling is about a reviewee, and every call carries the subject
 * the server decides against.
 */

export type MeetingType = 'PLAN_MEETING' | 'NORMALIZATION_MEETING'

export interface GoogleStatus {
  connected: boolean
  googleEmail: string | null
  /**
   * Whether the server holds OAuth credentials at all.
   *
   * Separate from `connected` so the screen can say "not available on this server" rather
   * than offering a button that cannot work. A person who clicks and is refused assumes they
   * did something wrong.
   */
  configured: boolean
}

export interface Slot {
  start: string
  end: string
}

export interface SlotProposal {
  slots: Slot[]
  /**
   * False when the other party has not connected their own calendar.
   *
   * There is no shared directory (scenario section 14), so their free/busy is simply
   * unavailable and these are times the organiser is free and no more. The screen has to say
   * so: a proposal presented as mutual when it is not is worse than no proposal.
   */
  bothCalendarsChecked: boolean
  attendeeName: string
  minutes: number
}

export interface BookedMeeting {
  id: number
  type: MeetingType
  when: Slot
  attendeeName: string
  htmlLink: string | null
}

export interface MeetingView {
  id: number
  type: MeetingType
  /** The other person. Never the viewer's own name back at them. */
  withName: string
  startsAt: string
  endsAt: string
  /** Present only for the organiser; Google issues the link per calendar. */
  htmlLink: string | null
}

export const MEETING_LABELS: Record<MeetingType, string> = {
  PLAN_MEETING: 'Plan meeting',
  NORMALIZATION_MEETING: 'Normalization meeting',
}

// ------------------------------------------------------------------ the caller's own account

export function useGoogleStatus() {
  return useQuery({
    queryKey: ['google-status'],
    queryFn: () => api.get<GoogleStatus>('/api/integrations/google/status'),
  })
}

/**
 * Starts the consent flow by navigating away.
 *
 * A full page navigation, not a fetch: the consent screen is a page Google shows the person,
 * and following the redirect inside an XHR would mean they never see it. They come back to
 * `/settings/calendar` with an outcome in the query string.
 */
export function useConnectGoogle() {
  return useMutation({
    mutationFn: async () => {
      const { authorizationUrl } = await api.post<{ authorizationUrl: string }>(
        '/api/integrations/google/authorize',
      )
      window.location.assign(authorizationUrl)
    },
  })
}

export function useDisconnectGoogle() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.del<void>('/api/integrations/google'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['google-status'] }),
  })
}

// ------------------------------------------------------------------------------- scheduling

/**
 * Times both parties are free.
 *
 * Only ever the intersection: the server never returns the other person's busy blocks, so
 * there is nothing here to render carelessly. Gated by the same capability as the booking, so
 * a caller with no grounds gets a 403 from this too.
 */
export function useMeetingSlots(
  type: MeetingType,
  subjectId: number,
  from: string,
  to: string,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ['meeting-slots', type, subjectId, from, to],
    queryFn: () =>
      api.get<SlotProposal>('/api/meetings/slots', { type, subjectId, from, to }),
    enabled,
    // A calendar changes while somebody is looking at this list, and an offered slot that has
    // since filled up is a booking that fails for no visible reason.
    staleTime: 60_000,
  })
}

export function useScheduleMeeting(type: MeetingType, subjectId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (start: string) =>
      api.post<BookedMeeting>('/api/meetings', { type, subjectId, start }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['meeting-slots'] })
      queryClient.invalidateQueries({ queryKey: ['my-meetings'] })
    },
  })
}

/** The caller's own meetings. There is no endpoint that lists anybody else's. */
export function useMyMeetings() {
  return useQuery({
    queryKey: ['my-meetings'],
    queryFn: () => api.get<MeetingView[]>('/api/meetings/me'),
  })
}
