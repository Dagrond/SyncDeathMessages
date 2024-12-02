package org.me.syncdeathmessages.handler;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.me.syncdeathmessages.SyncDeathMessages;
import org.me.syncdeathmessages.redis.RedisPublisher;
import org.me.syncdeathmessages.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Pattern;

public class DeathMessageHandler {
    private final SyncDeathMessages plugin;
    private final RedisPublisher redisPublisher;

    public DeathMessageHandler(SyncDeathMessages plugin, RedisPublisher redisPublisher) {
        this.plugin = plugin;
        this.redisPublisher = redisPublisher;
    }

    public void handleDeathEvent(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player.hasMetadata("NPC")) return; // ignore NPCs
        Player killer = player.getKiller();

        plugin.getFoliaLib().getImpl().runAsync((WrappedTask task) -> {
            String playerName = player.getDisplayName();

            if (killer != null) {
                handlePlayerKill(playerName, killer);
            } else {
                if (event.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent) {
                    handleEntityAttack(playerName, damageEvent, event);
                } else {
                    handleOtherDeaths(playerName, event);
                }
            }
        });
    }

    private void handlePlayerKill(String playerName, Player killer) {
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        boolean hasWeapon = weapon.getType() != Material.AIR;
        String messageKey = "messages.PLAYER_KILL." + (hasWeapon ? "with_weapon" : "default");
        List<String> messages = plugin.getConfig().getStringList(messageKey);

        String message = getRandomMessage(messages);
        if (message != null) {
            message = message.replace("{player}", playerName).replace("{killer}", killer.getDisplayName());
            if (hasWeapon) {
                String weaponKey = weapon.hasItemMeta() && Objects.requireNonNull(weapon.getItemMeta()).hasDisplayName() ? weapon.getItemMeta().getDisplayName() : weapon.getTranslationKey();
                TranslatableComponent weaponComponent = new TranslatableComponent(weaponKey);

                if (weapon.hasItemMeta() && Objects.requireNonNull(weapon.getItemMeta()).hasLore()) {
                    List<String> lore = weapon.getItemMeta().getLore();
                    StringBuilder hoverText = new StringBuilder(weaponKey);
                    if (lore != null) {
                        for (String line : lore) {
                            hoverText.append("\n").append(line);
                        }
                    }
                    weaponComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverText.toString())));
                }
                sendTranslatableMessage(weaponComponent, message, "{weapon}");
            } else {
                sendJsonMessage(message);
            }
        }
    }

    private void handleEntityAttack(String playerName, EntityDamageByEntityEvent damageEvent, PlayerDeathEvent deathEvent) {
        Entity damager = damageEvent.getDamager();
        List<String> messages = plugin.getConfig().getStringList("messages.ENTITY_ATTACK.default");
        if (deathEvent.getDeathMessage() != null && deathEvent.getDeathMessage().contains("☠")) {
            sendJsonMessage(deathEvent.getDeathMessage());
            return;
        }
        String message = getRandomMessage(messages);
        if (message != null) {
            message = message.replace("{player}", playerName);
            if (damager.getCustomName() != null) {
                message = message.replace("{mob}", damager.getCustomName());
                sendJsonMessage(message);
            } else {
                TranslatableComponent mobComponent = new TranslatableComponent("entity.minecraft." + damager.getType().getKey().getKey());
                sendTranslatableMessage(mobComponent, message, "{mob}");
            }
        }
    }


    private void handleOtherDeaths(String playerName, PlayerDeathEvent event) {
        String causeOfDeathKey = event.getEntity().getLastDamageCause() != null ? event.getEntity().getLastDamageCause().getCause().name() : "UNKNOWN";
        List<String> messages = plugin.getConfig().getStringList("messages." + causeOfDeathKey + ".default");
        String message = getRandomMessage(messages);
        if (message != null) {
            sendJsonMessage(message.replace("{player}", playerName));
        }
    }

    private void sendTranslatableMessage(BaseComponent translateComponent, String messageTemplate, String placeholder) {
        List<BaseComponent> components = new ArrayList<>();
        String[] parts = messageTemplate.split(Pattern.quote(placeholder));
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                components.add(translateComponent);
            }
            components.add(new TextComponent(Utils.colorize(parts[i])));
        }
        sendJsonMessage(components);
    }

    private void sendJsonMessage(String message) {
        if (message != null) {
            String jsonMessage = ComponentSerializer.toString(new TextComponent(Utils.colorize(message)));
            redisPublisher.publishToChannel(jsonMessage);
        }
    }

    private void sendJsonMessage(List<BaseComponent> components) {
        String jsonMessage = ComponentSerializer.toString(components.toArray(new BaseComponent[0]));
        redisPublisher.publishToChannel(jsonMessage);
    }

    private String getRandomMessage(List<String> messages) {
        if (messages.isEmpty()) return null;
        return messages.get(new Random().nextInt(messages.size()));
    }
}
