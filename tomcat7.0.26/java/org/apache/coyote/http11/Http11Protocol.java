/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http11;

import java.net.Socket;

import org.apache.coyote.AbstractProtocol;
import org.apache.juli.logging.Log;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.JIoEndpoint;
import org.apache.tomcat.util.net.JIoEndpoint.Handler;
import org.apache.tomcat.util.net.SSLImplementation;
import org.apache.tomcat.util.net.SocketWrapper;

/**
 * Abstract the protocol implementation, including threading, etc. Processor is
 * single threaded and specific to stream-based protocols, will not fit Jk
 * protocols like JNI.
 * 
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class Http11Protocol extends AbstractHttp11JsseProtocol {

	private static final org.apache.juli.logging.Log log = org.apache.juli.logging.LogFactory
			.getLog(Http11Protocol.class);

	@Override
	protected Log getLog() {
		return log;
	}

	@Override
	protected AbstractEndpoint.Handler getHandler() {
		return cHandler;
	}

	// ------------------------------------------------------------ Constructor

	public Http11Protocol() {
		endpoint = new JIoEndpoint();
		cHandler = new Http11ConnectionHandler(this);
		((JIoEndpoint) endpoint).setHandler(cHandler);
		setSoLinger(Constants.DEFAULT_CONNECTION_LINGER);
		setSoTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT);
		setTcpNoDelay(Constants.DEFAULT_TCP_NO_DELAY);
	}

	// ----------------------------------------------------------------- Fields

	protected Http11ConnectionHandler cHandler;

	// ------------------------------------------------ HTTP specific properties
	// ------------------------------------------ managed in the ProtocolHandler

	private int disableKeepAlivePercentage = 75;

	public int getDisableKeepAlivePercentage() {
		return disableKeepAlivePercentage;
	}

	public void setDisableKeepAlivePercentage(int disableKeepAlivePercentage) {
		if (disableKeepAlivePercentage < 0) {
			this.disableKeepAlivePercentage = 0;
		} else if (disableKeepAlivePercentage > 100) {
			this.disableKeepAlivePercentage = 100;
		} else {
			this.disableKeepAlivePercentage = disableKeepAlivePercentage;
		}
	}

	// ----------------------------------------------------- JMX related methods

	@Override
	protected String getNamePrefix() {
		return ("http-bio");
	}

	// ----------------------------------- Http11ConnectionHandler Inner Class

	protected static class Http11ConnectionHandler extends AbstractConnectionHandler<Socket, Http11Processor> implements
			Handler {

		protected Http11Protocol proto;

		Http11ConnectionHandler(Http11Protocol proto) {
			this.proto = proto;
		}

		@Override
		protected AbstractProtocol getProtocol() {
			return proto;
		}

		@Override
		protected Log getLog() {
			return log;
		}

		@Override
		public SSLImplementation getSslImplementation() {
			return proto.sslImplementation;
		}

		/**
		 * Expected to be used by the handler once the processor is no longer
		 * required.
		 * 
		 * @param socket
		 *            Not used in BIO
		 * @param processor
		 * @param isSocketClosing
		 *            Not used in HTTP
		 * @param addToPoller
		 *            Not used in BIO
		 */
		@Override
		public void release(SocketWrapper<Socket> socket, Http11Processor processor, boolean isSocketClosing,
				boolean addToPoller) {
			processor.recycle();
			recycledProcessors.offer(processor);
		}

		@Override
		protected void initSsl(SocketWrapper<Socket> socket, Http11Processor processor) {
			if (proto.isSSLEnabled() && (proto.sslImplementation != null)) {
				processor.setSSLSupport(proto.sslImplementation.getSSLSupport(socket.getSocket()));
			} else {
				processor.setSSLSupport(null);
			}

		}

		@Override
		protected void longPoll(SocketWrapper<Socket> socket, Http11Processor processor) {
			connections.put(socket.getSocket(), processor);
		}

		@Override
		protected Http11Processor createProcessor() {
			
			log.info("创建Http1.1连接处理器！");
			Http11Processor processor = new Http11Processor(proto.getMaxHttpHeaderSize(), (JIoEndpoint) proto.endpoint,
					proto.getMaxTrailerSize());
			processor.setAdapter(proto.adapter);
			processor.setMaxKeepAliveRequests(proto.getMaxKeepAliveRequests());
			processor.setKeepAliveTimeout(proto.getKeepAliveTimeout());
			processor.setConnectionUploadTimeout(proto.getConnectionUploadTimeout());
			processor.setDisableUploadTimeout(proto.getDisableUploadTimeout());
			processor.setCompressionMinSize(proto.getCompressionMinSize());
			processor.setCompression(proto.getCompression());
			processor.setNoCompressionUserAgents(proto.getNoCompressionUserAgents());
			processor.setCompressableMimeTypes(proto.getCompressableMimeTypes());
			processor.setRestrictedUserAgents(proto.getRestrictedUserAgents());
			processor.setSocketBuffer(proto.getSocketBuffer());
			processor.setMaxSavePostSize(proto.getMaxSavePostSize());
			processor.setServer(proto.getServer());
			processor.setDisableKeepAlivePercentage(proto.getDisableKeepAlivePercentage());
			register(processor);
			return processor;
		}
	}
}
