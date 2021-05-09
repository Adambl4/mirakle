import com.googlecode.streamflyer.core.ModifyingWriter
import com.googlecode.streamflyer.regex.fast.FastRegexModifier
import com.instamotor.mirakle.BuildConfig
import org.apache.commons.io.output.WriterOutputStream
import org.gradle.StartParameter
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.internal.AbstractTask
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.configuration.ConsoleOutput
import org.gradle.api.logging.configuration.ShowStacktrace
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskState
import org.gradle.process.internal.ExecAction
import org.gradle.process.internal.ExecActionFactory
import org.gradle.process.internal.ExecException
import org.gradle.tooling.GradleConnector
import org.gradle.workers.IsolationMode
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.inject.Inject

open class Mirakle : Plugin<Gradle> {
    override fun apply(gradle: Gradle) {
        gradle.rootProject { it.extensions.create("mirakle", MirakleExtension::class.java) }

        if (gradle.startParameter.taskNames.isEmpty()) return
        if (gradle.startParameter.projectProperties.containsKey(BUILD_ON_REMOTE)) return
        if (gradle.startParameter.projectProperties.containsKey(FALLBACK)) return
        if (gradle.startParameter.excludedTaskNames.remove("mirakle")) return
        if (gradle.startParameter.isDryRun) return

        val startTime = System.currentTimeMillis()

        gradle.assertNonSupportedFeatures()

        val startParamsCopy = gradle.startParameter.copy()
        val breakMode = startParamsCopy.projectProperties.let {
            (it[BREAK_MODE]?.toBoolean() ?: false) || (it[BREAK_TASK]?.isNotBlank() ?: false)
        }

        gradle.startParameter.apply {
            if (breakMode) {
                // pass all the tasks alongside with "mirakle" to let Gradle calculate taskGraph
                setTaskNames(listOf("mirakle") + taskNames)
            } else {
                setTaskNames(listOf("mirakle"))
                setExcludedTaskNames(emptyList())
            }
            if (!breakMode) {
                buildFile = File(startParamsCopy.currentDir, "mirakle.gradle").takeIf(File::exists)
                        ?: //a way to make Gradle not evaluate project's default build.gradle file on local machine
                        File(startParamsCopy.currentDir, "mirakle_build_file_stub").also { stub ->
                            stub.createNewFile()
                            gradle.rootProject { it.afterEvaluate { stub.delete() } }
                        }
            } else {
                if (File(startParamsCopy.currentDir, "mirakle.gradle").exists()) {
                    gradle.apply(mutableMapOf("from" to "mirakle.gradle"))
                }
            }
            // disable build scan on local machine, but it will be enabled on remote if flag is set
            isBuildScan = false
        }

        gradle.rootProject { project ->
            project.afterEvaluate {
                val config = kotlin.run {
                    val mirakleConfig = project.extensions.getByType(MirakleExtension::class.java)

                    getMainframerConfigOrNull(project.rootDir, mirakleConfig)?.also {
                        println("Mainframer config is applied, Mirakle config is ignored.")
                    } ?: mirakleConfig
                }

                if (config.host == null) throw MirakleException("Mirakle host is not defined.")

                println("Here's Mirakle ${BuildConfig.VERSION}. All tasks will be executed on ${config.host}.")

                val breakTaskFromCLI = startParamsCopy.projectProperties[BREAK_TASK]
                val breakOnTasks = breakTaskFromCLI?.takeIf(String::isNotBlank)?.let(::listOf) ?: config.breakOnTasks

                val upload = project.task<Exec>("uploadToRemote") {
                    setCommandLine("rsync")
                    args(
                            fixPathForWindows(project.rootDir.toString()),
                            "${config.host}:${config.remoteFolder}",
                            "--rsh",
                            "ssh ${config.sshArgs.joinToString(separator = " ")}",
                            "--exclude=mirakle.gradle"
                    )
                    args(config.buildRsyncToRemoteArgs())
                }

                val execute = project.task<Exec>("executeOnRemote") {
                    setCommandLine("ssh")
                    args(config.sshArgs)
                    args(
                            config.host,
                            "${config.remoteFolder}/\"${project.name}\"/gradlew",
                            "-P$BUILD_ON_REMOTE=true",
                            "-p ${config.remoteFolder}/\"${project.name}\""
                    )
                    startParamsCopy.copy()
                            .apply {
                                if (breakMode && breakOnTasks.isNotEmpty()) {
                                    // In break mode tasks arguments will be set later when Gradle::taskGraph is ready
                                    setTaskNames(emptyList())
                                }
                            }
                            .let(::mergeStartParamsWithProperties)
                            .let(::startParamsToArgs)
                            .let(::args)

                    isIgnoreExitValue = true

                    standardOutput = modifyOutputStream(
                            standardOutput ?: System.out,
                            "${config.remoteFolder}/${project.name}",
                            project.rootDir.path
                    )
                    errorOutput = modifyOutputStream(
                            errorOutput ?: System.err,
                            "${config.remoteFolder}/${project.name}",
                            project.rootDir.path
                    )
                }.mustRunAfter(upload) as Exec

                val download = project.task<Exec>("downloadFromRemote") {
                    setCommandLine("rsync")
                    args(
                            "${config.host}:${config.remoteFolder}/\"${project.name}\"/",
                            "./",
                            "--rsh",
                            "ssh ${config.sshArgs.joinToString(separator = " ")}",
                            "--exclude=mirakle.gradle"
                    )
                    args(config.buildRsyncFromRemoteArgs())
                }.mustRunAfter(execute) as Exec

                if (config.downloadInParallel) {
                    if (config.downloadInterval <= 0) throw MirakleException("downloadInterval must be >0")

                    val downloadInParallel = project.task<AbstractTask>("downloadInParallel") {
                        doFirst {
                            val downloadExecAction = services.get(ExecActionFactory::class.java).newExecAction().apply {
                                commandLine = download.commandLine
                                args = download.args
                                standardOutput = download.standardOutput ?: System.out
                                standardInput = download.standardInput ?: System.`in`
                            }

                            //It's impossible to pass this as serializable params to worker
                            //God forgive us
                            DownloadInParallelWorker.downloadExecAction = downloadExecAction
                            DownloadInParallelWorker.gradle = gradle

                            services.get(WorkerExecutor::class.java).submit(DownloadInParallelWorker::class.java) {
                                it.isolationMode = IsolationMode.NONE
                                it.setParams(config.downloadInterval)
                            }
                        }

                        onlyIf {
                            config.downloadInParallel && upload.execResult!!.exitValue == 0 && !execute.didWork
                        }
                    }

                    downloadInParallel.mustRunAfter(upload)
                    download.mustRunAfter(downloadInParallel)

                    gradle.startParameter.setTaskNames(listOf("downloadInParallel", "mirakle"))
                }

                val mirakle = project.task("mirakle").dependsOn(upload, execute, download)

                if (!config.fallback) {
                    mirakle.doLast {
                        execute.execResult!!.assertNormalExitValue()
                    }
                } else {
                    val fallback = project.task<DefaultTask>("fallback") {
                        onlyIf { upload.execResult!!.exitValue != 0 }

                        doFirst {
                            println("Upload to remote failed. Continuing with fallback.")

                            val connection = GradleConnector.newConnector()
                                    .forProjectDirectory(gradle.rootProject.projectDir)
                                    .connect()

                            connection.use { connection ->
                                connection.newBuild()
                                        .withArguments(startParamsToArgs(startParamsCopy).plus("-P$FALLBACK=true"))
                                        .setStandardInput(upload.standardInput ?: System.`in`)
                                        .setStandardOutput(upload.standardOutput ?: System.out)
                                        .setStandardError(upload.errorOutput ?: System.err)
                                        .run()
                            }
                        }
                    }

                    upload.isIgnoreExitValue = true
                    upload.finalizedBy(fallback)

                    execute.onlyIf { upload.execResult!!.exitValue == 0 }
                    download.onlyIf { upload.execResult!!.exitValue == 0 }

                    mirakle.doLast {
                        if (execute.didWork) {
                            execute.execResult!!.assertNormalExitValue()
                        }
                    }
                }

                if (breakMode && breakOnTasks.isNotEmpty()) {
                    if (config.downloadInParallel) {
                        throw MirakleException("Mirakle break mode doesn't work with download in parallel yet.")
                    }

                    gradle.taskGraph.whenReady { taskGraph ->
                        val breakTaskPatterns = breakOnTasks.filter(String::isNotBlank).map(Pattern::compile)

                        val graphWithoutMirakle = taskGraph.allTasks.filterNot(Task::isMirakleTask)

                        val breakTasks = graphWithoutMirakle.filter { task ->
                            breakTaskPatterns.any { it.matcher(task.name).find() }
                        }

                        when {
                            breakTasks.isEmpty() -> {
                                execute.args(startParamsCopy.taskNames.minus("mirakle"))
                                graphWithoutMirakle.forEach {
                                    it.enabled = false
                                }
                            }
                            breakTasks.size == 1 -> {
                                val breakTask = breakTasks.first()

                                val tasksForRemoteExecution = graphWithoutMirakle
                                        .takeWhile { it != breakTask }
                                        .onEach { it.enabled = false }

                                execute.args(tasksForRemoteExecution.map(Task::getPath))

                                breakTask.apply {
                                    enabled = true
                                    mustRunAfter(download)
                                    onlyIf { execute.didWork && execute.execResult!!.exitValue == 0 }

                                    if (inputs.files.files.isNotEmpty()) {
                                        val inputsNotInProjectDir = inputs.files.files.filter {
                                            !it.path.startsWith(project.rootDir.path)
                                        }

                                        if (inputsNotInProjectDir.isNotEmpty()) {
                                            println("${breakTask.toString().capitalize()} declares input not from project dir. That is not supported by Mirakle yet. ${inputsNotInProjectDir.joinToString { it.path }}")
                                            throw MirakleException()
                                        }

                                        // Need to include all of the parent directories down to the desired directory
                                        val includeRules = inputs.files.files.map { file ->
                                            val pathToInclude = file.path.substringAfter(project.rootDir.path).drop(1)
                                            val split = pathToInclude.split("/")

                                            (1 until split.size).mapIndexed { _, i ->
                                                split.take(i).joinToString(separator = "/")
                                            }.map {
                                                "--include=$it"
                                            }.let {
                                                val isFile = split.last().contains('.')
                                                if (isFile) {
                                                    it.plus("--include=$pathToInclude")
                                                } else {
                                                    it.plus("--include=$pathToInclude/***")
                                                }
                                            }
                                        }.flatten().toSet()

                                        //val excludeAllRule = "--exclude=/**" // TODO does it make sense to exclude all except the inputs?

                                        download.setArgs(includeRules /*+ excludeAllRule */+ download.args!!.toList())
                                    }
                                }

                                execute.doFirst {
                                    println("Mirakle will break remote execution on $breakTask")
                                }

                                mirakle.dependsOn(breakTask)
                                gradle.logTasks(graphWithoutMirakle - tasksForRemoteExecution)
                            }
                            else -> {
                                println("Task execution graph contains more than 1 task to break on. That is not supported by Mirakle yet. ${breakTasks.joinToString { it.path }}")
                                throw MirakleException()
                            }
                        }
                    }
                } else {
                    gradle.startParameter.apply {
                        if (taskNames.contains("downloadInParallel")) {
                            setTaskNames(listOf("downloadInParallel", "mirakle"))
                        } else {
                            setTaskNames(listOf("mirakle"))
                        }
                        setExcludedTaskNames(emptyList())
                    }
                }

                gradle.supportAndroidStudioAdvancedProfiling(config, upload, execute, download)

                gradle.logTasks(upload, execute, download)
                gradle.logBuild(startTime)
            }
        }
    }
}

