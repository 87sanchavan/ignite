/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.cache.websession;

import org.apache.ignite.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.transactions.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.startup.servlet.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;

import javax.cache.expiry.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;

import static java.util.concurrent.TimeUnit.*;
import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.apache.ignite.transactions.IgniteTxConcurrency.*;
import static org.apache.ignite.transactions.IgniteTxIsolation.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;

/**
 * Filter for web sessions caching.
 * <p>
 * This is a request filter, that you need to specify in your {@code web.xml} along
 * with {@link GridServletContextListenerStartup} to enable web sessions caching:
 * <pre name="code" class="xml">
 * &lt;listener&gt;
 *     &lt;listener-class&gt;org.gridgain.grid.startup.servlet.GridServletContextListenerStartup&lt;/listener-class&gt;
 * &lt;/listener&gt;
 *
 * &lt;filter&gt;
 *     &lt;filter-name&gt;GridGainWebSessionsFilter&lt;/filter-name&gt;
 *     &lt;filter-class&gt;org.gridgain.grid.cache.websession.GridWebSessionFilter&lt;/filter-class&gt;
 * &lt;/filter&gt;
 *
 * &lt;!-- You can also specify a custom URL pattern. --&gt;
 * &lt;filter-mapping&gt;
 *     &lt;filter-name&gt;GridGainWebSessionsFilter&lt;/filter-name&gt;
 *     &lt;url-pattern&gt;/*&lt;/url-pattern&gt;
 * &lt;/filter-mapping&gt;
 * </pre>
 * It is also possible to specify a servlet name in a filter mapping, and a servlet URL pattern will
 * be used in this case:
 * <pre name="code" class="xml">
 * &lt;filter&gt;
 *     &lt;filter-name&gt;GridGainWebSessionsFilter&lt;/filter-name&gt;
 *     &lt;filter-class&gt;org.gridgain.grid.cache.websession.GridWebSessionFilter&lt;/filter-class&gt;
 * &lt;/filter&gt;
 *
 * &lt;filter-mapping&gt;
 *     &lt;filter-name&gt;GridGainWebSessionsFilter&lt;/filter-name&gt;
 *     &lt;servlet-name&gt;YourServletName&lt;/servlet-name&gt;
 * &lt;/filter-mapping&gt;
 * </pre>
 * The filter has the following optional configuration parameters:
 * <table class="doctable">
 *     <tr>
 *         <th>Name</th>
 *         <th>Description</th>
 *         <th>Default</th>
 *     </tr>
 *     <tr>
 *         <td>GridGainWebSessionsGridName</td>
 *         <td>Name of the grid that contains cache for web session storage.</td>
 *         <td>{@code null} (default grid)</td>
 *     </tr>
 *     <tr>
 *         <td>GridGainWebSessionsCacheName</td>
 *         <td>Name of the cache for web session storage.</td>
 *         <td>{@code null} (default cache)</td>
 *     </tr>
 *     <tr>
 *         <td>GridGainWebSessionsMaximumRetriesOnFail</td>
 *         <td>
 *             Valid for {@code ATOMIC} caches only. Maximum number of retries for session updates in case
 *             node leaves topology and update fails. If retry is enabled,
 *             some updates can be applied more than once, otherwise some
 *             updates can be lost.
 *             <p>
 *             To disable retries, set this parameter to {@code 0}.
 *         </td>
 *         <td>{@code 3}</td>
 *     </tr>
 * </table>
 * These parameters are taken from either filter init parameter list or
 * servlet context parameters. You can specify filter init parameters as follows:
 * <pre name="code" class="xml">
 * &lt;filter&gt;
 *     &lt;filter-name&gt;GridGainWebSessionsFilter&lt;/filter-name&gt;
 *     &lt;filter-class&gt;org.gridgain.grid.cache.websession.GridWebSessionFilter&lt;/filter-class&gt;
 *     &lt;init-param&gt;
 *         &lt;param-name&gt;GridGainWebSessionsGridName&lt;/param-name&gt;
 *         &lt;param-value&gt;WebGrid&lt;/param-value&gt;
 *     &lt;/init-param&gt;
 *     &lt;init-param&gt;
 *         &lt;param-name&gt;GridGainWebSessionsCacheName&lt;/param-name&gt;
 *         &lt;param-value&gt;WebCache&lt;/param-value&gt;
 *     &lt;/init-param&gt;
 *
 *     &lt;!-- Valid for ATOMIC caches only. --&gt;
 *     &lt;init-param&gt;
 *         &lt;param-name&gt;GridGainWebSessionsMaximumRetriesOnFail&lt;/param-name&gt;
 *         &lt;param-value&gt;10&lt;/param-value&gt;
 *     &lt;/init-param&gt;
 * &lt;/filter&gt;
 * </pre>
 * <b>Note:</b> filter init parameter has a priority over servlet context
 * parameter; if you specify both, the servlet context parameter will be ignored.
 * <h1 class="header">Web sessions caching and concurrent requests</h1>
 * If your web application can accept concurrent request for one session,
 * consider using {@link GridCacheAtomicityMode#TRANSACTIONAL} cache
 * instead of {@link GridCacheAtomicityMode#ATOMIC}. In this case each request
 * be processed inside pessimistic transaction which will guarantee that all
 * updates will be applied in correct order. This is important, for example,
 * if you get some attribute from the session, update its value and set new
 * value back to the session. In case of {@link GridCacheAtomicityMode#ATOMIC}
 * cache concurrent requests can get equal value, but {@link GridCacheAtomicityMode#TRANSACTIONAL}
 * cache will always process such updates one after another.
 */
