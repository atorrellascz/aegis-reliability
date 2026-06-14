-- V1__init.sql
-- Flyway migration. Internal DB schema migration tooling is core reliability work;
-- versioned migrations like this are the disciplined way to evolve a schema
-- without downtime. New changes go in V2__*.sql, V3__*.sql, etc.

CREATE TABLE IF NOT EXISTS work_item (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       TEXT        NOT NULL,
    description TEXT        NOT NULL DEFAULT '',
    status      TEXT        NOT NULL DEFAULT 'open',
    priority    TEXT        NOT NULL DEFAULT 'medium',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Composite index supporting the common "open items by priority" access pattern.
-- Column order matters: equality column (status) first, then the column used for
-- ordering/filtering (priority). Verify with EXPLAIN ANALYZE that this index is
-- actually chosen and you are not doing a Seq Scan.
CREATE INDEX IF NOT EXISTS idx_work_item_status_priority
    ON work_item (status, priority);

-- Seed a few rows so the endpoints return something on first run.
INSERT INTO work_item (title, description, status, priority) VALUES
  ('Migrate auth service to new pool', 'Connection pool exhaustion during peak', 'open', 'high'),
  ('Add jitter to cache TTLs', 'Prevent synchronized expiry stampede', 'open', 'medium'),
  ('Document on-call runbook for Redis failover', 'Steps for primary loss', 'in_progress', 'high'),
  ('Tidy up legacy cron logs', 'Low urgency cleanup', 'open', 'low');
