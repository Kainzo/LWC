/*
 * Copyright 2011 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */

package com.griefcraft.modules.limits;

import com.griefcraft.lwc.LWC;
import com.griefcraft.scripting.JavaModule;
import com.griefcraft.scripting.event.LWCProtectionRegisterEvent;
import com.griefcraft.util.config.Configuration;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class LimitsV2 extends JavaModule {

    /**
     * The limit represented by unlimited
     */
    private final static int UNLIMITED = Integer.MAX_VALUE;

    /**
     * The limits configuration
     */
    private final Configuration configuration = Configuration.load("limitsv2.yml");

    /**
     * A map of the default limits
     */
    private final List<Limit> defaultLimits = new LinkedList<Limit>();

    /**
     * A map of all of the player limits
     */
    private final Map<String, List<Limit>> playerLimits = new HashMap<String, List<Limit>>();

    /**
     * A map of all of the group limits - downcasted to lowercase to simplify comparisons
     */
    private final Map<String, List<Limit>> groupLimits = new HashMap<String, List<Limit>>();

    abstract class Limit {

        /**
         * The limit
         */
        private final int limit;

        public Limit(int limit) {
            this.limit = limit;
        }

        /**
         * Get the player's protection count that should be used with this limit
         *
         * @param player
         * @param material
         * @return
         */
        public abstract int getProtectionCount(Player player, Material material);

        /**
         * @return
         */
        public int getLimit() {
            return limit;
        }
    }
    
    final class DefaultLimit extends Limit {

        public DefaultLimit(int limit) {
            super(limit);
        }
        
        @Override
        public int getProtectionCount(Player player, Material material) {
            return LWC.getInstance().getPhysicalDatabase().getProtectionCount(player.getName());
        }

    }
    
    final class BlockLimit extends Limit {

        /**
         * The block material to limit
         */
        private final Material material;

        public BlockLimit(Material material, int limit) {
            super(limit);
            this.material = material;
        }

        @Override
        public int getProtectionCount(Player player, Material material) {
            return LWC.getInstance().getPhysicalDatabase().getProtectionCount(player.getName(), material.getId());
        }

        /**
         * @return
         */
        public Material getMaterial() {
            return material;
        }

    }
    
    public LimitsV2() {
        loadLimits();
    }

    @Override
    public void onRegisterProtection(LWCProtectionRegisterEvent event) {
        if (event.isCancelled()) {
            return;
        }

        LWC lwc = event.getLWC();
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (hasReachedLimit(player, block.getType())) {
            lwc.sendLocale(player, "protection.exceeded");
            event.setCancelled(true);
        }
    }

    /**
     * Checks if a player has reached their protection limit
     *
     * @param player
     * @param material the material type the player has interacted with
     * @return
     */
    public boolean hasReachedLimit(Player player, Material material) {
        LWC lwc = LWC.getInstance();
        Limit limit = getEffectiveLimit(player, material);

        // if they don't have a limit it's not possible to reach it ^^
        //  ... but if it's null, what the hell did the server owner do?
        if (limit == null) {
            return false;
        }

        // Get the effective limit placed upon them
        int neverPassThisNumber = limit.getLimit();

        // get the amount of protections the player has
        int protections = limit.getProtectionCount(player, material);

        return protections >= neverPassThisNumber;
    }

    /**
     * Get the player's effective limit that should take precedence
     *
     * @param player
     * @param material
     * @return
     */
    public Limit getEffectiveLimit(Player player, Material material) {
        LWC lwc = LWC.getInstance();

        // The limit that was found
        Limit found;

        // First check for a player override
        found = getEffectiveLimit(playerLimits.get(player.getName().toLowerCase()), material);

        // begin going down the chain of precedence
        if (found == null) {
            // Go through the players groups and check each one
            for (String group : lwc.getPermissions().getGroups(player)) {
                found = getEffectiveLimit(groupLimits.get(group), material);

                if (found != null) {
                    break;
                }
            }

            // If all else fails, use the default limit
            if (found == null) {
                found = getEffectiveLimit(defaultLimits, material);
            }
        }

        return found;
    }

    /**
     * Gets the material's effective limit that should take precedence
     *
     * @param limits
     * @param material
     * @return Limit object if one is found otherwise NULL
     */
    private Limit getEffectiveLimit(List<Limit> limits, Material material) {
        if (limits == null) {
            return null;
        }

        // Temporary storage to use if the default is found so we save time if no override was found
        Limit defaultLimit = null;

        for (Limit limit : limits) {
            // Record the default limit if found
            if (limit instanceof DefaultLimit) {
                defaultLimit = limit;
            } else if (limit instanceof BlockLimit) {
                BlockLimit blockLimit = (BlockLimit) limit;

                // If it's the appropriate material we can return immediately
                if (blockLimit.getMaterial() == material) {
                    return blockLimit;
                }
            }
        }

        return defaultLimit;
    }

    /**
     * Load all of the limits
     */
    private void loadLimits() {
        // make sure we're working on a clean slate
        defaultLimits.clear();
        playerLimits.clear();
        groupLimits.clear();

        // add the default limits
        defaultLimits.addAll(findLimits("defaults"));

        // add all of the player limits
        for (String player : configuration.getKeys("players")) {
            System.out.println("PLAYER => " + player);
            playerLimits.put(player.toLowerCase(), findLimits("players." + player));
        }

        // add all of the group limits
        for (String group : configuration.getKeys("groups")) {
            System.out.println("GROUP => " + group);
            groupLimits.put(group, findLimits("groups." + group));
        }
    }

    /**
     * Find and match all of the limits in a given list of nodes for the config
     *
     * @param node
     * @return
     */
    private List<Limit> findLimits(String node) {
        List<Limit> limits = new LinkedList<Limit>();
        List<String> keys = configuration.getKeys(node);
        
        for (String key : keys) {
            String value = configuration.getString(node + "." + key);
            System.out.println(key + " => " + value);

            int limit;
            
            if (value.equalsIgnoreCase("unlimited")) {
                limit = UNLIMITED;
            } else {
                limit = Integer.parseInt(value);
            }

            // Match default
            if (key.equalsIgnoreCase("default")) {
                limits.add(new DefaultLimit(limit));
                System.out.println("\tdefault => " + limit);
            } else {
                Material material = Material.getMaterial(key.toUpperCase());
                limits.add(new BlockLimit(material, limit));
                System.out.println("\t" + material + " => " + limit);
            }
        }

        return limits;
    }

}