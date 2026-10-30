package com.agenticsdlc.orchestrator.app.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.app.api.dto.ArtifactSummaryView;
import com.agenticsdlc.orchestrator.app.api.dto.ArtifactView;
import com.agenticsdlc.orchestrator.app.api.dto.LineageView;
import com.agenticsdlc.orchestrator.app.api.dto.ProvenanceView;
import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Run;

import tools.jackson.core.type.TypeReference;

/**
 * Maps the artifacts of a run to the artifact, artifact summary and lineage views. Payloads are exposed as JSON
 * objects converted with the canonical {@link JsonCodec}, so the API shows exactly the JSON that was hashed.
 */
@Component
public class ArtifactViewMapper {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JsonCodec codec;

    /**
     * Creates the mapper.
     *
     * @param codec JSON codec
     */
    public ArtifactViewMapper(JsonCodec codec) {
        this.codec = codec;
    }

    /**
     * Returns the summaries of every artifact version of a run, in creation order.
     *
     * @param run a run snapshot
     * @return summaries
     */
    public List<ArtifactSummaryView> summaries(Run run) {
        return run.artifacts().stream().map(a -> summary(run, a)).toList();
    }

    /**
     * Returns the full view of one artifact version.
     *
     * @param run a run snapshot
     * @param artifact the artifact
     * @return the view
     */
    public ArtifactView view(Run run, Artifact artifact) {
        Map<String, Object> payload = codec.mapper().convertValue(artifact.payload(), OBJECT);
        return new ArtifactView(artifact.id(), artifact.stageId(), artifact.kind().name(), artifact.version(),
                artifact.planVersion(), artifact.hash(), accepted(run, artifact), artifact.createdAt(),
                ProvenanceView.from(artifact.provenance()), payload, artifact.markdown());
    }

    /**
     * Returns the lineage graph: one node per artifact version, one edge from every input artifact version (from
     * the provenance {@code derivedFrom}) to the artifact derived from it.
     *
     * @param run a run snapshot
     * @return the lineage
     */
    public LineageView lineage(Run run) {
        Map<String, String> idByStageVersion = new HashMap<>();
        List<LineageView.NodeView> nodes = new ArrayList<>();
        for (Artifact a : run.artifacts()) {
            idByStageVersion.put(key(a.stageId(), a.version()), a.id());
            nodes.add(new LineageView.NodeView(a.id(), a.stageId(), a.kind().name(), a.version(), a.hash(),
                    accepted(run, a)));
        }
        List<LineageView.EdgeView> edges = new ArrayList<>();
        for (Artifact a : run.artifacts()) {
            a.provenance().derivedFrom().forEach((stageId, version) -> {
                String from = idByStageVersion.get(key(stageId, version));
                if (from != null) {
                    edges.add(new LineageView.EdgeView(from, a.id()));
                }
            });
        }
        return new LineageView(List.copyOf(nodes), List.copyOf(edges));
    }

    private ArtifactSummaryView summary(Run run, Artifact a) {
        return new ArtifactSummaryView(a.id(), a.stageId(), a.kind().name(), a.version(), a.planVersion(), a.hash(),
                accepted(run, a), a.createdAt(), ProvenanceView.from(a.provenance()));
    }

    private static boolean accepted(Run run, Artifact artifact) {
        return Objects.equals(run.acceptedVersions().get(artifact.stageId()), artifact.version());
    }

    private static String key(String stageId, int version) {
        return stageId + "@" + version;
    }
}
