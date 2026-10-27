#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""极简 RCON 客户端 —— 只为在测试服上驱动 /gs 命令。

用法: python tools/rcon.py <命令> [<命令> ...]
"""
import io
import socket
import struct
import sys

HOST = '127.0.0.1'
PORT = 25575
PASSWORD = 'gstest'


def pack(req_id, typ, body):
    data = struct.pack('<ii', req_id, typ) + body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data


def recv(sock):
    raw = sock.recv(4)
    if len(raw) < 4:
        return None, None, None
    (ln,) = struct.unpack('<i', raw)
    buf = b''
    while len(buf) < ln:
        buf += sock.recv(ln - len(buf))
    rid, typ = struct.unpack('<ii', buf[:8])
    return rid, typ, buf[8:-2].decode('utf-8', 'replace')


def main():
    cmds = sys.argv[1:]
    if not cmds:
        sys.stderr.write('用法: python tools/rcon.py <命令> [...]\n')
        return 2
    s = socket.create_connection((HOST, PORT), timeout=10)
    s.sendall(pack(1, 3, PASSWORD))
    rid, typ, _ = recv(s)
    if rid == -1:
        sys.stderr.write('认证失败\n')
        return 1
    rc = 0
    for i, c in enumerate(cmds, start=2):
        s.sendall(pack(i, 2, c))
        _, _, body = recv(s)
        print(u'>>> %s' % c)
        if body:
            for line in body.split('\n'):
                print(u'    %s' % line)
        else:
            print(u'    (无输出)')
        print(u'')
    s.close()
    return rc


if __name__ == '__main__':
    sys.exit(main())
