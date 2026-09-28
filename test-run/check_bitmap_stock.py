# -*- coding: utf-8 -*-
"""Compare every test route's physical-seat bitmap with the SQL seat ledger."""
from loadtest_setup import FREE_SEAT_SQL, PAIRS, RedisMini, mysql_sql

LUA = """
local count = 0
for _, key in ipairs(KEYS) do
  for seat = 0, 107 do
    if seat % 6 ~= 4 then
      local free = true
      for segment = tonumber(ARGV[2]), tonumber(ARGV[3]) - 1 do
        if redis.call('GETBIT', key, seat * tonumber(ARGV[1]) + segment) ~= 0 then
          free = false
          break
        end
      end
      if free then count = count + 1 end
    end
  end
end
return count
"""

stations = [row[0] for row in mysql_sql(
    "SELECT departure FROM t_train_station WHERE train_id=1 ORDER BY id", True)]
carriages = [row[0] for row in mysql_sql(
    "SELECT carriage_number FROM t_carriage WHERE train_id=1 AND carriage_type=2", True)]
keys = [f"index12306-ticket-service:train_carriage_seat_status:1_{carriage}"
        for carriage in carriages]
redis = RedisMini()
missing = [key for key in keys if redis.cmd('EXISTS', key) == 0]
if missing:
    raise RuntimeError(f"Missing {len(missing)} bitmap keys: {missing[:5]}")
print('route | SQL | bitmap | display')
for departure, arrival, _price in PAIRS:
    sql = FREE_SEAT_SQL.replace("requested_start.departure='北京南'",
                                "requested_start.departure='%s'" % departure)
    sql = sql.replace("requested_end.arrival='杭州东'",
                      "requested_end.arrival='%s'" % arrival)
    sql_count = int(mysql_sql(sql, True)[0][0])
    bitmap_count = redis.cmd('EVAL', LUA, str(len(keys)), *keys,
                             str(len(stations)), str(stations.index(departure)), str(stations.index(arrival)))
    display_count = redis.cmd('HGET',
        'index12306-ticket-service:train_station_remaining_ticket:1_%s_%s' % (departure, arrival), '2')
    print('%s→%s | %s | %s | %s' % (departure, arrival, sql_count, bitmap_count, display_count))
    if sql_count != bitmap_count:
        raise RuntimeError('bitmap and SQL disagree for %s→%s' % (departure, arrival))
