package com.altrium.export;

import org.springframework.data.repository.Repository;

/**
 * Writes only.
 *
 * <p>There is no finder here and that is deliberate: nothing in the application reads this
 * table. The log exists to be examined afterwards, by somebody with database access and a
 * reason, and an endpoint that returned it would be one more place where "who looked at what"
 * could itself be read without leaving a trace. Add a read when a use case for one exists.
 */
public interface ExportLogRepository extends Repository<ExportLog, Long> {

    ExportLog save(ExportLog entry);
}
