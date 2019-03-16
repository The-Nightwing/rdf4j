/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.query.algebra;

/**
 * @author David Huynh
 */
public class Min extends AbstractAggregateOperator {

	public Min(ValueExpr arg) {
		super(arg);
	}

	public Min(ValueExpr arg, boolean distinct) {
		super(arg, distinct);
	}

	@Override
	public <X extends Exception> void visit(QueryModelVisitor<X> visitor) throws X {
		visitor.meet(this);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Min && super.equals(other);
	}

	@Override
	public int hashCode() {
		return super.hashCode() ^ "Min".hashCode();
	}

	@Override
	public Min clone() {
		return (Min) super.clone();
	}
}
