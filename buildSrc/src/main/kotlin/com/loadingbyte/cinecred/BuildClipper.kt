package com.loadingbyte.cinecred

import com.loadingbyte.cinecred.Platform.OS.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject


abstract class BuildClipper : DefaultTask() {

    @get:Input
    abstract val forPlatform: Property<Platform>
    @get:InputDirectory
    abstract val capiDir: DirectoryProperty
    @get:InputDirectory
    abstract val repositoryDir: DirectoryProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val forPlatform = forPlatform.get()
        val repoDir = repositoryDir.get().asFile.resolve("CPP/Clipper2Lib")
        val incDir = repoDir.resolve("include")
        val srcPaths = listOf(repoDir.resolve("src"), capiDir.get().asFile)
            .flatMap { it.listFiles { f: File -> f.extension == "cpp" && "triangulation" !in f.name }.asList() }
            .map { it.absolutePath }
        val outFile = outputFile.get().asFile

        val cmd = mutableListOf<String>()
        if (forPlatform.os == WINDOWS) {
            val sub = mutableListOf<String>()
            sub += listOf(Tools.vcvars(execOps), "&&", "cl", "/LD", "/MD", "/std:c++17", "/O2", "/GL", "/GR-")
            sub += listOf("/DCAPI=__declspec(dllexport)", "/D_HAS_EXCEPTIONS=0")
            sub += listOf("\"/Fe:${outFile.absolutePath}\"", "/I", "\"${incDir.absolutePath}\"")
            sub += srcPaths.map { "\"$it\"" }
            sub += listOf("/link", "/NOIMPLIB", "/NOEXP")
            cmd += listOf("cmd", "/C", sub.joinToString(" "))
        } else {
            if (forPlatform.os == MAC) {
                cmd += listOf("clang++", "-dynamiclib", "-target", "${forPlatform.arch.slug}-apple-macos12")
                cmd += "-Wl,-install_name,@rpath/${outFile.name}"
            } else if (forPlatform.os == LINUX)
                cmd += listOf("g++", "-shared", "-s")
            cmd += listOf("-std=c++17", "-O3", "-fPIC", "-flto", "-fno-rtti", "-fno-exceptions")
            cmd += listOf("-o", outFile.absolutePath, "-I", incDir.absolutePath)
            cmd += srcPaths
        }

        execOps.exec { commandLine(cmd).workingDir(temporaryDir) }.rethrowFailure().assertNormalExitValue()
    }

}
