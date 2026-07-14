package io.cyntex.runtime.engine;

import com.hazelcast.cluster.Address;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.test.TestProcessorMetaSupplierContext;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Test helper: whether a meta-supplier pins its vertex to a single processor across the whole cluster —
 * the order-preserving property the sink watermark depends on.
 *
 * <p>{@code preferredLocalParallelism() == 1} alone does not prove it: a per-member supplier reports 1 too
 * yet runs one instance on every member. The distinguishing property is that a total-parallelism-one
 * supplier hands the real processor supplier to a single member and a no-op to the rest, so resolving it
 * over several members does not yield the same supplier for all.
 */
final class TotalParallelismOne {

    private TotalParallelismOne() {
    }

    /** True when {@code meta} pins to one member when resolved over a {@code members}-member cluster. */
    static boolean pins(ProcessorMetaSupplier meta, int members) throws Exception {
        List<Address> addresses = IntStream.range(0, members)
                .mapToObj(i -> Address.createUnresolvedAddress("10.0.0." + (i + 1), 5701 + i))
                .toList();
        meta.init(new TestProcessorMetaSupplierContext()
                .setTotalParallelism(members).setLocalParallelism(1));
        Function<? super Address, ? extends ProcessorSupplier> assignment = meta.get(addresses);
        return addresses.stream().map(assignment).distinct().count() > 1;
    }
}
