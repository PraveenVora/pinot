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
package org.apache.pinot.query;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepMatchOrder;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.apache.pinot.calcite.prepare.PinotCalciteCatalogReader;
import org.apache.pinot.calcite.rel.rules.PinotQueryRuleSets;
import org.apache.pinot.calcite.rel.rules.PinotRelDistributionTraitRule;
import org.apache.pinot.calcite.rel.rules.PinotRuleUtils;
import org.apache.pinot.calcite.sql.fun.PinotOperatorTable;
import org.apache.pinot.calcite.sql.util.PinotChainedSqlOperatorTable;
import org.apache.pinot.calcite.sql2rel.PinotConvertletTable;
import org.apache.pinot.common.config.provider.TableCache;
import org.apache.pinot.query.context.PlannerContext;
import org.apache.pinot.query.planner.PlannerUtils;
import org.apache.pinot.query.planner.SubPlan;
import org.apache.pinot.query.planner.explain.PhysicalExplainPlanVisitor;
import org.apache.pinot.query.planner.logical.PinotLogicalQueryPlanner;
import org.apache.pinot.query.planner.logical.RelToPlanNodeConverter;
import org.apache.pinot.query.planner.physical.DispatchableSubPlan;
import org.apache.pinot.query.planner.physical.PinotDispatchPlanner;
import org.apache.pinot.query.routing.WorkerManager;
import org.apache.pinot.query.type.TypeFactory;
import org.apache.pinot.query.validate.BytesCastVisitor;
import org.apache.pinot.sql.parsers.CalciteSqlParser;
import org.apache.pinot.sql.parsers.SqlNodeAndOptions;
import org.apache.pinot.sql.parsers.parser.SqlPhysicalExplain;


/**
 * The {@code QueryEnvironment} contains the main entrypoint for query planning.
 *
 * <p>It provide the higher level entry interface to convert a SQL string into a {@link DispatchableSubPlan}.
 */
public class QueryEnvironment {
  // Calcite configurations
  private final RelDataTypeFactory _typeFactory;
  private final Prepare.CatalogReader _catalogReader;
  private final FrameworkConfig _config;
  private final HepProgram _optProgram;
  private final HepProgram _traitProgram;

  // Pinot extensions
  private final WorkerManager _workerManager;
  private final TableCache _tableCache;

  public QueryEnvironment(TypeFactory typeFactory, CalciteSchema rootSchema, WorkerManager workerManager,
      TableCache tableCache) {
    _typeFactory = typeFactory;
    // Calcite extension/plugins
    _workerManager = workerManager;
    _tableCache = tableCache;

    // catalog & config
    _catalogReader = getCatalogReader(_typeFactory, rootSchema);
    _config = getConfig(_catalogReader);
    // opt programs
    _optProgram = getOptProgram();
    _traitProgram = getTraitProgram();
  }

  private PlannerContext getPlannerContext() {
    return new PlannerContext(_config, _catalogReader, _typeFactory, _optProgram, _traitProgram);
  }

  /**
   * Plan a SQL query.
   *
   * This function is thread safe since we construct a new PlannerContext every time.
   *
   * TODO: follow benchmark and profile to measure whether it make sense for the latency-concurrency trade-off
   * between reusing plannerImpl vs. create a new planner for each query.
   *
   * @param sqlQuery SQL query string.
   * @param sqlNodeAndOptions parsed SQL query.
   * @return QueryPlannerResult containing the dispatchable query plan and the relRoot.
   */
  public QueryPlannerResult planQuery(String sqlQuery, SqlNodeAndOptions sqlNodeAndOptions, long requestId) {
    try (PlannerContext plannerContext = getPlannerContext()) {
      plannerContext.setOptions(sqlNodeAndOptions.getOptions());
      RelRoot relRoot = compileQuery(sqlNodeAndOptions.getSqlNode(), plannerContext);
      // TODO: current code only assume one SubPlan per query, but we should support multiple SubPlans per query.
      // Each SubPlan should be able to run independently from Broker then set the results into the dependent
      // SubPlan for further processing.
      DispatchableSubPlan dispatchableSubPlan = toDispatchableSubPlan(relRoot, plannerContext, requestId);
      return new QueryPlannerResult(dispatchableSubPlan, null, dispatchableSubPlan.getTableNames());
    } catch (CalciteContextException e) {
      throw new RuntimeException("Error composing query plan for '" + sqlQuery + "': " + e.getMessage() + "'", e);
    } catch (Throwable t) {
      throw new RuntimeException("Error composing query plan for: " + sqlQuery, t);
    }
  }

