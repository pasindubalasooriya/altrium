-- V9 - A record of every report taken out of the system.
--
-- Exports are the one place where data leaves Altrium and stops being governed by it. Every
-- other read is checked again on the next request; a spreadsheet is checked once, and then it
-- is a file on somebody's laptop. Revoking an HR user's department tomorrow does not recall
-- the workbook they downloaded today.
--
-- So the export is recorded. Not the contents - storing a second copy of the rows would double
-- the exposure this table exists to make visible - but who took what, and when.
--
-- Deliberately not scenario text. Section 13 asks for exports and says nothing about logging
-- them; this is the team's addition, on the grounds that an unlogged export makes the
-- authorization model unfalsifiable after the fact. It is cheap to keep and impossible to
-- reconstruct later.
--
-- Append only. Nothing in the application updates or deletes a row here, and the absence of an
-- updated_at column is the reminder: a log that can be edited answers a different question from
-- the one it was built for.
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

CREATE TABLE export_log
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- Who ran it. Restricted rather than cascading: a person can be deactivated but their row
    -- is never deleted (constraint 8), so this reference cannot dangle, and the log must not
    -- become deletable by way of the user table.
    exported_by  BIGINT       NOT NULL,

    cycle_id     BIGINT       NOT NULL,

    -- PDF or XLSX. Which format was taken matters: a spreadsheet is worked on and recirculated,
    -- a PDF is passed along as it stands.
    format       VARCHAR(8)   NOT NULL,

    -- The ground the authorization decision was made on, HR_IN_SCOPE or LEADERSHIP. This is
    -- what says whether the file held a department slice or company totals, and it is recorded
    -- as the decision rather than as the caller's roles, because somebody may hold both.
    grounds      VARCHAR(32)  NOT NULL,

    -- What the file covered, in words: the granted departments by name, or the whole
    -- organisation. Stored as text on purpose. Department ids would tell a reader what the
    -- scope resolves to today, and the question a log answers is what it resolved to then.
    scope_note   VARCHAR(512) NOT NULL,

    -- How many department rows the file carried. A cheap integrity check against the scope
    -- note, and enough to notice an export that covered more than it should have.
    row_count    INT          NOT NULL,

    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_export_log_user FOREIGN KEY (exported_by) REFERENCES app_user (id),
    CONSTRAINT fk_export_log_cycle FOREIGN KEY (cycle_id) REFERENCES review_cycle (id),
    CONSTRAINT ck_export_log_format CHECK (format IN ('PDF', 'XLSX')),
    CONSTRAINT ck_export_log_grounds CHECK (grounds IN ('HR_IN_SCOPE', 'LEADERSHIP'))
);

-- The two questions this table is asked: what has one person taken, and what has left about
-- one cycle. Both are audit questions rather than screen queries, so one index each is enough.
CREATE INDEX ix_export_log_user ON export_log (exported_by, created_at);
CREATE INDEX ix_export_log_cycle ON export_log (cycle_id, created_at);