class MirakleBreakMode : Mirakle() {
    override fun apply(gradle: Gradle) {
        gradle.startParameter.apply {
            if (!projectProperties.containsKey(BREAK_MODE)) {
                projectProperties = projectProperties.plus(BREAK_MODE to "true")
            }
        }
        super.apply(gradle)
    }
}

open class MirakleExtension {
    var host: String? = null

    var remoteFolder: String = "~/mirakle"

    var excludeLocal = setOf(
            "**/build"
    )

    var excludeRemote = setOf(
            "**/src/"
    )

    var excludeCommon = setOf(
            ".gradle",
            ".idea",
            "**/.git/",
            "**/local.properties",
            "**/mirakle.properties",
            "**/mirakle_local.properties"
    )

    var rsyncToRemoteArgs = setOf(
            "--archive",
            "--delete"
    )

    var rsyncFromRemoteArgs = setOf(
            "--archive",
            "--delete"
    )

    var sshArgs = emptySet<String>()

    var fallback = false

    var downloadInParallel = false
    var downloadInterval = 2000L

    var breakOnTasks = emptySet<String>()

    internal fun buildRsyncToRemoteArgs(): Set<String> =
        rsyncToRemoteArgs + excludeLocal.mapToRsyncExcludeArgs()

    internal fun buildRsyncFromRemoteArgs(): Set<String> =
        rsyncFromRemoteArgs + excludeRemote.mapToRsyncExcludeArgs()

