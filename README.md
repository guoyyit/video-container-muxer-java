# Video Container Muxer

A self-contained, in-process audio/video container muxer with timeline
processing. Multiple tracks are muxed by timestamp into a single container,
with re-reading, random access, and interruption recovery. No external
services or real media files are required — all test material is generated
in-process.

## Features

- **Multi-track muxing**: video frames and audio packets are interleaved by
  decode timestamp (DTS) into one container. Round-trip preserves track count,
  codec parameters, metadata, and timestamps. Output is byte-identical across
  repeated muxes regardless of enqueue order or producer concurrency.
- **Presentation reordering**: B-frames (PTS != DTS) and out-of-order arrival
  are reordered to correct presentation order. PTS never flows backward;
  intervals match input. Duration is determined by actual content;
  audio/video end misalignment is explicit.
- **Keyframe index + random access**: seek snaps to the keyframe at or before
  the requested PTS. Distinguishes HIT / BEFORE_FIRST_KEYFRAME / NO_KEYFRAME /
  BEYOND_END.
- **Interruption recovery**: a cancelled write leaves the file without a
  trailer; reopening fails stably as incomplete. Rewriting truncates and never
  depends on the old tail.
- **Concurrent producers**: multiple producer threads feed distinct tracks; a
  single writer thread interleaves deterministically. Bounded memory
  (configurable global + per-track budgets) with backpressure.
- **Validation**: track parameters and timestamps are validated before write
  and read; contradictions are rejected with track id + field reasons.
- **Streaming**: reads and seeks never load all content into memory.

## Quickstart

```java
Path path = Path.of("out.mux");

// Mux
try (ContainerMuxer muxer = ContainerMuxer.create(path)) {
    int video = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
            .codec("AVC").timescale(1_000_000).width(1920).height(1080).build());
    int audio = muxer.addTrack(TrackConfig.builder(TrackType.AUDIO)
            .codec("AAC").timescale(44100).sampleRate(44100).channels(2).bitsPerSample(16).build());
    muxer.start();
    try (TrackOutput v = muxer.output(video); TrackOutput a = muxer.output(audio)) {
        v.write(Frame.builder().pts(0).dts(0).duration(40000).keyframe(true).data(frameData).build());
        a.write(Frame.builder().pts(0).dts(0).duration(1024).keyframe(true).data(audioData).build());
        // ...
    }
}

// Read + seek
try (ContainerReader reader = ContainerReader.open(path)) {
    for (TrackInfo t : reader.tracks()) { /* ... */ }
    SeekOutcome outcome = reader.seek(video, 60_000_000); // 60s
    Iterator<Frame> frames = reader.framesFrom(video, 60_000_000);
    // ...
}
```

## Run the demo

```bash
mvn compile
java -cp "target/classes:$(find ~/.m2/repository -name '*.jar' | tr '\n' ':')" \
  com.github.guoyyit.videocontainermuxerjava.demo.MuxerDemoRunner
```

The demo generates ~2 minutes of test material (3000 video frames + 5167 audio
packets), muxes it with concurrent producers, reads it back, seeks, and
reports timing and memory.

## Documentation

- [Container format](docs/format.md)
- [Time model](docs/time-model.md) (PTS/DTS, duration, reorder windows)
- [Seek semantics](docs/seek-semantics.md)
- [Configuration](docs/configuration.md)

## Build

```bash
mvn test
```

## Project layout

```
src/main/java/.../mux/
  ContainerMuxer.java       # single-writer muxer with bounded queues
  ContainerReader.java      # streaming reader with validation
  FrameIterator.java        # presentation-order streaming iterator
  TrackConfig.java          # track parameters + validation
  MuxerConfig.java          # muxer tunables
  Frame.java, TrackInfo.java, TrackOutput.java
  SeekStatus.java, SeekOutcome.java
  ContainerFormat.java      # binary format constants + block IO
  ContainerException.java, ContainerValidationException.java,
  ContainerIncompleteException.java, ContainerCorruptException.java
src/test/java/.../mux/
  ContainerMuxerTest.java       # round-trip, reproducibility, duration
  PresentationReorderTest.java  # B-frames, out-of-order arrival
  SeekTest.java                 # seek outcomes
  InterruptionTest.java         # cancel/recovery
  ConcurrentMuxerTest.java      # multi-producer, bounded memory
  ValidationTest.java           # invalid tracks/frames
  CorruptTailTest.java          # corruption detection
  StreamingTest.java            # large input, bounded memory
```
