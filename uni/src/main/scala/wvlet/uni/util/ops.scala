package wvlet.uni.util

/**
  * Utility extension methods for fluent, builder-style code
  */
object ops:
  extension [A](self: A)
    /**
      * Apply a function to the current object and return the result
      */
    def pipe[B](f: A => B): B = f(self)

    /**
      * If a given option is defined, apply the function to the current object and the value of the
      * Option.
      */
    def ifDefined[B](opt: Option[B])(thenFn: (A, B) => A): A =
      opt match
        case Some(v) =>
          thenFn(self, v)
        case None =>
          self

    /**
      * Apply a function only when it matches the given pattern
      */
    def when(f: PartialFunction[A, Unit]): Unit = f.applyOrElse(self, (_: A) => ())

  extension [A](seq: Seq[A])
    /**
      * Apply a function for each element when it matches the given pattern
      */
    def when(f: PartialFunction[A, Unit]): Unit = seq.foreach(f.applyOrElse(_, (_: A) => ()))
