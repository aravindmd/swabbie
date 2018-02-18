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

import com.netflix.spinnaker.swabbie.model.Named
import com.netflix.spinnaker.config.Exclusion
import com.netflix.spinnaker.config.ExclusionType
import org.springframework.stereotype.Component

interface ExclusionPolicy {
  fun apply(excludable: Excludable, exclusions: List<Exclusion>): Boolean
  fun String.matchPattern(p: String): Boolean =
    p.startsWith("pattern:") && this.contains(p.split(":").last().toRegex())

  fun values(exclusions: List<Exclusion>, type: ExclusionType): List<String> {
    return exclusions.filter {
      it.type.equals(type.name, true)
    }.map { exclusion ->
        exclusion.attributes.map { it.value }.flatten()
      }.flatten()
  }
}

interface Excludable: Named {
  fun shouldBeExcluded(exclusionPolicies: List<ExclusionPolicy>, exclusions: List<Exclusion>): Boolean
}

interface WorkConfigurationExclusionPolicy : ExclusionPolicy
interface ResourceExclusionPolicy: ExclusionPolicy

@Component
class LiteralExclusionPolicy: WorkConfigurationExclusionPolicy, ResourceExclusionPolicy {
  override fun apply(excludable: Excludable, exclusions: List<Exclusion>): Boolean =
    values(exclusions, ExclusionType.Literal).let { names ->
      if (names.size == 1 && names[0] == "\\*") {
        // wildcard
        return true
      }

      return names.find { it.equals(excludable.name, ignoreCase = true) || excludable.name.matchPattern(it) } != null
    }
}
