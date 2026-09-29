/*
 * Copyright 2025 Casper Dalgaard Nielsen
 *                Adam Yasser Tallouzi
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

pub mod Fixpoint3.ReadWriteLock {
    import java.util.concurrent.locks.{ReadWriteLock => JReadWriteLock}
    import java.util.concurrent.locks.{StampedLock => JStampedLock}
    use Fixpoint3.ReadWriteLock.{Synchronized, Unsynchronized}

    ///
    /// Returns the underlying lock of `lock`.
    ///
    /// Only call this under `enableLocking`, which is what makes the second case impossible.
    ///
    def getJavaLock(lock: ReadWriteLock[r]): JReadWriteLock = match lock {
        case Synchronized(l) => l
        case Unsynchronized  => unreachable!()
    }

    pub def mkLock(_: Region[r]): ReadWriteLock[r] \ r =
        if (Concurrent.Options.enableLocking())
            Synchronized(unsafe IO as r { new JStampedLock().asReadWriteLock() })
        else
            Unsynchronized

    pub def readLock(lock: ReadWriteLock[r]): Unit \ r =
        if (Concurrent.Options.enableLocking()) {
            let l = getJavaLock(lock);
            unsafe IO as r { l.readLock().lock() }
        } else
            ()

    pub def writeLock(lock: ReadWriteLock[r]): Unit \ r =
        if (Concurrent.Options.enableLocking()) {
            let l = getJavaLock(lock);
            unsafe IO as r { l.writeLock().lock() }
        } else
            ()

    pub def unlockRead(lock: ReadWriteLock[r]): Unit \ r =
        if (Concurrent.Options.enableLocking()) {
            let l = getJavaLock(lock);
            unsafe IO as r { l.readLock().unlock() }
        } else
            ()

    pub def unlockWrite(lock: ReadWriteLock[r]): Unit \ r =
        if (Concurrent.Options.enableLocking()) {
            let l = getJavaLock(lock);
            unsafe IO as r { l.writeLock().unlock() }
        } else
            ()
}
