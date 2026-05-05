plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.nexio.animemap.MainKt")
}

dependencies {
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
