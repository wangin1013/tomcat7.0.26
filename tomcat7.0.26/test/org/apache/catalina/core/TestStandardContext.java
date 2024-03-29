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

package org.apache.catalina.core;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.HttpConstraintElement;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletSecurityElement;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.ServletSecurity.TransportGuarantee;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.authenticator.BasicAuthenticator;
import org.apache.catalina.deploy.FilterDef;
import org.apache.catalina.deploy.FilterMap;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.TestTomcat.MapRealm;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;

public class TestStandardContext extends TomcatBaseTest {

    private static final String REQUEST =
        "GET / HTTP/1.1\r\n" +
        "Host: anything\r\n" +
        "Connection: close\r\n" +
        "\r\n";

    @Test
    public void testBug46243() throws Exception {

        // Set up a container
        Tomcat tomcat = getTomcatInstance();

        File docBase = new File(tomcat.getHost().getAppBase(), "ROOT");
        if (!docBase.mkdirs() && !docBase.isDirectory()) {
            fail("Unable to create docBase");
        }

        Context root = tomcat.addContext("", "ROOT");

        // Add test a filter that fails
        FilterDef filterDef = new FilterDef();
        filterDef.setFilterClass(Bug46243Filter.class.getName());
        filterDef.setFilterName("Bug46243");
        root.addFilterDef(filterDef);
        FilterMap filterMap = new FilterMap();
        filterMap.setFilterName("Bug46243");
        filterMap.addURLPattern("*");
        root.addFilterMap(filterMap);

        // Add a test servlet so there is something to generate a response if
        // it works (although it shouldn't)
        Tomcat.addServlet(root, "Bug46243", new HelloWorldServlet());
        root.addServletMapping("/", "Bug46243");


        tomcat.start();

        // Configure the client
        Bug46243Client client =
                new Bug46243Client(tomcat.getConnector().getLocalPort());
        client.setRequest(new String[] { REQUEST });

        client.connect();
        client.processRequest();
        assertTrue(client.isResponse404());
    }

    private static final class Bug46243Client extends SimpleHttpClient {

        public Bug46243Client(int port) {
            setPort(port);
        }

        @Override
        public boolean isResponseBodyOK() {
            // Don't care about the body in this test
            return true;
        }
    }

    public static final class Bug46243Filter implements Filter {

        @Override
        public void destroy() {
            // NOOP
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response,
                FilterChain chain) throws IOException, ServletException {
            // If it works, do nothing
            chain.doFilter(request, response);
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
            throw new ServletException("Init fail", new ClassNotFoundException());
        }

    }

    @Test
    public void testBug49922() throws Exception {

        // Set up a container
        Tomcat tomcat = getTomcatInstance();

        File root = new File("test/webapp-3.0");
        tomcat.addWebapp("", root.getAbsolutePath());

        tomcat.start();
        ByteChunk result;

        // Check filter and servlet aren't called
        result = getUrl("http://localhost:" + getPort() +
                "/bug49922/foo");
        assertNull(result.toString());

        // Check extension mapping works
        result = getUrl("http://localhost:" + getPort() + "/foo.do");
        assertEquals("FilterServlet", result.toString());

        // Check path mapping works
        result = getUrl("http://localhost:" + getPort() + "/bug49922/servlet");
        assertEquals("FilterServlet", result.toString());

        // Check servlet name mapping works
        result = getUrl("http://localhost:" + getPort() + "/foo.od");
        assertEquals("FilterServlet", result.toString());

        // Check filter is only called once
        result = getUrl("http://localhost:" + getPort() +
                "/bug49922/servlet/foo.do");
        assertEquals("FilterServlet", result.toString());
        result = getUrl("http://localhost:" + getPort() +
                "/bug49922/servlet/foo.od");
        assertEquals("FilterServlet", result.toString());

        // Check dispatcher mapping
        result = getUrl("http://localhost:" + getPort() +
                "/bug49922/target");
        assertEquals("Target", result.toString());
        result = getUrl("http://localhost:" + getPort() +
                "/bug49922/forward");
        assertEquals("FilterTarget", result.toString());
        result = getUrl("http://localhost:" + getPort() +
                "/bug49922/include");
        assertEquals("IncludeFilterTarget", result.toString());
    }


    public static final class Bug49922Filter implements Filter {

