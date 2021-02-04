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

import com.jfrog.bintray.gradle.*
import com.jfrog.bintray.gradle.tasks.*
import org.gradle.api.publish.maven.internal.artifact.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.konan.target.*
import org.jfrog.gradle.plugin.artifactory.dsl.*

buildscript {
    repositories {
        mavenCentral()
    }
    val kotlinVersion: String by rootProject
    val kotlinxAtomicfuVersion: String by rootProject

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$kotlinxAtomicfuVersion")
    }
}

val kotlinxAtomicfuVersion: String by rootProject

plugins {
    id("com.github.ben-manes.versions")

    //needed to add classpath to script
    id("com.jfrog.bintray") apply false
    id("com.jfrog.artifactory") apply false
}

//true when on CI, and false when local dev. Needed for build configuration
val ciRun = System.getenv("CI") == "true"

//configure main host, for which jvm and js tests are enabled, true if locally, or linux on CI
val isMainHost: Boolean = !ciRun || HostManager.hostIsLinux

// can be: macos, ios, watchos, tvos. If provided, compile and test only those targets
val macTargetsCompilation: String? by project

println("Configuration: ciRun=$ciRun, isMainHost=$isMainHost, macTargetsCompilation=$macTargetsCompilation")

val Project.publicationNames: Array<String>
    get() {
        val publishing: PublishingExtension by extensions
        val all = publishing.publications.names
        //publish js, jvm, metadata, linuxX64 and kotlinMultiplatform only once
        return when {
            isMainHost -> all
            else       -> all - "js" - "jvm" - "metadata" - "kotlinMultiplatform" - "linuxX64"
        }.toTypedArray()
    }

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        //targets configuration
        extensions.configure<KotlinMultiplatformExtension> {
            val isAutoConfigurable = project.name.startsWith("rsocket") //manual config of others
            val jvmOnly = project.name == "rsocket-transport-ktor-server" //server is jvm only

            //windows target isn't supported by ktor-network
            val supportMingw = project.name != "rsocket-transport-ktor" && project.name != "rsocket-transport-ktor-client"


            if (!isAutoConfigurable) return@configure

            jvm {
                compilations.all {
                    kotlinOptions {
                        jvmTarget = "1.6"
                        useIR = true
                    }
                }
                testRuns.all {
                    executionTask.configure {
                        enabled = isMainHost
                        jvmArgs("-Xmx4g", "-XX:+UseParallelGC")
                    }
                }
            }

            if (jvmOnly) return@configure

            js {
                useCommonJs()
                //configure running tests for JS
                nodejs {
                    testTask {
                        enabled = isMainHost
                        useMocha {
                            timeout = "600s"
                        }
                    }
                }
                browser {
                    testTask {
                        enabled = isMainHost
                        useKarma {
                            useConfigDirectory(rootDir.resolve("js").resolve("karma.config.d"))
                            useChromeHeadless()
                        }
                    }
                }
            }

            //native targets configuration
            val hostTargets = listOfNotNull(linuxX64(), macosX64(), if (supportMingw) mingwX64() else null)

            val iosTargets = listOf(iosArm32(), iosArm64(), iosX64())
            val tvosTargets = listOf(tvosArm64(), tvosX64())
            val watchosTargets = listOf(watchosArm32(), watchosArm64(), watchosX86())
            val nativeTargets = hostTargets + iosTargets + tvosTargets + watchosTargets

            val nativeMain by sourceSets.creating {
                dependsOn(sourceSets["commonMain"])
            }
            val nativeTest by sourceSets.creating {
                dependsOn(sourceSets["commonTest"])
            }

            nativeTargets.forEach {
                sourceSets["${it.name}Main"].dependsOn(nativeMain)
                sourceSets["${it.name}Test"].dependsOn(nativeTest)
            }

            fun KotlinNativeTarget.disableCompilation() {
                compilations.all { compileKotlinTask.enabled = false }
                binaries.all { linkTask.enabled = false }
            }

            //disable cross compilation of linux target on non linux hosts
            if (!HostManager.hostIsLinux) linuxX64().disableCompilation()

            //disable compilation of part of mac targets
            if (HostManager.hostIsMac) when (macTargetsCompilation) {
                "macos"                  -> iosTargets + tvosTargets + watchosTargets
                "ios", "watchos", "tvos" -> {
                    //disable test compilation for macos, but leave main to compile examples and playground
                    macosX64 {
                        compilations.all { if (name == "test") compileKotlinTask.enabled = false }
                        binaries.all { linkTask.enabled = false }
                    }
                    when (macTargetsCompilation) {
                        "ios"     -> tvosTargets + watchosTargets
                        "watchos" -> iosTargets + tvosTargets
                        "tvos"    -> iosTargets + watchosTargets
                        else      -> emptyList()
                    }
                }
                else                     -> emptyList()
            }.forEach(KotlinNativeTarget::disableCompilation)

            //run tests on release + mimalloc to reduce tests execution time
            //compilation is slower in that mode, but work with buffers is much faster
            if (ciRun) {
                targets.all {
                    if (this is KotlinNativeTargetWithTests<*>) {
                        binaries.test(listOf(RELEASE))
                        testRuns.all { setExecutionSourceFrom(binaries.getTest(RELEASE)) }
                        compilations.all {
                            kotlinOptions.freeCompilerArgs += "-Xallocator=mimalloc"
                        }
                    }
                }
            }
        }

        //common configuration
        extensions.configure<KotlinMultiplatformExtension> {
            val isTestProject = project.name == "rsocket-test"
            val isLibProject = project.name.startsWith("rsocket")
            val isPlaygroundProject = project.name == "playground"
            val isExampleProject = "examples" in project.path

            sourceSets.all {
                languageSettings.apply {
                    progressiveMode = true
                    languageVersion = "1.4"
                    apiVersion = "1.4"

                    useExperimentalAnnotation("kotlin.RequiresOptIn")

                    if (name.contains("test", ignoreCase = true) || isTestProject || isPlaygroundProject) {
                        useExperimentalAnnotation("kotlin.time.ExperimentalTime")
                        useExperimentalAnnotation("kotlin.ExperimentalStdlibApi")

                        useExperimentalAnnotation("kotlinx.coroutines.ExperimentalCoroutinesApi")
                        useExperimentalAnnotation("kotlinx.coroutines.InternalCoroutinesApi")
                        useExperimentalAnnotation("kotlinx.coroutines.ObsoleteCoroutinesApi")
                        useExperimentalAnnotation("kotlinx.coroutines.FlowPreview")

                        useExperimentalAnnotation("io.ktor.util.KtorExperimentalAPI")
                        useExperimentalAnnotation("io.ktor.util.InternalAPI")
                        useExperimentalAnnotation("io.ktor.utils.io.core.internal.DangerousInternalIoApi")

                        useExperimentalAnnotation("io.rsocket.kotlin.TransportApi")
                        useExperimentalAnnotation("io.rsocket.kotlin.ExperimentalMetadataApi")
                        useExperimentalAnnotation("io.rsocket.kotlin.ExperimentalStreamsApi")
                    }
                }
            }

            if (isLibProject && !isTestProject) {
                explicitApiWarning() //TODO change to strict before release
                sourceSets["commonTest"].dependencies {
                    implementation(project(":rsocket-test"))
                }
            }

            //fix atomicfu for examples and playground
            if (isExampleProject || isPlaygroundProject) {
                sourceSets["commonMain"].dependencies {
                    implementation("org.jetbrains.kotlinx:atomicfu:$kotlinxAtomicfuVersion")
                }
            }
        }
    }
}


