-- V2__add_assignee.sql
-- Zero-downtime column add, phase 1 of 2 (the safe-migration pattern).
--
-- Adding a NULLABLE column is a fast, metadata-only change in Postgres: it does
-- NOT rewrite the table and does NOT take a long lock, so it is safe even on a
-- large, busy table. We deliberately do NOT add NOT NULL or a DEFAULT here:
-- during a rolling deploy the old app version is still running and would insert
-- rows without an assignee, which a NOT NULL column would reject.
--
-- Phase 2 (enforce NOT NULL) ships later, in V3, once every row has a value and
-- only the new app version -- which always writes assignee -- is live.

ALTER TABLE work_item
    ADD COLUMN assignee TEXT;

-- Backfill existing rows. New code can start reading assignee immediately and
-- will see 'unassigned' instead of NULL for historical work items.
UPDATE work_item
    SET assignee = 'unassigned'
    WHERE assignee IS NULL;
