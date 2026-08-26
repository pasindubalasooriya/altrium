-- V7 - The Super Admin becomes a dedicated platform account (P-9.5).
--
-- Until now the Super Admin was modelled as a role a person also held, and the seed made the
-- point deliberately: Devin Marsh was an ordinary engineer in Engineering reporting to Elena,
-- who happened to administer the system. That demonstrated P-9.4 nicely - platform
-- administration granting no access to review content.
--
-- The Product Owner has ruled otherwise. The Super Admin is a dedicated separate account and
-- holds no other role, and is never a reviewee. The reasoning is separation of duties: this
-- account grants the HR users their departments and configures the cycles, so a reviewing role
-- on top would let one account arrange the scope and then act inside it.
--
-- Three consequences, applied here to databases seeded before the rule existed. A fresh
-- database gets it from SeedData instead, and both have to agree.
--
-- EMPLOYEE is deliberately NOT stripped. It is not a job in this schema; it is the marker that
-- says a caller is provisioned and active, and SecurityConfig requires it on every authenticated
-- endpoint. Removing it would lock the Super Admin out of the administration console itself.
-- What P-9.5 removes is being a reviewee, and AuthorizationService enforces that at step 2.
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

-- 1. No reviewing role alongside SUPER_ADMIN.
DELETE ur FROM user_role ur
JOIN user_role admin ON admin.user_id = ur.user_id AND admin.role = 'SUPER_ADMIN'
WHERE ur.role IN ('MANAGER', 'HR', 'LEADERSHIP');

-- 2. No department and no manager. Both exist to place a reviewee: a department is the unit of
--    HR scoping, a manager is who reviews you. Neither applies to an account never reviewed.
--    Leaving the department set would also keep the account in an HR user's scope for no reason.
UPDATE app_user u
JOIN user_role ur ON ur.user_id = u.id AND ur.role = 'SUPER_ADMIN'
SET u.department_id = NULL,
    u.manager_id = NULL;

-- 3. Nobody reports to the Super Admin any more. Step 2 detached the account from its own
--    manager; this detaches anyone who reported to it. Without this they would keep a reporting
--    line to an account that can no longer review them, and their manager review would have no
--    author. There are none today - Devin managed nobody - but a database edited by hand since
--    the seed ran is exactly what a migration exists to correct.
UPDATE app_user u
JOIN user_role ur ON ur.user_id = u.manager_id AND ur.role = 'SUPER_ADMIN'
SET u.manager_id = NULL;

-- 4. A Super Admin already sitting in a cohort would be enrolled by the next sweep into a cycle
--    whose every artifact the authorization layer now refuses - a participant with no readable
--    review. Removing the standing assignment stops that at the source.
DELETE cm FROM cohort_member cm
JOIN user_role ur ON ur.user_id = cm.user_id AND ur.role = 'SUPER_ADMIN';
