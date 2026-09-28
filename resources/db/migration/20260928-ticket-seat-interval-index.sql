-- 支持按车次、车厢、座位和有效票状态查询区间占用；在已有票务库执行一次。
ALTER TABLE t_ticket
    DROP INDEX idx_train_carriage_seat,
    ADD INDEX idx_train_carriage_seat (train_id, carriage_number, seat_number, ticket_status, del_flag);
