package scala.meta
package internal
package ast

import scala.{Seq => _}
import scala.collection.immutable.Seq
import scala.compat.Platform.EOL
import scala.reflect.{classTag, ClassTag}
import scala.meta.internal.ast._
import scala.meta.semantic.{Context => SemanticContext}
import scala.meta.internal.semantic._
import scala.meta.internal.semantic.RuntimeConverters._
import org.scalameta.collections._
import org.scalameta.invariants._
import org.scalameta.unreachable
import scala.meta.internal.{ast => m}

object mergeTrees {
  // NOTE: "sy-" stands for "syntactic", "se-" stands for "semantic", "me-" stands for "merged".s
  // NOTE: Much like in LogicalTrees and in ToMtree, cases here must be ordered according
  // to the order of appearance of the corresponding AST nodes in Trees.scala.
  // TODO: I think we could hardcode this traversal into the @ast infrastructure.
  // That will let us save time on: 1) eager recreation of entire trees, 2) eager computation of tokens for withTokens.
  def apply[T <: Tree : ClassTag](sy: T, se: Tree): T = {
    object loop {
      def apply[T <: Tree : ClassTag](sy1: T, se1: Tree): T = {
        // NOTE: This roundabout way of recursing is here only for error reporting.
        // If we end up in, say, `apply(syss, sess)`, and we have `List()` vs `List(List())`,
        // then we will have troubles providing a nice traceback to the user (because we don't have parents at hand).
        // There are two solutions to this, as far as I can see:
        // 1) Pass parents explicitly to all calls to `loop` that deal with collections.
        // 2) Have `loop` definitions be nested within `mergeTrees.apply`, so that everyone can access `sy` and `se`.
        // I tried #1, and it's way too much hassle, which is why I settled down on #2.
        if ((sy1 ne sy) || (se1 ne se)) mergeTrees(sy1, se1)
        else {
          val expandedMetree = (sy, se) match {
            // ============ NAMES ============

            case (sy: m.Name.Anonymous, se: m.Name.Anonymous) =>
              sy.copy()
            case (sy: m.Name.Indeterminate, se: m.Name.Indeterminate) =>
              sy.copy()

            // ============ TERMS ============

            case (sy: m.Term.This, se: m.Term.This) =>
              sy.copy(loop(sy.qual, se.qual))
            case (sy: m.Term.Name, se: m.Term.Name) =>
              sy.copy()
            case (sy: m.Term.Apply, se: m.Term.Apply) =>
              sy.copy(loop(sy.fun, se.fun), loop(sy.args, se.args))
            case (sy: m.Term.Param, se: m.Term.Param) =>
              sy.copy(loop(sy.mods, se.mods), loop(sy.name, se.name), loop(sy.decltpe, se.decltpe), loop(sy.default, se.default))

            // ============ TYPES ============

            case (sy: m.Type.Name, se: m.Type.Name) =>
              sy.copy()
            case (sy: m.Type.Select, se: m.Type.Select) =>
              sy.copy(loop(sy.qual, se.qual), loop(sy.name, se.name))

            // ============ PATTERNS ============

            // ============ LITERALS ============

            // ============ DECLS ============

            // ============ DEFNS ============

            case (sy: m.Defn.Def, se: m.Defn.Def) =>
              if (sy.name.toString != se.name.toString) failCorrelate(sy, se, "incompatible methods")
              val medecltpe = (sy.decltpe, se.decltpe) match {
                case (None, Some(se)) => None
                case (sy, se) => loop(sy, se)
              }
              sy.copy(loop(sy.mods, se.mods), loop(sy.name, se.name), loop(sy.tparams, se.tparams), loop(sy.paramss, se.paramss), medecltpe, loop(sy.body, se.body))
            case (sy: m.Defn.Class, se: m.Defn.Class) =>
              if (sy.name.toString != se.name.toString) failCorrelate(sy, se, "incompatible classes")
              sy.copy(loop(sy.mods, se.mods), loop(sy.name, se.name), loop(sy.tparams, se.tparams), loop(sy.ctor, se.ctor), loop(sy.templ, se.templ))

            // ============ PKGS ============

            case (sy: m.Source, se: m.Source) =>
              sy.copy(loop(sy.stats, se.stats))
            case (sy: m.Pkg, se: m.Pkg) =>
              if (sy.ref.toString != se.ref.toString) failCorrelate(sy, se, "incompatible packages")
              sy.copy(loop(sy.ref, se.ref), loop(sy.stats, se.stats))

            // ============ CTORS ============

            case (sy: m.Ctor.Primary, se: m.Ctor.Primary) =>
              // NOTE: scala.reflect irreversibly desugars nullary constructors into empty-arglist ones
              val meparamss = (sy.paramss, se.paramss) match {
                case (Seq(), Seq(Seq())) => Seq()
                case (syss, sess) => loop(syss, sess)
              }
              sy.copy(loop(sy.mods, se.mods), loop(sy.name, se.name), meparamss)
            case (sy: m.Ctor.Ref.Name, se: m.Ctor.Ref.Name) =>
              sy.copy()
            case (sy: m.Ctor.Ref.Select, se: m.Ctor.Ref.Select) =>
              sy.copy(loop(sy.qual, se.qual), loop(sy.name, se.name))

            // ============ TEMPLATES ============

            case (sy: m.Template, se: m.Template) =>
              // NOTE: ensugaring rules for parent lists, as per Scala 2.11.7
              // 1) If the parent list is empty, make it List(AnyRef)
              // 2) If parsing a case class, append Product and Serializable to the end of the parent list
              // 3) During typechecking filter out all subsequent repeated occurrences of ProductN, Product and Serializable
              // 4) If the first parent in the list is a trait, then:
              //    * Convert it to AnyRef, if it's Any
              //    * Prepend tpe.firstParent to the list, otherwise
              // 5) If a parent is applied to a nullary argument list, make it empty argument list.
              def mergeParents(sys: Seq[m.Term], ses: Seq[m.Term]): Seq[m.Term] = {
                if (sys.length != ses.length) failCorrelate(sy, se, sys, ses)
                sys.zip(ses).map({
                  case (sy, m.Term.Apply(se, Nil)) => loop(sy, se)
                  case (sy, se) => loop(sy, se)
                })
              }
              val meparents = (sy.parents, se.parents) match {
                case (Seq(), Seq(m.Term.Apply(anyRef: m.Term.Ref, Nil))) if anyRef == Object_init =>
                  Seq()
                case (syss, sess) =>
                  mergeParents(syss, sess)
              }
              val mestats = (sy.stats, se.stats) match {
                case (None, Some(Nil)) => None
                case (sys, ses) => loop(sys, ses)
              }
              sy.copy(loop(sy.early, se.early), meparents, loop(sy.self, se.self), mestats)

            // ============ MODIFIERS ============

            // ============ ODDS & ENDS ============

            case _ =>
              failCorrelate(sy, se, "unexpected trees")
          }

          val tokenizedMetree = expandedMetree.withTokens(sy.tokens)
          val denotedMetree = tokenizedMetree match {
            case tokenizedMename: m.Name =>
              val sename = se.require[m.Name]
              tokenizedMename.withDenot(sename.denot)
            case tokenizedMetree =>
              tokenizedMetree
          }
          val typedMetree = denotedMetree match {
            case denotedMeterm: m.Term =>
              val seterm = se.require[m.Term]
              denotedMeterm.withTyping(seterm.typing)
            case denotedMeparam: m.Term.Param =>
              val separam = se.require[m.Term.Param]
              denotedMeparam.withTyping(separam.typing)
            case denotedMetree =>
              denotedMetree
          }
          val metree = typedMetree
          if (sys.props("convert.debug") != null && sy.parent.isEmpty) {
            println("======= SYNTACTIC TREE =======")
            println(sy)
            println(sy.show[Structure])
            println("======== SEMANTIC TREE ========")
            println(se)
            println(se.show[Semantics])
            println("======== MERGED TREE ========")
            println(metree)
            println(metree.show[Semantics])
            println("=================================")
          }
          // TODO: fix duplication wrt ToMtree.scala
          if (classTag[T].runtimeClass.isAssignableFrom(metree.getClass)) metree.asInstanceOf[T]
          else failExpected(sy, se, classTag[T].runtimeClass, metree)
        }
      }

      def apply[T <: Tree : ClassTag](syopt: Option[T], seopt: Option[Tree])(implicit hack1: OverloadHack1): Option[T] = (syopt, seopt) match {
        case (Some(sy), Some(se)) => Some(loop(sy, se))
        case (None, None) => None
        case _ => failCorrelate(sy, se, syopt.toList, seopt.toList)
      }

      def apply[T <: Tree : ClassTag](sysopt: Option[Seq[T]], sesopt: Option[Seq[Tree]])(implicit hack2: OverloadHack2): Option[Seq[T]] = (sysopt, sesopt) match {
        case (Some(sys), Some(ses)) => Some(loop(sys, ses))
        case (None, None) => None
        case _ => failCorrelate(sy, se, sysopt.toList, sesopt.toList)
      }

      def apply[T <: Tree : ClassTag](sys: Seq[T], ses: Seq[Tree])(implicit hack1: OverloadHack1): Seq[T] = {
        if (sys.length != ses.length) failCorrelate(sy, se, sys, ses)
        sys.zip(ses).map({ case (sy, se) => loop(sy, se) })
      }

      def apply[T <: Tree : ClassTag](syss: Seq[Seq[T]], sess: Seq[Seq[Tree]])(implicit hack2: OverloadHack2): Seq[Seq[T]] = {
        if (syss.length != sess.length) failCorrelate(sy, se, syss, sess)
        syss.zip(sess).map({ case (sys, ses) => loop(sys, ses) })
      }
    }
    loop(sy, se)
  }

