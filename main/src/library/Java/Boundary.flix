/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

pub mod Java.Boundary {
    import java.lang.Object
    import dev.flix.runtime.OpaqueHandle
    import dev.flix.runtime.OpaqueHandleBridge
    import java.lang.Boolean
    import java.lang.Character
    import java.lang.Byte
    import java.lang.Short
    import java.lang.Integer
    import java.lang.Long
    import java.lang.Float
    import java.lang.Double
    import java.util.Optional
    import java.util.LinkedHashSet
    import java.util.LinkedHashMap
    import java.util.{List => JList, Collection => JCollection, Set => JSet, Map => JMap}

    /// Explicit opaque crossing. Only the compiler supplies the stable type tag at the boundary.
    pub enum Opaque[t] { case Opaque(t) }

    /// Compiler-internal tagged conversion, not an implicit missing-instance fallback.
    pub def pack(key: String, name: String, value: Opaque[a]): OpaqueHandle[Object] \ IO =
        OpaqueHandleBridge.wrap(key, name, unchecked_cast(value as Object))

    /// Checks the type tag before the cast, including when a caller returns a different handle.
    pub def unpack(key: String, name: String, value: OpaqueHandle[Object]): Opaque[a] \ IO =
        unchecked_cast(OpaqueHandleBridge.unwrap(key, name, value) as Opaque[a])

    /// Deep result conversion; primitive instances box only when a container asks for them.
    pub trait JavaResult[t: Type] {
        type Out[t]: Type
        type Aef[t]: Eff = {}
        pub def toJava(x: t): JavaResult.Out[t] \ JavaResult.Aef[t]
    }

    /// Deep argument conversion selected by the original Flix parameter type.
    pub trait JavaArgument[t: Type] {
        type In[t]: Type
        type Aef[t]: Eff = {}
        pub def toFlix(x: JavaArgument.In[t]): t \ JavaArgument.Aef[t]
    }

    instance JavaResult[Bool] {
        type Out = Boolean
        pub def toJava(x: Bool): Boolean = Boolean.valueOf(x)
    }
    instance JavaArgument[Bool] {
        type In = Boolean
        pub def toFlix(x: Boolean): Bool = x.booleanValue()
    }

    instance JavaResult[Char] {
        type Out = Character
        pub def toJava(x: Char): Character = Char.valueOf(x)
    }
    instance JavaArgument[Char] {
        type In = Character
        pub def toFlix(x: Character): Char = Char.charValue(x)
    }

    instance JavaResult[Int8] {
        type Out = Byte
        pub def toJava(x: Int8): Byte = Int8.valueOf(x)
    }
    instance JavaArgument[Int8] {
        type In = Byte
        pub def toFlix(x: Byte): Int8 = Int8.byteValue(x)
    }

    instance JavaResult[Int16] {
        type Out = Short
        pub def toJava(x: Int16): Short = Int16.valueOf(x)
    }
    instance JavaArgument[Int16] {
        type In = Short
        pub def toFlix(x: Short): Int16 = Int16.shortValue(x)
    }

    instance JavaResult[Int32] {
        type Out = Integer
        pub def toJava(x: Int32): Integer = Int32.valueOf(x)
    }
    instance JavaArgument[Int32] {
        type In = Integer
        pub def toFlix(x: Integer): Int32 = Int32.intValue(x)
    }

    instance JavaResult[Int64] {
        type Out = Long
        pub def toJava(x: Int64): Long = Int64.valueOf(x)
    }
    instance JavaArgument[Int64] {
        type In = Long
        pub def toFlix(x: Long): Int64 = Int64.longValue(x)
    }

    instance JavaResult[Float32] {
        type Out = Float
        pub def toJava(x: Float32): Float = Float32.valueOf(x)
    }
    instance JavaArgument[Float32] {
        type In = Float
        pub def toFlix(x: Float): Float32 = Float32.floatValue(x)
    }

    instance JavaResult[Float64] {
        type Out = Double
        pub def toJava(x: Float64): Double = Float64.valueOf(x)
    }
    instance JavaArgument[Float64] {
        type In = Double
        pub def toFlix(x: Double): Float64 = Float64.doubleValue(x)
    }

    instance JavaResult[String] {
        type Out = String
        pub def toJava(x: String): String = x
    }
    instance JavaArgument[String] {
        type In = String
        pub def toFlix(x: String): String = x
    }