public class GridWebSessionFilter implements Filter {
    /** Web sessions caching grid name parameter name. */
    public static final String WEB_SES_GRID_NAME_PARAM = "GridGainWebSessionsGridName";

    /** Web sessions caching cache name parameter name. */
    public static final String WEB_SES_CACHE_NAME_PARAM = "GridGainWebSessionsCacheName";

    /** Web sessions caching retry on fail parameter name (valid for ATOMIC */
    public static final String WEB_SES_MAX_RETRIES_ON_FAIL_NAME_PARAM = "GridGainWebSessionsMaximumRetriesOnFail";

    /** Default retry on fail flag value. */
    public static final int DFLT_MAX_RETRIES_ON_FAIL = 3;

    /** Cache. */
    private GridCache<String, GridWebSession> cache;

    /** Listener. */
    private GridWebSessionListener lsnr;

    /** Logger. */
    private IgniteLogger log;

    /** Servlet context. */
    private ServletContext ctx;

    /** Session ID transformer. */
    private IgniteClosure<String, String> sesIdTransformer;

    /** Transactions enabled flag. */
    private boolean txEnabled;

    /** {@inheritDoc} */
    @Override public void init(FilterConfig cfg) throws ServletException {
        ctx = cfg.getServletContext();

        String gridName = U.firstNotNull(
            cfg.getInitParameter(WEB_SES_GRID_NAME_PARAM),
            ctx.getInitParameter(WEB_SES_GRID_NAME_PARAM));

        String cacheName = U.firstNotNull(
            cfg.getInitParameter(WEB_SES_CACHE_NAME_PARAM),
            ctx.getInitParameter(WEB_SES_CACHE_NAME_PARAM));

        String retriesStr = U.firstNotNull(
            cfg.getInitParameter(WEB_SES_MAX_RETRIES_ON_FAIL_NAME_PARAM),
            ctx.getInitParameter(WEB_SES_MAX_RETRIES_ON_FAIL_NAME_PARAM));

        int retries;

        try {
            retries = retriesStr != null ? Integer.parseInt(retriesStr) : DFLT_MAX_RETRIES_ON_FAIL;
        }
        catch (NumberFormatException e) {
            throw new IgniteException("Maximum number of retries parameter is invalid: " + retriesStr, e);
        }

        Ignite webSesIgnite = G.ignite(gridName);

        if (webSesIgnite == null)
            throw new IgniteException("Grid for web sessions caching is not started (is it configured?): " +
                gridName);

        log = webSesIgnite.log();

        if (webSesIgnite == null)
            throw new IgniteException("Grid for web sessions caching is not started (is it configured?): " +
                gridName);

        cache = webSesIgnite.cache(cacheName);

        if (cache == null)
            throw new IgniteException("Cache for web sessions is not started (is it configured?): " + cacheName);

        GridCacheConfiguration cacheCfg = cache.configuration();

        if (cacheCfg.getWriteSynchronizationMode() == FULL_ASYNC)
            throw new IgniteException("Cache for web sessions cannot be in FULL_ASYNC mode: " + cacheName);

        if (!cacheCfg.isEagerTtl())
            throw new IgniteException("Cache for web sessions cannot operate with lazy TTL. " +
                "Consider setting eagerTtl to true for cache: " + cacheName);

        if (cacheCfg.getCacheMode() == LOCAL)
            U.quietAndWarn(webSesIgnite.log(), "Using LOCAL cache for web sessions caching " +
                "(this is only OK in test mode): " + cacheName);

        if (cacheCfg.getCacheMode() == PARTITIONED && cacheCfg.getAtomicityMode() != ATOMIC)
            U.quietAndWarn(webSesIgnite.log(), "Using " + cacheCfg.getAtomicityMode() + " atomicity for web sessions " +
                "caching (switch to ATOMIC mode for better performance)");

        if (log.isInfoEnabled())
            log.info("Started web sessions caching [gridName=" + gridName + ", cacheName=" + cacheName +
                ", maxRetriesOnFail=" + retries + ']');

        txEnabled = cacheCfg.getAtomicityMode() == TRANSACTIONAL;

        lsnr = new GridWebSessionListener(webSesIgnite, cache, retries);

        String srvInfo = ctx.getServerInfo();

        // Special case for WebLogic, which appends timestamps to session
        // IDs upon session creation (the created session ID looks like:
        // pdpTSTcCcG6CVM8BTZWzxjTB1lh3w7zFbYVvwBb4bJGjrBx3TMPl!-508312620!1385045122601).
        if (srvInfo != null && srvInfo.contains("WebLogic")) {
            sesIdTransformer = new C1<String, String>() {
                @Override public String apply(String s) {
                    // Find first exclamation mark.
                    int idx = s.indexOf('!');

                    // Return original string if not found.
                    if (idx < 0 || idx == s.length() - 1)
                        return s;

                    // Find second exclamation mark.
                    idx = s.indexOf('!', idx + 1);

                    // Return original string if not found.
                    if (idx < 0)
                        return s;

                    // Return the session ID without timestamp.
                    return s.substring(0, idx);
                }
            };
        }
    }

