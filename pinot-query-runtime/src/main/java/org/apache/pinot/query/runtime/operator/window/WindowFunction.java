/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.query.runtime.operator.window;

import java.util.List;
import org.apache.commons.collections.CollectionUtils;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.query.planner.logical.RexExpression;
import org.apache.pinot.query.runtime.operator.WindowAggregateOperator;
import org.apache.pinot.query.runtime.operator.utils.AggregationUtils;


/**
 * This class provides the basic structure for window functions. It provides the batch row processing API:
 * processRows(List<Object[]> rows) which processes a batch of rows at a time.
 *
 */
public abstract class WindowFunction extends AggregationUtils.Accumulator {
  protected final String _functionName;
  protected final int[] _inputRefs;
  protected final boolean _isPartitionByOnly;
  protected final List<RexExpression> _orderSet;

  public WindowFunction(RexExpression.FunctionCall aggCall, String functionName,
      DataSchema inputSchema, WindowAggregateOperator.OrderSetInfo orderSetInfo) {
    super(aggCall, functionName, inputSchema);
    _isPartitionByOnly = CollectionUtils.isEmpty(orderSetInfo.getOrderSet()) || orderSetInfo.isPartitionByOnly();
    boolean isRankingWindowFunction = WindowAggregateOperator.RANKING_FUNCTION_NAMES.contains(functionName);
    int[] inputRefs = new int[]{_inputRef};
    if (isRankingWindowFunction) {
      inputRefs = orderSetInfo._orderSet.stream().map(RexExpression.InputRef.class::cast)
          .mapToInt(RexExpression.InputRef::getIndex).toArray();
    }
    _functionName = functionName;
    _inputRefs = inputRefs;
    _orderSet = orderSetInfo._orderSet;
  }

  /**
   * Batch processing API for Window functions.
   * This method processes a batch of rows at a time.
   * Each row generates one object as output.
   * Note, the input and output list size should be the same.
   *
   * @param rows List of rows to process
   * @return List of rows with the window function applied
   */
  public abstract List<Object> processRows(List<Object[]> rows);
}