//publication
subprojects {

    val versionSuffix: String? by project
    if (versionSuffix != null) {
        project.version = project.version.toString() + versionSuffix
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            targets.all {
                mavenPublication {
                    pom {
                        name.set(project.name)
                        description.set(project.description)
                        url.set("http://rsocket.io")

                        licenses {
                            license {
                                name.set("The Apache Software License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("whyoleg")
                                name.set("Oleg Yukhnevich")
                                email.set("whyoleg@gmail.com")
                            }
                            developer {
                                id.set("OlegDokuka")
                                name.set("Oleh Dokuka")
                                email.set("oleh.dokuka@icloud.com")
                            }
                        }
                        scm {
                            connection.set("https://github.com/rsocket/rsocket-kotlin.git")
                            developerConnection.set("https://github.com/rsocket/rsocket-kotlin.git")
                            url.set("https://github.com/rsocket/rsocket-kotlin")
                        }
                    }
                }
            }
        }
    }
}

val bintrayUser: String? by project
val bintrayKey: String? by project
if (bintrayUser != null && bintrayKey != null) {

    //configure artifactory
    subprojects {
        plugins.withId("com.jfrog.artifactory") {
            configure<ArtifactoryPluginConvention> {
                setContextUrl("https://oss.jfrog.org")

                publish(delegateClosureOf<PublisherConfig> {
                    repository(delegateClosureOf<DoubleDelegateWrapper> {
                        setProperty("repoKey", "oss-snapshot-local")
                        setProperty("username", bintrayUser)
                        setProperty("password", bintrayKey)
                        setProperty("maven", true)
                    })
                    println("Artifactory: ${publicationNames.contentToString()}")
                    defaults(delegateClosureOf<groovy.lang.GroovyObject> {
                        invokeMethod("publications", publicationNames)
                    })
                })

                val buildNumber: String? by project

                if (buildNumber != null) {
                    clientConfig.info.buildNumber = buildNumber
                }
            }
        }
    }

    //configure bintray / maven central
    val sonatypeUsername: String? by project
    val sonatypePassword: String? by project
    if (sonatypeUsername != null && sonatypePassword != null) {
        subprojects {
            plugins.withId("com.jfrog.bintray") {
                extensions.configure<BintrayExtension> {
                    user = bintrayUser
                    key = bintrayKey
                    println("Bintray: ${publicationNames.contentToString()}")
                    setPublications(*publicationNames)

                    publish = true
                    override = true
                    pkg.apply {

                        repo = "RSocket"
                        name = "rsocket-kotlinx"

                        setLicenses("Apache-2.0")

                        issueTrackerUrl = "https://github.com/rsocket/rsocket-kotlin/issues"
                        websiteUrl = "https://github.com/rsocket/rsocket-kotlin"
                        vcsUrl = "https://github.com/rsocket/rsocket-kotlin.git"

                        githubRepo = "rsocket/rsocket-kotlin"
                        githubReleaseNotesFile = "README.md"

                        version.apply {
                            name = project.version.toString()
                            vcsTag = project.version.toString()
                            released = java.util.Date().toString()

//                            gpg.sign = true

//                            mavenCentralSync.apply {
//                                user = sonatypeUsername
//                                password = sonatypePassword
//                            }
                        }
                    }
                }

                //workaround for https://github.com/bintray/gradle-bintray-plugin/issues/229
                tasks.withType<BintrayUploadTask> {
                    val names = publicationNames
                    names.forEach { dependsOn("publish${it.capitalize()}PublicationToMavenLocal") }
                    doFirst {
                        val publishing: PublishingExtension by project.extensions
                        publishing.publications
                            .filterIsInstance<MavenPublication>()
                            .filter { it.name in names }
                            .forEach { publication ->
                                val moduleFile = buildDir.resolve("publications/${publication.name}/module.json")
                                if (moduleFile.exists()) {
                                    publication.artifact(object : FileBasedMavenArtifact(moduleFile) {
                                        override fun getDefaultExtension() = "module"
                                    })
                                }
                            }
                    }
                }
            }
        }
    }
}
