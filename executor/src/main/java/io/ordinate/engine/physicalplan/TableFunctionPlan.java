/*
 *     Copyright (C) <2021>  <Junwen Chen>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.ordinate.engine.physicalplan;

import io.ordinate.engine.builder.SchemaBuilder;
import io.ordinate.engine.function.Function;
import io.ordinate.engine.record.FunctionSink;
import io.ordinate.engine.record.RecordSinkFactory;
import io.ordinate.engine.record.RootContext;
import io.reactivex.rxjava3.core.Observable;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import java.util.Collections;
import java.util.List;

public class TableFunctionPlan implements PhysicalPlan {
    final Function[] functions;
    final Schema schema;
    final FunctionSink functionSink;

    public static TableFunctionPlan create(Function[] functions) {
        return new TableFunctionPlan(functions);
    }

    public TableFunctionPlan(Function[] functions) {
        this.functions = functions;

        schema = SchemaBuilder.of(functions);
        functionSink = RecordSinkFactory.INSTANCE.buildFunctionSink(functions);
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public List<PhysicalPlan> children() {
        return Collections.emptyList();
    }

    @Override
    public Observable<VectorSchemaRoot> execute(RootContext rootContext) {
        VectorSchemaRoot vectorSchemaRoot = rootContext.getVectorSchemaRoot(schema, 1);
        functionSink.copy(functions, null, 0, vectorSchemaRoot);
        return Observable.fromArray(vectorSchemaRoot);
    }

    @Override
    public void accept(PhysicalPlanVisitor physicalPlanVisitor) {

    }

    @Override
    public void eachFree(VectorSchemaRoot vectorSchemaRoot) {
        vectorSchemaRoot.close();
    }
}
