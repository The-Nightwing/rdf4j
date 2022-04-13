/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.repository.sail;

import org.eclipse.rdf4j.common.iteration.CloseableExceptionConvertingIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.sail.SailException;

/**
 * @author Herko ter Horst
 */
class SailCloseableIteration<E> extends
		CloseableExceptionConvertingIteration<E, RepositoryException, CloseableIteration<? extends E, ? extends SailException>> {

	public SailCloseableIteration(CloseableIteration<? extends E, ? extends SailException> iter) {
		super(iter, e -> {
			if (e instanceof SailException) {
				return new RepositoryException(e);
			} else if (e instanceof RuntimeException) {
				throw (RuntimeException) e;
			} else if (e == null) {
				throw new IllegalArgumentException("e must not be null");
			} else {
				throw new IllegalArgumentException("Unexpected exception type: " + e.getClass());
			}
		});
	}

}
