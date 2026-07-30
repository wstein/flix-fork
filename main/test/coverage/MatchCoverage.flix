def classify(x: Int32): String = match x {
    case 1 => "one"
    case _ => "other"
}

def main(): Unit \ IO =
    println(classify(1))
