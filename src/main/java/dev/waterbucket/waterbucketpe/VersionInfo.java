/*
 * Copyright 2022 WaterbucketTEAM
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

package dev.waterbucket.waterbucketpe;

import dev.waterbucket.waterbucketpe.network.protocol.ProtocolVersion;
import org.apache.logging.log4j.Level;

public class VersionInfo {

    public static final boolean DEFAULT_DEBUG = true;

    /**
     * Fallback only: when the jar carries no git.properties (a bare source build), the panel
     * and startup log would otherwise keep advertising the last version this constant was
     * touched. Release jars report the gradle project version instead.
     */
    private final String baseVersion = "1.3.0";
    private final String buildVersion = "#build";
    private final String author = "WaterBucketTEAM";
    private final int metricsId = 15678;
    private final int latestProtocolVersion;

    private final String branchName;
    private final String commitId;
    private final String projectVersion;
    private boolean debug;

    public VersionInfo(String branchName, String commitId, String projectVersion, boolean debug) {
        this.branchName = branchName;
        this.commitId = commitId;
        this.projectVersion = projectVersion != null ? projectVersion : this.baseVersion;
        this.debug = debug;
        this.latestProtocolVersion = ProtocolVersion.latest().getProtocol();
    }

    public static VersionInfo unknown() {
        return new VersionInfo("unknown", "unknown", null, DEFAULT_DEBUG);
    }

    public String baseVersion() {
        // Prefer the gradle project version baked into git.properties over the hardcoded
        // fallback, so the panel and /wbinfo show the version actually being built.
        return this.projectVersion != null ? this.projectVersion : this.baseVersion;
    }

    public String buildVersion() {
        return this.buildVersion;
    }

    public int latestProtocolVersion() {
        return this.latestProtocolVersion;
    }

    public String author() {
        return this.author;
    }

    public String branchName() {
        return this.branchName;
    }

    public String commitId() {
        return this.commitId;
    }

    /**
     * The Gradle project version with the git branch and commit, as
     * "2.0.4-SNAPSHOT (git-master-05ec4eb)", or just the base version when the build carries no git info.
     */
    public String fullVersion() {
        if (this.commitId.equals("unknown")) {
            return this.baseVersion;
        }
        return this.projectVersion + " (git-" + this.branchName + "-" + this.commitId + ")";
    }

    public void debug(boolean debug) {
        // Apply the level symmetrically: debug(false) used to leave the root logger at DEBUG
        // while every consumer reported debugging as disabled.
        WaterbucketPE.setLoggerLevel(debug ? Level.DEBUG : Level.INFO);
        this.debug = debug;
    }

    public int metricsId() {
        return metricsId;
    }

    public boolean debug() {
        return this.debug;
    }
}
