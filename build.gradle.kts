/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import de.undercouch.gradle.tasks.download.*
import org.apache.tools.ant.taskdefs.condition.Os
import java.io.ByteArrayOutputStream
import org.gradle.process.ExecOperations
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.get

plugins {
    java
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.undercouchDownload)
    id("org.jetbrains.intellij.platform.migration") version "2.5.0"
    id("idea")
    alias(libs.plugins.ideaExt)
}

val isCI = System.getenv("INTELLIJ_EMMYLUA_IS_CI") != null

val name: String by project
val group: String by project
val version: String =
    if (isCI) System.getenv("INTELLIJ_EMMYLUA_BUILD_VERSION")
    else providers.gradleProperty("version").get()

val emmyDebuggerVersion: String by project

val isWin = Os.isFamily(Os.FAMILY_WINDOWS)

val flexGeneratedDir = layout.projectDirectory.dir("gen")
val externalLibDir = layout.projectDirectory.dir("ext")
val tmpDownloadDir = layout.buildDirectory.map { it.dir("tmpdownload") }


java {
    // also sets the jvm toolchain for kotlin
    toolchain { languageVersion = JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get()) }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
        testFramework(TestFrameworkType.Platform)
    }
    implementation(fileTree(baseDir = "libs") { include("*.jar") })
    implementation(libs.gson)
    implementation(libs.sbtIPCSocket)
    implementation(libs.luajJSE)
    implementation(libs.egitCore)
    implementation(libs.jgoodiesForms)
}

intellijPlatform {
    pluginConfiguration {
        name = name
        version = version
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
//        pluginVerification {
//            ides { recommended() }
//        }
    }
}

sourceSets.main {
    java.srcDirs(flexGeneratedDir)
    kotlin.srcDirs("src/main/compat")
    resources.srcDirs(externalLibDir)
//    resources.exclude("debugger/**")
//    resources.exclude("std/**")
}

tasks.test {
    useJUnitPlatform()
}


repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

fun getRev(execOps: ExecOperations): String {
    val os = ByteArrayOutputStream()
    execOps.exec {
        executable = "git"
        args("rev-parse", "HEAD")
        standardOutput = os
    }
    return os.toString().trim().substring(0, 7)
}

data class NativeDebugger(
    val zipfile: String,
    val libfile: String,
    val resourcedir: String,
) {
    fun downloadUrl() = "https://github.com/EmmyLua/EmmyLuaDebugger/releases/download/${emmyDebuggerVersion}/${zipfile}"
}

val nativeDebuggers = listOf(
    NativeDebugger("win32-x64.zip", "emmy_core.dll", "debugger/emmy/windows/x64"),
    NativeDebugger("win32-x86.zip", "emmy_core.dll", "debugger/emmy/windows/x86"),
    NativeDebugger("linux-x64.zip", "emmy_core.so", "debugger/emmy/linux"),
    NativeDebugger("darwin-x64.zip", "emmy_core.dylib", "debugger/emmy/mac/x64"),
    NativeDebugger("darwin-arm64.zip", "emmy_core.dylib", "debugger/emmy/mac/arm64"),
)

tasks.register<Download>("downloadEmmyDebugger") {
    src(nativeDebuggers.map { it.downloadUrl() })
    dest(tmpDownloadDir)
}

tasks.register<Copy>("unpackEmmyDebugger") {
    dependsOn("downloadEmmyDebugger")
    nativeDebuggers.forEach { nativeZip ->
        from(zipTree(tmpDownloadDir.map { it.file(nativeZip.zipfile) })) {
            include(nativeZip.libfile)
            into(nativeZip.resourcedir)
        }
    }
    destinationDir = externalLibDir.asFile
}

//    intellij {
//        type.set("IC")
//        updateSinceUntilBuild.set(false)
//        downloadSources.set(!isCI)
//        version.set(buildVersionData.ideaSDKVersion)
//        //localPath.set(System.getenv("IDEA_HOME_${buildVersionData.ideaSDKShortVersion}"))
//        sandboxDir.set("${project.buildDir}/${buildVersionData.ideaSDKShortVersion}/idea-sandbox")
//    }

// Inject ExecOperations service for use in tasks/functions if needed elsewhere
// val execOperations: ExecOperations by service() // Requires Gradle 7.4+ and plugins {} block
//
//abstract class BunchTask : DefaultTask() { // Create abstract task for injection
//    @get:Inject
//    abstract val execOperations: ExecOperations
//
//    @TaskAction
//    fun execute() {
//        val rev = getRev(execOperations) // Pass injected service
//        // reset
//        execOperations.exec { // Fix 3: Use injected execOperations
//            executable = "git"
//            args("reset", "HEAD", "--hard")
//        }
//        // clean untracked files
//        execOperations.exec { // Fix 3: Use injected execOperations
//            executable = "git"
//            args("clean", "-d", "-f")
//        }
//        // switch
//        execOperations.exec { // Fix 3: Use injected execOperations
//            executable = if (isWin) "bunch/bin/bunch.bat" else "bunch/bin/bunch"
//            args("switch", ".", buildVersionData.bunch)
//        }
//        // reset to HEAD
//        execOperations.exec { // Fix 3: Use injected execOperations
//            executable = "git"
//            args("reset", rev)
//        }
//    }
//}
//
//tasks.register<BunchTask>("bunch") // Register using the abstract task type
//
//tasks {
//    buildPlugin {
//        dependsOn("bunch", "installEmmyDebugger")
//        archiveBaseName.set(buildVersionData.archiveName)
//        from(fileTree(resDir) { include("!!DONT_UNZIP_ME!!.txt") }) {
//            into("/${project.name}")
//        }
//    }
//
//    compileKotlin {
//        kotlinOptions {
//            jvmTarget = buildVersionData.jvmTarget
//        }
//    }
//
//    instrumentCode {
//        compilerVersion.set(buildVersionData.instrumentCodeCompilerVersion)
//    }
//
//    withType<org.jetbrains.intellij.tasks.PrepareSandboxTask> {
//        doLast {
//            copy {
//                from("src/main/resources/std")
//                into("$destinationDir/${pluginName.get()}/std")
//            }
//            copy {
//                from("src/main/resources/debugger")
//                into("$destinationDir/${pluginName.get()}/debugger")
//            }
//        }
//    }
//}


//data class BuildData(
//    /** Ex. `2025.1` */
//    val version: String,
//    /** Ex. `251.23774.430`, see [releases](https://www.jetbrains.com/intellij-repository/releases) .*/
//    val fullNumber: String,
//    /** Ex. `251` */
//    val sinceBuild: String,
//    /** Ex. `251.*` */
//    val untilBuild: String,
//    val archiveName: String = "IntelliJ-EmmyLua",
//    val jvmTarget: String = "21",
//    val targetCompatibilityLevel: JavaVersion = JavaVersion.VERSION_21,
//    val explicitJavaDependency: Boolean = true,
//    // https://github.com/JetBrains/gradle-intellij-plugin/issues/403#issuecomment-542890849
//    val instrumentCodeCompilerVersion: String = ideaSDKVersion
//)
//
//val buildConfigs = listOf(
//    BuildData(
//        ideaSDKShortVersion = "251",
//        ideaSDKVersion = "251.23774.430",
//        sinceBuild = "251",
//        untilBuild = "251.*",
//        targetCompatibilityLevel = JavaVersion.VERSION_21,
//        jvmTarget = "21"
//    )
//)
//

