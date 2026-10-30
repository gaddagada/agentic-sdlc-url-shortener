/**
 * Read model of the runs list: the listing columns of the {@code runs} table ({@link RunListItem}), the paged,
 * filterable query over them ({@link RunListQuery}, {@link RunListing}) and its result ({@link RunListPage}). The
 * persistence layer implements {@link RunListing}; the web layer depends only on this package, never on JPA. The
 * persistence layer reports a write refused because another writer changed the run first as a
 * {@link RunConflictException}, which the web layer maps to 409 {@code run-conflict}.
 */
package com.agenticsdlc.orchestrator.app.runs;
