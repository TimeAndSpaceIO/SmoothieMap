# SmoothieMap

`SmoothieMap` is a `java.util.Map` implementation with worst write (`put(k, v)`) operation latencies
more than 100 times smaller than in ordinary hash table implementations like `java.util.HashMap`.
For example, when inserting 10 million entries into `HashMap` the longest one (when about 6m
entries are already in the map) takes about 42 milliseconds. The longest insertion into
`SmoothieMap` is only 0.27 milliseconds (when about 8m entries are already inserted).

Another important property of `SmoothieMap` - on eventual growth it produces very little garbage -
about 50 times less than e. g. `HashMap` by total size of objects, that are going to be GC'ed. On
eventual shrinking, `SmoothieMap` doesn't generate any garbage, not accounting mapped keys and
values themselves, that could be GC'ed or not depending on the application logic.

Final major advantage of `SmoothieMap` over traditional `HashMap` is memory footprint itself.

<table>
  <tr>
    <th></th>
    <th><code>new SmoothieMap(expectedSize)</code></th>
    <th><code>new SmoothieMap()</code>, i. e. without expected size provided</th>
    <th><code>HashMap</code>, regardless which constructor called</th>
  </tr>
  <tr>
    <th>Java ref size</th>
    <th colspan=3>Map footprint, bytes/entry (excluding keys and values themselves)</th>
  </tr>
  <tr>
    <td>4 bytes, <code>-XX:+UseCompressedOops</code></td>
    <td>16.1-22.5</td>
    <td>17.5-23.0</td>
    <td>37.3-42.7</td>
  </tr>
  <tr>
    <td>8 bytes, <code>-XX:-UseCompressedOops</code></td>
    <td>26.7-34.3</td>
    <td>28.2-37.2</td>
    <td>58.7-69.3</td>
  </tr>
</table>

<hr>

These properties make `SmoothieMap` interesting for low latency scenarios and *real-time
applications*, e. g. implementing services that has hard latency requirements defined by SLA.

On the other hand, *amortized* performance of read (`get(k)`) and write operations on `SmoothieMap`
is approximately equal to `HashMap`'s performance (sometimes slower, sometimes faster, but always
the same ballpark, regardless map size), unlike, e. g. `java.util.TreeMap`.

