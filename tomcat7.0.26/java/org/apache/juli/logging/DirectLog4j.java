package org.apache.juli.logging;

import org.apache.commons.logging.impl.Log4JLogger;

public class DirectLog4j extends Log4JLogger implements Log {

	private static final long serialVersionUID = 1L;

	static Log getInstance(String name) {
		return new DirectLog4j(name);
	}

	public DirectLog4j(String name) {
		super(name);
	}
}
