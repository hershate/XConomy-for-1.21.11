# 性能优化报告：Core 热路径（2026-07-30，含全面基准与边界修复）

> 分支：`perf/optimize-hotpaths`
> 红线：**安全性、稳定性、兼容性不可破坏** —— 所有改动均**行为等价**（或修正明确的潜伏 bug），不触碰并发锁、资金一致性、签名校验、SQL 落库等安全关键路径。

## 0. 本轮（完善测试）新增内容

在首轮 3 处优化基础上，本轮：
1. **把基准扩展为全面测试套件**（[benchmark/](../../../benchmark/)）：覆盖 DataFormat 全方法、整数/小数模式、访问分布（均匀/偏斜/高基数）、isDouble 合法/非法/整数分类、缓存读全 UUID 模式 + String 键 + 未命中、以及**多线程并发**。
2. **测试中发现并修复一个真实的边界 bug**：`Cache.getDataFromCache(String)` 在名字未命中时 `pds.get(null)` 抛 NPE（原潜伏 bug，被旧调用方的 `containsKey` 守卫掩盖；读路径合并查询优化会暴露它）→ 已修。
3. **多线程测试暴露并发瓶颈并修复**：原 `synchronizedMap` 展示缓存的全局锁把并发读串行化 → 改为 **`ConcurrentHashMap`（读无锁）**，多线程聚合吞吐从 ~10M 跃升到 ~600M ops/s。
4. 完善本报告。

## 1. 结论速览

行为等价自检 **ALL PASS**（shown 小数/整数模式 15 探针、format 系列、isDouble 31 探针、缓存读含未命中/IGNORE_CASE）；全量多模块编译 `BUILD SUCCESS`。

| 场景 | 基线 | 优化后 | 加速 |
|---|---:|---:|---:|
| `shown` 小数-偏斜（80%命中热集，记分板/Tab 真实） | ~2.0M ops/s | ~74M ops/s | **~37×** |
| `shown` 小数-均匀（4096 全命中） | ~2.3M | ~98M | **~43×** |
| `shown` 整数模式-偏斜 | ~2.0M | ~92M | **~45×** |
| `PEshownf` 小数-偏斜 | ~1.9M | ~90M | **~47×** |
| `shown` 高基数（10万去重，几乎全 miss） | ~1.8M | ~1.75M | ~0.96×（见 §6） |
| `isDouble` 小数-合法输入 | ~3.9M | ~11M | **~2.8×** |
| `isDouble` 小数-非法输入 | ~3.1M | ~5.5M | **~1.8×** |
| `isDouble` 整数模式-合法 | ~7.3M | ~24M | **~3.4×** |
| 缓存读 DEFAULT UUID 命中 | ~84M | ~119M | **~1.4×** |
| 缓存读 SEMIONLINE 命中（经 m_uuids） | ~19M | ~25M | **~1.3×** |
| 缓存读 String 键（按名） | ~40M | ~57M | **~1.4×** |
| 缓存读 DEFAULT UUID 未命中 | ~176M | ~161M | ~0.9×（噪声，见 §6） |
| 对照组 formatdouble/formatString/formatBigDecimal/isMAX | — | — | ~1.0×（未改，验证基准可信） |
| **多线程 shown（8 线程聚合）** | **~1.8M** | **~250–340M** | **~150–340×** |

> 环境：HotSpot 21.0.10，32 核；单线程 warmup=300k/rounds=12/iters=1M；并发 8×300k。两次独立运行取代表值；高速 op（对照组、缓存读）有 ±15% 测量噪声（由 `String.toLowerCase` 等分配/GC 引起），缓存命中类大增益稳定。

## 2. 为什么是这些路径

运行时真正高频的是**读/展示路径**：`DataFormat.shown` 被 PlaceholderAPI、记分板、Tab、`/balance`、`/baltop` 每行反复调用，且对**同一批在线玩家余额**高频重复；`CommandCore.isDouble` 每条带金额命令调用且原实现每次编译两段正则；`DataCon` 读路径每次取余额做两次哈希查找。写入路径的 per-UUID 锁是安全设计，**不在优化范围**。

## 3. 三处优化（行为等价）

### A. `DataFormat.shown` / `PEshownf` 按值结果缓存
- 文件：[DataFormat.java](../../../XConomy-Core/src/main/java/me/yic/xconomy/data/DataFormat.java)
- 输出仅取决于余额值 + `load()` 后固定配置；用按 `BigDecimal` 的缓存把一次完整格式化折叠为一次哈希查询。`load()` 时清空。
- **并发实现（本轮修复）**：采用 `ConcurrentHashMap` + `AtomicInteger` 软容量上限（4096），读 `get()` **无锁**；而非初版的 `synchronizedMap(LinkedHashMap)`（其全局锁在多线程下把读串行化，见 §4）。
- 安全：键=输入余额值、值=相同计算结果；并发未命中重复计算幂等；容量有界防增长。

