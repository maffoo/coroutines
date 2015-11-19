package scala.coroutines



import scala.annotation.tailrec
import scala.coroutines.common.Stack
import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context



class Coroutine[@specialized T] {
  import Coroutine._
  private[coroutines] var costackptr = 0
  private[coroutines] var costack = new Array[Definition[T]](INITIAL_CO_STACK_SIZE)
  private[coroutines] var pcstackptr = 0
  private[coroutines] var pcstack = new Array[Short](INITIAL_CO_STACK_SIZE)
  private[coroutines] var target: Coroutine[T] = null
  private[coroutines] var result: T = null.asInstanceOf[T]

  final def push(cd: Coroutine.Definition[T]) {
    Stack.push(costack, cd)
    cd.push(this)
  }

  final def pop() {
    val cd = Stack.pop(costack)
    cd.pop(this)
  }

  @tailrec
  final def enter(): T = {
    val cd = Stack.top(costack)
    cd.enter(this)
    if (target ne null) {
      val nc = target
      target = null
      nc.enter()
    } else result
  }
}


object Coroutine {
  private[coroutines] val INITIAL_CO_STACK_SIZE = 4
  private[coroutines] val INITIAL_VAR_STACK_SIZE = 8

  abstract class Definition[T] {
    def push(c: Coroutine[T]): Unit
    def pop(c: Coroutine[T]): Unit
    def enter(c: Coroutine[T]): Unit
  }

  def transform(c: Context)(f: c.Tree): c.Tree = {
    new Synthesizer[c.type](c).transform(f)
  }

  private[coroutines] class Synthesizer[C <: Context](val c: C) {
    import c.universe._

    private def inferReturnType(body: Tree): Tree = {
      // return type must correspond to the return type of the function literal
      val rettpe = body.tpe

      // return type is the lub of the function return type and yield argument types
      def isCoroutines(q: Tree) = q match {
        case q"coroutines.this.`package`" => true
        case t => false
      }
      val constraintTpes = body.collect {
        case q"$qual.yieldval[$tpt]($v)" if isCoroutines(qual) => tpt.tpe
        case q"$qual.yieldto[$tpt]($f)" if isCoroutines(qual) => tpt.tpe
      }
      tq"${lub(rettpe :: constraintTpes)}"
    }

    private def generateVariableMap(args: List[Tree], body: Tree): Map[Symbol, Int] = {
      Map()
    }

    private def generateEntryPoints(args: List[Tree], body: Tree,
      varmap: Map[Symbol, Int]): Map[Int, Tree] = {
      Map(
        0 -> q"def ep0() = {}",
        1 -> q"def ep1() = {}"
      )
    }

    private def generateEnterMethod(entrypoints: Map[Int, Tree], tpe: Tree): Tree = {
      if (entrypoints.size == 1) {
        val q"def $ep() = $_" = entrypoints(0)
        q"""
        def enter(c: Coroutine[$tpe]): Unit = $ep()
        """
      } else if (entrypoints.size == 2) {
        val q"def $ep0() = $_" = entrypoints(0)
        val q"def $ep1() = $_" = entrypoints(1)
        q"""
        def enter(c: Coroutine[$tpe]): Unit = {
          val pc = scala.coroutines.common.Stack.top(c.pcstack)
          if (pc == 0) $ep0() else $ep1()
        }
        """
      } else {
        val cases = for ((index, defdef) <- entrypoints) yield {
          val q"def $ep() = $rhs" = defdef
          cq"$index => $ep()"
        }

        q"""
        def enter(c: Coroutine[$tpe]): Unit = {
          val pc = scala.coroutines.common.Stack.top(c.pcstack)
          (pc: @scala.annotation.switch) match {
            case ..$cases
          }
        }
        """
      }
    }

    def transform(f: Tree): Tree = {
      // ensure that argument is a function literal
      val (args, body) = f match {
        case q"(..$args) => $body" => (args, body)
        case _ => c.abort(f.pos, "The coroutine takes a single function literal.")
      }

      // extract argument names and types
      val (argnames, argtpes) = (for (arg <- args) yield {
        val q"$_ val $name: $tpe = $_" = arg
        (name, tpe)
      }).unzip

      // infer return type
      val rettpe = inferReturnType(body)

      // generate variable map
      val varmap = generateVariableMap(args, body)

      // generate entry points from yields and coroutine applies
      val entrypoints = generateEntryPoints(args, body, varmap)

      // generate entry method
      val entermethod = generateEnterMethod(entrypoints, rettpe)

      // emit coroutine instantiation
      val coroutineTpe = TypeName(s"Arity${args.size}")
      val entrypointmethods = entrypoints.map(_._2)
      val co = q"""new scala.coroutines.Coroutine.$coroutineTpe[..$argtpes, $rettpe] {
        def apply(..$args) = {
          new Coroutine[$rettpe]
        }
        def push(c: Coroutine[$rettpe]): Unit = {
          ???
        }
        def pop(c: Coroutine[$rettpe]): Unit = {
          ???
        }
        $entermethod
        ..$entrypointmethods
      }"""
      println(co)
      co
    }
  }

  abstract class Arity0[@specialized T] extends Coroutine.Definition[T] {
    def apply(): Coroutine[T]
  }

  abstract class Arity1[A0, @specialized T] extends Coroutine.Definition[T] {
    def apply(a0: A0): Coroutine[T]
  }

  abstract class Arity2[A0, A1, @specialized T] extends Coroutine.Definition[T] {
    def apply(a0: A0, a1: A1): Coroutine[T]
  }

  abstract class Arity3[A0, A1, A2, @specialized T] extends Coroutine.Definition[T] {
    def apply(a0: A0, a1: A1, a2: A2): Coroutine[T]
  }
}