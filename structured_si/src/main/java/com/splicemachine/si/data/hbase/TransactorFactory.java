package com.splicemachine.si.data.hbase;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.splicemachine.constants.SIConstants;
import com.splicemachine.si.impl.ImmutableTransaction;
import com.splicemachine.si.impl.SITransactor;
import com.splicemachine.si.impl.SystemClock;
import com.splicemachine.si.impl.Transaction;
import com.splicemachine.si.impl.TransactionSchema;
import com.splicemachine.si.data.api.SDataLib;
import com.splicemachine.si.data.api.STableReader;
import com.splicemachine.si.data.api.STableWriter;
import com.splicemachine.si.api.ClientTransactor;
import com.splicemachine.si.api.TimestampSource;
import com.splicemachine.si.api.Transactor;
import com.splicemachine.si.impl.DataStore;
import com.splicemachine.si.impl.TransactionStore;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.client.HTablePool;
import java.util.concurrent.TimeUnit;

public class TransactorFactory extends SIConstants {
    private static Transactor defaultTransactor;
    private static volatile HTablePool hTablePool;

    public static void setDefaultTransactor(Transactor transactorToUse) {
        defaultTransactor = transactorToUse;
    }

    public static Transactor getDefaultTransactor() {
        return defaultTransactor;
    }

    public static ClientTransactor getDefaultClientTransactor() {
        return (ClientTransactor) defaultTransactor;
    }

    public static Transactor getTransactor(Configuration configuration, TimestampSource timestampSource) {
        if (hTablePool == null) {
            synchronized (TransactorFactory.class) {
                if (hTablePool == null) {
                    hTablePool = new HTablePool(configuration, Integer.MAX_VALUE);
                }
            }
        }
        return getTransactor(hTablePool, timestampSource);
    }

    public static Transactor getTransactor(HTablePool pool, TimestampSource timestampSource) {
        return getTransactorDirect(new HPoolTableSource(pool), timestampSource);
    }

    public static Transactor getTransactor(CoprocessorEnvironment environment, TimestampSource timestampSource) {
        return getTransactorDirect(new HCoprocessorTableSource(environment), timestampSource);
    }

    public static Transactor getTransactorDirect(HTableSource tableSource, TimestampSource timestampSource) {
        HStore store = new HStore(tableSource);
        SDataLib dataLib = new HDataLibAdapter(new HDataLib());
        final STableReader reader = new HTableReaderAdapter(store);
        final STableWriter writer = new HTableWriterAdapter(store);
        final TransactionSchema transactionSchema = new TransactionSchema(TRANSACTION_TABLE,
                DEFAULT_FAMILY,
                SNAPSHOT_ISOLATION_CHILDREN_FAMILY,
                EMPTY_BYTE_ARRAY,
                TRANSACTION_START_TIMESTAMP_COLUMN,
                TRANSACTION_PARENT_COLUMN_BYTES,
                TRANSACTION_DEPENDENT_COLUMN_BYTES,
                TRANSACTION_ALLOW_WRITES_COLUMN_BYTES,
                TRANSACTION_READ_UNCOMMITTED_COLUMN_BYTES,
                TRANSACTION_READ_COMMITTED_COLUMN_BYTES,
                TRANSACTION_COMMIT_TIMESTAMP_COLUMN, TRANSACTION_STATUS_COLUMN,
                TRANSACTION_KEEP_ALIVE_COLUMN);
        final Cache<Long,Transaction> cache = CacheBuilder.newBuilder().maximumSize(10000).expireAfterWrite(5, TimeUnit.MINUTES).build();
        final Cache<Long,ImmutableTransaction> immutableCache = CacheBuilder.newBuilder().maximumSize(10000).expireAfterWrite(5, TimeUnit.MINUTES).build();
        final TransactionStore transactionStore = new TransactionStore(transactionSchema, dataLib, reader, writer, cache,
                immutableCache);

        final DataStore rowStore = new DataStore(dataLib, reader, writer, "si-needed",
                "si-transaction-id", "si-delete-put", SNAPSHOT_ISOLATION_FAMILY,
                SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN,
                SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN,
                EMPTY_BYTE_ARRAY, SNAPSHOT_ISOLATION_FAILED_TIMESTAMP,
                DEFAULT_FAMILY);
        return new SITransactor(timestampSource, dataLib, writer, rowStore, transactionStore, new SystemClock(),10 * 60 * 1000);
    }
}
