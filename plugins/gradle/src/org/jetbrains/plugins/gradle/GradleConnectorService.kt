// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle

import com.intellij.concurrency.ConcurrentCollectionFactory
import com.intellij.execution.target.value.TargetValue
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemExecutionAware.Companion.getEnvironmentConfigurationProvider
import com.intellij.openapi.externalSystem.service.execution.TargetEnvironmentConfigurationProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.task.RunConfigurationTaskState
import org.gradle.initialization.BuildCancellationToken
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.internal.consumer.CancellationTokenInternal
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.gradle.util.GradleVersion
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.concurrency.resolvedPromise
import org.jetbrains.plugins.gradle.execution.target.TargetGradleConnector
import org.jetbrains.plugins.gradle.execution.target.maybeConvertToRemote
import org.jetbrains.plugins.gradle.internal.daemon.GradleDaemonServices
import org.jetbrains.plugins.gradle.service.project.DistributionFactoryExt
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Function

/**
 * @author Vladislav.Soroka
 */
@ApiStatus.Internal
@Service(Level.PROJECT)
internal class GradleConnectorService(@Suppress("UNUSED_PARAMETER") project: Project) : Disposable {
  private val connectorsMap = ConcurrentHashMap<String, GradleProjectConnection>()
  private val cancellationTokens = ConcurrentCollectionFactory.createConcurrentSet<BuildCancellationToken>()

  @Volatile
  private var shutdownStarted = false

  init {
    Runtime.getRuntime().addShutdownHook(object : Thread("Shutdown hook to get to know whether shutdown is started") {
      override fun start() {
        shutdownStarted = true
        super.start()
      }
    })
  }

  override fun dispose() {
    if (ApplicationManager.getApplication().isUnitTestMode) return
    disconnectGradleConnections()
    stopIdleDaemonsOfOldVersions()
  }

  private fun stopIdleDaemonsOfOldVersions() {
    if (DISABLE_STOP_OLD_IDLE_DAEMONS) return
    try {
      if (ProjectUtil.getOpenProjects().isEmpty()) {
        val gradleVersion_6_5 = GradleVersion.version("6.5")
        val idleDaemons = GradleDaemonServices.getDaemonsStatus().filter {
          it.status.toLowerCase() == "idle" &&
          GradleVersion.version(it.version) < gradleVersion_6_5
        }
        if (idleDaemons.isNotEmpty()) {
          GradleDaemonServices.stopDaemons(idleDaemons)
        }
      }
    }
    catch (e: Exception) {
      LOG.warn("Failed to stop Gradle daemons during project close", e)
    }
  }

  private fun disconnectGradleConnections() {
    if (!shutdownStarted) {
      // do not call Gradle connector disconnect API when IDE app exit is called during VM shutdown
      // otherwise Gradle call might lead to adding a new shutdown hook, but it's prohibited when shutdown is already started
      connectorsMap.values.forEach(GradleProjectConnection::disconnect)
    }
    else {
      cancellationTokens.forEach {
        if (!it.isCancellationRequested) {
          try {
            it.cancel()
          }
          catch (t : Throwable) {
            LOG.warn("Failed to cancel build", t)
          }
        }
      }
      GradleDaemonServices.gracefulStopDaemons()
    }
    cancellationTokens.clear()
    connectorsMap.clear()
  }

  private fun getConnection(connectorParams: ConnectorParams,
                            taskId: ExternalSystemTaskId?,
                            listener: ExternalSystemTaskNotificationListener?): ProjectConnection {
    return connectorsMap.compute(connectorParams.projectPath) { _, conn ->
      if (conn != null) {
        if (canBeReused(conn, connectorParams)) return@compute conn
        else {
          // close obsolete connection, can not disconnect the connector here - it may cause build cancel for the new connection operations
          val unwrappedConnection = conn.connection as NonClosableConnection
          // do not block the current thread on "ProjectConnection.close" for existing running Gradle operation
          ApplicationManager.getApplication().executeOnPooledThread {
            unwrappedConnection.delegate.close()
          }
        }
      }
      val newConnector = createConnector(connectorParams, taskId, listener)
      val newConnection = newConnector.connect()
      check(newConnection != null) {
        "Can't create connection to the target project via gradle tooling api. Project path: '${connectorParams.projectPath}'"
      }

      val wrappedConnection = NonClosableConnection(newConnection)
      return@compute GradleProjectConnection(connectorParams, newConnector, wrappedConnection)
    }!!.connection
  }

