/// A module whose comments must keep the declarations they belong to.
mod Comments {

  /*
   * A block comment is reproduced verbatim, interior
   *   indentation included.
   */
  pub def blocked(): Int32 = 1

  // A comment introducing the next declaration must not be indented
  // into the previous declaration's body.
  pub def introduced(): Int32 = 2 // and a trailing one

  ///
  /// A doc comment is folded into the declaration it documents,
  /// which is why a declaration starts earlier than it looks.
  ///
  pub def documented(): Int32 = 3

}
