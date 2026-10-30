package com.agenticsdlc.orchestrator.app.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.app.runs.RunListPage;
import com.agenticsdlc.orchestrator.app.runs.RunListQuery;
import com.agenticsdlc.orchestrator.app.runs.RunListing;

/**
 * {@link RunListing} over the {@code runs} table: the projection queries of {@link RunRepository} in a read-only
 * transaction. The page and its count are read in the same transaction.
 */
@Component
public class JpaRunListing implements RunListing {

    private final RunRepository runs;
    private final TransactionTemplate readTransactions;

    /**
     * Creates the listing.
     *
     * @param runs run repository
     * @param transactionManager transaction manager
     */
    public JpaRunListing(RunRepository runs, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
    }

    @Override
    public RunListPage page(RunListQuery query) {
        RunListPage page = readTransactions.execute(status -> runs.findListing(query));
        return page == null ? new RunListPage(List.of(), query.page(), query.size(), 0L) : page;
    }

    @Override
    public Optional<RunListItem> find(String runId) {
        Optional<RunListItem> item = readTransactions.execute(status -> runs.findListItem(runId));
        return item == null ? Optional.empty() : item;
    }
}