    instance JavaResult[List[a]] with JavaResult[a] {
        type Out = JList[JavaResult.Out[a]]
        type Aef = IO + JavaResult.Aef[a]
        pub def toJava(xs: List[a]): JList[JavaResult.Out[a]] \ (IO + JavaResult.Aef[a]) =
            Adaptor.toList(List.map(JavaResult.toJava, xs))
    }
    instance JavaArgument[List[a]] with JavaArgument[a] {
        type In = JList[JavaArgument.In[a]]
        type Aef = JavaArgument.Aef[a]
        pub def toFlix(xs: JList[JavaArgument.In[a]]): List[a] \ JavaArgument.Aef[a] =
            List.map(JavaArgument.toFlix, Adaptor.fromList(xs))
    }

    instance JavaResult[Option[a]] with JavaResult[a] {
        type Out = Optional[JavaResult.Out[a]]
        type Aef = IO + JavaResult.Aef[a]
        pub def toJava(x: Option[a]): Optional[JavaResult.Out[a]] \ (IO + JavaResult.Aef[a]) =
            Adaptor.toOptional(Option.map(JavaResult.toJava, x))
    }
    instance JavaArgument[Option[a]] with JavaArgument[a] {
        type In = Optional[JavaArgument.In[a]]
        type Aef = JavaArgument.Aef[a]
        pub def toFlix(x: Optional[JavaArgument.In[a]]): Option[a] \ JavaArgument.Aef[a] =
            Option.map(JavaArgument.toFlix, Adaptor.fromOptional(x))
    }

    instance JavaResult[Vector[a]] with JavaResult[a] {
        type Out = JList[JavaResult.Out[a]]
        type Aef = IO + JavaResult.Aef[a]
        pub def toJava(xs: Vector[a]): JList[JavaResult.Out[a]] \ (IO + JavaResult.Aef[a]) =
            Adaptor.toList(List.map(JavaResult.toJava, Vector.toList(xs)))
    }
    instance JavaArgument[Vector[a]] with JavaArgument[a] {
        type In = JList[JavaArgument.In[a]]
        type Aef = JavaArgument.Aef[a]
        pub def toFlix(xs: JList[JavaArgument.In[a]]): Vector[a] \ JavaArgument.Aef[a] =
            List.toVector(List.map(JavaArgument.toFlix, Adaptor.fromList(xs)))
    }

    instance JavaResult[Chain[a]] with JavaResult[a] {
        type Out = JCollection[JavaResult.Out[a]]
        type Aef = IO + JavaResult.Aef[a]
        pub def toJava(xs: Chain[a]): JCollection[JavaResult.Out[a]] \ (IO + JavaResult.Aef[a]) =
            checked_cast(Adaptor.toList(List.map(JavaResult.toJava, Chain.toList(xs))))
    }
    instance JavaArgument[Chain[a]] with JavaArgument[a] {
        type In = JCollection[JavaArgument.In[a]]
        type Aef = JavaArgument.Aef[a]
        pub def toFlix(xs: JCollection[JavaArgument.In[a]]): Chain[a] \ JavaArgument.Aef[a] = region rc {
            let iter = unsafe IO { xs.iterator() };
            iter |> Adaptor.fromIterator(rc, (Proxy.Proxy: Proxy[JavaArgument.In[a]]))
                |> Iterator.map(JavaArgument.toFlix) |> Iterator.toChain
        }
    }

    // Set and Map arguments deliberately require user instances, preserving the archived policy.
    instance JavaResult[Set[a]] with JavaResult[a], Order[a] {
        type Out = JSet[JavaResult.Out[a]]
        type Aef = IO + JavaResult.Aef[a]
        pub def toJava(xs: Set[a]): JSet[JavaResult.Out[a]] \ (IO + JavaResult.Aef[a]) =
            toHashSet(List.map(JavaResult.toJava, Set.toList(xs)))
    }
    instance JavaResult[Map[k, v]] with JavaResult[k], JavaResult[v], Order[k] {
        type Out = JMap[JavaResult.Out[k], JavaResult.Out[v]]
        type Aef = IO + JavaResult.Aef[k] + JavaResult.Aef[v]
        pub def toJava(xs: Map[k, v]): JMap[JavaResult.Out[k], JavaResult.Out[v]] \ (IO + JavaResult.Aef[k] + JavaResult.Aef[v]) =
            toHashMap(List.map(kv -> {
                let (k, v) = kv;
                (JavaResult.toJava(k), JavaResult.toJava(v))
            }, Map.toList(xs)))
    }

    def toHashSet(xs: List[a]): JSet[a] \ IO = {
        let result: LinkedHashSet[a] = new LinkedHashSet();
        List.forEach(x -> discard result.add(x), xs);
        checked_cast(result)
    }

    def toHashMap(xs: List[(k, v)]): JMap[k, v] \ IO = {
        let result: LinkedHashMap[k, v] = new LinkedHashMap();
        List.forEach(kv -> {
            let (k, v) = kv;
            discard result.put(k, v)
        }, xs);
        checked_cast(result)
    }
}