  private fun canBeReused(projectConnection: GradleProjectConnection, connectorParams: ConnectorParams): Boolean {
    // don't cache connections for not-yet-installed gradle versions
    if (connectorParams.gradleHome == null) return false
    // don't cache TargetProjectConnection as it doesn't support it yet
    if (projectConnection.connector is TargetGradleConnector) return false
    return connectorParams == projectConnection.params
  }

  private class GradleProjectConnection(val params: ConnectorParams, val connector: GradleConnector, val connection: ProjectConnection) {
    fun disconnect() {
      try {
        connector.disconnect()
      }
      catch (e: Exception) {
        LOG.warn("Failed to disconnect Gradle connector during project close. Project path: '${params.projectPath}'", e)
      }
    }
  }

  private class NonClosableConnection(val delegate: ProjectConnection) : ProjectConnection by delegate {
    override fun close() {
      throw IllegalStateException("This connection should not be closed explicitly.")
    }
  }

  private data class ConnectorParams(
    val projectPath: String,
    val serviceDirectory: String?,
    val distributionType: DistributionType?,
    val gradleHome: String?,
    val javaHome: String?,
    val wrapperPropertyFile: String?,
    val verboseProcessing: Boolean?,
    val ttlMs: Int?,
    val environmentConfigurationProvider: TargetEnvironmentConfigurationProvider?,
    val taskState: RunConfigurationTaskState?
  )

