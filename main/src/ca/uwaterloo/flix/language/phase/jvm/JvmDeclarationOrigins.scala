package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{Symbol, TypedAst}

object JvmDeclarationOrigins {

  def capture(root: TypedAst.Root): JvmProvenance = {
    val registry = new JvmProvenance()

    root.defs.values.foreach { defn =>
      registry.register(defn.sym, GeneratedJvmKey("definition", defn.sym.namespace :+ defn.sym.text))
    }
    root.enums.values.foreach { enm =>
      registry.register(enm.sym, GeneratedJvmKey("enum", enm.sym.namespace :+ enm.sym.text))
      enm.cases.values.foreach { caze =>
        registry.register(caze.sym, GeneratedJvmKey("enum-case", enm.sym.namespace ::: List(enm.sym.text, caze.sym.name)))
      }
    }
    root.structs.values.foreach { struct =>
      registry.register(struct.sym, GeneratedJvmKey("struct", struct.sym.namespace :+ struct.sym.text))
      struct.fields.values.foreach { field =>
        registry.register(field.sym, GeneratedJvmKey("struct-field", struct.sym.namespace ::: List(struct.sym.text, field.sym.name)))
      }
    }
    root.restrictableEnums.values.foreach { enm =>
      registry.register(enm.sym, GeneratedJvmKey("restrictable-enum", enm.sym.namespace :+ enm.sym.name))
      enm.cases.values.foreach { caze =>
        registry.register(caze.sym, GeneratedJvmKey("restrictable-case", enm.sym.namespace ::: List(enm.sym.name, caze.sym.name)))
      }
    }
    root.effects.values.foreach { effect =>
      registry.register(effect.sym, GeneratedJvmKey("effect", effect.sym.namespace :+ effect.sym.name))
      effect.ops.foreach { op =>
        registry.register(op.sym, GeneratedJvmKey("operation", effect.sym.namespace ::: List(effect.sym.name, op.sym.name)))
      }
    }
    root.traits.values.foreach { trt =>
      registry.register(trt.sym, GeneratedJvmKey("trait", trt.sym.namespace :+ trt.sym.name))
      trt.sigs.foreach { sig =>
        val fields = trt.sym.namespace ::: List(trt.sym.name, sig.sym.name)
        registry.register(sig.sym, GeneratedJvmKey("signature", fields))
        if (sig.exp.nonEmpty) {
          val sym = new Symbol.DefnSym(None, trt.sym.namespace :+ trt.sym.name, sig.sym.name, sig.loc)
          registry.register(sym, GeneratedJvmKey("default-implementation", fields))
        }
      }
    }
    root.instances.values.foreach { instance =>
      val head = JvmTypeKey.encode(instance.tpe, instance.tparams.map(_.sym), registry.origin)
      instance.defs.foreach { defn =>
        val fields = instance.trt.sym.namespace ::: List(instance.trt.sym.name, head, defn.sym.text)
        registry.register(defn.sym, GeneratedJvmKey("instance-member", fields))
      }
    }
    registry
  }
}
