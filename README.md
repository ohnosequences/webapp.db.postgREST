# webapp.db.postgREST

DSL to simplify requests to postgREST within Scala Play

## Installation

In a Scala Play project, you should add this to your dependencies:

```scala
resolvers += "Era7 maven releases" at "https://s3-eu-west-1.amazonaws.com/releases.era7.com"
libraryDependencies += "ohnosequences" %% "webapp.db.postgrest" % "x.y.z"
```

where `x.y.z` is the version of the [latest release](https://github.com/ohnosequences/webapp-db-postgrest/releases/latest)

## Use

This package is intended to be used with [PostgreSQL](https://www.postgresql.org) and [PostgREST](http://postgrest.org) as database system.

As a requirement, the [token](http://postgrest.org/en/v5.2/tutorials/tut1.html) for the PostgREST should be passed implicitly to the endpoints every time we create a new instance. This token will be attached to each request as an http header `Authorization: Bearer {token}`

It provides a `Database.Endpoint` structure. This endpoint (a.k.a. a table or a view of PostgreSQL) could be called with `select`, `insert`, `update`, `delete`, `logicDeletion` / `undoLogicDeletion` (the table we are calling the deletion on should have a `deleted` field). For example, `logicDeletion` / `undoLogicDeletion` could be called on the following table:

```sql
CREATE TABLE projects (
    id SERIAL PRIMARY KEY,
    owner INTEGER REFERENCES users(id),
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    basespace_id INTEGER DEFAULT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    importing BOOLEAN NOT NULL DEFAULT TRUE,
    creation_date DATE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Let `endpoint` be a `Database.Endpoint` henceforth. All the requests return `Future`s. No matter what. `onFailure` and `onSuccess` methods always come in two flavors (`WSResponse` => Future[Result] ` and `WSResponse => Result`) but the result of the request to the endpoint will always be a `Future`. All the available methods need to be called in the following way (order matters):

```scala
type Result = Int
val success: Result = 12345

endpoint.{method}
    .{modifier: setSuccess, failIfAlreadyExists, singular}
    .{other modifier}
    ...
    .where(
        predicate,
        other_predicate,
        ...
    )
    // Note the onFailure has to be called always before the onSuccess
    // If not provided explicitly, a default one which returns a Future[InternalServerError]
    // is used
    .onFailure { response =>
        ...
        myResult: Result = ...
        myResult
    }
    .onSuccess { response =>
        ...
        success
    }
```

A few guidelines about the requests:

* By default, `onFailure` returns a [`scala.play.mvc.Result`](https://www.playframework.com/documentation/2.6.x/api/scala/index.html#play.api.mvc.Results$). If we set `onFailure` to return something else, as in the above example, `onSuccess` has to return the same type of result.
* When the `onSuccess` is called, the method is executed.
* Instead of `onFailure`, we can call `debug`, which in case of failure would print the request and the response from the server.
* `setSuccess` can be used to redefine the meaning of a request success, passing a set of `Int`s which would mean success in that query. Everything but a success is considered a failure (and `onFailure` would be called for that request instead of `onSuccess`). By default, we comply with the [PostgREST error codes](http://postgrest.org/en/v5.2/api.html#http-status-codes):
  - `select` requests need to return a 200 (`OK`) to be considered successful.
  - `insert` requests need to return a 200 (`OK`), 201 (`CREATED`), 409 (`CONFLICT`) to be considered successful. `CREATED` is what PostgREST returns in case the request is successful, `CONFLICT` is what it returns in case the primary key for the insertion already exists (the `failIfAlreadyExists` modifier removes the `CONFLICT` from the set of successful states for the insertion). `OK` is not going to be ever received, but just as a meaningful convention, we left it there.
  - `update` requests need to receive an 200 (`OK`) or a 204 (`NO_CONTENT`) to be successful.
  - `delete` requests need to receive a 200 (`OK`), 202 (`ACCEPTED`) or 204 (NO_CONTENT).
  - `logicDeletion` and `undoDeletion` are an update (and therefore have the same success codes).

### `where` clause

The `where` clause has to receive a comma separated list of predicates, where all the predicated are coded in `Database.Predicate` and comply with some of the operators listed [here](http://postgrest.org/en/v5.2/api.html#horizontal-filtering-rows):

```scala
import ohnosequences.webapp.db.postgrest.Database
import Database.{Predicate => Pred}

db.project
  .update(
    owner -> "gollum",
    description -> "My precious"
    importing -> true
  )
  .where(
    Pred.eq("id", 132421.toString),
    Pred.like("name", "%ring%")
  )
```

If we do not provide the where to the requests which accept it (`select`, `update`, `delete` and the logic deletion ones), then all the rows in that table would be affected.

### Select

The `select` queries allow getting two flavors of response:
* If the select is plural (i.e. it is NOT called with the `singular` modifier), `response.json.as[JsArray]` gives as [`JsArray`](https://www.playframework.com/documentation/2.6.x/api/scala/index.html#play.api.libs.json.JsArray).
* If the select is singular (i.e. it IS called with the `singular` modifier), `response.json.as[JsObject]` gives us a [`JsObject`](https://www.playframework.com/documentation/2.6.x/api/scala/index.html#play.api.libs.json.JsObject).

Examples:

#### Retrieving a bunch of things
```scala
db.projects.select
  .columns("id", "name", "description", "deleted", "importing")
  .where(
     Pred.eq("owner", request.user),
     Pred.is("deleted", Some(false))
  )
  .onFailure { response =>
     InternalServerError(
     "An error occurred while retrieving your projects")
  }
  .onSuccess { response =>
     // This is a JsArray!
     val result = response.json
     Ok(result)
  }
}
```
#### Retrieving a single thing
```scala
db.users.select
  .columns("id", "password")
  .singular
  .where(
    Pred.eq("email", "frodo@thesire.com")
  )
  .onFailure { _ =>
    Unauthorized("You are not allowed into Hobbiton!")
  }
  .onSuccess { response =>
    val user = response.json.as[JsObject]
    val password = user("password").as[String]
    ...
    Ok
  }
```


## One controller to inject them all! ([*one ring to rule them all*](https://www.youtube.com/watch?v=qj139dE7tFI))

We recommend having a class which wraps all the endpoints in the database and can be injected into other Play controllers:

```scala
package controllers

import javax.inject._
import play.api.libs.ws.{WSClient}
import scala.concurrent.{ExecutionContext}
import webapp.db.postgrest.Database

class Database @Inject()(val ws: WSClient, val configuration: Configuration)(
    implicit val ec: ExecutionContext,
    implicit val materializer: akka.stream.Materializer) {

  // The url where the database is being served
  private val host = "http://localhost:3000"

  // Get from the 
  implicit val token = configuration.get[String]("db.token")

  implicit val wsClient = ws

  val users                = Database.Endpoint(host + "/users")
  val sessions             = Database.Endpoint(host + "/sessions")
  val projects             = Database.Endpoint(host + "/projects")
  val datasets             = Database.Endpoint(host + "/datasets")
  val files                = Database.Endpoint(host + "/files")
  val datasetFiles         = Database.Endpoint(host + "/files_in_dataset")
  val projectDatasets      = Database.Endpoint(host + "/datasets_in_project")
  val projectAnalyses      = Database.Endpoint(host + "/project_analyses")
  val datasetAnalyses      = Database.Endpoint(host + "/dataset_analyses")
  val projectAnalysisOwner = Database.Endpoint(host + "/project_analysis_owner")
}
```

Then we can inject a `Database` instance in a controller, like this:

```scala

import ohnosequences.webapp.db.postgrest.Database
import Database.{Predicate => Pred}

class myFancyController @Inject()(cc: ControllerComponents, val db: Database) extends AbstractController(cc) { 

  def myFancySelect = Action.async { request =>
    db.users.select
      .columns("id", "pass")
      .where(
          Pred.eq("id", 132421)
      )
      .singular
      .onSuccess { response =>
          println(s"The password of John Doe is $pass")
      }
  }
}
```