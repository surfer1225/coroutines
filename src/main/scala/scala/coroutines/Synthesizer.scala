package scala.coroutines



import scala.annotation.tailrec
import scala.collection._
import scala.coroutines.common._
import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context



/** Synthesizes all coroutine-related functionality.
 */
private[coroutines] class Synthesizer[C <: Context](val c: C)
extends Analyzer[C] with ControlFlowGraph[C] {
  import c.universe._

  private def inferReturnType(body: Tree): Tree = {
    // return type must correspond to the return type of the function literal
    val rettpe = body.tpe

    // return type is the lub of the function return type and yield argument types
    def isCoroutinesPackage(q: Tree) = q match {
      case q"coroutines.this.`package`" => true
      case t => false
    }
    // TODO: ensure that this does not capture constraints from nested class scopes
    // TODO: ensure that this does not collect nested coroutine invocations
    val constraintTpes = body.collect {
      case q"$qual.yieldval[$tpt]($v)" if isCoroutinesPackage(qual) => tpt.tpe
      case q"$qual.yieldto[$tpt]($f)" if isCoroutinesPackage(qual) => tpt.tpe
    }
    tq"${lub(rettpe :: constraintTpes)}"
  }

  private def extractSubgraphs(
    cfg: Node, rettpt: Tree
  )(implicit table: Table): Set[Subgraph] = {
    val subgraphs = mutable.LinkedHashSet[Subgraph]()
    val exitPoints = mutable.Map[Subgraph, mutable.Map[Node, Long]]()
    val seenEntries = mutable.Set[Node]()
    val nodefront = mutable.Queue[Node]()
    seenEntries += cfg
    nodefront.enqueue(cfg)

    def extract(
      n: Node, seen: mutable.Map[Node, Node], subgraph: Subgraph
    ): Node = {
      // duplicate and mark current node as seen
      val current = n.copyWithoutSuccessors
      seen(n) = current

      // detect referenced and declared stack variables
      for (t <- n.tree) {
        if (table.contains(t.symbol)) {
          subgraph.referencedVars(t.symbol) = table(t.symbol)
        }
        t match {
          case q"$_ val $_: $_ = $_" =>
            subgraph.declaredVars(t.symbol) = table(t.symbol)
          case _ =>
            // do nothing
        }
      }

      // check for termination condition
      def addToNodeFront() {
        // add successors to node front
        for (s <- n.successors) if (!seenEntries(s)) {
          seenEntries += s
          nodefront.enqueue(s)
        }
      }
      def addCoroutineInvocationToNodeFront(co: Tree) {
        val coroutinetpe = coroutineTypeFor(rettpt.tpe)
        if (!(co.tpe <:< coroutinetpe)) {
          c.abort(co.pos,
            s"Coroutine invocation site has invalid return type.\n" +
            s"required: $coroutinetpe\n" +
            s"found:    ${co.tpe} (with underlying type ${co.tpe.widen})")
        }
        addToNodeFront()
      }
      n.tree match {
        case q"coroutines.this.`package`.yieldval[$_]($_)" =>
          addToNodeFront()
          exitPoints(subgraph)(current) = n.successors.head.uid
        case q"coroutines.this.`package`.yieldto[$_]($_)" =>
          addToNodeFront()
          exitPoints(subgraph)(current) = n.successors.head.uid
        case q"$_ val $_ = $co.apply(..$args)" if isCoroutineDefType(co.tpe) =>
          addCoroutineInvocationToNodeFront(co)
          exitPoints(subgraph)(current) = n.successors.head.uid
        case _ =>
          // traverse successors
          for (s <- n.successors) {
            if (!seen.contains(s)) {
              extract(s, seen, subgraph)
            }
            current.successors ::= seen(s)
          }
      }
      current
    }

    // as long as there are more nodes on the expansion front, extract them
    while (nodefront.nonEmpty) {
      val subgraph = new Subgraph(table.newSubgraphUid())
      exitPoints(subgraph) = mutable.Map[Node, Long]()
      subgraph.start = extract(nodefront.dequeue(), mutable.Map(), subgraph)
      subgraphs += subgraph
    }

    // assign respective subgraph reference to each exit point node
    val startPoints = subgraphs.map(s => s.start.uid -> s).toMap
    for ((subgraph, exitMap) <- exitPoints; (node, nextUid) <- exitMap) {
      subgraph.exitSubgraphs(node) = startPoints(nextUid)
    }

    println(subgraphs
      .map(t => {
        "[" + t.referencedVars.keys.mkString(", ") + "]\n" + t.start.prettyPrint
      })
      .zipWithIndex.map(t => s"\n${t._2}:\n${t._1}")
      .mkString("\n"))
    subgraphs
  }

  private def synthesizeEntryPoint(
    subgraph: Subgraph, subgraphs: Set[Subgraph], rettpt: Tree
  )(implicit table: Table): Tree = {
    def findStart(chain: Chain): Zipper = {
      var z = {
        if (chain.parent == null) Zipper(null, Nil, trees => q"..$trees")
        else findStart(chain.parent).descend(trees => q"..$trees")
      }
      for ((sym, info) <- chain.vars) {
        if (subgraph.usesVar(sym) && !subgraph.declaresVar(sym)) {
          val cparam = table.names.coroutineParam
          val stack = info.stackname
          val pos = info.stackpos
          val stackget = q"scala.coroutines.common.Stack.get($cparam.$stack, $pos)"
          val decodedget = info.decodeLong(stackget)
          val valdef = info.origtree match {
            case q"$mods val $name: $tpt = $_" => q"$mods val $name: $tpt = $decodedget"
            case q"$mods var $name: $tpt = $_" => q"$mods var $name: $tpt = $decodedget"
          }
          z = z.append(valdef)
        }
      }
      z
    }

    val startPoint = findStart(subgraph.start.chain)
    val bodyZipper = subgraph.start.emitCode(startPoint, subgraph)
    val body = bodyZipper.root.result
    val defname = TermName(s"ep${subgraph.uid}")
    val defdef = q"""
      def $defname(${table.names.coroutineParam}: Coroutine[$rettpt]): Unit = {
        $body
      }
    """
    defdef
  }

  private def synthesizeEntryPoints(
    args: List[Tree], body: Tree, rettpt: Tree
  )(implicit table: Table): Map[Long, Tree] = {
    val cfg = generateControlFlowGraph()
    val subgraphs = extractSubgraphs(cfg, rettpt)

    val entrypoints = for (subgraph <- subgraphs) yield {
      (subgraph.uid, synthesizeEntryPoint(subgraph, subgraphs, rettpt))
    }
    entrypoints.toMap
  }

  private def synthesizeEnterMethod(
    entrypoints: Map[Long, Tree], tpt: Tree
  )(implicit table: Table): Tree = {
    val cparamname = table.names.coroutineParam
    if (entrypoints.size == 1) {
      val q"def $ep($_): Unit = $_" = entrypoints(0)

      q"""
        def enter($cparamname: Coroutine[$tpt]): Unit = $ep($cparamname)
      """
    } else if (entrypoints.size == 2) {
      val q"def $ep0($_): Unit = $_" = entrypoints(0)
      val q"def $ep1($_): Unit = $_" = entrypoints(1)

      q"""
        def enter($cparamname: Coroutine[$tpt]): Unit = {
          val pc = scala.coroutines.common.Stack.top($cparamname.pcstack)
          if (pc == 0) $ep0($cparamname) else $ep1($cparamname)
        }
      """
    } else {
      val cases = for ((index, defdef) <- entrypoints) yield {
        val q"def $ep($_): Unit = $rhs" = defdef
        cq"${index.toShort} => $ep($cparamname)"
      }

      q"""
        def enter($cparamname: Coroutine[$tpt]): Unit = {
          val pc: Short = scala.coroutines.common.Stack.top($cparamname.pcstack)
          (pc: @scala.annotation.switch) match {
            case ..$cases
          }
        }
      """
    }
  }

  def synthesize(lambda: Tree): Tree = {
    implicit val table = new Table(lambda)

    // ensure that argument is a function literal
    val (args, body) = lambda match {
      case q"(..$args) => $body" => (args, body)
      case _ => c.abort(lambda.pos, "The coroutine takes a single function literal.")
    }
    val argidents = for (arg <- args) yield {
      val q"$_ val $argname: $_ = $_" = arg
      q"$argname"
    }

    // extract argument names and types
    val (argnames, argtpts) = (for (arg <- args) yield {
      val q"$_ val $name: $tpt = $_" = arg
      (name, tpt)
    }).unzip

    // infer coroutine return type
    val rettpt = inferReturnType(body)

    // generate entry points from yields and coroutine applies
    val entrypoints = synthesizeEntryPoints(args, body, rettpt)

    // generate entry method
    val entermethod = synthesizeEnterMethod(entrypoints, rettpt)

    // generate variable pushes and pops for stack variables
    val (varpushes, varpops) = (for ((sym, info) <- table.vars.toList) yield {
      (info.pushTree, info.popTree)
    }).unzip

    // emit coroutine instantiation
    val coroutineTpe = TypeName(s"Arity${args.size}")
    val entrypointmethods = entrypoints.map(_._2)
    val valnme = TermName(c.freshName("c"))
    val co = q"""
      new scala.coroutines.Coroutine.$coroutineTpe[..$argtpts, $rettpt] {
        def call(..$args)(
          implicit cc: scala.coroutines.CanCall
        ): scala.coroutines.Coroutine[$rettpt] = {
          val $valnme = new scala.coroutines.Coroutine[$rettpt]
          push($valnme, ..$argidents)
          $valnme
        }
        def apply(..$args): $rettpt = {
          sys.error(
            "Coroutines can only be invoked directly from within other coroutines. " +
            "Use `call(<coroutine>(<arg0>, ..., <argN>))` instead if you want to " +
            "start a new coroutine.")
        }
        def push(c: scala.coroutines.Coroutine[$rettpt], ..$args): Unit = {
          scala.coroutines.common.Stack.push(c.costack, this, -1)
          scala.coroutines.common.Stack.push(c.pcstack, 0.toShort, -1)
          ..$varpushes
        }
        def pop(c: scala.coroutines.Coroutine[$rettpt]): Unit = {
          scala.coroutines.common.Stack.pop(c.pcstack)
          scala.coroutines.common.Stack.pop(c.costack)
          ..$varpops
        }
        $entermethod
        ..$entrypointmethods
      }
    """
    println(co)
    co
  }

  def call[T: WeakTypeTag](lambda: Tree): Tree = {
    val (receiver, args) = lambda match {
      case q"$r.apply(..$args)" =>
        if (!isCoroutineDefType(r.tpe))
          c.abort(r.pos,
            s"Receiver must be a coroutine.\n" +
            s"required: Coroutine.Definition[${implicitly[WeakTypeTag[T]]}]\n" +
            s"found:    ${r.tpe} (with underlying type ${r.tpe.widen})")
        (r, args)
      case _ =>
        c.abort(
          lambda.pos,
          "The call statement must take a coroutine invocation expression:\n" +
          "  call(<coroutine>.apply(<arg0>, ..., <argN>))")
    }

    val tpe = implicitly[WeakTypeTag[T]]
    val t = q"""
      import scala.coroutines.Permission.canCall
      $receiver.call(..$args)
    """
    println(t)
    t
  }
}
