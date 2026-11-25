plugins {
    java
}

group = "example.waterbucket"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly(files("libs/Waterbucket.jar"))
}

tasks.jar {
    archiveBaseName.set("EconomyApiPlugin")
}
