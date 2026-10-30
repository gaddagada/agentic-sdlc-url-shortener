/**
 * Evaluation harness: deterministic quality scores for the artifacts of a run ({@link
 * com.agenticsdlc.orchestrator.core.evaluation.RunEvaluator}), and a suite of requirement scenarios with expected
 * outcomes that is executed through the engine ({@link com.agenticsdlc.orchestrator.core.evaluation.EvaluationSuiteRunner}).
 * Scores measure structural quality computable from the artifacts themselves (coverage, consistency, completeness);
 * they do not judge whether generated code is correct.
 */
@org.jspecify.annotations.NullMarked
package com.agenticsdlc.orchestrator.core.evaluation;
