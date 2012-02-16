/*
 ###############################################################################
 #                                                                             #
 #    Copyright (C) 2011 OpenMEAP, Inc.                                        #
 #    Credits to Jonathan Schang & Robert Thacher                              #
 #                                                                             #
 #    Released under the GPLv3                                                 #
 #                                                                             #
 #    OpenMEAP is free software: you can redistribute it and/or modify         #
 #    it under the terms of the GNU General Public License as published by     #
 #    the Free Software Foundation, either version 3 of the License, or        #
 #    (at your option) any later version.                                      #
 #                                                                             #
 #    OpenMEAP is distributed in the hope that it will be useful,              #
 #    but WITHOUT ANY WARRANTY; without even the implied warranty of           #
 #    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the            #
 #    GNU General Public License for more details.                             #
 #                                                                             #
 #    You should have received a copy of the GNU General Public License        #
 #    along with OpenMEAP.  If not, see <http://www.gnu.org/licenses/>.        #
 #                                                                             #
 ###############################################################################
 */

package com.openmeap.cluster;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.openmeap.Event;
import com.openmeap.EventNotifier;
import com.openmeap.model.ModelManager;
import com.openmeap.model.dto.GlobalSettings;
import com.openmeap.util.AuthTokenProvider;
import com.openmeap.util.ThrowableList;
import com.openmeap.util.HttpRequestExecuter;

// TODO: separate this out into a ClusterNodesNotifierService and use delegate methods from the notifiers
public abstract class AbstractClusterServiceMgmtNotifier<T> implements EventNotifier<T> {
	
	private Logger logger = LoggerFactory.getLogger(AbstractClusterServiceMgmtNotifier.class);
	
	/**
	 * If null, then the notification requests are executed serially
	 */
	private ExecutorService executorService = null;
	private HttpRequestExecuter httpRequestExecuter = null;
	private ClusterServiceNotifierConfig config = null;
	private Long executorTimeout = null;
	
	protected void onBeforeNotify(final Event<T> event) {}
	protected void onAfterNotify(final Event<T> event) {}
	
	abstract protected void makeRequest(URL url, Event<T> message) throws ClusterNotificationException;
	
	public <E extends Event<T>> void notify(final E event) throws ClusterNotificationException {
		
		final ThrowableList exceptions = new ThrowableList();
		
		onBeforeNotify(event);
		
		final Map<String,Boolean> urlRequestCompleteStatus = new HashMap<String,Boolean>();
		for( final URL thisUrl : config.getWebServiceUrls() ) {
			if( executorService!=null ) {
				
				logger.debug("Making request to {} using the executor.",thisUrl);
				
				executorService.execute(new Runnable() {
							public void run() {
								notifyMakeRequest(exceptions,thisUrl,urlRequestCompleteStatus,event);
							}
						}
					);
				
			} else {
				logger.debug("Making request to {} serially.",thisUrl);
				notifyMakeRequest(exceptions,thisUrl,urlRequestCompleteStatus,event);
			}
		}
		
		// only block if they've configured a timeout
		// this set of if-else handles any exceptions in the list accumulated
		if( executorService!=null && executorTimeout!=null ) {
			try {
				// terminate, but make sure nothing is still waiting when we terminate
				if( ! executorService.awaitTermination(executorTimeout, TimeUnit.SECONDS) ) {
					executorService.shutdownNow();
					
					List<String> waiting = new ArrayList<String>();
					for( Map.Entry<String,Boolean> completed : urlRequestCompleteStatus.entrySet() ) {
						if( completed.getValue().equals(Boolean.FALSE) ) {
							waiting.add(completed.getKey());
						}
					}
					
					logger.error("Blocking timed-out still waiting to notify: {}", StringUtils.join(waiting,", "));
					throw new ClusterNotificationException( String.format("Blocking timed-out still waiting to notify: %s", StringUtils.join(waiting,", ")) );
				}
			} catch( InterruptedException ie ) {
				throw new ClusterNotificationException(ie);
			}
		} else if(exceptions.size()>0){
			throw new ClusterNotificationException( String.format("The following exceptions were thrown: %s",exceptions.toString()) );
		}
		
		onAfterNotify(event);
	}
	
	private <E extends Event<T>> void notifyMakeRequest(ThrowableList exceptions, URL thisUrl, 
			Map<String,Boolean> urlRequestCompleteStatus, E event) {
		try {
			urlRequestCompleteStatus.put(thisUrl.toString(), Boolean.FALSE);
			makeRequest(thisUrl,event);
			urlRequestCompleteStatus.put(thisUrl.toString(), Boolean.TRUE);
		} catch( ClusterNotificationException e ) {
			logger.error("ClusterNode with url {} threw and exception : {}",thisUrl,e);
			exceptions.add(e);
		}
	}
	
	protected String newAuthToken() {
		return AuthTokenProvider.newAuthToken(config.getAuthSalt());
	}
	
	/**
	 * The ModelServiceRefreshNotifier must have a request executer
	 * @param httpExecuter
	 */
	public void setHttpRequestExecuter(HttpRequestExecuter httpExecuter) {
		httpRequestExecuter = httpExecuter;
	}
	public HttpRequestExecuter getHttpRequestExecuter() {
		return httpRequestExecuter;
	}
	
	/**
	 * Set if you want an Executor to parallelize and background the requests 
	 * @param executor 
	 */
	public void setExecutor(ExecutorService executor) {
		this.executorService = executor;
	}
	public ExecutorService getExecutor() {
		return executorService;
	}
	
	public void setConfig(ClusterServiceNotifierConfig config) {
		this.config = config;
	}
	public ClusterServiceNotifierConfig getConfig() {
		return config;
	}
	
	public void setExecutorTimeout(Long executorTimeout) {
		this.executorTimeout = executorTimeout;
	}
	public Long getExecutorTimeout() {
		return executorTimeout;
	}
}