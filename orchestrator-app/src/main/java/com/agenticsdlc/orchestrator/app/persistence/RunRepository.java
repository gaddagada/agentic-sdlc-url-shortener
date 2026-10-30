package com.agenticsdlc.orchestrator.app.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository of {@link RunEntity}, with the projection queries of the paged runs list
 * ({@link RunListingQueries}).
 */
public interface RunRepository extends JpaRepository<RunEntity, String>, RunListingQueries {

    /**
     * Returns all runs, oldest first.
     *
     * @return runs
     */
    List<RunEntity> findAllByOrderByCreatedAtAscIdAsc();
}
