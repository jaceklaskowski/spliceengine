package com.splicemachine.mrio.api.core;

import java.io.IOException;
import java.sql.SQLException;

import com.splicemachine.access.HConfiguration;
import com.splicemachine.access.api.PartitionFactory;
import com.splicemachine.access.hbase.HBaseTableInfoFactory;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.db.impl.sql.compile.TableName;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.storage.ClientPartition;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.StringUtils;
import org.apache.log4j.Logger;
import com.splicemachine.mrio.MRConstants;
import com.splicemachine.utils.SpliceLogUtils;

/**
 *
 * Input Format that requires the following items passed to it.
 *
 *
 *
 */
public class SMInputFormat extends AbstractSMInputFormat<RowLocation, ExecRow> {
    protected static final Logger LOG = Logger.getLogger(SMInputFormat.class);
    protected Table table;
    protected Scan scan;
    protected SMSQLUtil util;
    protected SMRecordReaderImpl rr;
    protected boolean spark;

    @Override
    public void setConf(Configuration conf) {
        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG, "setConf conf=%s",conf);
        this.conf = conf;
        String tableName = conf.get(MRConstants.SPLICE_INPUT_TABLE_NAME);
        String conglomerate = conf.get(MRConstants.SPLICE_INPUT_CONGLOMERATE);
        String tableScannerAsString = conf.get(MRConstants.SPLICE_SCAN_INFO);
        spark = tableScannerAsString!=null;
        conf.setBoolean("splice.spark", spark);
        String jdbcString = conf.get(MRConstants.SPLICE_JDBC_STR);
        String rootDir = conf.get(HConstants.HBASE_DIR);
        if (!spark && util==null)
            util = SMSQLUtil.getInstance(jdbcString);
        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG, "setConf tableName=%s, conglomerate=%s, tableScannerAsString=%s"
                    + "jdbcString=%s, rootDir=%s",tableName,conglomerate,tableScannerAsString,jdbcString, rootDir);
        if (conglomerate ==null && !spark) {
            LOG.error("Conglomerate not provided when spark is activated");
            throw new RuntimeException("Conglomerate not provided when spark is activated");
        }
        if (tableName == null && conglomerate == null) {
            LOG.error("Table Name Supplied is null");
            throw new RuntimeException("Table Name Supplied is Null");
        }
        if (conglomerate == null) {
            if (jdbcString == null) {
                LOG.error("JDBC String Not Supplied");
                throw new RuntimeException("JDBC String Not Supplied");
            }
            try {
                conglomerate = util.getConglomID(tableName);
                conf.set(MRConstants.SPLICE_INPUT_CONGLOMERATE, conglomerate);
            } catch (SQLException e) {
                LOG.error(StringUtils.stringifyException(e));
                throw new RuntimeException(e);
            }
        }
        try {
            PartitionFactory tableFactory=SIDriver.driver().getTableFactory();
            setHTable(((ClientPartition)tableFactory.getTable(conglomerate)).unwrapDelegate());
        } catch (Exception e) {
            LOG.error(StringUtils.stringifyException(e));
        }
        if (tableScannerAsString == null) {
            if (jdbcString == null) {
                LOG.error("JDBC String Not Supplied");
                throw new RuntimeException("JDBC String Not Supplied");
            }
            try {
                conf.set(MRConstants.SPLICE_SCAN_INFO, util.getTableScannerBuilder(tableName, null).base64Encode());
            } catch (Exception e) {
                LOG.error(StringUtils.stringifyException(e));
                throw new RuntimeException(e);
            }
        }
        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG, "finishingSetConf");
    }

    public SMRecordReaderImpl getRecordReader(InputSplit split, Configuration config) throws IOException,
            InterruptedException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "getRecorderReader with table=%s, conglomerate=%s",table,config.get(TableInputFormat.INPUT_TABLE));
        rr = new SMRecordReaderImpl(config);
        if(table == null){
            org.apache.hadoop.hbase.TableName tableInfo=HBaseTableInfoFactory
                    .getInstance(HConfiguration.INSTANCE)
                    .getTableInfo(config.get(TableInputFormat.INPUT_TABLE));
            table=new HTable(HBaseConfiguration.create(config),tableInfo);
        }
        rr.setHTable(table);
        //if (!conf.getBoolean("splice.spark", false))
        rr.init(config, split);
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "returning record reader");
        return rr;
    }

    @Override
    public RecordReader<RowLocation, ExecRow> createRecordReader(
            InputSplit split, TaskAttemptContext context) throws IOException,
            InterruptedException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "createRecordReader for split=%s, context %s",split,context);
        if (rr != null)
            return rr;
        return getRecordReader(split,context.getConfiguration());
    }

}