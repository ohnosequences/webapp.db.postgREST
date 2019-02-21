package webapp.db.postgrest

import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.mvc.{Result, Results}
import play.api.http.Status._
import play.api.libs.json.Json
import play.api.libs.ws.ahc.AhcWSRequest
import HttpRequest.escapeParameter

object Database {
  type HTTPMethod = WSRequest => Future[WSResponse]
  type Column     = String
  type Value      = String

  case class Query(col: Column, value: Value)

  sealed case class Predicate(operator: String)

  trait BinaryPredicate {
    this: Predicate =>

    def apply[T](col: Column, value: T): Query =
      Query(escapeParameter(col), operator ++ escapeParameter(value.toString))
  }

  trait MultiPredicate {
    this: Predicate =>

    def apply(queries: Query*): Query = {
      val queriesString = queries
        .map {
          case Query(col, value) =>
            col ++ "." ++ value
        }
        .mkString(",")

      Query(operator, "(" ++ queriesString ++ ")")
    }
  }

  case object Predicate {
    final object eq    extends Predicate("eq.") with BinaryPredicate
    final object lt    extends Predicate("lt.") with BinaryPredicate
    final object gt    extends Predicate("gt.") with BinaryPredicate
    final object lte   extends Predicate("lte.") with BinaryPredicate
    final object gte   extends Predicate("gte.") with BinaryPredicate
    final object neq   extends Predicate("neq.") with BinaryPredicate
    final object like  extends Predicate("like.") with BinaryPredicate
    final object ilike extends Predicate("ilike.") with BinaryPredicate
    final object in extends Predicate("in.") {
      def apply[T](col: Column, values: T*): Query = {
        val escaped = values.map { a =>
          escapeParameter(a.toString)
        }

        Query(escapeParameter(col),
              operator ++ "(" ++ escaped.mkString(",") ++ ")")
      }
    }
    final object and extends Predicate("and") with MultiPredicate
    final object or  extends Predicate("or") with MultiPredicate
    final object not extends Predicate("not.") {
      def apply(query: Query): Query =
        Query(query.col, operator ++ query.value)
    }
    final object is extends Predicate("is.") {
      def apply(col: Column, value: Option[Boolean]): Query =
        // Substitutes None -> "null", Some(true) -> "true", Some(false) -> "false"
        Query(escapeParameter(col), operator ++ value.fold("null") {
          _.toString
        })
    }

    type eq    = eq.type
    type lt    = lt.type
    type gt    = gt.type
    type lte   = lte.type
    type gte   = gte.type
    type neq   = neq.type
    type like  = like.type
    type ilike = ilike.type
    type in    = in.type
    type and   = and.type
    type or    = or.type
    type not   = not.type
  }

  object Commons {

    def where(request: WSRequest, queries: Query*): WSRequest = {
      val queryParams = queries.map {
        case Query(col, value) =>
          (col, value)
      }

      // _* allows us to convert a List[String] to String*
      // https://alvinalexander.com/scala/how-to-define-methods-variable-arguments-varargs-fields
      request.addQueryStringParameters(queryParams: _*)
    }
  }

  // A is the type of the result of calling onFailure or onSuccess
  // R[X] is the actual type of the request
  sealed abstract class Request[A, R[X]](
      request: WSRequest,
      success: Set[Int],
      callback: WSResponse => Future[A]
  )(implicit ec: ExecutionContext) {

    val method: HTTPMethod

    def setSuccess(success: Set[Int]): R[A]

    def onFailure[B](callback: WSResponse => Future[B]): R[B]

    // This is a trick found here:
    // https://stackoverflow.com/questions/3307427/scala-double-definition-2-methods-have-the-same-type-erasure
    // If we declare callback: instead of callback: =>
    // the compiler will complain about type erasure
    def onFailure[B](callback: => WSResponse => B): R[B] =
      onFailure { response =>
        Future.successful { callback(response) }
      }

    def onSuccess(handler: WSResponse => Future[A]): Future[A] =
      method(request).flatMap { response =>
        val status = response.status

        if (success.contains(status))
          handler(response)
        else
          callback(response)
      }

    def onSuccess(handler: => WSResponse => A): Future[A] =
      method(request).flatMap { response =>
        val status = response.status

        if (success.contains(status))
          Future.successful { handler(response) } else
          callback(response)
      }

    def debug: R[Result]
  }

  trait SubQuery[A, R[X]] {
    // We force extensions of SubQuery to extend also a Request
    this: Request[A, R] =>

    def where(values: (Query)*): R[A]
  }