  // NOTE: This contraption is only necessary because we can't use quasiquotes in the same project they're defined in.
  private lazy val Object_init = Ctor.Name("Object").withDenot(denot(typeOf[Object].member(u.TermName("<init>"))))

  private def failCorrelate(sy: Tree, se: Tree, diagnostics: String): Nothing = {
    val details = s"${sy.show[Structure]}$EOL${se.show[Structure]}"
    fail(sy, se, s"encountered $diagnostics during syntactic + semantic merge:$EOL$details")
  }

  private def failCorrelate(syparent: Tree, separent: Tree, sys: Seq[_], ses: Seq[_]): Nothing = {
    val summary = s"syntactic = ${sys.length} element(s), semantic = ${ses.length} element(s)"
    fail(syparent, separent, s"encountered incompatible collections during syntactic + semantic merge: $summary")
  }

  private def failExpected(sy: Tree, se: Tree, expected: Class[_], actual: Tree): Nothing = {
    var expectedAbbrev = expected.getName
    expectedAbbrev = expectedAbbrev.stripPrefix("scala.meta.internal.ast.").stripPrefix("scala.meta.")
    expectedAbbrev = expectedAbbrev.stripSuffix("$Impl")
    expectedAbbrev = expectedAbbrev.replace("$", ".")
    val actualAbbrev = actual.productPrefix
    val summary = s"expected = $expectedAbbrev, actual = $actualAbbrev"
    val details = actual.show[Structure]
    fail(sy, se, s"obtained an unexpected result during syntactic + semantic merge: $summary$EOL$details")
  }

  private def fail(sy: Tree, se: Tree, diagnostics: String): Nothing = {
    def traceback(sy: Tree, se: Tree): List[String] = {
      def summary(tree: Tree): String = {
        val prefix = tree.productPrefix
        var details = tree.toString.replace("\n", " ")
        if (details.length > 60) details = details.take(60) + "..."
        s"($prefix) $details"
      }
      val tail = (sy.parent, se.parent) match {
        case (Some(sy), Some(se)) => traceback(sy.require[Tree], se.require[Tree])
        case (Some(sy), None) => List("<-...$EOL->")
        case (None, Some(se)) => List("<-$EOL->...")
        case (None, None) => Nil
      }
      s"<-${summary(sy)}$EOL->${summary(se)}" +: tail
    }
    throw new MergeException(List(sy, se), s"$diagnostics$EOL${traceback(sy, se).mkString(EOL)}")
  }
}