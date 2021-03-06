/*
 * Copyright (c) 2017. EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.activestore.impl.cassandra.session;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.exceptions.AlreadyExistsException;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.datastax.driver.core.querybuilder.Batch;
import javax.cache.Cache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.activestore.impl.cassandra.CassandraHelperEx;
import org.apache.ignite.activestore.impl.cassandra.session.pool.SessionPool;
import org.apache.ignite.cache.store.cassandra.common.CassandraHelper;
import org.apache.ignite.cache.store.cassandra.common.RandomSleeper;
import org.apache.ignite.cache.store.cassandra.persistence.KeyValuePersistenceSettings;
import org.apache.ignite.cache.store.cassandra.session.BatchExecutionAssistant;
import org.apache.ignite.cache.store.cassandra.session.BatchLoaderAssistant;
import org.apache.ignite.cache.store.cassandra.session.ExecutionAssistant;
import org.apache.ignite.internal.processors.cache.CacheEntryImpl;

/**
 * Implementation for {@link CassandraSession}.
 */
public class CassandraSessionImpl implements CassandraSession {
    /** Number of CQL query execution attempts. */
    private static final int CQL_EXECUTION_ATTEMPTS_COUNT = 20;

    /** Min timeout between CQL query execution attempts. */
    private static final int CQL_EXECUTION_ATTEMPT_MIN_TIMEOUT = 100;

    /** Max timeout between CQL query execution attempts. */
    private static final int CQL_EXECUTION_ATTEMPT_MAX_TIMEOUT = 500;

    /** Timeout increment for CQL query execution attempts. */
    private static final int CQL_ATTEMPTS_TIMEOUT_INCREMENT = 100;
    /** Storage for the session prepared statements */
    private static final Map<String, PreparedStatement> sesStatements = new HashMap<>();
    /** Table absence error handlers counter. */
    private final AtomicInteger tblAbsenceHandlersCnt = new AtomicInteger(-1);
    /** Prepared statement cluster disconnection error handlers counter. */
    private final AtomicInteger prepStatementHandlersCnt = new AtomicInteger(-1);
    /** Cassandra cluster builder. */
    private volatile Cluster.Builder builder;
    /** Cassandra driver session. */
    private volatile Session ses;
    /** Number of references to Cassandra driver session (for multithreaded environment). */
    private volatile int refCnt = 0;
    /** Number of records to immediately fetch in CQL statement execution. */
    private Integer fetchSize;
    /** Consistency level for Cassandra READ operations (select). */
    private ConsistencyLevel readConsistency;
    /** Consistency level for Cassandra WRITE operations (insert/update/delete). */
    private ConsistencyLevel writeConsistency;
    /** Logger. */
    private IgniteLogger log;

    /**
     * Creates instance of Cassandra driver session wrapper.
     *
     * @param builder Builder for Cassandra cluster.
     * @param fetchSize Number of rows to immediately fetch in CQL statement execution.
     * @param readConsistency Consistency level for Cassandra READ operations (select).
     * @param writeConsistency Consistency level for Cassandra WRITE operations (insert/update/delete).
     * @param log Logger.
     */
    public CassandraSessionImpl(Cluster.Builder builder, Integer fetchSize, ConsistencyLevel readConsistency,
        ConsistencyLevel writeConsistency, IgniteLogger log) {
        this.builder = builder;
        this.fetchSize = fetchSize;
        this.readConsistency = readConsistency;
        this.writeConsistency = writeConsistency;
        this.log = log;
    }

