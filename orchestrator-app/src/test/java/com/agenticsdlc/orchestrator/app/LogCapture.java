package com.agenticsdlc.orchestrator.app;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Captures the log events of one logger at a given level for a test (the test logging configuration keeps the
 * application loggers at WARN), and restores the logger when closed.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type, Level level) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        this.previousLevel = logger.getLevel();
        logger.setLevel(level);
        appender.start();
        logger.addAppender(appender);
    }

    /**
     * Starts capturing the events of a class's logger at a level and above.
     *
     * @param type the class whose logger is captured
     * @param level the lowest captured level
     * @return the capture (close it to restore the logger)
     */
    public static LogCapture of(Class<?> type, Level level) {
        return new LogCapture(type, level);
    }

    /**
     * Returns the captured events as {@code LEVEL message} lines.
     *
     * @return the lines in logging order
     */
    public List<String> lines() {
        return appender.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
