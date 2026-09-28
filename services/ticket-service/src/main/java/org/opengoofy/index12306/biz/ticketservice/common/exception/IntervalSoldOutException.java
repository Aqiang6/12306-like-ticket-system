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

package org.opengoofy.index12306.biz.ticketservice.common.exception;

import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;

/**
 * 区间售罄广播逐出异常：购票排队前/排队中观测到所需席别在当前区间的展示余票被刷新为 0。
 * 属预期业务拒绝（快手段），调用方据此与真实临界区故障区分开做降噪记录。
 */
public class IntervalSoldOutException extends ServiceException {

    public IntervalSoldOutException(String message) {
        super(message);
    }
}
