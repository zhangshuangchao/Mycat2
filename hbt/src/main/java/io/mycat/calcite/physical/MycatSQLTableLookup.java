package io.mycat.calcite.physical;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import io.mycat.AsyncMycatDataContextImpl;
import io.mycat.DrdsSqlWithParams;
import io.mycat.beans.mycat.CopyMycatRowMetaData;
import io.mycat.calcite.*;
import io.mycat.calcite.logical.MycatView;
import io.mycat.calcite.resultset.CalciteRowMetaData;
import io.mycat.vertx.VertxExecuter;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observable;
import lombok.Getter;
import org.apache.calcite.adapter.enumerable.*;
import org.apache.calcite.linq4j.tree.*;
import org.apache.calcite.plan.*;
import org.apache.calcite.rel.*;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.*;
import org.apache.calcite.runtime.NewMycatDataContext;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.sql.util.SqlString;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.Util;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.mycat.calcite.MycatImplementor.MYCAT_SQL_LOOKUP_IN;

@Getter
public class MycatSQLTableLookup extends SingleRel implements MycatRel {
    protected final RexNode joinCondition;
    protected final JoinRelType joinType;
    protected final Type type;
    protected final List<CorrelationId> correlationIds;
    protected final MycatView right;

    public enum Type {
        NONE,
        BACK
    }

    @Override
    public RelWriter explainTerms(RelWriter pw) {
        JoinInfo joinInfo = JoinInfo.of(getInput(), right, joinCondition);
        RelWriter relWriter = super.explainTerms(pw)
                .item("condition", joinCondition)
                .item("joinType", joinType.lowerName)
                .item("type", type.toString())
                .item("correlationIds", correlationIds)
                .input("left", input)
                .input("right", right)
                .item("leftKeys", joinInfo.leftKeys);
//        if (pw instanceof RelWriterImpl){
//            pw.item("rightSQL",right.getSQLTemplate(false));
//        }
        return relWriter;
    }

    public MycatSQLTableLookup changeTo(RelNode input, MycatView right) {
        return new MycatSQLTableLookup(getCluster(), input.getTraitSet(), input, right, joinType, joinCondition, correlationIds, type);
    }

    public MycatSQLTableLookup changeTo(RelNode input) {
        return new MycatSQLTableLookup(getCluster(), input.getTraitSet(), input, right, joinType, joinCondition, correlationIds, type);
    }

    public static MycatSQLTableLookup create(RelOptCluster cluster,
                                             RelTraitSet traits,
                                             RelNode input,
                                             MycatView right,
                                             JoinRelType joinType,
                                             RexNode condition,
                                             List<CorrelationId> correlationIds,
                                             Type type) {
        return new MycatSQLTableLookup(cluster, traits, input, right, joinType, condition, correlationIds, type);
    }

    public MycatSQLTableLookup(RelOptCluster cluster,
                               RelTraitSet traits,
                               RelNode input,
                               MycatView right,
                               JoinRelType joinType,
                               RexNode condition,
                               List<CorrelationId> correlationIds,
                               Type type) {
        super(cluster, traits.replace(MycatConvention.INSTANCE), input);
        this.joinCondition = condition;
        this.joinType = joinType;
        this.correlationIds = correlationIds;
        this.type = type;
        this.right = right;
        switch (type) {
            case NONE:
                this.rowType = right.getRowType();
                break;
            case BACK:
                this.rowType = SqlValidatorUtil.deriveJoinRowType(input.getRowType(), right.getRowType(), joinType,
                        cluster.getTypeFactory(), null, ImmutableList.of());
                break;
        }
//
//        class ValueRowTypeFinder extends RelShuttleImpl {
//            public RelDataType argRowType;
//
//            @Override
//            public RelNode visit(RelNode other) {
//                if (other instanceof MycatTableLookupValues) {
//                    MycatTableLookupValues mycatTableLookupValues = (MycatTableLookupValues) other;
//                    this.argRowType = mycatTableLookupValues.getRowType();
//                }
//                return super.visit(other);
//            }
//        }
//        ValueRowTypeFinder valueRowTypeFinder = new ValueRowTypeFinder();
//        right.getRelNode().accept(valueRowTypeFinder);
//        this.argRowType = valueRowTypeFinder.argRowType;

    }

