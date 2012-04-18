/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.catalina.startup;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Bootstrap loader for Catalina. This application constructs a class loader for
 * use in loading the Catalina internal classes (by accumulating all of the JAR
 * files found in the "server" directory under "catalina.home"), and starts the
 * regular execution of the container. The purpose of this roundabout approach
 * is to keep the Catalina internal classes (and any other classes they depend
 * on, such as an XML parser) out of the system class path and therefore not
 * visible to application level classes.
 * 
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @version $Id: Bootstrap.java 1201569 2011-11-14 01:36:07Z kkolinko $
 */

public final class Bootstrap {

	private static final Log log = LogFactory.getLog(Bootstrap.class);

	// ------------------------------------------------------- Static Variables

	/**
	 * Daemon object used by main.
	 */
	private static Bootstrap daemon = null;

	// -------------------------------------------------------------- Variables

	/**
	 * Daemon reference.
	 */
	private Object catalinaDaemon = null;

	protected ClassLoader commonLoader = null;
	protected ClassLoader catalinaLoader = null;
	protected ClassLoader sharedLoader = null;

	// -------------------------------------------------------- Private Methods

	private void initClassLoaders() {
		try {
			log.info("初始化类加载器： commonLoader,catalinaLoader,sharedLoader");
			commonLoader = createClassLoader("common", null);
			if (commonLoader == null) {
				// 如果没有config文件，则使用默认的类加载器，catalina跟webapp处于同一个环境中。
				commonLoader = this.getClass().getClassLoader();
			}
			catalinaLoader = createClassLoader("server", commonLoader);
			sharedLoader = createClassLoader("shared", commonLoader);
		} catch (Throwable t) {
			handleThrowable(t);
			log.error("Class loader creation threw exception", t);
			System.exit(1);
		}
	}

	private ClassLoader createClassLoader(String name, ClassLoader parent) throws Exception {

		String value = CatalinaProperties.getProperty(name + ".loader");
		if ((value == null) || (value.equals("")))
			return parent;

		value = replace(value);

		List<Repository> repositories = new ArrayList<Repository>();

		StringTokenizer tokenizer = new StringTokenizer(value, ",");
		while (tokenizer.hasMoreElements()) {
			String repository = tokenizer.nextToken().trim();
			if (repository.length() == 0) {
				continue;
			}

			// Check for a JAR URL repository
			try {
				@SuppressWarnings("unused")
				URL url = new URL(repository);
				repositories.add(new Repository(repository, RepositoryType.URL));
				continue;
			} catch (MalformedURLException e) {
				// Ignore
			}

			// Local repository
			if (repository.endsWith("*.jar")) {
				repository = repository.substring(0, repository.length() - "*.jar".length());
				repositories.add(new Repository(repository, RepositoryType.GLOB));
			} else if (repository.endsWith(".jar")) {
				repositories.add(new Repository(repository, RepositoryType.JAR));
			} else {
				repositories.add(new Repository(repository, RepositoryType.DIR));
			}
		}

		ClassLoader classLoader = ClassLoaderFactory.createClassLoader(repositories, parent);

		// Retrieving MBean server
		MBeanServer mBeanServer = null;
		if (MBeanServerFactory.findMBeanServer(null).size() > 0) {
			mBeanServer = MBeanServerFactory.findMBeanServer(null).get(0);
		} else {
			mBeanServer = ManagementFactory.getPlatformMBeanServer();
		}

		// Register the server classloader
		ObjectName objectName = new ObjectName("Catalina:type=ServerClassLoader,name=" + name);
		mBeanServer.registerMBean(classLoader, objectName);

		return classLoader;

	}

	/**
	 * System property replacement in the given string.
	 * 
	 * @param str
	 *            The original string
	 * @return the modified string
	 */
	protected String replace(String str) {
		// Implementation is copied from ClassLoaderLogManager.replace(),
		// but added special processing for catalina.home and catalina.base.
		String result = str;
		int pos_start = str.indexOf("${");
		if (pos_start >= 0) {
			StringBuilder builder = new StringBuilder();
			int pos_end = -1;
			while (pos_start >= 0) {
				builder.append(str, pos_end + 1, pos_start);
				pos_end = str.indexOf('}', pos_start + 2);
				if (pos_end < 0) {
					pos_end = pos_start - 1;
					break;
				}
				String propName = str.substring(pos_start + 2, pos_end);
				String replacement;
				if (propName.length() == 0) {
					replacement = null;
				} else if (Globals.CATALINA_HOME_PROP.equals(propName)) {
					replacement = getCatalinaHome();
				} else if (Globals.CATALINA_BASE_PROP.equals(propName)) {
					replacement = getCatalinaBase();
				} else {
					replacement = System.getProperty(propName);
				}
				if (replacement != null) {
					builder.append(replacement);
				} else {
					builder.append(str, pos_start, pos_end + 1);
				}
				pos_start = str.indexOf("${", pos_end + 1);
			}
			builder.append(str, pos_end + 1, str.length());
			result = builder.toString();
		}
		return result;
	}

