# 性能优化备忘（维护者：ZTF3）

> 日期：2026-07-30　分支：`perf/optimize-hotpaths`
> 详细量化报告见 [report/perf/2026-07-30-hotpath-optimization.md](report/perf/2026-07-30-hotpath-optimization.md)

## 目标与红线

在**不破坏安全性、稳定性、兼容性**的前提下，针对 XConomy-Core 的纯 Java 高频热路径做行为等价优化。资金写入/并发锁/跨服签名/SQL 落库等 2.27.0 已加固的安全关键路径**刻意未改**。

## 改动清单（3 处，均行为等价）

1. **`DataFormat.shown` / `PEshownf` 按值 LRU 缓存**（[DataFormat.java](../XConomy-Core/src/main/java/me/yic/xconomy/data/DataFormat.java)）
   - 容量 4096 的 `synchronizedMap(LinkedHashMap, accessOrder)`；`load()` 时 `clear()`。
   - 真实负载（在线玩家+排行榜去重余额 ≪ 4096）下命中率近 100%，展示吞吐 **17×–25×**。
2. **`CommandCore.isDouble` 去掉每次调用编译正则**（[CommandCore.java](../XConomy-Core/src/main/java/me/yic/xconomy/command/core/CommandCore.java)）
   - 字母检测改手写 ASCII 扫描；小数位正则提升为 `static final Pattern`。**1.83×**。
3. **`DataCon.getPlayerDatai` / `getAccountBalance` 合并 `containsKey+get` 为单次 `get`**（[DataCon.java](../XConomy-Core/src/main/java/me/yic/xconomy/data/DataCon.java)）
   - 读路径省一次哈希查找。**1.51×**。

## 验证

- 行为等价自检：`shown=PASS`、`isDouble=PASS`。
- 全量多模块 `mvn compile` BUILD SUCCESS（Stub/Core/Bukkit/Paper/benchmark）。
- benchmark 位于 [benchmark/](../benchmark/)，复现命令见报告 §6。

## 诚实记录

- 不现实的高基数（10 万级去重余额、几乎全 miss）场景下 `shown` 约 0.92×（缓存 get/put/淘汰开销），属开销下限；真实负载不受影响。
- 写入/落库/同步的进一步优化未做，需另评估且不得削弱资金安全语义。