  /**
   * Explain a SQL query.
   *
   * Similar to {@link QueryEnvironment#planQuery(String, SqlNodeAndOptions, long)}, this API runs the query
   * compilation. But it doesn't run the distributed {@link DispatchableSubPlan} generation, instead it only
   * returns the
   * explained logical plan.
   *
   * @param sqlQuery SQL query string.
   * @param sqlNodeAndOptions parsed SQL query.
   * @return QueryPlannerResult containing the explained query plan and the relRoot.
   */
  public QueryPlannerResult explainQuery(String sqlQuery, SqlNodeAndOptions sqlNodeAndOptions, long requestId) {
    try (PlannerContext plannerContext = getPlannerContext()) {
      SqlExplain explain = (SqlExplain) sqlNodeAndOptions.getSqlNode();
      plannerContext.setOptions(sqlNodeAndOptions.getOptions());
      RelRoot relRoot = compileQuery(explain.getExplicandum(), plannerContext);
      if (explain instanceof SqlPhysicalExplain) {
        // get the physical plan for query.
        DispatchableSubPlan dispatchableSubPlan = toDispatchableSubPlan(relRoot, plannerContext, requestId);
        return new QueryPlannerResult(null, PhysicalExplainPlanVisitor.explain(dispatchableSubPlan),
            dispatchableSubPlan.getTableNames());
      } else {
        // get the logical plan for query.
        SqlExplainFormat format = explain.getFormat() == null ? SqlExplainFormat.DOT : explain.getFormat();
        SqlExplainLevel level =
            explain.getDetailLevel() == null ? SqlExplainLevel.DIGEST_ATTRIBUTES : explain.getDetailLevel();
        Set<String> tableNames = RelToPlanNodeConverter.getTableNamesFromRelRoot(relRoot.rel);
        return new QueryPlannerResult(null, PlannerUtils.explainPlan(relRoot.rel, format, level), tableNames);
      }
    } catch (Exception e) {
      throw new RuntimeException("Error explain query plan for: " + sqlQuery, e);
    }
  }

  @VisibleForTesting
  public DispatchableSubPlan planQuery(String sqlQuery) {
    return planQuery(sqlQuery, CalciteSqlParser.compileToSqlNodeAndOptions(sqlQuery), 0).getQueryPlan();
  }

  @VisibleForTesting
  public String explainQuery(String sqlQuery, long requestId) {
    return explainQuery(sqlQuery, CalciteSqlParser.compileToSqlNodeAndOptions(sqlQuery), requestId).getExplainPlan();
  }

  public List<String> getTableNamesForQuery(String sqlQuery) {
    try (PlannerContext plannerContext = getPlannerContext()) {
      SqlNode sqlNode = CalciteSqlParser.compileToSqlNodeAndOptions(sqlQuery).getSqlNode();
      if (sqlNode.getKind().equals(SqlKind.EXPLAIN)) {
        sqlNode = ((SqlExplain) sqlNode).getExplicandum();
      }
      RelRoot relRoot = compileQuery(sqlNode, plannerContext);
      Set<String> tableNames = RelToPlanNodeConverter.getTableNamesFromRelRoot(relRoot.rel);
      return new ArrayList<>(tableNames);
    } catch (Throwable t) {
      throw new RuntimeException("Error composing query plan for: " + sqlQuery, t);
    }
  }

  /**
   * Results of planning a query
   */
  public static class QueryPlannerResult {
    private final DispatchableSubPlan _dispatchableSubPlan;
    private final String _explainPlan;
    private final Set<String> _tableNames;

    QueryPlannerResult(@Nullable DispatchableSubPlan dispatchableSubPlan, @Nullable String explainPlan,
        Set<String> tableNames) {
      _dispatchableSubPlan = dispatchableSubPlan;
      _explainPlan = explainPlan;
      _tableNames = tableNames;
    }

    public String getExplainPlan() {
      return _explainPlan;
    }

    public DispatchableSubPlan getQueryPlan() {
      return _dispatchableSubPlan;
    }

    public Set<String> getTableNames() {
      return _tableNames;
    }
  }

  // --------------------------------------------------------------------------
  // steps
  // --------------------------------------------------------------------------

  private RelRoot compileQuery(SqlNode sqlNode, PlannerContext plannerContext) {
    SqlNode validated = validate(sqlNode, plannerContext);
    RelRoot relation = toRelation(validated, plannerContext);
    RelNode optimized = optimize(relation, plannerContext);
    return relation.withRel(optimized);
  }

  private SqlNode validate(SqlNode sqlNode, PlannerContext plannerContext) {
    SqlNode validated = plannerContext.getValidator().validate(sqlNode);
    if (!validated.getKind().belongsTo(SqlKind.QUERY)) {
      throw new IllegalArgumentException("Unsupported SQL query, failed to validate query:\n" + sqlNode);
    }
    validated.accept(new BytesCastVisitor(plannerContext.getValidator()));
    return validated;
  }

