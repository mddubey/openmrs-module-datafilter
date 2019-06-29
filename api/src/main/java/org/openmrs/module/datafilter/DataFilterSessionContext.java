/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.datafilter;

import static org.openmrs.module.datafilter.DataFilterConstants.FILTER_NAME_ENCOUNTER;
import static org.openmrs.module.datafilter.DataFilterConstants.FILTER_NAME_OBS;
import static org.openmrs.module.datafilter.DataFilterConstants.FILTER_NAME_PATIENT;
import static org.openmrs.module.datafilter.DataFilterConstants.FILTER_NAME_VISIT;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Filter;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.openmrs.Location;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.springframework.orm.hibernate4.SpringSessionContext;

/**
 * Custom hibernate CurrentSessionContext that will enable filters on the current Session object,
 * that way the module can always enable filters on it from this single centralized place.
 *
 * <pre>
 *  TODO:
 *  May be this is not the best implementation since this gets called multiple times,
 *  Another possible approach is to add return advice after opening the session since
 *  it gets called once per request, the issue is that there is no authenticated user
 *  at the time it is called. Therefore, the logic would have to listen to successful authentication
 *  events and then enable filters for the newly authenticated user on the current session that
 *  was previously opened, it would only be done once unlike here where we do it every time the
 *  the current session is looked up.
 *
 *  Also note that in unit tests multiple uses can get logged in and out within the same thread (session)
 *  which actually should never arise in a deployed environment which is why we went with this initial
 *  implementation for simplicity. This also explains why for the above suggested alternative approach, you
 *  would need to listen for authentication events to refresh the enabled filters config.
 *
 *  The logic in this class should be generic, it's currently implemented to filter by location,
 *  but in theory it should allow another module to filter by some other basis e.g. program
 * </pre>
 */

public class DataFilterSessionContext extends SpringSessionContext {
	
	private static final Log log = LogFactory.getLog(DataFilterSessionContext.class);
	
	private ThreadLocal<Session> tempSessionHolder = new ThreadLocal<>();
	
	public DataFilterSessionContext(SessionFactoryImplementor sessionFactory) {
		super(sessionFactory);
	}
	
	/**
	 * @see SpringSessionContext#currentSession()
	 */
	@Override
	public Session currentSession() throws HibernateException {
		if (tempSessionHolder.get() != null) {
			//This method being again from below, return the cached session object to avoid stack overflow
			if (log.isTraceEnabled()) {
				log.trace("Session holder already contains a session object");
			}
			return tempSessionHolder.get();
		}
		
		Session session = super.currentSession();
		if (Daemon.isDaemonThread()) {
			if (log.isTraceEnabled()) {
				log.trace("Skipping enabling of filters on daemon thread");
			}
			return session;
		}
		
		if (Context.isAuthenticated() && Context.getAuthenticatedUser().isSuperUser()) {
			if (log.isTraceEnabled()) {
				log.trace("Disabling filters for super user");
			}
			
			for (String filter : DataFilterConstants.FILTER_NAMES) {
				session.disableFilter(filter);
			}
			
			return session;
		}
		
		if (log.isDebugEnabled()) {
			log.debug("Enabling filter on the current session");
		}
		
		Integer attributeTypeId;
		try {
			//Don't run this method on the next line since we're already inside it
			tempSessionHolder.set(session);
			attributeTypeId = AccessUtil.getPersonAttributeTypeId(Location.class);
			//In tests, we can get here because test data is getting setup or flushed to the db
			if (attributeTypeId == null) {
				//In theory this should match no attribute type hence no patients
				attributeTypeId = -1;
			}
		}
		finally {
			tempSessionHolder.remove();
		}
		
		Collection<String> basisIds = new HashSet();
		if (Context.isAuthenticated()) {
			try {
				tempSessionHolder.set(session);
				basisIds.addAll(AccessUtil.getAssignedBasisIds(Location.class));
			}
			finally {
				tempSessionHolder.remove();
			}
		}
		
		if (basisIds.isEmpty()) {
			//If the user isn't granted access to patients at any basis, we add -1 because ids are all > 0,
			//in theory the query will match no records if the user isn't granted access to any basis
			basisIds = Collections.singleton("-1");
		}
		
		enableFilter(FILTER_NAME_PATIENT, attributeTypeId, basisIds, session);
		enableFilter(FILTER_NAME_VISIT, attributeTypeId, basisIds, session);
		enableFilter(FILTER_NAME_ENCOUNTER, attributeTypeId, basisIds, session);
		enableFilter(FILTER_NAME_OBS, attributeTypeId, basisIds, session);
		
		return session;
	}
	
	private void enableFilter(String filterName, Integer attributeTypeId, Collection<String> basisIds, Session session) {
		Filter filter = session.getEnabledFilter(filterName);
		if (filter == null) {
			filter = session.enableFilter(filterName);
		}
		
		filter.setParameter(DataFilterConstants.PARAM_NAME_ATTRIB_TYPE_ID, attributeTypeId);
		filter.setParameterList(DataFilterConstants.PARAM_NAME_BASIS_IDS, basisIds);
	}
	
}
