/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.constraints;

import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.ConstraintType;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.impl.coreapi.schema.InternalSchemaActions;
import org.neo4j.kernel.impl.coreapi.schema.MandatoryRelationshipPropertyConstraintDefinition;

public class MandatoryRelationshipPropertyConstraint extends RelationshipPropertyConstraint
{
    public MandatoryRelationshipPropertyConstraint( int relTypeId, int propertyKeyId )
    {
        super( relTypeId, propertyKeyId );
    }

    @Override
    public void added( ChangeVisitor visitor )
    {
        visitor.visitAddedRelationshipMandatoryPropertyConstraint( this );
    }

    @Override
    public void removed( ChangeVisitor visitor )
    {
        visitor.visitRemovedRelationshipMandatoryPropertyConstraint( this );
    }

    @Override
    public ConstraintType type()
    {
        return ConstraintType.MANDATORY_RELATIONSHIP_PROPERTY;
    }

    @Override
    String constraintString()
    {
        return "NOT NULL";
    }

    @Override
    public ConstraintDefinition asConstraintDefinition( InternalSchemaActions schemaActions, ReadOperations readOps )
    {
        return new MandatoryRelationshipPropertyConstraintDefinition( schemaActions,
                relTypeById( relationshipType(), readOps ), propertyKeyById( propertyKeyId, readOps ) );
    }
}
