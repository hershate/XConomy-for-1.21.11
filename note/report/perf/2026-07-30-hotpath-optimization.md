# 性能优化报告：Core 热路径（2026-07-30）

> 分支：`perf/optimize-hotpaths`
> 红线：**安全性、稳定性、兼容性不可破坏** —— 本次所有改动均为**行为等价**，不触碰 2.27.0 已加固的并发锁、资金一致性、签名校验、SQL 落库等安全关键路径。

## 1. 结论速览

对 XConomy-Core 中**最高频的纯 Java 热路径**做了 3 处行为等价优化，并用 [benchmark/](../../../benchmark/) 模块在同一 JVM、相同输入、相同预热下量化前后差异。**行为等价自检 `shown=PASS`、`isDouble=PASS` 全部通过**，全量多模块编译（Stub/Core/Bukkit/Paper/benchmark）`BUILD SUCCESS`。

| 热路径（场景） | 基线(原始算法) | 优化后(Core) | 加速比 |
|---|---:|---:|---:|
| `shown` 余额展示 —— 4096 去重、轮询（缓存全命中） | 2,029,728 ops/s（492.7 ns） | 35,001,873 ops/s（28.6 ns） | **17.24×** |
| `shown` 余额展示 —— 偏斜 80% 命中热集（记分板/Tab 真实模式） | 1,986,243 ops/s（503.5 ns） | 49,182,102 ops/s（20.3 ns） | **24.76×** |
| `shown` 余额展示 —— 高基数 100k 去重、几乎全 miss（不现实极端） | 1,793,417 ops/s（557.6 ns） | 1,652,163 ops/s（605.3 ns） | 0.92×（见 §5） |
| `isDouble` 金额校验 —— 合法+非法混合 24 条 | 2,706,600 ops/s（369.5 ns） | 4,946,712 ops/s（202.2 ns） | **1.83×** |
| 缓存读路径 —— `containsKey+get` → 单次 `get`（N=4096） | 62,244,409 ops/s（16.1 ns） | 93,745,313 ops/s（10.7 ns） | **1.51×** |

> 测试环境：Java HotSpot 64-Bit Server VM 21.0.10，32 核；规模 warmup=300,000、rounds=15、iters/round=1,000,000；`integer-bal=false`、`format-balance` 已配置。

## 2. 为什么是这些路径

XConomy 运行时真正高频的是**读/展示路径**，而非写入路径：

- `DataFormat.shown(...)`：**PlaceholderAPI、记分板、Tab 列表、`/balance`、`/baltop` 每一行**都会调用它把 `BigDecimal` 余额格式化成带货币名/千分位/颜色的字符串。记分板/Tab 类插件常以数秒一次的频率对**同一批在线玩家**反复刷新——同一余额值被反复格式化。
- `CommandCore.isDouble(...)`：每次带金额的命令（`/pay`、`/money give/take/set`、`/baltop help <页>`）都调用，原实现**每次调用都编译两段正则**。
- `DataCon.getPlayerDatai(...)` / `getAccountBalance(...)`：PlaceholderAPI 每次取余额都走它，原实现先 `containsKey` 再 `get` 做了**两次哈希查找**。

写入路径（`changeplayerdata` 等）采用 per-UUID 锁串行化是有意为之的安全设计（防丢失更新），**不在本次优化范围内**。

## 3. 做了什么（三处优化，均行为等价）

### 优化 A：`DataFormat.shown` / `PEshownf` 增加按值有界 LRU 结果缓存
- 文件：[XConomy-Core/.../data/DataFormat.java](../../../XConomy-Core/src/main/java/me/yic/xconomy/data/DataFormat.java)
- 思路：`shown` 的输出**仅取决于余额值与 `load()` 后固定的格式化配置**（`displayformat`/单复数名/`DecimalFormat`/`format-balance`/`roundingmode`）。用按 `BigDecimal`（不可变、`equals/hashCode` 按值）的有界 LRU（容量 4096，`Collections.synchronizedMap(LinkedHashMap, accessOrder=true)`）把一次完整格式化（BigDecimal 比较 + 多次 `String.replace` + `DecimalFormat` + 颜色翻译）折叠为一次哈希查询。
- **安全论证**：缓存键=输入余额值，缓存值=完全相同的计算结果；`load()`（配置重载）首行 `clear()` 清空，保证配置变更后不返回旧格式；并发未命中会重复计算但结果幂等（`synchronizedMap` 保证 put/Get 线程安全）。
- 代码形状：`shown(am){ cache.get→命中返回; 未命中→computeShown→put }`，原算法整体移入私有 `computeShown`，逐行不变。