    private fun Set<String>.mapToRsyncExcludeArgs(): Set<String> =
        this.plus(excludeCommon).map { "--exclude=$it" }.toSet()
}

fun startParamsToArgs(params: StartParameter) = with(params) {
    emptyList<String>()
            .plus(taskNames.minus("mirakle"))
            .plus(excludedTaskNames.map { "--exclude-task $it" })
            .plus(buildFile?.let { "-b $it" })
            .plus(booleanParamsToOption.map { (param, option) -> if (param(this)) option else null })
            .plus(negativeBooleanParamsToOption.map { (param, option) -> if (!param(this)) option else null })
            .plus(projectProperties.minus(excludedProjectProperties).flatMap { (key, value) -> listOf("--project-prop", "\"$key=$value\"") })
            .plus(systemPropertiesArgs.flatMap { (key, value) -> listOf("--system-prop", "\"$key=$value\"") })
            .plus(logLevelToOption.firstOrNull { (level, _) -> logLevel == level }?.second)
            .plus(showStacktraceToOption.firstOrNull { (show, _) -> showStacktrace == show }?.second)
            .plus(consoleOutputToOption.firstOrNull { (console, _) -> consoleOutput == console }?.second)
            .filterNotNull()
}

val booleanParamsToOption = listOf(
        StartParameter::isProfile to "--profile",
        StartParameter::isRerunTasks to "--rerun-tasks",
        StartParameter::isRefreshDependencies to "--refresh-dependencies",
        StartParameter::isContinueOnFailure to "--continue",
        StartParameter::isOffline to "--offline",
        StartParameter::isParallelProjectExecutionEnabled to "--parallel",
        StartParameter::isConfigureOnDemand to "--configure-on-demand",
        StartParameter::isBuildScan to "--scan",
        StartParameter::isNoBuildScan to "--no-scan"
)