  companion object {
    private val LOG = logger<GradleConnectorService>()

    /** disable stop IDLE Gradle daemons on IDE project close. Applicable for Gradle versions w/o disconnect support (older than 6.5). */
    private val DISABLE_STOP_OLD_IDLE_DAEMONS = java.lang.Boolean.getBoolean("idea.gradle.disableStopIdleDaemonsOnProjectClose")

    /** some longer running tests require a longer idle time */
    private val USE_PRODUCTION_TTL_FOR_TESTS =
      java.lang.Boolean.getBoolean("gradle.connector.useExternalSystemRemoteProcessIdleTtlForTests")

    @JvmStatic
    private fun getInstance(projectPath: String, taskId: ExternalSystemTaskId?): GradleConnectorService? {
      var project = taskId?.findProject()
      if (project == null) {
        for (openProject in ProjectUtil.getOpenProjects()) {
          val projectBasePath = openProject.basePath ?: continue
          if (FileUtil.isAncestor(projectBasePath, projectPath, false)) {
            project = openProject
            break
          }
        }
      }
      return project?.getService(GradleConnectorService::class.java)
    }

    @JvmStatic
    fun <R : Any?> withGradleConnection(
      projectPath: String,
      taskId: ExternalSystemTaskId?,
      executionSettings: GradleExecutionSettings? = null,
      listener: ExternalSystemTaskNotificationListener? = null,
      cancellationToken: CancellationToken? = null,
      function: Function<ProjectConnection, R>
    ): R {
      val targetEnvironmentConfigurationProvider = executionSettings?.getEnvironmentConfigurationProvider()
      val connectionParams = ConnectorParams(
        projectPath,
        executionSettings?.serviceDirectory,
        executionSettings?.distributionType,
        executionSettings?.gradleHome,
        executionSettings?.javaHome,
        executionSettings?.wrapperPropertyFile,
        executionSettings?.isVerboseProcessing,
        executionSettings?.remoteProcessIdleTtlInMs?.toInt(),
        targetEnvironmentConfigurationProvider,
        executionSettings?.getUserData(RunConfigurationTaskState.KEY)
      )
      val connectionService = getInstance(projectPath, taskId)
      if (connectionService != null) {
        val buildCancellationToken = (cancellationToken as? CancellationTokenInternal)?.token
        buildCancellationToken?.let { connectionService.cancellationTokens.add(it) }
        try {
          val connection = connectionService.getConnection(connectionParams, taskId, listener)
          return if (connection is NonClosableConnection) {
            function.apply(connection)
          }
          else {
            connection.use(function::apply)
          }
        }
        finally {
          buildCancellationToken?.let { connectionService.cancellationTokens.remove(it) }
        }
      }
      else {
        val newConnector = createConnector(connectionParams, taskId, listener)
        val connection = newConnector.connect()
        return connection.use(function::apply)
      }
    }

    private fun createConnector(connectorParams: ConnectorParams,
                                taskId: ExternalSystemTaskId?,
                                listener: ExternalSystemTaskNotificationListener?): GradleConnector {
      val connector: GradleConnector
      if (connectorParams.environmentConfigurationProvider != null) {
        connector = TargetGradleConnector(connectorParams.environmentConfigurationProvider, taskId, listener, connectorParams.taskState)
      }
      else {
        connector = GradleConnector.newConnector()
      }
      val projectDir = File(connectorParams.projectPath)
      // GradleExecutionSettings.serviceDirectory contains the path in IDE "host" system file format
      val localPathToGradleUserHome = connectorParams.serviceDirectory

      if (connectorParams.distributionType == DistributionType.LOCAL) {
        // GradleExecutionSettings.gradleHome contains the path in IDE "host" system file format for compatibility reasons
        val localPathToGradleHome = connectorParams.gradleHome
        if (localPathToGradleHome != null) {
          if (connector is TargetGradleConnector) {
            val targetPathMapper = connectorParams.environmentConfigurationProvider?.pathMapper
            val targetGradleHomePath = targetPathMapper.maybeConvertToRemote(localPathToGradleHome)
            // can not use system dependant java.io.File to pass the value to target Gradle runner i.e. GradleConnector#useInstallation(java.io.File)
            connector.useInstallation(TargetValue.create(localPathToGradleHome, resolvedPromise(targetGradleHomePath)))
          }
          else {
            connector.useInstallation(File(localPathToGradleHome))
          }
        }
      }
      else if (connectorParams.distributionType == DistributionType.WRAPPED) {
        if (connectorParams.wrapperPropertyFile != null) {
          val gradleUserHomeLocalFile = localPathToGradleUserHome?.let { File(localPathToGradleUserHome) }
          DistributionFactoryExt.setWrappedDistribution(connector, connectorParams.wrapperPropertyFile)
        }
      }

      // Setup Grade user home if necessary
      if (localPathToGradleUserHome != null) {
        if (connector is TargetGradleConnector) {
          val targetPathMapper = connectorParams.environmentConfigurationProvider?.pathMapper
          val targetGradleUserHomePath = targetPathMapper.maybeConvertToRemote(localPathToGradleUserHome)
          // can not use system dependant java.io.File to pass the value to target Gradle runner i.e. GradleConnector#useInstallation(java.io.File)
          connector.useGradleUserHomeDir(TargetValue.create(localPathToGradleUserHome, resolvedPromise(targetGradleUserHomePath)))
        }
        else {
          connector.useGradleUserHomeDir(File(localPathToGradleUserHome))
        }
      }
      // Setup logging if necessary
      if (connectorParams.verboseProcessing == true && connector is DefaultGradleConnector) {
        connector.setVerboseLogging(true)
      }
      // do not spawn gradle daemons during test execution
      val app = ApplicationManager.getApplication()
      val ttl = if (!USE_PRODUCTION_TTL_FOR_TESTS && app != null && app.isUnitTestMode) 10000 else connectorParams.ttlMs ?: -1
      if (ttl > 0 && connector is DefaultGradleConnector) {
        connector.daemonMaxIdleTime(ttl, TimeUnit.MILLISECONDS)
      }

      connector.forProjectDirectory(projectDir)
      return connector
    }
  }
}