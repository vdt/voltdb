/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.sysprocs;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.voltdb.DependencyPair;
import org.voltdb.SystemProcedureExecutionContext;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.ProcedureRunner;
import org.voltdb.SQLStmt;
import org.voltdb.TheHashinator;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.Table;
import org.voltdb.dtxn.DtxnConstants;
import org.voltdb.dtxn.SiteTracker;

/**
 * Given as input a VoltTable with a schema corresponding to a persistent table,
 * partition the rows and insert into the appropriate persistent table
 * partitions(s). This system procedure does not generate undo data. Any
 * intermediate failure, for example a constraint violation, will leave partial
 * and inconsistent data in the persistent store.
 */
@ProcInfo(singlePartition = false)
public class LoadMultipartitionTable extends VoltSystemProcedure
{

    static final int DEP_distribute = (int) SysProcFragmentId.PF_distribute |
                                      DtxnConstants.MULTIPARTITION_DEPENDENCY;

    static final int DEP_aggregate = (int) SysProcFragmentId.PF_aggregate;

    @Override
    public void init() {
        registerPlanFragment(SysProcFragmentId.PF_distribute);
        registerPlanFragment(SysProcFragmentId.PF_aggregate);
    }

    @Override
    public DependencyPair executePlanFragment(
            Map<Integer, List<VoltTable>> dependencies, long fragmentId,
            ParameterSet params, SystemProcedureExecutionContext context) {
        // Return a single long for modified rowcount
        VoltTable result = new VoltTable(new VoltTable.ColumnInfo("", VoltType.BIGINT));

        if (fragmentId == SysProcFragmentId.PF_distribute) {
            assert context.getCluster().getTypeName() != null;
            assert context.getDatabase().getTypeName() != null;
            assert params != null;
            assert params.toArray() != null;
            assert params.toArray()[0] != null;
            assert params.toArray()[1] != null;

            String tableName = (String) params.toArray()[0];
            VoltTable toInsert = (VoltTable) params.toArray()[1];

            // add the partition id
            long currentPartition = context.getPartitionId();
            result.addRow(currentPartition);

            try {
                // voltLoadTable is void. Assume success or exception.
                voltLoadTable(context.getCluster().getTypeName(),
                                    context.getDatabase().getTypeName(),
                                    tableName,
                                    toInsert);
                // return the number of rows inserted
                result.addRow(toInsert.getRowCount());
            }
            catch (VoltAbortException e) {
                // must continue and reply with dependency.
                e.printStackTrace();
                // report -1 rows inserted, though this might be false
                result.addRow(-1);
            }
            return new DependencyPair(DEP_distribute, result);

        } else if (fragmentId == SysProcFragmentId.PF_aggregate) {
            long[] modifiedTuples = new long[context.getNumberOfPartitions()];
            List<VoltTable> deps = dependencies.get(DEP_distribute);
            assert(deps.size() > 0);

            // go through all the deps and find one mod tuple count per partition
            for (VoltTable t : deps) {
                t.advanceRow();
                int partitionId = (int) t.getLong(0);
                t.advanceRow();
                long rowsModified = t.getLong(0);

                if (modifiedTuples[partitionId] == 0) {
                    modifiedTuples[partitionId] = rowsModified;
                }
                else {
                    if (modifiedTuples[partitionId] != rowsModified)
                        throw new RuntimeException(
                                "@LoadMultipartitionTable received different tuple mod counts from two replicas.");
                }
            }

            // sum up all the modified rows from all partitions
            long rowsModified = 0;
            for (long l : modifiedTuples)
                rowsModified += l;

            result.addRow(rowsModified);
            return new DependencyPair(DEP_aggregate, result);
        }

        // must handle every dependency id.
        assert (false);
        return null;
    }