  private RelRoot toRelation(SqlNode sqlNode, PlannerContext plannerContext) {
    RexBuilder rexBuilder = new RexBuilder(_typeFactory);
    RelOptCluster cluster = RelOptCluster.create(plannerContext.getRelOptPlanner(), rexBuilder);
    SqlToRelConverter converter =
        new SqlToRelConverter(plannerContext.getPlanner(), plannerContext.getValidator(), _catalogReader, cluster,
            PinotConvertletTable.INSTANCE, _config.getSqlToRelConverterConfig());
    RelRoot relRoot;
    try {
      relRoot = converter.convertQuery(sqlNode, false, true);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to convert query to relational expression:\n" + sqlNode, e);
    }
    RelNode rootNode = relRoot.rel;
    try {
      // NOTE: DO NOT use converter.decorrelate(sqlNode, rootNode) because the converted type check can fail. This is
      //       probably a bug in Calcite.
      RelBuilder relBuilder = PinotRuleUtils.PINOT_REL_FACTORY.create(cluster, null);
      rootNode = RelDecorrelator.decorrelateQuery(rootNode, relBuilder);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to decorrelate query:\n" + RelOptUtil.toString(rootNode), e);
    }
    try {
      rootNode = converter.trimUnusedFields(false, rootNode);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to trim unused fields from query:\n" + RelOptUtil.toString(rootNode), e);
    }
    return relRoot.withRel(rootNode);
  }

  private RelNode optimize(RelRoot relRoot, PlannerContext plannerContext) {
    // TODO: add support for cost factory
    try {
      RelOptPlanner optPlanner = plannerContext.getRelOptPlanner();
      optPlanner.setRoot(relRoot.rel);
      RelNode optimized = optPlanner.findBestExp();
      RelOptPlanner traitPlanner = plannerContext.getRelTraitPlanner();
      traitPlanner.setRoot(optimized);
      return traitPlanner.findBestExp();
    } catch (Throwable e) {
      throw new RuntimeException(
          "Failed to generate a valid execution plan for query:\n" + RelOptUtil.toString(relRoot.rel), e);
    }
  }

  private DispatchableSubPlan toDispatchableSubPlan(RelRoot relRoot, PlannerContext plannerContext, long requestId) {
    PinotLogicalQueryPlanner logicalQueryPlanner = new PinotLogicalQueryPlanner();
    SubPlan plan = logicalQueryPlanner.makePlan(relRoot);
    PinotDispatchPlanner pinotDispatchPlanner =
        new PinotDispatchPlanner(plannerContext, _workerManager, requestId, _tableCache);
    return pinotDispatchPlanner.createDispatchableSubPlan(plan);
  }

  // --------------------------------------------------------------------------
  // utils
  // --------------------------------------------------------------------------

  private static Prepare.CatalogReader getCatalogReader(RelDataTypeFactory typeFactory, CalciteSchema rootSchema) {
    Properties properties = new Properties();
    properties.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "true");
    return new PinotCalciteCatalogReader(rootSchema, List.of(rootSchema.getName()), typeFactory,
        new CalciteConnectionConfigImpl(properties));
  }

  private static FrameworkConfig getConfig(Prepare.CatalogReader catalogReader) {
    return Frameworks.newConfigBuilder().traitDefs()
        .operatorTable(new PinotChainedSqlOperatorTable(Arrays.asList(PinotOperatorTable.instance(), catalogReader)))
        .defaultSchema(catalogReader.getRootSchema().plus())
        .sqlToRelConverterConfig(PinotRuleUtils.PINOT_SQL_TO_REL_CONFIG).build();
  }

  private static HepProgram getOptProgram() {
    HepProgramBuilder hepProgramBuilder = new HepProgramBuilder();
    // Set the match order as DEPTH_FIRST. The default is arbitrary which works the same as DEPTH_FIRST, but it's
    // best to be explicit.
    hepProgramBuilder.addMatchOrder(HepMatchOrder.DEPTH_FIRST);

    // ----
    // Run the Calcite CORE rules using 1 HepInstruction per rule. We use 1 HepInstruction per rule for simplicity:
    // the rules used here can rest assured that they are the only ones evaluated in a dedicated graph-traversal.
    for (RelOptRule relOptRule : PinotQueryRuleSets.BASIC_RULES) {
      hepProgramBuilder.addRuleInstance(relOptRule);
    }

    // ----
    // Pushdown filters using a single HepInstruction.
    hepProgramBuilder.addRuleCollection(PinotQueryRuleSets.FILTER_PUSHDOWN_RULES);

    // ----
    // Prune duplicate/unnecessary nodes using a single HepInstruction.
    // TODO: We can consider using HepMatchOrder.TOP_DOWN if we find cases where it would help.
    hepProgramBuilder.addRuleCollection(PinotQueryRuleSets.PRUNE_RULES);
    return hepProgramBuilder.build();
  }

  private static HepProgram getTraitProgram() {
    HepProgramBuilder hepProgramBuilder = new HepProgramBuilder();

    // Set the match order as BOTTOM_UP.
    hepProgramBuilder.addMatchOrder(HepMatchOrder.BOTTOM_UP);

    // ----
    // Run pinot specific rules that should run after all other rules, using 1 HepInstruction per rule.
    for (RelOptRule relOptRule : PinotQueryRuleSets.PINOT_POST_RULES) {
      hepProgramBuilder.addRuleInstance(relOptRule);
    }

    // apply RelDistribution trait to all nodes
    hepProgramBuilder.addRuleInstance(PinotRelDistributionTraitRule.INSTANCE);

    return hepProgramBuilder.build();
  }
}
