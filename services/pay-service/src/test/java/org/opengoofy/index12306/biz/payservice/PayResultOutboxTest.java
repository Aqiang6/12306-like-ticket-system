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

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.opengoofy.index12306.biz.payservice.dao.mapper.PayMapper;
import org.opengoofy.index12306.biz.payservice.dto.PayCallbackReqDTO;
import org.opengoofy.index12306.biz.payservice.mq.event.PayResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.payservice.mq.produce.PayResultCallbackOrderSendProduce;
import org.opengoofy.index12306.biz.payservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.payservice.service.PayService;
import org.opengoofy.index12306.biz.payservice.service.impl.PayServiceImpl;
import org.opengoofy.index12306.biz.payservice.service.task.PayResultOutboxDispatcher;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PayResultOutboxTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private PayService service;
    private PayMapper mapper;
    private PayResultCallbackOrderSendProduce producer;
    private PayResultOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("""
                CREATE TABLE t_pay (
                  id BIGINT PRIMARY KEY, order_sn VARCHAR(64), pay_sn VARCHAR(64), out_order_sn VARCHAR(64),
                  channel INT, trade_type INT, subject VARCHAR(512), trade_no VARCHAR(256),
                  order_request_id VARCHAR(64), total_amount INT, gmt_payment TIMESTAMP, pay_amount INT,
                  status INT, create_time TIMESTAMP, update_time TIMESTAMP, del_flag INT DEFAULT 0,
                  notification_status INT NOT NULL DEFAULT 0, notification_payload CLOB,
                  notification_next_retry TIMESTAMP)
                """);
        jdbc.update("INSERT INTO t_pay(id, order_sn, status, total_amount) VALUES(1, 'order-1', 0, 100)");
        service = context.getBean(PayService.class);
        mapper = context.getBean(PayMapper.class);
        producer = mock(PayResultCallbackOrderSendProduce.class);
        dispatcher = new PayResultOutboxDispatcher(mapper, producer);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private PayCallbackReqDTO callback() {
        return PayCallbackReqDTO.builder().orderSn("order-1").status(20).payAmount(100)
                .tradeNo("trade-1").gmtPayment(new Date(1_700_000_000_000L)).build();
    }

    private int value(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM t_pay WHERE id=1", Integer.class);
    }

    @Test
    void rolledBackPaymentCannotBeSentEvenByConcurrentScanner() {
        TransactionTemplate tx = new TransactionTemplate(context.getBean(FailingCommitManager.class));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            service.callbackPay(callback());
            assertEquals(1, value("notification_status"));
            // 扫描器用另一个连接，必须看不到未提交的支付事件。
            CompletableFuture.runAsync(dispatcher::dispatchPending).join();
            throw new IllegalStateException("rollback");
        }));
        dispatcher.dispatchPending();
        assertEquals(0, value("status"));
        assertEquals(0, value("notification_status"));
        verifyNoInteractions(producer);
    }

    @Test
    void commitFailureCannotPublishPayment() {
        context.getBean(FailingCommitManager.class).failCommit = true;
        assertThrows(TransactionSystemException.class, () -> service.callbackPay(callback()));
        dispatcher.dispatchPending();
        assertEquals(0, value("status"));
        assertEquals(0, value("notification_status"));
        verifyNoInteractions(producer);
    }

    @Test
    void committedPaymentSurvivesRestartAndFailedSend() {
        service.callbackPay(callback());
        verifyNoInteractions(producer);
        assertEquals(20, value("status"));
        when(producer.sendMessage(any())).thenThrow(new IllegalStateException("MQ unavailable"));
        dispatcher.dispatchPending();
        assertEquals(1, value("notification_status"));
        dispatcher.dispatchPending();
        verify(producer, times(1)).sendMessage(any());

        // 退款修改支付状态后，待发送事件仍必须保持原支付成功快照。
        jdbc.update("UPDATE t_pay SET status=40, notification_next_retry=?", new Date(0));
        SendResult success = new SendResult();
        success.setSendStatus(SendStatus.SEND_OK);
        reset(producer);
        when(producer.sendMessage(any())).thenReturn(success);
        new PayResultOutboxDispatcher(mapper, producer).dispatchPending();
        assertEquals(2, value("notification_status"));
        var event = org.mockito.ArgumentCaptor.forClass(PayResultCallbackOrderEvent.class);
        verify(producer).sendMessage(event.capture());
        assertEquals("20", event.getValue().getStatus());
        assertEquals("trade-1", event.getValue().getTradeNo());

        service.callbackPay(callback());
        dispatcher.dispatchPending();
        assertEquals(40, value("status"));
        verifyNoMoreInteractions(producer);
    }

    @Test
    void nonOkBrokerAcknowledgementIsNotMarkedSent() {
        service.callbackPay(callback());
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        doReturn(result).when(producer).sendMessage(any());
        dispatcher.dispatchPending();
        assertEquals(1, value("notification_status"));
    }

    @Test
    void failureAfterSendIsRetriedFromDurableRecord() {
        service.callbackPay(callback());
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(producer.sendMessage(any())).thenAnswer(call -> {
            // 模拟发送已成功，但进程在写确认前失败。
            throw new IllegalStateException("crashed after broker accepted message");
        });
        dispatcher.dispatchPending();
        jdbc.update("UPDATE t_pay SET notification_next_retry=?", new Date(0));
        doReturn(result).when(producer).sendMessage(any());
        new PayResultOutboxDispatcher(mapper, producer).dispatchPending();
        verify(producer, times(2)).sendMessage(any());
        assertEquals(2, value("notification_status"));
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = PayMapper.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return ds;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            return factory.getObject();
        }

        @Bean
        FailingCommitManager transactionManager(DataSource ds) {
            return new FailingCommitManager(ds);
        }

        @Bean
        PayService payService(PayMapper mapper) {
            return new PayServiceImpl(mapper, mock(AbstractStrategyChoose.class),
                    mock(TicketOrderRemoteService.class), mock(DistributedCache.class));
        }
    }

    static class FailingCommitManager extends DataSourceTransactionManager {
        boolean failCommit;

        FailingCommitManager(DataSource ds) {
            super(ds);
            setRollbackOnCommitFailure(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("simulated commit failure");
            }
            super.doCommit(status);
        }
    }
}
