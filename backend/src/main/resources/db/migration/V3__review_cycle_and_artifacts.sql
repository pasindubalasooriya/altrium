-- V3 — Review cycles and the four review artifacts.
--
-- Feature 4 reads these; features 7 to 13 write them. The tables arrive together because
-- the read model has to be scoped against all of them at once — "the reviews I may see"
-- is one question spanning four tables, and scoping three of them correctly is a leak.
--
-- Every artifact carries subject_id as a direct column rather than reaching it through the
-- cycle. That is what lets one Specification scope all four identically (P-0.3): the
-- WHERE clause always has a subject to filter on, without a join whose absence would be
-- easy to miss in a fifth table added later.
--
-- SCHEMA DECISION, worth recording: the plan listed quadrimester_config and review_cycle as
-- separate tables. They are one thing — a configured quadrimester IS the cycle, before it
-- opens — so they are merged here as review_cycle with a status. Two tables in one-to-one
-- correspondence would have made "is this cycle open?" answerable from two places, and
-- P-6.2 (the start date is locked once opened_at is set) needs a single answer.
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

CREATE TABLE review_cycle (
    id              BIGINT      NOT NULL AUTO_INCREMENT,

    -- Altrium's financial year runs to quadrimesters, three per year (scenario §4).
    financial_year  INT         NOT NULL,
    quadrimester_no TINYINT     NOT NULL,

    -- CONFIGURED -> OPEN -> CLOSED. Kept as a column rather than derived from the two
    -- timestamps so a query can filter on it without restating the state machine in SQL.
    status          VARCHAR(16) NOT NULL DEFAULT 'CONFIGURED',

    -- Changeable only while opened_at IS NULL (P-6.2).
    start_date      DATE        NOT NULL,
    end_date        DATE        NOT NULL,

    -- Stamped by the daily sweep, which selects start_date <= CURRENT_DATE AND opened_at IS
    -- NULL — arrived or passed, never equals today, so a missed run resolves next sweep.
    -- Its presence is what makes the sweep idempotent (P-6.4).
    opened_at       DATETIME(6) NULL,
    closed_at       DATETIME(6) NULL,

    created_by      BIGINT      NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_review_cycle PRIMARY KEY (id),

    -- One cycle per quadrimester. Two would make "the open cycle" ambiguous, and every
    -- artifact below is keyed to exactly one.
    CONSTRAINT uq_review_cycle_period UNIQUE (financial_year, quadrimester_no),

    CONSTRAINT ck_review_cycle_status      CHECK (status IN ('CONFIGURED', 'OPEN', 'CLOSED')),
    CONSTRAINT ck_review_cycle_quadrimester CHECK (quadrimester_no BETWEEN 1 AND 3),
    CONSTRAINT ck_review_cycle_dates       CHECK (end_date > start_date),

    CONSTRAINT fk_review_cycle_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The sweep's selection predicate (P-6.4).
CREATE INDEX ix_review_cycle_sweep ON review_cycle (opened_at, start_date);


-- Who is being reviewed in this cycle. Populated when the cycle opens, from the cohort the
-- Super Admin configured, skipping deactivated users (P-0.7) and Leadership (P-1.5).
--
-- A snapshot, not a live query: an employee who leaves mid-cycle keeps their row, because
-- the review that already exists must stay attributable and readable (P-0.7).
CREATE TABLE cycle_participant (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    cycle_id   BIGINT      NOT NULL,
    subject_id BIGINT      NOT NULL,

    -- Denormalised from app_user at intake. HR scoping asks "which department was this
    -- review in?", and that must not change retroactively when somebody transfers — last
    -- quarter's review belonged to last quarter's department.
    department_id BIGINT   NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_cycle_participant PRIMARY KEY (id),
    CONSTRAINT uq_cycle_participant UNIQUE (cycle_id, subject_id),
    CONSTRAINT fk_cycle_participant_cycle      FOREIGN KEY (cycle_id)      REFERENCES review_cycle (id),
    CONSTRAINT fk_cycle_participant_subject    FOREIGN KEY (subject_id)    REFERENCES app_user (id),
    CONSTRAINT fk_cycle_participant_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_cycle_participant_subject ON cycle_participant (subject_id);


-- Self-review (P-3.1). Written by the subject alone.
--
-- Deliberately carries NO rating column. The subject does not rate themselves anywhere in
-- the scenario, and a nullable column would eventually be filled by something.
CREATE TABLE self_review (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    cycle_id     BIGINT      NOT NULL,
    subject_id   BIGINT      NOT NULL,

    achievements TEXT        NULL,
    challenges   TEXT        NULL,
    goals        TEXT        NULL,

    submitted_at DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_self_review PRIMARY KEY (id),
    CONSTRAINT uq_self_review UNIQUE (cycle_id, subject_id),
    CONSTRAINT fk_self_review_cycle   FOREIGN KEY (cycle_id)   REFERENCES review_cycle (id),
    CONSTRAINT fk_self_review_subject FOREIGN KEY (subject_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_self_review_subject ON self_review (subject_id);


-- Peer assignment (P-3.6). Exactly two per subject per cycle, chosen by mgr(S).
--
-- The "exactly two" rule is enforced in the service: a database constraint can express at
-- most one row per pairing, not a required count, and a trigger would put a rule outside
-- AuthorizationService's reach.
CREATE TABLE peer_assignment (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    cycle_id    BIGINT      NOT NULL,
    subject_id  BIGINT      NOT NULL,
    peer_id     BIGINT      NOT NULL,

    assigned_by BIGINT      NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_peer_assignment PRIMARY KEY (id),
    CONSTRAINT uq_peer_assignment UNIQUE (cycle_id, subject_id, peer_id),

    -- Nobody reviews themselves as a peer. The exclusion of mgr(S) (P-3.6) cannot be
    -- expressed here because the reporting line lives in another table, so it sits in the
    -- service alongside the count rule.
    CONSTRAINT ck_peer_assignment_not_self CHECK (peer_id <> subject_id),

    CONSTRAINT fk_peer_assignment_cycle       FOREIGN KEY (cycle_id)    REFERENCES review_cycle (id),
    CONSTRAINT fk_peer_assignment_subject     FOREIGN KEY (subject_id)  REFERENCES app_user (id),
    CONSTRAINT fk_peer_assignment_peer        FOREIGN KEY (peer_id)     REFERENCES app_user (id),
    CONSTRAINT fk_peer_assignment_assigned_by FOREIGN KEY (assigned_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_peer_assignment_subject ON peer_assignment (subject_id);
CREATE INDEX ix_peer_assignment_peer    ON peer_assignment (peer_id);


-- Peer review (P-3.2 to P-3.5).
--
-- peer_id is a plain, named foreign key: authorship is recorded, not discarded. Anonymity
-- is a rule about who may READ it, enforced above this table (P-3.3) — the manager and HR
-- see exactly who wrote what, and only the subject never does. Storing it anonymously
-- would destroy accountability to protect a confidentiality the read layer already gives.
CREATE TABLE peer_review (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    cycle_id     BIGINT      NOT NULL,
    subject_id   BIGINT      NOT NULL,
    peer_id      BIGINT      NOT NULL,

    feedback     TEXT        NULL,
    rating       VARCHAR(32) NULL,

    submitted_at DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_peer_review PRIMARY KEY (id),

    -- Makes single submission (P-3.5) a database guarantee rather than a service promise.
    -- A duplicate hits this and surfaces as 409, not 403: the peer has permission, and it
    -- is the record that forbids the second write.
    CONSTRAINT uq_peer_review UNIQUE (cycle_id, subject_id, peer_id),

    CONSTRAINT ck_peer_review_not_self CHECK (peer_id <> subject_id),
    CONSTRAINT ck_peer_review_rating   CHECK (rating IS NULL OR rating IN
        ('NEEDS_IMPROVEMENT', 'MEETS_EXPECTATIONS', 'EXCEEDS_EXPECTATIONS')),

    CONSTRAINT fk_peer_review_cycle   FOREIGN KEY (cycle_id)   REFERENCES review_cycle (id),
    CONSTRAINT fk_peer_review_subject FOREIGN KEY (subject_id) REFERENCES app_user (id),
    CONSTRAINT fk_peer_review_peer    FOREIGN KEY (peer_id)    REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_peer_review_subject ON peer_review (subject_id);


-- Manager review (P-3.7). Written by mgr(S); read by S, mgr(S) and HR-in-scope.
CREATE TABLE manager_review (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    cycle_id     BIGINT      NOT NULL,
    subject_id   BIGINT      NOT NULL,

    -- The manager at the time of writing. Kept because reporting lines move, and last
    -- quarter's review was written by whoever wrote it.
    manager_id   BIGINT      NOT NULL,

    feedback     TEXT        NULL,

    submitted_at DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_manager_review PRIMARY KEY (id),
    CONSTRAINT uq_manager_review UNIQUE (cycle_id, subject_id),
    CONSTRAINT ck_manager_review_not_self CHECK (manager_id <> subject_id),
    CONSTRAINT fk_manager_review_cycle   FOREIGN KEY (cycle_id)   REFERENCES review_cycle (id),
    CONSTRAINT fk_manager_review_subject FOREIGN KEY (subject_id) REFERENCES app_user (id),
    CONSTRAINT fk_manager_review_manager FOREIGN KEY (manager_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_manager_review_subject ON manager_review (subject_id);


-- Final rating (P-4.1, P-4.4).
--
-- Chosen by mgr(S), never computed from the peer ratings — there is no aggregate column
-- here, and none is derived on read, because a displayed average becomes a suggestion and
-- then a default (scenario §11).
--
-- released_at gates the subject's view (P-4.4): a rating still under calibration is not
-- theirs to read.
CREATE TABLE final_rating (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    cycle_id     BIGINT      NOT NULL,
    subject_id   BIGINT      NOT NULL,

    rating       VARCHAR(32) NOT NULL,
    set_by       BIGINT      NOT NULL,
    set_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    released_at  DATETIME(6) NULL,

    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_final_rating PRIMARY KEY (id),
    CONSTRAINT uq_final_rating UNIQUE (cycle_id, subject_id),
    CONSTRAINT ck_final_rating_value CHECK (rating IN
        ('NEEDS_IMPROVEMENT', 'MEETS_EXPECTATIONS', 'EXCEEDS_EXPECTATIONS')),
    CONSTRAINT fk_final_rating_cycle   FOREIGN KEY (cycle_id)   REFERENCES review_cycle (id),
    CONSTRAINT fk_final_rating_subject FOREIGN KEY (subject_id) REFERENCES app_user (id),
    CONSTRAINT fk_final_rating_set_by  FOREIGN KEY (set_by)     REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_final_rating_subject ON final_rating (subject_id);


-- Rating calibration audit (P-4.3). Append-only: HR changing a rating adds a row, and no
-- row is ever updated or deleted. The before-value is what makes the change visible at all
-- — without it, calibration is indistinguishable from the manager having chosen differently.
CREATE TABLE rating_calibration (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    final_rating_id BIGINT      NOT NULL,

    rating_before   VARCHAR(32) NOT NULL,
    rating_after    VARCHAR(32) NOT NULL,

    calibrated_by   BIGINT      NOT NULL,
    calibrated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    note            TEXT        NULL,

    CONSTRAINT pk_rating_calibration PRIMARY KEY (id),
    CONSTRAINT ck_rating_calibration_before CHECK (rating_before IN
        ('NEEDS_IMPROVEMENT', 'MEETS_EXPECTATIONS', 'EXCEEDS_EXPECTATIONS')),
    CONSTRAINT ck_rating_calibration_after CHECK (rating_after IN
        ('NEEDS_IMPROVEMENT', 'MEETS_EXPECTATIONS', 'EXCEEDS_EXPECTATIONS')),
    CONSTRAINT fk_rating_calibration_rating FOREIGN KEY (final_rating_id) REFERENCES final_rating (id),
    CONSTRAINT fk_rating_calibration_actor  FOREIGN KEY (calibrated_by)   REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_rating_calibration_rating ON rating_calibration (final_rating_id);
