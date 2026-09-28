-- 移除不再支持的车次与席别售卖目录；历史订单和车票账本保留供对账。
UPDATE t_train
SET train_tag = TRIM(BOTH ',' FROM REPLACE(CONCAT(',', train_tag, ','), ',3,', ','))
WHERE FIND_IN_SET('3', train_tag) > 0;

UPDATE t_train SET del_flag = 1 WHERE train_type IS NULL OR train_type <> 0;

DELETE FROM t_seat
WHERE seat_type IS NULL OR seat_type NOT IN (0, 1, 2)
   OR train_id IN (SELECT id FROM t_train WHERE train_type IS NULL OR train_type <> 0);

DELETE FROM t_carriage
WHERE carriage_type IS NULL OR carriage_type NOT IN (0, 1, 2)
   OR train_id IN (SELECT id FROM t_train WHERE train_type IS NULL OR train_type <> 0);

DELETE FROM t_train_station_price
WHERE seat_type IS NULL OR seat_type NOT IN (0, 1, 2)
   OR train_id IN (SELECT id FROM t_train WHERE train_type IS NULL OR train_type <> 0);

ALTER TABLE t_train
    MODIFY train_tag varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL
    COMMENT '列车标签 0：复兴号 1：智能动车组 2：静音车厢';
