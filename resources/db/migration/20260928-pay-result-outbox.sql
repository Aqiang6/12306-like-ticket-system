-- 在每个支付物理库执行；聚合部署则在聚合库执行。先迁移，再发布应用。
-- 可重复运行。历史支付单保持 notification_status=0，不自动重放历史支付事件。
DROP PROCEDURE IF EXISTS migrate_pay_result_outbox;
DELIMITER $$
CREATE PROCEDURE migrate_pay_result_outbox()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE table_name_value VARCHAR(64);
    DECLARE tables_cursor CURSOR FOR
        SELECT TABLE_NAME FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE() AND (TABLE_NAME = 't_pay' OR TABLE_NAME REGEXP '^t_pay_[0-9]+$');
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
    OPEN tables_cursor;
    read_tables: LOOP
        FETCH tables_cursor INTO table_name_value;
        IF done THEN LEAVE read_tables; END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = table_name_value AND COLUMN_NAME = 'notification_status') THEN
            SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ',
                'ADD COLUMN notification_status TINYINT NOT NULL DEFAULT 0, ',
                'ADD COLUMN notification_payload TEXT NULL, ',
                'ADD COLUMN notification_next_retry DATETIME(3) NULL, ',
                'ADD INDEX idx_pay_notification (notification_status, notification_next_retry)');
            PREPARE migration_statement FROM @ddl;
            EXECUTE migration_statement;
            DEALLOCATE PREPARE migration_statement;
        END IF;
    END LOOP;
    CLOSE tables_cursor;
END$$
DELIMITER ;
CALL migrate_pay_result_outbox();
DROP PROCEDURE migrate_pay_result_outbox;
