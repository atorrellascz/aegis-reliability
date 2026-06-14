package com.aegis.workitem;

import java.time.Instant;

/**
 * A unit of work (task/project) -- the core domain entity of a work-management tool.
 * Kept as a record because it is an immutable read model here.
 */
public record WorkItem(
        long id,
        String title,
        String description,
        String status,
        String priority,
        String assignee,
        Instant updatedAt
) {}
