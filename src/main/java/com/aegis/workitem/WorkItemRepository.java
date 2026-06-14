package com.aegis.workitem;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Postgres access via JdbcTemplate (no ORM on purpose) so the SQL is explicit
 * and you can reason about indexes and EXPLAIN ANALYZE explicitly.
 *
 * The lookup is by primary key, which uses the implicit B-tree on work_item(id).
 * The status/priority query in V1__init.sql is what the composite index targets.
 */
@Repository
public class WorkItemRepository {

    private final JdbcTemplate jdbc;

    public WorkItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WorkItem> MAPPER = (rs, n) -> new WorkItem(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("priority"),
            rs.getString("assignee"),
            rs.getTimestamp("updated_at").toInstant()
    );

    public Optional<WorkItem> findById(long id) {
        try {
            WorkItem item = jdbc.queryForObject(
                    "SELECT id, title, description, status, priority, assignee, updated_at " +
                            "FROM work_item WHERE id = ?",
                    MAPPER, id);
            return Optional.ofNullable(item);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
