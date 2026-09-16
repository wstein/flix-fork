package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.Symbol

object JvmNames {

  def defnName(sym: Symbol.DefnSym)(implicit flix: Flix): String =
    name(sym, sym.text, sym.id.isDefined)

  def enumName(sym: Symbol.EnumSym)(implicit flix: Flix): String =
    name(sym, sym.text, sym.id.isDefined)

  private def name(sym: Symbol, text: String, generated: Boolean)(implicit flix: Flix): String = {
    val suffix = flix.jvmOrigins.nameTable.suffix(sym)
    if (generated) text + Flix.Delimiter + suffix else text
  }

}