	/**
	 * Initialize daemon.
	 */
	public void init() throws Exception {

		log.info("初始化Bootstrap对象！");
		
		//设置catalina的主目录
		setCatalinaHome();
		setCatalinaBase();

		//初始化类加载器
		initClassLoaders();

		Thread.currentThread().setContextClassLoader(catalinaLoader);

		SecurityClassLoad.securityClassLoad(catalinaLoader);

		// 加载Catalina类
		log.info("加载org.apache.catalina.startup.Catalina类，并实例化");
		Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
		Object startupInstance = startupClass.newInstance();

		// 设置startup的共享扩展类加载器
		String methodName = "setParentClassLoader";
		Class<?> paramTypes[] = new Class[1];
		paramTypes[0] = Class.forName("java.lang.ClassLoader");
		Object paramValues[] = new Object[1];
		paramValues[0] = sharedLoader;
		Method method = startupInstance.getClass().getMethod(methodName, paramTypes);
		method.invoke(startupInstance, paramValues);

		log.info("Catalina加载完成，并设置父类加载器为sharedLoader");
		catalinaDaemon = startupInstance;

	}

	/**
	 * Load daemon.
	 */
	private void load(String[] arguments) throws Exception {

		String methodName = "load";
		Object param[];
		Class<?> paramTypes[];
		if (arguments == null || arguments.length == 0) {
			paramTypes = null;
			param = null;
		} else {
			paramTypes = new Class[1];
			paramTypes[0] = arguments.getClass();
			param = new Object[1];
			param[0] = arguments;
		}

		log.info("执行catalina的load方法");

		Method method = catalinaDaemon.getClass().getMethod(methodName, paramTypes);
		method.invoke(catalinaDaemon, param);

	}

	/**
	 * getServer() for configtest
	 */
	private Object getServer() throws Exception {

		String methodName = "getServer";
		Method method = catalinaDaemon.getClass().getMethod(methodName);
		return method.invoke(catalinaDaemon);

	}

	// ----------------------------------------------------------- Main Program

	/**
	 * Load the Catalina daemon.
	 */
	public void init(String[] arguments) throws Exception {

		init();
		load(arguments);

	}

	/**
	 * Start the Catalina daemon.
	 */
	public void start() throws Exception {
		if (catalinaDaemon == null)
			init();

		log.info("执行catalina的start方法！");
		Method method = catalinaDaemon.getClass().getMethod("start", (Class[]) null);
		method.invoke(catalinaDaemon, (Object[]) null);

	}

	/**
	 * Stop the Catalina Daemon.
	 */
	public void stop() throws Exception {

		Method method = catalinaDaemon.getClass().getMethod("stop", (Class[]) null);
		method.invoke(catalinaDaemon, (Object[]) null);

	}

	/**
	 * Stop the standalone server.
	 */
	public void stopServer() throws Exception {

		Method method = catalinaDaemon.getClass().getMethod("stopServer", (Class[]) null);
		method.invoke(catalinaDaemon, (Object[]) null);

	}

	/**
	 * Stop the standalone server.
	 */
	public void stopServer(String[] arguments) throws Exception {

		Object param[];
		Class<?> paramTypes[];
		if (arguments == null || arguments.length == 0) {
			paramTypes = null;
			param = null;
		} else {
			paramTypes = new Class[1];
			paramTypes[0] = arguments.getClass();
			param = new Object[1];
			param[0] = arguments;
		}
		Method method = catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
		method.invoke(catalinaDaemon, param);

	}

	/**
	 * Set flag.
	 */
	public void setAwait(boolean await) throws Exception {

		Class<?> paramTypes[] = new Class[1];
		paramTypes[0] = Boolean.TYPE;
		Object paramValues[] = new Object[1];
		paramValues[0] = Boolean.valueOf(await);
		Method method = catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
		method.invoke(catalinaDaemon, paramValues);

	}

