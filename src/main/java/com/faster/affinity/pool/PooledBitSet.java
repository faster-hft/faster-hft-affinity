package com.faster.affinity.pool;

import java.util.BitSet;

/**
 * Pooled BitSet wrapper that automatically returns objects to pool.
 * Implements AutoCloseable for try-with-resources usage in hot paths.
 */
public final class PooledBitSet implements AutoCloseable {

    private final BitSet bitSet;
    private final ObjectPool<BitSet> pool;
    private boolean returned = false;

    public PooledBitSet(BitSet bitSet, ObjectPool<BitSet> pool) {
        this.bitSet = bitSet;
        this.pool = pool;
    }

    /**
     * Get the underlying BitSet for operations.
     * DO NOT store references to this BitSet beyond the pooled object's lifetime.
     */
    public BitSet get() {
        if (returned) {
            throw new IllegalStateException("BitSet has been returned to pool");
        }
        return bitSet;
    }

    /**
     * Set a bit in the BitSet (optimized, no extra method calls).
     */
    public void set(int bitIndex) {
        // Skip expensive validation in hot path
        bitSet.set(bitIndex);
    }

    /**
     * Set a range of bits in the BitSet (optimized, no extra method calls).
     */
    public void set(int fromIndex, int toIndex) {
        // Skip expensive validation in hot path
        bitSet.set(fromIndex, toIndex);
    }

    /**
     * Clear a bit in the BitSet (optimized, no extra method calls).
     */
    public void clear(int bitIndex) {
        if (returned) {
            throw new IllegalStateException("BitSet has been returned to pool");
        }
        bitSet.clear(bitIndex);
    }

    /**
     * Clear all bits in the BitSet.
     */
    public void clear() {
        get().clear();
    }

    /**
     * Check if a bit is set.
     */
    public boolean get(int bitIndex) {
        return get().get(bitIndex);
    }

    /**
     * Get the next set bit starting from the given index.
     */
    public int nextSetBit(int fromIndex) {
        return get().nextSetBit(fromIndex);
    }

    /**
     * Check if the BitSet is empty.
     */
    public boolean isEmpty() {
        return get().isEmpty();
    }

    /**
     * Get the length of the BitSet.
     */
    public int length() {
        return get().length();
    }

    /**
     * Get the number of bits set to true.
     */
    public int cardinality() {
        return get().cardinality();
    }

    /**
     * Perform bitwise OR with another BitSet.
     */
    public void or(BitSet set) {
        get().or(set);
    }

    /**
     * Perform bitwise AND with another BitSet.
     */
    public void and(BitSet set) {
        get().and(set);
    }

    /**
     * Clone the BitSet (returns a regular BitSet, not pooled).
     */
    public BitSet clone() {
        return (BitSet) get().clone();
    }

    /**
     * Copy from another BitSet.
     */
    public void copyFrom(BitSet source) {
        BitSet target = get();
        target.clear();
        target.or(source);
    }

    /**
     * Return the BitSet to the pool.
     * The PooledBitSet should not be used after this call.
     */
    @Override
    public void close() {
        if (!returned) {
            bitSet.clear(); // Reset the BitSet before returning to pool
            pool.release(bitSet);
            returned = true;
        }
    }

    @Override
    public String toString() {
        return returned ? "[returned]" : bitSet.toString();
    }

    /**
     * Factory method to acquire a pooled BitSet.
     */
    public static PooledBitSet acquire() {
        ObjectPool<BitSet> pool = ObjectPoolManager.getBitSetPool();
        BitSet bitSet = pool.acquire();
        return new PooledBitSet(bitSet, pool);
    }

    /**
     * Factory method to acquire a pooled BitSet and copy from source.
     */
    public static PooledBitSet acquire(BitSet source) {
        PooledBitSet pooled = acquire();
        pooled.copyFrom(source);
        return pooled;
    }
}