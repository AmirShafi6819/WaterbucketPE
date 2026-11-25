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

package dev.waterbucket.waterbucketpe.plugin.compat;

import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.player.ProxiedPlayer;
import dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerKickEvent;
import dev.waterbucket.waterbucketpe.plugin.compat.event.PlayerLoginEvent;
import lombok.Getter;

/**
 * The compatibility player wrapper: every method here runs against the live proxy session and
 * does exactly what the same-named call of the original server software does where the proxy
 * can really do it - messaging, titles, popups, forms, chat, kick with its own cancellable
 * event, identity and permissions. World-touching calls of the original software (inventory,
 * movement, blocks) have no meaning on a proxy and are deliberately not faked here.
 * <p>
 * Wrappers are light views created on demand - identity and state stay live through the
 * underlying {@link ProxiedPlayer}, so a wrapper never goes stale: it reflects the connection
 * at every call.
 */
@Getter
public class Player {

    private final ProxiedPlayer handle;

    public Player(ProxiedPlayer handle) {
        this.handle = handle;
    }

    /**
     * The underlying proxy player this wrapper is a view of.
     */
    public ProxiedPlayer getHandle() {
        return this.handle;
    }

    public Server getServer() {
        return Server.getInstance();
    }

    /**
     * The player's display name as the client profile carries it.
     */
    public String getName() {
        return this.handle.getName();
    }

    /**
     * The name lowercased, the canonical lookup key.
     */
    public String getLowerCaseName() {
        return this.getName().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The Xbox Live identifier, empty for offline-mode profiles.
     */
    public String getXuid() {
        return this.handle.getXuid();
    }

    public java.util.UUID getUniqueId() {
        return this.handle.getUniqueId();
    }

    /**
     * The client's real remote IP (or the proxy's dial address when the transport hides it),
     * matching what the proxy itself sees on the session.
     */
    public String getAddress() {
        java.net.InetSocketAddress address = this.handle.getAddress();
        return address == null ? "" : address.getAddress() == null
                ? address.getHostString() : address.getAddress().getHostAddress();
    }

    public int getPort() {
        java.net.InetSocketAddress address = this.handle.getAddress();
        return address == null ? 0 : address.getPort();
    }

    /**
     * The name of the downstream server the player is on right now, or null between servers.
     */
    public String getServerName() {
        return this.handle.getServerInfo() == null ? null : this.handle.getServerInfo().getServerName();
    }

    public boolean isConnected() {
        return this.handle.isConnected();
    }

    /**
     * Alias of {@link #isConnected()} - on a proxy a player is online exactly when connected.
     */
    public boolean isOnline() {
        return this.isConnected();
    }

    /**
     * Sends a raw line to the player. {%N} placeholders are filled from the parameters, like
     * the translation containers of the original software.
     */
    public void sendMessage(String message, String... parameters) {
        if (parameters != null && parameters.length > 0) {
            for (int i = 0; i < parameters.length; i++) {
                message = message.replace("{%" + i + "}", parameters[i] == null ? "" : parameters[i]);
            }
        }
        this.handle.sendMessage(message);
    }

    public void sendTip(String message) {
        this.handle.sendTip(message);
    }

    public void sendPopup(String message, String subtitle) {
        this.handle.sendPopup(message, subtitle == null ? "" : subtitle);
    }

    public void sendPopup(String message) {
        this.sendPopup(message, "");
    }

    /**
     * Sends the small text above the hotbar.
     */
    public void sendActionBar(String message) {
        org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket packet =
                new org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket();
        packet.setType(org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket.Type.ACTIONBAR);
        packet.setText(message == null ? "" : message);
        this.handle.sendPacket(packet);
    }

    /**
     * Sends a title with a subtitle and the three animation times in ticks.
     */
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        this.handle.sendTitle(title == null ? "" : title, subtitle == null ? "" : subtitle,
                fadeIn, stay, fadeOut);
    }

    public void sendTitle(String title, String subtitle) {
        this.sendTitle(title, subtitle, 10, 70, 20);
    }

    public void sendTitle(String title) {
        this.sendTitle(title, "", 10, 70, 20);
    }

    /**
     * Sends a toast notification (title + short content in the notification tray).
     */
    public void sendToastMessage(String title, String content) {
        this.handle.sendToastMessage(title, content);
    }

    /**
     * Sends a server form from its raw JSON payload (the same JSON the original software's
     * form builders produce). Returns the form id the client answers with in its response.
     */
    public int sendFormJson(String formData) {
        return this.sendFormJson(formData, Server.getInstance().nextFormId());
    }

    public int sendFormJson(String formData, int formId) {
        org.cloudburstmc.protocol.bedrock.packet.ModalFormRequestPacket packet =
                new org.cloudburstmc.protocol.bedrock.packet.ModalFormRequestPacket();
        packet.setFormId(formId);
        packet.setFormData(formData == null ? "{}" : formData);
        this.handle.sendPacket(packet);
        return formId;
    }

    /**
     * Sends a chat line as if this player typed it: the text travels to the downstream server
     * through the player's own session, so plugins there and clients downstream handle it like
     * any player chat.
     */
    public void chat(String message) {
        this.handle.chat(message);
    }

    /**
     * Fires the raw proxy chat event shape for the player (plugins on the proxy-side pipeline
     * see it exactly like a typed chat) and returns the possibly edited text; cancelled events
     * return null.
     */
    public String dispatchChat(String message) {
        PlayerChatEvent event = new PlayerChatEvent(this.handle, message);
        Server.getInstance().getHandle().getEventManager().callEvent(event);
        if (event.isCancelled()) {
            return null;
        }
        return event.getMessage();
    }

    /**
     * Kicks the player with a cancellable {@link PlayerKickEvent}. The event can change the
     * quit message the other clients see; cancelling it keeps the player online and returns
     * false.
     *
     * @return true when the kick went through
     */
    public boolean kick(String reason, boolean isAdmin) {
        PlayerKickEvent event = new PlayerKickEvent(this, reason,
                "§e" + this.getName() + " left the game");
        Server.getInstance().getHandle().getEventManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        this.handle.disconnect(reason == null ? "Disconnected without reason" : reason);
        // The quit message the kick event carries is broadcast to whoever is left online, like
        // the original software does for its kick events.
        if (event.getQuitMessage() != null && !event.getQuitMessage().isEmpty()) {
            Server.getInstance().broadcastMessage(event.getQuitMessage());
        }
        return true;
    }

    public boolean kick(String reason) {
        return this.kick(reason, true);
    }

    // ---- permissions ----

    public boolean hasPermission(String node) {
        return this.handle.hasPermission(node);
    }

    public boolean addPermission(String node) {
        return this.handle.addPermission(node);
    }

    public boolean removePermission(String node) {
        return this.handle.removePermission(node);
    }

    // ---- login event helper (used by the compatibility bridge) ----

    static PlayerLoginEvent newLoginEvent(Player player) {
        return new PlayerLoginEvent(player, "§cYou are not allowed to join this server.");
    }
}
