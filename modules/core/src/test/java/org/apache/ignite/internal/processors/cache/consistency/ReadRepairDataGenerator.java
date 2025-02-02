/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.consistency;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteBinary;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.cache.ReadRepairStrategy;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.CacheObjectContext;
import org.apache.ignite.internal.processors.cache.CacheObjectImpl;
import org.apache.ignite.internal.processors.cache.GridCacheAdapter;
import org.apache.ignite.internal.processors.cache.GridCacheEntryEx;
import org.apache.ignite.internal.processors.cache.GridCacheOperation;
import org.apache.ignite.internal.processors.cache.IgniteInternalCache;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersionManager;
import org.apache.ignite.internal.processors.cacheobject.IgniteCacheObjectProcessor;
import org.apache.ignite.internal.processors.dr.GridDrType;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.testframework.GridTestUtils;

import static org.apache.ignite.cache.CacheAtomicityMode.ATOMIC;
import static org.apache.ignite.cache.CacheMode.REPLICATED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 *
 */
public class ReadRepairDataGenerator {
    /** Key. */
    private final AtomicInteger incrementalKey = new AtomicInteger();

    /** Cache name. */
    private final String cacheName;

    /** Nodes aware of the entry value class. */
    private final List<Ignite> clsAwareNodes;

    /** External class loader. */
    private final ClassLoader extClsLdr;

    /** Primary node. */
    private final BiFunction<Integer, String, Ignite> primaryNode;

    /** Backup nodes. */
    private final BiFunction<Integer, String, List<Ignite>> backupNodes;

    /** Server nodes count. */
    private final Supplier<Integer> serverNodesCnt;

    /** Backups count. */
    private final Supplier<Integer> backupsCnt;

    /**
     * @param cacheName Cache name.
     * @param clsAwareNodes Class aware nodes.
     * @param extClsLdr Ext class loader.
     * @param primaryNode Primary node.
     * @param backupNodes Backup nodes.
     * @param serverNodesCnt Server nodes count.
     * @param backupsCnt Backups count.
     */
    public ReadRepairDataGenerator(
        String cacheName,
        List<Ignite> clsAwareNodes,
        ClassLoader extClsLdr,
        BiFunction<Integer, String, Ignite> primaryNode,
        BiFunction<Integer, String, List<Ignite>> backupNodes,
        Supplier<Integer> serverNodesCnt,
        Supplier<Integer> backupsCnt) {
        this.cacheName = cacheName;
        this.clsAwareNodes = Collections.unmodifiableList(clsAwareNodes);
        this.extClsLdr = extClsLdr;
        this.primaryNode = primaryNode;
        this.backupNodes = backupNodes;
        this.serverNodesCnt = serverNodesCnt;
        this.backupsCnt = backupsCnt;
    }

