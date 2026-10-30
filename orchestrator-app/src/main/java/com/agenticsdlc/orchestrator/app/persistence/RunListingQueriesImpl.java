package com.agenticsdlc.orchestrator.app.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CompoundSelection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.app.runs.RunListPage;
import com.agenticsdlc.orchestrator.app.runs.RunListQuery;

/**
 * Criteria API implementation of {@link RunListingQueries}: a constructor projection of the listing columns, the
 * filters as predicates (only those that are set, so no {@code :param IS NULL} tricks that PostgreSQL cannot type),
 * the whitelisted sort field plus the id as tie breaker, and a count query with the same predicates.
 */
class RunListingQueriesImpl implements RunListingQueries {

    private static final String ID = "id";

    private final EntityManager entityManager;

    /**
     * Creates the fragment.
     *
     * @param entityManager the shared, transaction-bound entity manager
     */
    RunListingQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public RunListPage findListing(RunListQuery query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<RunListItem> select = cb.createQuery(RunListItem.class);
        Root<RunEntity> run = select.from(RunEntity.class);
        select.select(projection(cb, run)).where(filters(cb, run, query));
        Path<Object> sortPath = run.get(query.sort().field().apiName());
        Order primary = query.sort().descending() ? cb.desc(sortPath) : cb.asc(sortPath);
        Order tieBreaker = query.sort().descending() ? cb.desc(run.get(ID)) : cb.asc(run.get(ID));
        select.orderBy(primary, tieBreaker);
        List<RunListItem> items = entityManager.createQuery(select)
                .setFirstResult((int) Math.min(Integer.MAX_VALUE, query.offset()))
                .setMaxResults(query.size())
                .getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<RunEntity> counted = count.from(RunEntity.class);
        count.select(cb.count(counted)).where(filters(cb, counted, query));
        long total = entityManager.createQuery(count).getSingleResult();
        return new RunListPage(items, query.page(), query.size(), total);
    }

    @Override
    public Optional<RunListItem> findListItem(String runId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<RunListItem> select = cb.createQuery(RunListItem.class);
        Root<RunEntity> run = select.from(RunEntity.class);
        select.select(projection(cb, run)).where(cb.equal(run.get(ID), runId));
        return entityManager.createQuery(select).getResultStream().findFirst();
    }

    private static CompoundSelection<RunListItem> projection(CriteriaBuilder cb, Root<RunEntity> run) {
        return cb.construct(RunListItem.class, run.get(ID), run.get("scenario"), run.get("status"),
                run.get("haltReason"), cb.substring(run.get("requirement"), 1, RunListItem.REQUIREMENT_PROBE),
                run.get("planVersion"), run.get("createdAt"), run.get("updatedAt"), run.get("endedAt"),
                run.get("autonomy"), run.get("workspaceMode"), run.get("startedBy"), run.get("riskLevel"),
                run.get("nodeId"), run.get("pendingActions"), run.get("stagesSucceeded"), run.get("stagesTotal"));
    }

    private static Predicate[] filters(CriteriaBuilder cb, Root<RunEntity> run, RunListQuery query) {
        List<Predicate> predicates = new ArrayList<>();
        if (!query.statuses().isEmpty()) {
            predicates.add(run.get("status").in(query.statuses()));
        }
        if (query.scenario() != null) {
            predicates.add(cb.equal(run.get("scenario"), query.scenario()));
        }
        if (query.startedBy() != null) {
            predicates.add(cb.equal(run.get("startedBy"), query.startedBy()));
        }
        return predicates.toArray(Predicate[]::new);
    }
}
