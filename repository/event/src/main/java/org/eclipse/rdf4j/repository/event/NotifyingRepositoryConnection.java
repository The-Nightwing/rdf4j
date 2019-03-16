/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.repository.event;

import org.eclipse.rdf4j.repository.RepositoryConnection;

/**
 * @author Herko ter Horst
 */
public interface NotifyingRepositoryConnection extends RepositoryConnection {

	/**
	 * Registers a <tt>RepositoryConnectionListener</tt> that will receive notifications of operations that are
	 * performed on this connection.
	 */
	public void addRepositoryConnectionListener(RepositoryConnectionListener listener);

	/**
	 * Removes a registered <tt>RepositoryConnectionListener</tt> from this connection.
	 */
	public void removeRepositoryConnectionListener(RepositoryConnectionListener listener);

}
