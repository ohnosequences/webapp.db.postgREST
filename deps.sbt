val playVersion = "2.6.12"

val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % "3.0.5" % Test
)

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ws"     % playVersion,
  "com.typesafe.play" %% "play-ahc-ws" % playVersion
) ++ testDependencies
