package doobie.specs2

import doobie.free.connection.ConnectionIO

import doobie.util.transactor._
import doobie.util.query._
import doobie.util.update._
import doobie.util.analysis._
import doobie.util.pretty._
import doobie.util.pos.Pos
import doobie.util.iolite.IOLite

import org.specs2.mutable.Specification
import org.specs2.execute.Failure
import org.specs2.specification.core.{ Fragments, Fragment }
import org.specs2.specification.dsl.Online._
import org.specs2.specification.create.{ FormattingFragments => Format }

import scala.reflect.runtime.universe.TypeTag

#+scalaz
import doobie.util.capture.Capture
import scalaz.{ Monad, Catchable, -\/, \/- }
#-scalaz
#+cats
import scala.util.{ Left => -\/, Right => \/- }
import cats.Monad
import fs2.util.{ Catchable, Suspendable }
import fs2.interop.cats._
#-cats

/**
 * Module with a mix-in trait for specifications that enables checking of doobie `Query` and `Update` values.
 * {{{
 * // An example specification, taken from the examples project.
 * object AnalysisTestSpec extends Specification with AnalysisSpec {
 *
 *   // The transactor to use for the tests.
 *   val transactor = DriverManagerTransactor[IOLite](
 *     "org.postgresql.Driver",
 *     "jdbc:postgresql:world",
 *     "postgres", ""
 *   )
 *
 *   // Now just mention the queries. Arguments are not used.
 *   check(MyDaoModule.findByNameAndAge(null, 0))
 *   check(MyDaoModule.allWoozles)
 *
 * }
 * }}}
 */
object analysisspec {

  @deprecated("Use IOLiteChecker.", "0.4.2")
  type AnalysisSpec = IOLiteChecker

  trait Checker[M[_]] { this: Specification =>

    // Effect type, required instances, unsafe run
    implicit val monadM: Monad[M]
    implicit val catchableM: Catchable[M]
#+scalaz
    implicit val captureM: Capture[M]
#-scalaz
#+cats
    implicit val captureM: Suspendable[M]
#-cats
    def unsafePerformIO[A](ma: M[A]): A

    def transactor: Transactor[M]

    def check[A, B](q: Query[A, B])(implicit A: TypeTag[A], B: TypeTag[B]): Fragments =
      checkAnalysis(s"Query[${typeName(A)}, ${typeName(B)}]", q.pos, q.sql, q.analysis)

    def check[A](q: Query0[A])(implicit A: TypeTag[A]): Fragments =
      checkAnalysis(s"Query0[${typeName(A)}]", q.pos, q.sql, q.analysis)

    def checkOutput[A](q: Query0[A])(implicit A: TypeTag[A]): Fragments =
      checkAnalysis(s"Query0[${typeName(A)}]", q.pos, q.sql, q.outputAnalysis)

    def check[A](q: Update[A])(implicit A: TypeTag[A]): Fragments =
      checkAnalysis(s"Update[${typeName(A)}]", q.pos, q.sql, q.analysis)

    def check(q: Update0): Fragments =
      checkAnalysis(s"Update0", q.pos, q.sql, q.analysis)

    private def checkAnalysis(typeName: String, pos: Option[Pos], sql: String, analysis: ConnectionIO[Analysis]): Fragments =
      // continuesWith is necessary to make sure the query doesn't run too early
      s"\n$typeName defined at ${loc(pos)}\n${sql.lines.map(s => "  " + s.trim).filterNot(_.isEmpty).mkString("\n")}" >> ok.continueWith {
        unsafePerformIO(catchableM.attempt(transactor.trans(monadM).apply(analysis))) match {
          // We can't rely on mutable Specification DSL here!
          case -\/(e) => indentBlock(Seq(
            "SQL Compiles and Typechecks" ! failure(formatError(e.getMessage))
          ))
          case \/-(a) => indentBlock(
            ("SQL Compiles and Typechecks" ! ok) +:
              a.paramDescriptions.map{ case (s, es) => s ! assertEmpty(es, pos) } ++:
              a.columnDescriptions.map{ case (s, es) => s ! assertEmpty(es, pos) }
          )
        }
      }

    private def loc(f: Option[Pos]): String =
      f.map(f => s"${f.file}:${f.line}").getOrElse("(source location unknown)")

    private def assertEmpty(es: List[AlignmentError], f: Option[Pos]) =
      if (es.isEmpty) success
      else new Failure(es.map(formatError).mkString("\n"), "", Nil)

    private val packagePrefix = "\\b[a-z]+\\.".r

    private def typeName[A](tag: TypeTag[A]): String =
      packagePrefix.replaceAllIn(tag.tpe.toString, "")

    private def formatError(e: AlignmentError): String =
      formatError(e.msg)

    private def formatError(s: String): String =
      (wrap(80)(s) match {
        case s :: ss => (s"x " + s) :: ss.map("  " + _)
        case Nil => Nil
      }).mkString("\n")

    private def indentBlock(fs: Seq[Fragment]): Fragments =
      // intersperse fragments with newlines, and indent them.
      // This differs from standard version (FragmentsDsl.fragmentsBlock()) in
      // that any failure gets properly indented, too.
      Fragments.empty
        .append(Format.t)
        .append(fs.flatMap(Seq(Format.br, _)))
        .append(Format.bt)
  }

  /** Implementation of Checker[IOLite] */
  trait IOLiteChecker extends Checker[IOLite] { this: Specification =>
    val monadM: Monad[IOLite] = implicitly
    val catchableM: Catchable[IOLite] = implicitly
#+scalaz
    val captureM: Capture[IOLite] = implicitly
#-scalaz
#+cats
    val captureM: Suspendable[IOLite] = implicitly
#-cats
    def unsafePerformIO[A](ma: IOLite[A]) = ma.unsafePerformIO
  }

#+cats
  import fs2.Task

  /** Implementation of Checker[fs2.Task] */
  trait TaskChecker extends Checker[Task] { this: Specification =>
    val monadM: Monad[Task] = implicitly
    val catchableM: Catchable[Task] = implicitly
    val captureM: Suspendable[Task] = implicitly
    def unsafePerformIO[A](ma: Task[A]) = ma.unsafeRun
  }
#-cats

#+scalaz
  import scalaz.concurrent.Task
  import scalaz.effect.IO

  /** Implementation of Checker[scalaz.concurrent.Task] */
  trait TaskChecker extends Checker[Task] { this: Specification =>
    val monadM: Monad[Task] = implicitly
    val catchableM: Catchable[Task] = implicitly
    val captureM: Capture[Task] = implicitly
    def unsafePerformIO[A](ma: Task[A]) = ma.unsafePerformSync
  }

  /** Implementation of Checker[scalaz.effect.IO] */
  trait IOChecker extends Checker[IO] { this: Specification =>
    val monadM: Monad[IO] = implicitly
    val catchableM: Catchable[IO] = implicitly
    val captureM: Capture[IO] = implicitly
    def unsafePerformIO[A](ma: IO[A]) = ma.unsafePerformIO
  }
#-scalaz

}
