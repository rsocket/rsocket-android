/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://dl.bintray.com/kotlin/kotlinx")
    }

    val kotlinVersion: String by settings
    val bintrayVersion: String by settings
    val artifactoryVersion: String by settings
    val versionUpdatesVersion: String by settings
    val gradleEnterpriseVersion: String by settings
    val kotlinxBenchmarkVersion: String by settings

    plugins {
        kotlin("plugin.allopen") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion

        id("kotlinx.benchmark") version kotlinxBenchmarkVersion

        id("com.jfrog.bintray") version bintrayVersion
        id("com.jfrog.artifactory") version artifactoryVersion
        id("com.github.ben-manes.versions") version versionUpdatesVersion
        id("com.gradle.enterprise") version gradleEnterpriseVersion
    }
}

plugins {
    id("com.gradle.enterprise")
}

gradleEnterprise {
    buildScan {
        termsOfServiceUrl = "https://gradle.com/terms-of-service"
        termsOfServiceAgree = "yes"
    }
}

rootProject.name = "rsocket-kotlin"

include("benchmarks")
include("benchmarks-io")
include("playground")

include("rsocket-core")
include("rsocket-test")

include("rsocket-transport-local")

include("rsocket-transport-ktor")
include("rsocket-transport-ktor-client")
include("rsocket-transport-ktor-server")
project(":rsocket-transport-ktor-client").projectDir = file("rsocket-transport-ktor/rsocket-transport-ktor-client")
project(":rsocket-transport-ktor-server").projectDir = file("rsocket-transport-ktor/rsocket-transport-ktor-server")

fun includeExample(name: String) {
    include("examples:$name")
}

includeExample("nodejs-tcp-transport")
includeExample("interactions")
includeExample("multiplatform-chat")

val publishCheckVersion: String? by settings
if (publishCheckVersion != null) include("publish-check")
