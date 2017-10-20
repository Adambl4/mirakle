import com.googlecode.streamflyer.core.ModifyingWriter
import com.googlecode.streamflyer.regex.RegexModifier
import com.googlecode.streamflyer.regex.addons.tokens.Token
import com.googlecode.streamflyer.regex.addons.tokens.TokenProcessor
import com.googlecode.streamflyer.regex.addons.tokens.TokensMatcher
import com.instamotor.BuildConfig
import org.apache.commons.io.output.WriterOutputStream
import org.gradle.StartParameter
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.configuration.ConsoleOutput
import org.gradle.api.logging.configuration.ShowStacktrace
import org.gradle.api.tasks.Exec
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Properties

class Mirakle : Plugin<Gradle> {
    override fun apply(gradle: Gradle) {
        gradle.rootProject { it.extensions.create("mirakle", MirakleExtension::class.java) }

        if (gradle.startParameter.taskNames.isEmpty()) return
        if (gradle.startParameter.projectProperties.containsKey(BUILD_ON_REMOTE)) return
        if (gradle.startParameter.excludedTaskNames.remove("mirakle")) return
        if (gradle.startParameter.isDryRun) return

        val startTime = System.currentTimeMillis()

        gradle.assertNonSupportedFeatures()

        val startParamsCopy = gradle.startParameter.newInstance()

        gradle.startParameter.apply {
            setTaskNames(listOf("mirakle"))
            setExcludedTaskNames(emptyList())
            useEmptySettings()
            buildFile = File(startParamsCopy.currentDir, "mirakle.gradle").takeIf(File::exists)
                    ?: //a way to make Gradle not evaluate project's default build.gradle file on local machine
                    File(startParamsCopy.currentDir, "mirakle_build_file_stub").also { stub ->
                        stub.createNewFile()
                        gradle.rootProject { it.afterEvaluate { stub.delete() } }
                    }
        }

        gradle.rootProject { project ->
            project.afterEvaluate {
                val config = getMainframerConfigOrNull(project.rootDir)?.also {
                    println("Mainframer config is applied, Mirakle config is ignored.")
                } ?: project.extensions.getByType(MirakleExtension::class.java)

                if (config.host == null) throw MirakleException("Mirakle host is not defined.")

                println("Here's Mirakle ${BuildConfig.VERSION}. All tasks will be executed on ${config.host}.")

                with(project) {
                    val upload = task<Exec>("uploadToRemote") {
                        setCommandLine("rsync")
                        args(
                                rootDir,
                                "${config.host}:${config.remoteFolder}",
                                "--rsh",
                                "ssh ${config.sshArgs.joinToString(separator = " ")}",
                                "--exclude=mirakle.gradle"
                        )
                        args(config.rsyncToRemoteArgs)
                    }

                    val execute = task<Exec>("executeOnRemote") {
                        setCommandLine("ssh")
                        args(config.sshArgs)
                        args(
                                config.host,
                                "${config.remoteFolder}/${project.name}/gradlew",
                                "-P$BUILD_ON_REMOTE=true",
                                "-p ${config.remoteFolder}/${project.name}"
                        )
                        args(startParamsToArgs(startParamsCopy))

                        isIgnoreExitValue = true

                        standardOutput = modifyOutputStream(
                                standardOutput,
                                "${config.remoteFolder}/${project.name}",
                                project.rootDir.path
                        )
                        errorOutput = modifyOutputStream(
                                errorOutput,
                                "${config.remoteFolder}/${project.name}",
                                project.rootDir.path
                        )
                    }.mustRunAfter(upload)

                    val download = task<Exec>("downloadFromRemote") {
                        setCommandLine("rsync")
                        args(
                                "${config.host}:${config.remoteFolder}/${project.name}/",
                                "./",
                                "--rsh",
                                "ssh ${config.sshArgs.joinToString(separator = " ")}",
                                "--exclude=mirakle.gradle"
                        )
                        args(config.rsyncFromRemoteArgs)
                    }.mustRunAfter(execute)

                    val mirakle = task("mirakle").dependsOn(upload, execute, download)

                    mirakle.doLast {
                        execute as Exec
                        execute.execResult.assertNormalExitValue()
                    }

                    gradle.logTasks(upload, execute, download)
                    gradle.logBuild(startTime)
                }
            }
        }
    }
}

open class MirakleExtension {
    var host: String? = null
    var remoteFolder: String = "mirakle"

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
            "**/local.properties"
    )

    var rsyncToRemoteArgs = setOf(
            "--archive",
            "--delete"
    )
        get() = field.plus(excludeLocal.plus(excludeCommon).map { "--exclude=$it" })

    var rsyncFromRemoteArgs = setOf(
            "--archive",
            "--delete"
    )
        get() = field.plus(excludeRemote.plus(excludeCommon).map { "--exclude=$it" })

    var sshArgs = emptySet<String>()
}

fun startParamsToArgs(params: StartParameter) = with(params) {
    emptyList<String>()
            .plus(taskNames)
            .plus(excludedTaskNames.map { "--exclude-task $it" })
            .plus(buildFile?.let { "-b $it" })
            .plus(booleanParamsToOption.map { (param, option) -> if (param(this)) option else null })
            .plus(negativeBooleanParamsToOption.map { (param, option) -> if (!param(this)) option else null })
            .plus(projectProperties.flatMap { (key, value) -> listOf("--project-prop", "$key=$value") })
            .plus(systemPropertiesArgs.flatMap { (key, value) -> listOf("--system-prop", "$key=$value") })
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
        StartParameter::isRecompileScripts to "--recompile-scripts",
        StartParameter::isParallelProjectExecutionEnabled to "--parallel",
        StartParameter::isConfigureOnDemand to "--configure-on-demand"
)

val negativeBooleanParamsToOption = listOf(
        StartParameter::isSearchUpwards to "--no-search-upward",
        StartParameter::isBuildProjectDependencies to "--no-rebuild"
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
        ConsoleOutput.Auto to "--console auto",
        ConsoleOutput.Rich to "--console rich"
)

//since build occurs on a remote machine, console output will contain remote directories
//to let IDE and other tools properly parse the output, mirakle need to replace remote dir by local one
fun modifyOutputStream(target: OutputStream, remoteDir: String, localDir: String): OutputStream {
    val tokenList = listOf(
            Token("1", "\\/.*?\\/$remoteDir", localDir)
            //Token("2", "(?<=\\n)BUILD FAILED", "REMOTE BUILD FAILED"),
            //Token("3", "(?<=\\n)BUILD SUCCESSFUL", "REMOTE BUILD SUCCESSFUL")
    )
    val modifier = RegexModifier(TokensMatcher(tokenList), TokenProcessor(tokenList), 0, 1)
    val modifyingWriter = ModifyingWriter(OutputStreamWriter(target), modifier)
    return WriterOutputStream(modifyingWriter)
}

const val BUILD_ON_REMOTE = "mirakle.build.on.remote"

fun getMainframerConfigOrNull(projectDir: File): MirakleExtension? {
    val mainframerDir = File(projectDir, ".mainframer")
    return if (mainframerDir.exists()) {
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
        }
    } else null
}