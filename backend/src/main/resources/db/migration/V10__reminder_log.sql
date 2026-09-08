-- V10 - What has already been reminded, so nobody is told twice in a day.
--
-- The sweep runs daily and selects on "the deadline is N days away". That is idempotent across
-- a normal day, but not across a rerun: restart the service, run the sweep by hand while
-- testing, or have the job fire twice after a clock change, and the same person receives the
-- same email again. An email cannot be recalled, and a reminder system that cries twice is one
-- people start filtering into a folder.
--
-- So each send is recorded, and the record is what makes the second attempt a no-op. The unique
-- key is the mechanism rather than a check in Java: two sweeps running concurrently would both
-- pass an application-level "have we sent this?" test and both send.
--
--   (item_type, item_id, recipient_id, sent_on)
--
-- All four parts are needed. `item_type` because one participant row generates a reminder to
-- the employee about their self-review and another to their manager about the manager review,
-- and those share an item id. `recipient_id` because a goal can outlive a change of manager.
-- `sent_on` because tomorrow's reminder about the same deadline is a different email, which is
-- the point of reminding at seven days and then at one.
--
-- Append only, like export_log. Nothing updates or deletes a row here.
--
-- It records that a reminder was sent, never what it said. The wording is in the code and the
-- deadline is in the row that caused it; storing the body would put review-adjacent text in a
-- table with no access control of its own.
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

CREATE TABLE reminder_log
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- SELF_REVIEW, PEER_REVIEW, MANAGER_REVIEW or PLAN_GOAL. Deliberately not a foreign key to
    -- any one table: the four kinds live in four tables, and a nullable column per kind would
    -- make the unique key below impossible to express.
    item_type    VARCHAR(24) NOT NULL,

    -- The row that carried the deadline: a cycle_participant, a peer_assignment or a plan_goal.
    -- Meaningful only together with item_type.
    item_id      BIGINT      NOT NULL,

    -- Who was told. The person responsible for the task, never their manager or their team.
    recipient_id BIGINT      NOT NULL,

    -- The deadline this reminder was about, kept for reading the table later. A reminder sent
    -- on the 3rd about a deadline on the 10th is a different thing from one sent on the 9th.
    due_date     DATE        NOT NULL,

    -- The day the send happened, in the server's zone. A date rather than a timestamp, because
    -- the rule is one per day and a timestamp would make the unique key useless.
    sent_on      DATE        NOT NULL,

    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_reminder_log_recipient FOREIGN KEY (recipient_id) REFERENCES app_user (id),
    CONSTRAINT ck_reminder_log_item_type
        CHECK (item_type IN ('SELF_REVIEW', 'PEER_REVIEW', 'MANAGER_REVIEW', 'PLAN_GOAL')),
    CONSTRAINT uq_reminder_log_once_per_day
        UNIQUE (item_type, item_id, recipient_id, sent_on)
);

-- For the one question this table is asked outside the sweep: what has this person been sent.
CREATE INDEX ix_reminder_log_recipient ON reminder_log (recipient_id, sent_on);
