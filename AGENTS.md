# bluemap-worldscan

A BlueMap addon that reads the region files of a Minecraft world and shows the structures and
biomes stored in its chunks as BlueMap layers. One shaded jar, Java 25, BlueMap API 2.8.2, Gradle.
`README.md` is the user documentation and describes the behaviour; keep it in step with the code.
Cross-repo rules: the `mekbits:workspace` skill.

## Build and test

```
./gradlew build                          # needs JDK 25; compiles, tests, builds the shadow jar
bash .github/scripts/check-shadow-jar.sh # CI's jar check, after a build
./gradlew benchmarkScan -Pdimension=<folder that holds region/> [-Pruns=3] [-Pthreads=1]
```

Without a local JDK 25, run Gradle in the `gradle:<wrapper version>-jdk25` image on a copy of the
worktree. `benchmarkScan` scans a world uncapped and prints the time, the bytes allocated per chunk
and a fingerprint of the resulting state. `measureBackup` and `reduceFixtures` are described where
they are registered in `build.gradle.kts`.

## Rules

- The world is only read: region files are opened read-only and never locked, because the game
  server keeps writing them. A chunk is taken only when its header entry and external file are the
  same after its region was read (`WorldScanner`); keep that check.
- A change to the scan path keeps the `benchmarkScan` state fingerprint of a real world the same
  before and after; run it on both a pre-1.18 and a 1.18+ world.
- `ChunkReader` takes tags as adventure-nbt's typed getters on a whole tree would (any number type
  is an int, another type is missing, the last of two equal names wins), pinned by
  `ChunkReaderTagRulesTest`; a new rule test should pass against the old reader too.
- `BiomeShapes` gives the polygons a JTS union of the cells gives: `BiomeShapesMatchJtsTest`
  compares it with `JtsBiomeShapes`, the old union kept as a test-only reference.
- The defaults stay `maxChunksPerSecond` 500 and `scanThreads` 1: the addon runs inside game
  servers on modest machines, and only an instance with the CPU to itself should raise them.
- A change to what is stored bumps `LayersFile`, `Manifest` or `ScanState` `FORMAT_VERSION`, and
  an instance never replaces stored layers of a newer format (README "When a new release is
  needed").
- Committed chunk fixtures hold only the tags the addon reads (`FixtureHygieneTest`); add new ones
  with `reduceFixtures`, never as raw chunks of a private world.
- A test that opens a `RegionFile` closes it, or Windows cannot delete the test's temp folder.

## Read before the task

- A Minecraft version adds structures or biomes → `chunk/StructureIds`, `chunk/BiomeIds`,
  `layer/LayerCatalog`, `biome/BiomeColors`, and README "Layers" and "Biomes"
- Changing a config key → `config/AddonConfig`, its defaults written on first start, and README
  "Configuration"
- Releasing → `version` in `gradle.properties`; pushing a `v` tag runs `.github/workflows/release.yml`
- Reviewing or self-reviewing → `mekbits:code-review`

## Layout

- `addon/` the BlueMap enable and disable lifecycle and the per-world scan pass
- `region/`, `chunk/` reading region files and the chunk NBT
- `scan/`, `state/` the incremental scan, its pacing and the stored scan state
- `biome/`, `layer/`, `bluemap/` building the layers, storing them in a map's asset storage and
  publishing them as marker sets
- `icon/` layer icons made from the Minecraft client jar
- `src/test/.../testsupport/` fakes of BlueMap, region and chunk builders, and the measuring tools
