/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.usergrid.persistence.collection.mvcc.stage.write;


import com.google.inject.Inject;
import org.apache.usergrid.persistence.collection.CollectionScope;
import org.apache.usergrid.persistence.collection.EntityCollectionManager;
import org.apache.usergrid.persistence.collection.EntityCollectionManagerFactory;
import org.apache.usergrid.persistence.collection.exception.WriteUniqueVerifyException;
import org.apache.usergrid.persistence.collection.guice.MigrationManagerRule;
import org.apache.usergrid.persistence.collection.guice.TestCollectionModule;
import org.apache.usergrid.persistence.collection.impl.CollectionScopeImpl;
import org.apache.usergrid.persistence.collection.mvcc.stage.TestEntityGenerator;
import org.apache.usergrid.persistence.core.cassandra.ITRunner;
import org.apache.usergrid.persistence.model.entity.Entity;
import org.apache.usergrid.persistence.model.entity.Id;
import org.apache.usergrid.persistence.model.entity.SimpleId;
import org.apache.usergrid.persistence.model.field.IntegerField;
import org.apache.usergrid.persistence.model.field.StringField;
import org.jukito.UseModules;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;


/**
 * Simple integration test of uniqueness verification.
 */
@RunWith( ITRunner.class )
@UseModules( TestCollectionModule.class )
public class WriteUniqueVerifyIT {

    @Inject
    @Rule
    public MigrationManagerRule migrationManagerRule;

    @Inject
    public EntityCollectionManagerFactory cmf;

    @Test
    public void testConflict() {

        final Id orgId = new SimpleId("WriteUniqueVerifyIT");
        final Id appId = new SimpleId("testConflict");

        final CollectionScope scope = new CollectionScopeImpl( appId, orgId, "fastcars" );
        final EntityCollectionManager entityManager = cmf.createCollectionManager( scope );

        final Entity entity = TestEntityGenerator.generateEntity();
        entity.setField(new StringField("name", "Aston Martin Vanquish", true));
        entity.setField(new StringField("identifier", "v12", true));
        entity.setField(new IntegerField("top_speed_mph", 200));
        entityManager.write( entity ).toBlockingObservable().last();

        Entity entityFetched = entityManager.load( entity.getId() ).toBlockingObservable().last();
        entityFetched.setField( new StringField("foo", "bar"));

        // another enity that tries to use two unique values already taken by first
        final Entity entity2 = TestEntityGenerator.generateEntity();
        entity2.setField(new StringField("name", "Aston Martin Vanquish", true));
        entity2.setField(new StringField("identifier", "v12", true));
        entity2.setField(new IntegerField("top_speed_mph", 120));

        try {
            entityManager.write( entity2 ).toBlockingObservable().last();
            fail("Write should have thrown an exception");

        } catch ( WriteUniqueVerifyException e ) {
            // verify two unique value violations
            assertEquals( 2, e.getVioliations().size() );
        }

        // ensure we can update original entity without error
        entity.setField( new IntegerField("top_speed_mph", 190) );
        entityManager.write( entity );
    }

    @Test
    public void testNoConflict1() {

        final Id orgId = new SimpleId("WriteUniqueVerifyIT");
        final Id appId = new SimpleId("testNoConflict");

        final CollectionScope scope = new CollectionScopeImpl( appId, orgId, "fastcars" );
        final EntityCollectionManager entityManager = cmf.createCollectionManager( scope );

        final Entity entity = TestEntityGenerator.generateEntity();
        entity.setField(new StringField("name", "Porsche 911 GT3", true));
        entity.setField(new StringField("identifier", "911gt3", true));
        entity.setField(new IntegerField("top_speed_mph", 194));
        entityManager.write( entity ).toBlockingObservable().last();

        Entity entityFetched = entityManager.load( entity.getId() ).toBlockingObservable().last();
        entityFetched.setField( new StringField("foo", "baz"));
        entityManager.write( entityFetched ).toBlockingObservable().last();
    }

    @Test
    public void testNoConflict2() {

        final Id orgId = new SimpleId("WriteUniqueVerifyIT");
        final Id appId = new SimpleId("testNoConflict");

        final CollectionScope scope = new CollectionScopeImpl( appId, orgId, "fastcars" );
        final EntityCollectionManager entityManager = cmf.createCollectionManager( scope );

        final Entity entity = TestEntityGenerator.generateEntity();
        entity.setField(new StringField("name", "Alfa Romeo 8C Competizione", true));
        entity.setField(new StringField("identifier", "ar8c", true));
        entity.setField(new IntegerField("top_speed_mph", 182));
        entityManager.write( entity ).toBlockingObservable().last();

        entity.setField( new StringField("foo", "bar"));
        entityManager.write( entity ).toBlockingObservable().last();
    }
}
