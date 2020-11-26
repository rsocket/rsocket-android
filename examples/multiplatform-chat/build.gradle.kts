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

import org.jetbrains.kotlin.konan.target.*

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("kotlinx-atomicfu")
}

val ktorVersion: String by rootProject
val kotlinxSerializationVersion: String by rootProject

kotlin {
    jvm("serverJvm")
    jvm("clientJvm")
    js("clientJs", LEGACY) {
        browser {
            binaries.executable()
        }
        nodejs {
            binaries.executable()
        }
    }
    when {
        HostManager.hostIsLinux -> linuxX64("clientNative")
        HostManager.hostIsMingw -> null //no native support for TCP mingwX64("clientNative")
        HostManager.hostIsMac   -> macosX64("clientNative")
        else                    -> null
    }?.binaries {
        executable {
            entryPoint = "main"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":rsocket-core"))

                implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:$kotlinxSerializationVersion")
            }
        }

        val clientMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":rsocket-transport-ktor-client"))
            }
        }

        val serverJvmMain by getting {
            dependencies {
                implementation(project(":rsocket-transport-ktor-server"))
                implementation("io.ktor:ktor-server-cio:$ktorVersion")
            }
        }

        val clientJvmMain by getting {
            dependsOn(clientMain)
            dependencies {
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
            }
        }

        val clientJsMain by getting {
            dependsOn(clientMain)
            dependencies {
                implementation("io.ktor:ktor-client-js:$ktorVersion")
            }
        }

        if (!HostManager.hostIsMingw) {
            val clientNativeMain by getting {
                dependsOn(clientMain)
            }
        }
    }
}
