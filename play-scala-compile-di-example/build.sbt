import com.typesafe.sbt.packager.docker.DockerChmodType

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, DockerPlugin, GraalVMPlugin)
  .settings(
    name := """play-scala-compile-di-example""",
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.13.1",
    libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test,
    scalacOptions ++= List(
      "-encoding",
      "utf8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings"
    )
    )
    .settings(compiletTimeNativeImageSettings)
    .settings(nativeImageDockerSettings)

val GraalAkkaVersion = "0.4.1"
val GraalVersion = "19.2.1"
val SVMVersion = "19.2.1"

def compiletTimeNativeImageSettings() = Seq(
  libraryDependencies ++= Seq(
    "org.graalvm.sdk" % "graal-sdk" % SVMVersion % "provided", // Only needed for compilation
    "com.oracle.substratevm" % "svm" % SVMVersion % "provided", // Only needed for compilation

    // Adds configuration to let Graal Native Image (SubstrateVM) work
    "com.github.vmencik" %% "graal-akka-actor" % GraalAkkaVersion % "provided", // Only needed for compilation
    "com.github.vmencik" %% "graal-akka-stream" % GraalAkkaVersion % "provided", // Only needed for compilation
    "com.github.vmencik" %% "graal-akka-http" % GraalAkkaVersion % "provided", // Only needed for compilation  
    )
)

// Shared settings for native image and docker builds
def nativeImageDockerSettings: Seq[Setting[_]] = dockerSettings ++ Seq(
  nativeImageDockerBuild := true,
  // If this is Some(…): run the native-image generation inside a Docker image
  // If this is None: run the native-image generation using a local GraalVM installation
  //graalVMVersion := Some(GraalVersion),
  graalVMVersion := None, 
  graalVMNativeImageOptions ++= sharedNativeImageSettings({
      graalVMVersion.value match {
        case Some(_) => new File("/opt/graalvm/stage/resources/")
        case None => baseDirectory.value / "src" / "graal"
      }
    }),
  (mappings in Docker) := Def.taskDyn {
      if (nativeImageDockerBuild.value) {
        Def.task {
          Seq(
            (packageBin in GraalVMNativeImage).value -> s"${(defaultLinuxInstallLocation in Docker).value}/bin/${executableScriptName.value}"
          )
        }
      } else {
        Def.task {
          // This is copied from the native packager DockerPlugin, because I don't think a dynamic task can reuse the
          // old value of itself in the dynamic part.
          def renameDests(from: Seq[(File, String)], dest: String) =
            for {
              (f, path) <- from
              newPath = "%s/%s" format (dest, path)
            } yield (f, newPath)

          renameDests((mappings in Universal).value, (defaultLinuxInstallLocation in Docker).value)
        }
      }
    }.value,
  dockerBaseImage := "bitnami/java:11-prod",
  // Need to make sure it has group execute permission
  // Note I think this is leading to quite large docker images :(
  dockerChmodType := {
    val old = dockerChmodType.value
    if (nativeImageDockerBuild.value) {
      DockerChmodType.Custom("u+x,g+x")
    } else {
      old
    }
  },
  dockerEntrypoint := {
    val old = dockerEntrypoint.value
    val withLibraryPath = if (nativeImageDockerBuild.value) {
      old :+ "-Djava.library.path=/opt/bitnami/java/lib"
    } else old
    proxyDockerBuild.value match {
      case Some((_, Some(configResource))) => withLibraryPath :+ s"-Dconfig.resource=$configResource"
      case _ => withLibraryPath
    }
  }
)

def sharedNativeImageSettings(targetDir: File) = Seq(
  //"-O1", // Optimization level
  "-H:ResourceConfigurationFiles=" + targetDir / "resource-config.json",
  "-H:ReflectionConfigurationFiles=" + targetDir / "reflect-config.json",
  "-H:DynamicProxyConfigurationFiles=" + targetDir / "proxy-config.json",
  "-H:IncludeResources=.+\\.conf",
  "-H:IncludeResources=.+\\.properties",
  "-H:+AllowVMInspection",
  "-H:-RuntimeAssertions",
  "-H:+ReportExceptionStackTraces",
  "-H:-PrintUniverse", // if "+" prints out all classes which are included
  "-H:-NativeArchitecture", // if "+" Compiles the native image to customize to the local CPU arch
  "-H:Class=" + "io.cloudstate.proxy.CloudStateProxyMain",
  "--verbose",
  //"--no-server", // Uncomment to not use the native-image build server, to avoid potential cache problems with builds
  //"--report-unsupported-elements-at-runtime", // Hopefully a self-explanatory flag
  "--enable-url-protocols=http,https",
  "--allow-incomplete-classpath",
  "--no-fallback",
  "--initialize-at-build-time"
    + Seq(
      "org.slf4j",
      "scala",
      "akka.dispatch.affinity",
      "akka.util"
    ).mkString("=", ",", ""),
  "--initialize-at-run-time=" +
    Seq(
      // We want to delay initialization of these to load the config at runtime
      "com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder",
      "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder",
      // These are to make up for the lack of shaded configuration for svm/native-image in grpc-netty-shaded
      "com.sun.jndi.dns.DnsClient"
    ).mkString(",")
)

def dockerSettings: Seq[Setting[_]] = Seq(
  proxyDockerBuild := None,
  dockerUpdateLatest := true,
  dockerRepository := sys.props.get("docker.registry"),
  dockerUsername := sys.props.get("docker.username").orElse(Some("cloudstateio")).filter(_ != ""),
  dockerAlias := {
    val old = dockerAlias.value
    proxyDockerBuild.value match {
      case Some((dockerName, _)) => old.withName(dockerName)
      case None => old
    }
  },
  dockerAliases := {
    val old = dockerAliases.value
    val single = dockerAlias.value
    // If a tag is explicitly configured, publish that, otherwise if it's a snapshot, just publish latest, otherwise,
    // publish both latest and the version
    sys.props.get("docker.tag") match {
      case some @ Some(_) => Seq(single.withTag(some))
      case _ if isSnapshot.value => Seq(single.withTag(Some("latest")))
      case _ => old
    }
  },
  // For projects that we publish using Docker, disable the generation of java/scaladocs
  publishArtifact in (Compile, packageDoc) := false
)



lazy val proxyDockerBuild = settingKey[Option[(String, Option[String])]](
  "Docker artifact name and configuration file which gets overridden by the buildProxy command"
)
lazy val nativeImageDockerBuild =
  settingKey[Boolean]("Whether the docker image should be based on the native image or not.")