    /**
     * Generates inconsistent data and checks it repairs properly.
     *
     * @param initiator Node used to perform the Read Repair operation during the check.
     * @param cnt       Count of entries to be generated/checked.
     * @param raw       Raw read flag. True means required GetEntry() instead of get().
     * @param async     Async read flag.
     * @param misses    Skiping entries generation on some owners.
     * @param nulls     Removing entries after the generation on some nodes.
     * @param binary    Read Repair will be performed with keeping data binary.
     * @param strategy  Strategy to perform the Read Repair.
     * @param c         Lambda consumes generated data and performs the Read Repair check.
     */
    public void generateAndCheck(
        Ignite initiator,
        int cnt,
        boolean raw,
        boolean async,
        boolean misses,
        boolean nulls,
        boolean binary,
        ReadRepairStrategy strategy,
        Consumer<ReadRepairData> c) throws Exception {
        IgniteCache<Integer, Object> cache = initiator.getOrCreateCache(cacheName);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        ReadRepairStrategy[] strategies = ReadRepairStrategy.values();

        for (int i = 0; i < rnd.nextInt(1, 10); i++) {
            ReadRepairStrategy keyStrategy = strategy != null ? strategy : strategies[rnd.nextInt(strategies.length)];

            Map<Integer, InconsistentMapping> results = new TreeMap<>(); // Sorted to avoid warning.

            try {
                for (int j = 0; j < cnt; j++) {
                    int curKey = incrementalKey.incrementAndGet();

                    InconsistentMapping res = setDifferentValuesForSameKey(curKey, misses, nulls, keyStrategy);

                    results.put(curKey, res);
                }

                for (Ignite node : G.allGrids()) { // Check that cache filled properly.
                    Map<Integer, Object> all =
                        node.getOrCreateCache(cacheName).<Integer, Object>withKeepBinary().getAll(results.keySet());

                    for (Map.Entry<Integer, Object> entry : all.entrySet()) {
                        Integer key = entry.getKey();
                        Object val = entry.getValue();

                        T2<Object, GridCacheVersion> valVer = results.get(key).mappingBin.get(node);

                        Object exp;

                        if (valVer != null)
                            exp = valVer.get1(); // Should read from itself (backup or primary).
                        else
                            exp = results.get(key).primaryBin; // Or read from primary (when not a partition owner).

                        assertEquals(exp, val);
                    }
                }

                c.accept(new ReadRepairData(cache, results, raw, async, keyStrategy, binary));
            }
            catch (Throwable th) {
                StringBuilder sb = new StringBuilder();

                sb.append("Read Repair test failed [")
                    .append("cache=").append(cache.getName())
                    .append(", strategy=").append(keyStrategy)
                    .append("]\n");

                for (Map.Entry<Integer, InconsistentMapping> entry : results.entrySet()) {
                    sb.append("Key: ").append(entry.getKey()).append("\n");

                    InconsistentMapping mapping = entry.getValue();

                    sb.append(" Generated data [primary=").append(unwrapBinaryIfNeeded(mapping.primaryBin))
                        .append(", repaired=").append(unwrapBinaryIfNeeded(mapping.repairedBin))
                        .append(", repairable=").append(mapping.repairable)
                        .append(", consistent=").append(mapping.consistent)
                        .append("]\n");

                    sb.append("  Distribution: \n");

                    for (Map.Entry<Ignite, T2<Object, GridCacheVersion>> dist : mapping.mappingBin.entrySet()) {
                        sb.append("   Node: ").append(dist.getKey().name()).append("\n");
                        sb.append("    Value: ").append(unwrapBinaryIfNeeded(dist.getValue().get1())).append("\n");
                        sb.append("    Version: ").append(dist.getValue().get2()).append("\n");
                    }

                    sb.append("\n");
                }

                throw new Exception(sb.toString(), th);
            }
        }
    }

    /**
     * Generated entries count.
     */
    public int generated() {
        return incrementalKey.get();
    }

