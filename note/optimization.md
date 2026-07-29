# 性能优化备忘（维护者：ZTF3）

> 日期：2026-07-30　分支：`perf/optimize-hotpaths`
> 详细量化报告见 [report/perf/2026-07-30-hotpath-optimization.md](report/perf/2026-07-30-hotpath-optimization.md)

## 目标与红线

在不破坏安全性、稳定性、兼容性的前提下，针对 XConomy-Core 纯 Java 高频热路径做行为等价优化。资金写入/并发锁/跨服签名/SQL 落库等 2.27.0 已加固的安全关键路径**刻意未改**。

## 改动清单

**优化（行为等价）：**
1. **`DataFormat.shown` / `PEshownf` 按值结果缓存** —— `ConcurrentHashMap`（读无锁）+ `AtomicInteger` 软容量上限(4096)，`load()` 清空。真实负载命中近 100%，单线程展示吞吐 **~37–47×**。
2. **`CommandCore.isDouble` 消除每次调用编译正则** —— 手写 ASCII 字母扫描 + `static final` 小数位正则。合法输入 **~2.8×**、整数模式 **~3.4×**。
3. **`DataCon.getPlayerDatai` / `getAccountBalance` 合并 `containsKey+get` 为单次 `get`** —— 读路径省一次哈希查找（~1.3–1.6×）。

**边界修复（测试发现）：**
4. **`Cache.getDataFromCache` 潜伏 NPE** —— 按名未命中时 `pds.get(null)` 抛 NPE（原被 `containsKey` 守卫掩盖）。改为 `u==null?null:pds.get(u)`。这是优化 3 去掉守卫后**必须配合**的修复。

**并发改进（多线程测试暴露）：**
5. 展示缓存由初版 `synchronizedMap(LinkedHashMap)`（全局锁串行化）改为 `ConcurrentHashMap`（读无锁）。8 线程聚合吞吐从 ~10M 提升到 **~250–340M ops/s**；附带发现基线 `shown` 因重分配在并发下被 GC 限制、几乎不随线程扩展，缓存命中路径零分配因而线性扩展。

## 验证

- 全面正确性套件 **ALL PASS**：shown 小数/整数模式、format 系列、isDouble 31 探针、缓存读（DEFAULT/SEMIONLINE/String/IGNORE_CASE + 命中/未命中）。
- 全量多模块 `mvn compile` BUILD SUCCESS。
- 对照组（未优化的 formatdouble/formatString/formatBigDecimal/isMAX）~1.0×，验证基准可信。
- benchmark 位于 [benchmark/](../benchmark/)，复现见报告 §7。

## 诚实记录

- 高基数（10 万级去重、几乎全 miss）`shown` ~0.96×（缓存微小开销）；真实负载不受影响。
- 高速 op 微基准有 ±15% 噪声（`toLowerCase` 分配/GC），缓存命中类大增益稳定。
- 写入/落库/同步的进一步优化未做，需另评估且不得削弱资金安全语义。
