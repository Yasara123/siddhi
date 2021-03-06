/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.query.selector.attribute.aggregator.incremental;

import org.wso2.siddhi.core.aggregation.AggregationRuntime;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.event.stream.MetaStreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventPool;
import org.wso2.siddhi.core.query.StoreQueryRuntime;
import org.wso2.siddhi.core.table.Table;
import org.wso2.siddhi.core.util.parser.StoreQueryParser;
import org.wso2.siddhi.core.window.Window;
import org.wso2.siddhi.query.api.aggregation.TimePeriod;
import org.wso2.siddhi.query.api.execution.query.StoreQuery;
import org.wso2.siddhi.query.api.execution.query.input.store.InputStore;
import org.wso2.siddhi.query.api.execution.query.selection.Selector;
import org.wso2.siddhi.query.api.expression.Expression;
import org.wso2.siddhi.query.api.expression.condition.Compare;

import java.util.List;
import java.util.Map;

/**
 * This class is used to recreate in-memory data from the tables (Such as RDBMS) in incremental aggregation.
 * This ensures that the aggregation calculations are done correctly in case of server restart
 */
public class RecreateInMemoryData {
    private final List<TimePeriod.Duration> incrementalDurations;
    private final Map<TimePeriod.Duration, Table> aggregationTables;
    private final Map<TimePeriod.Duration, IncrementalExecutor> incrementalExecutorMap;
    private final SiddhiAppContext siddhiAppContext;
    private final StreamEventPool streamEventPool;
    private final Map<String, Table> tableMap;
    private final Map<String, Window> windowMap;
    private final Map<String, AggregationRuntime> aggregationMap;

    public RecreateInMemoryData(List<TimePeriod.Duration> incrementalDurations,
            Map<TimePeriod.Duration, Table> aggregationTables,
            Map<TimePeriod.Duration, IncrementalExecutor> incrementalExecutorMap, SiddhiAppContext siddhiAppContext,
            MetaStreamEvent metaStreamEvent, Map<String, Table> tableMap, Map<String, Window> windowMap,
            Map<String, AggregationRuntime> aggregationMap) {
        this.incrementalDurations = incrementalDurations;
        this.aggregationTables = aggregationTables;
        this.incrementalExecutorMap = incrementalExecutorMap;
        this.siddhiAppContext = siddhiAppContext;
        this.streamEventPool = new StreamEventPool(metaStreamEvent, 10);
        this.tableMap = tableMap;
        this.windowMap = windowMap;
        this.aggregationMap = aggregationMap;
    }

    public void recreateInMemoryData() {
        List<Event> sortedEvents;
        Event[] events;
        Long latestEventTimestamp = null;

        // Get all events from table corresponding to max duration
        Table tableForMaxDuration = aggregationTables.get(incrementalDurations.get(incrementalDurations.size() - 1));
        StoreQuery storeQuery = StoreQuery.query()
                .from(InputStore.store(tableForMaxDuration.getTableDefinition().getId()))
                .select(Selector.selector().orderBy(Expression.variable("_TIMESTAMP")));
        StoreQueryRuntime storeQueryRuntime = StoreQueryParser.parse(storeQuery, siddhiAppContext, tableMap, windowMap,
                aggregationMap);

        // Get latest event timestamp in tableForMaxDuration
        events = storeQueryRuntime.execute();
        if (events != null) {
            latestEventTimestamp = (Long) events[events.length - 1].getData(0);
        }

        for (int i = incrementalDurations.size() - 1; i > 0; i--) {
            TimePeriod.Duration recreateForDuration = incrementalDurations.get(i);
            IncrementalExecutor incrementalExecutor = incrementalExecutorMap.get(recreateForDuration);

            // Get the table previous to the duration for which we need to recreate (e.g. if we want to recreate
            // for minute duration, take the second table [provided that aggregation is done for seconds])
            Table recreateFromTable = aggregationTables.get(incrementalDurations.get(i - 1));

            if (latestEventTimestamp == null) {
                storeQuery = StoreQuery.query().from(InputStore.store(recreateFromTable.getTableDefinition().getId()))
                        .select(Selector.selector().orderBy(Expression.variable("_TIMESTAMP")));
            } else {
                Expression findMaxCondition = Expression.compare(Expression.variable("_TIMESTAMP"),
                        Compare.Operator.GREATER_THAN, Expression.value(latestEventTimestamp));
                storeQuery = StoreQuery.query()
                        .from(InputStore.store(recreateFromTable.getTableDefinition().getId()).on(findMaxCondition))
                        .select(Selector.selector().orderBy(Expression.variable("_TIMESTAMP")));
            }

            storeQueryRuntime = StoreQueryParser.parse(storeQuery, siddhiAppContext, tableMap, windowMap,
                    aggregationMap);
            events = storeQueryRuntime.execute();
            if (events != null) {
                latestEventTimestamp = (Long) events[events.length - 1].getData(0);

                ComplexEventChunk<StreamEvent> complexEventChunk = new ComplexEventChunk<>(false);
                for (Event event : events) {
                    StreamEvent streamEvent = streamEventPool.borrowEvent();
                    streamEvent.setOutputData(event.getData());
                    complexEventChunk.add(streamEvent);
                }
                incrementalExecutor.execute(complexEventChunk);
            }
        }
    }
}