    public MycatSQLTableLookup(RelInput input) {
        this(input.getCluster(), input.getTraitSet(), input.getInput(), (MycatView) input.getInputs().get(1),
                input.getEnum("joinType", JoinRelType.class), input.getExpression("condition"),
                input.getIntegerList("correlationIds").stream().map(i -> new CorrelationId(i)).collect(Collectors.toList()),
                input.getEnum("type", Type.class));
    }

    @Override
    public ExplainWriter explain(ExplainWriter writer) {
        return null;
    }

    @Override
    public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
        return implementStream((StreamMycatEnumerableRelImplementor) implementor, pref);
    }


    //hashJoin
    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        RelNode left = getInput();
        double rowCount = mq.getRowCount(this);

        if (RelNodes.COMPARATOR.compare(left, right) > 0) {
            rowCount = RelMdUtil.addEpsilon(rowCount);
        }

        // Cheaper if the smaller number of rows is coming from the LHS.
        // Model this by adding L log L to the cost.
        final double rightRowCount = right.estimateRowCount(mq);
        final double leftRowCount = left.estimateRowCount(mq);
        if (Double.isInfinite(leftRowCount)) {
            rowCount = leftRowCount;
        } else {
            rowCount += Util.nLogN(leftRowCount);
        }
        if (Double.isInfinite(rightRowCount)) {
            rowCount = rightRowCount;
        } else {
            rowCount += rightRowCount;
        }
        RelOptCost relOptCost;
        relOptCost = planner.getCostFactory().makeCost(rowCount, 0, 0);
        return relOptCost;
    }

    @Override
    public Result implementStream(StreamMycatEnumerableRelImplementor implementor, Prefer pref) {
        RelNode left = getInput();
        final PhysType physType =
                PhysTypeImpl.of(
                        implementor.getTypeFactory(), getRowType(), pref.preferArray());
        final PhysType rightPhysType =
                PhysTypeImpl.of(
                        implementor.getTypeFactory(), right.getRowType(), pref.preferArray());
        BlockBuilder builder = new BlockBuilder();
        Result leftResult =
                implementor.visitChild(this, 0, (EnumerableRel) left, pref);
        Expression leftExpression =
                toObservable(builder.append(
                        "left", leftResult.block));

        switch (type) {
            case NONE: {
                leftExpression = leftExpression;
                break;
            }
            case BACK: {
                leftExpression = toObservableCache(leftExpression);

                break;
            }
            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }
        ParameterExpression root = implementor.getRootExpression();

        Method dispatch = Types.lookupMethod(MycatSQLTableLookup.class, "dispatchRightObservable", NewMycatDataContext.class, MycatSQLTableLookup.class, Observable.class);

        Expression rightExpression = Expressions.call(dispatch, root, implementor.stash(this, MycatSQLTableLookup.class), leftExpression);
        switch (type) {
            case NONE: {
                return implementor.result(physType, builder.append(rightExpression).toBlock());
            }
            case BACK: {
                return implementHashJoin(implementor, pref, builder, leftResult.physType, rightPhysType, leftExpression, rightExpression);
            }
            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
        return new MycatSQLTableLookup(getCluster(), traitSet, inputs.get(0), right, joinType, joinCondition, correlationIds, type);
    }

    public static Observable<Object[]> dispatchRightObservable(NewMycatDataContext context, MycatSQLTableLookup tableLookup, Observable<Object[]> leftInput) {
        MycatView rightView = (MycatView) tableLookup.getRight();
        CopyMycatRowMetaData rightRowMetaData = new CopyMycatRowMetaData(new CalciteRowMetaData(rightView.getRowType().getFieldList()));
        AsyncMycatDataContextImpl.SqlMycatDataContextImpl sqlMycatDataContext = (AsyncMycatDataContextImpl.SqlMycatDataContextImpl) context;

        Observable<@NonNull List<Object[]>> buffer;
        if (tableLookup.getType() == Type.BACK) {//semi 可以分解
            buffer = leftInput.buffer(300, 50);
        } else {//NONE 该运算不能分解
            buffer = leftInput.toList().toObservable();
        }
        Observable<Object[]> rightObservable = buffer.flatMap(argsList -> {
            if (argsList.isEmpty()) {
                return Observable.empty();
            }
            JoinInfo joinInfo = JoinInfo.of(tableLookup.getInput(), tableLookup.getRight(), tableLookup.getJoinCondition());
            List<String> rightFieldNames = RelOptUtil.findAllTables(rightView.getRelNode()).get(0).getRowType().getFieldNames();
            RexShuttle rexShuttle = argSolver(joinInfo, argsList);
            RelNode mycatInnerRelNode = rightView.getRelNode().accept(new RelShuttleImpl() {
                @Override
                public RelNode visit(RelNode other) {
                    if (other instanceof Filter) {
                        Filter filter = (Filter) other;
                        return filter.accept(rexShuttle);
                    } else if (other instanceof MycatTableLookupValues) {
                        MycatTableLookupValues mycatTableLookupValues = (MycatTableLookupValues) other;
                        return mycatTableLookupValues.apply(argsList);
                    }
                    return super.visit(other);
                }
            });
            MycatView newRightView = new MycatView(rightView.getTraitSet(), mycatInnerRelNode, rightView.getDistribution(),
                    rightView.getCondition().map(c -> c.accept(rexShuttle)).orElse(null));
            DrdsSqlWithParams drdsSql = context.getDrdsSql();
            SqlNode sqlTemplate = newRightView.getSQLTemplate(DrdsSqlWithParams.isForUpdate(drdsSql.getParameterizedSQL()));
            ImmutableMultimap<String, SqlString> apply1 = newRightView.apply(context.getContext().getMergeUnionSize(), sqlTemplate, sqlMycatDataContext.getSqlMap(Collections.emptyMap(), newRightView, drdsSql, drdsSql.getHintDataNodeFilter()), drdsSql.getParams());
            return Observable.merge(sqlMycatDataContext.getObservables(apply1, rightRowMetaData,
                    (sessionConnection, sql, extractParams, calciteRowMetaData) -> VertxExecuter.runQuery(sessionConnection, sql, extractParams, calciteRowMetaData)
            ));
        });
        return rightObservable;
    }

    @NotNull
    private static RexShuttle argSolver(JoinInfo joinInfo, List<Object[]> argsList) {
        return new RexShuttle() {
            @Override
            public void visitEach(Iterable<? extends RexNode> exprs) {
                super.visitEach(exprs);
            }

            @Override
            public List<RexNode> visitList(Iterable<? extends RexNode> exprs) {
                return super.visitList(exprs);
            }

            @Override
            public RexNode visitCorrelVariable(RexCorrelVariable variable) {
                return super.visitCorrelVariable(variable);
            }

            @Override
            public RexNode visitDynamicParam(RexDynamicParam dynamicParam) {
                return super.visitDynamicParam(dynamicParam);
            }

            @Override
            public RexNode visitCall(RexCall call) {
                RexBuilder rexBuilder = MycatCalciteSupport.RexBuilder;
                if (call.getOperator() == MYCAT_SQL_LOOKUP_IN) {
                    List<RexNode> operands = call.getOperands();
                    RexCall exprRow = (RexCall) operands.get(0);
                    Set<Integer> needColumnSet = exprRow.getOperands().stream().map(i -> ((RexInputRef) i).getIndex()).collect(Collectors.toSet());
                    List<Integer> needValueList = joinInfo.pairs().stream()
                            .filter(intPair -> needColumnSet.contains(intPair.target))
                            .map(intPair -> intPair.target).collect(Collectors.toList());
                    List<List<RexLiteral>> rowList = new ArrayList<>();
                    for (Object[] args : argsList) {
                        List<RexLiteral> row = needValueList.stream()
                                .map(i -> args[i])
                                .map(o -> {
                                    if (o == null){
                                        return (RexLiteral)MycatCalciteSupport.RexBuilder.makeNullLiteral(SqlTypeName.VARCHAR);
                                    }
                                    return MycatCalciteSupport.RexBuilder.makeLiteral(Optional.of(o).map(o1 -> {
                                        if (o1 instanceof LocalDateTime) {
                                            return java.sql.Timestamp.valueOf((LocalDateTime) o1).toString();
                                        }
                                        return o1.toString();
                                    }).orElse(null));
                                })
                                .collect(Collectors.toList());
                        rowList.add(row);
                    }
                    if (rowList.size() == 1) {
                        ArrayList<RexNode> ands = new ArrayList<>();
                        for (int i = 0; i < exprRow.getOperands().size(); i++) {
                            RexNode right = rowList.get(0).get(i);
                            RexNode left = exprRow.getOperands().get(i);
                            ands.add(MycatCalciteSupport.RexBuilder
                                    .makeCall(SqlStdOperatorTable.EQUALS, left, MycatCalciteSupport.RexBuilder.makeCast(left.getType(), right)));
                        }
                        if (ands.size() == 1) {
                            return ands.get(0);
                        }
                        return rexBuilder.makeCall(SqlStdOperatorTable.AND, ands);
                    }
                    List<RexNode> rowRexNodeList = new ArrayList<>();
                    for (List<RexLiteral> rexLiterals : rowList) {
                        RexNode rexNode = rexBuilder.makeCall(SqlStdOperatorTable.ROW, rexLiterals);
                        rowRexNodeList.add(rexNode);
                    }
                    return rexBuilder.makeIn(exprRow, rowRexNodeList);
                }
                return super.visitCall(call);
            }
        };
    }

    class MycatValueArgFinder extends SqlShuttle {
        List<Object[]> params;

        public MycatValueArgFinder(List<Object[]> params) {
            this.params = params;
        }

        @Override
        public SqlNode visit(SqlCall call) {
            List<SqlNode> operandList;
            SqlNode rightRow;
            SqlNode leftRow;
            LinkedList<SqlNode> sqlNodes = new LinkedList<>();
            if (call.getOperator() == MycatImplementor.MYCAT_SQL_VAULES || call.getOperator() == MycatImplementor.MYCAT_SQL_LOOKUP_IN) {
                operandList = call.getOperandList();
                rightRow = operandList.get(0);
                leftRow = operandList.get(1);
                sqlNodes.add(rightRow);

                for (Object[] param : params) {
                    sqlNodes.add(leftRow.accept(new SqlShuttle() {
                        @Override
                        public SqlNode visit(SqlDynamicParam sqlDynamicParam) {
                            Object object = param[sqlDynamicParam.getIndex()];
                            if (object == null) {
                                return SqlLiteral.createNull(SqlParserPos.ZERO);
                            } else {
                                return SqlLiteral.createCharString(object.toString(), SqlParserPos.ZERO);
                            }
                        }
                    }));
                }
                return new SqlBasicCall(call.getOperator(), sqlNodes.toArray(new SqlNode[]{}), SqlParserPos.ZERO);
            }
            return super.visit(call);
        }
    }

    ;

    private Result implementHashJoin(EnumerableRelImplementor implementor, Prefer pref, BlockBuilder builder, final PhysType leftPhysType, final PhysType rightPhysType,
                                     Expression leftExpression,
                                     Expression rightExpression) {
        RelNode left = input;
        JoinInfo joinInfo = JoinInfo.of(left, right, joinCondition);
        leftExpression =
                toEnumerate(leftExpression);
        rightExpression =
                toEnumerate(rightExpression);
        final PhysType physType =
                PhysTypeImpl.of(
                        implementor.getTypeFactory(), getRowType(), pref.preferArray());
        final PhysType keyPhysType =
                leftPhysType.project(
                        joinInfo.leftKeys, JavaRowFormat.LIST);
        Expression predicate = Expressions.constant(null);
        if (!joinInfo.nonEquiConditions.isEmpty()) {
            RexNode nonEquiCondition = RexUtil.composeConjunction(
                    getCluster().getRexBuilder(), joinInfo.nonEquiConditions, true);
            if (nonEquiCondition != null) {
                predicate = EnumUtils.generatePredicate(implementor, getCluster().getRexBuilder(),
                        left, right, leftPhysType, rightPhysType, nonEquiCondition);
            }
        }
        return implementor.result(
                physType,
                builder.append(
                                Expressions.call(
                                        leftExpression,
                                        BuiltInMethod.HASH_JOIN.method,
                                        Expressions.list(
                                                        rightExpression,
                                                        leftPhysType.generateAccessor(joinInfo.leftKeys),
                                                        rightPhysType.generateAccessor(joinInfo.rightKeys),
                                                        EnumUtils.joinSelector(joinType,
                                                                physType,
                                                                ImmutableList.of(
                                                                        leftPhysType, rightPhysType)))
                                                .append(
                                                        Util.first(keyPhysType.comparer(),
                                                                Expressions.constant(null)))
                                                .append(
                                                        Expressions.constant(joinType.generatesNullsOnLeft()))
                                                .append(
                                                        Expressions.constant(
                                                                joinType.generatesNullsOnRight()))
                                                .append(predicate)))
                        .toBlock());
    }

    @Override
    public boolean isSupportStream() {
        return type == Type.NONE;
    }

    @Override
    public double estimateRowCount(RelMetadataQuery mq) {
        return super.estimateRowCount(mq);
    }
}