    /** {@inheritDoc} */
    @Override public void destroy() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {
        assert ctx != null;

        if (req instanceof HttpServletRequest) {
            HttpServletRequest httpReq = (HttpServletRequest)req;

            String sesId = null;

            try {
                if (txEnabled) {
                    try (IgniteTx tx = cache.txStart(PESSIMISTIC, REPEATABLE_READ)) {
                        sesId = doFilter0(httpReq, res, chain);

                        tx.commit();
                    }
                }
                else
                    sesId = doFilter0(httpReq, res, chain);
            }
            catch (IgniteCheckedException e) {
                U.error(log, "Failed to update web session: " + sesId, e);
            }
        }
        else
            chain.doFilter(req, res);
    }

    /**
     * @param httpReq Request.
     * @param res Response.
     * @param chain Filter chain.
     * @return Session ID.
     * @throws IOException In case of I/O error.
     * @throws ServletException In case oif servlet error.
     * @throws IgniteCheckedException In case of other error.
     */
    private String doFilter0(HttpServletRequest httpReq, ServletResponse res, FilterChain chain) throws IOException,
        ServletException, IgniteCheckedException {
        GridWebSession cached;

        String sesId = httpReq.getRequestedSessionId();

        if (sesId != null) {
            cached = cache.get(sesId);

            if (cached != null) {
                if (log.isDebugEnabled())
                    log.debug("Using cached session for ID: " + sesId);

                if (cached.isNew())
                    cached = new GridWebSession(cached, false);
            }
            else {
                if (log.isDebugEnabled())
                    log.debug("Cached session was invalidated and doesn't exist: " + sesId);

                HttpSession ses = httpReq.getSession(false);

                if (ses != null) {
                    try {
                        ses.invalidate();
                    }
                    catch (IllegalStateException ignore) {
                        // Session was already invalidated.
                    }
                }

                cached = createSession(httpReq);
            }
        }
        else {
            cached = createSession(httpReq);

            sesId = cached.getId();
        }

        assert cached != null;

        cached.servletContext(ctx);
        cached.listener(lsnr);
        cached.resetUpdates();

        httpReq = new RequestWrapper(httpReq, cached);

        chain.doFilter(httpReq, res);

        HttpSession ses = httpReq.getSession(false);

        if (ses != null && ses instanceof GridWebSession) {
            Collection<T2<String, Object>> updates = ((GridWebSession)ses).updates();

            if (updates != null)
                lsnr.updateAttributes(ses.getId(), updates, ses.getMaxInactiveInterval());
        }

        return sesId;
    }

    /**
     * @param httpReq HTTP request.
     * @return Cached session.
     */
    @SuppressWarnings("unchecked")
    private GridWebSession createSession(HttpServletRequest httpReq) {
        HttpSession ses = httpReq.getSession(true);

        String sesId = sesIdTransformer != null ? sesIdTransformer.apply(ses.getId()) : ses.getId();

        if (log.isDebugEnabled())
            log.debug("Session created: " + sesId);

        GridWebSession cached = new GridWebSession(ses, true);

        try {
            while (true) {
                try {
                    GridCacheProjection<String, GridWebSession> cache0;

                    if (cached.getMaxInactiveInterval() > 0) {
                        ExpiryPolicy plc =
                            new TouchedExpiryPolicy(new Duration(MILLISECONDS, cached.getMaxInactiveInterval() * 1000));

                        cache0 = ((GridCacheProjectionEx<String, GridWebSession>)cache).withExpiryPolicy(plc);
                    }
                    else
                        cache0 = cache;

                    GridWebSession old = cache0.putIfAbsent(sesId, cached);

                    if (old != null) {
                        cached = old;

                        if (cached.isNew())
                            cached = new GridWebSession(cached, false);
                    }

                    break;
                }
                catch (GridCachePartialUpdateException e) {
                    if (log.isDebugEnabled())
                        log.debug(e.getMessage());
                }
            }
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Failed to save session: " + sesId, e);
        }

        return cached;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridWebSessionFilter.class, this);
    }

    /**
     * Request wrapper.
     */
    private static class RequestWrapper extends HttpServletRequestWrapper {
        /** Session. */
        private final GridWebSession ses;

        /**
         * @param req Request.
         * @param ses Session.
         */
        private RequestWrapper(HttpServletRequest req, GridWebSession ses) {
            super(req);

            assert ses != null;

            this.ses = ses;
        }

        /** {@inheritDoc} */
        @Override public HttpSession getSession(boolean create) {
            return ses;
        }

        /** {@inheritDoc} */
        @Override public HttpSession getSession() {
            return ses;
        }
    }
}
