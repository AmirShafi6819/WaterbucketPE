/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.plugin.python;

/**
 * Functional interface implemented by Python command handlers:
 * {@code handler(sender, alias, args) -> bool}.
 * <p>
 * GraalVM adapts a Python callable to this interface automatically when it is passed to
 * {@link PythonPlugin#registerCommand}.
 */
@FunctionalInterface
public interface PythonCommandExecutor {
    boolean execute(Object sender, String alias, Object[] args);
}
