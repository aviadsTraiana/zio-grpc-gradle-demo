import com.google.protobuf.gradle.*
import kotlin.collections.*

plugins {
    // Apply the scala Plugin to add support for Scala.
    scala
    java
    id("com.google.protobuf") version "0.8.16"
    kotlin("jvm") version "1.4.32"
    application
}

application {
    // Define the main class for the application.
    mainClass.set("zio.gradle.kotlin.App")
}


repositories {
    // Use JCenter for resolving dependencies.
    mavenCentral()
    jcenter()
}
val scalapbVersion = "0.11.2"
val zioGrpcVersion = "0.5.0"
dependencies {
    // Use Scala 2.13 in our library project
    implementation("org.scala-lang:scala-library:2.12.11")

    // This dependency is used by the application.

    implementation("dev.zio:zio_2.12:1.0.7") {
        because("we want to have pure service")
    }

    implementation("com.thesamet.scalapb.zio-grpc:zio-grpc-core_2.12:$zioGrpcVersion") {
        because("we want to generate the proto as pure functional")
    }

    implementation("io.grpc:grpc-netty:1.37.0"){
        because("we use grpc server here also")
    }

    //    implementation("io.grpc:grpc-netty:1.37.0")
    implementation("com.thesamet.scalapb:scalapb-runtime-grpc_2.12:$scalapbVersion") {
        because("ZIO-gRPC lets you write purely functional gRPC servers and clients")
    }
    implementation("com.thesamet.scalapb:scalapb-runtime_2.12:${scalapbVersion}")

    implementation("com.thesamet.scalapb:scalapb-json4s_2.12:0.11.0"){
        because("we load from json as if it were a db on server side")
    }


    //we can use this if we want to use proto def from a dependency
    /*
        val juroInfoServiceDep = implementation("com.cme.traiana.apis:juro-services-info_2.12:$depoVersion"){
        because("we use juro info grpc service and message definitions")
    }
    protobuf(juroInfoServiceDep)
    */

    // Use Scalatest for testing our library
    testImplementation("junit:junit:4.12")
    testImplementation("org.scalatest:scalatest_2.13:3.2.0")
    testImplementation("org.scalatestplus:junit-4-12_2.13:3.2.0.0")

    // Need scala-xml at test runtime
    testRuntimeOnly("org.scala-lang.modules:scala-xml_2.13:1.2.0")
}

val scalapbPlugin = "scalapb"
val zioGrpcPlugin = "ziogrpc"

protobuf {
    protoc {
        // The artifact spec for the Protobuf Compiler
        artifact = "com.google.protobuf:protoc:3.7.1"
        //https://github.com/google/protobuf-gradle-plugin/blob/master/examples/exampleKotlinDslProject/build.gradle.kts
    }
    plugins {
        val os = org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem()
        val artifactClassifier = if (os.isWindows) "windows@bat" else "unix@sh"
        // Optional: an artifact spec for a protoc plugin, with "grpc" as
        // the identifier, which can be referred to in the "plugins"
        // container of the "generateProtoTasks" closure.
        id(scalapbPlugin) {
            artifact = "com.thesamet.scalapb:protoc-gen-scala:$scalapbVersion:$artifactClassifier"
        }
        id(zioGrpcPlugin) {
            artifact = "com.thesamet.scalapb.zio-grpc:protoc-gen-zio:$zioGrpcVersion:$artifactClassifier"
        }
    }
    generateProtoTasks {
        ofSourceSet("main").forEach {

            it.builtins {
                //code generators built in protoc
                remove("java")
            }

            it.plugins {
                // Apply the "grpc" plugin whose spec is defined above, with options.
                id(scalapbPlugin) {
                    option("grpc")
                }
                id(zioGrpcPlugin) {}
            }
        }
    }
}

sourceSets {
    main {
        withConvention(ScalaSourceSet::class) {
            scala {
                val generatedCodePath = "${protobuf.protobuf.generatedFilesBaseDir}/main"
                srcDirs(
                    listOf(
                    "$generatedCodePath/$zioGrpcPlugin",
                    "$generatedCodePath/$scalapbPlugin"
                    )
                )
            }
        }
    }
}