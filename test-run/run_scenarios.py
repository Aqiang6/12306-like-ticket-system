# -*- coding: utf-8 -*-
"""多场景区间复用压测驱动。

每个场景自动执行完整闭环（压完立即关单，不等 RocketMQ 自动关单）：
  座位重置(seats) → 区间分配(routes) → 缓存预热(warmup) → JMeter 压测
  → 成交核对(verify，对照理论座位容量上限) → 立即关单(close) → 恢复核对(verify post)

理论成交上限 = 1000 座 × 每张票占用的站段数倒数：
  seg    每人一站   → 1000 × 4 站段 = 4000
  half   每人半程   → 1000 × 2 站段 = 2000
  full   每人全程   → 1000 × 1 站段 = 1000
  random 每人随机买 → 1000 ~ 1000 × 4 之间

用法: python run_scenarios.py [seg,half,full,random[,even]]
"""
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

if sys.platform == 'win32':
    sys.stdout.reconfigure(encoding='utf-8')

RUN = Path(__file__).resolve().parent
JMETER = r'D:\apache-jmeter-5.6.3\bin\jmeter.bat'
DEFAULT_SCENARIOS = 'seg,half,full,random'


def sh(cmd, timeout=3600):
    print('\n$ %s' % cmd, flush=True)
    proc = subprocess.run(cmd, shell=True, cwd=str(RUN), capture_output=True,
                          text=True, encoding='utf-8', errors='replace', timeout=timeout)
    out = (proc.stdout or '') + (proc.stderr or '')
    print('\n'.join(out.strip().splitlines()[-10:]), flush=True)
    if proc.returncode != 0:
        raise RuntimeError('命令失败(%s): %s' % (proc.returncode, cmd))
    return out


def parse_result(out, scenario, phase):
    m = re.search(r'^RESULT scenario=%s phase=%s (.+)$' % (re.escape(scenario), phase), out, re.M)
    if not m:
        raise RuntimeError('缺少 RESULT 行（%s/%s）' % (scenario, phase))
    return dict(kv.split('=', 1) for kv in m.group(1).split())


def run_scenario(sc):
    jtl = RUN / ('loadtest_result_%s.jtl' % sc)
    report = RUN / ('jmeter_report_scenario_%s' % sc)
    if jtl.exists():
        jtl.unlink()
    if report.exists():
        shutil.rmtree(report)
    sh('python loadtest_setup.py seats')
    sh('python loadtest_setup.py routes %s' % sc)
    sh('python loadtest_setup.py warmup')
    t0 = time.time()
    sh('"%s" -n -t "%s" -l "%s" -e -o "%s"' % (JMETER, RUN / 'loadtest_segments.jmx', jtl, report))
    took = time.time() - t0
    pre = parse_result(sh('python loadtest_setup.py verify %s' % sc), sc, 'pre')
    sh('python loadtest_setup.py close')
    post = parse_result(sh('python loadtest_setup.py verify %s post' % sc), sc, 'post')
    return pre, post, took


def main():
    wanted = (sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SCENARIOS).split(',')
    summary = []
    for sc in wanted:
        print('\n' + '=' * 28 + ' 场景 %s ' % sc + '=' * 28, flush=True)
        try:
            pre, post, took = run_scenario(sc)
            summary.append((sc, pre, post, took))
        except Exception as exc:
            print('场景 %s 执行失败: %s' % (sc, exc), flush=True)
            summary.append((sc, None, None, 0))
    print('\n' + '=' * 28 + ' 汇总 ' + '=' * 28)
    print('场景    | 成交   | 理论上限 | 达成率  | 复用率 | 超卖 | 位图不一致 | 关单恢复 | 结果')
    for sc, pre, post, took in summary:
        if pre is None:
            print('%-7s | 执行失败' % sc)
            continue
        sold, mx = int(pre['sold']), int(pre['max'])
        rate = ('%.1f%%' % (sold * 100.0 / mx)) if mx else '-'
        recovered = post is not None and post['sold'] == '0' and post['recover_bad'] == '0'
        print('%-7s | %-6s | %-8s | %-7s | %-6s | %-4s | %-10s | %-8s | %s (%.0fs)'
              % (sc, pre['sold'], pre['max'], rate, pre['reuse'], pre['oversell'],
                 pre['inconsistent'], '是' if recovered else '否', pre['verdict'], took))
    failed = [sc for sc, pre, _, _ in summary if pre is None or pre['verdict'] != 'PASS']
    if failed:
        print('未通过场景: %s' % ','.join(failed))
        sys.exit(1)
    print('全部场景通过 ✔')


if __name__ == '__main__':
    main()
