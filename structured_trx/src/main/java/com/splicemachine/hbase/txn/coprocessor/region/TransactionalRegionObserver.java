package com.splicemachine.hbase.txn.coprocessor.region;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import com.splicemachine.hbase.txn.TxnConstants;
import com.splicemachine.constants.SpliceConstants.TableEnv;
import com.splicemachine.hbase.txn.TxnConstants.TransactionIsolationLevel;
import com.splicemachine.constants.environment.EnvUtils;
import com.splicemachine.hbase.txn.logger.LogConstants;
import com.splicemachine.hbase.txn.logger.LogConstants.LogRecordType;
import com.splicemachine.hbase.txn.logger.TxnLogger;
import com.splicemachine.utils.SpliceLogUtils;

public class TransactionalRegionObserver extends BaseTxnRegionObserver {
	private static Logger LOG = Logger.getLogger(TransactionalRegionObserver.class);

	private ConcurrentHashMap<String, TransactionState> transactionStateByID = new ConcurrentHashMap<String, TransactionState>();
//	private HTablePool tablePool;
	private boolean tableEnvMatch = false;
	private boolean isDerbySysTable = false;

	@Override
	public void start(CoprocessorEnvironment e) throws IOException {	
		SpliceLogUtils.debug(LOG,"Starting TransactionalRegionObserver CoProcessor " + TransactionalRegionObserver.class + " in table " + tableName
				+ ", TxnUtils.getTableEnv((RegionCoprocessorEnvironment) e)="+TxnUtils.getTableEnv((RegionCoprocessorEnvironment) e));
		isDerbySysTable = TxnUtils.getTableEnv((RegionCoprocessorEnvironment) e).equals(TableEnv.DERBY_SYS_TABLE);
		tableEnvMatch = TxnUtils.getTableEnv((RegionCoprocessorEnvironment) e).equals(TableEnv.USER_TABLE) 
				|| TxnUtils.getTableEnv((RegionCoprocessorEnvironment) e).equals(TableEnv.USER_INDEX_TABLE)
				|| TxnUtils.getTableEnv((RegionCoprocessorEnvironment) e).equals(TableEnv.DERBY_SYS_TABLE);
		SpliceLogUtils.debug(LOG,"inside TransactionalRegionObserver Start CoProcessor, tableEnvMatch=" + tableEnvMatch);
		if(!tableEnvMatch) {
			super.start(e);
			return;
		}
		initZooKeeper(e);
		tablePool = new HTablePool(e.getConfiguration(), 1);
		HTableInterface txnLogTable = tablePool.getTable(TxnConstants.TRANSACTION_LOG_TABLE_BYTES);
		String splitRegionPath = null;
		if (TxnLogger.logNodeExist(txnLogPath, rzk)) {
			int count = TxnLogger.getTxnLogChildNum(txnLogPath, rzk);
			if (count > 0) {
				SpliceLogUtils.info(LOG,"Recover region, reading txn Log; region id: " + regionId);
				TxnLogger.scheduleReadingTxnLog(txnLogTable, ((RegionCoprocessorEnvironment) e).getRegionServerServices(), regionLogPath, 
						txnLogPath, tableName, regionId, region, rzk, lockManager, transactionStateByID, tablePool);
			} else if (count == 0) {
				SpliceLogUtils.info(LOG,"Recover region, no txn Log; region id: " + regionId);
			} else {
				throw new RuntimeException("TxnLogNode " + txnLogPath + " return count " + count);
			}
		} else {
			if ((splitRegionPath = TxnLogger.isSplitGenerated(splitLogPath, region.getStartKey(), region.getEndKey(), rzk)) != null) {
				SpliceLogUtils.info(LOG,"Reading split Log for region " + regionId);
				TxnLogger.readTransactionStatesLog(txnLogTable, LogRecordType.SPLIT_LOG, tableName, null, region.getStartKey(), region.getEndKey(), 
						region, rzk, lockManager, transactionStateByID, tablePool, txnLogPath, false);
				TxnLogger.removeLogNode(splitRegionPath, rzk);
			}
			//This is a completely new region that was/wasn't generated from split, need to create txn log node
			try {
				ZKUtil.createWithParents(zkw, txnLogPath);
			} catch (KeeperException e1) {
				e1.printStackTrace();
			}
		}
		txnLogTable.close();
	}

