package com.qinghuacisj.gs.script.module;

import org.bukkit.util.Vector;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code gs_borrow_life} 判定盒几何 + 去抖时序 的对账探针。
 *
 * <p><b>为什么要这么较真</b>：这个脚本的判定体是<b>三维旋转</b>的
 * （沿准星展开、抬头低头都能打），里面有一堆容易写错的点：
 * 基向量的叉乘顺序、右向的符号、yaw 退化时的兜底、搜索盒的外接球半径。
 * 任何一处抄错，表现都是「偶尔打不中」—— 玩家只会觉得「这武器时灵时不灵」，
 * 极难定位。所以这里把真 JS 的算式跑起来，和 Java 侧逐值比。
 *
 * <p>覆盖：
 * <ol>
 *   <li><b>基向量 + 投影</b>：yaw × pitch 扫描 × 一批相对位移，
 *       逐个比对 {@code inside()} 的布尔结果（含 ±90° 那个 yaw 兜底分支）。</li>
 *   <li><b>平视等价性</b>：pitch = 0 时必须退化回旧版口径
 *       {@code fwd = dz}、{@code lat = −dx}、{@code ver = dy − 0.1}
 *       ⇒ 竖直区间仍是 {@code dy ∈ [−0.9, +1.1]}。</li>
 *   <li><b>搜索盒半径</b>：必须是外接球
 *       √(2.7² + 2.5² + 1.0²) + 0.2，而不是只算水平的 √(2.5²+2.5²)。</li>
 *   <li><b>去抖时序</b>：原脚本注释里记的那个 bug ——
 *       0/100/300/450/510 ms 连点，<b>510ms 那一下本该打出去</b>。
 *       用真时间跑一遍，确认它没被吞。</li>
 * </ol>
 */
