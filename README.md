# video-container-muxer-java

进程内音视频封装与时间轴处理库：把多条轨道的帧按各自时间戳封装进单一容器
文件（自定义二进制格式 `VCMX`），并支持重新读取、按呈现时间随机定位。整条
链路不依赖任何外部服务或真实媒体文件——测试素材由 `TestMedia` 在进程内确定
性生成。

## 模块布局

```
src/main/java/.../muxer/
  Muxer.java            单写入方封装器（重排、背压、内存上界、EOS）
  Demuxer.java          流式读取器（打开时扫描索引、CRC 校验、随机定位）
  ContainerFormat.java  VCMX 二进制布局与底层读写
  TrackValidator.java   轨道与帧校验（错误信息可定位到轨道与字段）
  TrackSpec/Frame/...   不可变值类型
src/test/java/.../muxer/
  TestMedia.java        进程内确定性测试素材（含 B 帧重排的 GOP 结构）
  *Test.java            乱序重排 / 定位 / 中断恢复 / 并发 / 校验 / 大输入流式
```

## 容器格式（VCMX）

全部大端序：

```
header  := "VCMX" version(u32) trackCount(u32) track*        # 轨道按 trackId 排序
track   := trackId type codec timescale width height
           sampleRate channels (key value)*                  # 元数据键值按字典序
chunk   := 'C' trackId pts dts duration flags payloadLen
           payload crc32(u32)                                # CRC 覆盖 trackId..payload
footer  := 'I' (trackId (pts chunkOffset)* endPts frameCount)*
trailer := footerOffset(i64) completeFlag(u8) "VCMX"         # 固定 13 字节于文件尾
```

- 轨道表、元数据、索引全部排序后写出；chunk 按 `(pts微秒, trackId)` 全局有序。
  因此**同一份输入帧集合 + 同一套参数 ⇒ 逐字节相同的输出**，与写入顺序、
  线程交错无关（同一轨道内重复 pts 会被拒绝，保证排序键唯一）。
- 每个 chunk 自带 CRC-32，读取时校验；半帧、残缺尾部不会被当成有效内容。
- 缺少合法 trailer 或 `completeFlag=0` ⇒ 文件被判定为 `INCOMPLETE`（写入被
  取消/中断）；chunk 结构非法 ⇒ `CORRUPT`。判定在打开时完成且对同一文件
  稳定不变。

## 时间模型

- 每条轨道有自己的 `timescale`（每秒 tick 数），帧的 `pts`/`dts`/`duration`
  都以该轨道 timescale 为单位；跨轨道比较统一换算为微秒。
- `pts` 是呈现时间，`dts` 是解码时间；允许 `dts < pts`（B 帧等乱序解码），
  但 `dts > pts`、负值、超出 `Long.MAX_VALUE/1e6` 的时间戳都会被校验拒绝。
- 帧可以乱序到达（解码序、跨轨道任意交错）。Muxer 内部按轨道做优先队列
  重排，只有当**所有已声明轨道的前沿都越过某帧一个重排窗口**时才落盘，
  因此输出中呈现时间严格单调不倒流，相邻帧呈现间隔与输入完全一致。
- **时长语义**：轨道时长 = 该轨道实际内容的 `max(pts + duration)`；容器时长
  = 各轨道时长的最大值。音视频末尾不对齐时，容器时长取最长轨道——缺口
  不会被填充，也不会被截断到较短轨道。

## 定位语义（seek）

`Demuxer.seek(trackId, pts)` 使用打开时建立的关键帧索引（内存中仅保存
`(pts, 文件偏移)`，不保存载荷），结果确定性且五种情形可区分：

| SeekStatus | 含义 |
|---|---|
| `EXACT` | 请求时间恰好命中某帧 pts |
| `IN_GAP` | 请求落在帧间空隙；`targetPts` 为其后第一帧 |
| `BEFORE_FIRST_KEYFRAME` | 请求早于该轨首个关键帧 |
| `NO_KEYFRAMES` | 该轨没有任何关键帧，无法随机定位 |
| `OUT_OF_RANGE` | 请求为负或超出轨道时长 |

