/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.ordinate.engine.function.constant;

import io.ordinate.engine.function.*;
import io.ordinate.engine.record.Record;
import org.apache.calcite.avatica.util.ByteString;

public final class BinaryConstant extends BinarySequenceFunction  {
    BinarySequence binarySequence;

    public BinaryConstant(BinarySequence binarySequence) {
        this.binarySequence = binarySequence;
    }
    public BinaryConstant(String binarySequence) {
        this.binarySequence = new BinarySequenceImpl(ByteString.ofBase64(binarySequence).getBytes());
    }

    public static Function newInstance(String value) {
        return new BinaryConstant(value);
    }

    @Override
    public BinarySequence getBinary(Record rec) {
        return binarySequence;
    }

    @Override
    public boolean isNull(Record rec) {
        return binarySequence == null;
    }
}
