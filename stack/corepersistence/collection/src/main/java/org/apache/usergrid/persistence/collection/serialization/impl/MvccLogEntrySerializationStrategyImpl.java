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
package org.apache.usergrid.persistence.collection.serialization.impl;


import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.marshal.IntegerType;
import org.apache.cassandra.db.marshal.ReversedType;
import org.apache.cassandra.db.marshal.UUIDType;

import org.apache.usergrid.persistence.collection.CollectionScope;
import org.apache.usergrid.persistence.collection.MvccLogEntry;
import org.apache.usergrid.persistence.collection.VersionSet;
import org.apache.usergrid.persistence.collection.exception.CollectionRuntimeException;
import org.apache.usergrid.persistence.collection.mvcc.MvccLogEntrySerializationStrategy;
import org.apache.usergrid.persistence.collection.mvcc.entity.Stage;
import org.apache.usergrid.persistence.collection.mvcc.entity.impl.MvccLogEntryImpl;
import org.apache.usergrid.persistence.collection.serialization.SerializationFig;
import org.apache.usergrid.persistence.core.astyanax.IdRowCompositeSerializer;
import org.apache.usergrid.persistence.core.astyanax.MultiTennantColumnFamily;
import org.apache.usergrid.persistence.core.astyanax.MultiTennantColumnFamilyDefinition;
import org.apache.usergrid.persistence.core.astyanax.ScopedRowKey;
import org.apache.usergrid.persistence.core.migration.Migration;
import org.apache.usergrid.persistence.model.entity.Id;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.astyanax.ColumnListMutation;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.model.Row;
import com.netflix.astyanax.serializers.AbstractSerializer;
import com.netflix.astyanax.serializers.UUIDSerializer;


/**
 * Simple implementation for reading and writing log entries
 *
 * @author tnine
 */
@Singleton
public class MvccLogEntrySerializationStrategyImpl implements MvccLogEntrySerializationStrategy, Migration {

    private static final Logger LOG = LoggerFactory.getLogger( MvccLogEntrySerializationStrategyImpl.class );

    private static final StageSerializer SER = new StageSerializer();

    private static final IdRowCompositeSerializer ID_SER = IdRowCompositeSerializer.get();

    private static final CollectionScopedRowKeySerializer<Id> ROW_KEY_SER =
            new CollectionScopedRowKeySerializer<Id>( ID_SER );

    private static final MultiTennantColumnFamily<CollectionScope, Id, UUID> CF_ENTITY_LOG =
            new MultiTennantColumnFamily<CollectionScope, Id, UUID>( "Entity_Log", ROW_KEY_SER, UUIDSerializer.get() );


    protected final Keyspace keyspace;
    protected final SerializationFig fig;


    @Inject
    public MvccLogEntrySerializationStrategyImpl( final Keyspace keyspace, final SerializationFig fig ) {
        this.keyspace = keyspace;
        this.fig = fig;
    }


    @Override
    public MutationBatch write( final CollectionScope collectionScope, final MvccLogEntry entry ) {

        Preconditions.checkNotNull( collectionScope, "collectionScope is required" );
        Preconditions.checkNotNull( entry, "entry is required" );


        final Stage stage = entry.getStage();
        final UUID colName = entry.getVersion();
        final StageStatus stageStatus = new StageStatus( stage, entry.getState() );

        return doWrite( collectionScope, entry.getEntityId(), entry.getVersion(), new RowOp() {
            @Override
            public void doOp( final ColumnListMutation<UUID> colMutation ) {

                //Write the stage with a timeout, it's set as transient
                if ( stage.isTransient() ) {
                    colMutation.putColumn( colName, stageStatus, SER, fig.getTimeout() );
                    return;
                }

                //otherwise it's persistent, write it with no expiration
                colMutation.putColumn( colName, stageStatus, SER, null );
            }
        } );
    }


    @Override
    public VersionSet load( final CollectionScope collectionScope, final Collection<Id> entityIds,
                            final UUID maxVersion ) {
        Preconditions.checkNotNull( collectionScope, "collectionScope is required" );
        Preconditions.checkNotNull( entityIds, "entityIds is required" );
        Preconditions.checkArgument( entityIds.size() > 0, "You must specify an Id" );
        Preconditions.checkNotNull( maxVersion, "maxVersion is required" );


        //didnt put the max in the error message, I don't want to take the string construction hit every time
        Preconditions.checkArgument( entityIds.size() <= fig.getMaxLoadSize(),
                "requested size cannot be over configured maximum" );


        final List<ScopedRowKey<CollectionScope, Id>> rowKeys = new ArrayList<>( entityIds.size() );


        for ( final Id entityId : entityIds ) {
            rowKeys.add( ScopedRowKey.fromKey( collectionScope, entityId ) );
        }


        final Iterator<Row<ScopedRowKey<CollectionScope, Id>, UUID>> latestEntityColumns;


        try {
            latestEntityColumns = keyspace.prepareQuery( CF_ENTITY_LOG ).getKeySlice( rowKeys )
                                          .withColumnRange( maxVersion, null, false, 1 ).execute().getResult()
                                          .iterator();
        }
        catch ( ConnectionException e ) {
            throw new CollectionRuntimeException( null, collectionScope, "An error occurred connecting to cassandra",
                    e );
        }


        final VersionSetImpl versionResults = new VersionSetImpl( entityIds.size() );

        while ( latestEntityColumns.hasNext() ) {
            final Row<ScopedRowKey<CollectionScope, Id>, UUID> row = latestEntityColumns.next();

            final ColumnList<UUID> columns = row.getColumns();

            if ( columns.size() == 0 ) {
                continue;
            }


            final Id entityId = row.getKey().getKey();

            final Column<UUID> column = columns.getColumnByIndex( 0 );


            final UUID version = column.getName();

            final StageStatus stageStatus = column.getValue( SER );

            final MvccLogEntry logEntry =
                    new MvccLogEntryImpl( entityId, version, stageStatus.stage, stageStatus.state );


            versionResults.addEntry( logEntry );
        }

        return versionResults;
    }


