package example

/** Greets people. */
class Greeter(name: String):
  /** Returns a greeting. */
  def greet: String = s"Hello, ${name}!"
