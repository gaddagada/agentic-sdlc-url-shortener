package com.agenticsdlc.orchestrator.app.persistence;

import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.NullOrdering;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

/**
 * Every {@link RunListingQueriesTest} on an H2 database that orders NULLs like PostgreSQL: the highest value, so last
 * in ascending and first in descending order (H2's own default is the opposite). The Hibernate dialect is told the
 * same, as Hibernate's PostgreSQL dialect assumes it, so the SQL is rendered as for PostgreSQL. The documented order of
 * the runs list (rows without a value last in either direction) is thus checked under both conventions in every
 * build, not only in the Docker-based PostgreSQL integration test.
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:listing-postgres-null-order;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.database-platform=com.agenticsdlc.orchestrator.app.persistence."
                + "PostgresNullOrderRunListingQueriesTest$NullsHighestH2Dialect"})
class PostgresNullOrderRunListingQueriesTest extends RunListingQueriesTest {

    /**
     * H2 with NULLs as the highest value, the null ordering Hibernate assumes for PostgreSQL.
     */
    public static class NullsHighestH2Dialect extends H2Dialect {

        /** Creates the dialect for the latest H2 version. */
        public NullsHighestH2Dialect() {
        }

        /**
         * Creates the dialect for the connected database.
         *
         * @param info the connection's metadata
         */
        public NullsHighestH2Dialect(DialectResolutionInfo info) {
            super(info);
        }

        @Override
        public NullOrdering getNullOrdering() {
            return NullOrdering.GREATEST;
        }
    }
}
