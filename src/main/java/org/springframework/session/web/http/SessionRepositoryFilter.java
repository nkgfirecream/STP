/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.springframework.session.web.http;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.annotation.Order;
import org.springframework.session.ExpiringSession;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * Switches the {@link javax.servlet.http.HttpSession} implementation to be backed by a {@link org.springframework.session.Session}.
 *
 * The {@link SessionRepositoryFilter} wraps the {@link javax.servlet.http.HttpServletRequest} and overrides the methods
 * to get an {@link javax.servlet.http.HttpSession} to be backed by a {@link org.springframework.session.Session} returned
 * by the {@link org.springframework.session.SessionRepository}.
 *
 * The {@link SessionRepositoryFilter} uses a {@link HttpSessionStrategy} (default {@link CookieHttpSessionStrategy}  to
 * bridge logic between an {@link javax.servlet.http.HttpSession} and the {@link org.springframework.session.Session}
 * abstraction. Specifically:
 *
 * <ul>
 *     <li>The session id is looked up using {@link HttpSessionStrategy#getRequestedSessionId(javax.servlet.http.HttpServletRequest)}.
 *     The default is to look in a cookie named SESSION.</li>
 *     <li>The session id of newly created {@link org.springframework.session.ExpiringSession} is sent to the client using
 *     <li>The client is notified that the session id is no longer valid with {@link HttpSessionStrategy#onInvalidateSession(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)}</li>
 * </ul>
 *
 * <p>
 * The SessionRepositoryFilter must be placed before any Filter that access the HttpSession or that might commit the response
 * to ensure the session is overridden and persisted properly.
 * </p>
 *
 * @since 1.0
 * @author Rob Winch
 */
@Order(SessionRepositoryFilter.DEFAULT_ORDER)
public class SessionRepositoryFilter<S extends ExpiringSession> extends OncePerRequestFilter {
	private static final String SESSION_LOGGER_NAME = SessionRepositoryFilter.class.getName().concat(".SESSION_LOGGER");

	private static final Log SESSION_LOGGER = LogFactory.getLog(SESSION_LOGGER_NAME);

	public static final String SESSION_REPOSITORY_ATTR = SessionRepository.class.getName();

	public static final int DEFAULT_ORDER = Integer.MIN_VALUE + 50;

	private final SessionRepository<S> sessionRepository;

	private ServletContext servletContext;

	private MultiHttpSessionStrategy httpSessionStrategy = new CookieHttpSessionStrategy();

	/**
	 * Creates a new instance
	 *
	 * @param sessionRepository the <code>SessionRepository</code> to use. Cannot be null.
	 */
	public SessionRepositoryFilter(SessionRepository<S> sessionRepository) {
		if(sessionRepository == null) {
			throw new IllegalArgumentException("sessionRepository cannot be null");
		}
		this.sessionRepository = sessionRepository;
	}

	/**
	 * Sets the {@link HttpSessionStrategy} to be used. The default is a {@link CookieHttpSessionStrategy}.
	 *
	 * @param httpSessionStrategy the {@link HttpSessionStrategy} to use. Cannot be null.
	 */
	public void setHttpSessionStrategy(HttpSessionStrategy httpSessionStrategy) {
		if(httpSessionStrategy == null) {
			throw new IllegalArgumentException("httpSessionStrategy cannot be null");
		}
		this.httpSessionStrategy = new MultiHttpSessionStrategyAdapter(httpSessionStrategy);
	}

	/**
	 * Sets the {@link MultiHttpSessionStrategy} to be used. The default is a {@link CookieHttpSessionStrategy}.
	 *
	 * @param httpSessionStrategy the {@link MultiHttpSessionStrategy} to use. Cannot be null.
	 */
	public void setHttpSessionStrategy(MultiHttpSessionStrategy httpSessionStrategy) {
		if(httpSessionStrategy == null) {
			throw new IllegalArgumentException("httpSessionStrategy cannot be null");
		}
		this.httpSessionStrategy = httpSessionStrategy;
	}

	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
		request.setAttribute(SESSION_REPOSITORY_ATTR, sessionRepository);

		SessionRepositoryRequestWrapper wrappedRequest = new SessionRepositoryRequestWrapper(request, response, servletContext);
		SessionRepositoryResponseWrapper wrappedResponse = new SessionRepositoryResponseWrapper(wrappedRequest,response);

		HttpServletRequest strategyRequest = httpSessionStrategy.wrapRequest(wrappedRequest, wrappedResponse);
		HttpServletResponse strategyResponse = httpSessionStrategy.wrapResponse(wrappedRequest, wrappedResponse);

		try {
			filterChain.doFilter(strategyRequest, strategyResponse);
		} finally {
			wrappedRequest.commitSession();
		}
	}

	public void setServletContext(ServletContext servletContext) {
		this.servletContext = servletContext;
	}

	/**
	 * Allows ensuring that the session is saved if the response is committed.
	 *
	 * @author Rob Winch
	 * @since 1.0
	 */
	private final class SessionRepositoryResponseWrapper extends OnCommittedResponseWrapper {

		private final SessionRepositoryRequestWrapper request;

		/**
		 * @param response the response to be wrapped
		 */
		public SessionRepositoryResponseWrapper(SessionRepositoryRequestWrapper request, HttpServletResponse response) {
			super(response);
			if(request == null) {
				throw new IllegalArgumentException("request cannot be null");
			}
			this.request = request;
		}

		@Override
		protected void onResponseCommitted() {
			request.commitSession();
		}
	}

	/**
	 * A {@link javax.servlet.http.HttpServletRequest} that retrieves the {@link javax.servlet.http.HttpSession} using a
	 * {@link org.springframework.session.SessionRepository}.
	 *
	 * @author Rob Winch
	 * @since 1.0
	 */
	private final class SessionRepositoryRequestWrapper extends HttpServletRequestWrapper {
		private final String CURRENT_SESSION_ATTR = HttpServletRequestWrapper.class.getName();
		private Boolean requestedSessionIdValid;
		private boolean requestedSessionInvalidated;
		private final HttpServletResponse response;
		private final ServletContext servletContext;

		private SessionRepositoryRequestWrapper(HttpServletRequest request, HttpServletResponse response, ServletContext servletContext) {
			super(request);
			this.response = response;
			this.servletContext = servletContext;
		}

		/**
		 * Uses the HttpSessionStrategy to write the session id tot he response and persist the Session.
		 */
		private void commitSession() {
			HttpSessionWrapper wrappedSession = getCurrentSession();
			if(wrappedSession == null) {
				if(isInvalidateClientSession()) {
					httpSessionStrategy.onInvalidateSession(this, response);
				}
			} else {
				S session = wrappedSession.getSession();
				sessionRepository.save(session);
				if(!isRequestedSessionIdValid() || !session.getId().equals(getRequestedSessionId())) {
					httpSessionStrategy.onNewSession(session, this, response);
				}
			}
		}

		@SuppressWarnings("unchecked")
		private HttpSessionWrapper getCurrentSession() {
			return (HttpSessionWrapper) getAttribute(CURRENT_SESSION_ATTR);
		}

		private void setCurrentSession(HttpSessionWrapper currentSession) {
			if(currentSession == null) {
				removeAttribute(CURRENT_SESSION_ATTR);
			} else {
				setAttribute(CURRENT_SESSION_ATTR, currentSession);
			}
		}

		@SuppressWarnings("unused")
		public String changeSessionId() {
			HttpSession session = getSession(false);

			if(session == null) {
				throw new IllegalStateException("Cannot change session ID. There is no session associated with this request.");
			}

			// eagerly get session attributes in case implementation lazily loads them
			Map<String,Object> attrs = new HashMap<String,Object>();
			Enumeration<String> iAttrNames = session.getAttributeNames();
			while(iAttrNames.hasMoreElements()) {
				String attrName = iAttrNames.nextElement();
				Object value = session.getAttribute(attrName);

				attrs.put(attrName, value);
			}

			sessionRepository.delete(session.getId());
			HttpSessionWrapper original = getCurrentSession();
			setCurrentSession(null);

			HttpSessionWrapper newSession = getSession();
			original.setSession(newSession.getSession());

			newSession.setMaxInactiveInterval(session.getMaxInactiveInterval());
			for(Map.Entry<String, Object> attr : attrs.entrySet()) {
				String attrName = attr.getKey();
				Object attrValue = attr.getValue();
				newSession.setAttribute(attrName, attrValue);
			}
			return newSession.getId();
		}

		public boolean isRequestedSessionIdValid() {
			if(requestedSessionIdValid == null) {
				String sessionId = getRequestedSessionId();
				S session = sessionId == null ? null : getSession(sessionId);
				return isRequestedSessionIdValid(session);
			}

			return requestedSessionIdValid;
		}

		private boolean isRequestedSessionIdValid(S session) {
			if(requestedSessionIdValid == null) {
				requestedSessionIdValid = session != null;
			}
			return requestedSessionIdValid;
		}

		private boolean isInvalidateClientSession() {
			return getCurrentSession() == null && requestedSessionInvalidated;
		}

		private S getSession(String sessionId) {
			S session = sessionRepository.getSession(sessionId);
			if(session == null) {
				return null;
			}
			session.setLastAccessedTime(System.currentTimeMillis());
			return session;
		}

		@Override
		public HttpSessionWrapper getSession(boolean create) {
			HttpSessionWrapper currentSession = getCurrentSession();
			if(currentSession != null) {
				return currentSession;
			}
			String requestedSessionId = getRequestedSessionId();
			if(requestedSessionId != null) {
				S session = getSession(requestedSessionId);
				if(session != null) {
					this.requestedSessionIdValid = true;
					currentSession = new HttpSessionWrapper(session, getServletContext());
					currentSession.setNew(false);
					setCurrentSession(currentSession);
					return currentSession;
				}
			}
			if(!create) {
				return null;
			}
			if(SESSION_LOGGER.isDebugEnabled()) {
				SESSION_LOGGER
						.debug("A new session was created. To help you troubleshoot where the session was created we provided a StackTrace (this is not an error). You can prevent this from appearing by disabling DEBUG logging for "
								+ SESSION_LOGGER_NAME, new RuntimeException("For debugging purposes only (not an error)"));
			}
			S session = sessionRepository.createSession();
			session.setLastAccessedTime(System.currentTimeMillis());
			currentSession = new HttpSessionWrapper(session, getServletContext());
			setCurrentSession(currentSession);
			return currentSession;
		}

		public ServletContext getServletContext() {
			if(servletContext != null) {
				return servletContext;
			}
			// Servlet 3.0+
			// return super.getServletContext();
			return null;
		}

		@Override
		public HttpSessionWrapper getSession() {
			return getSession(true);
		}

		@Override
		public String getRequestedSessionId() {
			return httpSessionStrategy.getRequestedSessionId(this);
		}

		/**
		 * Allows creating an HttpSession from a Session instance.
		 *
		 * @author Rob Winch
		 * @since 1.0
		 */
		private final class HttpSessionWrapper extends ExpiringSessionHttpSession<S> {

			public HttpSessionWrapper(S session, ServletContext servletContext) {
				super(session, servletContext);
			}

			public void invalidate() {
				super.invalidate();
				requestedSessionInvalidated = true;
				setCurrentSession(null);
				sessionRepository.delete(getId());
			}
		}
	}

	static class MultiHttpSessionStrategyAdapter implements MultiHttpSessionStrategy {
		private HttpSessionStrategy delegate;

		public MultiHttpSessionStrategyAdapter(HttpSessionStrategy delegate) {
			this.delegate = delegate;
		}

		public String getRequestedSessionId(HttpServletRequest request) {
			return delegate.getRequestedSessionId(request);
		}

		public void onNewSession(Session session, HttpServletRequest request,
				HttpServletResponse response) {
			delegate.onNewSession(session, request, response);
		}

		public void onInvalidateSession(HttpServletRequest request,
				HttpServletResponse response) {
			delegate.onInvalidateSession(request, response);
		}

		public HttpServletRequest wrapRequest(HttpServletRequest request,
				HttpServletResponse response) {
			return request;
		}

		public HttpServletResponse wrapResponse(HttpServletRequest request,
				HttpServletResponse response) {
			return response;
		}
	}
}
