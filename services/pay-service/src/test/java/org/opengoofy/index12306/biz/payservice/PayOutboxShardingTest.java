/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.payservice;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.junit.jupiter.api.Test;
import org.opengoofy.index12306.biz.payservice.dao.algorithm.PayDataBaseComplexAlgorithm;
import org.opengoofy.index12306.biz.payservice.dao.algorithm.PayTableComplexAlgorithm;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PayOutboxShardingTest {
    @Test
    void pendingScanWithoutOrderNumberVisitsAllPhysicalShards() {
        Properties props = new Properties();
        props.setProperty("sharding-count", "32");
        props.setProperty("table-sharding-count", "16");
        PayDataBaseComplexAlgorithm databases = new PayDataBaseComplexAlgorithm();
        databases.init(props);
        PayTableComplexAlgorithm tables = new PayTableComplexAlgorithm();
        tables.init(props);
        ComplexKeysShardingValue<Long> scan = new ComplexKeysShardingValue<>("t_pay", Map.of(), Map.of());
        assertEquals(List.of("ds_0", "ds_1"), databases.doSharding(List.of("ds_0", "ds_1"), scan));
        assertEquals(List.of("t_pay_0", "t_pay_15"), tables.doSharding(List.of("t_pay_0", "t_pay_15"), scan));
    }
}
