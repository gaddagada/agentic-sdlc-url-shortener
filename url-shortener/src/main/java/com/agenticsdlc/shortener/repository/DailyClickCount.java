package com.agenticsdlc.shortener.repository;

import java.time.LocalDate;

/**
 * Query projection: number of clicks on one UTC day.
 *
 * @param day    UTC calendar day
 * @param clicks clicks on that day
 */
public record DailyClickCount(LocalDate day, long clicks) {
}
