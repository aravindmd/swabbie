/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie

import com.google.common.collect.Lists
import com.netflix.spectator.api.BasicTag
import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.patterns.PolledMeter
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.kork.web.exceptions.NotFoundException
import com.netflix.spinnaker.swabbie.events.Action
import com.netflix.spinnaker.swabbie.events.DeleteResourceEvent
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.events.OptOutResourceEvent
import com.netflix.spinnaker.swabbie.events.UnMarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.shouldExclude
import com.netflix.spinnaker.swabbie.model.AlwaysCleanRule
import com.netflix.spinnaker.swabbie.model.MarkedResource
import com.netflix.spinnaker.swabbie.model.OnDemandMarkData
import com.netflix.spinnaker.swabbie.model.Resource
import com.netflix.spinnaker.swabbie.model.ResourceEvaluation
import com.netflix.spinnaker.swabbie.model.ResourceState
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Status
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.model.WorkConfiguration
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.notifications.NotificationTask
import com.netflix.spinnaker.swabbie.notifications.Notifier
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.DAYS
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

abstract class AbstractResourceTypeHandler<T : Resource>(
  private val registry: Registry,
  val clock: Clock,
  private val rules: List<Rule<T>>,
  private val resourceRepository: ResourceTrackingRepository,
  private val resourceStateRepository: ResourceStateRepository,
  private val exclusionPolicies: List<ResourceExclusionPolicy>,
  private val ownerResolver: OwnerResolver<T>,
  private val notifier: Notifier,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val resourceUseTrackingRepository: ResourceUseTrackingRepository,
  private val swabbieProperties: SwabbieProperties,
  private val dynamicConfigService: DynamicConfigService,
  private val notificationQueue: NotificationQueue
) : ResourceTypeHandler<T>, MetricsSupport(registry) {
  protected val log: Logger = LoggerFactory.getLogger(javaClass)
  private val resourceOwnerField: String = "swabbieResourceOwner"
  private val numberOfCandidatesId: Id = registry.createId("swabbie.candidates.processed")

  /**
   * deletes a marked resource. Each handler must implement this function.
   * Deleted resources are produced via [DeleteResourceEvent]
   * for additional processing
   */
  abstract fun deleteResources(markedResources: List<MarkedResource>, workConfiguration: WorkConfiguration)

  /**
   * finds & tracks cleanup candidates
   */
  override fun mark(workConfiguration: WorkConfiguration) {
    doMark(workConfiguration)
  }

  /**
   * deletes violating resources
   */
  override fun delete(workConfiguration: WorkConfiguration) {
    doDelete(workConfiguration)
  }

  /**
   * Notifies for everything in resourceRepository.getMarkedResources() that doesn't have notificationInfo
   */
  override fun notify(workConfiguration: WorkConfiguration) {
    if (workConfiguration.dryRun) {
      log.info("Notification not enabled in dryRun for {}. Skipping...", workConfiguration.toLog())
      return
    }

    val markedResources = workConfiguration.getMarkedResourcesToNotify()
    if (markedResources.isEmpty()) {
      log.info("Nothing to notify for {}. Skipping...", workConfiguration)
      return
    }

    log.info("Processing ${markedResources.size} for notification in ${javaClass.simpleName}")
    if (workConfiguration.notificationConfiguration.enabled) {
      // Notifications are routinely batched and sent by [com.netflix.spinnaker.swabbie.notifications.NotificationSender]
      notificationQueue.add(workConfiguration)
    } else {
      // Do not send notifications for these resources. They are updated with a new deletion timestamp to offset
      // the time elapsed since they were marked.
      markedResources
        .filterExcludedAndOptedOut(workConfiguration)
        .forEach { markedResource ->
          markedResource
            .withNotificationInfo()
            .withAdditionalTimeForDeletion(
              Instant.ofEpochMilli(markedResource.markTs!!).until(Instant.now(clock), ChronoUnit.MILLIS))
            .also {
              resourceRepository.upsert(markedResource)
            }
        }
    }
  }

  /**
   * Adds a new notification task to the [NotificationQueue]
   * @see [com.netflix.spinnaker.swabbie.notifications.NotificationSender]
   */
  private fun NotificationQueue.add(workConfiguration: WorkConfiguration) {
    add(NotificationTask(
      namespace = workConfiguration.namespace,
      resourceType = workConfiguration.resourceType
    ))
  }

  /**
   * This find the resource, and then opts it out even if it was not marked.
   * This function is used when a caller makes an opt out request through the controller
   *  and the resource has not already been marked.
   */
  override fun optOut(resourceId: String, workConfiguration: WorkConfiguration) {
    val resource = getCandidate(resourceId, "", workConfiguration)
      ?: return
    log.info("Opting out resource $resourceId in namespace ${workConfiguration.namespace}")
    val resourceWithOwner = listOf(resource).withResolvedOwners(workConfiguration).first()
    val newMarkedResource = MarkedResource(
      resource = resourceWithOwner,
      summaries = emptyList(),
      namespace = workConfiguration.namespace,
      resourceOwner = resourceWithOwner.details[resourceOwnerField] as String,
      projectedDeletionStamp = deletionTimestamp(workConfiguration),
      lastSeenInfo = resourceUseTrackingRepository.getLastSeenInfo(resourceWithOwner.resourceId)
    )

    val status = Status(Action.OPTOUT.name, clock.instant().toEpochMilli())
    val state = ResourceState(
      markedResource = newMarkedResource,
      deleted = false,
      optedOut = true,
      statuses = mutableListOf(status),
      currentStatus = status
    )

    // opt out here to ensure there is never a time when this resource is not opted out.
    resourceStateRepository.upsert(state)
    resourceRepository.upsert(newMarkedResource)

    // this will trigger the actual opt out action
    applicationEventPublisher.publishEvent(OptOutResourceEvent(newMarkedResource, workConfiguration))
  }

  override fun markResource(resourceId: String, onDemandMarkData: OnDemandMarkData, workConfiguration: WorkConfiguration) {
    val resource = getCandidate(resourceId, "", workConfiguration)
      ?: throw NotFoundException("Resource $resourceId not found in namespace ${workConfiguration.namespace}")

    val markedResource = MarkedResource(
      resource = resource,
      summaries = listOf(Summary("Resource marked via the api", AlwaysCleanRule::class.java.simpleName)),
      namespace = workConfiguration.namespace,
      resourceOwner = onDemandMarkData.resourceOwner,
      projectedDeletionStamp = onDemandMarkData.projectedDeletionStamp,
      notificationInfo = onDemandMarkData.notificationInfo,
      lastSeenInfo = onDemandMarkData.lastSeenInfo
    )

    log.debug("Marking resource $resourceId in namespace ${workConfiguration.namespace} without checking rules.")
    resourceRepository.upsert(markedResource)
    applicationEventPublisher.publishEvent(MarkResourceEvent(markedResource, workConfiguration))
  }

  override fun deleteResource(resourceId: String, workConfiguration: WorkConfiguration) {
    val markedResource = resourceRepository.find(resourceId, workConfiguration.namespace)
      ?: throw NotFoundException("Resource $resourceId not found in namespace ${workConfiguration.namespace}")

    log.debug("Deleting resource $resourceId in namespace ${workConfiguration.namespace} without checking rules.")
    deleteResources(listOf(markedResource), workConfiguration)
  }

  /**
   * @param validMarkedResources: a list of resources that were just marked in the account/location defined in
   *  the work configuration
   * @param workConfiguration: defines the account/location to work with
   *
   * Fetches already marked resources, filters by work configuration namespace, and un-marks any resource whos id
   * is not present in validMarkedResources, up to the configured limit of the number to process.
   */
  private fun unmarkResources(
    validMarkedResources: Set<String>,
    workConfiguration: WorkConfiguration
  ) {
    val markedResourcesInNamespace = resourceRepository
      .getMarkedResources()
      .filter { it.namespace == workConfiguration.namespace }
    log.info("Checking for resources to unmark: " +
      "fetched ${markedResourcesInNamespace.size} marked resources from the database in ${workConfiguration.namespace}," +
      " ${validMarkedResources.size} resources were qualified to be marked this cycle.")
    if (validMarkedResources.size < markedResourcesInNamespace.size / 2) {
      log.warn("Number of resources qualified for marking is less than half the number in the database.")
    }

    var count = 0
    for (resource in markedResourcesInNamespace) {
      if (!validMarkedResources.contains(resource.resourceId)) {
        ensureResourceUnmarked(resource, workConfiguration, "Resource no longer qualifies to be deleted. Details: ${resource.resource.details}")
        count += 1
        if (count >= swabbieProperties.maxUnmarkedPerCycle) {
          log.warn("Unmarked ${swabbieProperties.maxUnmarkedPerCycle} resources (max allowed) in $javaClass. Aborting.")
          return
        }
      }
    }
  }

  private fun doMark(workConfiguration: WorkConfiguration) {
    // initialize counters
    exclusionCounters[Action.MARK] = AtomicInteger(0)
    val timerId = markDurationTimer.start()
    val candidateCounter = AtomicInteger(0)
    val violationCounter = AtomicInteger(0)
    val totalResourcesVisitedCounter = AtomicInteger(0)
    val markedResources = mutableListOf<MarkedResource>()
    val validMarkedIds = mutableSetOf<String>()

    try {
      log.info("${javaClass.simpleName} running. Configuration: {}", workConfiguration.namespace)
      val candidates: List<T>? = getCandidates(workConfiguration)
      if (candidates == null || candidates.isEmpty()) {
        return
      }
      log.info("Fetched {} resources. Configuration: {}", candidates.size, workConfiguration.toLog())
      totalResourcesVisitedCounter.set(candidates.size)

      val markedCandidates: List<MarkedResource> = resourceRepository.getMarkedResources()
        .filter { it.namespace == workConfiguration.namespace }

      val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
        .filter { it.markedResource.namespace == workConfiguration.namespace && it.optedOut }

      val preProcessedCandidates = candidates
        .withResolvedOwners(workConfiguration)
        .also { preProcessCandidates(it, workConfiguration) }
        .filter { !shouldExcludeResource(it, workConfiguration, optedOutResourceStates, Action.MARK) }

      val maxItemsToProcess = min(preProcessedCandidates.size, workConfiguration.getMaxItemsProcessedPerCycle(dynamicConfigService))

      for (candidate in preProcessedCandidates) {
        if (candidateCounter.get() >= maxItemsToProcess) {
          log.info("Max items ({}) to process reached, short-circuiting", maxItemsToProcess)
          break
        }

        try {
          val violations: List<Summary> = candidate.getViolations()
          val alreadyMarkedCandidate = markedCandidates.find { it.resourceId == candidate.resourceId }
          when {
            violations.isEmpty() -> {
              ensureResourceUnmarked(alreadyMarkedCandidate,
                workConfiguration,
                "Resource no longer qualifies for deletion")
            }
            alreadyMarkedCandidate == null -> {
              val newMarkedResource = MarkedResource(
                resource = candidate,
                summaries = violations,
                namespace = workConfiguration.namespace,
                resourceOwner = candidate.details[resourceOwnerField] as String,
                projectedDeletionStamp = deletionTimestamp(workConfiguration),
                lastSeenInfo = resourceUseTrackingRepository.getLastSeenInfo(candidate.resourceId)
              )

              if (!workConfiguration.dryRun) {
                // todo eb: should this upsert event happen in ResourceTrackingManager?
                resourceRepository.upsert(newMarkedResource)
                applicationEventPublisher.publishEvent(MarkResourceEvent(newMarkedResource, workConfiguration))
                log.info("Marked resource {} for deletion", newMarkedResource)
              }

              candidateCounter.incrementAndGet()
              violationCounter.addAndGet(violations.size)
              markedResources.add(newMarkedResource)
              validMarkedIds.add(newMarkedResource.resourceId)
            }
            else -> {
              // already marked, skipping.
              log.debug("Already marked resource " + alreadyMarkedCandidate.resourceId + " ...skipping")
              validMarkedIds.add(alreadyMarkedCandidate.resourceId)
            }
          }
        } catch (e: Exception) {
          log.error("Failed while invoking ${javaClass.simpleName}", e)
          recordFailureForAction(Action.MARK, workConfiguration, e)
        }
      }

      try {
        unmarkResources(validMarkedIds, workConfiguration)
      } catch (e: Exception) {
        log.error("Failed to unmark resources {}", validMarkedIds, e)
        recordFailureForAction(Action.UNMARK, workConfiguration, e)
      }

      printResult(candidateCounter, totalResourcesVisitedCounter, workConfiguration, markedResources, Action.MARK)
    } finally {
      recordMarkMetrics(
        timerId,
        workConfiguration,
        violationCounter,
        candidateCounter,
        totalResourcesVisitedCounter,
        resourceRepository.getNumMarkedResources()
      )
    }
  }

  override fun recalculateDeletionTimestamp(namespace: String, retentionSeconds: Long, numResources: Int) {
    val newTimestamp = deletionTimestamp(retentionSeconds)
    log.info("Updating deletion time to $newTimestamp for $numResources resources in ${javaClass.simpleName}.")

    val markedResources = resourceRepository.getMarkedResources()
      .filter { it.namespace == namespace }
      .sortedBy { it.resource.createTs }

    var countUpdated = 0
    markedResources.forEach { resource ->
      if (resource.projectedDeletionStamp > newTimestamp && countUpdated < numResources) {
        resource.projectedDeletionStamp = newTimestamp
        resourceRepository.upsert(resource)
        countUpdated += 1
      }
    }

    log.info("Updating deletion time to $newTimestamp complete for $countUpdated resources")
  }

  override fun evaluateCandidate(resourceId: String, resourceName: String, workConfiguration: WorkConfiguration): ResourceEvaluation {
    val candidate = getCandidate(resourceId, resourceName, workConfiguration) ?: return ResourceEvaluation(
      workConfiguration.namespace,
      resourceId,
      false,
      "Resource does not exist in the given namespace",
      emptyList()
    )

    val resourceState = resourceStateRepository.get(resourceId, workConfiguration.namespace)
    if (resourceState != null && resourceState.optedOut) {
      return ResourceEvaluation(
        workConfiguration.namespace,
        resourceId,
        false,
        "Resource has been opted out",
        emptyList()
      )
    }

    val candidates = listOf(candidate)
      .withResolvedOwners(workConfiguration)
      .filter { !shouldExcludeResource(it, workConfiguration, emptyList(), Action.MARK) }

    if (candidates.isEmpty()) {
      return ResourceEvaluation(
        workConfiguration.namespace,
        resourceId,
        false,
        "Resource has been excluded from marking",
        emptyList()
      )
    }

    val preprocessedCandidate = preProcessCandidates(candidates, workConfiguration).first()
    val wouldMark = preprocessedCandidate.getViolations().isNotEmpty()
    return ResourceEvaluation(
      workConfiguration.namespace,
      resourceId,
      wouldMark,
      if (wouldMark) "Resource has violations" else "Resource does not have violations",
      preprocessedCandidate.getViolations()
    )
  }

  private fun ensureResourceUnmarked(
    markedResource: MarkedResource?,
    workConfiguration: WorkConfiguration,
    reason: String
  ) {
    if (markedResource != null && !workConfiguration.dryRun) {
      try {
        log.debug("{} is no longer a candidate because: $reason.", markedResource.uniqueId())
        resourceRepository.remove(markedResource)
        applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource, workConfiguration))
      } catch (e: Exception) {
        log.error("Failed to unmark resource {}", markedResource, e)
      }
    }
  }

  fun deletionTimestamp(workConfiguration: WorkConfiguration): Long {
    val daysInFuture = workConfiguration.retention.toLong()
    val seconds = TimeUnit.DAYS.toSeconds(daysInFuture)
    return deletionTimestamp(seconds)
  }

  private fun deletionTimestamp(retentionSeconds: Long): Long {
    val proposedTime = clock.instant().plusSeconds(retentionSeconds).toEpochMilli()
    return swabbieProperties.schedule.getNextTimeInWindow(proposedTime)
  }

  private fun printResult(
    candidateCounter: AtomicInteger,
    totalResourcesVisitedCounter: AtomicInteger,
    workConfiguration: WorkConfiguration,
    markedResources: List<MarkedResource>,
    action: Action
  ) {
    val totalProcessed = min(workConfiguration.getMaxItemsProcessedPerCycle(dynamicConfigService), totalResourcesVisitedCounter.get())
    PolledMeter.using(registry)
      .withId(numberOfCandidatesId)
      .withTag(BasicTag("resourceType", workConfiguration.resourceType))
      .monitorValue(candidateCounter.get())

    log.info("${action.name} Summary: {} candidates out of {} processed. {} scanned. {} excluded. Configuration: {}",
      candidateCounter.get(),
      totalProcessed,
      totalResourcesVisitedCounter.get(),
      exclusionCounters[action],
      workConfiguration.toLog()
    )

    if (candidateCounter.get() > 0) {
      log.debug("${action.name} Result. Configuration: {}", workConfiguration.toLog())
      markedResources.forEach {
        log.debug(it.toString())
      }
    }
  }

  private fun shouldExcludeResource(
    resource: T,
    workConfiguration: WorkConfiguration,
    optedOutResourceStates: List<ResourceState>,
    action: Action
  ): Boolean {
    if (resource.getViolations().any { summary -> summary.ruleName == AlwaysCleanRule::class.java.simpleName }) {
      return false
    }

    val creationDate = Instant.ofEpochMilli(resource.createTs).atZone(ZoneId.systemDefault()).toLocalDate()
    if (action != Action.NOTIFY && DAYS.between(creationDate, LocalDate.now()) < workConfiguration.maxAge) {
      log.debug("Excluding resource (newer than {} days) {}", workConfiguration.maxAge, resource)
      exclusionCounters[action]?.incrementAndGet()
      return true
    }

    optedOutResourceStates.find { it.markedResource.resourceId == resource.resourceId }?.let {
      log.debug("Skipping Opted out resource {}", resource)
      exclusionCounters[action]?.incrementAndGet()
      return true
    }

    return shouldExclude(resource, workConfiguration, exclusionPolicies, log).also { excluded ->
      if (excluded) {
        exclusionCounters[action]?.incrementAndGet()
      }
    }
  }

  private fun doDelete(workConfiguration: WorkConfiguration) {
    exclusionCounters[Action.DELETE] = AtomicInteger(0)
    val candidateCounter = AtomicInteger(0)
    val markedResources = mutableListOf<MarkedResource>()
    if (workConfiguration.dryRun) {
      log.info("Deletion not enabled in dryRun for {}. Skipping...", workConfiguration.toLog())
      return
    }

    val currentMarkedResourcesToDelete = workConfiguration.getMarkedResourcesToDelete()
    if (currentMarkedResourcesToDelete.isEmpty()) {
      log.info("Nothing to delete. Configuration: {}", workConfiguration.toLog())
      return
    }

    val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
      .filter { it.markedResource.namespace == workConfiguration.namespace && it.optedOut }

    val candidates: List<T>? = getCandidates(workConfiguration)
    if (candidates == null) {
      currentMarkedResourcesToDelete
        .forEach { ensureResourceUnmarked(it, workConfiguration, "No current candidates for deletion") }
      log.info("Nothing to delete. No upstream resources. Configuration: {}", workConfiguration.toLog())
      return
    }

    val processedCandidates = candidates
      .filter { candidate ->
        currentMarkedResourcesToDelete.any { r ->
          candidate.resourceId == r.resourceId
        }
      }
      .withResolvedOwners(workConfiguration)
      .also { preProcessCandidates(it, workConfiguration) }

    val confirmedResourcesToDelete = currentMarkedResourcesToDelete.filter { resource ->
      var shouldSkip = false
      val candidate = processedCandidates.find { it.resourceId == resource.resourceId }
      if ((candidate == null || candidate.getViolations().isEmpty()) ||
        shouldExcludeResource(candidate, workConfiguration, optedOutResourceStates, Action.DELETE)) {
        shouldSkip = true
        ensureResourceUnmarked(resource, workConfiguration, "Resource no longer qualifies for deletion")
      }
      !shouldSkip
    }

    val maxItemsToProcess = min(confirmedResourcesToDelete.size, workConfiguration.getMaxItemsProcessedPerCycle(dynamicConfigService))
    confirmedResourcesToDelete.subList(0, maxItemsToProcess).let {
      partitionList(it, workConfiguration).forEach { partition ->
        if (!partition.isEmpty()) {
          try {
            deleteResources(partition, workConfiguration)
            partition.forEach { it -> applicationEventPublisher.publishEvent(DeleteResourceEvent(it, workConfiguration)) }
            candidateCounter.addAndGet(partition.size)
          } catch (e: Exception) {
            log.error("Failed to delete $it. Configuration: {}", workConfiguration.toLog(), e)
            recordFailureForAction(Action.DELETE, workConfiguration, e)
          }
        }
      }
    }

    printResult(
      candidateCounter,
      AtomicInteger(currentMarkedResourcesToDelete.size),
      workConfiguration,
      markedResources,
      Action.DELETE
    )
  }

  /**
   * Partitions the list of marked resources to process:
   * For convenience, partitions are grouped by relevance
   */
  fun partitionList(
    markedResources: List<MarkedResource>,
    configuration: WorkConfiguration
  ): List<List<MarkedResource>> {
    val partitions = mutableListOf<List<MarkedResource>>()
    markedResources.groupBy {
      it.resource.grouping
    }.map {
      Lists.partition(it.value, configuration.itemsProcessedBatchSize).forEach { partition ->
        partitions.add(partition)
      }
    }

    return partitions.sortedByDescending { it.size }
  }

  private fun T.getViolations(): List<Summary> {
    return rules.mapNotNull {
      it.apply(this).summary
    }
  }

  /**
   * Adds the owner to the resourceOwnerField, for each candidate, and if no owner is found it's resolved to the
   * "defaultDestination", defined in config
   */
  private fun List<T>.withResolvedOwners(workConfiguration: WorkConfiguration): List<T> =
    this.map { candidate ->
      candidate.apply {
        set(
          resourceOwnerField,
          ownerResolver.resolve(candidate) ?: workConfiguration.notificationConfiguration.defaultDestination
        )
      }
    }

  /**
   * @return Marked resources meeting the deletion criteria:
   * - They are due for deletion
   * - The have been notified on if notifications are enabled
   */
  private fun WorkConfiguration.getMarkedResourcesToDelete(): List<MarkedResource> {
    return resourceRepository.getMarkedResourcesToDelete()
      .filter {
        it.namespace == namespace && (!notificationConfiguration.enabled || it.notificationInfo != null)
      }
      .sortedBy { it.resource.createTs }
  }

  /**
   * @return Marked resources to notify on
   */
  private fun WorkConfiguration.getMarkedResourcesToNotify(): List<MarkedResource> {
    return resourceRepository.getMarkedResources()
      .filter {
        namespace == it.namespace && (it.notificationInfo == null)
      }
      .sortedBy { it.resource.createTs }
  }

  /**
   * Filters out excluded and opted out resources.
   * Returns at most [WorkConfiguration.maxItemsProcessedPerCycle] resources
   */
  private fun List<MarkedResource>.filterExcludedAndOptedOut(
    workConfiguration: WorkConfiguration
  ): List<MarkedResource> {
    val optedOutResourceStates: List<ResourceState> = resourceStateRepository.getAll()
      .filter {
        it.markedResource.namespace == workConfiguration.namespace && it.optedOut
      }
    val maxItemsToProcess = min(size, workConfiguration.getMaxItemsProcessedPerCycle(dynamicConfigService))
    return subList(0, maxItemsToProcess)
      .filter {
        @Suppress("UNCHECKED_CAST")
        !shouldExcludeResource(it.resource as T, workConfiguration, optedOutResourceStates, Action.NOTIFY)
      }
  }

  private val Int.days: Period
    get() = Period.ofDays(this)

  private val Period.fromNow: LocalDate
    get() = LocalDate.now(clock) + this
}
