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

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm("jvm")
    jvm("jvmIr") {
        compilations.all {
            kotlinOptions.useIR = true
        }
    }
    js("jsLegacy", LEGACY) {
        nodejs {
            binaries.executable()
            testTask {
                useMocha {
                    timeout = "600s"
                }
            }
        }
    }
    js("jsIr", IR) {
        nodejs {
            binaries.executable()
            testTask {
                useMocha {
                    timeout = "600s"
                }
            }
        }
    }
    linuxX64("native") {
        binaries.executable()
        binaries.test(listOf(RELEASE))
        testRuns.all { setExecutionSourceFrom(binaries.getTest(RELEASE)) }
    }
    linuxX64("nativeMimalloc") {
        binaries.executable()
        binaries.test(listOf(RELEASE))
        testRuns.all { setExecutionSourceFrom(binaries.getTest(RELEASE)) }
        compilations.all {
            kotlinOptions.freeCompilerArgs += "-Xallocator=mimalloc"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":rsocket-core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val jvmMain by getting {
            dependencies {
                api(kotlin("test-junit"))
            }
        }
        val jvmIrMain by getting {
            dependencies {
                api(kotlin("test-junit"))
            }
        }
        val jsLegacyMain by getting {
            dependencies {
                api(kotlin("test-js"))
            }
        }
        val jsIrMain by getting {
            dependencies {
                api(kotlin("test-js"))
            }
        }
    }
}
