/*
 * Copyright 2025 Casper Dalgaard Nielsen
 *                Adam Yasser Tallouzi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