成功时读游标被重置到**不晚于请求时间的最近关键帧**，随后的 `nextFrame()`
即从解码重启点开始；越界等失败情形不移动游标。重复定位结果完全一致。

## 并发与背压模型

- 多条轨道可由不同生产者线程并发填充；所有文件 I/O 在单一锁内串行，
  容器只被单一逻辑写入方落盘，不会出现交错错误。
- 内存上界由 `MuxerConfig.maxBufferedBytes` 配置（默认 8 MiB）：缓冲总量
  超过上界时生产者阻塞——**除了前沿（最慢）轨道的生产者**，因为它的
  推进正是其他轨道排干缓冲的前提，阻塞它会死锁。因此内存占用约为
  `maxBufferedBytes + 前沿轨道一个重排窗口`，不随轨道数或运行时长增长。
- 生产者写完自己的轨道后必须调用 `endOfTrack(trackId)`（或统一 `close()`）。
  已结束的轨道不再锚定重排前沿；否则一条不再产出的轨道会让其他轨道在
  缓冲填满后阻塞（这是背压而非错位）。
- 配置契约：`maxBufferedBytes` 需覆盖**一个重排窗口的聚合数据量**
  （默认窗口 1 秒）。窗口内乱序到达完全透明；低于已落盘水位线的迟到帧
  会被拒绝并抛出可定位的 `ValidationException`。

## 校验

写入与读取前都会校验：重复 trackId、轨道参数互相矛盾（视频轨带音频参数
等）、非法 timescale、负/超界时间戳、`dts > pts`、空载荷、重复 pts、
EOS 后再写帧等，均抛出 `ValidationException`，消息与字段
（`trackId()` / `field()`）可定位到具体轨道与字段。通过校验的容器必然
可读（`ValidationTest.validContainerStillReadableAfterValidationOfOthers`）。

## 中断与恢复

- `abort()` 取消写入：缓冲中的帧按确定序落盘（保留最大有效前缀），但不写
  footer/trailer，重新打开时稳定判定为 `INCOMPLETE`；已落盘部分可正常读取。
- 进程崩溃导致的截断（半帧、缺 trailer）同样在打开时判定为 `INCOMPLETE`，
  残缺尾部永不作为有效内容返回；重复读取结论一致。
- 载荷字节被篡改时结构仍完整（`COMPLETE`），但读到该 chunk 时 CRC 校验
  确定性失败（`CorruptContainerException`）。
- 重新写入只需在全新路径上创建新 `Muxer`，不读取、不依赖任何损坏的尾部。

## 性能量级（实测，StreamingTest，17,250 帧 / 61.5 MB）

| 操作 | 耗时 | 内存 |
|---|---|---|
| 单次封装（写 61.5 MB） | ~0.5 s（≈120 MB/s） | 上界 = `maxBufferedBytes` + 一个重排窗口 |
| 打开 + 索引扫描 | ~0.6 s（O(帧数)，跳过载荷） | O(帧数)×约 100 B 索引项（实测 1.6 MB，远小于文件） |
| 全量流式读取 | ~1.0 s | O(1) 载荷（每次一帧） |
| 单次定位 | ~1 ms（索引二分/扫描 + GOP 内顺序扫描） | O(1) 额外 |

读取与定位从不把全部内容载入内存：打开时只顺序扫过 chunk 头（载荷跳过），
常驻仅关键帧索引与每轨统计；帧载荷按需逐帧读取并即时 CRC 校验。

## 运行测试

```
mvn test
```

覆盖：往返一致性、字节级可复现（乱序/洗牌/并发到达）、B 帧重排与单调性、
时长语义、随机定位五种情形、中断/截断/篡改恢复、并发写入与内存上界、
非法轨道与非法时间戳、大输入流式处理。
