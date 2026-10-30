package com.agenticsdlc.orchestrator.app.persistence;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository of {@link ArtifactEntity}.
 */
public interface ArtifactRepository extends JpaRepository<ArtifactEntity, String> {

    /**
     * Returns the ids of the artifacts already persisted for a run.
     *
     * @param runId run id
     * @return artifact ids
     */
    @Query("select a.id from ArtifactEntity a where a.runId = :runId")
    Set<String> findIdsByRunId(@Param("runId") String runId);

    /**
     * Returns the artifacts of a run ordered by stage and version.
     *
     * @param runId run id
     * @return artifacts
     */
    List<ArtifactEntity> findByRunIdOrderByStageIdAscVersionAsc(String runId);
}
