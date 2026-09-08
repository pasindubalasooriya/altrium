package com.altrium.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    /**
     * The meetings one person is actually in, upcoming first.
     *
     * <p><strong>The caller's own id is in the {@code WHERE} clause and appears nowhere else
     * in the request.</strong> There is no parameter naming whose meetings to list, so there
     * is no widening for an authorization rule to have to refuse; the only list this query can
     * produce is the caller's own (P-0.3).
     *
     * <p>Being in the room is the whole rule here, and it is narrower than the capability that
     * booked the meeting. HR who may schedule a normalization meeting about an employee are in
     * that meeting and see it; an HR user granted the department afterwards is not, and does
     * not. A meeting is an appointment in somebody's day rather than a piece of review
     * content, and the people with an interest in it are the ones expected to turn up.
     */
    @Query("""
            SELECT m FROM Meeting m
            WHERE ( m.organiser.id = :userId OR m.attendee.id = :userId )
              AND m.endsAt >= :since
            ORDER BY m.startsAt ASC
            """)
    List<Meeting> forParticipant(@Param("userId") Long userId, @Param("since") Instant since);

    /**
     * Whether this pair already has this kind of meeting booked ahead of them.
     *
     * <p>Used to refuse a duplicate booking rather than to hide one: a manager who clicks twice
     * should not put two plan meetings in an employee's diary.
     */
    @Query("""
            SELECT COUNT(m) > 0 FROM Meeting m
            WHERE m.type = :type
              AND m.subject.id = :subjectId
              AND m.startsAt >= :since
            """)
    boolean existsUpcoming(@Param("type") MeetingType type,
                           @Param("subjectId") Long subjectId,
                           @Param("since") Instant since);
}
