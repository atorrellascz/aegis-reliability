-- V3__enforce_assignee_not_null.sql
-- Zero-downtime column add, phase 2 of 2.
--
-- By the time this runs, V2 has backfilled every existing row and the new app
-- version -- which always writes assignee -- is the only one live. Now it is
-- safe to close the contract: give the column a DEFAULT (so future inserts that
-- omit it still get a value) and enforce NOT NULL (so the column is now a hard
-- guarantee the application can rely on).
--
-- Splitting this from V2 across two deploys is the whole point: it is what makes
-- the change safe under a rolling deploy instead of a flag-day migration.

ALTER TABLE work_item
    ALTER COLUMN assignee SET DEFAULT 'unassigned';

ALTER TABLE work_item
    ALTER COLUMN assignee SET NOT NULL;
