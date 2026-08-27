-- V8 - A development goal is drafted by the manager and agreed by the employee.
--
-- Product Owner ruling, and a deviation from scenario section 8, which describes the PDP as
-- "collaborative between manager and employee" and section 6, which says the two of them own
-- it. Under the ruling the employee no longer writes goals: the manager drafts them, submits
-- them, and the employee agrees to what they have been given and then reports progress against
-- it. The collaboration survives as agreement and progress rather than as shared authorship.
--
-- The lifecycle is per goal, not per plan. A development plan is permanent and gains goals
-- through the year, so a goal added in month six is agreed on its own rather than reopening
-- everything already settled - and one contested goal does not hold up the rest.
--
--   DRAFT     the manager is still writing it; the employee cannot see it at all
--   PENDING   submitted; the employee can see it and agree to it
--   AGREED    the employee has agreed; the wording is fixed and progress reporting opens
--
-- This is a second axis, not a replacement for `status`. `status` remains OPEN or COMPLETE and
-- records whether the manager has approved the goal as finished (P-5.2). A goal can be AGREED
-- and OPEN, or AGREED and COMPLETE; it can never be COMPLETE without being AGREED.
--
-- IMMUTABLE once applied (constraint 7). Any change is a new numbered migration.

ALTER TABLE plan_goal
    -- NULL for improvement goals, which have no agreement step and never will: a PIP is put to
    -- an employee rather than agreed with them, and an employee who could withhold agreement
    -- from a PIP goal could stall the plan indefinitely.
    ADD COLUMN agreement   VARCHAR(16) NULL AFTER status,
    ADD COLUMN submitted_at DATETIME(6) NULL AFTER agreement,
    ADD COLUMN agreed_at    DATETIME(6) NULL AFTER submitted_at,

    -- The employee's own words, kept apart from `detail`, which is the manager's. One field
    -- shared between them would mean the employee reporting progress could overwrite the goal
    -- they had agreed to, which is precisely what fixing the wording at agreement prevents.
    ADD COLUMN progress_note TEXT NULL AFTER agreed_at;

ALTER TABLE plan_goal
    ADD CONSTRAINT ck_plan_goal_agreement
        CHECK (agreement IS NULL OR agreement IN ('DRAFT', 'PENDING', 'AGREED'));

-- Existing development goals are treated as AGREED rather than DRAFT.
--
-- They were written under the old rule, by the employee or the manager, and are already being
-- worked on. Marking them DRAFT would hide every one of them from the employee whose plan they
-- are, and marking them PENDING would ask people to agree to goals they wrote themselves.
UPDATE plan_goal
SET agreement = 'AGREED',
    submitted_at = created_at,
    agreed_at = created_at
WHERE development_plan_id IS NOT NULL;