  case object Request {
    private val defaultCallback: WSResponse => Future[Result] = { response =>
      Future.successful { Results.InternalServerError }
    }

    private val debugCallback: WSResponse => Future[Result] = { response =>
      println(s"Request response: ${response.body}")

      Future.successful { Results.InternalServerError }
    }

    case class Select[A](
        val request: WSRequest,
        val success: Set[Int] = Set(OK),
        val callback: WSResponse => Future[A] = defaultCallback
    )(implicit val ec: ExecutionContext)
        extends Request[A, Select](request, success, callback)
        with SubQuery[A, Select] {

      val method: HTTPMethod = _.get

      def setSuccess(success: Set[Int]): Select[A] =
        Select(request, success, callback)

      def onFailure[B](callback: WSResponse => Future[B]): Select[B] =
        Select(request, success, callback)

      def columns(args: String*): Select[A] = {
        val whichColumns = args.mkString(",")

        val newRequest = request
          .addQueryStringParameters(
            "select" -> whichColumns
          )

        Select(newRequest, success, callback)
      }

      def where(values: (Query)*): Select[A] = {
        val newRequest = Commons.where(request, values: _*)
        Select(newRequest, success, callback)
      }

      def singular: Select[A] = {
        val newRequest = request.addHttpHeaders(
          "Accept" -> "application/vnd.pgrst.object+json"
        )

        Select(newRequest, success, callback)
      }

      def debug: Select[Result] = {
        println(s"Request: $request")
        onFailure(debugCallback)
      }
    }

    case class Insert[A](
        val request: WSRequest,
        val value: JsValue,
        val success: Set[Int] = Set(OK, CREATED, CONFLICT),
        val callback: WSResponse => Future[A] = defaultCallback
    )(implicit val ec: ExecutionContext)
        extends Request[A, Insert](request, success, callback) {

      val method: HTTPMethod = _.post(value)

      def setSuccess(success: Set[Int]): Insert[A] =
        Insert(request, value, success, callback)

      def failIfAlreadyExists: Insert[A] =
        Insert(request, value, success - CONFLICT, callback)

      def onFailure[B](callback: WSResponse => Future[B]): Insert[B] =
        Insert(request, value, success, callback)

      def debug: Insert[Result] = {
        println(s"Request: $request")
        println(s"Fields to insert: $value")
        onFailure(debugCallback)
      }
    }

    case class Update[A](
        val request: WSRequest,
        val value: JsValue,
        val success: Set[Int] = Set(OK, NO_CONTENT),
        val callback: WSResponse => Future[A] = defaultCallback
    )(implicit val ec: ExecutionContext)
        extends Request[A, Update](request, success, callback)
        with SubQuery[A, Update] {

      val method: HTTPMethod = _.patch(value)

      def setSuccess(success: Set[Int]): Update[A] =
        Update(request, value, success, callback)

      def onFailure[B](callback: WSResponse => Future[B]): Update[B] =
        Update(request, value, success, callback)

      def where(values: (Query)*): Update[A] = {
        val newRequest = Commons.where(request, values: _*)
        Update(newRequest, value, success, callback)
      }

      def debug: Update[Result] = {
        println(s"Request: $request")
        println(s"Fields to update: $value")
        onFailure(debugCallback)
      }
    }

    case class Delete[A](
        val request: WSRequest,
        val success: Set[Int] = Set(OK, ACCEPTED, NO_CONTENT),
        val callback: WSResponse => Future[A] = defaultCallback
    )(implicit val ec: ExecutionContext)
        extends Request[A, Delete](request, success, callback)
        with SubQuery[A, Delete] {

      val method: HTTPMethod = _.delete

      def setSuccess(success: Set[Int]): Delete[A] =
        Delete(request, success, callback)

      def onFailure[B](callback: WSResponse => Future[B]): Delete[B] =
        Delete(request, success, callback)

      def where(values: (Query)*): Delete[A] = {
        val newRequest = Commons.where(request, values: _*)
        Delete(newRequest, success, callback)
      }

      def debug: Delete[Result] = {
        println(s"Request: $request")
        onFailure(debugCallback)
      }
    }
  }

  /**
    * Encapsulates an endpoint of the database. We can make 4 kinds of main queries to
    * an endpoint in the database:
    *
    * - select
    * - insert
    * - update
    * - delete
    *
    * On top of that, we have coded implementations to perform logic deletions and un-deletions
    */
  case class Endpoint(val endpoint: String)(
      implicit val ws: WSClient,
      implicit val ec: ExecutionContext,
      implicit val token: String,
      implicit val materializer: akka.stream.Materializer) {

    import Request._

    /*
     This is necessary due to the automatic escaping of parameters AhcWSRequest
     performs internally. If we do not enable the disableUrlEncoding, we would end
     up with

     /projects?select=id,basespace

     being transformed into

     /projects?select=id%2basespace%2C

     due to the escaping of ,
     */
    private val request =
      ws.url(endpoint)
        .addHttpHeaders(
          "Authorization" -> ("Bearer" ++ " " ++ token)
        ) match {
        case AhcWSRequest(underlying) =>
          AhcWSRequest(underlying.copy(disableUrlEncoding = Some(true)))
      }

    override def toString: String =
      endpoint

    def select: Select[Result] =
      Select(request)

    // Insert a single object
    def insert(fields: (String, Json.JsValueWrapper)*): Insert[Result] =
      Insert(request, Json.obj(fields: _*))

    // Bulk insert
    def insert(values: JsArray): Insert[Result] =
      Insert(request, values)

    def update(fields: (String, Json.JsValueWrapper)*): Update[Result] =
      Update(request, Json.obj(fields: _*))

    def delete: Delete[Result] =
      Delete(request)

    def logicDeletion: Update[Result] =
      update("deleted" -> true)

    def undoLogicDeletion: Update[Result] =
      update("deleted" -> false)
  }
}