    /**
     * These parameters, with the exception of ctx, map to user provided values.
     *
     * @param ctx
     *            Internal. Not a user-supplied parameter.
     * @param tableName
     *            Name of persistent table receiving data.
     * @param table
     *            A VoltTable with schema matching tableName containing data to
     *            load.
     * @return {@link org.voltdb.VoltSystemProcedure#STATUS_SCHEMA}
     * @throws VoltAbortException
     */
    public long run(SystemProcedureExecutionContext ctx,
            String tableName, VoltTable table)
            throws VoltAbortException {
        VoltTable[] results;
        SynthesizedPlanFragment pfs[];

        // if tableName is replicated, just send table everywhere.
        // otherwise, create a VoltTable for each partition and
        // split up the incoming table .. then send those partial
        // tables to the appropriate sites.

        Table catTable = ctx.getDatabase().getTables().getIgnoreCase(tableName);
        if (catTable == null) {
            throw new VoltAbortException("Table not present in catalog.");
        }
        // fix any case problems
        tableName = catTable.getTypeName();

        // check that the schema of the input matches
        int columnCount = table.getColumnCount();

        // find the insert statement for this table
        String insertProcName = String.format("%s.insert", tableName);
        Procedure proc = ctx.getDatabase().getProcedures().get(insertProcName);
        if (proc == null) {
            throw new VoltAbortException(
                    String.format("Unable to locate auto-generated CRUD insert statement for table %s",
                            tableName));
        }

        // statements of all single-statement procs are named "sql"
        Statement catStmt = proc.getStatements().get("sql");
        if (catStmt == null) {
            throw new VoltAbortException(
                    String.format("Unable to find SQL statement for found table %s: BAD",
                            tableName));
        }

        // create a SQLStmt instance on the fly (unusual to do)
        SQLStmt stmt = new SQLStmt(catStmt.getSqltext());
        ProcedureRunner.initSQLStmt(stmt, catStmt);

        if (catTable.getIsreplicated()) {
            long queued = 0;
            long executed = 0;

            // make sure at the start of the table
            table.resetRowPosition();
            for (int i = 0; table.advanceRow(); ++i) {
                Object[] params = new Object[columnCount];

                // get the parameters from the volt table
                for (int col = 0; col < columnCount; ++col) {
                    params[col] = table.get(col, table.getColumnType(col));
                }

                // queue an insert and count it
                voltQueueSQL(stmt, params);
                ++queued;

                // every 100 statements, exec the batch
                // 100 is an arbitrary number
                if ((i % 100) == 0) {
                    executed += executeSQL();
                }
            }
            // execute any leftover batched statements
            if (queued > executed) {
                executed += executeSQL();
            }

            return executed;
        }
        else {
            // find the index and type of the partitioning attribute
            int partitionCol = catTable.getPartitioncolumn().getIndex();
            int intType = catTable.getPartitioncolumn().getType();
            VoltType partitionType = VoltType.get((byte) intType);

            // create a table for each partition
            int numPartitions = ctx.getNumberOfPartitions();
            VoltTable partitionedTables[] = new VoltTable[numPartitions];
            for (int i = 0; i < partitionedTables.length; i++) {
                partitionedTables[i] = table.clone(1024 * 1024);
            }

            // split the input table into per-partition units
            while (table.advanceRow()) {
                int p = 0;
                try {
                    p = TheHashinator.hashToPartition(
                        table.get(partitionCol, partitionType));
                }
                catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException(e.getMessage());
                }
                // this adds the active row from table
                partitionedTables[p].add(table);
            }

            SiteTracker tracker = ctx.getSiteTracker();
            Map<Long, Integer> sitesToPartitions = tracker.getSitesToPartitions();
            int num_exec_sites = sitesToPartitions.size();
            pfs = new SynthesizedPlanFragment[num_exec_sites + 1];
            int site_index = 0;
            for (Entry<Long, Integer> e : sitesToPartitions.entrySet()) {
                long site = e.getKey();
                int partition = e.getValue();

                ParameterSet params = new ParameterSet();
                params.setParameters(tableName, partitionedTables[partition]);
                pfs[site_index] = new SynthesizedPlanFragment();
                pfs[site_index].fragmentId = SysProcFragmentId.PF_distribute;
                pfs[site_index].outputDepId = DEP_distribute;
                pfs[site_index].inputDepIds = new int[] {};
                pfs[site_index].multipartition = false;
                pfs[site_index].siteId = site;
                pfs[site_index].parameters = params;
                site_index++;
            }
            // a final plan fragment to aggregate the results
            pfs[num_exec_sites] = new SynthesizedPlanFragment();
            pfs[num_exec_sites].fragmentId = SysProcFragmentId.PF_aggregate;
            pfs[num_exec_sites].inputDepIds = new int[] { DEP_distribute };
            pfs[num_exec_sites].outputDepId = DEP_aggregate;
            pfs[num_exec_sites].multipartition = false;
            pfs[num_exec_sites].parameters = new ParameterSet();

            // send these forth in to the world .. and wait
            results = executeSysProcPlanFragments(pfs, DEP_aggregate);
            return results[0].asScalarLong();
        }
    }

    /**
     * Execute a set of queued inserts. Ensure each insert successfully
     * inserts one row. Throw exception if not.
     *
     * @return Count of rows inserted.
     * @throws VoltAbortException if any failure at all.
     */
    long executeSQL() throws VoltAbortException {
        long count = 0;
        VoltTable[] results = voltExecuteSQL();
        for (VoltTable result : results) {
            long dmlUpdated = result.asScalarLong();
            if (dmlUpdated == 0) {
                throw new VoltAbortException("Insert failed for tuple.");
            }
            if (dmlUpdated > 1) {
                throw new VoltAbortException("Insert modified more than one tuple.");
            }
            ++count;
        }
        return count;
    }
}
