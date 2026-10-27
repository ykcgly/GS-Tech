#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校验 `docs/gs_listener_拆分契约.md` 里列的每一行日志，是否真的出现在
`docs/参考服日志_构思技艺.txt` 里（或至少是它的合法前缀 —— 参考服日志有长度上限会截断）。

★ 为什么需要它：拆分契约里那些「照抄这行」的日志文本是**我手抄的**，
  手抄就会出错（已经抓到一次：残樱那行漏了右括号，还配了句「原样就是这样，照抄」）。
  这个脚本把「手抄」这一步变成机械可查的。

用法：
    python tools/check_split_contract_lines.py
退出码 0 = 全绿。
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONTRACT = os.path.join(ROOT, 'docs', u'gs_listener_拆分契约.md')
REFLOG = os.path.join(ROOT, 'docs', u'参考服日志_构思技艺.txt')

PREFIX = u'[构思技艺]'


def load_ref():
    out = []
    with io.open(REFLOG, 'r', encoding='utf-8') as f:
        for line in f:
            i = line.find(PREFIX)
            if i >= 0:
                out.append(line[i:].rstrip())
    return out


def extract_contract_lines():
    """从拆分契约里抽出所有以 [构思技艺] 开头的行（在代码块里）。

    返回 [(行号, 文本)]。跳过「更正记录」那种引用块里的行。
    """
    out = []
    in_block = False
    in_quote = False
    with io.open(CONTRACT, 'r', encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            s = line.rstrip()
            if s.strip().startswith(u'```'):
                in_block = not in_block
                continue
            if s.strip().startswith(u'>'):
                in_quote = True
                continue
            in_quote = False
            if not in_block or in_quote:
                continue
            if s.startswith(PREFIX):
                out.append((n, s))
    return out


def main():
    ref = load_ref()
    lines = extract_contract_lines()
    print(u'拆分契约里的日志行 %d 条 ｜ 参考服日志 %d 行' % (len(lines), len(ref)))
    print(u'')

    ok = 0
    fail = 0
    for n, text in lines:
        exact = text in ref
        # 契约里用「…」标出「这行太长、我截断了」⇒ 剥掉它再按前缀比对
        stripped = text[:-1].rstrip() if text.endswith(u'…') else text
        truncated_marker = text.endswith(u'…')
        if exact:
            ok += 1
            print(u'  OK   L%-4d 逐字节命中   %s' % (n, text[:76]))
        elif truncated_marker and any(r.startswith(stripped) for r in ref):
            ok += 1
            print(u'  OK   L%-4d 契约标了「…」，剥掉后命中参考行前缀  %s' % (n, stripped[:60]))
        elif any(r.startswith(text) or text.startswith(r) for r in ref):
            ok += 1
            print(u'  OK   L%-4d 命中前缀（参考服日志截断） %s' % (n, text[:60]))
        else:
            fail += 1
            print(u'  失败 L%-4d 参考服日志里找不到: %s' % (n, text))
            # 找出最接近的一行，帮人定位
            best = None
            bestscore = 0
            for r in ref:
                m = 0
                for a, b in zip(text, r):
                    if a != b:
                        break
                    m += 1
                if m > bestscore:
                    bestscore = m
                    best = r
            if best is not None and bestscore > 10:
                print(u'       最接近的参考行（前 %d 字符相同）:' % bestscore)
                print(u'         %s' % best)
                print(u'       契约行在第 %d 字符处开始不同:' % (bestscore + 1))
                print(u'         契约: ...%s' % text[max(0, bestscore - 12):bestscore + 40])
                print(u'         参考: ...%s' % best[max(0, bestscore - 12):bestscore + 40])

    print(u'')
    print(u'通过 %d ｜ 失败 %d' % (ok, fail))
    return 1 if fail else 0


if __name__ == '__main__':
    sys.exit(main())
