/* Copyright 2018 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.messaging.command.switches;

/**
 * Describes what to do about the switch default rules.
 */
public enum DeleteRulesAction {
    // Drop all rules
    DROP,

    // Drop all rules, add back in the base default rules
    DROP_ADD,

    // Don't drop the default rules, but do drop everything else
    IGNORE,

    // Drop all non-base rules (ie IGNORE), and add base rules back (eg overwrite)
    OVERWRITE,

    // Drop a single rule
    ONE,

    // Drop just the default / base drop rule
    REMOVE_DROP,

    // Drop just the verification (broadcast) rule only
    REMOVE_BROADCAST,

    // Drop just the verification (unicast) rule only
    REMOVE_UNICAST,

    // Drop all default rules (ie a combination of the above)
    REMOVE_DEFAULTS,

    // Drop the default, add them back .. presumably a good way to ensure the defaults are there
    REMOVE_ADD
}

