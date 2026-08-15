///
/// Measures the workloads in `docs/sequential-execution.md`, section 6.
///
/// Run it twice and compare:
///
///     ./mill flix.run docs/bench/sequential-execution.flix
///     ./mill flix.run docs/bench/sequential-execution.flix --Xsequential
///
/// The parallel solver's spread is wide enough that a single pair of runs can mislead. Run each
/// configuration a few times and compare medians.
///
mod Bench {
    import java.lang.System

    def now(): Int64 \ IO = System.nanoTime()

    ///
    /// Runs `f` three times to warm up, then `runs` times, and prints the median in milliseconds.
    ///
    /// Every result is folded into the printed checksum so that nothing measured is dead code.
    ///
    pub def bench(label: String, runs: Int32, f: Unit -> Int32 \ IO): Unit \ IO = {
        let _ = List.foldLeft((acc, _) -> acc + f(), 0, List.range(0, 3));
        let (checksum, times) = List.foldLeft((acc, _) -> {
            let (sum, ts) = acc;
            let t0 = now();
            let r = f();
            let t1 = now();
            (sum + r, ((t1 - t0) / 1000000i64) :: ts)
        }, (0, Nil), List.range(0, runs));
        let sorted = List.sort(times) |> List.toVector;
        println("${label}: ${Vector.get(runs / 2, sorted)} ms (checksum ${checksum})")
    }

    def sparseEdges(n: Int32): List[(Int32, Int32)] =
        List.range(0, n - 1) |> List.map(i -> (i, i + 1))

    def denseEdges(n: Int32): List[(Int32, Int32)] =
        List.range(0, n) |> List.flatMap(i -> (i, Int32.modulo(i + 1, n)) :: (i, Int32.modulo(i + 7, n)) :: Nil)

    def closure(es: List[(Int32, Int32)]): Int32 = {
        let facts = inject es into Edge/2;
        let p = facts <+> #{
            Path(x, y) :- Edge(x, y).
            Path(x, z) :- Path(x, y), Edge(y, z).
        };
        Vector.length(query p select (x, y) from Path(x, y))
    }

    def collections(): Int32 = {
        let m = List.range(0, 200000) |> List.map(i -> (i, i)) |> List.toMap;
        Map.count((k, _) -> k > 10, m) + Map.count((_, v) -> v < 199990, m)
    }

    ///
    /// Forces a million lazy values, each held in a list so that the thunk survives inlining and
    /// the forcing goes through the emitted `Lazy` class.
    ///
    def lazily(ls: List[Lazy[Int32]]): Int32 =
        List.foldLeft((acc, l) -> acc + force l, 0, ls)

    ///
    /// Enters and exits a million regions.
    ///
    def regions(): Int32 =
        List.foldLeft((acc, i) -> region inner {
            let a = Array#{i, i + 1, i + 2} @ inner;
            acc + Array.sum(a)
        }, 0, List.range(0, 1000000))

    def trees(): Int32 = region rc {
        let t = BPlusTree.empty(rc);
        List.forEach(i -> BPlusTree.put(i, i, t), List.range(0, 200000));
        BPlusTree.size(t)
    }

    pub def main(): Unit \ IO = {
        let sparse = sparseEdges(400);
        let dense = denseEdges(260);
        let large = List.range(0, 700) |> List.map(i -> (i, Int32.modulo(i + 1, 700)));
        let lazies = List.range(0, 1000000) |> List.map(i -> lazy (i + 1));
        bench("datalog-sparse", 9, _ -> checked_ecast(closure(sparse)));
        bench("datalog-dense", 9, _ -> checked_ecast(closure(dense)));
        bench("datalog-large", 9, _ -> checked_ecast(closure(large)));
        bench("collections", 9, _ -> checked_ecast(collections()));
        bench("lazy", 9, _ -> checked_ecast(lazily(lazies)));
        bench("regions", 9, _ -> checked_ecast(regions()));
        bench("bplustree", 9, _ -> checked_ecast(trees()))
    }
}

def main(): Unit \ IO = Bench.main()
