/// A single-stage pipeline stays on one line.
def evens(xs: List[Int32]): List[Int32] =
  xs |> List.filter(x -> Int32.remainder(x, 2) == 0)

/// A pipeline of several stages gets one stage per line.
def total(xs: List[Int32]): Int32 =
  xs |> List.filter(x -> x > 0) |> List.map(x -> x*2) |> List.sum

def main(): Unit \ IO =
  println(total(List#{1, -2, 3}));
  println(evens(List#{1, 2, 3, 4}))
