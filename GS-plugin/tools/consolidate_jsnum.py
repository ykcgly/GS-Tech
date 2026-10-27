#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""阶段②收尾：把各模块私有的 jsNum / toFixed 副本统一到 util/JsNum。

背景：18 个脚本分别移植时，多个模块各带一份 jsNum / toFixed 私有拷贝。
tools/JsNumConsolidationProbe.java 实测：13 份里有 10 份与 JsNum 分歧
（次正规数、< 1e-6、>= 1e21、以及 %.Nf 的 HALF_UP 边界）。
实际调用点的实参都在正常区间，所以分歧是**潜在**的，不是线上 bug ——
但统一之后既瘦身、又彻底消掉这一类隐患。

本脚本只改方法体（保留签名与 javadoc，调用点零改动），
并删掉因此变成死代码的私有辅助方法。

用法: python tools/consolidate_jsnum.py [--apply]
不带 --apply 时只打印将要做的改动（dry run）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'qinghuacisj', 'gs')

MOD = os.path.join(SRC, 'script', 'module')
SUB = os.path.join(MOD, 'listener')

# (文件, 方法名, 新方法体, 备注)
TARGETS = [
    (os.path.join(MOD, 'PhantomModule.java'), 'jsNum',
     'return JsNum.str(v);',
     '原实现用 shortestDecimal() 自己做最短往返搜索，与 JsNum 一致；一并删掉那个辅助方法'),
    (os.path.join(MOD, 'PhantomModule.java'), 'toFixed',
     'return JsNum.toFixed(v, f);',
     '原实现用 new BigDecimal(Math.abs(v)) + HALF_UP，与 JsNum 一致'),
    (os.path.join(MOD, 'ShadowModule.java'), 'jsNum',
     'return JsNum.str(v);',
     '★ 原实现是朴素版：< 1e-6 与 >= 1e21 会分歧'),
    (os.path.join(SUB, 'EmSub.java'), 'jsNum',
     'return JsNum.str(v);',
     '★ 原实现解析 Double.toString 重排，次正规数 (MIN_VALUE) 会分歧'),
    (os.path.join(SUB, 'PsychicSub.java'), 'jsNum',
     'return JsNum.str(v);',
     '★ 原实现是朴素版：< 1e-6 与 >= 1e21 会分歧'),
    (os.path.join(SUB, 'PsychicSub.java'), 'toFixed1',
     'return JsNum.toFixed(v, 1);',
     '★ 原实现是 %.1f（十进制 HALF_UP），1.15 这类会分歧'),
    (os.path.join(SUB, 'SiSub.java'), 'jsNum',
     'return JsNum.str(v);',
     '★ 原实现解析 Double.toString 重排，次正规数会分歧'),
    (os.path.join(SUB, 'VoSub.java'), 'jsNum',
     'return JsNum.str(v);',
     '★ 原实现解析 Double.toString 重排，次正规数会分歧'),
    (os.path.join(SUB, 'WlSub.java'), 'jsNum',
     'return JsNum.str(v);',
     '★ 原实现是朴素版：< 1e-6 与 >= 1e21 会分歧'),
    (os.path.join(SUB, 'WuliSub.java'), 'jsNum',
     'return JsNum.str(v);',
     '★ 原实现是朴素版：< 1e-6 与 >= 1e21 会分歧'),
    (os.path.join(SUB, 'WuliSub.java'), 'toFixed1',
     'return JsNum.toFixed(v, 1);',
     '★ 原实现是 %.1f，1.15 这类会分歧'),
    (os.path.join(SUB, 'CherrySub.java'), 'toFixed2',
     'return JsNum.toFixed(v, 2);',
     '★ 原实现是 %.2f，1.005 这类会分歧'),
    (os.path.join(MOD, 'NbsSong.java'), 'f1',
     'return JsNum.toFixed(v, 1);',
     '★ 原实现是 %.1f，HALF_UP 边界会分歧'),
    (os.path.join(MOD, 'NbsSong.java'), 'f0',
     'return JsNum.toFixed(v, 0);',
     '★ 原实现是 %.0f，HALF_UP 边界会分歧'),
]

# 统一后变成死代码的私有辅助方法（只删这些）
DEAD = [
    (os.path.join(MOD, 'PhantomModule.java'), 'shortestDecimal'),
]

NOTE = u'        // ★ 阶段②收尾：统一到 util/JsNum（原为本地副本，见 tools/JsNumConsolidationProbe.java）。\n'


def read(p):
    with io.open(p, 'r', encoding='utf-8', newline='') as f:
        return f.read()


