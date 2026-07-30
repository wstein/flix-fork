def choose(b: Bool): Int32 =
    if b then 1 else 2

def main(): Unit \ IO =
    println(choose(true))