    @Override
    public List<MvccLogEntry> load( final CollectionScope collectionScope, final Id entityId, final UUID version,
                                    final int maxSize ) {
        Preconditions.checkNotNull( collectionScope, "collectionScope is required" );
        Preconditions.checkNotNull( entityId, "entity id is required" );
        Preconditions.checkNotNull( version, "version is required" );
        Preconditions.checkArgument( maxSize > 0, "max Size must be greater than 0" );


        ColumnList<UUID> columns = null;
        try {
            columns = keyspace.prepareQuery( CF_ENTITY_LOG ).getKey( ScopedRowKey.fromKey( collectionScope, entityId ) )
                              .withColumnRange( version, null, false, maxSize ).execute().getResult();
        }
        catch ( ConnectionException e ) {
            throw new RuntimeException( "Unable to load log entries", e );
        }


        List<MvccLogEntry> results = new ArrayList<MvccLogEntry>( columns.size() );

        for ( Column<UUID> col : columns ) {
            final UUID storedVersion = col.getName();
            final StageStatus stage = col.getValue( SER );

            results.add( new MvccLogEntryImpl( entityId, storedVersion, stage.stage, stage.state ) );
        }

        return results;
    }


    @Override
    public MutationBatch delete( final CollectionScope context, final Id entityId, final UUID version ) {

        Preconditions.checkNotNull( context, "context is required" );
        Preconditions.checkNotNull( entityId, "entityId is required" );
        Preconditions.checkNotNull( version, "version context is required" );

        return doWrite( context, entityId, version, new RowOp() {
            @Override
            public void doOp( final ColumnListMutation<UUID> colMutation ) {
                colMutation.deleteColumn( version );
            }
        } );
    }


    @Override
    public Collection<MultiTennantColumnFamilyDefinition> getColumnFamilies() {
        //create the CF entity data.  We want it reversed b/c we want the most recent version at the top of the
        //row for fast seeks
        MultiTennantColumnFamilyDefinition cf =
                new MultiTennantColumnFamilyDefinition( CF_ENTITY_LOG, BytesType.class.getSimpleName(),
                        ReversedType.class.getSimpleName() + "(" + UUIDType.class.getSimpleName() + ")",
                        IntegerType.class.getSimpleName(), MultiTennantColumnFamilyDefinition.CacheOption.KEYS );


        return Collections.singleton( cf );
    }


    /**
     * Simple callback to perform puts and deletes with a common row setup code
     */
    private static interface RowOp {

        /**
         * The operation to perform on the row
         */
        void doOp( ColumnListMutation<UUID> colMutation );
    }


    /**
     * Do the column update or delete for the given column and row key
     *
     * @param context We need to use this when getting the keyspace
     */
    private MutationBatch doWrite( CollectionScope context, Id entityId, UUID version, RowOp op ) {

        final MutationBatch batch = keyspace.prepareMutationBatch();

        final long timestamp = version.timestamp();

        LOG.debug( "Writing version with timestamp '{}'", timestamp );

        op.doOp( batch.withRow( CF_ENTITY_LOG, ScopedRowKey.fromKey( context, entityId ) ) );

        return batch;
    }


    /**
     * Internal stage shard
     */
    private static class StageCache {
        private Map<Integer, Stage> values = new HashMap<Integer, Stage>( Stage.values().length );


        private StageCache() {
            for ( Stage stage : Stage.values() ) {

                final int stageValue = stage.getId();

                values.put( stageValue, stage );
            }
        }


        /**
         * Get the stage with the byte value
         */
        private Stage getStage( final int value ) {
            return values.get( value );
        }
    }


    /**
     * Internal stage shard
     */
    private static class StatusCache {
        private Map<Integer, MvccLogEntry.State> values =
                new HashMap<Integer, MvccLogEntry.State>( MvccLogEntry.State.values().length );


        private StatusCache() {
            for ( MvccLogEntry.State state : MvccLogEntry.State.values() ) {

                final int statusValue = state.getId();

                values.put( statusValue, state );
            }
        }


        /**
         * Get the stage with the byte value
         */
        private MvccLogEntry.State getStatus( final int value ) {
            return values.get( value );
        }
    }


    public static class StageSerializer extends AbstractSerializer<StageStatus> {

        /**
         * Used for caching the byte => stage mapping
         */
        private static final StageCache CACHE = new StageCache();
        private static final StatusCache STATUS_CACHE = new StatusCache();


        @Override
        public ByteBuffer toByteBuffer( final StageStatus obj ) {

            ByteBuffer byteBuffer = ByteBuffer.allocate( 8 );
            byteBuffer.putInt( obj.stage.getId() );
            byteBuffer.putInt( obj.state.getId() );
            byteBuffer.rewind();
            return byteBuffer;
        }


        @Override
        public StageStatus fromByteBuffer( final ByteBuffer byteBuffer ) {
            int value = byteBuffer.getInt();
            Stage stage = CACHE.getStage( value );
            value = byteBuffer.getInt();
            MvccLogEntry.State state = STATUS_CACHE.getStatus( value );
            return new StageStatus( stage, state );
        }
    }


    public static class StageStatus {
        final Stage stage;
        final MvccLogEntry.State state;


        public StageStatus( Stage stage, MvccLogEntry.State state ) {
            this.stage = stage;
            this.state = state;
        }
    }
}