    /**
     *
     */
    private InconsistentMapping setDifferentValuesForSameKey(
        int key,
        boolean misses,
        boolean nulls,
        ReadRepairStrategy strategy) throws Exception {
        List<Ignite> nodes = new ArrayList<>();
        Map<Ignite, T2<Object, GridCacheVersion>> mapping = new HashMap<>();

        Ignite primary = primaryNode.apply(key, cacheName);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (rnd.nextBoolean()) { // Reversed order.
            nodes.addAll(backupNodes.apply(key, cacheName));
            nodes.add(primary);
        }
        else {
            nodes.add(primary);
            nodes.addAll(backupNodes.apply(key, cacheName));
        }

        if (rnd.nextBoolean()) // Random order.
            Collections.shuffle(nodes);

        IgniteInternalCache<Integer, Object> internalCache = null;

        for (Ignite ignite : G.allGrids()) {
            if (!ignite.configuration().isClientMode()) {
                internalCache = ((IgniteEx)ignite).cachex(cacheName);

                break;
            }
        }

        GridCacheVersionManager mgr = ((GridCacheAdapter)internalCache.cache()).context().shared().versions();

        int incVal = 0;
        Object primVal = null;
        Collection<T2<Object, GridCacheVersion>> vals = new ArrayList<>();

        if (misses) {
            List<Ignite> keeped = nodes.subList(0, rnd.nextInt(1, nodes.size()));

            nodes.stream()
                .filter(node -> !keeped.contains(node))
                .forEach(node -> {
                    T2<Object, GridCacheVersion> nullT2 = new T2<>(null, null);

                    vals.add(nullT2);
                    mapping.put(node, nullT2);
                });  // Recording nulls (missed values).

            nodes = keeped;
        }

        boolean rmvd = false;

        boolean wrap = rnd.nextBoolean();
        boolean incVer = rnd.nextBoolean();

        GridCacheVersion ver = null;

        for (Ignite node : nodes) {
            IgniteInternalCache<Integer, Object> cache = ((IgniteEx)node).cachex(cacheName);

            GridCacheAdapter<Integer, Object> adapter = (GridCacheAdapter<Integer, Object>)cache.<Integer, Object>cache();

            GridCacheEntryEx entry = adapter.entryEx(key);

            if (ver == null || incVer)
                ver = mgr.next(entry.context().kernalContext().discovery().topologyVersion()); // Incremental version.

            boolean rmv = nulls && (!rmvd || rnd.nextBoolean());

            Object val = rmv ?
                null :
                wrapTestValueIfNeeded(wrap, rnd.nextBoolean()/*increment or same as previously*/ ? ++incVal : incVal);

            T2<Object, GridCacheVersion> valVer = new T2<>(val, val != null ? ver : null);

            vals.add(valVer);
            mapping.put(node, valVer);

            GridKernalContext kctx = ((IgniteEx)node).context();

            byte[] bytes = marshalValue(entry.context().cacheObjectContext(), rmv ? -1 : val); // Incremental value.

            try {
                kctx.cache().context().database().checkpointReadLock();

                boolean init = entry.initialValue(
                    new CacheObjectImpl(null, bytes),
                    ver,
                    0,
                    0,
                    false,
                    AffinityTopologyVersion.NONE,
                    GridDrType.DR_NONE,
                    false,
                    false);

                if (rmv) {
                    if (cache.configuration().getAtomicityMode() == ATOMIC)
                        entry.innerUpdate(
                            ver,
                            ((IgniteEx)node).localNode().id(),
                            ((IgniteEx)node).localNode().id(),
                            GridCacheOperation.DELETE,
                            null,
                            null,
                            false,
                            false,
                            false,
                            false,
                            null,
                            false,
                            false,
                            false,
                            false,
                            false,
                            AffinityTopologyVersion.NONE,
                            null,
                            GridDrType.DR_NONE,
                            0,
                            0,
                            null,
                            false,
                            false,
                            null,
                            null,
                            null,
                            null,
                            false);
                    else
                        entry.innerRemove(
                            null,
                            ((IgniteEx)node).localNode().id(),
                            ((IgniteEx)node).localNode().id(),
                            false,
                            false,
                            false,
                            false,
                            false,
                            null,
                            AffinityTopologyVersion.NONE,
                            CU.empty0(),
                            GridDrType.DR_NONE,
                            null,
                            null,
                            null,
                            1L);

                    rmvd = true;

                    assertFalse(entry.hasValue());
                }
                else
                    assertTrue(entry.hasValue());

                assertTrue("iterableKey " + key + " already inited", init);

                if (node.equals(primary))
                    primVal = val;
            }
            finally {
                ((IgniteEx)node).context().cache().context().database().checkpointReadUnlock();
            }
        }

        assertEquals(vals.size(), mapping.size());
        assertEquals(vals.size(),
            (int)(internalCache.configuration().getCacheMode() == REPLICATED ? serverNodesCnt.get() : backupsCnt.get() + 1));

        if (!misses && !nulls)
            assertNotNull(primVal); // Primary value set.

        Object repaired;

        boolean consistent;
        boolean repairable;

        if (vals.stream().distinct().count() == 1) { // Consistent value.
            consistent = true;
            repairable = true;
            repaired = vals.iterator().next().getKey();
        }
        else {
            consistent = false;

            switch (strategy) {
                case LWW:
                    if (misses || rmvd || !incVer) {
                        repaired = incomparableTestValue();

                        repairable = false;
                    }
                    else {
                        repaired = wrapTestValueIfNeeded(wrap, incVal);

                        repairable = true;
                    }

                    break;

                case PRIMARY:
                    repaired = primVal;

                    repairable = true;

                    break;

                case RELATIVE_MAJORITY:
                    repaired = incomparableTestValue();

                    Map<T2<Object, GridCacheVersion>, Integer> counts = new HashMap<>();

                    for (T2<Object, GridCacheVersion> val : vals) {
                        counts.putIfAbsent(val, 0);

                        counts.compute(val, (k, v) -> v + 1);
                    }

                    int[] sorted = counts.values().stream().sorted(Comparator.reverseOrder()).mapToInt(v -> v).toArray();

                    int max = sorted[0];

                    repairable = !(sorted.length > 1 && sorted[1] == max);

                    if (repairable)
                        for (Map.Entry<T2<Object, GridCacheVersion>, Integer> count : counts.entrySet())
                            if (count.getValue().equals(max)) {
                                repaired = count.getKey().getKey();

                                break;
                            }

                    break;

                case REMOVE:
                    repaired = null;

                    repairable = true;

                    break;

                case CHECK_ONLY:
                    repaired = incomparableTestValue();

                    repairable = false;

                    break;

                default:
                    throw new UnsupportedOperationException(strategy.toString());
            }
        }

        IgniteBinary igniteBinary = clsAwareNodes.get(0).binary();

        Object primValBin = igniteBinary.toBinary(primVal);
        Object repairedBin = igniteBinary.toBinary(repaired);

        Map<Ignite, T2<Object, GridCacheVersion>> mappingBin = mapping.entrySet().stream().collect(
            Collectors.toMap(
                Map.Entry::getKey,
                (entry) -> {
                    T2<Object, GridCacheVersion> t2 = entry.getValue();

                    return new T2<>(igniteBinary.toBinary(t2.getKey()), t2.getValue());
                }));

        return new InconsistentMapping(mappingBin, primValBin, repairedBin, repairable, consistent);
    }