public final class BorrowLifeParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /** 真 JS 侧的算式 —— 从 {@code gs_borrow_life.js} 的 {@code blBoxHit} 逐字摘出。 */
    private static final String JS_MATH =
            "function basis(dirx, diry, dirz, yaw) {\n"
                    + "  var fx = dirx, fy = diry, fz = dirz;\n"
                    + "  var fl = Math.sqrt(fx*fx + fy*fy + fz*fz);\n"
                    + "  if (!(fl > 1.0e-6)) { fx = 0.0; fy = 0.0; fz = 1.0; fl = 1.0; }\n"
                    + "  fx /= fl; fy /= fl; fz /= fl;\n"
                    + "  var hx = fx, hz = fz;\n"
                    + "  var hl = Math.sqrt(hx*hx + hz*hz);\n"
                    + "  if (!(hl > 1.0e-4)) {\n"
                    + "    var rad = yaw * Math.PI / 180.0;\n"
                    + "    hx = -Math.sin(rad); hz = Math.cos(rad);\n"
                    + "    hl = Math.sqrt(hx*hx + hz*hz);\n"
                    + "    if (!(hl > 1.0e-6)) { hx = 0.0; hz = 1.0; hl = 1.0; }\n"
                    + "  }\n"
                    + "  hx /= hl; hz /= hl;\n"
                    + "  var rx = -hz, ry = 0.0, rz = hx;\n"
                    + "  var ux = ry*fz - rz*fy;\n"
                    + "  var uy = rz*fx - rx*fz;\n"
                    + "  var uz = rx*fy - ry*fx;\n"
                    + "  return {fx:fx, fy:fy, fz:fz, rx:rx, ry:ry, rz:rz, ux:ux, uy:uy, uz:uz};\n"
                    + "}\n"
                    + "function inside(b, dx, dy, dz, RANGE, WIDE, H, BACK) {\n"
                    + "  var halfW = WIDE * 0.5;\n"
                    + "  var halfH = H * 0.5;\n"
                    + "  var fwd = dx*b.fx + dy*b.fy + dz*b.fz;\n"
                    + "  var lat = dx*b.rx + dy*b.ry + dz*b.rz;\n"
                    + "  var ver = dx*b.ux + dy*b.uy + dz*b.uz;\n"
                    + "  if (fwd < -BACK || fwd > RANGE) return false;\n"
                    + "  if (lat < -halfW || lat > halfW) return false;\n"
                    + "  if (ver < -halfH || ver > halfH) return false;\n"
                    + "  return true;\n"
                    + "}\n"
                    + "function runAll() {\n"
                    + "  var cases = __CASES;\n"
                    + "  var out = [];\n"
                    + "  for (var i = 0; i < cases.length; i++) {\n"
                    + "    var c = cases[i];\n"
                    + "    var b = basis(c[3], c[4], c[5], c[6]);\n"
                    + "    out.push(inside(b, c[0], c[1], c[2], c[7], c[8], c[9], c[10]) ? 1 : 0);\n"
                    + "  }\n"
                    + "  return out;\n"
                    + "}\n";

    /** Bukkit {@code Location#getDirection()} 的口径。 */
    private static Vector direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);
        return new Vector(x, y, z);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_borrow_life：判定盒几何 + 去抖时序 对账（真 JS vs Java）===");
        System.out.println();

        sweep();
        eyeLevel();
        reachBox();
        debounce();

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ [A] 全角度扫描

    private static void sweep() throws Exception {
        System.out.println("[A] 基向量 + 投影：yaw × pitch 全扫描");

        List<double[]> cases = new ArrayList<>();
        double[] yaws = new double[24];
        for (int i = 0; i < 24; i++) {
            yaws[i] = i * 15.0;
        }
        // ★ 含 ±90（正上/正下看，触发 yaw 兜底分支）与 ±89.9（接近退化）
        double[] pitches = {-90.0, -89.9, -60.0, -45.0, -30.0, 0.0,
                30.0, 45.0, 60.0, 89.9, 90.0};
        double[] offs = {-6.0, -4.0, -3.0, -2.5, -2.0, -1.0, -0.4,
                0.0, 0.4, 1.0, 2.0, 2.5, 3.0, 4.0, 6.0};

        for (double yaw : yaws) {
            for (double pitch : pitches) {
                Vector d = direction((float) yaw, (float) pitch);
                for (double dx : offs) {
                    for (double dy : offs) {
                        for (double dz : offs) {
                            cases.add(new double[]{
                                    dx, dy, dz, d.getX(), d.getY(), d.getZ(), yaw,
                                    BorrowLifeModule.BL_RANGE,
                                    BorrowLifeModule.BL_WIDE,
                                    BorrowLifeModule.BL_H,
                                    BorrowLifeModule.BL_BACK});
                        }
                    }
                }
            }
        }
        System.out.println("      用例数 " + cases.size());

        int[] js = evalJs(cases);
        int mismatch = 0;
        int insideCount = 0;
        for (int i = 0; i < cases.size(); i++) {
            double[] c = cases.get(i);
            // ★ 用与 JS 完全相同的 dir 分量重建基向量
            BorrowLifeModule.Basis b = BorrowLifeModule.basis(
                    new Vector(c[3], c[4], c[5]), (float) c[6]);
            boolean jv = BorrowLifeModule.inside(b, c[0], c[1], c[2]);
            boolean ref = js[i] == 1;
            if (jv) {
                insideCount++;
            }
            if (jv != ref) {
                mismatch++;
                if (mismatch <= 5) {
                    System.out.println("      不一致 dx=" + c[0] + " dy=" + c[1] + " dz=" + c[2]
                            + " yaw=" + c[6] + "  JS=" + ref + "  Java=" + jv);
                }
            }
        }
        check("全部 " + cases.size() + " 例逐值一致（其中命中 " + insideCount + " 例）",
                mismatch == 0);
        check("命中数既不为 0 也不为全部（说明扫描真的覆盖了边界）",
                insideCount > 0 && insideCount < cases.size());
        System.out.println();
    }

    // ------------------------------------------------------------ [B] 平视等价性

    /** 在基向量坐标系里构造世界位移：d = f*fwd + r*lat + u*ver。 */
    private static double[] combine(BorrowLifeModule.Basis b,
                                    double fwd, double lat, double ver) {
        return new double[]{
                b.fx * fwd + b.rx * lat + b.ux * ver,
                b.fy * fwd + b.ry * lat + b.uy * ver,
                b.fz * fwd + b.rz * lat + b.uz * ver};
    }

    private static void eyeLevel() {
        System.out.println("[B] 平视（pitch = 0）必须与旧版逐点等价");

        // (1) 平视时 f 水平、r 水平、u 竖直
        boolean basisOk = true;
        for (double yaw : new double[]{0, 90, 180, 270, 45, 135, 225, 315}) {
            Vector d = direction((float) yaw, 0.0f);
            BorrowLifeModule.Basis b = BorrowLifeModule.basis(d, (float) yaw);
            if (Math.abs(b.fy) > 1e-9 || Math.abs(b.ry) > 1e-9 || Math.abs(b.uz) > 1e-9) {
                basisOk = false;
            }
            if (Math.abs(Math.abs(b.uy) - 1.0) > 1e-9) {
                basisOk = false;
            }
        }
        check("平视时 f 水平 / r 水平 / u 竖直（fy=ry=uz=0、|uy|=1）", basisOk);

        // (2) {f, r, u} 是正交基 ⇒ 在基坐标系里构造 d，反投影必须精确还原构造值，
        //     于是三个区间常数可以直接断言（与 yaw/pitch 无关，全角度都该成立）。
        //     ★ 边界上的网格点要跳过：扫描网格会**正好**落在 ±2.5 / ±1.0 / 5.0 上，
        //       而反投影回来可能是 -2.5000000000000004 ⇒ inside() 正确地判为「出界」。
        //       这是浮点边界，不是逻辑差异，所以按 1e-9 邻域跳过（并统计跳过了多少）。
        int badFwd = 0;
        int badLat = 0;
        int badVer = 0;
        int skipped = 0;
        int total = 0;
        for (double yaw : new double[]{0, 30, 90, 180, 270, 330}) {
            for (double pitch : new double[]{-60, -30, 0, 30, 60}) {
                Vector dir = direction((float) yaw, (float) pitch);
                BorrowLifeModule.Basis b = BorrowLifeModule.basis(dir, (float) yaw);
                for (double fwd = -1.0; fwd <= 6.0001; fwd += 0.05) {
                    for (double lat = -3.5; lat <= 3.5001; lat += 0.25) {
                        for (double ver = -1.5; ver <= 1.5001; ver += 0.25) {
                            total++;
                            if (onEdge(fwd, -0.4) || onEdge(fwd, 5.0)
                                    || onEdge(lat, -2.5) || onEdge(lat, 2.5)
                                    || onEdge(ver, -1.0) || onEdge(ver, 1.0)) {
                                skipped++;
                                continue;
                            }
                            double[] d = combine(b, fwd, lat, ver);
                            boolean got = BorrowLifeModule.inside(b, d[0], d[1], d[2]);
                            boolean want = fwd > -0.4 && fwd < 5.0
                                    && lat > -2.5 && lat < 2.5
                                    && ver > -1.0 && ver < 1.0;
                            if (got != want) {
                                if (fwd <= -0.4 || fwd >= 5.0) {
                                    badFwd++;
                                } else if (Math.abs(lat) >= 2.5) {
                                    badLat++;
                                } else {
                                    badVer++;
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("      用例 " + total + "，其中边界点跳过 " + skipped);
        check("前向区间 = (-0.4, +5.0)（偏差 " + badFwd + "）", badFwd == 0);
        check("左右区间 = (-2.5, +2.5)（偏差 " + badLat + "）", badLat == 0);
        check("上下区间 = (-1.0, +1.0)（偏差 " + badVer + "）", badVer == 0);

        // (3) 平视 yaw=0 时与旧版口径逐点等价：
        //       f=(0,0,1) / r=(-1,0,0) / u=(0,1,0)
        //       ⇒ fwd = dz、lat = -dx、ver = (dy - 0.1)
        //     （dy 是「怪脚下」相对「自己脚下」；d 的 y 分量才是 dy - 0.1）
        //     ⇒ 世界系判据：dz ∈ (-0.4, 5)、|dx| < 2.5、dy ∈ (-0.9, +1.1)
        BorrowLifeModule.Basis b0 = BorrowLifeModule.basis(direction(0f, 0f), 0f);
        int badRaw = 0;
        int skipRaw = 0;
        for (double dx = -3.0; dx <= 3.0001; dx += 0.25) {
            for (double dy = -1.5; dy <= 1.5001; dy += 0.05) {
                for (double dz = -1.0; dz <= 5.5001; dz += 0.25) {
                    if (onEdge(dx, -2.5) || onEdge(dx, 2.5)
                            || onEdge(dy, -0.9) || onEdge(dy, 1.1)
                            || onEdge(dz, -0.4) || onEdge(dz, 5.0)) {
                        skipRaw++;
                        continue;
                    }
                    boolean got = BorrowLifeModule.inside(b0, dx, dy - 0.1, dz);
                    boolean want = dz > -0.4 && dz < 5.0
                            && Math.abs(dx) < 2.5
                            && dy > -0.9 && dy < 1.1;
                    if (got != want) {
                        badRaw++;
                    }
                }
            }
        }
        System.out.println("      边界点跳过 " + skipRaw);
        check("平视 yaw=0：世界系 dz∈(-0.4,5)、|dx|<2.5、dy∈(-0.9,+1.1)（偏差 "
                + badRaw + "）", badRaw == 0);
        System.out.println();
    }

    /** 该值是否落在某条边界上（浮点扫描网格会正好踩到边界）。 */
    private static boolean onEdge(double v, double edge) {
        return Math.abs(v - edge) < 1e-9;
    }

    // ------------------------------------------------------------ [C] 搜索盒半径

    private static void reachBox() {
        System.out.println("[C] 搜索盒半径：外接球 vs 原脚本注释里记的旧版公式");
        double halfR = (BorrowLifeModule.BL_RANGE + BorrowLifeModule.BL_BACK) * 0.5;
        double halfW = BorrowLifeModule.BL_WIDE * 0.5;
        double halfH = BorrowLifeModule.BL_H * 0.5;
        double reach = Math.sqrt(halfR * halfR + halfW * halfW + halfH * halfH) + 0.2;

        // ★ 原脚本注释原文：
        //     「旧版只按水平算 √(2.5²+2.5²) ≈ 3.54 ⇒ 抬头/低头时高处低处的怪会漏搜」
        //   ⇒ 旧公式 = √((长/2)² + (宽/2)²)（把「半长」按 RANGE/2 算、且不带 +0.2 余量）
        double oldNoMargin = Math.sqrt((BorrowLifeModule.BL_RANGE * 0.5) * (BorrowLifeModule.BL_RANGE * 0.5)
                + halfW * halfW);
        double oldWithMargin = oldNoMargin + 0.2;
        // 判定盒在任一世界轴上可能伸出的最大半宽（柯西-施瓦茨取等）
        double worstExtent = Math.sqrt(halfR * halfR + halfW * halfW + halfH * halfH);

        check("半长 (5+0.4)/2 = 2.7（实测 " + halfR + "）", Math.abs(halfR - 2.7) < 1e-9);
        check("外接球半径 = 4.0131（实测 " + String.format("%.4f", reach) + "）",
                Math.abs(reach - 4.0131) < 0.001);
        check("旧公式 √(2.5²+2.5²) = 3.5355（实测 " + String.format("%.4f", oldNoMargin) + "）",
                Math.abs(oldNoMargin - 3.5355) < 0.001);
        check("判定盒在任一轴上的最大半宽 = 3.8131（实测 "
                + String.format("%.4f", worstExtent) + "）",
                Math.abs(worstExtent - 3.8131) < 0.001);
        check("旧公式 < 最大半宽 ⇒ 确实会漏搜（差 "
                + String.format("%.4f", worstExtent - oldNoMargin) + "）",
                oldNoMargin < worstExtent);
        check("旧公式即使加 0.2 余量仍不够（" + String.format("%.4f", oldWithMargin)
                        + " < " + String.format("%.4f", worstExtent) + "）",
                oldWithMargin < worstExtent);
        check("外接球 + 0.2 余量 >= 最大半宽 ⇒ 零漏搜", reach >= worstExtent);

        // (1) 真正该验的不变量：凡是 inside() 接受的点，都必须落在搜索盒里
        //     —— 否则就是「明明在范围内却打不中」。这条与坐标轴无关，全角度成立。
        int missOuter = 0;
        int missOld = 0;
        for (double yaw = 0; yaw < 360; yaw += 30) {
            for (double pitch : new double[]{-90, -60, -45, -30, 0, 30, 45, 60, 90}) {
                Vector dir = direction((float) yaw, (float) pitch);
                BorrowLifeModule.Basis b = BorrowLifeModule.basis(dir, (float) yaw);
                // 搜索盒中心（相对玩家脚下）
                double cx = b.fx * mid();
                double cy = 1.0 + b.fy * mid();
                double cz = b.fz * mid();
                for (double dx = -6.0; dx <= 6.0001; dx += 0.5) {
                    for (double dy = -6.0; dy <= 6.0001; dy += 0.5) {
                        for (double dz = -6.0; dz <= 6.0001; dz += 0.5) {
                            if (!BorrowLifeModule.inside(b, dx, dy, dz)) {
                                continue;
                            }
                            // 该 d 对应的「怪脚下」相对玩家脚下
                            double ex = dx;
                            double ey = dy + 0.1;
                            double ez = dz;
                            if (Math.abs(ex - cx) > reach || Math.abs(ey - cy) > reach
                                    || Math.abs(ez - cz) > reach) {
                                missOuter++;
                            }
                            if (Math.abs(ex - cx) > oldWithMargin
                                    || Math.abs(ey - cy) > oldWithMargin
                                    || Math.abs(ez - cz) > oldWithMargin) {
                                missOld++;
                            }
                        }
                    }
                }
            }
        }
        check("外接球半径：零漏搜（实测 " + missOuter + " 例）", missOuter == 0);
        System.out.println("      0.5 网格采到的旧公式漏搜点：" + missOld + " 例（网格太粗，采不到那条窄缝）");

        // ★ 0.5 的网格太粗，采不到那条窄缝 ⇒ 直接枚举判定盒的 8 个角点：
        //   角点在 inside() 里是被接受的（边界含等号），
        //   只要它某一轴的偏移超过旧公式的半径，
        //   旧版的 getNearbyEntities 就会“搜不到”它 —— 这就是当初那个 bug。
        int cornerMissOld = 0;
        int cornerTotal = 0;
        for (double yaw = 0; yaw < 360; yaw += 30) {
            for (double pitch : new double[]{-90, -60, -45, -30, 0, 30, 45, 60, 90}) {
                Vector dir = direction((float) yaw, (float) pitch);
                BorrowLifeModule.Basis b = BorrowLifeModule.basis(dir, (float) yaw);
                double cx = b.fx * mid();
                double cy = 1.0 + b.fy * mid();
                double cz = b.fz * mid();
                for (double fwd : new double[]{-0.4, 5.0}) {
                    for (double lat : new double[]{-2.5, 2.5}) {
                        for (double ver : new double[]{-1.0, 1.0}) {
                            double[] d = combine(b, fwd, lat, ver);
                            cornerTotal++;
                            double ex = d[0];
                            double ey = d[1] + 0.1;
                            double ez = d[2];
                            if (Math.abs(ex - cx) > oldWithMargin
                                    || Math.abs(ey - cy) > oldWithMargin
                                    || Math.abs(ez - cz) > oldWithMargin) {
                                cornerMissOld++;
                            }
                        }
                    }
                }
            }
        }
        check("枚举判定盒 8 个角点（共 " + cornerTotal + "）：旧公式漏搜 "
                + cornerMissOld + " 例 ⇒ 证明这个修正是必要的", cornerMissOld > 0);
        System.out.println();
    }

    /** 搜索盒中心沿准星前移的距离。 */
    private static double mid() {
        return (BorrowLifeModule.BL_RANGE - BorrowLifeModule.BL_BACK) * 0.5;
    }

    // ------------------------------------------------------------ [D] 去抖时序

    private static void debounce() {
        System.out.println("[D] 去抖时序：原脚本注释里那个「冷却结束那一下被误吞」的坑");

        org.bukkit.entity.Player p = fakePlayer();
        BorrowLifeModule m = new BorrowLifeModule();

        check("去抖窗口 150ms", BorrowLifeModule.BL_DEBOUNCE_MS == 150L);
        check("冷却 500ms", BorrowLifeModule.BL_CD_MS == 500L);
        check("去抖必须明显小于冷却（否则 0.5 秒后再按会被误吞）",
                BorrowLifeModule.BL_DEBOUNCE_MS < BorrowLifeModule.BL_CD_MS);

        // 首次按下：不算重复
        check("首次按下：isDuplicate = false", !m.isDuplicate(p));

        // 立刻再按（同一次右键的第二个事件）：算重复
        m.armDebounce(p);
        check("同一刻再按：isDuplicate = true（第二个事件被吞）", m.isDuplicate(p));

        // ★★ 复现注释里的时序：0 / 100 / 300 / 450 / 510 ms 连点。
        //    100/300/450 都被冷却挡下（**不武装**去抖窗口），
        //    所以 510ms 那一下相对「上次武装时刻 0」已过 510ms > 150ms ⇒ 不该被吞。
        //    若实现成「每次按都武装」（原脚本注释里记的旧 bug），
        //    450ms 会把窗口推到 450，510−450 = 60 < 150 ⇒ 被误吞。
        BorrowLifeModule m2 = new BorrowLifeModule();
        Guards.Cooldown cd = new Guards.Cooldown(BorrowLifeModule.BL_CD_MS);

        long t0 = Guards.now();
        m2.armDebounce(p);      // t=0 真的生效了 ⇒ 武装
        cd.mark(p);
        int fired = 1;

        long[] at = {100, 300, 450, 510};
        for (long ms : at) {
            sleepUntil(t0 + ms);
            boolean dup = m2.isDuplicate(p);
            boolean cooled = cd.left(p) > 0;
            if (!dup && !cooled) {
                // 真的打出去了 ⇒ 才武装 + 记冷却
                m2.armDebounce(p);
                cd.mark(p);
                fired++;
            }
        }
        check("0/100/300/450/510 ms 连点 ⇒ 实际打出 " + fired + " 次（应为 2：t=0 与 t=510）",
                fired == 2);

        // 反证：如果按「每次按都武装」的错法，510ms 那一下会被吞
        BorrowLifeModule m3 = new BorrowLifeModule();
        Guards.Cooldown cd3 = new Guards.Cooldown(BorrowLifeModule.BL_CD_MS);
        long t1 = Guards.now();
        m3.armDebounce(p);
        cd3.mark(p);
        int fired3 = 1;
        for (long ms : at) {
            sleepUntil(t1 + ms);
            boolean dup = m3.isDuplicate(p);
            boolean cooled = cd3.left(p) > 0;
            m3.armDebounce(p);          // ← 错法：不管有没有生效都武装
            if (!dup && !cooled) {
                cd3.mark(p);
                fired3++;
            }
        }
        check("反证：错法（每次按都武装）只会打出 " + fired3 + " 次 ⇒ 确实会误吞 510ms 那一下",
                fired3 == 1);
        System.out.println();
    }

    private static void sleepUntil(long target) {
        long wait = target - Guards.now();
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 工具

    private static int[] evalJs(List<double[]> cases) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cases.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            double[] c = cases.get(i);
            for (int j = 0; j < c.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(c[j]);
            }
            sb.append(']');
        }
        sb.append(']');

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .build()) {
            ctx.getBindings("js").putMember("__CASES", sb.toString());
            ctx.eval(Source.newBuilder("js",
                    "function parse(s){ return JSON.parse(s); }\n"
                            + "var __CASES = parse(__CASES);\n"
                            + JS_MATH, "jsmath").build());
            Value out = ctx.getBindings("js").getMember("runAll").execute();
            int n = (int) out.getArraySize();
            int[] r = new int[n];
            for (int i = 0; i < n; i++) {
                r[i] = out.getArrayElement(i).asInt();
            }
            return r;
        }
    }

    private static org.bukkit.entity.Player fakePlayer() {
        final UUID id = UUID.randomUUID();
        return (org.bukkit.entity.Player) Proxy.newProxyInstance(
                BorrowLifeParityProbe.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, a) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    default -> {
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) {
                            yield false;
                        }
                        if (rt == int.class) {
                            yield 0;
                        }
                        if (rt == long.class) {
                            yield 0L;
                        }
                        if (rt == double.class) {
                            yield 0.0;
                        }
                        if (rt == float.class) {
                            yield 0.0f;
                        }
                        yield null;
                    }
                });
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("      OK   " + label);
        } else {
            fail++;
            System.out.println("      失败 " + label);
        }
    }
}
