package com.splicemachine.derby.impl.sql.execute.operations;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import com.splicemachine.constants.bytes.BytesUtil;
import com.splicemachine.derby.hbase.SpliceDriver;
import com.splicemachine.derby.hbase.SpliceObserverInstructions;
import com.splicemachine.derby.iapi.storage.RowProviderIterator;
import com.splicemachine.derby.impl.job.operation.SuccessFilter;
import com.splicemachine.derby.utils.*;
import com.splicemachine.derby.utils.marshall.*;
import com.splicemachine.encoding.MultiFieldDecoder;
import com.splicemachine.encoding.MultiFieldEncoder;
import com.splicemachine.job.JobStats;
import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.services.io.FormatableArrayHolder;
import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.store.access.ColumnOrdering;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.impl.sql.GenericStorablePreparedStatement;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.log4j.Logger;
import com.splicemachine.constants.SpliceConstants;
import com.splicemachine.derby.hbase.SpliceOperationCoprocessor;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.derby.iapi.storage.RowProvider;
import com.splicemachine.derby.impl.storage.ClientScanProvider;
import com.splicemachine.derby.impl.storage.SimpleRegionAwareRowProvider;
import com.splicemachine.derby.stats.Accumulator;
import com.splicemachine.derby.stats.TimingStats;
import com.splicemachine.utils.SpliceLogUtils;
import org.datanucleus.sco.backed.Map;

public class GroupedAggregateOperation extends GenericAggregateOperation {
	private static Logger LOG = Logger.getLogger(GroupedAggregateOperation.class);
	protected boolean isInSortedOrder;
	protected boolean isRollup;
	protected int orderingItem;
	protected List<Integer> keyColumns;
	protected List<Integer> groupByColumns;
	protected List<Integer> nonGroupByUniqueColumns;
	protected List<Boolean> groupByDescAscInfo;	
	protected List<Boolean> descAscInfo;
	protected List<Integer> allKeyColumns;
	HashMap<Integer,List<DataValueDescriptor>> distinctValues;
	private int numDistinctAggs = 0;
	protected ColumnOrdering[] order;
	private HashBuffer<ByteBuffer,ExecRow> currentAggregations = new HashBuffer<ByteBuffer,ExecRow>(SpliceConstants.ringBufferSize);
	private ExecRow[] resultRows;
	private boolean completedExecution = false;
    protected KeyMarshall hasher;
    protected byte[] currentKey;
    protected MultiFieldEncoder sinkEncoder;
    protected RowProvider rowProvider;
    private Accumulator scanAccumulator = TimingStats.uniformAccumulator();

    private HashBufferSource hbs;

    private boolean isTemp;

    public GroupedAggregateOperation () {
    	super();
    	SpliceLogUtils.trace(LOG,"instantiate without parameters");
    }
  
