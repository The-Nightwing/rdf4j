/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.repository.evaluation;

import org.eclipse.rdf4j.common.iteration.CloseableExceptionConvertingIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.ExceptionConvertingIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;

public class RepositoryTripleSource implements TripleSource {

	private final RepositoryConnection repo;

	private final boolean includeInferred;

	public RepositoryTripleSource(RepositoryConnection repo) {
		this(repo, true);
	}

	public RepositoryTripleSource(RepositoryConnection repo, boolean includeInferred) {
		this.repo = repo;
		this.includeInferred = includeInferred;
	}

	@Override
	public CloseableExceptionConvertingIteration<Statement, QueryEvaluationException, RepositoryResult<Statement>> getStatements(
			Resource subj, IRI pred,
			Value obj, Resource... contexts) throws QueryEvaluationException {
		RepositoryResult<Statement> iter = null;
		CloseableExceptionConvertingIteration<Statement, QueryEvaluationException, RepositoryResult<Statement>> result = null;

		boolean allGood = false;
		try {
			iter = repo.getStatements(subj, pred, obj, includeInferred, contexts);
			result = new CloseableExceptionConvertingIteration<>(iter, QueryEvaluationException::new);
			allGood = true;
			return result;
		} catch (RepositoryException e) {
			throw new QueryEvaluationException(e);
		} finally {
			if (!allGood) {
				try {
					if (result != null) {
						result.close();
					}
				} finally {
					if (iter != null) {
						iter.close();
					}
				}
			}
		}
	}

	@Override
	public ValueFactory getValueFactory() {
		return repo.getValueFactory();
	}
}