### B. `CommandCore.isDouble` 消除每次调用编译正则
- 文件：[CommandCore.java](../../../XConomy-Core/src/main/java/me/yic/xconomy/command/core/CommandCore.java)
- 字母检测改手写 ASCII 扫描（等价 `s.matches(".*[a-zA-Z].*")`，同时挡 NaN/Infinity/`e`）；小数位正则提升为 `static final`。负数/上限校验不变。

### C. `DataCon` 读路径合并 `containsKey+get` 为单次 `get`
- 文件：[DataCon.java](../../../XConomy-Core/src/main/java/me/yic/xconomy/data/DataCon.java)
- `getPlayerDatai` / `getAccountBalance` 直接 `get`，未命中回源。省一次哈希查找；UUID/SEMIONLINE/String/IGNORE_CASE 全模式等价（依赖 §5 的 NPE 修复保证 String 未命中安全）。

## 4. 关键发现：多线程可扩展性（CHM vs synchronizedMap）

PlaceholderAPI/记分板是**多线程并发读**。初版用 `synchronizedMap` 缓存，8 线程聚合仅 ~10M ops/s（单线程 ~40M，8 线程反而远未扩展——全局锁串行化）。改为 `ConcurrentHashMap` 后：

| 实现 | 8 线程聚合 ops/s |
|---|---:|
| 基线（无缓存，stateless） | ~1.8M（分配/GC 限制，几乎不随线程扩展） |
| synchronizedMap 缓存 | ~10.6M（全局锁串行化） |
| **ConcurrentHashMap 缓存（最终）** | **~250–340M（近线性扩展）** |

附带发现：**基线 `shown` 因每次都分配大量 String/char[]（replace 链 + 颜色翻译），在高并发下被 GC 限制、几乎不随线程扩展**；缓存命中路径零分配，因而能线性扩展。这是对真实服务器 PlaceholderAPI 线程池有效性的实质提升。

## 5. 边界修复：`Cache.getDataFromCache` 的潜伏 NPE

全面正确性套件发现：`getDataFromCache(String)` 当玩家名不在缓存时，`u = uuids.get(name)` 为 null，而 `pds` 是 `ConcurrentHashMap`（不允许 null 键），`pds.get(null)` 抛 NPE。

- 原代码该路径**从未被触发**，因为所有调用方都用 `CacheContainsKey` 先守卫。
- 优化 C 去掉守卫、直接 `getDataFromCache` 后，String 未命中会命中此 NPE → **这是优化 C 必须配合的修复**。
- 修复（[Cache.java](../../../XConomy-Core/src/main/java/me/yic/xconomy/data/caches/Cache.java)）：`return u == null ? null : pds.get(u);` —— 未命中返回 null（语义“不在缓存”），消除潜伏 NPE，使单次 get 安全。已用 DEFAULT/SEMIONLINE/String/IGNORE_CASE 的命中与未命中用例验证。

## 6. 诚实的边界与噪声

- **高基数几乎全 miss**：`shown` 在 10 万级去重余额、几乎全 miss 时 ~0.96×（缓存 get/put/容量判定的微小开销）。真实负载去重余额数（在线玩家 + 排行榜 ≪ 4096）命中率近 100%，不受影响。
- **未命中读**：缓存读未命中 ~0.9×，源于 null 判定开销与测量噪声；绝对仍 ~160M ops/s。
- **测量噪声**：对照组（isMAX 等）代码未改却在 0.88×–1.06× 间波动；`String.toLowerCase` 分配主导的缓存读微基准（如 IGNORE_CASE）单次运行在 0.64×–2.8× 间波动——均属 GC/分配噪声，非算法回归（合并查询做的工作严格不多于原实现）。

## 7. 复现

```bash
mvn -pl XConomy-Core -am install -DskipTests
mvn -pl benchmark -am dependency:build-classpath -Dmdep.outputFile="$PWD/benchcp.txt"
mvn -pl benchmark -am compile
java -cp "benchmark/target/classes;$(cat benchcp.txt)" me.yic.xconomy.bench.BenchRunner
```

基准源码：[benchmark/src/main/java/me/yic/xconomy/bench/](../../../benchmark/src/main/java/me/yic/xconomy/bench/)。`baseline`=原始算法逐行拷贝，`optimized`=优化后 Core，同进程对比。

## 8. 刻意未改动（红线）

- 资金写入与 per-UUID 锁、落库失败失效缓存 —— 防丢币/防刷币语义未改。
- 跨服同步报文与签名前置校验、Redis KV 签名 —— 未改。
- SQL 相对增量、连接 null 防护、重连锁 —— 未改。
- `balance double(20,2)` 列精度未改。
