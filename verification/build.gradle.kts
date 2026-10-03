plugins { kotlin("jvm") version "2.1.20" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.include("com/orangetiger/otstudio/Domain.kt", "com/orangetiger/otstudio/Api.kt", "com/orangetiger/otstudio/ModelOptions.kt", "com/orangetiger/otstudio/SchemaCodec.kt", "com/orangetiger/otstudio/LoraCodec.kt", "com/orangetiger/otstudio/LocalModels.kt", "com/orangetiger/otstudio/ModelDownloader.kt")
    }
    test { kotlin.srcDir("../app/src/test/java"); kotlin.exclude("com/orangetiger/otstudio/AndroidFlowTest.kt", "com/orangetiger/otstudio/NativeEngineTest.kt") }
}
dependencies {
    implementation("org.json:json:20250107")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "failed", "skipped") } }
dependencyLocking { lockAllConfigurations() }
