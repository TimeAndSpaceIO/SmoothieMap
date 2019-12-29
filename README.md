# SmoothieMap

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.timeandspace/smoothie-map/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.timeandspace/smoothie-map)
[![Build Status](https://travis-ci.org/TimeAndSpaceIO/SmoothieMap.svg?branch=2.x)](https://travis-ci.org/TimeAndSpaceIO/SmoothieMap)

SmoothieMap is a `Map` implementation for Java with the lowest memory usage and absence of rehash latency spikes.
Under the hood, it is a version of [extendible hashing](https://en.wikipedia.org/wiki/Extendible_hashing) with 48-slot
mini segments.

This project also includes a proof-of-concept implementation of [SwissTable algorithm](
https://abseil.io/blog/20180927-swisstables) in Java: see [`SwissTable.java`](
https://github.com/TimeAndSpaceIO/SmoothieMap/blob/2.x/src/main/java/io/timeandspace/smoothie/SwissTable.java).

See [this post](https://medium.com/@leventov/smoothiemap-2-the-lowest-memory-hash-table-ever-6bebd06780a3) for a more
detailed introduction, performance and memory comparisons, etc.

## Usage

Maven:
```xml
<dependency>
  <groupId>io.timeandspace</groupId>
  <artifactId>smoothie-map</artifactId>
  <version>2.0.2</version>
</dependency>
```

Then, in Java:
```java
Map<String, String> myMap = SmoothieMap.<String, String>newBuilder().build();
```

See [**Javadocs**](http://timeandspaceio.github.io/SmoothieMap/api/io/timeandspace/smoothie/package-summary.html).
