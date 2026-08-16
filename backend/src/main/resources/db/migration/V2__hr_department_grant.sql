-- V2 — HR department grants.
--
-- The Super Admin decides which departments each HR user may operate in (P-2.1, P-9.2).
-- This table is the whole of that decision, and the authorization layer reads it on every
-- HR action rather than at login (P-2.5), so a revoked grant takes effect on the caller's
-- very next request.
--
-- explicit_grant is the HR Head mechanism (P-2.4). An ordinary HR user is blocked in their
-- own department (P-2.3); a grant row carrying this flag lifts that block for that
-- department only. It does NOT lift the own-review block (P-2.2), which is absolute and has
-- no override at all — that rule is enforced above this table and cannot be expressed here.
--
-- Deliberately not an added column on app_user: an HR user may hold several departments, and
-- the flag belongs to the pairing, not to the person.
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

CREATE TABLE hr_department_grant (
    id             BIGINT      NOT NULL AUTO_INCREMENT,

    hr_user_id     BIGINT      NOT NULL,
    department_id  BIGINT      NOT NULL,

    -- Lifts the own-department block for this pairing only (P-2.4).
    explicit_grant BOOLEAN     NOT NULL DEFAULT FALSE,

    -- Who granted it, for the segregation-of-duties trail. Nullable because a grant created
    -- by seeding or migration has no acting Super Admin behind it.
    granted_by     BIGINT      NULL,
    granted_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_hr_department_grant PRIMARY KEY (id),

    -- One row per pairing. Two grants for the same department, disagreeing on the explicit
    -- flag, would make the own-department decision depend on row order.
    CONSTRAINT uq_hr_grant_user_department UNIQUE (hr_user_id, department_id),

    CONSTRAINT fk_hr_grant_user       FOREIGN KEY (hr_user_id)    REFERENCES app_user (id),
    CONSTRAINT fk_hr_grant_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_hr_grant_granted_by FOREIGN KEY (granted_by)    REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The scope lookup runs on every HR request, so it is indexed rather than left to the FK.
CREATE INDEX ix_hr_grant_user ON hr_department_grant (hr_user_id);