	@Override
	public void preSplit(ObserverContext<RegionCoprocessorEnvironment> e) {
		SpliceLogUtils.debug(LOG,"preSplit in CoProcessor " + TransactionalRegionObserver.class + " in table " + tableName);
		byte[] splitPoint = e.getEnvironment().getRegion().checkSplit();
		SpliceLogUtils.debug(LOG,"Split point " + Bytes.toString(splitPoint) + " for table " + region.getTableDesc().getNameAsString() 
				+ " key range: [" + Bytes.toString(region.getStartKey()) + ", " + Bytes.toString(region.getEndKey()) + "], region id " 
				+ regionId);
		
		try {
			TxnUtils.splitAndLogTransactionStateMap(region.getStartKey(), splitPoint, region.getEndKey(), transactionStateByID, tablePool);
			TxnLogger.createLogNode(splitLogPath + LogConstants.SPLIT_LOG_REGION_POSTFIX, rzk, TxnLogger.composeSplitLogNodeData(region.getStartKey(), splitPoint), CreateMode.PERSISTENT_SEQUENTIAL);
			TxnLogger.createLogNode(splitLogPath + LogConstants.SPLIT_LOG_REGION_POSTFIX, rzk, TxnLogger.composeSplitLogNodeData(splitPoint, region.getEndKey()), CreateMode.PERSISTENT_SEQUENTIAL);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		super.preSplit(e);
	}

	@Override
	public void postSplit(ObserverContext<RegionCoprocessorEnvironment> e, HRegion l, HRegion r) { 
		//Note: the split generate two regions whose ranges are [, split) and [split, ], the split point would be located in the right daughter.
		SpliceLogUtils.debug(LOG, "postSplit in CoProcessor " + TransactionalRegionObserver.class);
		SpliceLogUtils.info(LOG,"Left half region id " + EnvUtils.getRegionId(l) + " key range: [" + Bytes.toString(l.getStartKey()) + ", " + Bytes.toString(l.getEndKey()) + "]");
		SpliceLogUtils.info(LOG,"Right half region id " + EnvUtils.getRegionId(r) + " key range: [" + Bytes.toString(r.getStartKey()) + ", " + Bytes.toString(r.getEndKey()) + "]");
		SpliceLogUtils.info(LOG,"Old region id " + regionId);
		
		//Do nothing, only for info
		super.postSplit(e, l, r);
	}
	
	@Override
	public void preGet(ObserverContext<RegionCoprocessorEnvironment> e,Get get, List<KeyValue> results) throws IOException {
		SpliceLogUtils.debug(LOG,"preGet in CoProcessor " + TransactionalRegionObserver.class + " in table " + tableName+",tableEnvMatch="+tableEnvMatch);
		
		if(!tableEnvMatch) {
			return;
		}
		if (TxnUtils.isTransactional(get)) {
			//if (isDerbySysTable) { //FIXME: check table/region lock type and isolation level. if there is an exclusive table lock and isolation level is read_commit etc
			//	return;
			//}
			SpliceLogUtils.debug(LOG,"preGet, transID="+TxnUtils.getTransactionID(get));
			TxnUtils.editGetResult(get, region, results, rzk, lockManager, transactionStateByID, tablePool, e, txnLogPath);
		} else {
			SpliceLogUtils.debug(LOG,"preGet in CoProcessor is not Transactional");
		}
	}
	
	@Override
	public void postGet(ObserverContext<RegionCoprocessorEnvironment> e, Get get, List<KeyValue> results) throws IOException {
		SpliceLogUtils.debug(LOG,"postGet in CoProcessor " + TransactionalRegionObserver.class + " in table " + tableName);
		if(!tableEnvMatch) {
			return;
		}
		String transactionID = TxnUtils.getTransactionID(get);
		SpliceLogUtils.debug(LOG,"postGet, transID="+transactionID);
		if (transactionID != null && TxnUtils.getIsolationLevel(get).equals(TransactionIsolationLevel.READ_COMMITTED)) {
			lockManager.releaseSharedReadLock(get.getRow());
		}
		super.postGet(e, get, results);
	}

	@Override
	public RegionScanner preScannerOpen(ObserverContext<RegionCoprocessorEnvironment> e, Scan scan, RegionScanner s) throws IOException {
		SpliceLogUtils.debug(LOG,"preScannerOpen in CoProcessor " + TransactionalRegionObserver.class + " in table " + tableName+",tableEnvMatch="+tableEnvMatch);
		if(!tableEnvMatch) {
			return s;
		}
		if (TxnUtils.isTransactional(scan)) {
			SpliceLogUtils.debug(LOG,"preScannerOpen in CoProcessor:  isTransactional,transID="+TxnUtils.getTransactionID(scan));
			return TxnUtils.generateScanner(scan, region, rzk, lockManager, transactionStateByID, tablePool, e, txnLogPath);
		} else {
			SpliceLogUtils.debug(LOG,"preScannerOpen in CoProcessor is not Transactional");
		}
		return s;
	}

	@Override
	public void prePut(ObserverContext<RegionCoprocessorEnvironment> e,Put put, WALEdit edit, boolean writeToWAL) throws IOException {
		SpliceLogUtils.debug(LOG,"prePut in CoProcessor " + TransactionalRegionObserver.class + " in table " + tableName+", tableEnvMatch="+tableEnvMatch);
		
		//FIXME: temp solution to let DDL statement go through
		if (isDerbySysTable) {
			//FIXME: check whether table is locked by other transaction. If it is, give a timeout retry
			return;
		}
		
		if(!tableEnvMatch) {
			return;
		}
		
		SpliceLogUtils.debug(LOG,"after flag check, prePut in CoProcessor " + TransactionalRegionObserver.class + " in table " + tableName);
		
		if (TxnUtils.isTransactional(put)) { // Removed extra Zookeeper call		
			String transactionID = TxnUtils.getTransactionID(put);
			SpliceLogUtils.debug(LOG,"prePut in CoProcessor transactionId="+transactionID);
			if (transactionID != null) {
				//FIXME: what if the lock did not get acquired??? Need to check the status before proceed
				lockManager.acquireExclusiveWriteLock(put.getRow(), transactionID);
				if (transactionStateByID.containsKey(transactionID)) {
					SpliceLogUtils.debug(LOG,"Transaction state exists in region " + regionId);
					transactionStateByID.get(transactionID).addWrite(put);
				} else {
					SpliceLogUtils.debug(LOG,"Create new transaction state in region " + regionId);
					TransactionState state = new TransactionState(transactionID, region, rzk, lockManager, transactionStateByID, 
							tablePool, txnLogPath, false);
					state.addWrite(put);
					transactionStateByID.put(transactionID, state);
				}
				e.bypass();
			} 
		} else {
			SpliceLogUtils.debug(LOG,"prePut in CoProcessor is not Transactional");
		}
	}

	@Override
	public void preDelete(ObserverContext<RegionCoprocessorEnvironment> e,Delete delete, WALEdit edit, boolean writeToWAL) throws IOException {
		SpliceLogUtils.debug(LOG,"preDelete in CoProcessor " + TransactionalRegionObserver.class + " in table " + tableName+",tableEnvMatch="+tableEnvMatch);
		
		//FIXME: temp solution to let DDL statement go through
		if (isDerbySysTable) {
			//FIXME: check whether table is locked by other transaction. If it is, give a timeout retry
			return;
		}
		
		if(!tableEnvMatch) {
			return;
		}
		if (TxnUtils.isTransactional(delete)) {
			String transactionID = TxnUtils.getTransactionID(delete);
			SpliceLogUtils.debug(LOG,"preDelete in CoProcessor transactionId="+transactionID);
			if (transactionID != null) {
				lockManager.acquireExclusiveWriteLock(delete.getRow(), transactionID);
				if (transactionStateByID.containsKey(transactionID)) {
					transactionStateByID.get(transactionID).addDelete(delete);
				} else {
					TransactionState state = new TransactionState(transactionID, region, rzk, lockManager, transactionStateByID, 
							tablePool, txnLogPath, false);
					state.addDelete(delete);
					transactionStateByID.put(transactionID, state);
				}
				e.bypass();
			}
		} else {
			SpliceLogUtils.debug(LOG,"preDelete in CoProcessor is not Transactional");
		}
	}
	
	@Override
	public void preClose(ObserverContext<RegionCoprocessorEnvironment> e,
			boolean abortRequested) {
		SpliceLogUtils.debug(LOG,"preClose in CoProcessor tableEnvMatch="+tableEnvMatch);
		if (tableEnvMatch)
			TxnLogger.removeLogNode(txnLogPath, rzk);
		super.preClose(e, abortRequested);
	}
}