    /**
     *
     */
    private Object incomparableTestValue() {
        return new IncomparableClass();
    }

    /**
     * @param wrap Wrap.
     * @param val  Value.
     */
    private Object wrapTestValueIfNeeded(boolean wrap, Integer val) throws ReflectiveOperationException {
        if (wrap) {
            // Some nodes will be unable to deserialize this object.
            // Checking that Read Repair feature cause no `class not found` problems.
            Class<?> clazz = extClsLdr.loadClass("org.apache.ignite.tests.p2p.cache.PersonKey");

            Object obj = clazz.newInstance();

            GridTestUtils.setFieldValue(obj, "id", val);

            return obj;
        }
        else
            return val;
    }

    /**
     * @param obj Object.
     */
    public static Object unwrapBinaryIfNeeded(Object obj) {
        if (obj instanceof BinaryObject) {
            BinaryObject valObj = (BinaryObject)obj;

            return valObj.deserialize();
        }
        else
            return obj;
    }

    /**
     * @param ctx Context.
     * @param val Value.
     */
    byte[] marshalValue(CacheObjectContext ctx, Object val) throws IgniteCheckedException {
        IgniteCacheObjectProcessor clsAwareProc = ((IgniteEx)clsAwareNodes.get(0)).context().cacheObjects();

        return clsAwareProc.marshal(ctx, val);
    }

    /**
     *
     */
    public static final class ReadRepairData {
        /** Initiator's cache. */
        public final IgniteCache<Integer, Object> cache;

        /** Generated data across topology per key mapping. */
        public final Map<Integer, InconsistentMapping> data;

        /** Raw read flag. True means required GetEntry() instead of get(). */
        public final boolean raw;

        /** Async read flag. */
        public final boolean async;

        /** Read with keepBinary flag. */
        public final boolean binary;

        /** Strategy. */
        public final ReadRepairStrategy strategy;

        /**
         *
         */
        public ReadRepairData(
            IgniteCache<Integer, Object> cache,
            Map<Integer, InconsistentMapping> data,
            boolean raw,
            boolean async,
            ReadRepairStrategy strategy,
            boolean binary) {
            this.cache = binary ? cache.withKeepBinary() : cache;
            this.data = Collections.unmodifiableMap(data);
            this.raw = raw;
            this.async = async;
            this.binary = binary;
            this.strategy = strategy;
        }

        /**
         *
         */
        public boolean repairable() {
            return data.values().stream().allMatch(mapping -> mapping.repairable);
        }
    }

    /**
     *
     */
    public static final class InconsistentMapping {
        /** Value per node, binary. */
        public final Map<Ignite, T2<Object, GridCacheVersion>> mappingBin;

        /** Primary node's value, binary. */
        public final Object primaryBin;

        /** Expected repaired result, binary. */
        public final Object repairedBin;

        /** Inconsistency can be repaired using the specified strategy. */
        public final boolean repairable;

        /** Consistent value. */
        public final boolean consistent;

        /**
         * @param mappingBin Mapping bin.
         * @param primaryBin Primary.
         * @param repairedBin Repaired bin.
         * @param repairable Repairable.
         * @param consistent Consistent.
         */
        public InconsistentMapping(
            Map<Ignite, T2<Object, GridCacheVersion>> mappingBin,
            Object primaryBin,
            Object repairedBin,
            boolean repairable,
            boolean consistent) {
            this.mappingBin = Collections.unmodifiableMap(mappingBin);
            this.primaryBin = primaryBin;
            this.repairedBin = repairedBin;
            this.repairable = repairable;
            this.consistent = consistent;
        }
    }

    /**
     *
     */
    private static class IncomparableClass {
        /**
         * {@inheritDoc}
         */
        @Override public boolean equals(Object obj) {
            fail("Shound never be compared.");

            return false;
        }
    }
}
