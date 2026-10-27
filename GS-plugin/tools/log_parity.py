#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
开机日志对账 —— 把真服启动日志里的 [构思技艺] 行与参考服日志机械比对。

用法：
    python tools/log_parity.py <本次启动的 latest.log> [<参考服日志>]

默认参考服日志 = docs/参考服日志_构思技艺.txt

★ 为什么需要它：所有子模块的探针都只证明「自己的日志行逐字节对得上」，
  但**没有人**证明「全部模块合起来、在真服上跑出来的日志集合与参考服一致」。
  这个脚本补的就是那一步（集成者的活）。

判定规则：
  1. 本次日志里的 [构思技艺] 行，去掉时间戳/等级前缀后，**必须**能在参考服日志里
     找到一模一样的行 —— 除非它在「允许新增」名单里（见 EXTRA_OK）。
  2. 参考服日志里的每一行，**必须**在本次日志里出现 —— 除非它在「允许缺失」名单里
     （见 MISSING_OK，例如需要玩家实际操作的运行期行）。
  3. 允许参考服日志里的行被**截断**（RSC 日志有长度上限）：一行是另一行的前缀、
     且较短的那行 >= MIN_TRUNC 个字符时，算匹配。★ 门槛存在的意义：
     防止「两行都只有开头几个字一样」被误判成截断。
  4. 运行期动态值（世代号等）按 DYNAMIC 正则归一化后再比。

