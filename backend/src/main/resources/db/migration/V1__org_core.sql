-- V1 — Organisational core: departments, users, reporting lines, roles.
--
-- This is the foundation the entire authorization layer queries against:
--   * app_user.manager_id  -> P-1.1 direct-reports (the hot path of every scoped query)
--   * app_user.department_id -> P-2.1/P-2.3 HR department scoping
--   * app_user.active      -> P-0.7 soft delete
--   * user_role            -> P-0.1 additive roles, and P-1.5/P-7.2 Leadership exclusion
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

CREATE TABLE department (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_department PRIMARY KEY (id),
    CONSTRAINT uq_department_name UNIQUE (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE app_user (
    id                BIGINT       NOT NULL AUTO_INCREMENT,

    -- The Asgardeo `sub` claim. This is the only link between a JWT and a row here;
    -- everything the authorization layer decides hangs off resolving it.
    asgardeo_subject  VARCHAR(255) NOT NULL,

    email             VARCHAR(255) NOT NULL,
    full_name         VARCHAR(200) NOT NULL,

    -- Nullable: Leadership sit above the department structure.
    department_id     BIGINT       NULL,

    -- Self-referencing single manager (P-1.4). NULL only at the top of the chain.
    -- Loop rejection is enforced in the service by walking up the chain; the database
    -- cannot express it, and without it the direct-reports query never terminates.
    manager_id        BIGINT       NULL,

    -- Soft delete (P-0.7 / constraint 8). Deactivated users are excluded from peer
    -- selection, cohort intake and newly opened cycles, but their rows and history remain.
    active            BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_asgardeo_subject UNIQUE (asgardeo_subject),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT fk_app_user_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_app_user_manager    FOREIGN KEY (manager_id)    REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The direct-reports lookup runs on effectively every scoped query, so it is indexed
-- rather than left to the FK alone.
CREATE INDEX ix_app_user_manager     ON app_user (manager_id);
CREATE INDEX ix_app_user_department  ON app_user (department_id);
CREATE INDEX ix_app_user_active      ON app_user (active);

-- Roles are additive: every principal is an Employee in addition to any other role (P-0.1).
--
-- Asgardeo remains authoritative for the *caller's* own authorities, which arrive in the JWT.
-- These rows exist because the authorization layer must also reason about *other* users'
-- roles — for example P-1.5/P-7.2, which forbid creating any review or plan artifact for a
-- Leadership member. The caller's token cannot answer that question about someone else.
CREATE TABLE user_role (
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    user_id  BIGINT      NOT NULL,
    role     VARCHAR(32) NOT NULL,
    CONSTRAINT pk_user_role PRIMARY KEY (id),
    CONSTRAINT uq_user_role UNIQUE (user_id, role),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_user_role_value CHECK (role IN
        ('EMPLOYEE', 'MANAGER', 'HR', 'LEADERSHIP', 'SUPER_ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_user_role_user ON user_role (user_id);
CREATE INDEX ix_user_role_role ON user_role (role);