val negativeBooleanParamsToOption = listOf(
        StartParameter::isBuildProjectDependencies to "--no-rebuild"
)

val excludedProjectProperties = listOf(
        "android.injected.attribution.file.location" // disable Android Studio Build Analyzer collection on remote machine
)

val logLevelToOption = listOf(
        LogLevel.DEBUG to "--debug",
        LogLevel.INFO to "--info",
        LogLevel.WARN to "--warn",
        LogLevel.QUIET to "--quiet"
)

val showStacktraceToOption = listOf(
        ShowStacktrace.ALWAYS to "--stacktrace",
        ShowStacktrace.ALWAYS_FULL to "--full-stacktrace"
)

val consoleOutputToOption = listOf(
        ConsoleOutput.Plain to "--console plain",
        //ConsoleOutput.Auto to "--console auto", //default, no need to pass
        ConsoleOutput.Rich to "--console rich"
)

//since build occurs on a remote machine, console output will contain remote directories
//to let IDE and other tools properly parse the output, mirakle need to replace remote dir by local one
fun modifyOutputStream(target: OutputStream, remoteDir: String, localDir: String): OutputStream {
    val modifier = FastRegexModifier("\\/.*?\\/${remoteDir.replace("~/", "")}", 0, localDir, 0, 1)
    val modifyingWriter = ModifyingWriter(OutputStreamWriter(target), modifier)
    return WriterOutputStream(modifyingWriter)
}