If you are curious how this has been achieved and what algorithm is behind `SmoothieMap`, read the
implementation comment in [`SmoothieMap`
class](https://github.com/OpenHFT/SmoothieMap/blob/master/src/main/java/net/openhft/SmoothieMap.java)
(on top of the class body).

### Other "real-time `Map`" field players

 - [Javolution](http://javolution.org/)'s `FastMap` appears to be an ordinary open-addressing hash
 table with linear probing, i. e. has very bad latency of `put()` call, when hash table resize is
 triggered. `FastSortedMap` indeed has real-time `put()`s, but it a tree and has `log(N)` operations
 complexity, and should better be compared to `java.util.TreeMap`.

 - [`PauselessHashMap`](https://github.com/giltene/PauselessHashMap) offers truly real-time `put()`s
   with constant worst latencies (while `SmoothieMap`'s latencies still grow linearly with the map
   size, though with a very small coefficient), but has different downsides:

    - Other operations, like `remove()`, `putAll()`, `clear()`, and the derivation of keysets and
    such *will* block for pending resize operations.

    - It produces garbage on nearly the same rates as `HashMap`.

    - It runs a background Executor with resizer threads, that could be undesirable, or lead to
    problems or stalls, if resizer threads starve. `SmoothieMap` is simply single-threaded.

    - `PauselessHashMap` also has amortized read and write performance close to `HashMap`'s, but
    `PauselessHashMap` is consistently slower.

    - `PauselessHashMap` has footprint characteristics similar to `HashMap`'s, i. e. it consumes
    more memory, than `SmoothieMap`.

### Should I use SmoothieMap?

Points for:

 - :smile: You have hard latency requirements of `put()` or `remove()` operations performance on the
 map.

 - :grinning: You don't want the map to produce garbage on growing and/or shrinking (Entry objects).

 - :grinning: You are worried about `HashMap` memory footprint. `SmoothieMap` allows to reduce it.

 - :blush: You run your application on a modern, powerful CPU with wide pipeline and supporting [bit
 manipulation extensions](https://en.wikipedia.org/wiki/Bit_Manipulation_Instruction_Sets),
 preferably Intel, preferably Haswell or newer architecture. `SmoothieMap` tends to perform better
 on newer CPUs.

Points against:

 - :confused: You run your application on an outdated CPU (but desktop- of server-class)

 - :worried: Your map(s) are not very large (say smaller than of 1000 entries), particularly
 :persevere: if smaller than 32 entries. In this case even full `HashMap` resize could complete in
 less than 1 microsecond. While `SmoothieMap` cannot even be configured to hold less than 32
 entries, so if you want to hold only a few entries, you are going to waste memory.

 - :persevere: You run your application on 32-bit or mobile-class CPU, like ARM. `SmoothieMap` is
 tailored for 64-bit CPUs and should perform badly on those without fast native 64-bit arithmetic
 operations and addressing.
 
 However, a `SmoothieMap` version optimized without native (or slow) 64-bit arithmetic could be
 implemented, it's just not here yet.

 - :dizzy_face: There is some non-zero possibility that 32 or more keys collide by 30 lowest bits of
 their hash codes. In this situation `SmoothieMap` is not operational and throws
 `IllegalStateException`.

 Fortunately, unless somebody purposely inserts keys with colliding hash
 codes, performing a hash DOS attack, this is practically impossible for any decent hash code
 implementation. Moreover, you can override [`SmoothieMap.keyHashCode()`](http://openhft.github.io/SmoothieMap/apidocs/1.0/net/openhft/smoothie/SmoothieMap.html#contains(java.lang.Object)) method,
 for example adding some random salt, excluding any possibility even of hash DOS attack.

 - :dizzy_face: You run on old Java version. SmoothieMap sets Java 8 as the compatibility baseline.

### Quick start

Add the [`net.openhft:smoothie-map:1.0-rc`](http://search.maven.org/#artifactdetails%7Cnet.openhft%7Csmoothie-map%7C1.0-rc%7Cjar) dependency
to your project (you can copy a snippet for your favourite build system on the linked page).

E. g. Maven:

    <dependency>
      <groupId>net.openhft</groupId>
      <artifactId>smoothie-map</artifactId>
      <version>1.0-rc</version>
    </dependency>

Then use it in Java:

    Map<K, V> map = new net.openhft.smoothie.SmoothieMap<>();

See [JavaDocs](http://openhft.github.io/SmoothieMap/apidocs/1.0/net/openhft/smoothie/SmoothieMap.html)
for more information.

### Production use considerations

 - SmoothieMap supports Java 8 or newer only

 - SmoothieMap is licensed under [LGPL, version 3
](https://tldrlegal.com/license/gnu-lesser-general-public-license-v3-(lgpl-3))

 - There are some unit tests, including generated with `guava-testlib`.


### Anticipated questions

#### Is `SmoothieMap` safe for concurrent use from multiple threads?

No, `SmoothieMap` is not synchronized. It competes with `HashMap`, not `ConcurrentHashMap`. However,
concurrent version could be implemented naturally, in a way similar to `ConcurrentHashMap` was
implemented in JDK 5, 6 and 7.

Similarly, `SmoothieMap` could be tweaked to add some sort of LRU ordering, making it a good choice
for implementing caches.

#### How `SmoothieMap` is compared to `HashObjObjMap` from [Koloboke](https://github.com/OpenHFT/Koloboke)?

 - `HashObjObjMap` doesn't have latency guarantees, so it is similar to `HashMap` on this regard.

 - `HashObjObjMap` has smaller footprint on average in case of 4-byte Java refs
 (`-XX:+UseCompressedOops`), but with greater variance: 12-24 bytes per entry. If references are
 8-byte (`-XX:-UseCompressedOops`), `HashObjObjMap` takes even more memory than
 `SmoothieMap`: 24-48 bytes per entry, 36 bytes on average.

 - The same with performance: on average `HashObjObjMap` is faster (especially if
 keys are effectively compared by identity `==`, and/or only successful queries are performed (i. e.
 `get(key)` calls always find some value mapped for the key). But `HashObjObjMap` has greater
 variance in performance, depending on the workload.

#### What about primitive specializations for keys and/or values?

`SmoothieMap` is specially designed for `Object` keys and values. For primitive Map specializations
you would better use [Koloboke](https://github.com/OpenHFT/Koloboke) or other similar libs.

#### How `SmoothieMap` is compared to [Chronicle Map](https://github.com/OpenHFT/Chronicle-Map)

Chronicle Map stores keys and values off-heap (in shared memory), SmoothieMap is an ordinary vanilla
Java `Map` implementation. Actually SmoothieMap is the result of the idea "what if we try to move
Chronicle Map's design decisions back to the Java heap?"

<hr>

### Author

[Roman Leventov](https://github.com/leventov)