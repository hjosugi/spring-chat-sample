package io.github.hjosugi.chatsample.chatclient;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Keeps what a logger writes while it is open, at the level it is opened with, and puts
 * the logger back as it was when it is closed.
 */
final class LogCapture implements AutoCloseable {

	private final Logger logger;

	private final Level level;

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	LogCapture(Class<?> type, Level level) {
		this(type.getName(), level);
	}

	LogCapture(String name, Level level) {
		this.logger = (Logger) LoggerFactory.getLogger(name);
		this.level = this.logger.getLevel();
		this.appender.start();
		this.logger.addAppender(this.appender);
		this.logger.setLevel(level);
	}

	/**
	 * The messages so far, formatted, oldest first.
	 */
	List<String> messages() {
		return this.appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	/**
	 * The events so far, oldest first.
	 */
	List<ILoggingEvent> events() {
		return List.copyOf(this.appender.list);
	}

	@Override
	public void close() {
		this.logger.detachAppender(this.appender);
		this.logger.setLevel(this.level);
	}

}
