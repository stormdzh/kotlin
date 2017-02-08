/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.daemon

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.common.repl.*
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.repl.GenericReplCompiler
import org.jetbrains.kotlin.cli.jvm.repl.GenericReplCompilerState
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.daemon.common.CompileService
import org.jetbrains.kotlin.daemon.common.RemoteOperationsTracer
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import org.jetbrains.kotlin.script.KotlinScriptDefinitionFromAnnotatedTemplate
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

open class KotlinJvmReplService(
        disposable: Disposable,
        val portForServers: Int,
        templateClasspath: List<File>,
        templateClassName: String,
        protected val fallbackScriptArgs: ScriptArgsWithTypes?,
        protected val messageCollector: MessageCollector,
        @Deprecated("drop it")
        protected val operationsTracer: RemoteOperationsTracer?
) : ReplCompileAction, ReplAtomicEvalAction, ReplCheckAction, CreateReplStageStateAction {

    protected val configuration = CompilerConfiguration().apply {
        addJvmClasspathRoots(PathUtil.getJdkClassesRoots())
        addJvmClasspathRoots(PathUtil.getKotlinPathsForCompiler().let { listOf(it.runtimePath, it.reflectPath, it.scriptRuntimePath) })
        addJvmClasspathRoots(templateClasspath)
        put(CommonConfigurationKeys.MODULE_NAME, "kotlin-script")
    }

    protected fun makeScriptDefinition(templateClasspath: List<File>, templateClassName: String): KotlinScriptDefinition? {
        val classloader = URLClassLoader(templateClasspath.map { it.toURI().toURL() }.toTypedArray(), this.javaClass.classLoader)

        try {
            val cls = classloader.loadClass(templateClassName)
            val def = KotlinScriptDefinitionFromAnnotatedTemplate(cls.kotlin, null, null, emptyMap())
            messageCollector.report(
                    CompilerMessageSeverity.INFO,
                    "New script definition $templateClassName: files pattern = \"${def.scriptFilePattern}\", resolver = ${def.resolver?.javaClass?.name}",
                    CompilerMessageLocation.NO_LOCATION
            )
            return def
        }
        catch (ex: ClassNotFoundException) {
            messageCollector.report(
                    CompilerMessageSeverity.ERROR, "Cannot find script definition template class $templateClassName", CompilerMessageLocation.NO_LOCATION
            )
        }
        catch (ex: Exception) {
            messageCollector.report(
                    CompilerMessageSeverity.ERROR, "Error processing script definition template $templateClassName: ${ex.message}", CompilerMessageLocation.NO_LOCATION
            )
        }
        return null
    }

    private val scriptDef = makeScriptDefinition(templateClasspath, templateClassName)

    private val replCompiler: ReplCompiler? by lazy {
        if (scriptDef == null) null
        else GenericReplCompiler(disposable, scriptDef, configuration, messageCollector)
    }

    private val replEvaluator: ReplFullEvaluator? by lazy {
        replCompiler?.let { compiler ->
            GenericReplCompilingEvaluator(compiler, configuration.jvmClasspathRoots, null, fallbackScriptArgs, ReplRepeatingMode.NONE)
        }
    }

    protected val statesLock = ReentrantReadWriteLock()
    // TODO: consider using values here for session cleanup
    protected val states = WeakHashMap<RemoteReplStateFacadeServer, Boolean>() // used as (missing) WeakHashSet
    protected val stateIdCounter = AtomicInteger()
    @Deprecated("remove after removal state-less check/compile/eval methods")
    protected val defaultStateFacade: RemoteReplStateFacadeServer by lazy { createRemoteState() }

    override fun createState(lock: ReentrantReadWriteLock): IReplStageState<*> =
            replCompiler?.createState(lock) ?: throw IllegalStateException("repl compiler is not initialized properly")

    override fun check(state: IReplStageState<*>, codeLine: ReplCodeLine): ReplCheckResult {
        operationsTracer?.before("check")
        try {
            return replCompiler?.check(state, codeLine)
                   ?: ReplCheckResult.Error("Initialization error", CompilerMessageLocation.NO_LOCATION)
        }
        finally {
            operationsTracer?.after("check")
        }
    }

    override fun compile(state: IReplStageState<*>, codeLine: ReplCodeLine): ReplCompileResult {
        operationsTracer?.before("compile")
        try {
            return replCompiler?.compile(state, codeLine)
                   ?: ReplCompileResult.Error("Initialization error", CompilerMessageLocation.NO_LOCATION)
        }
        finally {
            operationsTracer?.after("compile")
        }
    }

    @Deprecated("eval is not supported on daemon")
    override fun compileAndEval(state: IReplStageState<*>, codeLine: ReplCodeLine, scriptArgs: ScriptArgsWithTypes?, invokeWrapper: InvokeWrapper?): ReplEvalResult {
        operationsTracer?.before("eval")
        try {
            return replEvaluator?.compileAndEval(state, codeLine, scriptArgs ?: fallbackScriptArgs, invokeWrapper)
                   ?: ReplEvalResult.Error.Runtime("Initialization error")
        }
        finally {
            operationsTracer?.after("eval")
        }
    }

    @Deprecated("Use check(state, line) instead")
    fun check(codeLine: ReplCodeLine): ReplCheckResult = check(defaultStateFacade.state, codeLine)

    @Deprecated("Use compile(state, line) instead")
    fun compile(codeLine: ReplCodeLine, verifyHistory: List<ReplCodeLine>?): ReplCompileResult = compile(defaultStateFacade.state, codeLine)

    @Deprecated("eval is not supported on daemon")
    fun compileAndEval(codeLine: ReplCodeLine, verifyHistory: List<ReplCodeLine>?): ReplEvalResult = ReplEvalResult.Error.Runtime("Eval is not supported on daemon")

    fun createRemoteState(port: Int = portForServers): RemoteReplStateFacadeServer = statesLock.write {
        val id = getValidId(stateIdCounter) { id -> states.none { it.key.getId() == id} }
        val stateFacade = RemoteReplStateFacadeServer(id, createState().asState(GenericReplCompilerState::class.java), port)
        states.put(stateFacade, true)
        stateFacade
    }

    fun<R> withValidReplState(stateId: Int, body: (IReplStageState<*>) -> R): CompileService.CallResult<R> = statesLock.read {
        states.keys.firstOrNull { it.getId() == stateId }?.let {
            CompileService.CallResult.Good(body(it.state))
        }
        ?: CompileService.CallResult.Error("No REPL state with id $stateId found")
    }
}

internal class KeepFirstErrorMessageCollector(compilerMessagesStream: PrintStream) : MessageCollector {

    private val innerCollector = PrintingMessageCollector(compilerMessagesStream, MessageRenderer.WITHOUT_PATHS, false)

    internal var firstErrorMessage: String? = null
    internal var firstErrorLocation: CompilerMessageLocation? = null

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation) {
        if (firstErrorMessage == null && severity.isError) {
            firstErrorMessage = message
            firstErrorLocation = location
        }
        innerCollector.report(severity, message, location)
    }

    override fun hasErrors(): Boolean = innerCollector.hasErrors()
    override fun clear() {
        innerCollector.clear()
    }
}

internal val internalRng = Random()

inline internal fun getValidId(counter: AtomicInteger, check: (Int) -> Boolean): Int {
    // fighting hypothetical integer wrapping
    var newId = counter.incrementAndGet()
    var attemptsLeft = 100
    while (!check(newId)) {
        attemptsLeft -= 1
        if (attemptsLeft <= 0)
            throw IllegalStateException("Invalid state or algorithm error")
        // assuming wrap, jumping to random number to reduce probability of further clashes
        newId = counter.addAndGet(internalRng.nextInt())
    }
    return newId
}