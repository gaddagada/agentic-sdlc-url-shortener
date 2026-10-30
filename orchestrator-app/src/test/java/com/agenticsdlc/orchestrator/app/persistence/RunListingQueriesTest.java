package com.agenticsdlc.orchestrator.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.app.runs.RunListPage;
import com.agenticsdlc.orchestrator.app.runs.RunListQuery;
import com.agenticsdlc.orchestrator.app.runs.RunListSort;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

/**
 * The projection queries of the runs list ({@link RunListingQueries} through {@link JpaRunListing}) on H2 with the
 * Flyway schema: the listing and progress columns become list items, the database cuts the requirement, and paging,
 * the exact-match filters and the whitelisted orders (ties broken by id) behave as documented.
 */
@DataJpaTest
@Import({JpaRunStore.class, JpaRunListing.class, PersistenceFixtures.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunListingQueriesTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired
    private JpaRunStore store;

    @Autowired
    private JpaRunListing listing;

    @Autowired
    private RunRepository runs;

    @Autowired
    private WorkflowGraph graph;

    @BeforeEach
    void fiveRuns() {
        save("r1", "greenfield", RunStatus.COMPLETED, "alice", 1, "Build a URL shortener.");
        save("r2", "greenfield", RunStatus.WAITING_HUMAN, "bob", 2, "Add click analytics. " + "x".repeat(400));
        save("r3", "brownfield", RunStatus.RUNNING, "alice", 3, "Add a bulk link API.");
        save("r4", "greenfield", RunStatus.SAFE_STOPPED, "carol", 4, "Remove URL validation.");
        save("r5", "ambiguous", RunStatus.WAITING_HUMAN, "alice", 5, "Make it faster.");
    }

    @AfterEach
    void cleanUp() {
        runs.deleteAll();
    }

    @Test
    void itemsAreTheListingColumnsWithTheRequirementCutByTheDatabase() {
        RunListItem item = listing.find("r2").orElseThrow();

        assertThat(item.scenario()).isEqualTo("greenfield");
        assertThat(item.status()).isEqualTo("WAITING_HUMAN");
        assertThat(item.requirement()).hasSize(RunListItem.REQUIREMENT_PROBE).startsWith("Add click analytics.");
        assertThat(item.planVersion()).isEqualTo(1);
        assertThat(item.createdAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(item.updatedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(item.autonomy()).isEqualTo("FULL_AUTO");
        assertThat(item.workspaceMode()).isEqualTo("REPO");
        assertThat(item.startedBy()).as("from the createdBy tag").isEqualTo("bob");
        assertThat(item.riskLevel()).isNotBlank();
        assertThat(item.pendingActions()).isZero();
        assertThat(item.stagesSucceeded()).isZero();
        assertThat(item.stagesTotal()).isEqualTo(graph.stages().size());
        assertThat(listing.find("r1").orElseThrow().requirement()).isEqualTo("Build a URL shortener.");
        assertThat(listing.find("missing")).isEmpty();
    }

    @Test
    void pagesAreCountedOverAllMatchingRows() {
        RunListPage first = listing.page(new RunListQuery(0, 2, List.of(), null, null, created(true)));
        RunListPage last = listing.page(new RunListQuery(2, 2, List.of(), null, null, created(true)));
        RunListPage beyond = listing.page(new RunListQuery(9, 2, List.of(), null, null, created(true)));

        assertThat(first.items()).extracting(RunListItem::id).containsExactly("r5", "r4");
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(last.items()).extracting(RunListItem::id).containsExactly("r1");
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(5);
    }

    @Test
    void filtersAreExactMatchesCombinedWithAnd() {
        assertThat(ids(new RunListQuery(0, 50, List.of("WAITING_HUMAN"), null, null, created(false))))
                .containsExactly("r2", "r5");
        assertThat(ids(new RunListQuery(0, 50, List.of("RUNNING", "SAFE_STOPPED"), null, null, created(false))))
                .containsExactly("r3", "r4");
        assertThat(ids(new RunListQuery(0, 50, List.of(), "greenfield", null, created(false))))
                .containsExactly("r1", "r2", "r4");
        assertThat(ids(new RunListQuery(0, 50, List.of(), null, "alice", created(false))))
                .containsExactly("r1", "r3", "r5");
        assertThat(ids(new RunListQuery(0, 50, List.of("WAITING_HUMAN"), "greenfield", "bob", created(false))))
                .containsExactly("r2");
        RunListPage none = listing.page(new RunListQuery(0, 50, List.of("ABORTED"), null, null, created(false)));
        assertThat(none.items()).isEmpty();
        assertThat(none.total()).isZero();
        assertThat(none.totalPages()).isZero();
    }

    @Test
    void everyWhitelistedFieldOrdersTheListWithTheIdAsTieBreaker() {
        assertThat(ids(new RunListQuery(0, 50, List.of(), null, null, RunListSort.DEFAULT)))
                .as("updatedAt desc").containsExactly("r5", "r4", "r3", "r2", "r1");
        assertThat(ids(new RunListQuery(0, 50, List.of(), null, null,
                new RunListSort(RunListSort.Field.SCENARIO, false))))
                .containsExactly("r5", "r3", "r1", "r2", "r4");
        assertThat(ids(new RunListQuery(0, 50, List.of(), null, null,
                new RunListSort(RunListSort.Field.STARTED_BY, true))))
                .containsExactly("r4", "r2", "r5", "r3", "r1");
        assertThat(ids(new RunListQuery(0, 50, List.of(), null, null,
                new RunListSort(RunListSort.Field.STATUS, false))))
                .containsExactly("r1", "r3", "r4", "r2", "r5");
        for (RunListSort.Field field : RunListSort.Field.values()) {
            assertThat(ids(new RunListQuery(0, 50, List.of(), null, null, new RunListSort(field, true))))
                    .as("sort by %s", field).hasSize(5);
        }
    }

    @Test
    void theProgressColumnsFollowEverySave() {
        Run run = store.load("r3").orElseThrow();
        run.stage("intake").setStatus(com.agenticsdlc.orchestrator.core.model.StageStatus.SUCCEEDED);
        run.stage("decompose").setStatus(com.agenticsdlc.orchestrator.core.model.StageStatus.AWAITING_APPROVAL);
        store.save(run);

        RunListItem item = listing.find("r3").orElseThrow();
        assertThat(item.stagesSucceeded()).isEqualTo(1);
        assertThat(item.pendingActions()).isEqualTo(1);
    }

    private List<String> ids(RunListQuery query) {
        return listing.page(query).items().stream().map(RunListItem::id).toList();
    }

    private static RunListSort created(boolean descending) {
        return new RunListSort(RunListSort.Field.CREATED_AT, descending);
    }

    private void save(String id, String scenario, RunStatus status, String starter, int second, String requirement) {
        RunOptions options = new RunOptions(id.equals("r2") ? AutonomyLevel.FULL_AUTO : AutonomyLevel.GUIDED, null,
                List.of(), null, id.equals("r2") ? WorkspaceMode.REPO : WorkspaceMode.NONE,
                Map.of(JpaRunStore.CREATED_BY_TAG, starter));
        Run run = Run.create(id, graph, scenario, requirement, options, T0.plusSeconds(second));
        run.setStatus(status);
        store.save(run);
    }
}
