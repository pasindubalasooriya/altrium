-- V5 - Development plans and their goals (feature 15).
--
-- The PDP is NOT keyed to a cycle, and that is the single most important thing about this
-- migration. Scenario section 8 gives every employee a development plan "from the moment they
-- join", and section 5 step 9 has a passed improvement plan resume the suspended development
-- plan "with its goals and progress intact". Both only work if there is one enduring row per
-- person that cycles pass over, rather than a new plan each quadrimester.
--
-- That is the carry-over pillar the client asked for, and it is expressed here as a unique key
-- on user_id. A per-cycle plan would have made carry-over a copying operation, and a copy is
-- where progress gets lost.
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

CREATE TABLE development_plan (
    id           BIGINT      NOT NULL AUTO_INCREMENT,

    -- One per employee, forever. Not per cycle, not per manager, not per department.
    user_id      BIGINT      NOT NULL,

    -- ACTIVE or SUSPENDED. Suspension belongs to the improvement plan (feature 16, P-5.7):
    -- opening a PIP suspends the PDP, and passing the PIP resumes this same row. Only
    -- PlanService ever writes this column (P-5.8).
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    suspended_at DATETIME(6) NULL,

    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_development_plan PRIMARY KEY (id),
    CONSTRAINT uq_development_plan_user UNIQUE (user_id),
    CONSTRAINT ck_development_plan_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT fk_development_plan_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Freeform goals with movable target dates (P-5.5).
--
-- development_plan_id is NOT NULL here because improvement plans do not exist yet. When
-- feature 16 arrives it will decide whether PIP goals extend this table or take their own, and
-- that decision is deliberately not pre-empted: designing a column now for a table that does
-- not exist would be guessing at a shape nobody has had to build yet.
--
-- The exclusivity lock (P-5.7) is likewise left to that migration, since the PDP alone cannot
-- conflict with anything. Backfilling it from this table is one INSERT SELECT.
CREATE TABLE plan_goal (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    development_plan_id BIGINT       NOT NULL,

    title               VARCHAR(200) NOT NULL,
    detail              TEXT         NULL,

    -- Movable, in deliberate contrast to a PIP deadline, which is immutable once set (P-5.5).
    -- Nullable: a goal can be agreed before a date for it is.
    target_date         DATE         NULL,

    -- OPEN until mgr(S) approves it as complete (P-5.2). The employee reports progress; the
    -- manager is the one who says it is done, which is why approved_by is recorded.
    status              VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    completed_at        DATETIME(6)  NULL,
    approved_by         BIGINT       NULL,

    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_plan_goal PRIMARY KEY (id),
    CONSTRAINT ck_plan_goal_status CHECK (status IN ('OPEN', 'COMPLETE')),

    -- A completed goal without an approver would be a goal that completed itself.
    CONSTRAINT ck_plan_goal_approval CHECK (
        status = 'OPEN' OR (completed_at IS NOT NULL AND approved_by IS NOT NULL)),

    CONSTRAINT fk_plan_goal_plan        FOREIGN KEY (development_plan_id) REFERENCES development_plan (id),
    CONSTRAINT fk_plan_goal_approved_by FOREIGN KEY (approved_by)         REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_plan_goal_plan ON plan_goal (development_plan_id);
