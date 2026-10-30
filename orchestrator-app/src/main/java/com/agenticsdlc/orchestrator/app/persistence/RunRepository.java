package com.agenticsdlc.orchestrator.app.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository of {@link RunEntity}.
 */
public interface RunRepository extends JpaRepository<RunEntity, String> {

    /**
     * Returns all runs, oldest first.
     *
     * @return runs
     */
    List<RunEntity> findAllByOrderByCreatedAtAscIdAsc();
}
