#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""阶段②收尾：消掉「JS 浮点除法 vs Java 整除」的潜在隐患。

背景：NightwatchModule / PhantomModule 的加载日志里有 12 处
`(XXX_MS / 1000)`。JS 里 `/` 恒为浮点除法，Java 里两侧都是整型就是**截断除法**。
这些常量目前**都是 1000 的倍数**，所以今天的行为是对的 —— 但常量一旦改成
7500，Java 会打出 "7"、JS 会打出 "7.5"，而这类差异不会报错、只会让日志对不上。

★ 为什么不能简单地把 `/ 1000` 改成 `/ 1000.0`：
  Java 里 `"" + 7.0` 是 **"7.0"**，而 JS 里 `"" + (7000/1000)` 是 **"7"**。
  直接改成浮点除法会**当场把日志打坏**（"7.0 秒" 而不是 "7 秒"）。
  正确做法是让结果经过「JS 数字→字符串」的转换：
    `(X / 1000)`  →  `helper(X / 1000.0)`
  当前常量下两者逐字节相同（helper(7.0) == "7"），常量改了也不会错。

用法: python tools/fix_int_division.py [--apply]
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'qinghuacisj', 'gs', 'script', 'module')

# 文件 → 该文件里「JS 数字转字符串」的 helper 名
FILES = {
    os.path.join(MOD, 'NightwatchModule.java'): 'nwNum',
    os.path.join(MOD, 'PhantomModule.java'): 'jsNum',
}

# (X / 1000)  其中 X 是常量名（大写+下划线/数字）
PAT = re.compile(r'\((\s*)([A-Z][A-Z0-9_]*)(\s*/\s*)1000(\s*)\)')


def read(p):
    with io.open(p, 'r', encoding='utf-8', newline='') as f:
        return f.read()


def write(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)


def main():
    apply = '--apply' in sys.argv
    print(u'=== 消掉 JS 浮点除法 / Java 整除 的隐患 ===')
    print(u'模式: %s' % (u'★ APPLY（真改）' if apply else u'dry run（只打印）'))
    print(u'')
    total = 0
    for path, helper in FILES.items():
        text = read(path)
        name = os.path.basename(path)
        out = []
        pos = 0
        n = 0
        for m in PAT.finditer(text):
            sp1, const, sp2, sp3 = m.group(1), m.group(2), m.group(3), m.group(4)
            repl = u'%s(%s / 1000.0)' % (helper, const)
            out.append(text[pos:m.start()])
            out.append(repl)
            pos = m.end()
            n += 1
            print(u'  %-22s %-28s → %s' % (name, m.group(0), repl))
        out.append(text[pos:])
        new = u''.join(out)
        total += n
        if apply and n:
            write(path, new)
    print(u'')
    print(u'共 %d 处。' % total)
    if not apply:
        print(u'（dry run，未写入。加 --apply 才真改）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