        @Override
        public void destroy() {
            // NOOP
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response,
                FilterChain chain) throws IOException, ServletException {
            response.setContentType("text/plain");
            response.getWriter().print("Filter");
            chain.doFilter(request, response);
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
            // NOOP
        }
    }

    public static final class Bug49922ForwardServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            req.getRequestDispatcher("/bug49922/target").forward(req, resp);
        }

    }

    public static final class Bug49922IncludeServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.getWriter().print("Include");
            req.getRequestDispatcher("/bug49922/target").include(req, resp);
        }

    }

    public static final class Bug49922TargetServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.getWriter().print("Target");
        }

    }

    public static final class Bug49922Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.getWriter().print("Servlet");
        }

    }

    @Test
    public void testBug50015() throws Exception {
        // Set up a container
        Tomcat tomcat = getTomcatInstance();

        // Must have a real docBase - just use temp
        File docBase = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        // Setup realm
        MapRealm realm = new MapRealm();
        realm.addUser("tomcat", "tomcat");
        realm.addUserRole("tomcat", "tomcat");
        ctx.setRealm(realm);

        // Configure app for BASIC auth
        LoginConfig lc = new LoginConfig();
        lc.setAuthMethod("BASIC");
        ctx.setLoginConfig(lc);
        ctx.getPipeline().addValve(new BasicAuthenticator());

        // Add ServletContainerInitializer
        ServletContainerInitializer sci = new Bug50015SCI();
        ctx.addServletContainerInitializer(sci, null);

        // Start the context
        tomcat.start();

        // Request the first servlet
        ByteChunk bc = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + "/bug50015",
                bc, null);

        // Check for a 401
        assertNotSame("OK", bc.toString());
        assertEquals(401, rc);
    }

    public static final class Bug50015SCI
            implements ServletContainerInitializer {

        @Override
        public void onStartup(Set<Class<?>> c, ServletContext ctx)
                throws ServletException {
            // Register and map servlet
            Servlet s = new Bug50015Servlet();
            ServletRegistration.Dynamic sr = ctx.addServlet("bug50015", s);
            sr.addMapping("/bug50015");

            // Limit access to users in the Tomcat role
            HttpConstraintElement hce = new HttpConstraintElement(
                    TransportGuarantee.NONE, "tomcat");
            ServletSecurityElement sse = new ServletSecurityElement(hce);
            sr.setServletSecurity(sse);
        }
    }

    public static final class Bug50015Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.getWriter().write("OK");
        }

    }

    @Test
    public void testBug51376a() throws Exception {
        doTestBug51376(false);
    }

    @Test
    public void testBug51376b() throws Exception {
        doTestBug51376(true);
    }

    private void doTestBug51376(boolean loadOnStartUp) throws Exception {

        // Set up a container
        Tomcat tomcat = getTomcatInstance();

        // Must have a real docBase - just use temp
        File docBase = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

        // Add ServletContainerInitializer
        Bug51376SCI sci = new Bug51376SCI(loadOnStartUp);
        ctx.addServletContainerInitializer(sci, null);

        // Start the context
        tomcat.start();

        // Stop the context
        ctx.stop();

        // Make sure that init() and destroy() were called correctly
        assertTrue(sci.getServlet().isOk());
    }

    public static final class Bug51376SCI
            implements ServletContainerInitializer {

        private Bug51376Servlet s = null;
        private boolean loadOnStartUp;

        public Bug51376SCI(boolean loadOnStartUp) {
            this.loadOnStartUp = loadOnStartUp;
        }

        private Bug51376Servlet getServlet() {
            return s;
        }

        @Override
        public void onStartup(Set<Class<?>> c, ServletContext ctx)
                throws ServletException {
            // Register and map servlet
            s = new Bug51376Servlet();
            ServletRegistration.Dynamic sr = ctx.addServlet("bug51376", s);
            sr.addMapping("/bug51376");
            if (loadOnStartUp) {
                sr.setLoadOnStartup(1);
            }
        }
    }

    public static final class Bug51376Servlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private Boolean initOk = null;
        private Boolean destoryOk = null;

        @Override
        public void init() {
            if (initOk == null && destoryOk == null) {
                initOk = Boolean.TRUE;
            } else {
                initOk = Boolean.FALSE;
            }
        }

        @Override
        public void destroy() {
            if (initOk.booleanValue() && destoryOk == null) {
                destoryOk = Boolean.TRUE;
            } else {
                destoryOk = Boolean.FALSE;
            }
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.getWriter().write("OK");
        }

        protected boolean isOk() {
            if (initOk != null && initOk.booleanValue() && destoryOk != null &&
                    destoryOk.booleanValue()) {
                return true;
            } else if (initOk == null && destoryOk == null) {
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Test case for bug 49711: HttpServletRequest.getParts does not work
     * in a filter.
     */
    @Test
    public void testBug49711() {
        Bug49711Client client = new Bug49711Client();

        // Make sure non-multipart works properly
        client.doRequest("/regular", false, false);

        assertEquals("Incorrect response for GET request",
                     "parts=0",
                     client.getResponseBody());

        client.reset();

        // Make sure regular multipart works properly
        client.doRequest("/multipart", false, true); // send multipart request

        assertEquals("Regular multipart doesn't work",
                     "parts=1",
                     client.getResponseBody());

        client.reset();

        // Make casual multipart request to "regular" servlet w/o config
        // We expect that no parts will be available
        client.doRequest("/regular", false, true); // send multipart request

        assertEquals("Incorrect response for non-configured casual multipart request",
                     "parts=0", // multipart request should be ignored
                     client.getResponseBody());

        client.reset();

        // Make casual multipart request to "regular" servlet w/config
        // We expect that the server /will/ parse the parts, even though
        // there is no @MultipartConfig
        client.doRequest("/regular", true, true); // send multipart request

        assertEquals("Incorrect response for configured casual multipart request",
                     "parts=1",
                     client.getResponseBody());

        client.reset();
    }

    private static class Bug49711Servlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
            // Just echo the parameters and values back as plain text
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");

            PrintWriter out = resp.getWriter();

            out.println("parts=" + (null == req.getParts()
                                    ? "null"
                                    : Integer.valueOf(req.getParts().size())));
        }
    }

    @MultipartConfig
    private static class Bug49711Servlet_multipart extends Bug49711Servlet {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Bug 49711 test client: test for casual getParts calls.
     */
    private class Bug49711Client extends SimpleHttpClient {

        private boolean init;
        private Context context;

        private synchronized void init() throws Exception {
            if (init) return;

            Tomcat tomcat = getTomcatInstance();
            context = tomcat.addContext("", TEMP_DIR);
            Tomcat.addServlet(context, "regular", new Bug49711Servlet());
            Wrapper w = Tomcat.addServlet(context, "multipart", new Bug49711Servlet_multipart());

            // Tomcat.addServlet does not respect annotations, so we have
            // to set our own MultipartConfigElement.
            w.setMultipartConfigElement(new MultipartConfigElement(""));

            context.addServletMapping("/regular", "regular");
            context.addServletMapping("/multipart", "multipart");
            tomcat.start();

            setPort(tomcat.getConnector().getLocalPort());

            init = true;
        }

        private Exception doRequest(String uri,
                                    boolean allowCasualMultipart,
                                    boolean makeMultipartRequest) {
            try {
                init();

                context.setAllowCasualMultipartParsing(allowCasualMultipart);

                // Open connection
                connect();

                // Send specified request body using method
                String[] request;

                if(makeMultipartRequest) {
                    String boundary = "--simpleboundary";

                    String content = "--" + boundary + CRLF
                        + "Content-Disposition: form-data; name=\"name\"" + CRLF + CRLF
                        + "value" + CRLF
                        + "--" + boundary + "--" + CRLF;

                    // Re-encode the content so that bytes = characters
                    content = new String(content.getBytes("UTF-8"), "ASCII");

                    request = new String[] {
                        "POST http://localhost:" + getPort() + uri + " HTTP/1.1" + CRLF
                        + "Host: localhost" + CRLF
                        + "Connection: close" + CRLF
                        + "Content-Type: multipart/form-data; boundary=" + boundary + CRLF
                        + "Content-Length: " + content.length() + CRLF
                        + CRLF
                        + content
                        + CRLF
                    };
                }
                else
                {
                    request = new String[] {
                        "GET http://localhost:" + getPort() + uri + " HTTP/1.1" + CRLF
                        + "Host: localhost" + CRLF
                        + "Connection: close" + CRLF
                        + CRLF
                    };
                }

                setRequest(request);
                processRequest(); // blocks until response has been read

                // Close the connection
                disconnect();
            } catch (Exception e) {
                return e;
            }

            return null;
        }

        @Override
        public boolean isResponseBodyOK() {
            return false; // Don't care
        }
    }
}
