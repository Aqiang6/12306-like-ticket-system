# -*- coding: utf-8 -*-
"""容灾验证：A 正常购票冒烟 / B 崩溃状态模拟恢复 + 幂等 / C 真实 kill 实例混沌
用法: python dr_verify.py A|B|C
"""
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

MYSQL = r'D:\mysql-9.2.0-winx64\bin\mysql.exe'
RUN = Path(r'D:\12306-main\test-run')
USERS_CSV = RUN / 'loadtest_users.csv'


def sql(q, fetch=True):
    p = subprocess.run([MYSQL, '--default-character-set=utf8mb4', '-uroot', '-p123456',
                        '-h127.0.0.1', '-D', '12306', '-N', '-B'],
                       input=q.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    out = p.stdout.decode('utf-8', errors='replace').strip()
    if p.returncode != 0:
        raise RuntimeError('mysql: %s' % p.stderr.decode('utf-8', errors='replace')[:300])
    if not fetch:
        return None
    return [line.split('\t') for line in out.splitlines() if line]


def purchase(user, port, timeout=90):
    body = {"trainId": "1", "departure": "北京南", "arrival": "杭州东",
            "passengers": [{"passengerId": user[3], "seatType": 2}]}
    headers = {'Content-Type': 'application/json', 'Authorization': user[2],
               'userId': user[1], 'username': user[0]}
    req = urllib.request.Request('http://localhost:%d/api/ticket-service/ticket/purchase/v2' % port,
                                 data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
                                 headers=headers, method='POST')
    try:
        r = urllib.request.urlopen(req, timeout=timeout)
        return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))
    except Exception as e:
        return {'code': 'NET', 'message': type(e).__name__ + ':' + str(e)[:80]}


def load_users():
    return [line.split(',') for line in USERS_CSV.read_text(encoding='utf-8').strip().splitlines()]


def free_users(n):
    blocked = {r[0] for r in sql(
        "SELECT DISTINCT username FROM t_ticket WHERE train_id=1 AND ticket_status IN (0,1,2)")}
    out = []
    for u in load_users():
        if u[0] not in blocked:
            out.append(u)
            if len(out) >= n:
                break
    return out


def free_seats(n):
    return sql(
        "SELECT s.carriage_number, s.seat_number FROM t_seat s WHERE s.train_id=1 AND s.seat_type=2 AND s.del_flag=0 "
        "AND NOT EXISTS (SELECT 1 FROM t_ticket t WHERE t.train_id=s.train_id "
        "AND t.carriage_number=s.carriage_number AND t.seat_number=s.seat_number "
        "AND t.ticket_status IN (0,1,2) AND t.del_flag=0 "
        "AND t.departure < '杭州东' AND t.arrival > '北京南') LIMIT %d;" % n)


def task_row(token):
    r = sql("SELECT task_status, retry_count, username FROM t_order_create_task WHERE purchase_token='%s';" % token)
    return r[0] if r else None


def order_count(username, since):
    total = 0
    for i in range(16):
        r = sql("SELECT COUNT(*) FROM t_order_%d WHERE username='%s' AND create_time >= '%s';"
                % (i, username, since))
        if r:
            total += int(r[0][0])
    return total


def now_db():
    return sql("SELECT NOW();")[0][0]


def wait_task_confirmed(token, timeout_s=60, want_status='1'):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        row = task_row(token)
        if row and row[0] == want_status:
            return row
        time.sleep(4)
    return task_row(token)


def test_a():
    user = free_users(1)[0]
    port = int(user[4])
    print('A: 用户 %s -> 端口 %d' % (user[0], port))
    since = now_db()
    t0 = time.time()
    resp = purchase(user, port)
    print('A: 购票响应 %s（%.1fs）' % (json.dumps(resp, ensure_ascii=False)[:200], time.time() - t0))
    assert resp.get('code') == '0', '购票失败: %s' % resp
    row = wait_task_confirmed(None, 0)  # placeholder
    tasks = sql("SELECT purchase_token, task_status, retry_count FROM t_order_create_task WHERE username='%s' ORDER BY id DESC LIMIT 1;" % user[0])
    assert tasks, '发件箱无记录'
    token, status, retry = tasks[0]
    print('A: 发件箱任务 %s status=%s retry=%s' % (token[:8] + '...', status, retry))
    row = wait_task_confirmed(token, 60)
    assert row and row[0] == '1', '任务未确认: %s' % row
    oc = order_count(user[0], since)
    tc = sql("SELECT COUNT(*) FROM t_ticket WHERE username='%s' AND ticket_status=0 AND train_id=1;" % user[0])[0][0]
    print('A: 通过 ✔ 订单数=%d 待支付票=%d（同步链路成功即确认，扫描器不再补偿）' % (oc, int(tc)))