fun getMainframerConfigOrNull(projectDir: File, mirakleConfig: MirakleExtension): MirakleExtension? {
    val mainframerDir = File(projectDir, ".mainframer")
    return mainframerDir.takeIf(File::exists)?.let {
        MirakleExtension().apply {
            val config = mainframerDir.listFiles().firstOrNull { it.name == "config" }
            val commonIgnore = mainframerDir.listFiles().firstOrNull { it.name == "ignore" }
            val localIgnore = mainframerDir.listFiles().firstOrNull { it.name == "localignore" }
            val remoteIgnore = mainframerDir.listFiles().firstOrNull { it.name == "remoteignore" }

            if (config == null) throw MirakleException("Mainframer folder is detected but config is missed.")

            remoteFolder = "~/mainframer"
            excludeCommon = emptySet()
            excludeLocal = emptySet()
            excludeRemote = emptySet()

            Properties().apply {
                load(config.inputStream())

                host = getProperty("remote_machine")
                rsyncToRemoteArgs += "--compress-level=${getProperty("local_compression_level") ?: "1"}"
                rsyncFromRemoteArgs += "--compress-level=${getProperty("remote_compression_level") ?: "1"}"
            }

            if (commonIgnore != null && commonIgnore.exists()) {
                rsyncToRemoteArgs += "--exclude-from=${commonIgnore.path}"
                rsyncFromRemoteArgs += "--exclude-from=${commonIgnore.path}"
            }

            if (localIgnore != null && localIgnore.exists()) {
                rsyncToRemoteArgs += "--exclude-from=${localIgnore.path}"
            }

            if (remoteIgnore != null && remoteIgnore.exists()) {
                rsyncFromRemoteArgs += "--exclude-from=${remoteIgnore.path}"
            }

            // Mainframer doesn't have these config params, use Mirakle's parameters instead
            fallback = mirakleConfig.fallback
            downloadInParallel = mirakleConfig.downloadInParallel
            downloadInterval = mirakleConfig.downloadInterval
            sshArgs = mirakleConfig.sshArgs
            breakOnTasks = mirakleConfig.breakOnTasks
        }
    }
}

const val BUILD_ON_REMOTE = "mirakle.build.on.remote"
const val FALLBACK = "mirakle.build.fallback"
const val BREAK_MODE = "mirakle.break.mode"
const val BREAK_TASK = "mirakle.break.task"

