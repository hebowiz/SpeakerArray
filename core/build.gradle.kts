plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "failed", "skipped"); showStandardStreams = true } }

// Uses the exact core designer consumed by Android. Output directories are explicit.
tasks.register<JavaExec>("experimentalFir") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.hebowiz.speakerarray.core.ExperimentalOfflineKt")
    args(providers.gradleProperty("experimentInput").getOrElse("../build/practical-audit"),
        providers.gradleProperty("experimentOutput").getOrElse("../build/experimental-audit/offline"))
}