def test_b():
    users = free_users(2)
    user = users[0]
    since = now_db()
    seats = free_seats(2)
    assert len(seats) == 2, '空闲座位不足'
    token = uuid.uuid4().hex
    ids = []
    vals = []
    for c, s in seats:
        vals.append("(1,'%s','%s','%s',0,'%s','%s',NOW(),NOW(),0)" % (c, s, user[3], '北京南', '杭州东'))
    sql("INSERT INTO t_ticket (train_id,carriage_number,seat_number,passenger_id,ticket_status,departure,arrival,create_time,update_time,del_flag) VALUES "
        + ','.join(vals) + ";")
    ids = []
    for c, s in seats:
        r = sql("SELECT id FROM t_ticket WHERE train_id=1 AND carriage_number='%s' AND seat_number='%s' "
                "AND ticket_status=0 AND departure='北京南' AND arrival='杭州东' ORDER BY id DESC LIMIT 1;" % (c, s))
        ids.append(r[0][0])
    prepare = {"trainId": "1", "departure": "北京南", "arrival": "杭州东", "trainNumber": "G35",
               "ticketIds": [int(i) for i in ids],
               "seatResults": [{"passengerId": user[3], "seatType": 2, "carriageNumber": seats[0][0],
                                "seatNumber": seats[0][1], "amount": None}],
               "purchaseToken": token}
    content = json.dumps(prepare, ensure_ascii=False).replace("'", "''")
    sql("INSERT INTO t_order_create_task (purchase_token,username,user_id,train_id,task_status,retry_count,next_retry_time,task_content,create_time,update_time,del_flag) "
        "VALUES ('%s','%s','%s',1,0,0,NOW(),'%s',NOW(),NOW(),0);" % (token, user[0], user[1], content))
    print('B: 已注入崩溃状态：账本票 %s + 任务 %s（无订单），等待扫描器+消费者补建…' % (ids, token[:8] + '...'))
    t0 = time.time()
    row = wait_task_confirmed(token, 75)
    took = time.time() - t0
    oc = order_count(user[0], since)
    assert row and row[0] == '1', '任务未确认: %s' % row
    assert oc >= 1, '任务已确认但订单未创建'
    print('B: 通过 ✔ %.0fs 内补建订单（订单数=%d，任务 retry=%s）' % (took, oc, row[1]))

    # 幂等验证：把任务重置为待确认，让扫描器重发同一令牌，订单服务必须拒绝重复建单
    sql("UPDATE t_order_create_task SET task_status=0, next_retry_time=NOW() WHERE purchase_token='%s';" % token)
    print('B: 已重置任务触发重发，验证令牌幂等…')
    row = wait_task_confirmed(token, 75)
    oc2 = order_count(user[0], since)
    assert row and row[0] == '1', '重发后任务未确认: %s' % row
    assert oc2 == oc, '出现重复订单！重发前=%d 重发后=%d' % (oc, oc2)
    print('B: 幂等通过 ✔ 重发后订单数仍为 %d' % oc2)


def test_c(burst=300, kill_after=1.5):
    import os
    users = free_users(burst)
    print('C: %d 个用户并发购票，%.1fs 后 kill 9002 实例…' % (len(users), kill_after))
    results = [None] * len(users)
    port9002_pid = None

    def work(idx):
        u = users[idx]
        results[idx] = purchase(u, int(u[4]), timeout=120)

    t0 = time.time()
    pool = ThreadPoolExecutor(max_workers=120)
    fut = pool.map(work, range(len(users)))
    watcher = threading.Thread(target=lambda: list(fut), daemon=True)
    watcher.start()
    time.sleep(kill_after)
    pids = subprocess.run(['netstat', '-ano'], capture_output=True).stdout.decode(errors='replace').splitlines()
    for line in pids:
        if 'LISTENING' in line and ':9002 ' in line:
            port9002_pid = line.split()[-1]
            break
    if port9002_pid:
        subprocess.run(['taskkill', '/F', '/PID', port9002_pid], capture_output=True)
        print('C: 已 kill 9002 实例 PID=%s（%.1fs）' % (port9002_pid, time.time() - t0))
    watcher.join(timeout=240)
    ok = [r for r in results if r and r.get('code') == '0']
    net_err = [r for r in results if r and r.get('code') == 'NET']
    biz_err = [r for r in results if r and r.get('code') not in ('0', 'NET')]
    print('C: 请求完成：成功 %d / 网络中断 %d / 业务失败 %d' % (len(ok), len(net_err), len(biz_err)))
    for r in biz_err[:3]:
        print('   业务失败样例:', json.dumps(r, ensure_ascii=False)[:150])

    ok_users = set()
    for i, r in enumerate(results):
        if r and r.get('code') == '0':
            ok_users.add(users[i][0])
    print('C: 等待 150s 让存活实例的扫描器补建崩溃窗口任务…')
    time.sleep(150)
    names = "','".join(u[0] for u in users)
    tasks = sql("SELECT task_status, COUNT(*) FROM t_order_create_task WHERE username IN ('%s') GROUP BY task_status;" % names)
    print('C: 发件箱任务状态分布: %s' % tasks)
    stuck = sql("SELECT purchase_token, username, retry_count FROM t_order_create_task WHERE username IN ('%s') AND task_status=0;" % names)
    assert not stuck, '仍有任务未收敛: %s' % stuck[:5]
    orders = {}
    for u in users:
        c = 0
        for i in range(16):
            r = sql("SELECT COUNT(*) FROM t_order_%d WHERE username='%s' AND create_time >= DATE_SUB(NOW(), INTERVAL 20 MINUTE);" % (i, u[0]))
            if r:
                c += int(r[0][0])
        orders[u[0]] = c
    dups = {k: v for k, v in orders.items() if v > 1}
    recovered = [u[0] for u in users if orders[u[0]] >= 1 and u[0] not in ok_users]
    missing_order = [u[0] for u, cnt in orders.items() if cnt == 0 and u[0] in ok_users]
    print('C: 成功响应 %d 人均有订单 ✔；非成功响应但有订单（崩溃恢复）%d 人；重复订单 %d 人；成功响应但无订单 %d 人'
          % (len(ok_users), len(recovered), len(dups), len(missing_order)))
    if dups:
        print('   重复订单样例:', list(dups.items())[:5])
    if missing_order:
        print('   成功但无订单样例:', missing_order[:5])
    assert not dups, '存在重复订单'
    assert not missing_order, '存在成功响应但无订单的用户'
    print('C: 通过 ✔ 无重复、无丢单，全部任务收敛')


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'A':
        test_a()
    elif cmd == 'B':
        test_b()
    elif cmd == 'C':
        test_c()
    else:
        print('usage: A|B|C')
