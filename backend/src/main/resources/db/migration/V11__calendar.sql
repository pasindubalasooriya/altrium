-- V11 - Google Calendar scheduling (scenario section 12).
--
-- Two tables, for two separate things: whose Google account Altrium may act on behalf of,
-- and which meetings it has booked.
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.


-- One person's connected Google account.
--
-- Altrium has no Google Workspace, so there is no directory and no domain-wide delegation:
-- every organiser books from their own personal Gmail account, and every calendar Altrium
-- reads is one whose owner personally clicked Connect. That is what makes this a per-user
-- row rather than a single service credential in configuration.
--
-- One row per user, never one per connection attempt. Reconnecting replaces the tokens in
-- place, so a person cannot accumulate stale grants they have forgotten about.
CREATE TABLE google_connection
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,

    user_id              BIGINT       NOT NULL,

    -- The Gmail address the person actually consented with, which need not be their Altrium
    -- address. Shown back to them so they can see which account is connected before they
    -- book anything from it.
    google_email         VARCHAR(320) NOT NULL,

    -- The refresh token, encrypted. It is a long-lived credential that can read a person's
    -- calendar and write to it, so it is the one field in this schema that would matter on
    -- its own if the database were copied. See TokenCipher for what the key is and why.
    --
    -- The access token is deliberately absent. It lasts an hour, so storing it would add a
    -- second credential at rest to save one HTTP call, and it would have to be refreshed
    -- anyway on most requests.
    refresh_token_cipher VARBINARY(1024) NOT NULL,

    -- What the person consented to, recorded as granted rather than as requested. Google may
    -- return fewer scopes than were asked for, and a booking that assumes otherwise fails at
    -- the point of writing to the calendar instead of at the point of connecting.
    granted_scopes       VARCHAR(512) NOT NULL,

    connected_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_google_connection_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uq_google_connection_user UNIQUE (user_id)
);


-- A meeting Altrium booked, and who it was about.
--
-- The row is a record, not the meeting. The meeting itself lives in the organiser's Google
-- Calendar, which is where it can be moved, cancelled or declined without Altrium being told.
-- So nothing here is treated as current: the row says what was booked and links to the event,
-- and the calendar remains the authority on what is still happening.
--
-- It stores no agenda, no notes and no outcome. A normalization meeting is about a rating and
-- a plan meeting is about a plan, and both of those already live in tables with access rules;
-- a free-text field here would be a second, unguarded copy of the same content.
CREATE TABLE meeting
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- PLAN_MEETING or NORMALIZATION_MEETING (scenario section 12).
    meeting_type    VARCHAR(32) NOT NULL,

    -- The reviewee the meeting concerns, and the subject every authorization decision about
    -- this row is made against. For a plan meeting that is the employee, who is also the
    -- attendee. For a normalization meeting it is still the employee, even though neither
    -- party in the room is them: the meeting exists to calibrate their rating, so scoping it
    -- to them is what makes "HR may schedule this" the same question as "HR may calibrate
    -- this", answered by the same grant.
    subject_id      BIGINT      NOT NULL,

    -- Who booked it, on whose calendar the event was created.
    organiser_id    BIGINT      NOT NULL,

    -- Who was invited by email. The employee for a plan meeting, their manager for a
    -- normalization one.
    attendee_id     BIGINT      NOT NULL,

    starts_at       DATETIME(6) NOT NULL,
    ends_at         DATETIME(6) NOT NULL,

    -- Google's id for the event, so a later feature could cancel or update it. Not unique:
    -- a booking that failed after the event was created leaves no row at all, and two rows
    -- sharing an id would mean a bug worth seeing rather than a constraint worth enforcing.
    google_event_id VARCHAR(255) NOT NULL,

    -- The organiser's link to the event. Not the attendee's: Google issues a per-calendar
    -- link and this one is only useful to the person who booked it.
    google_html_link VARCHAR(512),

    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_meeting_subject FOREIGN KEY (subject_id) REFERENCES app_user (id),
    CONSTRAINT fk_meeting_organiser FOREIGN KEY (organiser_id) REFERENCES app_user (id),
    CONSTRAINT fk_meeting_attendee FOREIGN KEY (attendee_id) REFERENCES app_user (id),
    CONSTRAINT ck_meeting_type
        CHECK (meeting_type IN ('PLAN_MEETING', 'NORMALIZATION_MEETING')),
    CONSTRAINT ck_meeting_interval CHECK (ends_at > starts_at)
);

-- The one question this table is asked: what has been booked with me, or by me.
CREATE INDEX ix_meeting_organiser ON meeting (organiser_id, starts_at);
CREATE INDEX ix_meeting_attendee ON meeting (attendee_id, starts_at);