    public GroupedAggregateOperation(NoPutResultSet s,
			boolean isInSortedOrder,
			int	aggregateItem,
			int	orderingItem,
			Activation a,
			GeneratedMethod ra,
			int maxRowSize,
			int resultSetNumber,
		    double optimizerEstimatedRowCount,
			double optimizerEstimatedCost,
			boolean isRollup) throws StandardException  {
    	super(s,aggregateItem,a,ra,resultSetNumber,optimizerEstimatedRowCount,optimizerEstimatedCost);
    	SpliceLogUtils.trace(LOG, "instantiate with isInSortedOrder %s, aggregateItem %d, orderingItem %d, isRollup %s",isInSortedOrder,aggregateItem,orderingItem,isRollup);
    	this.isInSortedOrder = isInSortedOrder;
    	this.isRollup = isRollup;
    	this.orderingItem = orderingItem;
    	recordConstructorTime();
    }
    
	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		super.readExternal(in);
		isInSortedOrder = in.readBoolean();
		isRollup = in.readBoolean();
		orderingItem = in.readInt();
	}

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        out.writeBoolean(isInSortedOrder);
        out.writeBoolean(isRollup);
        out.writeInt(orderingItem);
    }

    @Override
    public void init(SpliceOperationContext context) throws StandardException{
        SpliceLogUtils.trace(LOG, "init called");
        super.init(context);
        ((SpliceOperation)source).init(context);
        GenericStorablePreparedStatement statement = context.getPreparedStatement();
        order = (ColumnOrdering[])
                ((FormatableArrayHolder) (statement.getSavedObject(orderingItem))).getArray(ColumnOrdering.class);        
        keyColumns = new ArrayList<Integer>();
        nonGroupByUniqueColumns = new ArrayList<Integer>();
        groupByColumns = new ArrayList<Integer>();
        descAscInfo = new ArrayList<Boolean>();
        groupByDescAscInfo = new ArrayList<Boolean>();
        for (int index = 0; index < order.length; index++) {
            keyColumns.add(order[index].getColumnId());
            descAscInfo.add(order[index].getIsAscending());
        }
        
        for(SpliceGenericAggregator agg: aggregates){
            if(agg.isDistinct()) {
            	if (!keyColumns.contains(agg.getAggregatorInfo().getInputColNum()))
            		nonGroupByUniqueColumns.add(agg.getAggregatorInfo().getInputColNum());
            	numDistinctAggs++;
            }
        }
        // Create the Distinct Values Map
    	distinctValues = new HashMap<Integer,List<DataValueDescriptor>>();
        // Make sure the lists are clear, who is unique and who is group by
        if (numDistinctAggs > 0) {
        	groupByColumns.addAll(keyColumns.subList(0, keyColumns.size()-1));
        	nonGroupByUniqueColumns.add(nonGroupByUniqueColumns.size(),keyColumns.get(keyColumns.size()-1));
        	groupByDescAscInfo.addAll(descAscInfo.subList(0, descAscInfo.size()-1));
        	for (Integer unique: nonGroupByUniqueColumns) {
        		groupByDescAscInfo.add(true);
        	}
        } else {
        	groupByColumns.addAll(keyColumns);
        	groupByDescAscInfo.addAll(descAscInfo);        	
        }
        
        
        sinkEncoder = MultiFieldEncoder.create(groupByColumns.size() + nonGroupByUniqueColumns.size()+1);
        sinkEncoder.setRawBytes(uniqueSequenceID).mark();
//        scanEncoder = MultiFieldEncoder.create(groupByColumns.size());
    	allKeyColumns = new ArrayList<Integer>(groupByColumns);
    	allKeyColumns.addAll(nonGroupByUniqueColumns);
        if(regionScanner==null){
            isTemp = true;
        } else {
            RowEncoder scanEncoder = RowEncoder.create(sourceExecIndexRow.nColumns(),convertIntegers(allKeyColumns),convertBooleans(groupByDescAscInfo),
                    sinkEncoder.getEncodedBytes(0),
                    KeyType.FIXED_PREFIX,
                    RowMarshaller.packedCompressed());
            rowProvider = new SimpleRegionAwareRowProvider(
                    "groupedAggregateRowProvider",
                    SpliceUtils.NA_TRANSACTION_ID,
                    context.getRegion(),
                    context.getScan(),
                    SpliceConstants.TEMP_TABLE_BYTES,
                    SpliceConstants.DEFAULT_FAMILY_BYTES,
                    scanEncoder.getDual(sourceExecIndexRow),groupByColumns.size()); // Make sure the partitioner (Region Aware) worries about group by keys, not the additonal unique keys
            rowProvider.open();
            isTemp = !context.isSink() || context.getTopOperation()!=this;
        }
        hasher = KeyType.BARE;

        MultiFieldEncoder mfe = MultiFieldEncoder.create(groupByColumns.size() + nonGroupByUniqueColumns.size()+1);
        boolean[] groupByDescAscArray = convertBooleans(groupByDescAscInfo);
        int[] keyColumnArray = convertIntegers(allKeyColumns);
        RowProviderIterator<ExecRow> sourceProvider = createSourceIterator();

        hbs = new HashBufferSource(uniqueSequenceID, keyColumnArray, sourceProvider, merger, KeyType.BARE, mfe, groupByDescAscArray, aggregateFinisher);
    }

    @Override
    public RowProvider getReduceRowProvider(SpliceOperation top,RowDecoder decoder) throws StandardException {
        try {
            reduceScan = Scans.buildPrefixRangeScan(uniqueSequenceID,SpliceUtils.NA_TRANSACTION_ID);
        } catch (IOException e) {
            throw Exceptions.parseException(e);
        }
        SuccessFilter filter = new SuccessFilter(failedTasks,false);
        reduceScan.setFilter(filter);
        SpliceUtils.setInstructions(reduceScan, activation, top);
        return new ClientScanProvider("groupedAggregateReduce",SpliceOperationCoprocessor.TEMP_TABLE,reduceScan,decoder);
    }

    @Override
    public RowProvider getMapRowProvider(SpliceOperation top, RowDecoder decoder) throws StandardException {
        return getReduceRowProvider(top,decoder);
    }

    @Override
    protected JobStats doShuffle() throws StandardException {
        long start = System.currentTimeMillis();
        final RowProvider rowProvider = ((SpliceOperation)source).getMapRowProvider(this, getRowEncoder().getDual(getExecRowDefinition()));
        nextTime+= System.currentTimeMillis()-start;
        SpliceObserverInstructions soi = SpliceObserverInstructions.create(getActivation(),this);
        return rowProvider.shuffleRows(soi);
    }

    @Override
    public RowEncoder getRowEncoder() throws StandardException {
        return RowEncoder.create(sourceExecIndexRow.nColumns(), convertIntegers(allKeyColumns),convertBooleans(groupByDescAscInfo), null, new KeyMarshall() {
            @Override
            public void encodeKey(DataValueDescriptor[] columns,
                                  int[] keyColumns,
                                  boolean[] sortOrder,
                                  byte[] keyPostfix,
                                  MultiFieldEncoder keyEncoder) throws StandardException {
                byte[] key = BytesUtil.concatenate(currentKey,keyPostfix);
                keyEncoder.setRawBytes(key);
            }

            @Override
            public void decode(DataValueDescriptor[] columns,
                               int[] reversedKeyColumns,
                               boolean[] sortOrder,
                               MultiFieldDecoder rowDecoder) throws StandardException {
                hasher.decode(columns, reversedKeyColumns, sortOrder, rowDecoder);
            }

            @Override
            public int getFieldCount(int[] keyColumns) {
                return 1;
            }
        }, RowMarshaller.packedCompressed());
    }

    @Override
	public void cleanup() { 
		
	}

    @Override
    public ExecRow getNextSinkRow() throws StandardException {
        ExecRow row = doSinkAggregation();
        if (LOG.isTraceEnabled())
        	SpliceLogUtils.trace(LOG, "getNextSinkRow %s",row);
        return row;
    }

	@Override
	public ExecRow getNextRowCore() throws StandardException {
		ExecRow row = doScanAggregation();
        if (LOG.isTraceEnabled())
        	SpliceLogUtils.trace(LOG, "getNextRowCore %s",row);
        return row;
	}
	
	private final HashMerger merger = new HashMerger<ByteBuffer,ExecRow>() {
		@Override
		public ExecRow shouldMerge(HashBuffer<ByteBuffer, ExecRow> hashBuffer, ByteBuffer key){
			return hashBuffer.get(key);
		}

		@Override
		public void merge(HashBuffer<ByteBuffer, ExecRow> hashBuffer, ExecRow curr,ExecRow next){
			try {
				mergeVectorAggregates(next,curr);
			} catch (StandardException e) {
				SpliceLogUtils.logAndThrowRuntime(LOG, e);
			}
		}};
	
	private ExecRow doSinkAggregation() throws StandardException {

        if(resultRows==null){
        	resultRows = isRollup?new ExecRow[groupByColumns.size()+1]:new ExecRow[1]; // Need to fix Group By Columns
        }

        Pair<ByteBuffer,ExecRow> nextRow = hbs.getNextAggregatedRow();
        ExecRow rowResult = null;
        if(nextRow != null){
		    makeCurrent(nextRow.getFirst(),nextRow.getSecond());
            rowResult = nextRow.getSecond();
        }else{
            SpliceLogUtils.trace(LOG, "finalizeResults");
            completedExecution=true;
        }

        if (LOG.isTraceEnabled())
			SpliceLogUtils.trace(LOG,"next aggregated row = %s",nextRow);
		return rowResult;
	}

	private ExecRow doScanAggregation() throws StandardException {
		if (completedExecution) {
			if (currentAggregations.size()>0) {
				ByteBuffer key = currentAggregations.keySet().iterator().next();
				return makeCurrent(key,currentAggregations.remove(key));
			} else 
				return null; // Done
		}
		long start = System.nanoTime();
        if(resultRows==null)
                resultRows = new ExecRow[1];
    	ExecRow nextRow = getNextRowFromScan();
		if(nextRow ==null)
            return finalizeResults();
        //TODO -sf- stash these away somewhere so we're not constantly autoboxing
        int[] groupByCols = convertIntegers(groupByColumns);
		do{
	        resultRows[0] = nextRow;
	        ExecRow[] rolledUpRows = resultRows;
            for(ExecRow rolledUpRow:rolledUpRows) {
                sinkEncoder.reset();
                ((KeyMarshall)hasher).encodeKey(rolledUpRow.getRowArray(), groupByCols, null, null, sinkEncoder);
                ByteBuffer keyBuffer = ByteBuffer.wrap(sinkEncoder.build());
				if(!currentAggregations.merge(keyBuffer, rolledUpRow, merger)){
					ExecRow row = rolledUpRow.getClone();
                    refreshDistinctValues(row);
					Map.Entry<ByteBuffer,ExecRow> finalized = currentAggregations.add(keyBuffer,row);
                    if(finalized!=null&&finalized !=row){
						return makeCurrent(finalized.getKey(),finishAggregation(finalized.getValue()));
					}
				}
            }
			nextRow = getNextRowFromScan();
			scanAccumulator.tick(System.nanoTime()-start);
            start = System.nanoTime();
		} while (nextRow!=null);
		
		 ExecRow next = finalizeResults();
		if (LOG.isTraceEnabled())
			SpliceLogUtils.trace(LOG,"next aggregated row = %s",next);
        return next;
	}

	private void refreshDistinctValues(ExecRow row) throws StandardException {
		distinctValues.clear();
		for (int i = 0; i < aggregates.length; i++) {				
			SpliceGenericAggregator agg = aggregates[i];
            if(agg.isDistinct()) {
            		DataValueDescriptor value = agg.getInputColumnValue(row);
            		List<DataValueDescriptor> values;
        			values = new ArrayList<DataValueDescriptor>();
        			values.add(value);
        			distinctValues.put(i, values);
            }
		}
		
	}

    private ExecRow[] getRolledUpRows(ExecRow rowToRollUp) throws StandardException {
        if(!isRollup){
            resultRows[0] = rowToRollUp;
            return resultRows;
        }
        int rollUpPos = groupByColumns.size();
        int pos = 0;
        ExecRow nextRow = rowToRollUp.getClone();
        SpliceLogUtils.trace(LOG,"setting rollup cols to null");
        do{
            SpliceLogUtils.trace(LOG,"adding row %s",nextRow);
            resultRows[pos] = nextRow;

            //strip out the next key in the rollup
            if(rollUpPos>0){
	            nextRow = nextRow.getClone();
	            DataValueDescriptor rollUpCol = nextRow.getColumn(order[rollUpPos-1].getColumnId()+1);
	            rollUpCol.setToNull();
            }
            rollUpPos--;
            pos++;
        }while(rollUpPos>=0);

        return resultRows;
    }
	
	protected void initializeVectorAggregation(ExecRow row) throws StandardException{
	   for(SpliceGenericAggregator aggregator: aggregates){
			aggregator.initialize(row);
			aggregator.accumulate(row, row);
		}
	}
	
	private void mergeVectorAggregates(ExecRow newRow, ExecRow currRow) throws StandardException {
		for (int i=0; i< aggregates.length; i++) {
			SpliceGenericAggregator agg = aggregates[i];
			DataValueDescriptor value = agg.getInputColumnValue(newRow).cloneValue(false);
            if(agg.isDistinct()) {
            	if (!isTemp)
            		continue;
            	else {
            		List<DataValueDescriptor> values;
            		if (distinctValues.containsKey(i)) {
            			values = distinctValues.get(i);
            			if (values.contains(value)) {
            				continue; // Already there, skip...
            			}
            			values.add(value);
            			distinctValues.put(i, values);
            		} else {
            			values = new ArrayList<DataValueDescriptor>();
            			values.add(value);
            			distinctValues.put(i, values);
            		}
            	}
            }
			agg.merge(newRow,currRow);
		}
	}
	
	protected ExecRow getNextRowFromScan() throws StandardException {
		SpliceLogUtils.trace(LOG,"getting next row from scan");
        if(rowProvider.hasNext())
            return rowProvider.next();
        else return null;
	}

	private ExecRow getNextRowFromSource() throws StandardException{
		ExecRow sourceRow;
		ExecRow inputRow = null;

		if ((sourceRow = source.getNextRowCore())!=null){
			sourceExecIndexRow.execRowToExecIndexRow(sourceRow);
			inputRow = sourceExecIndexRow;
		}

		return inputRow;
	}
	
	private ExecRow finalizeResults() throws StandardException {
		SpliceLogUtils.trace(LOG, "finalizeResults");
		completedExecution=true;
		currentAggregations = currentAggregations.finishAggregates(aggregateFinisher);
		if(currentAggregations.size()>0) {
			ByteBuffer key = currentAggregations.keySet().iterator().next();
			return makeCurrent(key,currentAggregations.remove(key));
		}
		else 
			return null;
	}
	
	private ExecRow makeCurrent(ByteBuffer key, ExecRow row) throws StandardException{
		setCurrentRow(row);
        currentKey = key.array();
		return row;
	}

	@Override
	public ExecRow getExecRowDefinition() {
		SpliceLogUtils.trace(LOG,"getExecRowDefinition");
        return sourceExecIndexRow.getClone();
	}

	@Override
	public String toString() {
		return "GroupedAggregateOperation {source="+source;
	}


	public boolean isInSortedOrder() {
		return this.isInSortedOrder;
	}
	
	public boolean hasDistinctAggregate() {
		return this.numDistinctAggs>0;
	}
	
	@Override
	public long getTimeSpent(int type)
	{
		long totTime = constructorTime + openTime + nextTime + closeTime;

		if (type == NoPutResultSet.CURRENT_RESULTSET_ONLY)
			return	totTime - source.getTimeSpent(ENTIRE_RESULTSET_TREE);
		else
			return totTime;
	}
	@Override
	public void	close() throws StandardException
	{
		SpliceLogUtils.trace(LOG, "close in GroupedAggregate");
		beginTime = getCurrentTimeMillis();
		if ( isOpen )
	    {
            if(reduceScan!=null)
                SpliceDriver.driver().getTempCleaner().deleteRange(uniqueSequenceID,reduceScan.getStartRow(),reduceScan.getStopRow());
			// we don't want to keep around a pointer to the
			// row ... so it can be thrown away.
			// REVISIT: does this need to be in a finally
			// block, to ensure that it is executed?
		    clearCurrentRow();
			sourceExecIndexRow = null;
			source.close();

			super.close();
		}
		closeTime += getElapsedMillis(beginTime);

		isOpen = false;
	}
	
	public Properties getSortProperties() {
		Properties sortProperties = new Properties();
		sortProperties.setProperty("numRowsInput", ""+getRowsInput());
		sortProperties.setProperty("numRowsOutput", ""+getRowsOutput());
		return sortProperties;
	}

    @Override
    public String prettyPrint(int indentLevel) {
        return "Grouped"+super.prettyPrint(indentLevel);
    }
    public static int[] convertIntegers(List<Integer> integers) {
        int[] ret = new int[integers.size()];
        for (int i=0; i < ret.length; i++) {
            ret[i] = integers.get(i).intValue();
        }
        return ret;
    }
    public static boolean[] convertBooleans(List<Boolean> booleans) {
        boolean[] ret = new boolean[booleans.size()];
        for (int i=0; i < ret.length; i++) {
            ret[i] = booleans.get(i).booleanValue();
        }
        return ret;
    }

    private RowProviderIterator<ExecRow> createSourceIterator() {
        return new RowProviderIterator<ExecRow>(){

            private Iterator<ExecRow> rolledUpRows = Collections.EMPTY_LIST.iterator();
            private boolean populated;

            @Override
            public boolean hasNext() throws StandardException {

                if(!populated && rolledUpRows != null && !rolledUpRows.hasNext()){
                    ExecRow nextRow = getNextRowFromSource();

                    if(nextRow != null){
                        rolledUpRows = Arrays.asList(getRolledUpRows(nextRow)).iterator();
                        populated = true;
                    }else{
                        rolledUpRows = null;
                        populated = true;
                    }
                }

                return rolledUpRows != null && rolledUpRows.hasNext();
            }

            @Override
            public ExecRow next() throws StandardException {

                if(!populated){
                    hasNext();
                }

                ExecRow nextRow = null;

                if( rolledUpRows != null){
                    nextRow = rolledUpRows.next();
                    populated = false;
                    initializeVectorAggregation(nextRow);
                }

                return nextRow;
            }
        };
    }


}
