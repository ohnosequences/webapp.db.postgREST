package webapp.db.postgrest

import java.net.URLEncoder

object HttpRequest {
  @inline def escapeParameter(parameter: String): String =
    URLEncoder.encode(parameter, "UTF-8")
}
