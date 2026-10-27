#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
审计原脚本里「把 JS 的 double 传给 Java float 形参」的调用点。

背景（已用 tools/FloatInteropProbe.java 独立复核，7/7）：
    GraalJS 把 JS 的 double 塞进 Java 的 float 形参时，只要该值**不是 float 能精确表示的**，
    就一律抛 TypeError（'Invalid or lossy primitive coercion'）。
    脚本自己的 try/catch 把它吞掉 ⇒ 该调用**静默失效**。
    ★ 已交叉验证：真 org.bukkit.Location#setRotation(1.1, 0.0) 同样抛。

本脚本把 18 个脚本里的调用点分成三类：
    [确定失效] 字面量参数不是 float 精确值          （如 playSound(..., 1.0, 1.1)）
    [确定生效] 字面量参数都是 float 精确值          （如 playSound(..., 1.0, 1.0)）
    [看运行期] 参数是变量 / 表达式                  （如 setRotation(yaw, pitch)，几乎必失效）

用法：
    python tools/audit_float_interop.py
"""
import io
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SCRIPTS = os.path.join(os.path.dirname(ROOT), u'QHCGS', u'GS', u'GS', u'scripts')

# 形参里有 float 的 Bukkit 方法（按形参位置标出哪些是 float）
# 值 = 该方法的 float 形参下标（0-based，只算参数）
FLOAT_METHODS = {
    'setRotation': [0, 1],      # (float yaw, float pitch)
    'setWalkSpeed': [0],
    'setFlySpeed': [0],
    'setYaw': [0],
    'setPitch': [0],
    'setVolume': [0],
    'setProgress': [0],         # BossBar#setProgress(float)
    'setSpeed': [0],
    'setGravity': [0],
    'playSound': [2, 3],        # (Location, Sound, float volume, float pitch)
    'playSoundAt': [2, 3],
}

# 也匹配 .sound( 这种自定义包装（本项目的 nwSound 等），一律按 (名字, 音量, 音高) 处理
CUSTOM_SOUND = re.compile(r'\b([A-Za-z_$][\w$]*[Ss]ound)\s*\(')


def is_float_exact(d):
    """d 是否能被 float 精确表示（且不溢出）。"""
    try:
        f = struct.unpack('f', struct.pack('f', d))[0]
    except (OverflowError, ValueError):
        return False
    return f == d


def split_args(s):
    """按顶层逗号切分实参（考虑括号 / 引号 / 方括号）。"""
    out = []
    depth = 0
    cur = ''
    i = 0
    quote = None
    while i < len(s):
        c = s[i]
        if quote:
            cur += c
            if c == '\\':
                if i + 1 < len(s):
                    cur += s[i + 1]
                    i += 2
                    continue
            elif c == quote:
                quote = None
        elif c in '"\'`':
            quote = c
            cur += c
        elif c in '([{':
            depth += 1
            cur += c
        elif c in ')]}':
            depth -= 1
            cur += c
        elif c == ',' and depth == 0:
            out.append(cur.strip())
            cur = ''
        else:
            cur += c
        i += 1
    if cur.strip():
        out.append(cur.strip())
    return out


NUM_LIT = re.compile(r'^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$')


def classify(arg):
    """返回 ('lit', value) / ('expr', None) / ('other', None)。"""
    a = arg.strip()
    if NUM_LIT.match(a):
        return ('lit', float(a))
    return ('expr', None)


def find_call_args(line, open_paren_idx):
    """从 '(' 开始找配对的 ')'，返回 (实参串, 结束下标)。"""
    depth = 0
    quote = None
    i = open_paren_idx
    while i < len(line):
        c = line[i]
        if quote:
            if c == '\\':
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in '"\'`':
            quote = c
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return line[open_paren_idx + 1:i], i
        i += 1
    return None, len(line)


def main():
    if not os.path.isdir(SCRIPTS):
        print(u'找不到脚本目录: %s' % SCRIPTS)
        return 2

    files = sorted(f for f in os.listdir(SCRIPTS) if f.endswith('.js'))
    print(u'扫描 %d 个脚本 …' % len(files))
    print(u'')

    grand = {'dead': 0, 'live': 0, 'rt': 0}
    rows = []

    for fn in files:
        path = os.path.join(SCRIPTS, fn)
        with io.open(path, 'r', encoding='utf-8', errors='replace') as f:
            lines = f.read().split('\n')

        dead = []   # 确定失效
        live = []   # 确定生效
        rt = []     # 看运行期

        for ln, line in enumerate(lines, 1):
            # 跳过注释行
            st = line.strip()
            if st.startswith('//') or st.startswith('*') or st.startswith('/*'):
                continue

            for m in re.finditer(r'\.([A-Za-z_$][\w$]*)\s*\(', line):
                name = m.group(1)
                if name not in FLOAT_METHODS:
                    continue
                args, _ = find_call_args(line, m.end() - 1)
                if args is None:
                    continue
                parts = split_args(args)
                idxs = [i for i in FLOAT_METHODS[name] if i < len(parts)]
                if not idxs:
                    continue
                kinds = [classify(parts[i]) for i in idxs]
                desc = u'%s(%s)' % (name, u', '.join(parts))
                if all(k[0] == 'lit' for k in kinds):
                    if all(is_float_exact(k[1]) for k in kinds):
                        live.append((ln, desc))
                    else:
                        bad = [k[1] for k in kinds if not is_float_exact(k[1])]
                        dead.append((ln, desc, bad))
                else:
                    rt.append((ln, desc))

            # 自定义音效包装：xxxSound(name, vol, pitch)
            for m in CUSTOM_SOUND.finditer(line):
                if m.group(1) in FLOAT_METHODS:
                    continue
                args, _ = find_call_args(line, m.end() - 1)
                if args is None:
                    continue
                parts = split_args(args)
                if len(parts) < 3:
                    continue
                kinds = [classify(parts[-2]), classify(parts[-1])]
                desc = u'%s(%s)' % (m.group(1), u', '.join(parts))
                if all(k[0] == 'lit' for k in kinds):
                    if all(is_float_exact(k[1]) for k in kinds):
                        live.append((ln, desc))
                    else:
                        bad = [k[1] for k in kinds if not is_float_exact(k[1])]
                        dead.append((ln, desc, bad))
                else:
                    rt.append((ln, desc))

        if dead or live or rt:
            rows.append((fn, dead, live, rt))
            grand['dead'] += len(dead)
            grand['live'] += len(live)
            grand['rt'] += len(rt)

    # 打印
    for fn, dead, live, rt in rows:
        print(u'── %s' % fn)
        if dead:
            print(u'   [确定失效] %d 处 —— 原脚本上这些调用被 try/catch 静默吞掉' % len(dead))
            for ln, desc, bad in dead[:6]:
                print(u'      L%-5d %s' % (ln, desc[:110]))
                print(u'             非 float 精确值: %s' % bad)
            if len(dead) > 6:
                print(u'      …（还有 %d 处）' % (len(dead) - 6))
        if rt:
            print(u'   [看运行期] %d 处 —— 参数是变量/表达式（几乎必然失效）' % len(rt))
            for ln, desc in rt[:4]:
                print(u'      L%-5d %s' % (ln, desc[:110]))
            if len(rt) > 4:
                print(u'      …（还有 %d 处）' % (len(rt) - 4))
        if live:
            print(u'   [确定生效] %d 处（字面量都是 float 精确值）' % len(live))
        print(u'')

    print(u'═' * 60)
    print(u'汇总：确定失效 %d ｜ 看运行期 %d ｜ 确定生效 %d ｜ 合计 %d'
          % (grand['dead'], grand['rt'], grand['live'],
             grand['dead'] + grand['rt'] + grand['live']))
    print(u'')
    print(u'★ 说明：「看运行期」那批里，参数若是 atan2/getYaw 之类算出来的，')
    print(u'  在真服上几乎必然不是 float 精确值 ⇒ 同样静默失效。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