退出码：0 = 全绿，1 = 有差异。
"""
import io
import os
import re
import sys

PREFIX = u'[构思技艺]'

# 允许「一行是另一行的前缀」的最短长度（防误判）。
MIN_TRUNC = 40

# 本次日志里出现、但参考服日志里**没有** —— 允许的行（前缀匹配）。
# ★ 每一条都要写明理由；「懒得查」不是理由。
EXTRA_OK = [
    # 脚本已 Java 化后框架自己打的启动摘要（参考服是 RSC，没有这些行）
    u'脚本已 Java 化',
    u'构思技艺 (GS) 加载成功',
    # 原脚本里惰性注册 / 条件触发的日志，参考服那轮没跑到
    u'释梦 / 梦境套：永不掉耐久已启用',
    u'妄念：原版施法 / 爆炸 / 阵营拦截 已启用',
    u'碎念执：已清理',
]

# 参考服日志里有、但本次日志里**没有** —— 允许缺失的行（前缀匹配）。
MISSING_OK = [
    # 需要玩家实际操作 / 特定条件才会打的行
    u'梦境套：防辐射已生效',
    u'⚠ 拿不到 RadiationTask',
    # ★★ gs_guide_book 那一行是**点击期**的，不是加载期的。
    #    证据（三条，都是机械可查的）：
    #      ① 它在参考服日志里是**最后一行**（第 88 行），位置在
    #         「构思技艺 (GS) 加载成功！」（第 86 行）**之后** —— 加载期不可能打出它。
    #      ② 同为「只被 groups.yml 的 actions: - "script X" 引用」的
    #         gs_update_log，在参考服日志里**一次都没出现**（它同样有顶层 info 行）。
    #         ⇒ RSC 不是「把 scripts/ 全求值一遍」，而是**按需求值**。
    #      ③ 本条日志的 `+ GG_PAGES.length` 是运行期读数组长度，也符合点击期构造。
    #    ⇒ 结论：移植版保持惰性实例化是对的（曾试过预加载全部，会凭空多出
    #      `gs_update_log 已加载`，被本脚本当场否掉，已回退）。
    #      玩家点「构思技艺图鉴」按钮时，这一行照样会打出来。
    u'gs_guide_book 已加载',
]

# 运行期动态值 —— 归一化成占位符后再比。
# ★ 只对**确实每轮都不同**的值做归一化；别拿它掩盖真差异。
DYNAMIC = [
    # 「世界世代号 96」—— 世代号每 reload +1，参考服那次恰好是 96
    (re.compile(u'(世界世代号\\s+)\\d+'), u'\\1<N>'),
]


def norm(line):
    """去掉时间戳与日志等级前缀，只留 [构思技艺] 起的内容。"""
    i = line.find(PREFIX)
    if i < 0:
        return None
    s = line[i:].rstrip()
    s = re.sub(u'\u00a7.', u'', s)          # 去掉颜色码残留
    for pat, rep in DYNAMIC:
        s = pat.sub(rep, s)
    return s


def read_lines(path):
    """读文件里所有 [构思技艺] 行（保留出现顺序，不去重）。"""
    out = []
    with io.open(path, 'r', encoding='utf-8', errors='replace') as f:
        for raw in f:
            n = norm(raw)
            if n is not None:
                out.append(n)
    return out


def matches(a, b):
    """a 与 b 是否算同一行（完全相同，或有一方是另一方的合法截断）。"""
    if a == b:
        return True
    short, long_ = (a, b) if len(a) <= len(b) else (b, a)
    if len(short) < MIN_TRUNC:
        return False
    return long_.startswith(short)


def covered_by(needle, hay):
    for h in hay:
        if matches(needle, h):
            return True
    return False


def listed(line, prefixes):
    """line 是否命中 prefixes 里的某一条（前缀匹配）。

    ★★ 这里必须**两种写法都认**：既认「带 `[构思技艺] ` 前缀的整行」，
       也认「去掉前缀之后的正文」。原因：{@link norm} 把行规整成
       `[构思技艺] 正文`，所以 `EXTRA_OK` / `MISSING_OK` 里如果只写正文
       （例如 `gs_guide_book 已加载`），`line.startswith(p)` 永远为假 ——
       名单看着写了、实际是**死条目**。
       ★ 这个坑真的踩过：`MISSING_OK` 里最初那两条（`梦境套：防辐射已生效`
         与 `⚠ 拿不到 RadiationTask`）一直是死的，只是因为那两行在两边日志里
         都不出现、从不触发，才没暴露出来。加 `gs_guide_book 已加载` 时暴露了。
       现在两种写法都能用，且对已有条目是纯增强（不会让原本能过的行变红）。
    """
    body = line[len(PREFIX):] if line.startswith(PREFIX) else line
    body = body.lstrip()          # ★ PREFIX 不含尾空格 ⇒ 这里必须剥掉正文前的空格
    for p in prefixes:
        if line.startswith(p) or body.startswith(p):
            return True
    return False


def main():
    if len(sys.argv) < 2:
        sys.stderr.write(u'用法: python tools/log_parity.py <本次 latest.log> [<参考服日志>]\n')
        return 2

    got_path = sys.argv[1]
    ref_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        'docs', u'参考服日志_构思技艺.txt')

    got = read_lines(got_path)
    ref = read_lines(ref_path)

    print(u'本次日志 %d 行 ｜ 参考服日志 %d 行' % (len(got), len(ref)))
    print(u'')

    ok = 0
    fail = 0

    # ---- 方向 ①：本次有、参考没有（多余的行）----
    print(u'[1] 本次日志里的行是否都能在参考服日志里找到')
    extra = []
    for g in got:
        if covered_by(g, ref):
            ok += 1
        elif listed(g, EXTRA_OK):
            ok += 1
            print(u'      OK（允许新增） %s' % g[:88])
        else:
            fail += 1
            extra.append(g)
            print(u'      失败 多出来的行: %s' % g[:110])

    # ---- 方向 ②：参考有、本次没有（漏掉的行）----
    print(u'')
    print(u'[2] 参考服日志里的行是否都在本次日志里出现')
    missing = []
    for r in ref:
        if covered_by(r, got):
            ok += 1
        elif listed(r, MISSING_OK):
            ok += 1
            print(u'      OK（允许缺失） %s' % r[:88])
        else:
            fail += 1
            missing.append(r)
            print(u'      失败 漏掉的行: %s' % r[:110])

    print(u'')
    print(u'通过 %d ｜ 失败 %d' % (ok, fail))

    if extra:
        print(u'')
        print(u'★ 多出来的行（%d 条）—— 逐条判断是「框架新增」还是「移植多打了」：' % len(extra))
        for e in extra:
            print(u'    %s' % e)
    if missing:
        print(u'')
        print(u'★ 漏掉的行（%d 条）—— 逐条判断是「未移植」还是「允许缺失」：' % len(missing))
        for m in missing:
            print(u'    %s' % m)

    return 1 if fail else 0


if __name__ == '__main__':
    sys.exit(main())