	public boolean getAwait() throws Exception {
		Class<?> paramTypes[] = new Class[0];
		Object paramValues[] = new Object[0];
		Method method = catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
		Boolean b = (Boolean) method.invoke(catalinaDaemon, paramValues);
		return b.booleanValue();
	}

	/**
	 * Destroy the Catalina Daemon.
	 */
	public void destroy() {

		// FIXME

	}

	/**
	 * Main method, used for testing only.
	 * 
	 * @param args
	 *            Command line arguments to be processed
	 */
	public static void main(String args[]) {

		log.info("开始运行：起源Bootstrap的main方法！");
		if (daemon == null) {
			log.debug("创建Bootstrap对象！");
			Bootstrap bootstrap = new Bootstrap();
			try {
				bootstrap.init();
			} catch (Throwable t) {
				log.error("初始化Bootstrap对象出错！");
				handleThrowable(t);
				t.printStackTrace();
				return;
			}
			log.debug("将创建的Bootstrap对象置为daemon(后台程序)");
			daemon = bootstrap;
		}

		try {
			String command = "start";
			if (args.length > 0) {
				command = args[args.length - 1];
			}

			if (command.equals("startd")) {
				args[args.length - 1] = "start";
				daemon.load(args);
				daemon.start();
			} else if (command.equals("stopd")) {
				args[args.length - 1] = "stop";
				daemon.stop();
			} else if (command.equals("start")) {
				daemon.setAwait(true);
				daemon.load(args);
				daemon.start();
			} else if (command.equals("stop")) {
				daemon.stopServer(args);
			} else if (command.equals("configtest")) {
				daemon.load(args);
				if (null == daemon.getServer()) {
					System.exit(1);
				}
				System.exit(0);
			} else {
				log.warn("Bootstrap: command \"" + command + "\" does not exist.");
			}
		} catch (Throwable t) {
			// Unwrap the Exception for clearer error reporting
			if (t instanceof InvocationTargetException && t.getCause() != null) {
				t = t.getCause();
			}
			handleThrowable(t);
			t.printStackTrace();
			System.exit(1);
		}

	}

	public void setCatalinaHome(String s) {
		System.setProperty(Globals.CATALINA_HOME_PROP, s);
	}

	public void setCatalinaBase(String s) {
		System.setProperty(Globals.CATALINA_BASE_PROP, s);
	}

	/**
	 * Set the <code>catalina.base</code> System property to the current working
	 * directory if it has not been set.
	 */
	private void setCatalinaBase() {

		if (System.getProperty(Globals.CATALINA_BASE_PROP) != null)
			return;
		if (System.getProperty(Globals.CATALINA_HOME_PROP) != null)
			System.setProperty(Globals.CATALINA_BASE_PROP, System.getProperty(Globals.CATALINA_HOME_PROP));
		else
			System.setProperty(Globals.CATALINA_BASE_PROP, System.getProperty("user.dir"));

	}

	/**
	 * Set the <code>catalina.home</code> System property to the current working
	 * directory if it has not been set.
	 */
	private void setCatalinaHome() {

		if (System.getProperty(Globals.CATALINA_HOME_PROP) != null)
			return;
		File bootstrapJar = new File(System.getProperty("user.dir"), "bootstrap.jar");
		if (bootstrapJar.exists()) {
			try {
				System.setProperty(Globals.CATALINA_HOME_PROP,
						(new File(System.getProperty("user.dir"), "..")).getCanonicalPath());
			} catch (Exception e) {
				// Ignore
				System.setProperty(Globals.CATALINA_HOME_PROP, System.getProperty("user.dir"));
			}
		} else {
			System.setProperty(Globals.CATALINA_HOME_PROP, System.getProperty("user.dir"));
		}
	}

	/**
	 * Get the value of the catalina.home environment variable.
	 */
	public static String getCatalinaHome() {
		return System.getProperty(Globals.CATALINA_HOME_PROP, System.getProperty("user.dir"));
	}

	/**
	 * Get the value of the catalina.base environment variable.
	 */
	public static String getCatalinaBase() {
		return System.getProperty(Globals.CATALINA_BASE_PROP, getCatalinaHome());
	}

	// Copied from ExceptionUtils since that class is not visible during start
	private static void handleThrowable(Throwable t) {
		if (t instanceof ThreadDeath) {
			throw (ThreadDeath) t;
		}
		if (t instanceof VirtualMachineError) {
			throw (VirtualMachineError) t;
		}
		// All other instances of Throwable will be silently swallowed
	}
}