    /** {@inheritDoc} */
    @Override public <V> V execute(ExecutionAssistant<V> assistant) {
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to execute Cassandra CQL statement: " + assistant.getStatement() +
            " in operation " + assistant.operationName();
        RandomSleeper sleeper = newSleeper();
        incrementSessionRefs();
        try {
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                error = null;
                if (attempt != 0) {
                    log.warning("Trying " + (attempt + 1) + " attempt to execute Cassandra CQL statement: " +
                        assistant.getStatement());
                }
                try {
                    PreparedStatement preparedSt = prepareStatement(assistant.getStatement(),
                        assistant.getPersistenceSettings(), assistant.tableExistenceRequired(),
                        assistant.tableExistenceRequired());
                    if (preparedSt == null) {
                        return null;
                    }
                    Statement statement = tuneStatementExecutionOptions(assistant.bindStatement(preparedSt));
                    ResultSet res = session().execute(statement);
                    Row row = res == null || !res.iterator().hasNext() ? null : res.iterator().next();
                    return row == null ? null : assistant.process(row);
                }
                catch (Throwable e) {
                    error = e;
                    if (CassandraHelper.isTableAbsenceError(e)) {
                        if (!assistant.tableExistenceRequired()) {
                            log.warning(errorMsg, e);
                            return null;
                        }
                        handleTableAbsenceError(assistant.getPersistenceSettings());
                    }
                    else if (CassandraHelper.isHostsAvailabilityError(e)) {
                        handleHostsAvailabilityError(e, attempt, errorMsg);
                    }
                    else if (CassandraHelper.isPreparedStatementClusterError(e)) {
                        handlePreparedStatementClusterError(e);
                    }
                    else {
                        // For an error which we don't know how to handle, we will not try next attempts and terminate.
                        throw new IgniteException(errorMsg, e);
                    }
                }
                sleeper.sleep();
                attempt++;
            }
        }
        catch (Throwable e) {
            error = e;
        }
        finally {
            decrementSessionRefs();
        }
        log.error(errorMsg, error);
        throw new IgniteException(errorMsg, error);
    }

    /** {@inheritDoc} */
    @Override public <R, V> R execute(BatchExecutionAssistant<R, V> assistant, Iterable<? extends V> data) {
        if (data == null || !data.iterator().hasNext()) {
            return assistant.processedData();
        }
        int attempt = 0;
        String errorMsg = "Failed to execute Cassandra " + assistant.operationName() + " operation";
        Throwable error = new IgniteException(errorMsg);
        RandomSleeper sleeper = newSleeper();
        int dataSize = 0;
        incrementSessionRefs();
        try {
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                if (attempt != 0) {
                    log.warning("Trying " + (attempt + 1) + " attempt to execute Cassandra batch " +
                        assistant.operationName() + " operation to process rest " +
                        (dataSize - assistant.processedCount()) + " of " + dataSize + " elements");
                }
                //clean errors info before next communication with Cassandra
                Throwable unknownEx = null;
                Throwable tblAbsenceEx = null;
                Throwable hostsAvailEx = null;
                Throwable prepStatEx = null;
                List<Cache.Entry<Integer, ResultSetFuture>> futResults = new LinkedList<>();
                PreparedStatement preparedSt = prepareStatement(assistant.getStatement(),
                    assistant.getPersistenceSettings(), assistant.tableExistenceRequired(),
                    assistant.tableExistenceRequired());
                if (preparedSt == null) {
                    return null;
                }
                int seqNum = 0;
                for (V obj : data) {
                    if (!assistant.alreadyProcessed(seqNum)) {
                        try {
                            Statement statement = tuneStatementExecutionOptions(
                                assistant.bindStatement(preparedSt, obj));
                            ResultSetFuture fut = session().executeAsync(statement);
                            futResults.add(new CacheEntryImpl<>(seqNum, fut));
                        }
                        catch (Throwable e) {
                            if (CassandraHelper.isTableAbsenceError(e)) {
                                // If there is table absence error and not required for the operation we can return.
                                if (!assistant.tableExistenceRequired()) {
                                    return assistant.processedData();
                                }
                                tblAbsenceEx = e;
                                handleTableAbsenceError(assistant.getPersistenceSettings());
                            }
                            else if (CassandraHelper.isHostsAvailabilityError(e)) {
                                hostsAvailEx = e;
                                // Handle host availability only once.
                                if (hostsAvailEx == null) {
                                    handleHostsAvailabilityError(e, attempt, errorMsg);
                                }
                            }
                            else if (CassandraHelper.isPreparedStatementClusterError(e)) {
                                prepStatEx = e;
                                handlePreparedStatementClusterError(e);
                            }
                            else {
                                unknownEx = e;
                            }
                        }
                    }
                    seqNum++;
                }
                dataSize = seqNum;
                // For an error which we don't know how to handle, we will not try next attempts and terminate.
                if (unknownEx != null) {
                    throw new IgniteException(errorMsg, unknownEx);
                }
                // Remembering any of last errors.
                if (tblAbsenceEx != null) {
                    error = tblAbsenceEx;
                }
                else if (hostsAvailEx != null) {
                    error = hostsAvailEx;
                }
                else if (prepStatEx != null) {
                    error = prepStatEx;
                }
                // Clean errors info before next communication with Cassandra.
                unknownEx = null;
                tblAbsenceEx = null;
                hostsAvailEx = null;
                prepStatEx = null;
                for (Cache.Entry<Integer, ResultSetFuture> futureResult : futResults) {
                    try {
                        ResultSet resSet = futureResult.getValue().getUninterruptibly();
                        Row row = resSet != null && resSet.iterator().hasNext() ? resSet.iterator().next() : null;
                        if (row != null) {
                            assistant.process(row, futureResult.getKey());
                        }
                    }
                    catch (Throwable e) {
                        if (CassandraHelper.isTableAbsenceError(e)) {
                            tblAbsenceEx = e;
                        }
                        else if (CassandraHelper.isHostsAvailabilityError(e)) {
                            hostsAvailEx = e;
                        }
                        else if (CassandraHelper.isPreparedStatementClusterError(e)) {
                            prepStatEx = e;
                        }
                        else {
                            unknownEx = e;
                        }
                    }
                }
                // For an error which we don't know how to handle, we will not try next attempts and terminate.
                if (unknownEx != null) {
                    throw new IgniteException(errorMsg, unknownEx);
                }
                // If there are no errors occurred it means that operation successfully completed and we can return.
                if (tblAbsenceEx == null && hostsAvailEx == null && prepStatEx == null) {
                    return assistant.processedData();
                }
                if (tblAbsenceEx != null) {
                    // If there are table absence error and it is not required for the operation we can return.
                    if (!assistant.tableExistenceRequired()) {
                        return assistant.processedData();
                    }
                    error = tblAbsenceEx;
                    handleTableAbsenceError(assistant.getPersistenceSettings());
                }
                if (hostsAvailEx != null) {
                    error = hostsAvailEx;
                    handleHostsAvailabilityError(hostsAvailEx, attempt, errorMsg);
                }
                if (prepStatEx != null) {
                    error = prepStatEx;
                    handlePreparedStatementClusterError(prepStatEx);
                }
                sleeper.sleep();
                attempt++;
            }
        }
        catch (Throwable e) {
            error = e;
        }
        finally {
            decrementSessionRefs();
        }
        errorMsg = "Failed to process " + (dataSize - assistant.processedCount()) +
            " of " + dataSize + " elements, during " + assistant.operationName() +
            " operation with Cassandra";
        log.error(errorMsg, error);
        throw new IgniteException(errorMsg, error);
    }

    /** {@inheritDoc} */
    @Override public void execute(BatchLoaderAssistant assistant) {
        int attempt = 0;
        String errorMsg = "Failed to execute Cassandra " + assistant.operationName() + " operation";
        Throwable error = new IgniteException(errorMsg);
        RandomSleeper sleeper = newSleeper();
        incrementSessionRefs();
        try {
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                if (attempt != 0) {
                    log.warning("Trying " + (attempt + 1) + " attempt to load Ignite cache");
                }
                Statement statement = tuneStatementExecutionOptions(assistant.getStatement());
                try {
                    ResultSetFuture fut = session().executeAsync(statement);
                    ResultSet resSet = fut.getUninterruptibly();
                    if (resSet == null || !resSet.iterator().hasNext()) {
                        return;
                    }
                    for (Row row : resSet) {
                        assistant.process(row);
                    }
                    return;
                }
                catch (Throwable e) {
                    error = e;
                    if (CassandraHelper.isTableAbsenceError(e)) {
                        return;
                    }
                    else if (CassandraHelper.isHostsAvailabilityError(e)) {
                        handleHostsAvailabilityError(e, attempt, errorMsg);
                    }
                    else if (CassandraHelper.isPreparedStatementClusterError(e)) {
                        handlePreparedStatementClusterError(e);
                    }
                    else {
                        // For an error which we don't know how to handle, we will not try next attempts and terminate.
                        throw new IgniteException(errorMsg, e);
                    }
                }
                sleeper.sleep();
                attempt++;
            }
        }
        catch (Throwable e) {
            error = e;
        }
        finally {
            decrementSessionRefs();
        }
        log.error(errorMsg, error);
        throw new IgniteException(errorMsg, error);
    }

    @Override public void execute(TransactionExecutionAssistance assistant, Map<String,
        Collection<Cache.Entry<?, ?>>> mutations) {
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to execute Cassandra logged batch statement, to persist Ignite transaction data";
        RandomSleeper sleeper = newSleeper();
        Map<String, PreparedStatement> statements = new HashMap<>();
        int updatesCount = 0;
        incrementSessionRefs();
        try {
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                error = null;
                if (attempt != 0) {
                    log.warning("Trying " + (attempt + 1) + " attempt to execute Cassandra logged batch " +
                        " to perform " + updatesCount + " updates");
                }
                try {
                    BatchStatement batch = new BatchStatement();
                    for (Map.Entry<String, Collection<Cache.Entry<?, ?>>> entry : mutations.entrySet()) {
                        String cache = entry.getKey();
                        Collection<Cache.Entry<?, ?>> changes = entry.getValue();
                        PreparedStatement st = statements.get(cache);
                        if (st == null) {
                            st = prepareStatement(assistant.getStatement(cache),
                                assistant.getPersistenceSettings(cache), true, assistant.tableExistenceRequired());
                            if (st == null) {
                                return;
                            }
                            statements.put(cache, st);
                        }
                        for (Cache.Entry mutation : changes) {
                            batch.add(assistant.bindStatement(cache, st, mutation));
                            if (attempt == 0) {
                                updatesCount++;
                            }
                        }
                    }
                    session().execute(tuneStatementExecutionOptions(batch));
                    return;
                }
                catch (Throwable e) {
                    error = e;
                    if (CassandraHelper.isTableAbsenceError(e)) {
                        if (assistant.tableExistenceRequired()) {
                            for (Map.Entry<String, Collection<Cache.Entry<?, ?>>> entry : mutations.entrySet())
                                handleTableAbsenceError(assistant.getPersistenceSettings(entry.getKey()));
                        }
                        else {
                            throw new IgniteException(errorMsg, e);
                        }
                    }
                    else if (CassandraHelper.isHostsAvailabilityError(e)) {
                        if (handleHostsAvailabilityError(e, attempt, errorMsg)) {
                            statements.clear();
                        }
                    }
                    else if (CassandraHelper.isPreparedStatementClusterError(e)) {
                        handlePreparedStatementClusterError(e);
                        statements.clear();
                    }
                    else {
                        // For an error which we don't know how to handle, we will not try next attempts and terminate.
                        throw new IgniteException(errorMsg, e);
                    }
                }
                sleeper.sleep();
                attempt++;
            }
        }
        catch (Throwable e) {
            error = e;
        }
        finally {
            decrementSessionRefs();
        }
        log.error(errorMsg, error);
        throw new IgniteException(errorMsg, error);
    }

    /**
     * {@inheritDoc}
     *
     * @param assistant
     */
    @Override public void executeAllRows(ExecutionAssistant<Void> assistant) {
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to execute Cassandra CQL statement: " + assistant.getStatement() +
            " in operation " + assistant.operationName();
        RandomSleeper sleeper = newSleeper();
        incrementSessionRefs();
        try {
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                error = null;
                if (attempt != 0) {
                    log.warning("Trying " + (attempt + 1) + " attempt to execute Cassandra CQL statement: " +
                        assistant.getStatement());
                }
                try {
                    PreparedStatement preparedSt = prepareStatement(assistant.getStatement(),
                        assistant.getPersistenceSettings(), assistant.tableExistenceRequired(),
                        assistant.tableExistenceRequired());
                    if (preparedSt == null) {
                        return;
                    }
                    Statement statement = tuneStatementExecutionOptions(assistant.bindStatement(preparedSt));
                    ResultSet res = session().execute(statement);
                    if (res != null) {
                        for (Row row : res) {
                            assistant.process(row);
                        }
                    }
                    return;
                }
                catch (Throwable e) {
                    error = e;
                    if (CassandraHelper.isTableAbsenceError(e)) {
                        if (!assistant.tableExistenceRequired()) {
                            log.warning(errorMsg, e);
                            return;
                        }
                        handleTableAbsenceError(assistant.getPersistenceSettings());
                    }
                    else if (CassandraHelper.isHostsAvailabilityError(e)) {
                        handleHostsAvailabilityError(e, attempt, errorMsg);
                    }
                    else if (CassandraHelper.isPreparedStatementClusterError(e)) {
                        handlePreparedStatementClusterError(e);
                    }
                    else {
                        // For an error which we don't know how to handle, we will not try next attempts and terminate.
                        throw new IgniteException(errorMsg, e);
                    }
                }
                sleeper.sleep();
                attempt++;
            }
        }
        catch (Throwable e) {
            error = e;
        }
        finally {
            decrementSessionRefs();
        }
        log.error(errorMsg, error);
        throw new IgniteException(errorMsg, error);
    }

    /** {@inheritDoc} */
    @Override public synchronized void close() throws IOException {
        if (decrementSessionRefs() == 0 && ses != null) {
            SessionPool.put(this, ses);
            ses = null;
        }
    }

    private void createKeyspace(KeyValuePersistenceSettings settings) {
        if (settings.getKeyspace() == null || settings.getKeyspace().trim().isEmpty()) {
            throw new IllegalArgumentException("Keyspace name should be specified");
        }
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to create Cassandra keyspace '" + settings.getKeyspace() + "'";
        RandomSleeper sleeper = newSleeper();
        String statement = settings.getKeyspaceDDLStatement();
        while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
            try {
                session().execute(statement);
                return;
            }
            catch (AlreadyExistsException ignored) {
                return;
            }
            catch (Throwable e) {
                if (!CassandraHelper.isHostsAvailabilityError(e)) {
                    throw new IgniteException(errorMsg, e);
                }
                handleHostsAvailabilityError(e, attempt, errorMsg);
                error = e;
            }
            sleeper.sleep();
            attempt++;
        }

        throw new IgniteException(errorMsg, error);
    }

    private void createTable(KeyValuePersistenceSettings settings) {
        String fullName = settings.getKeyspace() + "." + settings.getTable();
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to create Cassandra table '" + fullName + "'";
        RandomSleeper sleeper = newSleeper();
        while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
            try {
                session().execute(settings.getTableDDLStatement());
                createTableIndexes(settings);
                return;
            }
            catch (AlreadyExistsException ignored) {
                return;
            }
            catch (Throwable e) {
                if (!CassandraHelper.isHostsAvailabilityError(e) && !CassandraHelperEx.isKeyspaceAbsenceError(e)) {
                    throw new IgniteException(errorMsg, e);
                }
                if (CassandraHelperEx.isKeyspaceAbsenceError(e)) {
                    log.warning("Failed to create Cassandra table '" + fullName +
                        "' cause appropriate keyspace doesn't exist. Keyspace will be created automatically.", e);
                    createKeyspace(settings);
                }
                else if (CassandraHelper.isHostsAvailabilityError(e)) {
                    handleHostsAvailabilityError(e, attempt, errorMsg);
                }
                error = e;
            }
            sleeper.sleep();
            attempt++;
        }
        throw new IgniteException(errorMsg, error);
    }

    private void dropTable(String keyspace, String table) {
        String fullName = keyspace + "." + table;
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to drop Cassandra table '" + fullName + "'";
        RandomSleeper sleeper = newSleeper();
        String statement = "drop table if exists " + fullName;
        while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
            try {
                session().execute(statement);
                return;
            }
            catch (Throwable e) {
                if (CassandraHelper.isHostsAvailabilityError(e)) {
                    handleHostsAvailabilityError(e, attempt, errorMsg);
                }
                else {
                    throw new IgniteException(errorMsg, e);
                }
                error = e;
            }
            sleeper.sleep();
            attempt++;
        }
        throw new IgniteException(errorMsg, error);
    }

    @Override public void truncate(String keyspace, String table) {
        String fullName = keyspace + "." + table;
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to drop Cassandra table '" + fullName + "'";
        RandomSleeper sleeper = newSleeper();
        String statement = "truncate " + fullName;
        while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
            try {
                session().execute(statement);
                return;
            }
            catch (Throwable e) {
                if (CassandraHelper.isHostsAvailabilityError(e)) {
                    handleHostsAvailabilityError(e, attempt, errorMsg);
                }
                else {
                    throw new IgniteException(errorMsg, e);
                }
                error = e;
            }
            sleeper.sleep();
            attempt++;
        }
        throw new IgniteException(errorMsg, error);
    }

    /**
     * Recreates Cassandra driver session.
     */
    private synchronized void refresh() {
        //make sure that session removed from the pool
        SessionPool.get(this);
        //closing and reopening session
        CassandraHelper.closeSession(ses);
        ses = null;
        session();
        synchronized (sesStatements) {
            sesStatements.clear();
        }
    }

    /**
     * @return Cassandra driver session.
     */
    private synchronized Session session() {
        if (ses != null) {
            return ses;
        }
        ses = SessionPool.get(this);
        if (ses != null) {
            return ses;
        }
        synchronized (sesStatements) {
            sesStatements.clear();
        }
        try {
            return ses = builder.build().connect();
        }
        catch (Throwable e) {
            throw new IgniteException("Failed to establish session with Cassandra database", e);
        }
    }

    /**
     * Increments number of references to Cassandra driver session (required for multithreaded environment).
     */
    private synchronized void incrementSessionRefs() {
        refCnt++;
    }

    /**
     * Decrements number of references to Cassandra driver session (required for multithreaded environment).
     */
    private synchronized int decrementSessionRefs() {
        if (refCnt != 0) {
            refCnt--;
        }
        return refCnt;
    }

    /**
     * Prepares CQL statement using current Cassandra driver session.
     *
     * @param statement CQL statement.
     * @param settings Persistence settings.
     * @param tblExistenceRequired Flag indicating if table existence is required for the statement.
     * @return Prepared statement.
     */
    private PreparedStatement prepareStatement(String statement, KeyValuePersistenceSettings settings,
        boolean tblExistenceRequired, boolean createMissingTable) {
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to prepare Cassandra CQL statement: " + statement;
        RandomSleeper sleeper = newSleeper();
        incrementSessionRefs();
        try {
            synchronized (sesStatements) {
                if (sesStatements.containsKey(statement)) {
                    return sesStatements.get(statement);
                }
            }
            while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                try {
                    PreparedStatement prepStatement = session().prepare(statement);
                    synchronized (sesStatements) {
                        sesStatements.put(statement, prepStatement);
                    }
                    return prepStatement;
                }
                catch (Throwable e) {
                    if (CassandraHelper.isTableAbsenceError(e)) {
                        if (!tblExistenceRequired) {
                            return null;
                        }
                        if (createMissingTable) {
                            handleTableAbsenceError(settings);
                        }
                        else {
                            throw new IgniteException(errorMsg, e);
                        }
                    }
                    else if (CassandraHelper.isHostsAvailabilityError(e)) {
                        handleHostsAvailabilityError(e, attempt, errorMsg);
                    }
                    else {
                        throw new IgniteException(errorMsg, e);
                    }
                    error = e;
                }
                sleeper.sleep();
                attempt++;
            }
        }
        finally {
            decrementSessionRefs();
        }
        throw new IgniteException(errorMsg, error);
    }

    /**
     * Creates Cassandra table indexes.
     *
     * @param settings Persistence settings.
     */
    private void createTableIndexes(KeyValuePersistenceSettings settings) {
        String fullName = settings.getKeyspace() + "." + settings.getTable();
        List<String> statements = settings.getIndexDDLStatements();
        if (statements == null || statements.isEmpty()) {
            return;
        }
        int attempt = 0;
        Throwable error = null;
        String errorMsg = "Failed to create indexes for Cassandra table '" + fullName + "'";
        while (attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
            try {
                for (String statement : statements) {
                    try {
                        session().execute(statement);
                    }
                    catch (AlreadyExistsException ignored) {
                    }
                    catch (Throwable e) {
                        if (!(e instanceof InvalidQueryException) || !e.getMessage().equals("Index already exists")) {
                            throw new IgniteException(errorMsg, e);
                        }
                    }
                }
                return;
            }
            catch (Throwable e) {
                if (CassandraHelper.isHostsAvailabilityError(e)) {
                    handleHostsAvailabilityError(e, attempt, errorMsg);
                }
                else {
                    throw new IgniteException(errorMsg, e);
                }
                error = e;
            }
            attempt++;
        }
        throw new IgniteException(errorMsg, error);
    }

    /**
     * Tunes CQL statement execution options (consistency level, fetch option and etc.).
     *
     * @param statement Statement.
     * @return Modified statement.
     */
    private Statement tuneStatementExecutionOptions(Statement statement) {
        String qry = "";
        if (statement instanceof BoundStatement) {
            qry = ((BoundStatement)statement).preparedStatement().getQueryString().trim().toLowerCase();
        }
        else if (statement instanceof PreparedStatement) {
            qry = ((PreparedStatement)statement).getQueryString().trim().toLowerCase();
        }
        boolean readStatement = qry.startsWith("select");
        boolean writeStatement = statement instanceof Batch || statement instanceof BatchStatement ||
            qry.startsWith("insert") || qry.startsWith("delete") || qry.startsWith("update");
        if (readStatement && readConsistency != null) {
            statement.setConsistencyLevel(readConsistency);
        }
        if (writeStatement && writeConsistency != null) {
            statement.setConsistencyLevel(writeConsistency);
        }
        if (fetchSize != null) {
            statement.setFetchSize(fetchSize);
        }
        return statement;
    }

    /**
     * Handles situation when Cassandra table doesn't exist.
     *
     * @param settings Persistence settings.
     */
    private void handleTableAbsenceError(KeyValuePersistenceSettings settings) {
        int hndNum = tblAbsenceHandlersCnt.incrementAndGet();
        try {
            synchronized (tblAbsenceHandlersCnt) {
                // Oooops... I am not the first thread who tried to handle table absence problem.
                if (hndNum != 0) {
                    log.warning("Table " + settings.getTableFullName() + " absence problem detected. " +
                        "Another thread already fixed it.");
                    return;
                }
                log.warning("Table " + settings.getTableFullName() + " absence problem detected. " +
                    "Trying to create table.");
                IgniteException error = new IgniteException("Failed to create Cassandra table " +
                    settings.getTableFullName());
                int attempt = 0;
                while (error != null && attempt < CQL_EXECUTION_ATTEMPTS_COUNT) {
                    error = null;
                    try {
                        createTable(settings);
                    }
                    catch (Throwable e) {
                        if (CassandraHelper.isHostsAvailabilityError(e)) {
                            handleHostsAvailabilityError(e, attempt, null);
                        }
                        else {
                            throw new IgniteException("Failed to create Cassandra table " + settings.getTableFullName(),
                                e);
                        }
                        error = (e instanceof IgniteException) ? (IgniteException)e : new IgniteException(e);
                    }
                    attempt++;
                }
                if (error != null) {
                    throw error;
                }
            }
        }
        finally {
            if (hndNum == 0) {
                tblAbsenceHandlersCnt.set(-1);
            }
        }
    }

    /**
     * Handles situation when prepared statement execution failed cause session to the cluster was released.
     */
    private void handlePreparedStatementClusterError(Throwable e) {
        int hndNum = prepStatementHandlersCnt.incrementAndGet();
        try {
            synchronized (prepStatementHandlersCnt) {
                // Oooops... I am not the first thread who tried to handle prepared statement problem.
                if (hndNum != 0) {
                    log.warning("Prepared statement cluster error detected, another thread already fixed the problem",
                        e);
                    return;
                }
                log.warning("Prepared statement cluster error detected, refreshing Cassandra session", e);
                refresh();
                log.warning("Cassandra session refreshed");
            }
        }
        finally {
            if (hndNum == 0) {
                prepStatementHandlersCnt.set(-1);
            }
        }
    }

    /**
     * Handles situation when Cassandra host which is responsible for CQL query execution became unavailable.
     *
     * @param e Exception to handle.
     * @param attempt Number of attempts.
     * @param msg Error message.
     * @return {@code true} if host unavailability was successfully handled.
     */
    private boolean handleHostsAvailabilityError(Throwable e, int attempt, String msg) {
        if (attempt >= CQL_EXECUTION_ATTEMPTS_COUNT) {
            log.error("Host availability problem detected. " +
                "Number of CQL execution attempts reached maximum " + CQL_EXECUTION_ATTEMPTS_COUNT +
                ", exception will be thrown to upper execution layer.", e);
            throw msg == null ? new IgniteException(e) : new IgniteException(msg, e);
        }
        if (attempt == CQL_EXECUTION_ATTEMPTS_COUNT / 4 ||
            attempt == CQL_EXECUTION_ATTEMPTS_COUNT / 2 ||
            attempt == CQL_EXECUTION_ATTEMPTS_COUNT / 2 + CQL_EXECUTION_ATTEMPTS_COUNT / 4 ||
            attempt == CQL_EXECUTION_ATTEMPTS_COUNT - 1) {
            log.warning("Host availability problem detected, CQL execution attempt  " + (attempt + 1) + ", " +
                "refreshing Cassandra session", e);
            refresh();
            log.warning("Cassandra session refreshed");
            return true;
        }
        log.warning("Host availability problem detected, CQL execution attempt " + (attempt + 1) + ", " +
            "sleeping extra " + CQL_EXECUTION_ATTEMPT_MAX_TIMEOUT + " milliseconds", e);
        try {
            Thread.sleep(CQL_EXECUTION_ATTEMPT_MAX_TIMEOUT);
        }
        catch (InterruptedException ignored) {
        }
        log.warning("Sleep completed");
        return false;
    }

    /**
     * @return New random sleeper.
     */
    private RandomSleeper newSleeper() {
        return new RandomSleeper(CQL_EXECUTION_ATTEMPT_MIN_TIMEOUT,
            CQL_EXECUTION_ATTEMPT_MAX_TIMEOUT,
            CQL_ATTEMPTS_TIMEOUT_INCREMENT, log);
    }
}

