package org.weirdcanada.common.query

import scalaz.{Free, FreeInstances, Functor}
import Free._

import java.sql.PreparedStatement

sealed trait JDBCValue[A] {
  def set(st: PreparedStatement, i: Int, a: A): Unit
}
object JDBCValue {

  implicit object jdbcString extends JDBCValue[String] {
    def set(st: PreparedStatement, i: Int, a: String): Unit =
      st.setString(i,a)
  }

  implicit object jdbcInt extends JDBCValue[Int] {
    def set(st: PreparedStatement, i: Int, a: Int): Unit = 
      st.setInt(i,a)
  }

}

abstract class Conditional[A : JDBCValue] { 
  def getSetter: JDBCValue[A] = implicitly[JDBCValue[A]]
}

case class Eq[A : JDBCValue](column: String, value: A) extends Conditional[A]
case class LessThan[A : JDBCValue](column: String, value: A) extends Conditional[A]
case class LessThanOrEqualTo[A : JDBCValue](column: String, value: A) extends Conditional[A]
case class In[A : JDBCValue](column: String, values: Iterable[A]) extends Conditional[A]

sealed trait Query[+A] 

case class Select[A](columns: List[String], next: A) extends Query[A]
case class From[A](tables: List[String], next: A) extends Query[A]
case class FromQ[A](subQuery: Free[Query,Unit], next: A) extends Query[A]
case class Where[A,B](logics: List[Conditional[B]], next: A) extends Query[A] {
  def and(newLogic: Conditional[B]): Where[A,B] = Where(this.logics ::: newLogic :: Nil, next)
}
case object Done extends Query[Nothing]

object Query {

  implicit object queryFunctor extends Functor[Query] {

    def map[A, B](fa: Query[A])(f: A => B): Query[B] = fa match {
      case Select(columns, next) => Select(columns, f(next))
      case From(tables, next) => From(tables, f(next) )
      case FromQ(subQuery, next) => FromQ(subQuery, f(next))
      case Where(logics, next) => Where(logics, f(next))
      case Done => Done
    }
  }

  def select(columns: List[String]): Free[Query, Unit] =
    Suspend(Select(columns, Return(())))

  def from(tables: List[String]): Free[Query, Unit] =
    Suspend(From(tables, Return(())))

  def fromQ(subQuery: Free[Query,Unit]): Free[Query, Unit] = 
    Suspend(FromQ(subQuery, Return(())))
 
  def done: Free[Query, Unit] = 
    Return(Done)

  def where[A](logics: List[Conditional[A]]): Free[Query, Unit] = 
    Suspend(Where(logics, Return(())))

  private def renderConditional[A](conditional: Conditional[A]): String = conditional match {
    case Eq(column, _) => "%s = ?".format(column)
    case LessThan(column, _) => "%s < ?".format(column)
    case LessThanOrEqualTo(column, _) =>  "%s <= ?".format(column)
    case In(column, values) => "%s IN (%s)".format( column, values.map {_ => "?" }.mkString(",") ) 
  }

  def sqlInterpreter[A](query: Free[Query, A], statements: List[String]): String = query match {
    case Suspend(Select(columns, a)) => sqlInterpreter(a, statements :::  "select %s".format(columns.mkString(",")) :: Nil)
    case Suspend(From(tables, a)) => sqlInterpreter(a, statements ::: "from %s".format(tables.mkString(",")) :: Nil)
    case Suspend(FromQ(subquery, a)) => sqlInterpreter(a, statements ::: "from ( %s )".format(sqlInterpreter(subquery,Nil)) :: Nil)
    case Suspend(Where(logics, a)) => 
      val whereStatement: String = logics.map { renderConditional }.mkString(" AND ") match {
        case "" => ""
        case string => "WHERE %s".format(string)
      }
      sqlInterpreter(a, statements ::: whereStatement :: Nil )
    case Suspend(Done) => statements.mkString("\n")
    case Return(a) => statements.mkString("\n")
    case Gosub(a, f) => {
      val freeA = a()
      val value = sqlInterpreter(freeA, statements)
      sqlInterpreter(f(()), List(value))
      /*freeA match {
        case Suspend(Select(columns,_)) => sqlInterpreter(f(()), value)
        case Suspend(From(tables, _)) => sqlInterpreter(f(()), value)
        case Suspend(FromQ(_,_)) => sqlInterpreter(f(()), value)
        case Return(newA) => sqlInterpreter(f(newA), value)
        case _ => value
      }*/
    }
  }

  def sqlPrepared[A](query: Free[Query, A], st: PreparedStatement, counter: Int = 1): (PreparedStatement, Int) = query match {
    case Suspend(Select(columns, a)) => sqlPrepared(a, st, counter)
    case Suspend(From(tables, a)) => sqlPrepared(a, st, counter)
    case Suspend(FromQ(subquery, a)) => sqlPrepared(a, st, counter)
    case Suspend(Where(logics, a)) => 
      val (newStatement, newCounter): (PreparedStatement, Int) = logics.foldLeft( (st, counter) ){ (acc, logic) => logic match {
        case conditional @ Eq(_, value) => conditional.getSetter.set(st, counter, value); (st, counter + 1)
        case conditional @ LessThan(_, value) => conditional.getSetter.set(st, counter, value); (st, counter + 1)
        case conditional @ LessThanOrEqualTo(_, value) => conditional.getSetter.set(st, counter, value); (st, counter + 1)
        case conditional @ In(_, values) => 
          values.foldLeft( (st, counter) ){ case ( (newSt, newInCounter), value) => 
            conditional.getSetter.set(newSt, newInCounter, value)
            (newSt, newInCounter + 1)
          }
      }}
      sqlPrepared(a, newStatement, newCounter)
    case Suspend(Done) => (st, counter)
    case Return(a) => (st, counter)
    case Gosub(a, f) => {
      val freeA = a()
      val (value, inc)= sqlPrepared(freeA, st, counter )
      sqlPrepared(f(()), value, inc)
      /*freeA match {
        case Suspend(Select(columns,_)) => sqlInterpreter(f(()), value)
        case Suspend(From(tables, _)) => sqlInterpreter(f(()), value)
        case Suspend(FromQ(_,_)) => sqlInterpreter(f(()), value)
        case Return(newA) => sqlInterpreter(f(newA), value)
        case _ => value
      }*/
    }
  }

 
  val sub: Free[Query, Unit] = 
    for {
      _ <- select("XXXX" :: "YYY" :: Nil)
      _ <- from("sub-table" :: Nil)
      _ <- where(In("neat", List(1,2,3,4)) :: Nil)
    } yield ()

  val tmp: Free[Query, Unit] =
    for {
      y <- select("column1" :: "column2" :: Nil)
      _ <- from("cool" :: Nil) 
      _ <- fromQ(sub)
      _ <- where( Eq("levin", "cool") :: Nil )
      _ <- done
    } yield ()

}

object QueryMain {

  def main(args: Array[String]) {

    import Query._

    println("*** tmp: %s".format(sqlInterpreter(tmp,Nil)))

  }

}