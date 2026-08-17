-- V6 - Improvement plans (feature 16).
--
-- The heaviest table in the system, because a PIP is the one artifact with consequences
-- attached. Three of its columns exist purely to make it formal rather than convenient: the
-- consequence clause, the co-signature, and the witness. None of them is decoration, and each
-- is guarded differently.
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

CREATE TABLE improvement_plan (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,

    -- ACTIVE -> PASSED or FAILED. Written only by PlanService (P-5.8).
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',

    -- Optimistic locking. The pass and fail transitions read the plan, check its state and
    -- write it back; two concurrent closes would otherwise both see ACTIVE and both proceed,
    -- and one of them would resume a development plan that the other had already resumed.
    version             BIGINT       NOT NULL DEFAULT 0,

    opened_by           BIGINT       NOT NULL,
    opened_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- P-5.6: must be non-empty before the plan can be co-signed. Nullable at creation, because
    -- a manager drafting a plan has not necessarily written it yet, and forcing it at insert
    -- would only produce a placeholder.
    consequence_clause  TEXT         NULL,

    -- P-5.5: set once, at opening, and immutable thereafter. No actor may extend it - not the
    -- manager who opened it, not HR, not the Super Admin. EXTEND_PIP_DEADLINE carries an empty
    -- grounds set, so the refusal is enforced rather than merely unimplemented.
    deadline            DATE         NOT NULL,

    -- P-5.3: the co-sign gate. While this is NULL the plan is invisible to the employee, which
    -- is a predicate in the query and a state gate in AuthorizationService, not a hidden screen.
    cosigned_by         BIGINT       NULL,
    cosigned_at         DATETIME(6)  NULL,

    -- P-5.4: HR-in-scope alone records this. A manager can neither co-sign nor witness, which
    -- is the entire point of the two formality objects.
    witness_name        VARCHAR(200) NULL,
    witness_recorded_by BIGINT       NULL,
    witness_recorded_at DATETIME(6)  NULL,

    closed_at           DATETIME(6)  NULL,

    -- THE EXCLUSIVITY GUARANTEE (P-5.7), as a database constraint rather than a promise.
    --
    -- Holds user_id while the plan is ACTIVE and NULL otherwise, and carries a unique index.
    -- MySQL treats NULLs as distinct in a unique index, so any number of closed plans coexist
    -- and a second ACTIVE one for the same person cannot be inserted at all. Two concurrent
    -- requests to open a PIP therefore cannot both succeed, whatever the service does.
    --
    -- This replaces the active_plan_lock table the project plan sketched. That table would have
    -- enforced the same invariant from outside, in a second place that has to be kept in step,
    -- and a lock table that has drifted is worse than no lock table because it is believed.
    -- Here the constraint lives on the table it constrains. See the worklog for the full note.
    active_user_id      BIGINT       AS (CASE WHEN status = 'ACTIVE' THEN user_id END) STORED,

    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_improvement_plan PRIMARY KEY (id),
    CONSTRAINT uq_improvement_plan_active UNIQUE (active_user_id),

    CONSTRAINT ck_improvement_plan_status CHECK (status IN ('ACTIVE', 'PASSED', 'FAILED')),

    -- A co-signature without an author, or an author without a time, would be half a formality.
    CONSTRAINT ck_improvement_plan_cosign CHECK (
        (cosigned_by IS NULL AND cosigned_at IS NULL)
        OR (cosigned_by IS NOT NULL AND cosigned_at IS NOT NULL)),

    CONSTRAINT ck_improvement_plan_witness CHECK (
        (witness_name IS NULL AND witness_recorded_by IS NULL)
        OR (witness_name IS NOT NULL AND witness_recorded_by IS NOT NULL)),

    CONSTRAINT fk_improvement_plan_user      FOREIGN KEY (user_id)             REFERENCES app_user (id),
    CONSTRAINT fk_improvement_plan_opened_by FOREIGN KEY (opened_by)           REFERENCES app_user (id),
    CONSTRAINT fk_improvement_plan_cosigner  FOREIGN KEY (cosigned_by)         REFERENCES app_user (id),
    CONSTRAINT fk_improvement_plan_witness   FOREIGN KEY (witness_recorded_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_improvement_plan_user ON improvement_plan (user_id);


-- Goals belong to one plan or the other, never both and never neither.
--
-- V5 left development_plan_id NOT NULL rather than guessing at this shape. The decision now is
-- to extend the existing table rather than add improvement_goal, because P-5.2 gives goal
-- approval to mgr(S) on both plan types in the same breath - two tables would have meant two
-- copies of the approval rule, and the second one would be the one that drifted.
ALTER TABLE plan_goal
    MODIFY COLUMN development_plan_id BIGINT NULL,
    ADD COLUMN improvement_plan_id BIGINT NULL AFTER development_plan_id,
    ADD CONSTRAINT fk_plan_goal_improvement_plan
        FOREIGN KEY (improvement_plan_id) REFERENCES improvement_plan (id),
    ADD CONSTRAINT ck_plan_goal_one_owner CHECK (
        (development_plan_id IS NOT NULL AND improvement_plan_id IS NULL)
        OR (development_plan_id IS NULL AND improvement_plan_id IS NOT NULL));

CREATE INDEX ix_plan_goal_improvement_plan ON plan_goal (improvement_plan_id);
