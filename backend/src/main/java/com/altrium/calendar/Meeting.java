package com.altrium.calendar;

import com.altrium.org.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A meeting Altrium booked.
 *
 * <p>A record of a booking, not the meeting itself. The event lives in the organiser's Google
 * Calendar and can be moved, declined or cancelled there without Altrium hearing about it, so
 * nothing reads this row as though it were the current state of anybody's diary. It answers
 * one question: what did Altrium put in a calendar, for whom, and when.
 *
 * <p>No setters. A booking that needs changing is changed in the calendar, which is where the
 * people involved can see it happen.
 */
@Entity
@Table(name = "meeting")
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "meeting_type", nullable = false, length = 32)
    private MeetingType type;

    /**
     * The reviewee this meeting is about, and the subject every authorization decision on this
     * row is made against.
     *
     * <p>For a normalization meeting that is not either person in the room. See
     * {@link MeetingType#NORMALIZATION_MEETING} for why it is still the right subject.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private AppUser subject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organiser_id", nullable = false)
    private AppUser organiser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attendee_id", nullable = false)
    private AppUser attendee;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "google_event_id", nullable = false, length = 255)
    private String googleEventId;

    @Column(name = "google_html_link", length = 512)
    private String googleHtmlLink;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Meeting() {
    }

    public Meeting(MeetingType type, AppUser subject, AppUser organiser, AppUser attendee,
                   TimeSlot when, String googleEventId, String googleHtmlLink) {
        this.type = type;
        this.subject = subject;
        this.organiser = organiser;
        this.attendee = attendee;
        this.startsAt = when.start();
        this.endsAt = when.end();
        this.googleEventId = googleEventId;
        this.googleHtmlLink = googleHtmlLink;
    }

    public Long getId() {
        return id;
    }

    public MeetingType getType() {
        return type;
    }

    public AppUser getSubject() {
        return subject;
    }

    public AppUser getOrganiser() {
        return organiser;
    }

    public AppUser getAttendee() {
        return attendee;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getGoogleEventId() {
        return googleEventId;
    }

    public String getGoogleHtmlLink() {
        return googleHtmlLink;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
