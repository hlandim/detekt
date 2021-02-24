package io.gitlab.arturbosch.detekt.testkit

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID

@Suppress("TooManyFunctions")
class DslGradleRunner @Suppress("LongParameterList") constructor(
    val projectLayout: ProjectLayout,
    val buildFileName: String,
    val mainBuildFileContent: String,
    val configFileOrNone: String? = null,
    val baselineFileOrNone: String? = null,
    val gradleVersionOrNone: String? = null,
    val dryRun: Boolean = false
) {

    private val rootDir: File = Files.createTempDirectory("applyPlugin").toFile().apply { deleteOnExit() }
    private val randomString = UUID.randomUUID().toString()

    private val settingsContent = """
        |rootProject.name = "rootDir-project"
        |include(${projectLayout.submodules.joinToString(",") { "\"${it.name}\"" }})
        |
        """.trimMargin()

    private val baselineContent = """
        |<some>
        |   <xml/>
        |</some>
        """.trimMargin()

    private val configFileContent = """
        |build:
        |  maxIssues: 5
        |style:
        |  MagicNumber:
        |    active: true
        """.trimMargin()

    /**
     * Each generated file is different so the artifacts are not cached in between test runs
     */
    private fun ktFileContent(className: String, withCodeSmell: Boolean = false) = """
    |internal class $className(
    |   val randomDefaultValue: String = "$randomString"
    |) {
    |   val smellyConstant: Int = ${if (withCodeSmell) "11" else "0"}
    |}
    |
    """.trimMargin()

    fun setupProject() {
        writeProjectFile(buildFileName, mainBuildFileContent)
        writeProjectFile(SETTINGS_FILENAME, settingsContent)
        configFileOrNone?.let { writeProjectFile(configFileOrNone, configFileContent) }
        baselineFileOrNone?.let { writeProjectFile(baselineFileOrNone, baselineContent) }
        projectLayout.srcDirs.forEachIndexed { srcDirIdx, sourceDir ->
            repeat(projectLayout.numberOfSourceFilesInRootPerSourceDir) { srcFileIndex ->
                val withCodeSmell =
                    srcDirIdx * projectLayout.numberOfSourceFilesInRootPerSourceDir +
                        srcFileIndex < projectLayout.numberOfCodeSmellsInRootPerSourceDir
                writeKtFile(
                    dir = File(rootDir, sourceDir),
                    className = "My${srcDirIdx}Root${srcFileIndex}Class",
                    withCodeSmell = withCodeSmell
                )
            }
        }

        projectLayout.submodules.forEach { submodule ->
            val moduleRoot = File(rootDir, submodule.name)
            moduleRoot.mkdirs()
            File(moduleRoot, buildFileName).writeText(submodule.buildFileContent ?: "")
            submodule.srcDirs.forEachIndexed { srcDirIdx, moduleSourceDir ->
                repeat(submodule.numberOfSourceFilesPerSourceDir) {
                    val withCodeSmell =
                        srcDirIdx * submodule.numberOfSourceFilesPerSourceDir + it < submodule.numberOfCodeSmells
                    writeKtFile(
                        dir = File(moduleRoot, moduleSourceDir),
                        className = "My$srcDirIdx${submodule.name}${it}Class",
                        withCodeSmell = withCodeSmell)
                }
            }
        }
    }

    fun projectFile(path: String): File = File(rootDir, path).canonicalFile

    fun writeProjectFile(filename: String, content: String) {
        File(rootDir, filename).writeText(content)
    }

    fun writeKtFile(srcDir: String, className: String) {
        writeKtFile(File(rootDir, srcDir), className)
    }

    private fun writeKtFile(dir: File, className: String, withCodeSmell: Boolean = false) {
        dir.mkdirs()
        File(dir, "$className.kt").writeText(ktFileContent(className, withCodeSmell))
    }

    private fun buildGradleRunner(tasks: List<String>): GradleRunner {
        val args = mutableListOf("--stacktrace", "--info", "--build-cache")
        if (dryRun) {
            args.add("-Pdetekt-dry-run=true")
        }
        args.addAll(tasks.toList())

        return GradleRunner.create().apply {
            withProjectDir(rootDir)
            withPluginClasspath()
            withArguments(args)
            gradleVersionOrNone?.let { withGradleVersion(gradleVersionOrNone) }
        }
    }

    fun runTasksAndCheckResult(vararg tasks: String, doAssert: DslGradleRunner.(BuildResult) -> Unit) {
        this.doAssert(runTasks(*tasks))
    }

    fun runTasks(vararg tasks: String): BuildResult = buildGradleRunner(tasks.toList()).build()

    fun runTasksAndExpectFailure(vararg tasks: String, doAssert: DslGradleRunner.(BuildResult) -> Unit) {
        val result: BuildResult = buildGradleRunner(tasks.toList()).buildAndFail()
        this.doAssert(result)
    }

    fun runDetektTaskAndCheckResult(doAssert: DslGradleRunner.(BuildResult) -> Unit) {
        runTasksAndCheckResult(DETEKT_TASK) { this.doAssert(it) }
    }

    fun runDetektTask(): BuildResult = runTasks(DETEKT_TASK)

    fun runDetektTaskAndExpectFailure(doAssert: DslGradleRunner.(BuildResult) -> Unit = {}) {
        val result = buildGradleRunner(listOf(DETEKT_TASK)).buildAndFail()
        this.doAssert(result)
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.gradle"
        private const val DETEKT_TASK = "detekt"
    }
}
