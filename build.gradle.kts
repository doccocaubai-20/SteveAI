plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.steveai"
version = "0.5.5"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("net.citizensnpcs:citizensapi:2.0.43-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("com.google.gson", "dev.steveai.libs.gson")
    }

    build {
        dependsOn(shadowJar)
    }
}
