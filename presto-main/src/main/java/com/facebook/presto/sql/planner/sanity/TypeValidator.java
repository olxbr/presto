/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.sanity;

import com.facebook.presto.Session;
import com.facebook.presto.execution.warnings.WarningCollector;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.SimplePlanVisitor;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.AggregationNode.Aggregation;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.UnionNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.tree.SymbolReference;
import com.google.common.collect.ListMultimap;

import java.util.List;
import java.util.Map;

import static com.facebook.presto.sql.analyzer.ExpressionAnalyzer.getExpressionTypes;
import static com.facebook.presto.type.UnknownType.UNKNOWN;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

/**
 * Ensures that all the expressions and FunctionCalls matches their output symbols
 */
public final class TypeValidator
        implements PlanSanityChecker.Checker
{
    public TypeValidator() {}

    @Override
    public void validate(PlanNode plan, Session session, Metadata metadata, SqlParser sqlParser, TypeProvider types, WarningCollector warningCollector)
    {
        plan.accept(new Visitor(session, metadata, sqlParser, types, warningCollector), null);
    }

    private static class Visitor
            extends SimplePlanVisitor<Void>
    {
        private final Session session;
        private final Metadata metadata;
        private final SqlParser sqlParser;
        private final TypeProvider types;
        private final WarningCollector warningCollector;

        public Visitor(Session session, Metadata metadata, SqlParser sqlParser, TypeProvider types, WarningCollector warningCollector)
        {
            this.session = requireNonNull(session, "session is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
            this.types = requireNonNull(types, "types is null");
            this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
        }

        @Override
        public Void visitAggregation(AggregationNode node, Void context)
        {
            visitPlan(node, context);

            AggregationNode.Step step = node.getStep();

            switch (step) {
                case SINGLE:
                    checkFunctionSignature(node.getAggregations());
                    checkAggregation(node.getAggregations());
                    break;
                case FINAL:
                    checkFunctionSignature(node.getAggregations());
                    break;
            }

            return null;
        }

        @Override
        public Void visitWindow(WindowNode node, Void context)
        {
            visitPlan(node, context);

            checkWindowFunctions(node.getWindowFunctions());

            return null;
        }

        @Override
        public Void visitProject(ProjectNode node, Void context)
        {
            visitPlan(node, context);

            for (Map.Entry<VariableReferenceExpression, Expression> entry : node.getAssignments().entrySet()) {
                if (entry.getValue() instanceof SymbolReference) {
                    SymbolReference symbolReference = (SymbolReference) entry.getValue();
                    verifyTypeSignature(entry.getKey(), types.get(Symbol.from(symbolReference)).getTypeSignature());
                    continue;
                }
                Map<NodeRef<Expression>, Type> expressionTypes = getExpressionTypes(session, metadata, sqlParser, types, entry.getValue(), emptyList(), warningCollector);
                Type actualType = expressionTypes.get(NodeRef.of(entry.getValue()));
                verifyTypeSignature(entry.getKey(), actualType.getTypeSignature());
            }

            return null;
        }

        @Override
        public Void visitUnion(UnionNode node, Void context)
        {
            visitPlan(node, context);

            ListMultimap<VariableReferenceExpression, VariableReferenceExpression> variableMapping = node.getVariableMapping();
            for (VariableReferenceExpression keyVariable : variableMapping.keySet()) {
                List<VariableReferenceExpression> valueVariables = variableMapping.get(keyVariable);
                for (VariableReferenceExpression valueVariable : valueVariables) {
                    verifyTypeSignature(keyVariable, valueVariable.getType().getTypeSignature());
                }
            }

            return null;
        }

        private void checkWindowFunctions(Map<VariableReferenceExpression, WindowNode.Function> functions)
        {
            for (Map.Entry<VariableReferenceExpression, WindowNode.Function> entry : functions.entrySet()) {
                FunctionHandle functionHandle = entry.getValue().getFunctionHandle();
                CallExpression call = entry.getValue().getFunctionCall();

                verifyTypeSignature(entry.getKey(), metadata.getFunctionManager().getFunctionMetadata(functionHandle).getReturnType());
                checkCall(entry.getKey(), call);
            }
        }

        private void checkCall(VariableReferenceExpression variable, CallExpression call)
        {
            Type actualType = call.getType();
            verifyTypeSignature(variable, actualType.getTypeSignature());
        }

        private void checkFunctionSignature(Map<VariableReferenceExpression, Aggregation> aggregations)
        {
            for (Map.Entry<VariableReferenceExpression, Aggregation> entry : aggregations.entrySet()) {
                verifyTypeSignature(entry.getKey(), metadata.getFunctionManager().getFunctionMetadata(entry.getValue().getFunctionHandle()).getReturnType());
            }
        }

        private void checkAggregation(Map<VariableReferenceExpression, Aggregation> aggregations)
        {
            for (Map.Entry<VariableReferenceExpression, Aggregation> entry : aggregations.entrySet()) {
                verifyTypeSignature(
                        entry.getKey(),
                        metadata.getFunctionManager().getFunctionMetadata(entry.getValue().getFunctionHandle()).getReturnType());
                // TODO check if the argument type agrees with function handle (will be added once Aggregation is using CallExpression).
            }
        }

        private void verifyTypeSignature(VariableReferenceExpression variable, TypeSignature actual)
        {
            // UNKNOWN should be considered as a wildcard type, which matches all the other types
            TypeManager typeManager = metadata.getTypeManager();
            if (!actual.equals(UNKNOWN.getTypeSignature()) && !typeManager.isTypeOnlyCoercion(typeManager.getType(actual), variable.getType())) {
                checkArgument(variable.getType().getTypeSignature().equals(actual), "type of variable '%s' is expected to be %s, but the actual type is %s", variable.getName(), variable.getType(), actual);
            }
        }
    }
}