//TODO test
fun Gradle.supportAndroidStudioAdvancedProfiling(config: MirakleExtension, upload: Exec, execute: Exec, download: Exec) {
    if (startParameter.projectProperties.containsKey("android.advanced.profiling.transforms")) {
        println("Android Studio advanced profilling enabled. Profiler files will be uploaded to remote project dir.")

        val studioProfilerJar = File(startParameter.projectProperties["android.advanced.profiling.transforms"])
        val studioProfilerProp = File(startParameter.systemPropertiesArgs["android.profiler.properties"])

        val jarInRootProject = gradle.rootProject.file(studioProfilerJar.name)
        val propInRootProject = gradle.rootProject.file(studioProfilerProp.name)

        if (jarInRootProject.exists()) jarInRootProject.delete()
        if (propInRootProject.exists()) propInRootProject.delete()

        upload.doFirst {
            Files.copy(studioProfilerJar.toPath(), jarInRootProject.toPath())
            Files.copy(studioProfilerProp.toPath(), propInRootProject.toPath())
        }

        upload.doLast {
            jarInRootProject.delete()
            propInRootProject.delete()
        }

        execute.doFirst {
            val profilerJarPathArg = "android.advanced.profiling.transforms=${studioProfilerJar.toPath()}"
            val profilerPropPathArg = "android.profiler.properties=${studioProfilerProp.toPath()}"

            val rootProfilerJarArg = "android.advanced.profiling.transforms=${config.remoteFolder}/${gradle.rootProject.name}/${jarInRootProject.name}"
            val rootProfilerPropArg = "android.profiler.properties=${config.remoteFolder}/${gradle.rootProject.name}/${propInRootProject.name}"

            execute.args = execute.args!!.apply {
                set(indexOf(profilerJarPathArg), rootProfilerJarArg)
                set(indexOf(profilerPropPathArg), rootProfilerPropArg)
            }
        }

        download.doFirst {
            download.args("--exclude=${jarInRootProject.name}")
            download.args("--exclude=${propInRootProject.name}")
        }
    }
}

class DownloadInParallelWorker @Inject constructor(val downloadInterval: Long) : Runnable {
    override fun run() {
        val mustInterrupt = AtomicBoolean()

        gradle.addListener(object : TaskExecutionListener {
            override fun afterExecute(task: Task, state: TaskState?) {
                if (task.name == "executeOnRemote") {
                    gradle.removeListener(this)
                    mustInterrupt.set(true)
                }
            }

            override fun beforeExecute(task: Task) {}
        })

        while (!mustInterrupt.get()) {
            try {
                Thread.sleep(downloadInterval)
                downloadExecAction.execute()
            } catch (e: ExecException) {
                println("Parallel download failed with exception $e")
            }
        }
    }

    companion object {
        lateinit var gradle: Gradle
        lateinit var downloadExecAction: ExecAction
    }
}

/*
* If an option is configured in multiple locations, the first one wins:
*   1. mirakle.properties + mirakle_local.properties
*   2. args from CLI
*   3. GRADLE_USER_HOME/gradle.properties
* */
fun mergeStartParamsWithProperties(startParameter: StartParameter): StartParameter {
    fun loadPropertiesIfExist(file: File) = file.takeIf(File::exists)?.let {
        Properties().apply { load(it.inputStream()) }.toMap() as Map<String, String>
    } ?: emptyMap()

    fun addPropertiesToStartParams(properties: Map<String, String>, startParameter: StartParameter) {
        properties.onEach { (key, value) ->
            if (key.startsWith("systemProp.")) {
                startParameter.systemPropertiesArgs[key.removePrefix("systemProp.")] = value
            } else {
                startParameter.projectProperties[key] = value
            }
        }
    }

    val mirakleProperties = loadPropertiesIfExist(File(startParameter.currentDir, "mirakle.properties"))
    val mirakleLocalProperties = loadPropertiesIfExist(File(startParameter.currentDir, "mirakle_local.properties"))
    val gradleHomeDirProperties = loadPropertiesIfExist(File(startParameter.gradleUserHomeDir, "gradle.properties"))

    return startParameter.copy().apply {
        projectProperties = mutableMapOf()
        systemPropertiesArgs = mutableMapOf()

        addPropertiesToStartParams(gradleHomeDirProperties, this)

        addPropertiesToStartParams(startParameter.projectProperties, this)
        addPropertiesToStartParams(startParameter.systemPropertiesArgs.mapKeys { (key, _) -> "systemProp.$key" }, this)

        addPropertiesToStartParams(mirakleProperties + mirakleLocalProperties, this)
    }
}