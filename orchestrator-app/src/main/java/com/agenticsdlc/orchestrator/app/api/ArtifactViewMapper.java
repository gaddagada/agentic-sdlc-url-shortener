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
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.audit.ChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Run;

import tools.jackson.core.type.TypeReference;

/**
 * Maps the artifacts of a run to the artifact, artifact summary and lineage views. Payloads are exposed as JSON
 * objects converted with the canonical {@link JsonCodec}, so the API shows exactly the JSON that was hashed. The
 * change record signature of a release-readiness artifact is verified with the configured
 * {@link ChangeRecordSigner} each time the view is built, over the record as currently stored.
 */
@Component
public class ArtifactViewMapper {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final JsonCodec codec;
    private final ChangeRecordSigner signer;

    /**
     * Creates the mapper.
     *
     * @param codec JSON codec
     * @param signer the configured change record signer (verifies release signatures)
     */
    public ArtifactViewMapper(JsonCodec codec, ChangeRecordSigner signer) {
        this.codec = codec;
        this.signer = signer;
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
        ArtifactView.SignatureView signature = null;
        Boolean verified = null;
        if (artifact.payload() instanceof ReleaseReadiness release && release.changeRecord() != null
                && release.changeRecord().signature() != null) {
            ReleaseReadiness.Signature s = release.changeRecord().signature();
            signature = new ArtifactView.SignatureView(s.algorithm(), s.keyId(), s.value(), s.signedAt());
            verified = verify(release.changeRecord());
        }
        return new ArtifactView(artifact.id(), artifact.stageId(), artifact.kind().name(), artifact.version(),
                artifact.planVersion(), artifact.hash(), accepted(run, artifact), artifact.createdAt(),
                ProvenanceView.from(artifact.provenance()), payload, artifact.markdown(), signature, verified);
    }

    private boolean verify(ReleaseReadiness.ChangeRecord record) {
        try {
            return signer.verify(record);
        } catch (RuntimeException e) {
            return false;
        }
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
        List<LineageView.LineageNodeView> nodes = new ArrayList<>();
        for (Artifact a : run.artifacts()) {
            idByStageVersion.put(key(a.stageId(), a.version()), a.id());
            nodes.add(new LineageView.LineageNodeView(a.id(), a.stageId(), a.kind().name(), a.version(), a.hash(),
                    accepted(run, a)));
        }
        List<LineageView.LineageEdgeView> edges = new ArrayList<>();
        for (Artifact a : run.artifacts()) {
            a.provenance().derivedFrom().forEach((stageId, version) -> {
                String from = idByStageVersion.get(key(stageId, version));
                if (from != null) {
                    edges.add(new LineageView.LineageEdgeView(from, a.id()));
                }
            });
        }
        return new LineageView(List.copyOf(nodes), List.copyOf(edges));
    }

    private ArtifactSummaryView summary(Run run, Artifact a) {
        return new ArtifactSummaryView(a.id(), a.stageId(), a.kind().name(), a.version(), a.planVersion(), a.hash(),
                accepted(run, a), a.createdAt(), ProvenanceView.from(a.provenance()));
    }

    /**
     * Whether an artifact version is the accepted version of its stage. Acceptance happens only after the exit gate
     * (or its approval), so a version under review is not accepted yet.
     */
    private static boolean accepted(Run run, Artifact artifact) {
        return Objects.equals(run.acceptedVersions().get(artifact.stageId()), artifact.version());
    }

    private static String key(String stageId, int version) {
        return stageId + "@" + version;
    }
}
