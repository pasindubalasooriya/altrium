-- V4 - Cohorts: who gets reviewed in which quadrimester.
--
-- V3 merged the plan's cohort_membership into cycle_participant, which was right for what
-- cycle_participant is - a snapshot of who was actually taken into a cycle when it opened.
-- It left a gap, though, and features 5 and 6 walk straight into it. Scenario section 4
-- describes something the snapshot cannot express:
--
--   "Employees are split into cohorts, one per quadrimester (~33 each). Each employee is
--    assessed once per year in a fixed quadrimester and stays there year over year."
--
-- That is a standing assignment, not a per-cycle fact. It has to survive the cycle it fed,
-- because next year's cycle for the same quadrimester reads it again. cycle_participant
-- cannot carry it: it is keyed to one cycle, and deriving next year's cohort from last
-- year's participants would make an intake mistake permanent.
--
-- So the two tables are genuinely different things and both are needed:
--   cohort_member      - a standing assignment, edited by the Super Admin, read at intake
--   cycle_participant  - what intake produced, frozen, never re-read as configuration
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

-- A named group of employees attached to a quadrimester.
--
-- quadrimester_no is nullable on purpose. Scenario section 4 lets the Super Admin "add or
-- remove cohorts from a quadrimester", so a cohort must be able to exist while attached to
-- none. A detached cohort is simply never picked up by the sweep, which is the honest
-- reading of "removed from the quadrimester" and needs no second flag to express.
CREATE TABLE cohort (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(120) NOT NULL,
    quadrimester_no TINYINT      NULL,

    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_cohort PRIMARY KEY (id),
    CONSTRAINT uq_cohort_name UNIQUE (name),
    CONSTRAINT ck_cohort_quadrimester CHECK (quadrimester_no IS NULL OR quadrimester_no BETWEEN 1 AND 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The sweep's lookup: every cohort attached to the quadrimester being opened.
CREATE INDEX ix_cohort_quadrimester ON cohort (quadrimester_no);


-- Membership.
--
-- UNIQUE on user_id alone, not on the pairing. That single choice is what makes "assessed
-- once per year in a fixed quadrimester" a database guarantee rather than a service promise:
-- a person in two cohorts attached to different quadrimesters would be reviewed twice in one
-- year, and a person in two cohorts on the same quadrimester would be taken into intake
-- twice. Moving somebody between cohorts is therefore an update, not an insert, which is
-- also the honest shape of the operation the Super Admin is performing.
CREATE TABLE cohort_member (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    cohort_id BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL,

    added_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_cohort_member PRIMARY KEY (id),
    CONSTRAINT uq_cohort_member_user UNIQUE (user_id),
    CONSTRAINT fk_cohort_member_cohort FOREIGN KEY (cohort_id) REFERENCES cohort (id),
    CONSTRAINT fk_cohort_member_user   FOREIGN KEY (user_id)   REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_cohort_member_cohort ON cohort_member (cohort_id);