### 优化 B：`CommandCore.isDouble` 消除每次调用编译正则
- 文件：[XConomy-Core/.../command/core/CommandCore.java](../../../XConomy-Core/src/main/java/me/yic/xconomy/command/core/CommandCore.java)
- 思路：
  1. 字母检测 `s.matches(".*[a-zA-Z].*")` → 手写 ASCII 字母扫描 `containsAsciiLetter`（无正则）。该检测同时拒绝 `NaN`/`Infinity`/科学计数法 `e`，**语义完全一致**。
  2. 小数位检测 `Pattern.compile("\\.\\d+")` → 提升为 `private static final Pattern DECIMAL_PATTERN`，只编译一次，仅 `matcher(s)` 每次创建（开销极小）。
- **安全论证**：正则文本与小数位判定逻辑完全相同，仅消除重复编译；负数/超限/上限校验逻辑不变（继续拒绝负金额防刷币）。

### 优化 C：`DataCon` 读路径合并 `containsKey+get` 为单次查询
- 文件：[XConomy-Core/.../data/DataCon.java](../../../XConomy-Core/src/main/java/me/yic/xconomy/data/DataCon.java)
- 思路：`getPlayerDatai` 与 `getAccountBalance` 原为 `if(containsKey) get`（两次哈希查找），改为直接 `get`，未命中（含并发移除）返回 null 再回源数据库。
- **安全论证**：`ConcurrentHashMap`/`CacheNonPlayer.bal` 中 `containsKey(k)` 为真时 `get(k)` 必非空（值非空由写入处保证）；直接 `get` 在“不存在/并发移除”时同样回源，结果与原实现一致，且更优雅地容忍并发移除。省一次哈希查找。

## 4. 行为等价性验证

benchmark 在测速前先跑**等价自检**（[BenchRunner.checkShownEquivalence](../../../benchmark/src/main/java/me/yic/xconomy/bench/BenchRunner.java) / `checkIsDoubleEquivalence`）：

- `shown`：对一组代表性余额（含 `==1` 单数分支、大额触发 `format-balance`、纯小数），断言 `BaselineDataFormat.shown(x) == DataFormat.shown(x)`，且**多次调用优化版（触发缓存命中路径）结果一致**。
- `isDouble`：对合法/非法/边界输入（负数、`NaN`、`Infinity`、超长串、`1.234` 三位小数等），断言 `BaselineIsDouble.isDouble(s) == CommandCore.isDouble(s)`。

结果：**shown=PASS, isDouble=PASS**。此外全量多模块 `mvn compile` BUILD SUCCESS，证明 API/二进制兼容。

## 5. 诚实的边界：高基数几乎全 miss 场景

`shown 高基数(N=100000>缓存容量, 几乎全 miss)` 得到 **0.92×（约 8% 回退）**：当展示的**去重余额数量远超缓存容量**且几乎每次都 miss 时，优化版需额外付出一次 `cache.get`(miss) + `cache.put`(触发 LRU 淘汰) 的开销。

为什么这不构成实际问题：
- 该场景要求**在一个紧凑循环里展示 10 万级互不相同的余额**。真实经济插件中，单次刷新周期内需展示的去重余额数 ≈ 在线玩家数 + 排行榜大小（`ranking-size` 上限 100），远小于缓存容量 4096，命中率接近 100%。
- 因此 0.92× 是**不现实的最差情形**，列入仅为如实说明缓存的开销下限；真实负载下是 17×–25×。

## 6. 复现方法

```bash
# 1) 编译并安装 Core/Stub（使 benchmark 可解析依赖）
mvn -pl XConomy-Core -am install -DskipTests

# 2) 生成 benchmark 依赖 classpath（绝对路径，避免多模块歧义）
mvn -pl benchmark -am dependency:build-classpath -Dmdep.outputFile="$PWD/benchcp.txt"

# 3) 运行（benchmark/target/classes 置前，确保 CChat/CConfig 覆盖桩生效）
mvn -pl benchmark -am compile
java -cp "benchmark/target/classes;$(cat benchcp.txt)" me.yic.xconomy.bench.BenchRunner
```

基准源码：[benchmark/src/main/java/me/yic/xconomy/bench/](../../../benchmark/src/main/java/me/yic/xconomy/bench/)。`baseline` 列为原始算法逐行拷贝（`BaselineDataFormat` / `BaselineIsDouble`），`optimized` 列为优化后 Core 实现，同进程对比，隔离环境噪声。

## 7. 刻意未改动（红线）

- **资金写入与并发**：`changeplayerdata` / `changeplayerdataWithCheck` 的 per-UUID 锁、double-check、落库失败失效缓存——2.27.0 的防丢币/防刷币语义，**一字未改**。
- **跨服同步与签名**：`SyncData` 报文结构、`ProcessSyncData` 签名前置校验、Redis KV 签名——**一字未改**。
- **SQL 落库**：相对增量、连接 null 防护、重连锁——**一字未改**。
- **数据库列精度**：`balance double(20,2)` 未动（如需更高精度需表结构迁移，超出本次范围）。

> 如未来需要进一步优化写入/落库吞吐（如批量落库、连接池调优），需另开评估，且必须在不削弱上述资金安全语义的前提下进行。
