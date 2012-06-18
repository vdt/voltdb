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

package org.voltdb.iv2;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.zookeeper_voltpatches.KeeperException;
import org.apache.zookeeper_voltpatches.ZooKeeper;

import org.json_voltpatches.JSONException;
import org.json_voltpatches.JSONObject;

import org.voltcore.logging.VoltLogger;

import org.voltcore.utils.CoreUtils;

import org.voltcore.zk.LeaderElector;
import org.voltcore.zk.MapCache;
import org.voltcore.zk.MapCacheReader;

import org.voltdb.StatsSource;
import org.voltdb.VoltDB;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.VoltZK;

import com.google.common.collect.UnmodifiableIterator;

/**
 * Cartographer provides answers to queries about the components in a cluster.
 * It provides the StatsSource interface for the TOPO statistics selector, but
 * can be called directly as long as the caller is careful about not calling
 * from a network thread (need to avoid ZK deadlocks).
 */
public class Cartographer extends StatsSource
{
    private static final VoltLogger hostLog = new VoltLogger("HOST");
    private final MapCacheReader m_iv2Masters;
    private final ZooKeeper m_zk;

    /**
     * A dummy iterator that wraps an UnmodifiableIterator<String> and provides the
     * Iterator<Object>
     */
    private class DummyIterator implements Iterator<Object> {
        private final UnmodifiableIterator<String> i;

        private DummyIterator(UnmodifiableIterator<String> i) {
            this.i = i;
        }

        @Override
        public boolean hasNext() {
            return i.hasNext();
        }

        @Override
        public Object next() {
            return i.next();
        }

        @Override
        public void remove() {
            i.remove();
        }
    }

    public Cartographer(ZooKeeper zk)
    {
        super(false);
        m_zk = zk;
        m_iv2Masters = new MapCache(m_zk, VoltZK.iv2masters);
        try {
            m_iv2Masters.start(true);
        } catch (Exception e) {
            VoltDB.crashLocalVoltDB("Screwed", true, e);
        }
    }

    @Override
    protected void populateColumnSchema(ArrayList<ColumnInfo> columns)
    {
        columns.add(new ColumnInfo("Partition", VoltType.STRING));
        columns.add(new ColumnInfo("Sites", VoltType.STRING));
        columns.add(new ColumnInfo("Leader", VoltType.STRING));

    }

    @Override
    protected Iterator<Object> getStatsRowKeyIterator(boolean interval)
    {
        return new DummyIterator(m_iv2Masters.pointInTimeCache().keySet().iterator());
    }

    @Override
    protected void updateStatsRow(Object rowKey, Object[] rowValues) {
        JSONObject thing = m_iv2Masters.pointInTimeCache().get((String)rowKey);
        long leader = Long.MIN_VALUE;
        try {
            leader = Long.valueOf(thing.getLong("hsid"));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        List<Long> sites = getReplicasForIv2Master((String)rowKey);

        rowValues[columnNameToIndex.get("Partition")] = rowKey;
        rowValues[columnNameToIndex.get("Sites")] = CoreUtils.hsIdCollectionToString(sites);
        rowValues[columnNameToIndex.get("Leader")] = CoreUtils.hsIdToString(leader);
    }

    /**
     * Take a ZK path of a child in the VoltZK.iv2masters and return
     * all of the HSIDs of the sites replicating that partition
     */
    public List<Long> getReplicasForIv2Master(String zkPath) {
        List<Long> retval = null;
        if (!zkPath.startsWith(VoltZK.iv2masters)) {
            hostLog.error("Invalid ZK path given to getReplicasForIv2Master: " + zkPath +
                    ".  It must be a child of " + VoltZK.iv2masters);
        }
        else {
            int partId = Integer.valueOf(zkPath.split("/")[zkPath.split("/").length - 1]);
            retval = getReplicasForPartition(partId);
        }
        return retval;
    }

    /**
     * Given a partition ID, return a list of HSIDs of all the sites with copies of that partition
     */
    public List<Long> getReplicasForPartition(int partition) {
        String zkpath = LeaderElector.electionDirForPartition(partition);
        List<Long> retval = new ArrayList<Long>();
        try {
            List<String> children = m_zk.getChildren(zkpath, null);
            for (String child : children) {
                retval.add(Long.valueOf(child.split("_")[0]));
            }
        }
        catch (KeeperException ke) {
            org.voltdb.VoltDB.crashLocalVoltDB("KeeperException getting replicas for partition: " + partition,
                    true, ke);
        }
        catch (InterruptedException ie) {
            org.voltdb.VoltDB.crashLocalVoltDB("InterruptedException getting replicas for partition: " +
                    partition, true, ie);
        }
        return retval;
    }

    /**
     * Convenience method to return the immediate count of replicas for the given partition
     */
    public int getReplicaCountForPartition(int partition) {
        return getReplicasForPartition(partition).size();
    }

    public void shutdown() throws InterruptedException
    {
        m_iv2Masters.shutdown();
    }
}