def write(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)


def find_method(text, name):
    """定位 `[修饰符] 返回类型 <name>(...) {`，返回 (start, end_exclusive, params, body_start, close_start)。

    结束位置用「与签名同缩进 + }」判定 —— 这是本项目的排版约定，
    方法体内部的花括号缩进更深，所以不会误判。
    """
    pat = re.compile(r'^([ \t]*)(?:[\w@]+\s+)*[\w<>\[\],. ]+\s+' + re.escape(name) + r'\s*\(([^)]*)\)\s*\{[ \t]*\r?\n',
                     re.MULTILINE)
    m = pat.search(text)
    if not m:
        return None
    indent = m.group(1)
    params = m.group(2).strip()
    # 找结束行：与签名同缩进 + '}'
    close = re.compile(r'^' + re.escape(indent) + r'\}[ \t]*\r?\n', re.MULTILINE)
    cm = close.search(text, m.end())
    if not cm:
        raise RuntimeError(u'找不到 %s 的结束花括号' % name)
    return (m.start(), cm.end(), params, m.end(), cm.start())


def add_import(text):
    """确保有 import com.qinghuacisj.gs.util.JsNum;"""
    if re.search(r'^import\s+com\.qinghuacisj\.gs\.util\.JsNum\s*;', text, re.MULTILINE):
        return text, False
    lines = text.split('\n')
    last = -1
    for i, ln in enumerate(lines):
        if ln.startswith('import '):
            last = i
    if last < 0:
        raise RuntimeError(u'找不到 import 块')
    lines.insert(last + 1, 'import com.qinghuacisj.gs.util.JsNum;')
    return '\n'.join(lines), True


def main():
    apply = '--apply' in sys.argv
    print(u'=== 统一私有 jsNum / toFixed 副本到 util/JsNum ===')
    print(u'模式: %s' % (u'★ APPLY（真改）' if apply else u'dry run（只打印）'))
    print(u'')

    cache = {}
    changes = 0

    for path, name, body, why in TARGETS:
        if path not in cache:
            cache[path] = read(path)
        text = cache[path]
        hit = find_method(text, name)
        if hit is None:
            print(u'  ❌ %-22s %s —— 没找到方法' % (os.path.basename(path), name))
            continue
        start, end, params, body_start, close_start = hit
        old_body = text[body_start:close_start]
        old_lines = len(old_body.rstrip('\n').split('\n'))
        # ★ 必须把签名原有的缩进带上 —— m.start() 落在行首（缩进之前）
        indent = re.match(r'^([ \t]*)', text[start:]).group(1)
        new_method = u'%sstatic String %s(%s) {\n%s        %s\n%s}\n' % (
            indent, name, params, NOTE, body, indent)
        new_lines = 4
        cache[path] = text[:start] + new_method + text[end:]
        changes += 1
        print(u'  ✅ %-22s %s(%s)   %d 行 → %d 行' % (
            os.path.basename(path), name, params, old_lines, new_lines))
        print(u'       %s' % why)

    # 删死代码
    print(u'')
    for path, name in DEAD:
        if path not in cache:
            cache[path] = read(path)
        text = cache[path]
        hit = find_method(text, name)
        if hit is None:
            print(u'  ⚠  %-22s %s —— 没找到（可能已被删）' % (os.path.basename(path), name))
            continue
        start, end, params, body_start, close_start = hit
        # 连同紧邻其上的 javadoc 一起删
        head = text.rfind('/**', 0, start)
        cut = start
        if head >= 0 and text[head:start].strip().endswith('*/'):
            cut = head
        # 往上吃掉空行
        while cut > 0 and text[cut - 1] in '\r\n':
            cut -= 1
            if cut > 0 and text[cut - 1] == '\n':
                break
        removed = len(text[cut:end].rstrip('\n').split('\n'))
        cache[path] = text[:cut] + text[end:]
        print(u'  🗑  %-22s %s（死代码，删 %d 行）' % (os.path.basename(path), name, removed))

    # 补 import
    print(u'')
    for path in cache:
        text, added = add_import(cache[path])
        cache[path] = text
        if added:
            print(u'  + import JsNum  → %s' % os.path.basename(path))

    if apply:
        for path, text in cache.items():
            write(path, text)
        print(u'')
        print(u'已写入 %d 个文件。' % len(cache))
    else:
        print(u'')
        print(u'（dry run，未写入。加 --apply 才真改）')

    return 0


if __name__ == '__main__':
    sys.exit(main())
