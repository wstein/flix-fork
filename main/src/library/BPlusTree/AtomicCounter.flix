/*
 * Copyright 2025 Casper Dalgaard Nielsen
 *                Adam Yasser Tallouzi
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

pub mod BPlusTree.AtomicCounter {
    import java.util.concurrent.atomic.{LongAdder => JLongAdder}

    ///
    /// A thread-safe counter producing `Int32`.
    ///
    /// A `LongAdder` is used as `AtomicInteger`/`AtomicLong` appeared to perform
    /// badly under high contention.
    ///
    /// Note: `Unsynchronized` is only ever constructed when `enableLocking` is `false`, which only
    /// `--Xsequential` asks for. A plain cell then counts just as well, since there is no second
    /// thread to lose an increment to. Every operation below tests `enableLocking` rather than
    /// matching on the counter, so that the branch holding the `JLongAdder` folds away and the
    /// class becomes unreachable. See `BPlusTree.Lock` for the same construction on the lock.
    ///
    pub enum AtomicCounter[r: Region] {
        case Synchronized(JLongAdder)
        case Unsynchronized(Ref[Int32, r])
    }

    ///
    /// Returns the underlying adder of `counter`.
    ///
    /// Only call this under `enableLocking`, which is what makes the second case impossible.
    ///
    def getAdder(counter: AtomicCounter[r]): JLongAdder = match counter {
        case AtomicCounter.Synchronized(c)   => c
        case AtomicCounter.Unsynchronized(_) => unreachable!()
    }

    ///
    /// Returns the underlying cell of `counter`.
    ///
    /// Only call this when locking is compiled out, which is what makes the first case impossible.
    ///
    def getCell(counter: AtomicCounter[r]): Ref[Int32, r] = match counter {
        case AtomicCounter.Synchronized(_)   => unreachable!()
        case AtomicCounter.Unsynchronized(c) => c
    }

    ///
    /// Returns a fresh counter initialized to `initialValue`.
    ///
    pub def mkCounter(rc: Region[r], initialValue: Int32): AtomicCounter[r] \ r =
        if (Concurrent.Options.enableLocking())
            unsafe IO as r {
                let adder = new JLongAdder();
                adder.add(Int32.toInt64(initialValue));
                AtomicCounter.Synchronized(adder)
            }
        else
            AtomicCounter.Unsynchronized(Ref.fresh(rc, initialValue))

    ///
    /// Increments `counter` by `1`.
    ///
    pub def increment(counter: AtomicCounter[r]): Unit \ r =
        if (Concurrent.Options.enableLocking()) {
            let c = getAdder(counter);
            unsafe IO as r { c.increment() }
        } else {
            let cell = getCell(counter);
            Ref.put(Ref.get(cell) + 1, cell)
        }

    ///
    /// Returns the current state of `counter`.
    ///
    pub def get(counter: AtomicCounter[r]): Int32 \ r =
        if (Concurrent.Options.enableLocking()) {
            let c = getAdder(counter);
            unsafe IO as r { c.intValue() }
        } else
            Ref.get(getCell(counter))
}
